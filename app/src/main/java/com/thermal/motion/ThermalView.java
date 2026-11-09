package com.thermal.motion;

import android.content.Context;
import android.graphics.*;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class ThermalView extends View {
    private final Bitmap bmp = Bitmap.createBitmap(ThermalProcessor.W, ThermalProcessor.H, Bitmap.Config.ARGB_8888);
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint box = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint();
    private List<Rect> boxes = new ArrayList<>();
    private boolean shutter, hasFrame;
    private String message = "請插上 Seek Thermal 熱像儀";

    public ThermalView(Context c) {
        super(c);
        box.setStyle(Paint.Style.STROKE);
        box.setColor(Color.GREEN);
        box.setStrokeWidth(5);
        text.setColor(Color.WHITE);
        text.setTextSize(42);
        badge.setColor(0xAA000000);
        setBackgroundColor(Color.BLACK);
    }

    public void setFrame(ThermalProcessor.Result r) {
        if (r.argb != null) { bmp.setPixels(r.argb, 0, ThermalProcessor.W, 0, 0, ThermalProcessor.W, ThermalProcessor.H); hasFrame = true; }
        boxes = r.boxes;
        shutter = r.shutterActive;
        message = r.calibrated ? null : "等待第一次快門校正…";
        invalidate();
    }

    public void setMessage(String m) { message = m; invalidate(); }

    int rot;        // quarter turns clockwise
    boolean flipH;  // mirror left/right (applied after rotation)

    public void setOrientation(int rot, boolean flipH) {
        this.rot = rot;
        this.flipH = flipH;
        requestLayout();
        invalidate();
    }

    private int contentW() { return rot % 2 == 0 ? ThermalProcessor.W : ThermalProcessor.H; }
    private int contentH() { return rot % 2 == 0 ? ThermalProcessor.H : ThermalProcessor.W; }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = width * contentH() / contentW();
        int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        setMeasuredDimension(width, Math.min(height, max));
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth() / (float) contentW(), getHeight() / (float) contentH());
        c.save();
        c.translate(getWidth() / 2f, getHeight() / 2f);
        c.scale(flipH ? -s : s, s);
        c.rotate(rot * 90);
        c.translate(-ThermalProcessor.W / 2f, -ThermalProcessor.H / 2f);
        if (hasFrame) c.drawBitmap(bmp, null, new RectF(0, 0, ThermalProcessor.W, ThermalProcessor.H), img);
        box.setColor(shutter ? Color.YELLOW : Color.GREEN);
        box.setStrokeWidth(5 / s);
        for (Rect r : boxes) c.drawRect(r.left, r.top, r.right, r.bottom, box);
        c.restore();
        String m = shutter ? "快門校正中（偵測持續）" : message;
        if (m != null) {
            float tw = text.measureText(m);
            c.drawRect(10, 10, 30 + tw, 70, badge);
            c.drawText(m, 20, 56, text);
        }
    }
}
