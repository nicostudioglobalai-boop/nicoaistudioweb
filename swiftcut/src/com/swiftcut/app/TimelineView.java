package com.swiftcut.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * CapCut-style filmstrip timeline: thumbnail strip, pinch-to-zoom,
 * pan when zoomed, draggable range handles, playhead.
 *
 * Touch:
 *  - pinch           zoom the timeline (down to ~10s per screen)
 *  - drag strip      pan when zoomed in
 *  - tap strip       move the playhead
 *  - drag pink tabs  set trim range
 */
public class TimelineView extends View {

    public interface Listener {
        void onRangeChanged(long startMs, long endMs);

        void onPlayheadChanged(long posMs);
    }

    private static final int THUMBS = 10;
    private static final int THUMB_H = 96;
    private static final float MAX_ZOOM = 30f;

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

    private float zoom = 1f;
    private long panMs;
    private ScaleGestureDetector pinch;
    private float downX;
    private float lastX;
    private boolean moved;
    private boolean pinched;

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

        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                pinched = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float factor = d.getScaleFactor();
                float newZoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
                if (newZoom != zoom) {
                    long focalMs = mapMs(d.getFocusX());
                    zoom = newZoom;
                    panMs = clampPan(focalMs - (long) (d.getFocusX() * durationMs
                            / ((float) getWidth() * zoom)));
                    invalidate();
                }
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDuration(long d) {
        durationMs = Math.max(1, d);
        zoom = 1f;
        panMs = 0;
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
        synchronized (thumbs) {
            for (Bitmap b : thumbs) b.recycle();
            thumbs.clear();
        }
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

    /* ---- coordinate helpers ---- */

    private long visibleSpan() {
        return Math.max(1, (long) (durationMs / zoom));
    }

    private long mapMs(float x) {
        return panMs + (long) (x * durationMs / (getWidth() * zoom));
    }

    private float mapX(long ms) {
        return (ms - panMs) * getWidth() * zoom / (float) durationMs;
    }

    private long clampPan(long p) {
        return Math.max(0, Math.min(durationMs - visibleSpan(), p));
    }

    /* ---- drawing ---- */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int W = getWidth(), H = getHeight();
        canvas.drawColor(0xFF1E0F24);

        boolean drawn = false;
        synchronized (thumbs) {
            if (!thumbs.isEmpty()) {
                long msAcc = 0;
                int i = 0;
                int guard = 0;
                while (guard++ < 400) {
                    Bitmap b = thumbs.get(i % thumbs.size());
                    float baseW = b.getWidth() * (H / (float) b.getHeight());
                    long tileMs = (long) (baseW * durationMs / W);
                    long tileEnd = msAcc + tileMs;
                    float bx0 = mapX(msAcc);
                    float bx1 = mapX(tileEnd);
                    if (bx0 > W) break;
                    if (bx1 >= 0) {
                        canvas.drawBitmap(b, null,
                                new android.graphics.RectF(bx0, 0, bx1, H), null);
                    }
                    msAcc = tileEnd;
                    i++;
                }
                drawn = true;
            }
        }
        if (!drawn) {
            canvas.drawText("Loading thumbnails: " + fmt(playheadMs) + " / " + fmt(durationMs),
                    W / 2f, H / 2f, txt);
        }

        float s0 = mapX(startMs);
        float s1 = mapX(endMs);
        if (s0 > 0) canvas.drawRect(0, 0, Math.min(s0, W), H, dim);
        if (s1 < W) canvas.drawRect(Math.max(0, s1), 0, W, H, dim);

        if (s1 > 0 && s0 < W) {
            canvas.drawRect(Math.max(0, s0), 2 * dp, s1, H - 2 * dp, sel);
        }
        float hw = 9 * dp;
        if (s0 > -hw && s0 < W + hw) {
            canvas.drawRoundRect(s0 - hw / 2, H / 2f - 22 * dp, s0 + hw / 2, H / 2f + 22 * dp,
                    4 * dp, 4 * dp, handle);
        }
        if (s1 > -hw && s1 < W + hw) {
            canvas.drawRoundRect(s1 - hw / 2, H / 2f - 22 * dp, s1 + hw / 2, H / 2f + 22 * dp,
                    4 * dp, 4 * dp, handle);
        }

        float hx = mapX(playheadMs);
        if (hx >= 0 && hx <= W) {
            canvas.drawLine(hx, 0, hx, H, head);
            canvas.drawCircle(hx, 4 * dp, 3.5f * dp, handle);
        }
    }

    /* ---- touch ---- */

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (durationMs <= 1) return false;
        pinch.onTouchEvent(ev);
        float x = ev.getX();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                lastX = x;
                moved = false;
                pinched = false;
                onStartTouch(x);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                moved = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > ViewConfiguration.get(getContext())
                        .getScaledTouchSlop()) moved = true;
                if (!pinched) {
                    if (drag == 1) {
                        startMs = clamp(Math.min(mapMs(x), endMs - 500));
                        notifyRange();
                    } else if (drag == 2) {
                        endMs = clamp(Math.max(mapMs(x), startMs + 500));
                        endMs = Math.min(endMs, durationMs);
                        notifyRange();
                    } else if (zoom > 1f) {
                        if (moved) {
                            panMs = clampPan(panMs - (long) ((x - lastX)
                                    * durationMs / (getWidth() * zoom)));
                            invalidate();
                        }
                        lastX = x;
                    } else {
                        setPlayheadInternal(clamp(mapMs(x)));
                        if (listener != null) listener.onPlayheadChanged(playheadMs);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!moved && !pinched && drag == 0) {
                    long ms = clamp(mapMs(x));
                    setPlayheadInternal(ms);
                    if (listener != null) listener.onPlayheadChanged(ms);
                }
                drag = 0;
                pinched = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private void onStartTouch(float x) {
        float s0 = mapX(startMs);
        float s1 = mapX(endMs);
        float grab = 26 * dp;
        if (Math.abs(x - s0) <= grab && s0 >= -grab && s0 <= getWidth() + grab) drag = 1;
        else if (Math.abs(x - s1) <= grab && s1 >= -grab && s1 <= getWidth() + grab) drag = 2;
        else drag = 0;
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
