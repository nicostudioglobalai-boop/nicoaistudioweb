package com.swiftcut.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int DEFAULT_HIGHLIGHT_MS = 20000;

    private Uri videoUri;
    private String videoName = "video";
    private long durationMs;

    private TextView info, range, status;
    private SeekBar seekStart, seekEnd;
    private ProgressBar progress;
    private final Handler ui = new Handler();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        Button btnTrim = findViewById(R.id.btnTrim);
        Button btnMute = findViewById(R.id.btnMute);
        Button btnAudio = findViewById(R.id.btnAudio);
        Button btnHighlight = findViewById(R.id.btnHighlight);
        Button btnCompress = findViewById(R.id.btnCompress);
        info = findViewById(R.id.info);
        range = findViewById(R.id.range);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        seekStart = findViewById(R.id.seekStart);
        seekEnd = findViewById(R.id.seekEnd);

        Button btnPick = findViewById(R.id.btnPick);
        btnPick.setOnClickListener(v -> {
            if (busy) {
                toast("Busy — wait for the current job");
                return;
            }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("video/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Pick a video"), REQ_PICK);
        });

        btnTrim.setOnClickListener(v -> runIfLoaded(() -> {
            long s = seekStart.getProgress();
            long e = seekEnd.getProgress();
            if (e - s < 1000) {
                toast("Select at least 1 second");
                return false;
            }
            runJob("Trimming.", out -> VideoOps.copyTracks(MainActivity.this, videoUri,
                    s, e, true, true, out, jobProgress()), "trimmed",
                    outName(videoName, "trimmed", false));
            return true;
        }));

        btnMute.setOnClickListener(v -> runIfLoaded(() -> {
            runJob("Removing sound.", out -> VideoOps.copyTracks(MainActivity.this, videoUri,
                    0, durationMs, true, false, out, jobProgress()), "muted",
                    outName(videoName, "muted", false));
            return true;
        }));

        btnAudio.setOnClickListener(v -> runIfLoaded(() -> {
            runJob("Extracting audio.", out -> VideoOps.copyTracks(MainActivity.this, videoUri,
                    0, durationMs, false, true, out, jobProgress()), "audio",
                    outName(videoName, "audio", true), true);
            return true;
        }));

        btnHighlight.setOnClickListener(v -> runIfLoaded(() -> {
            final long target = Math.min(DEFAULT_HIGHLIGHT_MS, durationMs);
            busy = true;
            setStatus("AI: scanning sound:");
            ui.post(() -> progress.setProgress(0));
            pool.execute(() -> {
                File out = tmp("highlight");
                if (out.exists()) out.delete();
                try {
                    VideoOps.Progress pr = jobProgress();
                    long[] part = AiHighlight.findBestPart(this, videoUri, target, pr);
                    ui.post(() -> {
                        seekStart.setProgress((int) part[0]);
                        seekEnd.setProgress((int) part[1]);
                        updateRange();
                    });
                    long s = part[0], e = part[1];
                    VideoOps.copyTracks(MainActivity.this, videoUri, s, e, true, true, out, pr);
                    publish(out, outName(videoName, "highlight", false), false);
                } catch (Exception ex) {
                    setStatus("Smart cut failed: " + ex.getMessage());
                    busy = false;
                }
            });
            return true;
        }));

        btnCompress.setOnClickListener(v -> runIfLoaded(() -> {
            runJob("Compressing.", out -> Compressor.compress(MainActivity.this, videoUri,
                    900000, out, jobProgress()), "small",
                    outName(videoName, "small", false));
            return true;
        }));

        seekStart.setOnSeekBarChangeListener(bar(this::updateRange));
        seekEnd.setOnSeekBarChangeListener(bar(this::updateRange));
        updateRange();
    }

    private interface Op {
        boolean run() throws Exception;
    }

    private interface OutJob {
        void run(File out) throws Exception;
    }

    private void runIfLoaded(Op op) {
        if (videoUri == null) {
            toast("Pick a video first");
            return;
        }
        if (busy) {
            toast("Busy — wait for the current job");
            return;
        }
        pool.execute(() -> {
            try {
                op.run();
            } catch (Exception ex) {
                setStatus("Failed: " + ex.getMessage());
            }
        });
    }

    private void runJob(String label, OutJob job, String tag, String outName) {
        runJob(label, job, tag, outName, false);
    }

    private void runJob(String label, OutJob job, String tag, String outName, boolean audio) {
        busy = true;
        setStatus(label);
        ui.post(() -> progress.setProgress(0));
        pool.execute(() -> {
            File out = tmp(tag);
            if (out.exists()) out.delete();
            try {
                job.run(out);
                publish(out, outName, audio);
            } catch (Exception ex) {
                setStatus(label + " failed: " + ex.getMessage());
                busy = false;
            }
        });
    }

    private VideoOps.Progress jobProgress() {
        return (pct, msg) -> {
            if (Thread.currentThread().isInterrupted()) return;
            ui.post(() -> progress.setProgress(pct));
        };
    }

    private void publish(File f, String name, boolean audio) {
        try {
            InputStream in = new FileInputStream(f);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, audio ? "audio/mp4" : "video/mp4");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        audio ? "Music/SwiftCut" : "Movies/SwiftCut");
                ContentResolver cr = getContentResolver();
                Uri uri = cr.insert(audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                OutputStream os = cr.openOutputStream(uri);
                copy(in, os);
                cv.clear();
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
            } else {
                File base = audio
                        ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                File dir = new File(base, "SwiftCut");
                dir.mkdirs();
                File dst = new File(dir, name);
                copy(in, new FileOutputStream(dst));
                MediaScannerConnection.scanFile(this,
                        new String[]{dst.getAbsolutePath()}, null, null);
            }
            setStatus("Saved: " + name + "\nCheck Gallery/Files for " + (audio ? "Music/SwiftCut" : "Movies/SwiftCut"));
            f.delete();
        } catch (Exception ex) {
            setStatus("Save failed: " + ex.getMessage());
        }
        busy = false;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private File tmp(String tag) {
        return new File(getExternalFilesDir(null),
                "sw_" + tag + "_" + stamp() + ".mp4");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static String outName(String src, String tag, boolean audio) {
        int dot = src.lastIndexOf('.');
        String stem = dot > 0 ? src.substring(0, dot) : src;
        return stem + "_" + tag + (audio ? ".m4a" : ".mp4");
    }

    private String fmtMs(long ms) {
        return String.format(Locale.US, "%d:%02d", ms / 60000, ms % 60000 / 1000);
    }

    private SeekBar.OnSeekBarChangeListener bar(final Runnable r) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                r.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        };
    }

    private void updateRange() {
        int a = seekStart.getProgress();
        int b = seekEnd.getProgress();
        if (b < a) b = a;
        range.setText(fmtMs(a) + " \u2192 " + fmtMs(b)
                + "   (" + fmtMs(Math.max(0, b - a)) + " kept)");
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        videoUri = data.getData();
        try {
            String name = "video";
            long size = 0;
            Cursor c = getContentResolver().query(videoUri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0) size = c.getLong(si);
                c.close();
            }
            MediaMetadataRetriever mm = new MediaMetadataRetriever();
            mm.setDataSource(this, videoUri);
            durationMs = Long.parseLong(mm.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            String wh = mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    + "x" + mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            mm.release();
            videoName = name;
            info.setText(name + "   " + Formatter.formatShortFileSize(this, size)
                    + "  \u2022  " + wh + "  \u2022  " + fmtMs(durationMs));
            seekStart.setMax((int) durationMs);
            seekEnd.setMax((int) durationMs);
            seekStart.setProgress(0);
            seekEnd.setProgress((int) durationMs);
            updateRange();
        } catch (Exception ex) {
            setStatus("Could not read this file: " + ex.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdown();
    }
}
