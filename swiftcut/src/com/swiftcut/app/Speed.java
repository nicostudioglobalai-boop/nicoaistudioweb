package com.swiftcut.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.Image;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Speed tool: re-encodes the video faster (2x) or slower (0.5x).
 *
 * Three passes, each small and independently safe:
 *  A) audio -> decode -> resample -> AAC encode -> audio-only temp mp4
 *  B) video -> decode -> re-timed frames -> H.264 encode -> video-only temp mp4
 *  C) merge both temp files into the final mp4 (track copy, no re-encode)
 *
 * Audio pitch shifts with speed (tape effect) — pitch-preserving
 * stretching is too heavy for 2GB phones.
 */
public final class Speed {

    private Speed() {
    }

    public static double clipSpeed(double s) {
        return (s <= 0.5) ? 0.5 : 2.0;
    }

    public static void convert(Context c, Uri in, double speed,
                               File out, VideoOps.Progress p) throws Exception {
        speed = clipSpeed(speed);
        File dir = out.getParentFile();
        File aTmp = new File(dir, out.getName() + ".a.mp4");
        File vTmp = new File(dir, out.getName() + ".v.mp4");

        int degrees = readRotation(c, in);
        boolean hasAudio = buildAudioPass(c, in, speed, aTmp, p);
        buildVideoPass(c, in, speed, vTmp, degrees, hasAudio ? 20 : 0, 90, p);
        mergePass(c, vTmp, hasAudio ? aTmp : null, out, degrees, hasAudio ? 90 : 100, p);
        if (aTmp.exists()) aTmp.delete();
        if (vTmp.exists()) vTmp.delete();
    }

    private static int readRotation(Context c, Uri in) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, in, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                if (f.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    try {
                        return f.getInteger("rotation");
                    } catch (Exception ignore) {
                        return 0;
                    }
                }
            }
        } catch (Throwable ignore) {
        } finally {
            try {
                ex.release();
            } catch (Exception ignore) {
            }
        }
        return 0;
    }

    /* ---------------- Pass A: audio ---------------- */

    private static boolean buildAudioPass(Context c, Uri in, double speed, File out,
                                          VideoOps.Progress p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec dec = null;
        MediaCodec enc = null;
        try {
            ex.setDataSource(c, in, null);
            int aTrack = -1;
            MediaFormat afmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    aTrack = i;
                    afmt = f;
                    break;
                }
            }
            if (aTrack < 0) return false;
            String mime = afmt.getString(MediaFormat.KEY_MIME);
            if (mime.contains("flac")) return false;

            ex.selectTrack(aTrack);
            int rate = afmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = afmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long dur = afmt.containsKey(MediaFormat.KEY_DURATION)
                    ? afmt.getLong(MediaFormat.KEY_DURATION) : 0;

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(afmt, null, null, 0);
            dec.start();

            AudioResampler rs = new AudioResampler(rate, channels, speed);

            MediaFormat aef = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels);
            aef.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
            aef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            enc.configure(aef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            MediaCodec.BufferInfo dInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo eInfo = new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean decDone = false;
            boolean encInDone = false;
            boolean encOutDone = false;
            boolean muxStarted = false;
            int muxTrack = -1;

            while (!encOutDone) {
                if (!inputDone) {
                    int idx = dec.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        ByteBuffer b = dec.getInputBuffer(idx);
                        int size = b == null ? 0 : ex.readSampleData(b, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(idx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int dIdx = dec.dequeueOutputBuffer(dInfo, 10000);
                if (dIdx >= 0) {
                    if (dInfo.size > 0) {
                        ByteBuffer o = dec.getOutputBuffer(dIdx);
                        o.order(ByteOrder.LITTLE_ENDIAN);
                        rs.feed(o.asShortBuffer());
                        if (p != null && dur > 0) {
                            p.onProgress(Math.min(20, (int) (dInfo.presentationTimeUs
                                    * 20 / dur)), null);
                        }
                    }
                    boolean eos = (dInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(dIdx, false);
                    if (eos) decDone = true;
                } else if (dIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    decDone = true;
                }

                // feed encoder from the resampler
                if (!encInDone) {
                    int eIdx = enc.dequeueInputBuffer(10000);
                    if (eIdx >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(eIdx);
                        if (ib != null && ib.remaining() > 0 && rs.hasPending()) {
                            byte[] chunk = rs.pull(Math.min(ib.remaining(),
                                    rs.pending()));
                            if (chunk.length > 0) {
                                ib.put(chunk);
                                long pts = rs.ptsFor(chunk.length);
                                enc.queueInputBuffer(eIdx, 0, chunk.length, pts, 0);
                            }
                        } else if (decDone && !rs.hasPending()) {
                            enc.queueInputBuffer(eIdx, 0, 0, rs.now(),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encInDone = true;
                        }
                    }
                }

                int eIdx = enc.dequeueOutputBuffer(eInfo, 10000);
                if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (eIdx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(eIdx);
                    if (muxStarted && ob != null && eInfo.size > 0
                            && (eInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        mux.writeSampleData(muxTrack, ob, eInfo);
                    }
                    enc.releaseOutputBuffer(eIdx, false);
                    if ((eInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encOutDone = true;
                    }
                }
            }
            mux.stop();
            return out.exists() && out.length() > 1024;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (dec != null) dec.release();
            } catch (Exception ignore) {
            }
            try {
                if (enc != null) enc.release();
            } catch (Exception ignore) {
            }
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

    /* ---------------- Pass B: video ---------------- */

    private static void buildVideoPass(Context c, Uri in, double speed, File out,
                                       int degrees, int pctFrom, int pctTo,
                                       VideoOps.Progress p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec dec = null;
        MediaCodec enc = null;
        try {
            ex.setDataSource(c, in, null);
            int vTrack = -1;
            MediaFormat vfmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                if (f.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    vTrack = i;
                    vfmt = f;
                    break;
                }
            }
            if (vTrack < 0) throw new IOException("No video track found");
            ex.selectTrack(vTrack);

            int w = vfmt.getInteger(MediaFormat.KEY_WIDTH);
            int h = vfmt.getInteger(MediaFormat.KEY_HEIGHT);
            long duration = vfmt.containsKey(MediaFormat.KEY_DURATION)
                    ? vfmt.getLong(MediaFormat.KEY_DURATION) : 0;

            dec = MediaCodec.createDecoderByType(vfmt.getString(MediaFormat.KEY_MIME));
            dec.configure(vfmt, null, null, 0);
            dec.start();

            boolean semi = false;
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", w, h);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 2500000);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
            enc = MediaCodec.createEncoderByType("video/avc");
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            try {
                semi = enc.getInputFormat().getInteger(MediaFormat.KEY_COLOR_FORMAT)
                        == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            } catch (Exception ignore) {
            }

            MediaCodec.BufferInfo dInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo eInfo = new MediaCodec.BufferInfo();

            boolean decInDone = false;
            boolean encInDone = false;
            boolean encOutDone = false;
            boolean muxStarted = false;
            int muxTrack = -1;
            long prevPts = -1;

            while (!encOutDone) {
                if (!decInDone) {
                    int idx = dec.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        ByteBuffer b = dec.getInputBuffer(idx);
                        int size = b == null ? 0 : ex.readSampleData(b, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decInDone = true;
                        } else {
                            dec.queueInputBuffer(idx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int dIdx = dec.dequeueOutputBuffer(dInfo, 10000);
                if (dIdx >= 0) {
                    boolean eos = (dInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    Image img = dInfo.size > 0 ? dec.getOutputImage(dIdx) : null;
                    long outPts = (long) (dInfo.presentationTimeUs / speed);
                    if (outPts <= prevPts) outPts = prevPts + 1;
                    prevPts = outPts;
                    if (img != null && !encInDone) {
                        byte[] frame = Compressor.toPlanar(img, semi);
                        img.close();
                        int eIdx = enc.dequeueInputBuffer(10000);
                        if (eIdx >= 0) {
                            ByteBuffer ib = enc.getInputBuffer(eIdx);
                            if (ib == null || ib.remaining() < frame.length) {
                                throw new IOException("Encoder input buffer too small");
                            }
                            ib.put(frame);
                            enc.queueInputBuffer(eIdx, 0, frame.length, outPts, 0);
                        }
                    } else if (img != null) {
                        img.close();
                    }
                    dec.releaseOutputBuffer(dIdx, false);
                    if (eos && !encInDone) {
                        int eIdx = enc.dequeueInputBuffer(10000);
                        if (eIdx >= 0) {
                            enc.queueInputBuffer(eIdx, 0, 0, outPts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encInDone = true;
                        }
                    }
                    if (p != null && duration > 0) {
                        int pct = pctFrom + (int) (dInfo.presentationTimeUs
                                * (pctTo - pctFrom) / duration);
                        p.onProgress(Math.min(pctTo, Math.max(pctFrom, pct)), null);
                    }
                }

                int eIdx = enc.dequeueOutputBuffer(eInfo, 10000);
                if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (eIdx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(eIdx);
                    if (muxStarted && ob != null && eInfo.size > 0
                            && (eInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        mux.writeSampleData(muxTrack, ob, eInfo);
                    }
                    enc.releaseOutputBuffer(eIdx, false);
                    if ((eInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encOutDone = true;
                    }
                }
            }
            mux.stop();
        } finally {
            try {
                if (dec != null) dec.release();
            } catch (Exception ignore) {
            }
            try {
                if (enc != null) enc.release();
            } catch (Exception ignore) {
            }
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

    /* ---------------- Pass C: merge ---------------- */

    private static void mergePass(Context c, File video, File audio, File out,
                                  int degrees, int pctFrom, VideoOps.Progress p) throws Exception {
        MediaExtractor vex = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaExtractor aex = null;
        try {
            vex.setDataSource(video.getAbsolutePath());
            int vTrack = -1;
            int vMux = -1;
            for (int i = 0; i < vex.getTrackCount(); i++) {
                MediaFormat f = vex.getTrackFormat(i);
                if (f.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    vTrack = i;
                    vMux = mux.addTrack(f);
                    break;
                }
            }
            int aMux = -1;
            int aTrack = -1;
            if (audio != null && audio.exists() && audio.length() > 1024) {
                aex = new MediaExtractor();
                aex.setDataSource(audio.getAbsolutePath());
                for (int i = 0; i < aex.getTrackCount(); i++) {
                    MediaFormat f = aex.getTrackFormat(i);
                    if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                        aTrack = i;
                        aMux = mux.addTrack(f);
                        break;
                    }
                }
            }
            if (vTrack < 0) throw new IOException("Nothing to merge");
            if (degrees != 0) mux.setOrientationHint(degrees);
            mux.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);

            vex.selectTrack(vTrack);
            long vTotal = vex.getTrackFormat(vTrack).containsKey(MediaFormat.KEY_DURATION)
                    ? vex.getTrackFormat(vTrack).getLong(MediaFormat.KEY_DURATION) : 0;
            while (true) {
                int size = vex.readSampleData(buf, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = vex.getSampleTime();
                info.flags = (vex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(vMux, buf, info);
                vex.advance();
                if (p != null && vTotal > 0) {
                    int pct = pctFrom + (int) (vex.getSampleTime() * 10 / vTotal);
                    p.onProgress(Math.min(pctFrom + 10, pct), null);
                }
            }

            if (aex != null && aMux >= 0) {
                aex.selectTrack(aTrack);
                while (true) {
                    int size = aex.readSampleData(buf, 0);
                    if (size < 0) break;
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = aex.getSampleTime();
                    info.flags = (aex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mux.writeSampleData(aMux, buf, info);
                    aex.advance();
                }
            }
            mux.stop();
            if (p != null) p.onProgress(100, null);
        } finally {
            try {
                vex.release();
            } catch (Exception ignore) {
            }
            try {
                if (aex != null) aex.release();
            } catch (Exception ignore) {
            }
            try {
                mux.release();
            } catch (Exception ignore) {
            }
        }
    }

    /** Decimating resampler for exact 2x / 0.5x. */
    static final class AudioResampler {
        final int sampleRate;
        final int channels;
        private final double speed;

        private short[] acc = new short[8192];
        private int accLen;
        private byte[] out = new byte[16384];
        private int outLen;
        private long writtenSamples;

        AudioResampler(int rate, int ch, double speed) {
            this.sampleRate = rate;
            this.channels = ch;
            this.speed = speed;
        }

        void feed(ShortBuffer pcm) {
            int frames = pcm.remaining() / channels;
            while (frames-- > 0) {
                ensure(accLen + channels);
                for (int ch = 0; ch < channels; ch++) acc[accLen++] = pcm.get();
            }
            resample();
        }

        boolean hasPending() {
            return outLen > 0;
        }

        int pending() {
            return outLen;
        }

        byte[] pull(int max) {
            int n = Math.min(max, outLen);
            byte[] chunk = new byte[n];
            System.arraycopy(out, 0, chunk, 0, n);
            System.arraycopy(out, n, out, 0, outLen - n);
            outLen -= n;
            return chunk;
        }

        long now() {
            return writtenSamples * 1000000L / sampleRate;
        }

        long ptsFor(int bytes) {
            long before = writtenSamples;
            writtenSamples += bytes / 2 / channels;
            return before * 1000000L / sampleRate;
        }

        private void resample() {
            int frames = accLen / channels;
            int produced = (int) (frames / speed);
            if (produced <= 0) return;
            byte[] pcm = new byte[produced * channels * 2];
            int off = 0;
            for (int i = 0; i < produced; i++) {
                int src = Math.min(frames - 1, (int) (i * speed));
                for (int ch = 0; ch < channels; ch++) {
                    int v = acc[src * channels + ch];
                    pcm[off++] = (byte) (v & 0xFF);
                    pcm[off++] = (byte) ((v >> 8) & 0xFF);
                }
            }
            int consumed = (int) (produced * speed) * channels;
            int rest = accLen - consumed;
            if (rest > 0) System.arraycopy(acc, consumed, acc, 0, rest);
            accLen = Math.max(0, rest);
            appendPcm(pcm);
        }

        private void ensure(int need) {
            if (acc.length < need) {
                short[] n = new short[Math.max(need, acc.length * 2)];
                System.arraycopy(acc, 0, n, 0, accLen);
                acc = n;
            }
        }

        private void appendPcm(byte[] pcm) {
            int need = outLen + pcm.length;
            if (out.length < need) {
                byte[] n = new byte[Math.max(need, out.length * 2)];
                System.arraycopy(out, 0, n, 0, outLen);
                out = n;
            }
            System.arraycopy(pcm, 0, out, outLen, pcm.length);
            outLen += pcm.length;
        }
    }
}
