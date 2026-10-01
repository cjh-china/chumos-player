package com.chumosplayer.util;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 音频转码：把任意受支持的音频解码后重新编码为低码率 AAC（.m4a），用于压缩体积。
 *
 * 采用「解码线程 + 编码线程」结构，中间用阻塞队列传 PCM：
 * - 解码线程：extractor -> decoder -> PCM 队列
 * - 编码线程：PCM 队列 -> encoder -> muxer，每轮先排空编码器输出再喂输入，避免缓冲死锁
 * 使用 MediaCodec 硬解/硬编，不依赖第三方库。
 */
public final class AudioTranscoder {

    private static final String MIME_AAC = "audio/mp4a-latm";
    private static final int TIMEOUT_US = 10000;
    private static final int QUEUE_SIZE = 64;

    private AudioTranscoder() {}

    /** 一段 PCM 数据（附带时间戳）；eos 为 true 表示流结束 */
    private static final class Pcm {
        final byte[] data;
        final long ptsUs;
        final boolean eos;

        Pcm(byte[] data, long ptsUs, boolean eos) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.eos = eos;
        }
    }

    /**
     * 把 input 转码为 bitrate（bps）的 AAC，输出到 output。
     * 失败抛 IOException，调用方应保留原文件。
     */
    public static void transcodeToAac(File input, File output, int bitrate) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int inTrack = selectAudioTrack(extractor);
            if (inTrack < 0) throw new IOException("未找到音频轨道");
            MediaFormat inFormat = extractor.getTrackFormat(inTrack);
            extractor.selectTrack(inTrack);
            String inMime = inFormat.getString(MediaFormat.KEY_MIME);

            int sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            decoder = MediaCodec.createDecoderByType(inMime);
            decoder.configure(inFormat, null, null, 0);
            decoder.start();

            MediaFormat encFormat = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, channels);
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            encoder = MediaCodec.createEncoderByType(MIME_AAC);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            final BlockingQueue<Pcm> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
            final Pcm eos = new Pcm(null, 0, true);
            final Exception[] decodeErr = new Exception[1];
            final MediaExtractor ex = extractor;
            final MediaCodec dec = decoder;

            Thread decodeThread = new Thread(() -> {
                try {
                    decodeLoop(ex, dec, queue, eos);
                } catch (Exception e) {
                    decodeErr[0] = e;
                    try { queue.put(eos); } catch (InterruptedException ignore) { }
                }
            }, "audio-decode");
            decodeThread.start();

            try {
                try {
                    encodeLoop(encoder, muxer, queue);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("编码失败", e);
                }
            } finally {
                try { decodeThread.join(); } catch (InterruptedException ignore) { }
            }
            if (decodeErr[0] != null) throw new IOException("解码失败", decodeErr[0]);
            if (output.length() == 0) throw new IOException("转码输出为空");
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignore) { }
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignore) { }
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignore) { }
            extractor.release();
        }
    }

    /** 解码线程：读取源 -> 解码 -> PCM 入队，结束时放入 eos 标记 */
    private static void decodeLoop(MediaExtractor extractor, MediaCodec decoder,
                                   BlockingQueue<Pcm> queue, Pcm eos) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        while (!outputDone) {
            if (!inputDone) {
                int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIdx);
                    int size = extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                ByteBuffer out = decoder.getOutputBuffer(outIdx);
                if (info.size > 0 && out != null) {
                    byte[] data = new byte[info.size];
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    out.get(data);
                    queue.put(new Pcm(data, info.presentationTimeUs, false));
                }
                boolean eosFlag = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(outIdx, false);
                if (eosFlag) outputDone = true;
            }
        }
        queue.put(eos);
    }

    /** 编码线程：每轮先排空编码器输出，再喂 PCM 输入，避免缓冲区互锁 */
    private static void encodeLoop(MediaCodec encoder, MediaMuxer muxer,
                                   BlockingQueue<Pcm> queue) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean muxerStarted = false;
        int outTrack = -1;
        boolean inputDone = false;
        boolean outputDone = false;
        Pcm current = null;
        int feedOffset = 0;

        while (!outputDone) {
            // 1) 先排空编码器输出（非阻塞，能排多少排多少）
            int encIdx = encoder.dequeueOutputBuffer(info, 0);
            if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (encIdx >= 0) {
                ByteBuffer encBuf = encoder.getOutputBuffer(encIdx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && muxerStarted) {
                    encBuf.position(info.offset);
                    encBuf.limit(info.offset + info.size);
                    muxer.writeSampleData(outTrack, encBuf, info);
                }
                boolean eosFlag = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(encIdx, false);
                if (eosFlag) { outputDone = true; continue; }
            }

            // 2) 再喂输入：每次最多喂一个缓冲，拿不到就回到顶部继续排输出
            if (!inputDone) {
                if (current == null) {
                    current = queue.poll();
                    feedOffset = 0;
                }
                if (current != null) {
                    if (current.eos) {
                        int idx = encoder.dequeueInputBuffer(TIMEOUT_US);
                        if (idx >= 0) {
                            encoder.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            current = null;
                        }
                    } else {
                        int idx = encoder.dequeueInputBuffer(TIMEOUT_US);
                        if (idx >= 0) {
                            ByteBuffer in = encoder.getInputBuffer(idx);
                            if (in != null) {
                                in.clear();
                                int n = Math.min(in.remaining(), current.data.length - feedOffset);
                                in.put(current.data, feedOffset, n);
                                feedOffset += n;
                                encoder.queueInputBuffer(idx, 0, n, current.ptsUs, 0);
                                if (feedOffset >= current.data.length) current = null;
                            }
                        }
                    }
                }
            }
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }
}
