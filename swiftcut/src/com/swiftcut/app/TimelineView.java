package com.swiftcut.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * CapCut-style filmstrip timeline: thumbnail strip, draggable range
 * handles for trimming, and a playhead. Touch: drag the pink handles
 * to set the trim range, tap anywhere to move the playhead.
 */
public class TimelineView extends View {

    public interface Listener {
        void onRangeChanged(long startMs, long endMs);

        void onPlayheadChanged(long posMs);
    }

    private static final int THUMBS = 10;
    private static final int THUMB_H = 96;

    private final Paint dim = new Paint();
    private final Paint sel = new Paint();
    private final Paint handle = new Paint();
    private final Paint head = new Paint();
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Bitmap> thumbs = new ArrayList<>();
    private long durationMs = 1;
    private long startMs, endMs, playheadMs;
    private Listener listener;
    private int drag;
    private float dp;

    public TimelineView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        dim.setColor(0xAA000000);
        sel.setColor(0xFFFFFFFF);
        sel.setStyle(Paint.Style.STROKE);
        sel.setStrokeWidth(2.5f * dp);
        handle.setColor(0xFFF651A8);
        head.setColor(0xFFFFFFFF);
        head.setStrokeWidth(2f * dp);
        txt.setColor(0xFF9CA3AF);
        txt.setTextSize(12 * dp);
        txt.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDuration(long d) {
        durationMs = Math.max(1, d);
    }

    public void setRange(long s, long e) {
        startMs = s;
        endMs = Math.max(e, s + 1);
        invalidate();
    }

    public void setPlayhead(long p) {
        playheadMs = p;
        invalidate();
    }

    /** Loads thumbnail frames off the UI thread. */
    public void loadThumbs(final Context c, final Uri uri) {
        thumbs.clear();
        invalidate();
        new Thread(new Runnable() {
            @Override
            public void run() {
                MediaMetadataRetriever mm = new MediaMetadataRetriever();
                try {
                    mm.setDataSource(c, uri);
                    long durUs = durationMs * 1000;
                    for (int i = 0; i < THUMBS; i++) {
                        long us = durUs * (2 * i + 1) / (2 * THUMBS);
                        Bitmap f = mm.getFrameAtTime(us,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (f == null) continue;
                        float w = f.getWidth() * (THUMB_H / (float) f.getHeight());
                        Bitmap b = Bitmap.createScaledBitmap(f,
                                Math.max(1, (int) w), THUMB_H, true);
                        if (b != f) f.recycle();
                        synchronized (thumbs) {
                            thumbs.add(b);
                        }
                        postInvalidate();
                    }
                } catch (Throwable ignore) {
                } finally {
                    try {
                        mm.release();
                    } catch (Exception ignore) {
                    }
                }
            }
        }, "thumbs").start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int W = getWidth(), H = getHeight();
        canvas.drawColor(0xFF1E0F24);

        synchronized (thumbs) {
            if (thumbs.isEmpty()) {
                canvas.drawText("Loading thumbnails: " + fmt(playheadMs) + " / " + fmt(durationMs),
                        W / 2f, H / 2f, txt);
            } else {
                float x = 0;
                int i = 0;
                while (x < W + 1) {
                    Bitmap b = thumbs.get(i % thumbs.size());
                    float bw = b.getWidth() * (H / (float) b.getHeight());
                    canvas.drawBitmap(b, x, 0, null);
                    x += bw;
                    i++;
                }
            }
        }

        float s0 = W * startMs / (float) durationMs;
        float s1 = W * endMs / (float) durationMs;
        if (s0 > 0) canvas.drawRect(0, 0, s0, H, dim);
        if (s1 < W) canvas.drawRect(s1, 0, W, H, dim);

        canvas.drawRect(s0, 2 * dp, s1, H - 2 * dp, sel);
        float hw = 9 * dp;
        canvas.drawRoundRect(s0 - hw / 2, H / 2f - 22 * dp, s0 + hw / 2, H / 2f + 22 * dp,
                4 * dp, 4 * dp, handle);
        canvas.drawRoundRect(s1 - hw / 2, H / 2f - 22 * dp, s1 + hw / 2, H / 2f + 22 * dp,
                4 * dp, 4 * dp, handle);

        float hx = W * playheadMs / (float) durationMs;
        canvas.drawLine(hx, 0, hx, H, head);
        canvas.drawCircle(hx, 4 * dp, 3.5f * dp, handle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (durationMs <= 1) return false;
        float x = ev.getX();
        int W = getWidth();
        long ms = clamp((long) (x * durationMs / W));
        float s0 = W * startMs / (float) durationMs;
        float s1 = W * endMs / (float) durationMs;
        float grab = 26 * dp;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(x - s0) <= grab) drag = 1;
                else if (Math.abs(x - s1) <= grab) drag = 2;
                else {
                    drag = 0;
                    setPlayheadInternal(ms);
                    if (listener != null) listener.onPlayheadChanged(ms);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag == 1) {
                    startMs = clamp(Math.min(ms, endMs - 500));
                    notifyRange();
                } else if (drag == 2) {
                    endMs = clamp(Math.max(ms, startMs + 500));
                    endMs = Math.min(endMs, durationMs);
                    notifyRange();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drag == 0) {
                    if (listener != null) listener.onPlayheadChanged(ms);
                }
                drag = 0;
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private void setPlayheadInternal(long ms) {
        playheadMs = ms;
        invalidate();
    }

    private void notifyRange() {
        invalidate();
        if (listener != null) listener.onRangeChanged(startMs, endMs);
    }

    private long clamp(long ms) {
        return Math.max(0, Math.min(durationMs, ms));
    }

    private static String fmt(long ms) {
        return String.format(java.util.Locale.US, "%d:%02d", ms / 60000, ms % 60000 / 1000);
    }
}
