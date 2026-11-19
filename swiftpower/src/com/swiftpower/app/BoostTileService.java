package com.swiftpower.app;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class BoostTileService extends TileService {

    @Override
    public void onClick() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }
        final Context ctx = this;
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = boost(ctx);
                android.os.Handler h = new android.os.Handler(getMainLooper());
                h.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(ctx, result, Toast.LENGTH_SHORT).show();
                        if (tile != null) tile.updateTile();
                    }
                });
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                h.post(new Runnable() {
                    @Override public void run() {
                        Tile t = getQsTile();
                        if (t != null) { t.setState(Tile.STATE_INACTIVE); t.updateTile(); }
                    }
                });
            }
        }).start();
    }

    static String boost(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        int killed = 0;
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now);
            if (stats != null) {
                for (UsageStats s : stats) {
                    String pkg = s.getPackageName();
                    if (pkg == null || pkg.contains("launcher") || pkg.startsWith("com.android.systemui")) continue;
                    try { am.killBackgroundProcesses(pkg); killed++; } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        long freed = Math.max(0, after.availMem - before.availMem);
        return String.format(Locale.getDefault(), "Boosted: %d apps closed, %s freed", killed, fmt(freed));
    }

    private static String fmt(long b) {
        double v = b;
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.getDefault(), "%.0f %s", v, u[i]);
    }
}
