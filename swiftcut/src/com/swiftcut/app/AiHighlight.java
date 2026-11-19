package com.swiftcut.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * "Smart" analyser: decodes the audio track of a video on-device and
 * measures loudness (RMS) in half-second buckets, then picks the most
 * energetic window of the requested length. This is what powers the
 * Smart-Cut highlight feature — fully offline.
 */
public final class AiHighlight {

    private AiHighlight() {
    }

    /** Returns {startMs, endMs} of the best segment of length ~targetMs. */
    public static long[] findBestPart(Context c, Uri in, long targetMs, VideoOps.Progress p)
            throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, in, null);
            int audioTrack = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("audio/")) {
                    audioTrack = i;
                    fmt = f;
                    break;
                }
            }
            if (audioTrack < 0) {
                long d = 15000;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    if (f.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                        d = f.getLong(MediaFormat.KEY_DURATION);
                        break;
                    }
                }
                if (d <= targetMs) return new long[]{0, d / 1000};
                long mid = (d / 2) - targetMs * 500;
                if (mid < 0) mid = 0;
                return new long[]{mid / 1000, (mid + targetMs * 1000) / 1000};
            }

            ex.selectTrack(audioTrack);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            MediaCodec dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            final long bucketUs = 500000;
            java.util.List<Float> energy = new java.util.ArrayList<>();
            java.util.List<Long> times = new java.util.ArrayList<>();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastPtsUs = 0;
            float curSum = 0;
            long curCount = 0;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer b = dec.getInputBuffer(inIdx);
                        int size = b == null ? 0 : ex.readSampleData(b, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer o = dec.getOutputBuffer(outIdx);
                        o.order(ByteOrder.LITTLE_ENDIAN);
                        ShortBuffer sb = o.asShortBuffer();
                        long n = sb.remaining();
                        if (n > 0) {
                            double sum = 0;
                            for (int i = 0; i < n; i += 2) {
                                double v = sb.get(i) / 32768.0;
                                sum += v * v;
                            }
                            curSum += (float) sum;
                            curCount += n / 2;
                        }
                        long bucket = info.presentationTimeUs / bucketUs;
                        if (bucket > lastPtsUs / bucketUs) {
                            float rms = curCount > 0 ? (float) Math.sqrt(curSum / curCount) : 0f;
                            energy.add(rms);
                            times.add(lastPtsUs);
                            lastPtsUs = info.presentationTimeUs;
                            curSum = 0;
                            curCount = 0;
                        }
                        if (p != null && info.presentationTimeUs > 0 && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                            int pct = (int) (info.presentationTimeUs * 100
                                    / Math.max(1, fmt.getLong(MediaFormat.KEY_DURATION)));
                            p.onProgress(Math.min(100, pct), null);
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(outIdx, false);
                    if (eos) {
                        float rms = curCount > 0 ? (float) Math.sqrt(curSum / curCount) : 0f;
                        energy.add(rms);
                        times.add(lastPtsUs);
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    if (curCount > 0) {
                        energy.add((float) Math.sqrt(curSum / curCount));
                        times.add(lastPtsUs);
                    }
                    outputDone = true;
                }
            }
            dec.stop();
            dec.release();

            if (energy.isEmpty()) throw new Exception("Could not read audio");

            int win = (int) (targetMs / 500);
            if (win < 1) win = 1;
            if (win >= energy.size()) {
                return new long[]{times.get(0) / 1000,
                        (times.get(energy.size() - 1) + bucketUs) / 1000};
            }
            double best = -1;
            int bestI = 0;
            double sum = 0;
            for (int i = 0; i < win; i++) sum += energy.get(i);
            best = sum;
            for (int i = win; i < energy.size(); i++) {
                sum += energy.get(i) - energy.get(i - win);
                if (sum > best) {
                    best = sum;
                    bestI = i - win + 1;
                }
            }
            long startUs = times.get(bestI);
            long endUs = startUs + targetMs * 1000;
            return new long[]{startUs / 1000, endUs / 1000};
        } finally {
            try {
                ex.release();
            } catch (Exception ignore) {
            }
        }
    }
}
