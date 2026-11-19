package com.swiftpower.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private ViewFlipper flipper;
    private TextView[] tabs;
    private TextView boostStatus, battLine, battSub, guardStatus, storageSummary, netResult, netInfo;
    private LinearLayout mediaList;
    private Button btnGuardStart;

    private static final int REQ_MEDIA = 7;

    private final List<Long> rowSizes = new ArrayList<Long>();
    private final List<Uri> rowUris = new ArrayList<Uri>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        flipper = (ViewFlipper) findViewById(R.id.flipper);
        tabs = new TextView[]{(TextView) findViewById(R.id.tab0), (TextView) findViewById(R.id.tab1),
                (TextView) findViewById(R.id.tab2), (TextView) findViewById(R.id.tab3)};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { selectTab(index); }
            });
        }
        selectTab(0);

        boostStatus = (TextView) findViewById(R.id.boostStatus);
        battLine = (TextView) findViewById(R.id.battLine);
        battSub = (TextView) findViewById(R.id.battSub);
        guardStatus = (TextView) findViewById(R.id.guardStatus);
        storageSummary = (TextView) findViewById(R.id.storageSummary);
        netResult = (TextView) findViewById(R.id.netResult);
        netInfo = (TextView) findViewById(R.id.netInfo);
        mediaList = (LinearLayout) findViewById(R.id.mediaList);
        btnGuardStart = (Button) findViewById(R.id.btnGuardStart);

        findViewById(R.id.btnOpenTiles).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent("android.settings.ACTION_QS_TILE_SETTINGS")); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            }
        });
        findViewById(R.id.btnBoost).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { boostNow(); }
        });
        btnGuardStart.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { startGuard(); }
        });
        findViewById(R.id.btnGuardStop).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, GuardService.class));
                guardStatus.setText("Guard stopped.");
                refreshGuardUi();
            }
        });
        findViewById(R.id.btnScan).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { scanMedia(); }
        });
        findViewById(R.id.btnNetTest).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { netTest(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBattery();
        refreshGuardUi();
        refreshNetworkInfo();
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

    private void boostNow() {
        boostStatus.setTextColor(getColor(R.color.text_secondary));
        boostStatus.setText("Boosting…");
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        new Thread(new Runnable() {
            @Override public void run() {
                int killed = 0;
                try {
                    UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                    long now = System.currentTimeMillis();
                    List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now);
                    if (stats != null) {
                        for (UsageStats s : stats) {
                            String pkg = s.getPackageName();
                            if (pkg == null || pkg.equals(getPackageName())) continue;
                            if (pkg.contains("launcher") || pkg.startsWith("com.android.systemui")) continue;
                            try { am.killBackgroundProcesses(pkg); killed++; Thread.sleep(40); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
                final long freed = Math.max(0, after.availMem - before.availMem);
                final int k = killed;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boostStatus.setTextColor(getColor(R.color.accent_green));
                        boostStatus.setText(String.format(Locale.getDefault(),
                                "Done ✓  •  closed %d background apps  •  freed about %s of RAM", k, fmt(freed)));
                    }
                });
            }
        }).start();
    }

    private void startGuard() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        try {
            startForegroundService(new Intent(this, GuardService.class));
            guardStatus.setTextColor(getColor(R.color.accent_green));
            guardStatus.setText("Guard started ✓ — alerts pop up while charging. It stops itself when you unplug.");
        } catch (Exception e) {
            guardStatus.setTextColor(getColor(R.color.accent_amber));
            guardStatus.setText("Could not start (" + e.getClass().getSimpleName() + ")");
        }
        refreshGuardUi();
    }

    private void refreshGuardUi() {
        boolean on = GuardService.isRunning();
        btnGuardStart.setText(on ? "Guard running ✓" : "Start guard");
        if (!on) guardStatus.setText("Guard off — start it whenever the phone is (or will be) charging.");
    }

    private void refreshBattery() {
        Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra("level", -1);
        int scale = b.getIntExtra("scale", 100);
        int plugged = b.getIntExtra("plugged", 0);
        int status = b.getIntExtra("status", -1);
        int temp = b.getIntExtra("temperature", 0);
        int volt = b.getIntExtra("voltage", 0);
        int pct = (level >= 0) ? level * 100 / Math.max(1, scale) : -1;
        String state = plugged != 0 ? (status == 5 ? "Full" : "Charging") : "On battery";
        battLine.setText((pct >= 0 ? pct + "%  •  " : "") + state
                + "  •  " + (temp / 10f) + "°C  •  " + (volt / 1000f) + "V");
        battSub.setText(pct >= 80 && plugged == 0
                ? "Healthy range — batteries live longest between 20% and 80%."
                : "Tip: keeping the battery between 20% and 80% drastically extends its life.");
    }

    private void scanMedia() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            List<String> perms = new ArrayList<String>();
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            requestPermissions(perms.toArray(new String[0]), REQ_MEDIA);
            return;
        }
        storageSummary.setTextColor(getColor(R.color.text_secondary));
        storageSummary.setText("Scanning…");
        mediaList.removeAllViews();
        rowSizes.clear();
        rowUris.clear();
        for (Uri base : new Uri[]{MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI}) {
            try {
                Cursor c = getContentResolver().query(base,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE},
                        null, null, MediaStore.MediaColumns.SIZE + " DESC");
                if (c != null) {
                    int n = 0;
                    while (c.moveToNext() && n < 15) {
                        rowUris.add(ContentUris.withAppendedId(base, c.getLong(0)));
                        rowSizes.add(c.getLong(1));
                        n++;
                    }
                    c.close();
                }
            } catch (Exception ignored) {}
        }
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < rowSizes.size(); i++) order.add(i);
        java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Long.compare(rowSizes.get(b), rowSizes.get(a));
            }
        });
        long total = 0;
        for (long s : rowSizes) total += s;
        final List<Integer> finalOrder = order;
        final long finalTotal = total;
        storageSummary.setTextColor(getColor(R.color.text_primary));
        storageSummary.setText(String.format(Locale.getDefault(),
                "Found %d big items — about %s of space. Tap any to open it in the gallery and delete it there.",
                rowSizes.size(), fmt(finalTotal)));
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        for (final int idx : finalOrder) {
            TextView tv = new TextView(this);
            boolean isVideo = rowUris.get(idx).toString().contains("video");
            tv.setText((isVideo ? "▶ Video" : "🖼 Photo") + "  •  " + fmt(rowSizes.get(idx)));
            tv.setTextSize(14);
            tv.setTextColor(getColor(R.color.text_secondary));
            tv.setPadding(pad, pad / 2, pad, pad / 2);
            tv.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, rowUris.get(idx))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Can't open this file", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            mediaList.addView(tv);
        }
    }

    private void netTest() {
        netResult.setTextColor(getColor(R.color.text_secondary));
        netResult.setText("Testing (about 10 seconds)…");
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("Ping: ").append(pingHost("8.8.8.8")).append("\n");
                sb.append("DNS: ").append(dnsTest()).append("\n");
                sb.append("Speed: ").append(speedTest());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        netResult.setTextColor(getColor(R.color.text_primary));
                        netResult.setText(sb.toString());
                    }
                });
            }
        }).start();
    }

    private String pingHost(String host) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ping", "-c", "3", "-W", "2", host});
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line, avg = null;
            while ((line = br.readLine()) != null) {
                if (line.contains("rtt") || line.contains("round-trip")) {
                    String[] parts = line.split("=");
                    if (parts.length > 1) {
                        String[] vals = parts[1].trim().split(" ")[0].split("/");
                        if (vals.length > 1) avg = vals[1] + " ms avg";
                    }
                }
            }
            p.waitFor();
            if (avg != null) return avg;
            long t0 = System.currentTimeMillis();
            InetAddress.getByName(host);
            return "≈" + (System.currentTimeMillis() - t0) + " ms";
        } catch (Exception e) {
            try {
                long t0 = System.currentTimeMillis();
                InetAddress.getByName(host);
                return "≈" + (System.currentTimeMillis() - t0) + " ms";
            } catch (Exception e2) {
                return "no connection";
            }
        }
    }

    private String dnsTest() {
        try {
            long t0 = System.currentTimeMillis();
            InetAddress addr = InetAddress.getByName("www.google.com");
            long ms = System.currentTimeMillis() - t0;
            return addr.getHostAddress() + " resolved in " + ms + " ms" + (ms > 500 ? " (slow DNS — try changing DNS)" : "");
        } catch (Exception e) {
            return "failed — no working internet";
        }
    }

    private String speedTest() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://dl.google.com/android/repository/platform-34-ext7_r03.zip");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.connect();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            long read = 0;
            long t0 = System.currentTimeMillis();
            int n;
            while (read < 2000000 && (n = in.read(buf)) != -1) {
                read += n;
            }
            in.close();
            long ms = System.currentTimeMillis() - t0;
            if (ms <= 0) return "n/a";
            double mbps = (read * 8 / 1000.0) / ms;
            return String.format(Locale.getDefault(), "%.1f Mbps (%s in %d ms)", mbps, fmt(read), ms);
        } catch (Exception e) {
            return "test failed — " + e.getClass().getSimpleName();
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void refreshNetworkInfo() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            String type = ni != null ? ni.getTypeName() : "none";
            String detail = "";
            if (ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    int rssi = wi.getRssi();
                    String q = rssi > -55 ? "excellent" : rssi > -67 ? "good" : rssi > -75 ? "weak" : "very weak";
                    detail = "  •  link " + wi.getLinkSpeed() + " Mbps  •  signal " + q
                            + "  •  " + wi.getSSID().replace("\"", "");
                }
            } else if (ni != null && ni.getSubtypeName() != null) {
                detail = "  •  " + ni.getSubtypeName();
            }
            netInfo.setText("Network: " + type + detail);
        } catch (Exception e) {
            netInfo.setText("Network info unavailable");
        }
    }

    private static String fmt(long b) {
        double v = b;
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.getDefault(), "%.1f %s", v, u[i]);
    }
}
