package com.swiftcut.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Fast copy-track operations using MediaExtractor + MediaMuxer.
 * No re-encoding: trims, mutes and audio extraction are near-instant
 * and keep original quality. Designed for 2GB RAM phones.
 */
public final class VideoOps {

    public interface Progress {
        void onProgress(int pct, String msg);
    }

    private VideoOps() {
    }

    /** Copy selected tracks between startMs and endMs into out (an .mp4 file). */
    public static void copyTracks(Context c, Uri in, long startMs, long endMs,
                                  boolean keepVideo, boolean keepAudio,
                                  File out, Progress p) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            ex.setDataSource(c, in, null);

            int[] dest = new int[ex.getTrackCount()];
            boolean haveVideo = false;
            int degrees = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                dest[i] = -1;
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                boolean isVideo = mime.startsWith("video/");
                boolean isAudio = mime.startsWith("audio/");
                boolean keep = (isVideo && keepVideo) || (isAudio && keepAudio);
                if (!keep) continue;
                if (isVideo) {
                    haveVideo = true;
                    try {
                        degrees = f.getInteger("rotation");
                    } catch (Exception ignore) {
                    }
                }
                dest[i] = mux.addTrack(f);
                ex.selectTrack(i);
            }
            boolean any = false;
            for (int d : dest) if (d >= 0) { any = true; break; }
            if (!any) {
                throw new IOException("No usable track in this video");
            }
            if (degrees != 0) mux.setOrientationHint(degrees);
            mux.start();

            if (startMs > 0) ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long endUs = endMs * 1000;
            ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long span = Math.max(1, endUs - startMs * 1000);
            while (true) {
                int srcIdx = ex.getSampleTrackIndex();
                if (srcIdx < 0) break;
                long pts = ex.getSampleTime();
                if (pts > endUs) break;
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                if (dest[srcIdx] < 0) {
                    ex.advance();
                    continue;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                int flags = ex.getSampleFlags();
                info.flags = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(dest[srcIdx], buf, info);
                if (p != null) {
                    int pct = (int) ((pts - startMs * 1000) * 100 / span);
                    p.onProgress(Math.max(0, Math.min(100, pct)), null);
                }
                ex.advance();
            }
            mux.stop();
        } finally {
            try {
                ex.release();
            } catch (Exception ignore) {
            }
            try {
                mux.release();
            } catch (Exception ignore) {
            }
        }
    }

}
