package com.swiftdual.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String[][] APPS = {
            {"WhatsApp", "com.whatsapp",
                    "Open WhatsApp ▸ Settings ▸ tap the small arrow beside your name ▸ Add account."},
            {"Instagram", "com.instagram.android",
                    "Open Instagram ▸ press and hold your profile picture ▸ Add account."},
            {"Facebook", "com.facebook.katana",
                    "Open Facebook ▸ Menu (☰) ▸ Settings ▸ tap your profile ▸ Switch accounts ▸ Add."},
            {"Messenger", "com.facebook.orca",
                    "Open Messenger ▸ tap your profile picture ▸ Switch account ▸ Add account."},
            {"Telegram", "org.telegram.messenger",
                    "Open Telegram ▸ Settings ▸ Expand (arrow) ▸ Add Account. Up to 4 accounts on one phone."},
            {"X (Twitter)", "com.twitter.android",
                    "Open X ▸ profile icon ▸ long-press your name/avatar ▸ Add an existing account."},
            {"TikTok", "com.zhiliaoapp.musically",
                    "Open TikTok ▸ Profile ▸ tap your name ▸ Switch account ▸ Add account."},
            {"YouTube", "com.google.android.youtube",
                    "Tap profile picture ▸ Switch account ▸ Add account (a second Google account)."},
            {"Gmail", "com.google.android.gm",
                    "Tap profile picture ▸ Add another account (email: baked-in multi-account)."},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        LinearLayout list = findViewById(R.id.list);
        float dp = getResources().getDisplayMetrics().density;

        PackageManager pm = getPackageManager();
        int added = 0;
        for (String[] a : APPS) {
            View row = row(a, pm, dp);
            if (row != null) list.addView(row);
            if (row != null) added++;
        }
        if (added == 0) {
            TextView t = new TextView(this);
            t.setTextColor(0xFF94A3B8);
            t.setTextSize(13);
            t.setPadding(0, (int) (8 * dp), 0, 0);
            t.setText("None of these apps are installed yet. Install any of them and reopen SwiftDual.");
            list.addView(t);
        }
    }

    private View row(String[] a, PackageManager pm, float dp) {
        boolean installed;
        try {
            pm.getPackageInfo(a[1], 0);
            installed = true;
        } catch (Exception e) {
            installed = false;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, (int) (14 * dp));
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.card_inner);
        card.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (12 * dp));

        TextView name = new TextView(this);
        name.setText(a[0] + (installed ? "" : "  (not installed)"));
        name.setTextColor(installed ? Color.parseColor("#F1F5F9") : Color.parseColor("#6B7280"));
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        TextView pkg = new TextView(this);
        pkg.setText(a[1]);
        pkg.setTextColor(Color.parseColor("#64748B"));
        pkg.setTextSize(11);
        card.addView(pkg);

        TextView tip = new TextView(this);
        tip.setText(a[2]);
        tip.setTextColor(Color.parseColor("#94A3B8"));
        tip.setTextSize(12);
        tip.setPadding(0, (int) (6 * dp), 0, (int) (10 * dp));
        card.addView(tip);

        if (!installed) return card;

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(buttons);

        Button open = new Button(this);
        open.setText("OPEN " + a[0].toUpperCase());
        open.setTextColor(Color.parseColor("#071A1E"));
        open.setBackgroundResource(R.drawable.btn_accent);
        open.setAllCaps(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp.rightMargin = (int) (8 * dp);
        open.setLayoutParams(bp);
        open.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(a[1]);
            if (i == null) {
                toast("Can't open " + a[0]);
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        buttons.addView(open);

        Button info = new Button(this);
        info.setText("APP INFO");
        info.setTextColor(Color.parseColor("#F1F5F9"));
        info.setBackgroundResource(R.drawable.btn_outline);
        info.setAllCaps(false);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a[1])));
            } catch (Exception ex) {
                toast("Settings page not available");
            }
        });
        buttons.addView(info);
        return card;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
