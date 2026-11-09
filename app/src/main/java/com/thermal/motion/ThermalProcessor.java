package com.thermal.motion;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns raw sensor frames into a calibrated, colorized image and detects motion.
 *
 * Key idea for the shutter (FFC) problem: when a new flat-field frame arrives,
 * the per-pixel offset of every later image changes by exactly (oldFfc - newFfc).
 * We apply that same delta to the motion background model, so detection keeps
 * running across the shutter event instead of resetting or firing false alarms.
 */
public class ThermalProcessor {

    public static final int W = 206, H = 156, N = W * H;
    static final int RW = SeekCamera.RAW_W;

    public static class Result {
        public int[] argb;
        public List<Rect> boxes = new ArrayList<>();
        public boolean calibrated;
        public boolean shutterActive;
        public float fps;
        public int ffcCount;
        public long lastFfcInterval;
        public long msSinceFfc;
        public float noise;
        public int motionPixels;
        public long gapMs;        // blind time of the shutter event that just ended (0 otherwise)
    }

    // Settings (written from UI thread)
    public volatile float sensitivity = 6f;  // threshold in noise sigmas
    public volatile int minArea = 6;
    public volatile boolean whiteHot = false;
    public volatile boolean invert = false;

    private final int[] ffc = new int[N];
    private boolean haveFfc;
    private final boolean[] deadFrame4 = new boolean[N];
    private boolean haveFrame4;
    private final boolean[] dead = new boolean[N];
    private final int[] cal = new int[N];
    private float[] bg;
    private int warmup;
    private final boolean[] mask = new boolean[N];
    private final boolean[] clean = new boolean[N];
    private final int[] label = new int[N];
    private final int[] queue = new int[N];
    private float lo = 0, hi = 1;
    private boolean agcInit;
    private final int[] hist = new int[65536];

    private long lastFfcTime, lastFrameTime;
    private long ffcInterval;
    private int ffcCount;
    private float fps;
    private long shutterUntil;
    private long gapStart;
    private List<Rect> lastBoxes = new ArrayList<>();
    private int[] lastArgb;

    static final int[] IRON = buildLut(new int[][]{
            {0, 0, 0}, {30, 0, 90}, {110, 0, 150}, {190, 20, 120},
            {235, 80, 30}, {255, 160, 0}, {255, 230, 90}, {255, 255, 255}});
    static final int[] GRAY = buildLut(new int[][]{{0, 0, 0}, {255, 255, 255}});

    static int[] buildLut(int[][] stops) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float p = i / 255f * (stops.length - 1);
            int a = Math.min((int) p, stops.length - 2);
            float t = p - a;
            int r = (int) (stops[a][0] + t * (stops[a + 1][0] - stops[a][0]));
            int g = (int) (stops[a][1] + t * (stops[a + 1][1] - stops[a][1]));
            int b = (int) (stops[a][2] + t * (stops[a + 1][2] - stops[a][2]));
            lut[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return lut;
    }

    public void resetBackground() { bg = null; }

    /** Returns a result to display, or null if nothing new should be shown. */
    public Result process(short[] raw, int id, long now) {
        switch (id) {
            case 4: onFrame4(raw); return null;
            case 1: onFfc(raw, now); return holdResult(now);
            case 3: return onImage(raw, now);
            default: return null;
        }
    }

    private static int px(short[] raw, int x, int y) { return raw[y * RW + x] & 0xffff; }

    private void onFrame4(short[] raw) {
        // Pixel-map frame: dead pixels read far below the typical value.
        int[] v = new int[N];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) v[y * W + x] = px(raw, x, y);
        int[] s = v.clone();
        Arrays.sort(s);
        int med = s[N / 2];
        int cnt = 0;
        for (int i = 0; i < N; i++) { deadFrame4[i] = v[i] < med * 0.4; if (deadFrame4[i]) cnt++; }
        haveFrame4 = cnt < N / 20;  // ignore if implausible (>5% dead)
        rebuildDead();
    }

    private void onFfc(short[] raw, long now) {
        int[] nf = new int[N];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) nf[y * W + x] = px(raw, x, y);
        if (haveFfc && bg != null) {
            // Shift background by exactly the change in offset correction.
            for (int i = 0; i < N; i++) bg[i] += ffc[i] - nf[i];
        }
        System.arraycopy(nf, 0, ffc, 0, N);
        // Only count a new event if the previous shutter frame was a while ago.
        if (now - lastFfcTime > 1500) {
            if (lastFfcTime > 0) ffcInterval = now - lastFfcTime;
            ffcCount++;
            if (gapStart == 0) gapStart = lastFrameTime;
        }
        lastFfcTime = now;
        shutterUntil = now + 400;
        haveFfc = true;
        agcInit = false;
        rebuildDead();
    }

    private void rebuildDead() {
        Arrays.fill(dead, false);
        if (haveFrame4) System.arraycopy(deadFrame4, 0, dead, 0, N);
        if (!haveFfc) return;
        // Pixels whose FFC value deviates strongly from their neighbours are bad.
        float[] res = new float[N];
        for (int y = 1; y < H - 1; y++)
            for (int x = 1; x < W - 1; x++) {
                int i = y * W + x;
                float nb = (ffc[i - 1] + ffc[i + 1] + ffc[i - W] + ffc[i + W]) / 4f;
                res[i] = Math.abs(ffc[i] - nb);
            }
        float[] sample = new float[N / 8];
        for (int k = 0; k < sample.length; k++) sample[k] = res[k * 8];
        Arrays.sort(sample);
        float mad = Math.max(sample[sample.length / 2], 1f);
        for (int i = 0; i < N; i++) if (res[i] > 12 * mad || ffc[i] == 0 || ffc[i] == 0xffff) dead[i] = true;
    }

    private Result holdResult(long now) {
        Result r = baseResult(now);
        r.shutterActive = true;
        r.argb = lastArgb;
        r.boxes = lastBoxes;
        return lastArgb == null ? null : r;
    }

    private Result baseResult(long now) {
        Result r = new Result();
        r.calibrated = haveFfc;
        r.ffcCount = ffcCount;
        r.lastFfcInterval = ffcInterval;
        r.msSinceFfc = lastFfcTime > 0 ? now - lastFfcTime : -1;
        r.fps = fps;
        return r;
    }

    private Result onImage(short[] raw, long now) {
        if (lastFrameTime > 0 && gapStart == 0) {
            float inst = 1000f / Math.max(1, now - lastFrameTime);
            fps = fps == 0 ? inst : fps * 0.9f + inst * 0.1f;
        }
        lastFrameTime = now;

        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                cal[i] = px(raw, x, y) - (haveFfc ? ffc[i] : 0);
            }
        fillDead();

        Result r = baseResult(now);
        if (gapStart > 0) { r.gapMs = now - gapStart; gapStart = 0; }
        r.shutterActive = now < shutterUntil;
        if (haveFfc) detectMotion(r); else bg = null;
        r.argb = colorize();
        lastArgb = r.argb;
        lastBoxes = r.boxes;
        return r;
    }

    private void fillDead() {
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (!dead[i]) continue;
                int sum = 0, n = 0;
                if (x > 0 && !dead[i - 1]) { sum += cal[i - 1]; n++; }
                if (x < W - 1 && !dead[i + 1]) { sum += cal[i + 1]; n++; }
                if (y > 0 && !dead[i - W]) { sum += cal[i - W]; n++; }
                if (y < H - 1 && !dead[i + W]) { sum += cal[i + W]; n++; }
                if (n > 0) cal[i] = sum / n;
            }
    }

    private int[] colorize() {
        Arrays.fill(hist, 0);
        for (int i = 0; i < N; i++) hist[Math.max(0, Math.min(65535, cal[i] + 32768))]++;
        int acc = 0, pLo = 0, pHi = 65535;
        for (int v = 0; v < 65536; v++) { acc += hist[v]; if (acc >= N / 100) { pLo = v; break; } }
        acc = 0;
        for (int v = 65535; v >= 0; v--) { acc += hist[v]; if (acc >= N / 100) { pHi = v; break; } }
        float nlo = pLo - 32768, nhi = Math.max(pHi - 32768, nlo + 20);
        if (!agcInit) { lo = nlo; hi = nhi; agcInit = true; }
        else { lo += (nlo - lo) * 0.15f; hi += (nhi - hi) * 0.15f; }
        int[] lut = whiteHot ? GRAY : IRON;
        boolean inv = invert;
        float scale = 255f / Math.max(1f, hi - lo);
        int[] out = new int[N];
        for (int i = 0; i < N; i++) {
            int v = (int) ((cal[i] - lo) * scale);
            v = v < 0 ? 0 : (v > 255 ? 255 : v);
            out[i] = lut[inv ? 255 - v : v];
        }
        return out;
    }

    private void detectMotion(Result r) {
        if (bg == null) {
            bg = new float[N];
            for (int i = 0; i < N; i++) bg[i] = cal[i];
            warmup = 15;
            return;
        }
        // Global offset (sensor drift) and noise level, estimated robustly.
        int step = 7, m = N / step;
        float[] s = new float[m];
        for (int k = 0; k < m; k++) s[k] = cal[k * step] - bg[k * step];
        Arrays.sort(s);
        float off = s[m / 2];
        for (int k = 0; k < m; k++) s[k] = Math.abs(cal[k * step] - bg[k * step] - off);
        Arrays.sort(s);
        float sigma = Math.max(1.4826f * s[m / 2], 1f);
        float thr = Math.max(sensitivity * sigma, 4f);
        r.noise = sigma;

        for (int i = 0; i < N; i++) { bg[i] += off; mask[i] = Math.abs(cal[i] - bg[i]) > thr; }

        if (warmup > 0) {
            warmup--;
            for (int i = 0; i < N; i++) bg[i] += 0.3f * (cal[i] - bg[i]);
            return;
        }

        // Remove isolated noise: keep pixels with at least 3 masked neighbours.
        int count = 0;
        Arrays.fill(clean, false);
        for (int y = 1; y < H - 1; y++)
            for (int x = 1; x < W - 1; x++) {
                int i = y * W + x;
                if (!mask[i]) continue;
                int nb = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
                    if ((dx | dy) != 0 && mask[i + dy * W + dx]) nb++;
                if (nb >= 3) { clean[i] = true; count++; }
            }
        r.motionPixels = count;

        // If most of the frame "moved", it's a global change (camera moved / bump): re-learn.
        if (count > N / 3) {
            for (int i = 0; i < N; i++) bg[i] = cal[i];
            warmup = 5;
            return;
        }

        // Connected components -> bounding boxes.
        Arrays.fill(label, 0);
        int next = 1;
        for (int start = 0; start < N; start++) {
            if (!clean[start] || label[start] != 0) continue;
            int qh = 0, qt = 0, area = 0;
            int minX = W, minY = H, maxX = 0, maxY = 0;
            queue[qt++] = start;
            label[start] = next;
            while (qh < qt) {
                int i = queue[qh++];
                int x = i % W, y = i / W;
                area++;
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                        int j = ny * W + nx;
                        if (clean[j] && label[j] == 0) { label[j] = next; queue[qt++] = j; }
                    }
            }
            next++;
            if (area >= minArea) r.boxes.add(new Rect(minX, minY, maxX + 1, maxY + 1));
        }
        mergeBoxes(r.boxes);

        // Selective background update: learn fast where static, slowly where moving.
        for (int i = 0; i < N; i++) {
            float a = clean[i] ? 0.003f : 0.05f;
            bg[i] += a * (cal[i] - bg[i]);
        }
    }

    /** Merge boxes that overlap or nearly touch (a person often splits into parts). */
    private static void mergeBoxes(List<Rect> boxes) {
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int a = 0; a < boxes.size(); a++)
                for (int b = a + 1; b < boxes.size(); b++) {
                    Rect ra = boxes.get(a), rb = boxes.get(b);
                    Rect grown = new Rect(ra.left - 3, ra.top - 3, ra.right + 3, ra.bottom + 3);
                    if (Rect.intersects(grown, rb)) {
                        ra.union(rb);
                        boxes.remove(b);
                        merged = true;
                        break outer;
                    }
                }
        }
    }
}
