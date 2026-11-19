package com.swiftvault.app;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_MEDIA = 7;
    private static final int REQ_TREE = 21;
    private static final int REQ_DELETE = 31;

    private LinearLayout meters, fileList;
    private TextView status;
    private Button btnMove;
    private Uri treeUri;

    private final List<Long> rowSizes = new ArrayList<Long>();
    private final List<Uri> rowUris = new ArrayList<Uri>();
    private final List<String> rowNames = new ArrayList<String>();
    private final List<CheckBox> rowBoxes = new ArrayList<CheckBox>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        meters = (LinearLayout) findViewById(R.id.meters);
        fileList = (LinearLayout) findViewById(R.id.fileList);
        status = (TextView) findViewById(R.id.status);
        btnMove = (Button) findViewById(R.id.btnMove);

        findViewById(R.id.btnPickDest).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { pickDestination(); }
        });
        findViewById(R.id.btnScan).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { scan(); }
        });
        btnMove.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { moveTicked(); }
        });

        String saved = getSharedPreferences("vault", Context.MODE_PRIVATE).getString("tree", null);
        if (saved != null) treeUri = Uri.parse(saved);

        refreshMeters();
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) {
            scan();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMeters();
        refreshStatusHead();
    }

    private void refreshStatusHead() {
        status.setText(treeUri == null
                ? "Destination not chosen yet - tap step 1."
                : "Destination ready (check mark) " + treeUri.getLastPathSegment());
    }

    private void pickDestination() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "Folder picker unavailable: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            treeUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            getSharedPreferences("vault", Context.MODE_PRIVATE).edit().putString("tree", treeUri.toString()).apply();
            refreshStatusHead();
        } else if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                status.setTextColor(getColor(R.color.accent_green));
                status.setText("Moved. Internal memory freed - scan again anytime.");
                rowBoxes.clear();
                rowSizes.clear();
                rowUris.clear();
                rowNames.clear();
                fileList.removeAllViews();
                refreshMeters();
            } else {
                status.setTextColor(getColor(R.color.accent_amber));
                status.setText("Copies are on the SD card but the originals were kept (cancelled). Nothing lost.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_MEDIA) {
            for (int g : grantResults) {
                if (g == PackageManager.PERMISSION_GRANTED) { scan(); break; }
            }
        }
    }

    private void refreshMeters() {
        meters.removeAllViews();
        int pad = (int) (4 * getResources().getDisplayMetrics().density);
        try {
            StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            List<StorageVolume> vols = sm.getStorageVolumes();
            for (StorageVolume vol : vols) {
                java.io.File dir = vol.getDirectory();
                if (dir == null) continue;
                try {
                    StatFs sf = new StatFs(dir.getAbsolutePath());
                    long total = sf.getTotalBytes();
                    long free = sf.getAvailableBytes();
                    if (total <= 0) continue;
                    TextView t = new TextView(this);
                    String name = "";
                    try { name = vol.getDescription(this); } catch (Exception ignored) {}
                    t.setText(String.format(Locale.getDefault(), "%s (%s): %s used of %s - %s free",
                            vol.isRemovable() ? "SD card" : "Internal", name,
                            fmt(total - free), fmt(total), fmt(free)));
                    t.setTextSize(13);
                    t.setTextColor(getColor(free * 100 / total < 15 ? R.color.accent_amber : R.color.text_secondary));
                    t.setPadding(pad, pad, pad, pad);
                    meters.addView(t);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void scan() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
            return;
        }
        rowSizes.clear();
        rowUris.clear();
        rowNames.clear();
        fileList.removeAllViews();
        for (Uri base : new Uri[]{MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI}) {
            try {
                Cursor c = getContentResolver().query(base,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE,
                                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED},
                        MediaStore.MediaColumns.SIZE + " > ?",
                        new String[]{"1000000"},
                        MediaStore.MediaColumns.SIZE + " DESC");
                if (c != null) {
                    int n = 0;
                    while (c.moveToNext() && n < 20) {
                        long id = c.getLong(0);
                        long size = c.getLong(1);
                        String name = c.getString(2);
                        long date = c.getLong(3);
                        rowUris.add(ContentUris.withAppendedId(base, id));
                        rowSizes.add(size);
                        rowNames.add((base == MediaStore.Video.Media.EXTERNAL_CONTENT_URI ? "[video] " : "[photo] ")
                                + (name != null ? name : "file") + " - " + fmt(size)
                                + (date > 0 ? " - " + new SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                   .format(new Date(date * 1000L)) : ""));
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
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        rowBoxes.clear();
        for (int idx : order) {
            CheckBox cb = new CheckBox(this);
            cb.setText(rowNames.get(idx));
            cb.setTextSize(13);
            cb.setTextColor(getColor(R.color.text_primary));
            cb.setPadding(pad, pad / 2, pad, pad / 2);
            fileList.addView(cb);
            rowBoxes.add(cb);
        }
        if (rowBoxes.isEmpty()) {
            status.setTextColor(getColor(R.color.text_secondary));
            status.setText("No photos/videos over 1 MB found - you are clean. PDFs/docs: see the guide card below.");
        } else {
            long total = 0;
            for (long s : rowSizes) total += s;
            status.setTextColor(getColor(R.color.text_secondary));
            status.setText(String.format(Locale.getDefault(),
                    "%d files listed (%s). Tick what to keep on the SD card, then tap step 3.",
                    rowBoxes.size(), fmt(total)));
        }
    }

    private void moveTicked() {
        if (treeUri == null) {
            Toast.makeText(this, "Pick the SD card folder first (step 1).", Toast.LENGTH_LONG).show();
            return;
        }
        final List<Integer> chosen = new ArrayList<Integer>();
        for (int i = 0; i < rowBoxes.size(); i++) {
            if (rowBoxes.get(i).isChecked()) chosen.add(i);
        }
        if (chosen.isEmpty()) {
            Toast.makeText(this, "Tick at least one file.", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setTextColor(getColor(R.color.text_secondary));
        status.setText("Copying " + chosen.size() + " file(s) to the SD card...");
        btnMove.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Uri> originals = new ArrayList<Uri>();
                String firstError = null;
                for (int k = 0; k < chosen.size(); k++) {
                    final int idx = chosen.get(k);
                    try {
                        copyToTree(rowUris.get(idx));
                        originals.add(rowUris.get(idx));
                    } catch (Exception e) {
                        if (firstError == null) firstError = e.getMessage();
                    }
                }
                final List<Uri> fOrig = originals;
                final String fErr = firstError;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnMove.setEnabled(true);
                        if (fOrig.isEmpty()) {
                            status.setTextColor(getColor(R.color.accent_amber));
                            status.setText("Copy failed: " + (fErr == null ? "unknown" : fErr) + " - nothing deleted, nothing lost.");
                            return;
                        }
                        status.setTextColor(getColor(R.color.text_secondary));
                        status.setText("Copies on SD (check). Confirming removal from internal memory...");
                        try {
                            PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), fOrig);
                            startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                        } catch (Exception e) {
                            status.setTextColor(getColor(R.color.accent_amber));
                            status.setText("Copies are on the SD card. Internal cleanup needs Android's confirm dialog, which failed ("
                                    + e.getClass().getSimpleName() + "). Delete originals in Gallery when you like.");
                        }
                    }
                });
            }
        }).start();
    }

    private void copyToTree(Uri sourceUri) throws Exception {
        ContentResolver cr = getContentResolver();
        String displayName = sourceUri.getLastPathSegment();
        if (displayName == null) displayName = "vault";
        int slash = displayName.lastIndexOf('/');
        if (slash >= 0) displayName = displayName.substring(slash + 1);
        if (!displayName.contains(".")) displayName = displayName + ".jpg";
        String mime = cr.getType(sourceUri);
        if (mime == null) mime = "application/octet-stream";
        String stem = displayName.substring(0, displayName.lastIndexOf('.'));
        String ext = displayName.substring(displayName.lastIndexOf('.') + 1);
        Uri docUri = DocumentsContract.createDocument(cr,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
                mime, stem + "_" + System.currentTimeMillis() + "." + ext);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = cr.openInputStream(sourceUri);
            out = cr.openOutputStream(docUri);
            if (in == null || out == null) throw new Exception("cannot open file");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
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
