package com.thermal.motion;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity implements SeekCamera.Listener {
    static final String ACTION_PERM = "com.thermal.motion.USB_PERMISSION";
    static final int SEEK_VID = 0x289d;

    private UsbManager usb;
    private SeekCamera camera;
    private final ThermalProcessor proc = new ThermalProcessor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ThermalView view;
    private TextView status, logView;
    private final ArrayList<String> log = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private volatile boolean framePending;
    private int motionEvents;
    private boolean wasMoving;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) stopCamera("相機已拔除");
            else connect();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(USB_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff111111);
        view = new ThermalView(this);
        root.addView(view);

        status = new TextView(this);
        status.setTextColor(0xffffffff);
        status.setTextSize(14);
        status.setPadding(24, 16, 24, 8);
        root.addView(status);

        TextView sensLabel = label("靈敏度：6（數字越小越靈敏）");
        root.addView(sensLabel);
        SeekBar sens = new SeekBar(this);
        sens.setMax(17);
        sens.setProgress(3);
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                proc.sensitivity = p + 3;
                sensLabel.setText("靈敏度：" + (p + 3) + "（數字越小越靈敏）");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sens);

        LinearLayout row1 = row(), row2 = row();
        row1.addView(button("色板切換", v -> proc.whiteHot = !proc.whiteHot));
        row1.addView(button("冷熱反轉", v -> proc.invert = !proc.invert));
        row1.addView(button("重設背景", v -> proc.resetBackground()));
        row2.addView(button("診斷記錄", v -> logView.setVisibility(logView.getVisibility() == View.GONE ? View.VISIBLE : View.GONE)));
        row2.addView(button("複製記錄", v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("log", logText()));
            Toast.makeText(this, "已複製，可以貼給 Claude", Toast.LENGTH_SHORT).show();
        }));
        SharedPreferences prefs = getSharedPreferences("view", MODE_PRIVATE);
        view.setOrientation(prefs.getInt("rot", 0), prefs.getBoolean("flip", false));
        LinearLayout row0 = row();
        row0.addView(button("旋轉 90°", v -> {
            view.setOrientation((view.rot + 1) % 4, view.flipH);
            prefs.edit().putInt("rot", view.rot).apply();
        }));
        row0.addView(button("左右翻轉", v -> {
            view.setOrientation(view.rot, !view.flipH);
            prefs.edit().putBoolean("flip", view.flipH).apply();
        }));
        shutterOff = prefs.getBoolean("shutterOff", true);
        smartIdx = prefs.getInt("smart", 2);
        LinearLayout rowS = row();
        Button shutterBtn = button(shutterLabel(), null);
        shutterBtn.setOnClickListener(v -> {
            shutterOff = !shutterOff;
            prefs.edit().putBoolean("shutterOff", shutterOff).apply();
            shutterBtn.setText(shutterLabel());
            if (camera != null) camera.setShutterOff(shutterOff);
        });
        rowS.addView(shutterBtn);
        rowS.addView(button("立即校正", v -> requestCal("手動")));
        Button smartBtn = button(smartLabel(), null);
        smartBtn.setOnClickListener(v -> {
            smartIdx = (smartIdx + 1) % SMART_SEC.length;
            prefs.edit().putInt("smart", smartIdx).apply();
            smartBtn.setText(smartLabel());
        });
        rowS.addView(smartBtn);
        root.addView(rowS);
        root.addView(row0);
        root.addView(row1);
        root.addView(row2);

        logView = new TextView(this);
        logView.setTextColor(0xffaaaaaa);
        logView.setTextSize(11);
        logView.setPadding(24, 8, 24, 8);
        logView.setVisibility(View.GONE);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
        connect();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xffcccccc);
        t.setPadding(24, 8, 24, 0);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(16, 0, 16, 0);
        return l;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); connect(); }

    @Override protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        if (camera != null) camera.stop();
        super.onDestroy();
    }

    private void connect() {
        if (camera != null) return;
        UsbDevice dev = null;
        for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == SEEK_VID) dev = d;
        if (dev == null) { view.setMessage("請插上 Seek Thermal 熱像儀"); return; }
        if (!usb.hasPermission(dev)) {
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(dev, PendingIntent.getBroadcast(this, 0, new Intent(ACTION_PERM).setPackage(getPackageName()), flags));
            view.setMessage("請允許 USB 存取權限");
            return;
        }
        onLog("連線 " + dev.getProductName() + String.format(Locale.US, " PID=0x%04x", dev.getProductId()));
        view.setMessage("相機啟動中…");
        camera = new SeekCamera(usb, dev, this);
        camera.setShutterOff(shutterOff);
        camera.start();
    }

    private void stopCamera(String why) {
        if (camera != null) { camera.stop(); camera = null; }
        onLog(why);
        view.setMessage(why);
    }

    // ---- camera callbacks (USB thread) ----

    @Override public void onFrame(short[] raw, int id, long t) {
        ThermalProcessor.Result r = proc.process(raw, id, t);
        if (r == null || framePending) return;   // drop display frames if UI is behind
        framePending = true;
        ui.post(() -> { framePending = false; show(r); });
    }

    @Override public void onLog(String msg) {
        String line = fmt.format(new Date()) + " " + msg;
        ui.post(() -> {
            log.add(line);
            if (log.size() > 300) log.remove(0);
            logView.setText(logText());
        });
    }

    @Override public void onStopped(String reason) {
        ui.post(() -> { camera = null; onLog(reason); view.setMessage(reason); });
    }

    private int lastLoggedFfc;
    static final int[] SMART_SEC = {0, 30, 60, 120, 300};
    private boolean shutterOff;
    private int smartIdx;
    private long lastMotionTime, lastCalReq;

    private String shutterLabel() { return shutterOff ? "快門：已關閉" : "快門：自動"; }

    private String smartLabel() {
        int s = SMART_SEC[smartIdx];
        return s == 0 ? "智慧校正：關" : "智慧校正：" + (s < 60 ? s + "秒" : s / 60 + "分");
    }

    private void requestCal(String why) {
        if (camera == null) return;
        lastCalReq = System.currentTimeMillis();
        onLog("要求校正（" + why + "）");
        camera.calibrateNow();
    }

    /** With the shutter off, calibrate only when the scene is quiet (or when it is long overdue). */
    private void smartCalibration(ThermalProcessor.Result r, long now) {
        if (!r.boxes.isEmpty() && !r.shutterActive) lastMotionTime = now;
        int sec = SMART_SEC[smartIdx];
        if (camera == null || !shutterOff || sec == 0 || r.msSinceFfc < 0) return;
        if (now - lastCalReq < 6000) return;
        long due = sec * 1000L;
        if (r.msSinceFfc < due) return;
        if (now - lastMotionTime >= 3000) requestCal("畫面靜止 3 秒");
        else if (r.msSinceFfc >= 3 * due) requestCal("已超過 " + 3 * sec + " 秒未校正");
    }
    private long startTime, minuteStart, minuteGap, totalGap;
    private int minuteFfc, minuteIdx;

    private void show(ThermalProcessor.Result r) {
        view.setFrame(r);
        long now = System.currentTimeMillis();
        if (startTime == 0) { startTime = now; minuteStart = now; }
        if (r.gapMs > 0) {
            minuteGap += r.gapMs; totalGap += r.gapMs; minuteFfc++;
            onLog("  └ 快門盲區 " + r.gapMs + " 毫秒");
        }
        smartCalibration(r, now);
        if (now - minuteStart >= 60000) {
            minuteIdx++;
            onLog(String.format(Locale.US, "【第 %d 分鐘】快門 %d 次，平均間隔 %s，盲區佔 %.1f%%",
                    minuteIdx, minuteFfc,
                    minuteFfc > 0 ? String.format(Locale.US, "%.1f 秒", 60f / minuteFfc) : "-",
                    minuteGap / 600f));
            minuteStart = now; minuteGap = 0; minuteFfc = 0;
        }
        boolean moving = !r.boxes.isEmpty() && !r.shutterActive;
        if (moving && !wasMoving) motionEvents++;
        if (!r.shutterActive) wasMoving = moving;
        if (r.ffcCount != lastLoggedFfc) {
            lastLoggedFfc = r.ffcCount;
            onLog("快門校正 #" + r.ffcCount + (r.lastFfcInterval > 0 ? "，距上次 " + r.lastFfcInterval / 1000f + " 秒" : ""));
        }
        status.setText(String.format(Locale.US,
                "已執行 %d 分 %02d 秒 ｜ 幀率 %.1f fps ｜ 雜訊 %.1f\n快門校正 %d 次 ｜ 間隔 %s ｜ 距上次 %s ｜ 盲區佔 %.1f%%\n%s ｜ 移動事件 %d 次",
                (now - startTime) / 60000, (now - startTime) / 1000 % 60, r.fps, r.noise, r.ffcCount,
                r.lastFfcInterval > 0 ? String.format(Locale.US, "%.1f 秒", r.lastFfcInterval / 1000f) : "-",
                r.msSinceFfc >= 0 ? String.format(Locale.US, "%.0f 秒", r.msSinceFfc / 1000f) : "-",
                now > startTime ? totalGap * 100f / (now - startTime) : 0f,
                r.boxes.isEmpty() ? "無移動" : "偵測到 " + r.boxes.size() + " 個移動目標",
                motionEvents));
        status.append("\n快門：" + (camera != null && camera.isSuspended() ? "已關閉（不會自動閉合）" : "自動")
                + " ｜ " + smartLabel());
    }

    private String logText() {
        StringBuilder sb = new StringBuilder("手機: " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE + "\n");
        for (String s : log) sb.append(s).append('\n');
        return sb.toString();
    }
}
