package no.agens.testlab.touch;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * A finger on the touchscreen for Testlab's handsets_drag: DOWN, a MOVE every ~16 ms along the path,
 * UP. Runs on the phone as the shell user (`CLASSPATH=tl-touch.dex app_process / no.agens.testlab.touch.Touch …`)
 * and injects in-process like scrcpy, so the timing is the phone's own: no process per event
 * (as with `input motionevent`, where the finger jumps and a drag ends early).
 *
 * Arguments: "x,y;x,y;…" (pixels of the current orientation), duration ms of one pass along the
 * path (split over the legs by length), hold ms (still, after DOWN), repeat (passes; even ones go
 * back along the path).
 */
public final class Touch {
    private static final long FRAME_MS = 16;

    private static Object im;
    private static Method inject;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.out.println("ERR usage: Touch \"x,y;x,y;...\" [duration_ms] [hold_ms] [repeat]"); System.exit(2); }
        String[] parts = args[0].split(";");
        float[][] pts = new float[parts.length][2];
        for (int i = 0; i < parts.length; i++) {
            String[] xy = parts[i].split(",");
            pts[i][0] = Float.parseFloat(xy[0].trim());
            pts[i][1] = Float.parseFloat(xy[1].trim());
        }
        long duration = args.length > 1 ? Long.parseLong(args[1]) : 800;
        long hold = args.length > 2 ? Long.parseLong(args[2]) : 150;
        int repeat = args.length > 3 ? Math.max(1, Integer.parseInt(args[3])) : 1;
        open();

        double total = 0;
        for (int i = 1; i < pts.length; i++) total += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);

        long down = SystemClock.uptimeMillis();
        int moves = 0;
        send(down, down, MotionEvent.ACTION_DOWN, pts[0][0], pts[0][1]);
        float[] at = pts[0];
        // Holding still: a few MOVEs at the same spot, as a real finger reports.
        long holdEnd = down + hold;
        while (SystemClock.uptimeMillis() < holdEnd) {
            sleepUntil(Math.min(holdEnd, SystemClock.uptimeMillis() + FRAME_MS));
            send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, at[0], at[1]);
            moves++;
        }
        for (int pass = 0; pass < repeat; pass++) {
            boolean back = pass % 2 == 1;
            for (int leg = 1; leg < pts.length; leg++) {
                float[] a = back ? pts[pts.length - leg] : pts[leg - 1];
                float[] b = back ? pts[pts.length - leg - 1] : pts[leg];
                double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                long legMs = total > 0 ? Math.max(FRAME_MS, Math.round(duration * len / total)) : Math.max(FRAME_MS, duration / Math.max(1, pts.length - 1));
                long start = SystemClock.uptimeMillis();
                long end = start + legMs;
                while (true) {
                    long now = SystemClock.uptimeMillis();
                    float t = Math.min(1f, (now - start) / (float) legMs);
                    float x = a[0] + (b[0] - a[0]) * t, y = a[1] + (b[1] - a[1]) * t;
                    send(down, now, MotionEvent.ACTION_MOVE, x, y);
                    moves++;
                    at = new float[]{x, y};
                    if (t >= 1f) break;
                    sleepUntil(Math.min(end, now + FRAME_MS));
                }
            }
        }
        long up = SystemClock.uptimeMillis();
        send(down, up, MotionEvent.ACTION_UP, at[0], at[1]);
        System.out.println("ok moves=" + moves + " ms=" + (up - down));
    }

    private static void open() throws Exception {
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal"); // Android 14+
            im = g.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null);
        }
        inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static void send(long downTime, long eventTime, int action, float x, float y) throws Exception {
        MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = action == MotionEvent.ACTION_UP ? 0f : 1f;
        coords[0].size = 1f;
        MotionEvent e = MotionEvent.obtain(downTime, eventTime, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            inject.invoke(im, e, 0); // INJECT_INPUT_EVENT_MODE_ASYNC, as scrcpy
        } finally {
            e.recycle();
        }
    }

    private static void sleepUntil(long t) {
        long d = t - SystemClock.uptimeMillis();
        if (d > 0) SystemClock.sleep(d);
    }
}
