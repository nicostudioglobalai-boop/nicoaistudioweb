package com.swiftpower.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class GuardService extends Service {

    private static final String CHANNEL = "guard";
    private static final int NOTIF_ID = 41;
    private static boolean running = false;

    private Handler handler;
    private long lastMs = 0;
    private int lastLevel = -1;
    private int lastTemp = -1;
    private boolean warned80 = false;
    private boolean warnedFull = false;
    private boolean warnedHot = false;
    private boolean warnedSlow = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Charging guard", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Charging guard active")
                .setContentText("Watching battery health - alerts for slow charge, heat, and full battery.")
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);

        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(tick, 1500);
        }
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) { schedule(); return; }
            int level = b.getIntExtra("level", -1);
            int scale = b.getIntExtra("scale", 100);
            int plugged = b.getIntExtra("plugged", 0);
            int temp = b.getIntExtra("temperature", 0);
            int pct = (level >= 0) ? level * 100 / Math.max(1, scale) : -1;

            if (plugged == 0) {
                alert("Charger unplugged", "Charging guard stopped itself to save battery.");
                stopSelfSafely();
                return;
            }
            if (lastMs > 0 && lastLevel >= 0 && level >= 0 && level != lastLevel) {
                float minutes = (System.currentTimeMillis() - lastMs) / 60000f;
                if (minutes > 0.5f && minutes < 30f) {
                    float rate = (level - lastLevel) / minutes;
                    if (rate > 0 && rate < 0.35f && !warnedSlow && minutes >= 3f) {
                        warnedSlow = true;
                        alert("Slow charging detected", "Only " + String.format(java.util.Locale.getDefault(), "%.2f", rate)
                                + "%/min - the cable or adapter may be weak or the port dirty.");
                    }
                }
            }
            if (temp >= 405 && !warnedHot) {
                warnedHot = true;
                alert("Battery is hot", (temp / 10f) + "C while charging - remove any case, keep the phone out of the sun, or let it cool.");
            }
            if (temp < 380) warnedHot = false;

            if (pct >= 80 && pct < 95 && !warned80) {
                warned80 = true;
                alert("Battery at 80%", "Charging to 100% wears lithium batteries faster - unplugging now extends battery life.");
            }
            if (pct < 75) warned80 = false;
            if (pct >= 95 && !warnedFull) {
                warnedFull = true;
                alert("Battery full", "You can unplug the charger.");
            }
            if (pct < 90) warnedFull = false;

            lastMs = System.currentTimeMillis();
            lastLevel = level;
            lastTemp = temp;
            schedule();
        }
    };

    private void schedule() {
        handler.postDelayed(tick, 30000);
    }

    private void stopSelfSafely() {
        running = false;
        if (handler != null) handler.removeCallbacks(tick);
        stopForeground(true);
        stopSelf();
    }

    private void alert(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
