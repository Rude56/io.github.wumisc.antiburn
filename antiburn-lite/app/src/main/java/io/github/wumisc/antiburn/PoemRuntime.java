package io.github.wumisc.antiburn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Stateful poem crawl / clock-hold controller running inside SystemUI. */
final class PoemRuntime {
    private static final int TARGET_FRAME_NS = 1_000_000_000 / 30;
    private static final int LUT_SIZE = 257;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static View statusView;
    private static View navView;
    private static WindowManager wm;
    private static OverlayView overlay;
    private static final ArrayList<Module> modules = new ArrayList<>();
    private static PoemLibrary.Playback playback;
    private static String sourceSignature = "";
    private static State state = State.IDLE;
    private static Runnable clockEnd;
    private static long clockEndAt;

    private PoemRuntime() {}

    private enum State { IDLE, POEM, CLOCK }

    static void tick(View status, View nav, boolean pause) {
        statusView = status;
        navView = nav;
        if (pause) {
            pause();
            return;
        }
        if (!Config.enablePoem || Config.poemPool == null || Config.poemPool.isEmpty()) {
            stop();
            return;
        }
        String signature = signature();
        if (!signature.equals(sourceSignature)) {
            stop();
            sourceSignature = signature;
            playback = new PoemLibrary.Playback(Config.poemPool, Config.poemOrder, Config.poemSeed);
        }
        if (state == State.IDLE) {
            startPoem();
        }
    }

    private static String signature() {
        return Config.poemLocalJson.length() + ":" + Config.poemLocalJson.hashCode()
                + ":" + Config.poemOnlineJson.length() + ":" + Config.poemOnlineJson.hashCode()
                + ":" + Config.poemOrder + ":" + Config.poemSeed
                + ":" + Config.poemShowAuthor + ":" + Config.poemShowSource
                + ":" + Config.poemSpeedPx + ":" + Config.poemDurationExpr.hashCode()
                + ":" + Config.poemPositionExpr.hashCode();
    }

    private static void startPoem() {
        if (statusView == null || playback == null) return;
        PoemItem poem = playback.next();
        if (poem == null) { stop(); return; }
        String text = poem.displayText(Config.poemShowAuthor, Config.poemShowSource);
        if (TextUtils.isEmpty(text)) return;

        showStatus();
        scanModules();
        if (overlay == null) overlay = new OverlayView(statusView.getContext());
        overlay.configure(statusView, text, Config.poemSpeedPx,
                Config.poemDurationExpr, Config.poemPositionExpr,
                Config.poemSkipCutout, Config.poemBob, new OverlayView.Listener() {
                    @Override public void onFrame(float l, float t, float r, float b) {
                        applyOcclusion(l, t, r, b);
                    }
                    @Override public void onFinished() { enterClockHold(); }
                });
        try {
            WindowManager.LayoutParams lp = overlay.layoutParams(statusView);
            wm = (WindowManager) statusView.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.addView(overlay, lp);
        } catch (Throwable t) {
            XposedInit.diag("POEM overlay add failed: " + t);
            stop();
            return;
        }
        if (navView != null) navView.setAlpha(Config.enableNav ? 0f : 1f);
        state = State.POEM;
    }

    private static void enterClockHold() {
        state = State.CLOCK;
        removeOverlay();
        restoreModules();
        if (navView != null) navView.setAlpha(1f);
        cancelClock();
        int seconds = Math.max(0, Config.poemClockSec);
        if (seconds == 0) {
            handler.post(() -> { if (state == State.CLOCK) { state = State.IDLE; startPoem(); } });
            return;
        }
        clockEndAt = SystemClock.elapsedRealtime() + seconds * 1000L;
        clockEnd = () -> {
            if (state != State.CLOCK) return;
            state = State.IDLE;
            startPoem();
        };
        handler.postDelayed(clockEnd, seconds * 1000L);
    }

    static void pause() {
        cancelClock();
        if (overlay != null) overlay.stopAnimation();
        removeOverlay();
        restoreModules();
        showStatus();
        if (navView != null) navView.setAlpha(1f);
        state = State.IDLE;
    }

    static void stop() {
        pause();
        playback = null;
        sourceSignature = "";
    }

    private static void cancelClock() {
        if (clockEnd != null) handler.removeCallbacks(clockEnd);
        clockEnd = null;
        clockEndAt = 0;
    }

    private static void removeOverlay() {
        if (overlay != null) {
            overlay.stopAnimation();
            try {
                if (overlay.isAttached() && wm != null) wm.removeView(overlay);
            } catch (Throwable ignored) {}
            overlay.markDetached();
        }
    }

    private static void showStatus() {
        if (statusView == null) return;
        if (statusView.getAlpha() != 1f) statusView.setAlpha(1f);
    }

    private static void scanModules() {
        restoreModules();
        modules.clear();
        if (statusView == null) return;
        int[] origin = new int[2];
        statusView.getLocationOnScreen(origin);
        collectModules(statusView, origin, 0);
    }

    private static void collectModules(View v, int[] origin, int depth) {
        if (v == null || depth > 9 || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectModules(g.getChildAt(i), origin, depth + 1);
            return;
        }
        if (v.getWidth() < 2 || v.getHeight() < 2 || !v.isShown()) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int l = loc[0] - origin[0];
        int t = loc[1] - origin[1];
        modules.add(new Module(v, l, t, l + v.getWidth(), t + v.getHeight(), v.getAlpha()));
    }

    private static void applyOcclusion(float l, float t, float r, float b) {
        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            boolean hit = m.right > l && m.left < r && m.bottom > t && m.top < b;
            if (hit != m.occluded) {
                m.occluded = hit;
                m.view.setAlpha(hit ? 0f : m.originalAlpha);
            }
        }
    }

    private static void restoreModules() {
        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            if (m.occluded || m.view.getAlpha() != m.originalAlpha) m.view.setAlpha(m.originalAlpha);
            m.occluded = false;
        }
    }

    private static final class Module {
        final View view;
        final int left, top, right, bottom;
        final float originalAlpha;
        boolean occluded;
        Module(View v, int l, int t, int r, int b, float a) {
            view = v; left = l; top = t; right = r; bottom = b; originalAlpha = a;
        }
    }

    private static final class OverlayView extends View implements Choreographer.FrameCallback {
        interface Listener {
            void onFrame(float l, float t, float r, float b);
            void onFinished();
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private final ArrayList<Rect> cutouts = new ArrayList<>();
        private int screenW, screenH, originX, originY;
        private String text = "";
        private float textW;
        private long startNs, nextFrameNs, durationNs;
        private float[] lut;
        private boolean customCurve, bob, skipCutout, running, attached;
        private Listener listener;
        private float currentLeft, currentTop, currentRight, currentBottom;

        OverlayView(Context c) {
            super(c);
            paint.setTextSize(14f * c.getResources().getDisplayMetrics().scaledDensity);
            paint.setFakeBoldText(true);
            paint.setColor(0xFFFFFFFF);
            setWillNotDraw(false);
        }

        void configure(View status, String value, int speed, String durationExpr,
                       String positionExpr, boolean skip, boolean floatY, Listener l) {
            listener = l;
            text = value;
            skipCutout = skip;
            bob = floatY;
            customCurve = positionExpr != null && !positionExpr.trim().isEmpty()
                    && !PoemMotion.DEFAULT_POSITION_EXPR.equals(positionExpr.trim());
            int[] loc = new int[2];
            status.getLocationOnScreen(loc);
            originX = loc[0]; originY = loc[1];
            screenW = status.getWidth() > 0 ? status.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            screenH = status.getHeight() > 0 ? status.getHeight() : (int) (28 * getResources().getDisplayMetrics().density);
            textW = paint.measureText(text);
            PoemMotion.Vars vars = new PoemMotion.Vars();
            vars.speed = speed; vars.width = screenW; vars.textWidth = textW; vars.padding = 8;
            vars.chars = text.codePointCount(0, text.length()); vars.p = 0;
            try {
                PoemMotion.Compiled duration = PoemMotion.compile(durationExpr);
                double valueSeconds = duration.eval(vars);
                if (!Double.isFinite(valueSeconds)) valueSeconds = (screenW + textW) / Math.max(1, speed);
                durationNs = (long) (Math.max(1, Math.min(120, valueSeconds)) * 1_000_000_000L);
            } catch (Throwable ignored) {
                durationNs = (long) (((screenW + textW + 16f) / Math.max(1, speed)) * 1_000_000_000L);
            }
            vars.duration = durationNs / 1_000_000_000d;
            if (customCurve) {
                try { lut = PoemMotion.sample(PoemMotion.compile(positionExpr), LUT_SIZE, vars); }
                catch (Throwable ignored) { lut = null; customCurve = false; }
            } else lut = null;
            loadCutouts(status);
            loadTextColor(status);
            startNs = nextFrameNs = 0;
            running = false;
            attached = true;
        }

        private void loadTextColor(View status) {
            try {
                int id = status.getResources().getIdentifier("clock", "id", "com.android.systemui");
                View clock = id == 0 ? null : status.findViewById(id);
                if (clock instanceof TextView) paint.setColor(((TextView) clock).getCurrentTextColor());
            } catch (Throwable ignored) {}
        }

        private void loadCutouts(View status) {
            cutouts.clear();
            if (!skipCutout || android.os.Build.VERSION.SDK_INT < 29) return;
            try {
                WindowInsets insets = status.getRootWindowInsets();
                DisplayCutout c = insets == null ? null : insets.getDisplayCutout();
                if (c == null) return;
                for (Rect r : c.getBoundingRects()) {
                    cutouts.add(new Rect(r.left - originX, r.top - originY, r.right - originX, r.bottom - originY));
                }
                Collections.sort(cutouts, new Comparator<Rect>() {
                    @Override public int compare(Rect a, Rect b) { return Integer.compare(a.left, b.left); }
                });
            } catch (Throwable ignored) {}
        }

        WindowManager.LayoutParams layoutParams(View status) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    screenW, screenH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = originX;
            lp.y = originY;
            return lp;
        }

        boolean isAttached() { return attached; }
        void markDetached() { attached = false; }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startNs = SystemClock.elapsedRealtimeNanos();
            nextFrameNs = startNs;
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (frameTimeNanos < nextFrameNs) {
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }
            nextFrameNs = Math.max(frameTimeNanos, nextFrameNs + TARGET_FRAME_NS);
            update(frameTimeNanos);
            if (running) Choreographer.getInstance().postFrameCallback(this);
        }

        private void update(long nowNs) {
            double p = (nowNs - startNs) / (double) durationNs;
            if (p >= 1) {
                p = 1;
                running = false;
            }
            float progress = (float) Math.max(0, Math.min(1, p));
            float x = customCurve ? PoemMotion.lookup(lut, progress) : progress;
            float distance = screenW + textW + 16f;
            float left = screenW - x * distance;
            float top = (screenH - (paint.ascent() + paint.descent())) / 2f - paint.ascent();
            if (bob) top += (float) Math.sin(progress * Math.PI * 4) * 1.5f;
            float right = left + textW;
            float bottom = top - paint.ascent() + paint.descent();
            currentLeft = left; currentTop = top - paint.ascent(); currentRight = right; currentBottom = bottom;
            if (listener != null) listener.onFrame(currentLeft, currentTop, currentRight, currentBottom);
            invalidate();
            if (!running && listener != null) listener.onFinished();
        }

        void stopAnimation() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override protected void onDetachedFromWindow() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (text == null || text.isEmpty()) return;
            float baseline = (screenH - (paint.ascent() + paint.descent())) / 2f;
            if (bob) baseline += (float) Math.sin(((currentLeft / Math.max(1f, screenW))) * Math.PI * 4) * 1.5f;
            if (cutouts.isEmpty()) {
                canvas.drawText(text, currentLeft, baseline, paint);
                return;
            }
            float cursor = currentLeft;
            float end = currentRight;
            for (int i = 0; i < cutouts.size() && cursor < end; i++) {
                Rect r = cutouts.get(i);
                if (r.right <= cursor || r.left >= end) continue;
                if (r.left > cursor) drawSegment(canvas, cursor, Math.min(r.left, end), baseline);
                cursor = Math.max(cursor, r.right);
            }
            if (cursor < end) drawSegment(canvas, cursor, end, baseline);
        }

        private void drawSegment(Canvas c, float l, float r, float baseline) {
            if (r <= l) return;
            c.save();
            c.clipRect(l, 0, r, screenH);
            c.drawText(text, currentLeft, baseline, paint);
            c.restore();
        }
    }
}

