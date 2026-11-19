package com.swiftboost.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ViewFlipper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private ViewFlipper flipper;
    private TextView[] tabs;

    private TextView batteryPct, batteryDetail, ramText, storageText, deviceText;
    private ProgressBar batteryBar, ramBar, storageBar;
    private TextView boostStatus, animCurrent, permStatus, adbCmd;

    private static final String ANIM_KEYS[] = {
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE
    };

    private static final String[][] TIPS = {
            {"Reduce animations", "Windows, transitions and animator scales above 1x waste time on every tap and burn CPU. Set them to 0.5x or lower — that alone can make the phone feel 2x faster (Boost tab)."},
            {"Trim background apps", "Apps you rarely open still wake up, sync and hold RAM. Use Boost Now to close them, and in Settings > Battery set apps you rarely use to 'Restricted' battery usage."},
            {"Disable auto-sync you don't need", "Accounts constantly syncing (cloud galleries, old email, chat backups) cause wake-ups. Turn off auto-sync for non-critical accounts in Settings > Accounts."},
            {"Free storage", "Keep at least 15-20% of internal storage free. When storage is nearly full, Android slows down writes and app launches. Clear app caches and delete old downloads."},
            {"Uninstall or disable bloatware", "Preloaded apps you never use still update and run in the background. Uninstall or disable them in Settings > Apps."},
            {"Use Battery Saver smartly", "Battery Saver caps background work and performance. Turn it on around 20-30% battery, or leave Adaptive Battery on so the system learns your habits."},
            {"Lower refresh rate and brightness", "On 90/120 Hz phones, dropping to 60 Hz saves a lot of power. Lower screen brightness and short screen timeout are the two biggest battery savers of all."},
            {"Prefer Lite apps and dark theme", "Lite versions of heavy apps use less RAM and data. On OLED screens, a dark theme measurably reduces battery drain."},
            {"Avoid always-on booster apps", "Apps that run a permanent background 'cleaner' service drain the very battery they claim to save. SwiftBoost runs nothing in the background — you tap it when you need it."},
            {"Keep the system updated", "Android security and maintenance updates include runtime improvements from ART (app runtime). Keep both the system and your apps updated."}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        flipper = (ViewFlipper) findViewById(R.id.flipper);
        tabs = new TextView[]{(TextView) findViewById(R.id.tab0), (TextView) findViewById(R.id.tab1), (TextView) findViewById(R.id.tab2)};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { selectTab(index); }
            });
        }
        selectTab(0);

        batteryPct = (TextView) findViewById(R.id.batteryPct);
        batteryDetail = (TextView) findViewById(R.id.batteryDetail);
        batteryBar = (ProgressBar) findViewById(R.id.batteryBar);
        ramBar = (ProgressBar) findViewById(R.id.ramBar);
        ramText = (TextView) findViewById(R.id.ramText);
        storageBar = (ProgressBar) findViewById(R.id.storageBar);
        storageText = (TextView) findViewById(R.id.storageText);
        deviceText = (TextView) findViewById(R.id.deviceText);
        boostStatus = (TextView) findViewById(R.id.boostStatus);
        animCurrent = (TextView) findViewById(R.id.animCurrent);
        permStatus = (TextView) findViewById(R.id.permStatus);
        adbCmd = (TextView) findViewById(R.id.adbCmd);

        adbCmd.setText("adb shell pm grant com.swiftboost.app android.permission.WRITE_SECURE_SETTINGS");

        findViewById(R.id.btnSaver).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { openBatterySaver(); }
        });
        findViewById(R.id.btnBattOpt).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception e) { openBatterySaver(); }
            }
        });
        findViewById(R.id.btnBoost).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { boostRam(); }
        });

        int[][] presetIds = {{R.id.anim100, 100}, {R.id.anim50, 50}, {R.id.anim25, 25}, {R.id.anim0, 0}};
        for (final int[] p : presetIds) {
            findViewById(p[0]).setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { setAnimationScale(p[1] / 100f); }
            });
        }

        findViewById(R.id.btnCopy).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("adb", adbCmd.getText().toString()));
                permStatus.setText("Command copied — paste it in a computer terminal with the phone connected, USB debugging on.");
            }
        });

        buildTips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
        refreshBoostTab();
    }

    private void selectTab(int index) {
        flipper.setDisplayedChild(index);
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == index;
            tabs[i].setTextColor(getColor(active ? R.color.bg : R.color.text_secondary));
            tabs[i].setBackgroundColor(getColor(active ? R.color.accent : android.R.color.transparent));
            tabs[i].setTypeface(null, active ? 1 : 0);
        }
    }

    private void refreshDashboard() {
        Intent bat = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra("level", -1);
            int scale = bat.getIntExtra("scale", 100);
            int status = bat.getIntExtra("status", -1);
            int plugged = bat.getIntExtra("plugged", 0);
            int health = bat.getIntExtra("health", -1);
            int temp = bat.getIntExtra("temperature", 0);
            int volt = bat.getIntExtra("voltage", 0);
            String tech = bat.getStringExtra("technology");
            if (level >= 0) {
                int pct = level * 100 / Math.max(1, scale);
                batteryPct.setText(pct + "%");
                batteryBar.setProgress(pct);
            }
            String state;
            if (status == 5) state = "Full";
            else if (status == 2 || plugged != 0) state = "Charging";
            else state = "Discharging";
            String source = plugged == 1 ? "AC" : plugged == 2 ? "USB" : plugged == 4 ? "Wireless" : null;
            if (source != null) state += " (" + source + ")";
            StringBuilder sb = new StringBuilder(state);
            sb.append("  •  ").append(temp / 10f).append("°C");
            sb.append("  •  ").append(volt / 1000f).append("V");
            if (tech != null) sb.append("  •  ").append(tech);
            if (health == 2) sb.append("  •  Health: Good");
            else if (health == 3) sb.append("  •  Health: Overheat");
            else if (health == 4) sb.append("  •  Health: Dead");
            batteryDetail.setText(sb.toString());
            int color = getColor(pctColor(status == 2 || plugged != 0 ? 100 : level));
            batteryPct.setTextColor(color);
        }

        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        int usedPct = (int) (100 - mem.availMem * 100 / mem.totalMem);
        ramBar.setProgress(usedPct);
        ramBar.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(usedPct > 85 ? R.color.accent_amber : R.color.accent)));
        ramText.setText(String.format(Locale.getDefault(),
                "%s used of %s  •  %s free", fmtBytes(mem.totalMem - mem.availMem), fmtBytes(mem.totalMem), fmtBytes(mem.availMem)));

        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total = stat.getTotalBytes();
            long free = stat.getAvailableBytes();
            int freePct = (int) (free * 100 / total);
            storageBar.setProgress(100 - freePct);
            storageBar.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(freePct < 15 ? R.color.accent_amber : R.color.accent)));
            storageText.setText(String.format(Locale.getDefault(), "%s used of %s  •  %s free",
                    fmtBytes(total - free), fmtBytes(total), fmtBytes(free)));
            if (freePct < 15) storageText.append("\nStorage low — delete files to speed things up.");
        } catch (Exception ignored) {
        }

        deviceText.setText(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\nAndroid " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
    }

    private int pctColor(int pct) {
        if (pct <= 15) return R.color.accent_amber;
        if (pct <= 40) return R.color.accent;
        return R.color.accent_green;
    }

    private void refreshBoostTab() {
        boolean granted = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            float w = animValue(0);
            permStatus.setTextColor(getColor(R.color.accent_green));
            permStatus.setText("Turbo unlocked ✓  You can change animation speed below.");
            updateAnimLine(w);
        } else {
            permStatus.setTextColor(getColor(R.color.text_secondary));
            updateAnimLine(-1);
        }
    }

    private void updateAnimLine(float current) {
        if (current < 0) {
            animCurrent.setText("Current scale: locked (grant permission below)");
        } else {
            animCurrent.setText("Current scale: " + current + "x");
        }
    }

    private float animValue(int i) {
        try {
            return Settings.Global.getFloat(getContentResolver(), ANIM_KEYS[i], 1f);
        } catch (Exception e) {
            return -1f;
        }
    }

    private void setAnimationScale(float value) {
        boolean ok = true;
        try {
            for (String key : ANIM_KEYS) {
                Settings.Global.putFloat(getContentResolver(), key, value);
            }
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            updateAnimLine(value);
            boostStatus.setTextColor(getColor(R.color.accent_green));
            boostStatus.setText("Animation scale set to " + value + "x ✓");
        } else {
            boostStatus.setTextColor(getColor(R.color.accent_amber));
            boostStatus.setText("Permission missing — see 'Unlock Turbo Mode' below.");
            refreshBoostTab();
        }
    }

    private void openBatterySaver() {
        try { startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void boostRam() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        boostStatus.setTextColor(getColor(R.color.text_secondary));
        boostStatus.setText("Boosting…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> targets = recentApps();
                int killed = 0;
                for (String pkg : targets) {
                    try {
                        am.killBackgroundProcesses(pkg);
                        killed++;
                        Thread.sleep(40);
                    } catch (Exception ignored) {
                    }
                }
                try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                final ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
                long freed = after.availMem - before.availMem;
                final int n = killed;
                final long f = Math.max(0, freed);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshDashboard();
                        boostStatus.setTextColor(getColor(R.color.accent_green));
                        boostStatus.setText(String.format(Locale.getDefault(),
                                "Closed %d background apps • freed about %s of RAM", n, fmtBytes(f)));
                    }
                });
            }
        }).start();
    }

    private List<String> recentApps() {
        List<String> result = new ArrayList<String>();
        if (!hasUsageAccess()) return result;
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now);
            if (stats == null) return result;
            for (UsageStats s : stats) {
                String pkg = s.getPackageName();
                if (pkg == null || pkg.equals(getPackageName())) continue;
                if (pkg.contains("launcher") || pkg.startsWith("com.android.systemui")) continue;
                if (s.getLastTimeUsed() > now - 86400000L && !result.contains(pkg)) result.add(pkg);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        try {
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void buildTips() {
        LinearLayout list = (LinearLayout) findViewById(R.id.tipsList);
        for (String[] tip : TIPS) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            card.setPadding(pad, pad, pad, pad);
            card.setBackgroundResource(R.drawable.card_bg);

            TextView title = new TextView(this);
            title.setText(tip[0]);
            title.setTextColor(getColor(R.color.accent));
            title.setTextSize(16);
            title.setTypeface(null, 1);

            TextView body = new TextView(this);
            body.setText(tip[1]);
            body.setTextColor(getColor(R.color.text_secondary));
            body.setTextSize(13);
            body.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, 0);

            card.addView(title);
            card.addView(body);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
            list.addView(card, lp);
        }
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.getDefault(), "%.1f %s", v, u[i]);
    }
}
