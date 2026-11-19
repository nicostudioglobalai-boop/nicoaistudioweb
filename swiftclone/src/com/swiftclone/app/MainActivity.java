package com.swiftclone.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private ViewFlipper flipper;
    private TextView[] tabs;

    private TextView ownerStatus, secStatus, usageStatus;
    private TextView dpmCmd, secCmd;
    private TextView spaceStatus, cloneStatus, fastStatus, animCurrent;
    private CheckBox showSystem;
    private LinearLayout appList;
    private Button btnCreateSpace, btnOpenSpace, btnProvision;

    private final List<String> pkgNames = new ArrayList<String>();
    private final List<CheckBox> boxes = new ArrayList<CheckBox>();

    private static final int MODE_NONE = 0;
    private static final int MODE_DEVICE_OWNER = 1;
    private static final int MODE_PROFILE_OWNER = 2;

    private static final String[] ANIM_KEYS = {
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE
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

        ownerStatus = (TextView) findViewById(R.id.ownerStatus);
        secStatus = (TextView) findViewById(R.id.secStatus);
        usageStatus = (TextView) findViewById(R.id.usageStatus);
        dpmCmd = (TextView) findViewById(R.id.dpmCmd);
        secCmd = (TextView) findViewById(R.id.secCmd);
        spaceStatus = (TextView) findViewById(R.id.spaceStatus);
        cloneStatus = (TextView) findViewById(R.id.cloneStatus);
        fastStatus = (TextView) findViewById(R.id.fastStatus);
        animCurrent = (TextView) findViewById(R.id.animCurrent);
        showSystem = (CheckBox) findViewById(R.id.showSystem);
        appList = (LinearLayout) findViewById(R.id.appList);
        btnCreateSpace = (Button) findViewById(R.id.btnCreateSpace);
        btnOpenSpace = (Button) findViewById(R.id.btnOpenSpace);

        dpmCmd.setText("adb shell dpm set-device-owner com.swiftclone.app/.AdminReceiver");
        secCmd.setText("adb shell pm grant com.swiftclone.app android.permission.WRITE_SECURE_SETTINGS");
        btnProvision = (Button) findViewById(R.id.btnProvision);
        btnProvision.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { provisionProfile(); }
        });

        showSystem.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { buildAppList(); }
        });

        findViewById(R.id.btnCopyDpm).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { copy(dpmCmd.getText().toString(), ownerStatus, "Command copied ✓"); }
        });
        findViewById(R.id.btnCopySec).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { copy(secCmd.getText().toString(), secStatus, "Command copied ✓"); }
        });
        findViewById(R.id.btnUsage).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception ignored) {}
            }
        });
        btnCreateSpace.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { createSpace(); }
        });
        btnOpenSpace.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { openSpace(); }
        });
        findViewById(R.id.btnClone).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { cloneSelected(); }
        });
        findViewById(R.id.btnFast).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { runFastMode(); }
        });

        int[][] presets = {{R.id.anim100, 100}, {R.id.anim50, 50}, {R.id.anim25, 25}, {R.id.anim0, 0}};
        for (final int[] p : presets) {
            findViewById(p[0]).setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { setAnimationScale(p[1] / 100f); }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyMode();
        refreshStatus();
        buildAppList();
        refreshAnimLine(animValue());
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

    private void copy(String text, TextView feedback, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("cmd", text));
        if (feedback != null) feedback.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int mode() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        try { if (dpm.isProfileOwnerApp(getPackageName())) return MODE_PROFILE_OWNER; } catch (Exception ignored) {}
        try { if (dpm.isDeviceOwnerApp(getPackageName())) return MODE_DEVICE_OWNER; } catch (Exception ignored) {}
        return MODE_NONE;
    }

    private void applyMode() {
        int m = mode();
        btnCreateSpace.setVisibility(m == MODE_DEVICE_OWNER ? View.VISIBLE : View.GONE);
        btnOpenSpace.setVisibility(m == MODE_DEVICE_OWNER ? View.VISIBLE : View.GONE);
        if (m == MODE_PROFILE_OWNER) {
            selectTab(1);
        }
    }

    private void provisionProfile() {
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE);
            i.putExtra("android.app.extra.PROFILE_ADMIN_NAME", new ComponentName(this, AdminReceiver.class));
            i.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true);
            if (i.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, "This phone's wizard isn't available — use the LADB command below instead.", Toast.LENGTH_LONG).show();
                return;
            }
            ownerStatus.setText("Opening the official setup wizard… follow it, then reopen SwiftClone then the wizard finishes.");
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Setup wizard blocked (" + e.getClass().getSimpleName()
                    + ") — use the LADB command below instead.", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        int m = mode();
        boolean usage = hasUsageAccess();
        boolean sec = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        usageStatus.setText(usage ? "Usage access: granted ✓" : "Usage access: not granted — tap below and allow SwiftClone.");
        usageStatus.setTextColor(getColor(usage ? R.color.accent_green : R.color.text_secondary));
        secStatus.setText(sec ? "Animation boost: unlocked ✓" : "Animation boost: not yet unlocked — optional, command below.");
        secStatus.setTextColor(getColor(sec ? R.color.accent_green : R.color.text_secondary));

        if (m == MODE_PROFILE_OWNER) {
            ownerStatus.setText("✓ CLONING READY — no computer needed! This copy of SwiftClone is provisioned inside the clone space. Pick apps from your main profile in the Clone Space tab and duplicate them.");
            ownerStatus.setTextColor(getColor(R.color.accent_green));
            dpmCmd.setText("Setup complete — no further commands needed.");
            spaceStatus.setText("Mode: inside the clone space. Select your apps and duplicate them.");
            refreshSpaceStatus(true);
        } else if (m == MODE_DEVICE_OWNER) {
            ownerStatus.setText("Cloning powers: ACTIVE ✓  (Go to the Clone Space tab)");
            ownerStatus.setTextColor(getColor(R.color.accent_green));
            dpmCmd.setText("Setup complete — no further commands needed.");
            refreshSpaceStatus(true);
        } else {
            ownerStatus.setText("Not set up yet. Try the one-tap wizard below first; if your phone blocks it, use the command with LADB/computer instead.");
            ownerStatus.setTextColor(getColor(R.color.text_secondary));
            dpmCmd.setText("adb shell dpm set-device-owner com.swiftclone.app/.AdminReceiver");
            refreshSpaceStatus(false);
        }
    }

    private void refreshSpaceStatus(boolean ready) {
        int m = mode();
        if (m == MODE_DEVICE_OWNER) {
            if (spaceUserId() >= 0) {
                spaceStatus.setText("Status: active ✓ (" + clonedCount() + " apps duplicated so far)");
            } else {
                spaceStatus.setText("Status: no clone space yet — tap 'Create space'.");
            }
        } else if (m == MODE_PROFILE_OWNER) {
            spaceStatus.setText("Status: ready ✓ — apps already installed here: " + clonedCount());
        } else {
            spaceStatus.setText("Status: setup first (Setup tab).");
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("swiftclone", Context.MODE_PRIVATE);
    }

    private int spaceUserId() {
        return prefs().getInt("space_user", -10);
    }

    private int clonedCount() {
        return prefs().getInt("cloned_count", 0);
    }

    private void bumpCount(int n) {
        prefs().edit().putInt("cloned_count", clonedCount() + n).apply();
    }

    private int handleId(UserHandle uh) {
        try {
            Method m = UserHandle.class.getDeclaredMethod("getIdentifier");
            m.setAccessible(true);
            return (Integer) m.invoke(uh);
        } catch (Exception e) {
            return -10;
        }
    }

    private UserHandle handleForId(int id) {
        if (id < 0) return null;
        try {
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            List<UserHandle> profiles = um.getUserProfiles();
            if (profiles != null) {
                for (UserHandle uh : profiles) {
                    if (handleId(uh) == id) return uh;
                }
            }
        } catch (Exception ignored) {}
        try {
            return UserHandle.class.getConstructor(int.class).newInstance(id);
        } catch (Exception e) {
            return null;
        }
    }

    private void createSpace() {
        cloneStatus.setTextColor(getColor(R.color.text_secondary));
        if (mode() != MODE_DEVICE_OWNER) {
            cloneStatus.setTextColor(getColor(R.color.accent_amber));
            cloneStatus.setText("Finish setup first (Setup tab).");
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            UserHandle uh = dpm.createAndManageUser(admin, "SwiftClone Space", admin, null,
                    DevicePolicyManager.SKIP_SETUP_WIZARD);
            int id = handleId(uh);
            if (id < 0) {
                cloneStatus.setTextColor(getColor(R.color.accent_amber));
                cloneStatus.setText("Could not read the new profile id — try again.");
                return;
            }
            prefs().edit().putInt("space_user", id).putInt("cloned_count", 0).apply();
            try { dpm.startUserInBackground(admin, uh); } catch (Exception ignored) {}
            cloneStatus.setTextColor(getColor(R.color.accent_green));
            cloneStatus.setText("Clone space created ✓ — select apps below and duplicate them.");
            refreshSpaceStatus(true);
        } catch (Exception e) {
            cloneStatus.setTextColor(getColor(R.color.accent_amber));
            cloneStatus.setText("Your phone's system blocked profile creation (" + e.getClass().getSimpleName()
                    + "). Try removing accounts and rebooting, or factory-fresh state.");
        }
    }

    private void openSpace() {
        int id = spaceUserId();
        if (mode() != MODE_DEVICE_OWNER || id < 0) {
            cloneStatus.setTextColor(getColor(R.color.accent_amber));
            cloneStatus.setText("No clone space yet — create it first.");
            return;
        }
        UserHandle uh = handleForId(id);
        if (uh == null) {
            cloneStatus.setTextColor(getColor(R.color.accent_amber));
            cloneStatus.setText("Could not access the stored space profile — recreate it.");
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.startUserInBackground(new ComponentName(this, AdminReceiver.class), uh);
        } catch (Exception ignored) {}
        boolean switched = false;
        String err = "";
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                switched = dpm.switchUser(new ComponentName(this, AdminReceiver.class), uh);
            } catch (Exception e) {
                err = e.getClass().getSimpleName();
            }
        }
        if (!switched) {
            cloneStatus.setTextColor(getColor(R.color.text_secondary));
            cloneStatus.setText("Space is running. If it doesn't open, switch to 'SwiftClone Space' from Settings → System → Multiple users."
                    + (err.isEmpty() ? "" : " (" + err + ")"));
        }
    }

    private void cloneSelected() {
        final List<String> selection = new ArrayList<String>();
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).isChecked()) selection.add(pkgNames.get(i));
        }
        if (selection.isEmpty()) {
            cloneStatus.setTextColor(getColor(R.color.accent_amber));
            cloneStatus.setText("Select at least one app first.");
            return;
        }
        final int m = mode();
        if (m == MODE_NONE) {
            cloneStatus.setTextColor(getColor(R.color.accent_amber));
            cloneStatus.setText("Finish setup first (Setup tab — one-tap wizard, no computer needed).");
            return;
        }
        if (m == MODE_DEVICE_OWNER && spaceUserId() < 0) {
            cloneStatus.setTextColor(getColor(R.color.accent_amber));
            cloneStatus.setText("Tap 'Create space' first.");
            return;
        }
        final int targetUser = (m == MODE_DEVICE_OWNER) ? spaceUserId() : handleId(Process.myUserHandle());
        cloneStatus.setTextColor(getColor(R.color.text_secondary));
        cloneStatus.setText("Duplicating " + selection.size() + " app(s)…");
        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0;
                String firstError = null;
                for (String pkg : selection) {
                    int res = installForUser(pkg, targetUser);
                    if (res == 0 || res == 1) ok++;
                    else if (firstError == null) firstError = "code " + res;
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                }
                final int fOk = ok;
                final String fErr = firstError;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        bumpCount(fOk);
                        refreshSpaceStatus(true);
                        cloneStatus.setTextColor(getColor(fOk > 0 ? R.color.accent_green : R.color.accent_amber));
                        if (mode() == MODE_PROFILE_OWNER) {
                            cloneStatus.setText("Duplicated " + fOk + "/" + selection.size()
                                    + " app(s) into this space ✓. Switch back to your main profile to add more.");
                        } else {
                            cloneStatus.setText("Duplicated " + fOk + "/" + selection.size()
                                    + " app(s). Open the space to use them.");
                        }
                        if (fErr != null) cloneStatus.append("  (first failure: " + fErr + ")");
                    }
                });
            }
        }).start();
    }

    private int installForUser(String pkg, int userId) {
        PackageManager pm = getPackageManager();
        try {
            Method m = PackageManager.class.getMethod("installExistingPackageAsUser", String.class, int.class);
            return (Integer) m.invoke(pm, pkg, userId);
        } catch (NoSuchMethodException e) {
            try {
                Method m = PackageManager.class.getMethod("installExistingPackage", String.class, int.class);
                return (Integer) m.invoke(pm, pkg, userId);
            } catch (Exception e2) {
                return -100;
            }
        } catch (Exception e) {
            return -101;
        }
    }

    private void runFastMode() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        fastStatus.setTextColor(getColor(R.color.text_secondary));
        fastStatus.setText("Working…");
        new Thread(new Runnable() {
            @Override public void run() {
                List<String> targets = recentApps();
                int killed = 0;
                for (String pkg : targets) {
                    try {
                        am.killBackgroundProcesses(pkg);
                        killed++;
                        Thread.sleep(40);
                    } catch (Exception ignored) {}
                }
                int[] buckets = raiseBuckets();
                try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
                final long freed = Math.max(0L, after.availMem - before.availMem);
                final int fKilled = killed;
                final int fBuckets = buckets[0];
                final int fBucketsFail = buckets[1];
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        fastStatus.setTextColor(getColor(R.color.accent_green));
                        String s = String.format(Locale.getDefault(),
                                "Done ✓  •  closed %d background app(s)  •  freed about %s of RAM", fKilled, fmtBytes(freed));
                        if (fBuckets > 0) s += String.format(Locale.getDefault(), "  •  %d app(s) set to stay Active (no throttling)", fBuckets);
                        else if (fBucketsFail > 0) s += "  •  Active-priority boost not allowed on this system (background closing still worked)";
                        fastStatus.setText(s);
                    }
                });
            }
        }).start();
    }

    private int[] raiseBuckets() {
        int ok = 0, fail = 0;
        if (Build.VERSION.SDK_INT < 28) return new int[]{0, 0};
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            Method m = UsageStatsManager.class.getMethod("setAppStandbyBucket", String.class, int.class);
            List<PackageInfo> all = getPackageManager().getInstalledPackages(0);
            for (PackageInfo pi : all) {
                String pkg = pi.packageName;
                if (pkg.equals(getPackageName()) || pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.")) continue;
                try {
                    m.invoke(usm, pkg, 10);
                    ok++;
                } catch (Exception e) {
                    fail++;
                }
            }
        } catch (Exception e) {
            return new int[]{0, -1};
        }
        return new int[]{ok, fail};
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
        } catch (Exception ignored) {}
        return result;
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        try {
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void buildAppList() {
        appList.removeAllViews();
        boxes.clear();
        pkgNames.clear();

        int m = mode();
        PackageManager pm = getPackageManager();
        List<String[]> rows = new ArrayList<String[]>();
        boolean showSys = showSystem.isChecked();

        if (m == MODE_PROFILE_OWNER) {
            try {
                LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
                UserHandle parent = null;
                int myId = handleId(Process.myUserHandle());
                for (UserHandle uh : ((UserManager) getSystemService(Context.USER_SERVICE)).getUserProfiles()) {
                    if (handleId(uh) != myId) { parent = uh; break; }
                }
                if (parent == null) parent = Process.myUserHandle();
                List<LauncherActivityInfo> acts = la.getActivityList(null, parent);
                Set<String> seen = new HashSet<String>();
                for (LauncherActivityInfo a : acts) {
                    String pkg = a.getComponentName().getPackageName();
                    if (!seen.add(pkg)) continue;
                    rows.add(new String[]{String.valueOf(a.getLabel()), pkg});
                }
            } catch (Exception ignored) {}
        } else {
            List<PackageInfo> installed = pm.getInstalledPackages(0);
            for (PackageInfo pi : installed) {
                ApplicationInfo ai = pi.applicationInfo;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !showSys) continue;
                if ("com.swiftclone.app".equals(pi.packageName)) continue;
                rows.add(new String[]{String.valueOf(ai.loadLabel(pm)), pi.packageName});
            }
        }

        Collections.sort(rows, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        int pad = (int) (4 * getResources().getDisplayMetrics().density);
        for (String[] row : rows) {
            CheckBox box = new CheckBox(this);
            box.setText(row[0] + "\n" + row[1]);
            box.setTextSize(14);
            box.setTextColor(getColor(R.color.text_primary));
            box.setPadding(pad, pad, pad, pad);
            appList.addView(box);
            boxes.add(box);
            pkgNames.add(row[1]);
        }
    }

    private float animValue() {
        try {
            return Settings.Global.getFloat(getContentResolver(), ANIM_KEYS[0], 1f);
        } catch (Exception e) {
            return -1f;
        }
    }

    private void refreshAnimLine(float current) {
        if (current < 0) animCurrent.setText("Current scale: locked (run the optional command in Setup)");
        else animCurrent.setText("Current scale: " + current + "x");
    }

    private void setAnimationScale(float value) {
        try {
            for (String key : ANIM_KEYS) {
                Settings.Global.putFloat(getContentResolver(), key, value);
            }
            refreshAnimLine(value);
            fastStatus.setTextColor(getColor(R.color.accent_green));
            fastStatus.setText("Animation scale set to " + value + "x ✓");
        } catch (Exception e) {
            refreshAnimLine(-1);
            fastStatus.setTextColor(getColor(R.color.accent_amber));
            fastStatus.setText("Not unlocked yet — copy and run the 'animation command' in Setup (no root).");
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
