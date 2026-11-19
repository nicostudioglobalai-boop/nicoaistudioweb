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

/**
 * Video compressor: decodes the video, re-encodes at a lower bitrate
 * and copies the original audio track untouched. Streaming design
 * (frame by frame) so it works within 2GB RAM.
 */
public final class Compressor {

    private Compressor() {
    }

    public static void compress(Context c, Uri in, long bitrate,
                                File out, VideoOps.Progress p) throws Exception {
        MediaExtractor vex = new MediaExtractor();
        MediaExtractor aex = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec dec = null;
        MediaCodec enc = null;
        boolean started = false;
        try {
            vex.setDataSource(c, in, null);
            aex.setDataSource(c, in, null);

            int vTrack = -1;
            MediaFormat vfmt = null;
            int aTrack = -1;
            MediaFormat afmt = null;
            int degrees = 0;
            for (int i = 0; i < vex.getTrackCount(); i++) {
                MediaFormat f = vex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/") && vTrack < 0) {
                    vTrack = i;
                    vfmt = f;
                    try {
                        degrees = f.getInteger("rotation");
                    } catch (Exception ignore) {
                    }
                }
                if (mime.startsWith("audio/") && aTrack < 0) {
                    aTrack = i;
                    afmt = f;
                }
            }
            if (vTrack < 0) throw new IOException("No video track found");

            int w = vfmt.getInteger(MediaFormat.KEY_WIDTH);
            int h = vfmt.getInteger(MediaFormat.KEY_HEIGHT);
            long duration = vfmt.containsKey(MediaFormat.KEY_DURATION)
                    ? vfmt.getLong(MediaFormat.KEY_DURATION) : 0;

            String vmime = vfmt.getString(MediaFormat.KEY_MIME);
            dec = MediaCodec.createDecoderByType(vmime);
            dec.configure(vfmt, null, null, 0);
            dec.start();

            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", w, h);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
            enc = MediaCodec.createEncoderByType("video/avc");
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            int resolvedColor = enc.getInputFormat().getInteger(MediaFormat.KEY_COLOR_FORMAT);
            boolean semiPlanar =
                    resolvedColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

            // audio passthrough
            int aMuxTrack = -1;
            MediaExtractor curA = aex;
            if (aTrack >= 0 && !afmt.getString(MediaFormat.KEY_MIME).contains("flac")) {
                aMuxTrack = mux.addTrack(afmt);
                curA.selectTrack(aTrack);
            }

            MediaCodec.BufferInfo dInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo eInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();

            ByteBuffer aBuf = ByteBuffer.allocate(2 * 1024 * 1024);

            boolean decInDone = false;
            boolean decOutDone = false;
            boolean encInDone = false;
            boolean encOutDone = false;
            boolean muxStarted = false;
            int vMuxTrack = -1;

            while (!encOutDone) {
                // feed decoder
                if (!decInDone) {
                    int idx = dec.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        ByteBuffer b = dec.getInputBuffer(idx);
                        int size = b == null ? 0 : vex.readSampleData(b, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decInDone = true;
                        } else {
                            dec.queueInputBuffer(idx, 0, size, vex.getSampleTime(), 0);
                            vex.advance();
                        }
                    }
                }

                // drain decoder -> feed encoder
                int dIdx = dec.dequeueOutputBuffer(dInfo, 10000);
                if (dIdx >= 0) {
                    Image img = dec.getOutputImage(dIdx);
                    if (img != null && dInfo.size > 0) {
                        byte[] frame = toPlanar(img, semiPlanar);
                        img.close();
                        if (!encInDone) {
                            int eIdx = enc.dequeueInputBuffer(10000);
                            if (eIdx >= 0) {
                                ByteBuffer ib = enc.getInputBuffer(eIdx);
                                if (ib == null || ib.remaining() < frame.length) {
                                    throw new IOException("Encoder input buffer too small");
                                }
                                ib.put(frame);
                                enc.queueInputBuffer(eIdx, 0, frame.length,
                                        dInfo.presentationTimeUs,
                                        frame.length == 0 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                                if (frame.length == 0) encInDone = true;
                            }
                        }
                        if (p != null && duration > 0) {
                            int pct = (int) (dInfo.presentationTimeUs * 100 / duration);
                            p.onProgress(Math.min(50, Math.max(0, pct / 2)), null);
                        }
                    }
                    dec.releaseOutputBuffer(dIdx, false);
                } else if (dIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignore, format known once encoder finishes
                } else if (dIdx == MediaCodec.INFO_TRY_AGAIN_LATER && decInDone
                        && dec.getOutputBuffers().length == 0) {
                    // keep looping
                }
                if ((dInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        && !encInDone) {
                    // push EOS to encoder
                    int eIdx = enc.dequeueInputBuffer(10000);
                    if (eIdx >= 0) {
                        enc.queueInputBuffer(eIdx, 0, 0, dInfo.presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encInDone = true;
                    }
                }

                // drain encoder -> muxer
                int eIdx = enc.dequeueOutputBuffer(eInfo, 10000);
                if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vMuxTrack = mux.addTrack(enc.getOutputFormat());
                    if (aMuxTrack >= 0) {
                        mux.start();
                    } else {
                        mux.start();
                    }
                    muxStarted = true;
                } else if (eIdx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(eIdx);
                    if (muxStarted && ob != null && eInfo.size > 0
                            && (eInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        mux.writeSampleData(vMuxTrack, ob, eInfo);
                    }
                    enc.releaseOutputBuffer(eIdx, false);
                    if ((eInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encOutDone = true;
                    }
                    if (p != null && duration > 0) {
                        int pct = (int) (eInfo.presentationTimeUs * 100 / duration);
                        p.onProgress(50 + Math.min(50, Math.max(0, pct / 2)), null);
                    }
                }

                // interleave audio passthrough
                if (muxStarted && aMuxTrack >= 0) {
                    long limit = encOutDone ? Long.MAX_VALUE
                            : (eInfo.presentationTimeUs > 0 ? eInfo.presentationTimeUs + 100000 : Long.MAX_VALUE);
                    int got = curA.getSampleTrackIndex();
                    while (got == aTrack) {
                        int size = curA.readSampleData(aBuf, 0);
                        if (size < 0) break;
                        aInfo.offset = 0;
                        aInfo.size = size;
                        aInfo.presentationTimeUs = curA.getSampleTime();
                        aInfo.flags = (curA.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mux.writeSampleData(aMuxTrack, aBuf, aInfo);
                        curA.advance();
                        if (curA.getSampleTime() > limit) break;
                        got = curA.getSampleTrackIndex();
                    }
                }
            }

            mux.stop();
            if (p != null) p.onProgress(100, null);
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
                vex.release();
            } catch (Exception ignore) {
            }
            try {
                aex.release();
            } catch (Exception ignore) {
            }
            try {
                mux.release();
            } catch (Exception ignore) {
            }
            if (!started && out.exists() && out.length() < 1024) out.delete();
        }
    }

    /** Convert an Image (YUV_420_888) to the layout the encoder expects. */
    static byte[] toPlanar(Image img, boolean semiPlanar) {
        Image.Plane yP = img.getPlanes()[0];
        Image.Plane uP = img.getPlanes()[1];
        Image.Plane vP = img.getPlanes()[2];
        int w = img.getWidth();
        int h = img.getHeight();
        int cw = (w + 1) / 2;
        int ch = (h + 1) / 2;

        byte[] y = readPlane(yP, w, h);
        byte[] u = readPlane(uP, cw, ch);
        byte[] v = readPlane(vP, cw, ch);

        byte[] out = new byte[w * h + cw * ch * 2];
        System.arraycopy(y, 0, out, 0, y.length);
        int off = y.length;
        if (semiPlanar) {
            System.arraycopy(u, 0, out, off, u.length);
            off += u.length;
            System.arraycopy(v, 0, out, off, v.length);
        } else {
            for (int i = 0; i < u.length; i++) out[off++] = u[i];
            for (int i = 0; i < v.length; i++) out[off++] = v[i];
        }
        return out;
    }

    private static byte[] readPlane(Image.Plane plane, int w, int h) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        byte[] out = new byte[w * h];
        if (pixStride == 1 && rowStride == w) {
            buf.get(out, 0, out.length);
            return out;
        }
        int pos = 0;
        for (int r = 0; r < h; r++) {
            int rowStart = r * rowStride;
            if (pixStride == 1) {
                for (int col = 0; col < w; col++) {
                    out[pos++] = buf.get(rowStart + col);
                }
            } else {
                for (int col = 0; col < w; col++) {
                    out[pos++] = buf.get(rowStart + col * pixStride);
                }
            }
        }
        return out;
    }
}
