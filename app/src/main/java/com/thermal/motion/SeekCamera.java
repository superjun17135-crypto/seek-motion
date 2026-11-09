package com.thermal.motion;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Minimal USB driver for the Seek Thermal Compact (VID 0x289d, PID 0x0010).
 * Protocol follows the community reverse-engineering work (libseek-thermal).
 * Only read-only / operating commands are ever sent; firmware, memory and
 * factory-setting writes are deliberately not implemented.
 */
public class SeekCamera implements Runnable {

    public interface Listener {
        void onFrame(short[] raw, int frameId, long timestampMs);
        void onLog(String msg);
        void onStopped(String reason);
    }

    public static final int RAW_W = 208, RAW_H = 156;
    static final int WORDS = RAW_W * RAW_H;          // 32448 = 0x7EC0
    static final int BYTES = WORDS * 2;

    // Vendor commands
    static final int TARGET_PLATFORM = 0x54;
    static final int SET_OPERATION_MODE = 0x3C;
    static final int GET_OPERATION_MODE = 0x3D;
    static final int SET_IMAGE_PROCESSING_MODE = 0x3E;
    static final int GET_FIRMWARE_INFO = 0x4E;
    static final int READ_CHIP_ID = 0x36;
    static final int SET_FACTORY_SETTINGS_FEATURES = 0x56;
    static final int GET_FACTORY_SETTINGS = 0x58;
    static final int START_GET_IMAGE_TRANSFER = 0x53;
    static final int TOGGLE_SHUTTER = 0x37;
    static final int GET_SHUTTER_POLARITY = 0x39;

    private final UsbManager manager;
    private final UsbDevice device;
    private final Listener listener;
    private UsbDeviceConnection conn;
    private UsbInterface itf;
    private UsbEndpoint in;
    private volatile boolean running;
    // Automatic-shutter control (request 55, address 0xFF). Only the RAM flag values
    // 0xFD (suspend) / 0xFC (resume) are used; motor-drive values are never sent.
    private volatile boolean wantShutterOff;
    private volatile boolean calRequested;
    private boolean suspended, ffcEverDone, lastWasFfc, inCal, calGotFfc;
    private long calStart;
    private Thread thread;
    private int idIndex = -1;

    public SeekCamera(UsbManager manager, UsbDevice device, Listener listener) {
        this.manager = manager;
        this.device = device;
        this.listener = listener;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "seek-usb");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(1500); } catch (InterruptedException ignored) {}
        }
    }

    /** true = suspend the camera's automatic shutter (takes effect after the first calibration). */
    public void setShutterOff(boolean off) { wantShutterOff = off; }

    /** Let the camera run exactly one calibration now, then suspend again if the shutter is off. */
    public void calibrateNow() { calRequested = true; }

    public boolean isSuspended() { return suspended; }

    private boolean shutterFlag(boolean suspend) {
        byte[] d = {(byte) 0xff, 0, (byte) (suspend ? 0xfd : 0xfc), 0};
        int n = conn.controlTransfer(0x41, TOGGLE_SHUTTER, 0, 0, d, d.length, 1000);
        listener.onLog((suspend ? "關閉自動快門" : "恢復自動快門") + (n == d.length ? "：成功" : "：相機拒絕 (" + n + ")"));
        return n == d.length;
    }

    private void shutterControl(long now) {
        if (calRequested) {
            calRequested = false;
            if (!suspended || shutterFlag(false)) {
                suspended = false;
                inCal = true; calGotFfc = false; calStart = now;
                listener.onLog("執行一次校正");
            }
        }
        if (inCal && now - calStart > 4000) { inCal = false; listener.onLog("校正等待逾時"); }
        if (wantShutterOff && !suspended && ffcEverDone && !inCal) {
            if (shutterFlag(true)) suspended = true; else wantShutterOff = false;
        } else if (!wantShutterOff && suspended) {
            if (shutterFlag(false)) suspended = false;
        }
    }

    private void trackFfc(int id) {
        if (id == 1) { lastWasFfc = true; if (inCal) calGotFfc = true; }
        else if (id == 3 && lastWasFfc) {
            lastWasFfc = false;
            ffcEverDone = true;
            if (inCal && calGotFfc) inCal = false;
        }
    }

    private boolean set(int req, int... data) {
        byte[] b = new byte[data.length];
        for (int i = 0; i < data.length; i++) b[i] = (byte) data[i];
        int r = conn.controlTransfer(0x41, req, 0, 0, b.length == 0 ? null : b, b.length, 1000);
        return r >= 0;
    }

    private byte[] get(int req, int len) {
        byte[] b = new byte[len];
        int r = conn.controlTransfer(0xC1, req, 0, 0, b, len, 1000);
        return r < 0 ? null : b;
    }

    static String hex(byte[] b) {
        if (b == null) return "(失敗)";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x ", x));
        return sb.toString().trim();
    }

    private boolean open() {
        conn = manager.openDevice(device);
        if (conn == null) { listener.onLog("無法開啟 USB 裝置"); return false; }
        itf = device.getInterface(0);
        if (!conn.claimInterface(itf, true)) { listener.onLog("無法取得介面 0"); return false; }
        for (int i = 0; i < itf.getEndpointCount(); i++) {
            UsbEndpoint ep = itf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
        }
        if (in == null) { listener.onLog("找不到 IN 端點"); return false; }
        return true;
    }

    private boolean init() {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (set(TARGET_PLATFORM, 0x01)) {
                set(SET_OPERATION_MODE, 0x00, 0x00);
                listener.onLog("韌體資訊: " + hex(get(GET_FIRMWARE_INFO, 4)));
                listener.onLog("晶片 ID: " + hex(get(READ_CHIP_ID, 12)));
                set(SET_FACTORY_SETTINGS_FEATURES, 0x20, 0x00, 0x30, 0x00, 0x00, 0x00);
                get(GET_FACTORY_SETTINGS, 0x40);
                set(SET_FACTORY_SETTINGS_FEATURES, 0x20, 0x00, 0x50, 0x00, 0x00, 0x00);
                get(GET_FACTORY_SETTINGS, 0x40);
                set(SET_FACTORY_SETTINGS_FEATURES, 0x0c, 0x00, 0x70, 0x00, 0x00, 0x00);
                get(GET_FACTORY_SETTINGS, 0x18);
                set(SET_FACTORY_SETTINGS_FEATURES, 0x06, 0x00, 0x08, 0x00, 0x00, 0x00);
                get(GET_FACTORY_SETTINGS, 0x0c);
                set(SET_IMAGE_PROCESSING_MODE, 0x08, 0x00);
                get(GET_OPERATION_MODE, 2);
                set(SET_IMAGE_PROCESSING_MODE, 0x08, 0x00);
                set(SET_OPERATION_MODE, 0x01, 0x00);
                listener.onLog("運作模式: " + hex(get(GET_OPERATION_MODE, 2)));
                listener.onLog("快門極性: " + hex(get(GET_SHUTTER_POLARITY, 2)));
                return true;
            }
            listener.onLog("初始化失敗，重置後重試 (" + (attempt + 1) + ")");
            set(SET_OPERATION_MODE, 0x00, 0x00);
            set(SET_OPERATION_MODE, 0x00, 0x00);
            set(SET_OPERATION_MODE, 0x00, 0x00);
        }
        return false;
    }

    private boolean readFrame(byte[] buf) {
        if (!set(START_GET_IMAGE_TRANSFER, 0xC0, 0x7E, 0x00, 0x00)) return false;
        int done = 0;
        while (done < BYTES) {
            int n = Math.min(16384, BYTES - done);
            int r = conn.bulkTransfer(in, buf, done, n, 1000);
            if (r <= 0) return false;
            done += r;
        }
        return true;
    }

    /** Frame id location differs between sources; detect it from the first frames. */
    private int frameId(short[] raw) {
        if (idIndex < 0) {
            int a = raw[10] & 0xffff, b = raw[2] & 0xffff;
            idIndex = (a >= 1 && a <= 10) ? 10 : (b >= 1 && b <= 10) ? 2 : 10;
            listener.onLog("影格 ID 位置: word[" + idIndex + "] (w2=" + b + ", w10=" + a + ")");
        }
        return raw[idIndex] & 0xffff;
    }

    @Override
    public void run() {
        String reason = "已停止";
        try {
            if (!open() || !init()) { reason = "相機初始化失敗"; return; }
            listener.onLog("相機已啟動");
            byte[] buf = new byte[BYTES];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            int errors = 0, logged = 0;
            while (running) {
                shutterControl(System.currentTimeMillis());
                if (!readFrame(buf)) {
                    if (++errors > 10) {
                        listener.onLog("連續讀取失敗，重新初始化");
                        errors = 0;
                        if (!init()) { reason = "重新初始化失敗"; return; }
                        suspended = false; ffcEverDone = false; inCal = false;
                    }
                    continue;
                }
                errors = 0;
                short[] raw = new short[WORDS];
                bb.rewind();
                bb.asShortBuffer().get(raw);
                int id = frameId(raw);
                trackFfc(id);
                if (logged < 12) {
                    logged++;
                    StringBuilder sb = new StringBuilder("影格頭 id=" + id + ":");
                    for (int i = 0; i < 12; i++) sb.append(' ').append(raw[i] & 0xffff);
                    listener.onLog(sb.toString());
                }
                listener.onFrame(raw, id, System.currentTimeMillis());
            }
        } catch (Exception e) {
            reason = "錯誤: " + e;
        } finally {
            if (conn != null) {
                try { if (suspended) shutterFlag(false); } catch (Exception ignored) {}
                try { set(SET_OPERATION_MODE, 0x00, 0x00); } catch (Exception ignored) {}
                if (itf != null) conn.releaseInterface(itf);
                conn.close();
            }
            listener.onStopped(reason);
        }
    }
}
