#ifndef AUDIO_DECODER_H
#define AUDIO_DECODER_H

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <algorithm>
#include <atomic>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <optional>
#include <thread>
#include <vector>

#include "audio_time.h"
#include "log_sink.h"
#include "timeline_buffer.h"

static constexpr int CT_PCM = 1, CT_ALAC = 2, CT_AAC_LC = 4, CT_AAC_ELD = 8;

class LatencyReporter {
public:
    LatencyReporter(const char *name, LogSink &log) : mName(name), mLog(log) {}

    void record(int64_t ns) {
        if (mN == 0 || ns < mMinNs) mMinNs = ns;
        if (ns > mMaxNs) mMaxNs = ns;
        mSumNs += ns;
        if (++mN >= UPDATE_FREQ) {
            const int32_t meanUs = (int32_t)(mSumNs / mN / 1000);
            mMeanUs.store(meanUs, std::memory_order_relaxed);
            mMaxUs.store((int32_t)(mMaxNs / 1000), std::memory_order_relaxed);
            mN = 0; mSumNs = 0; mMinNs = 0; mMaxNs = 0;
        }
    }

    void setHeld(size_t n) { mHeld.store(n, std::memory_order_relaxed); }

    struct __attribute__((packed)) Debug {
        int32_t meanUs, maxUs;
        uint16_t held;
    };
    Debug debugInfo() const {
        Debug d{};
        d.meanUs = mMeanUs.load(std::memory_order_relaxed);
        d.maxUs = mMaxUs.load(std::memory_order_relaxed);
        d.held = (uint16_t)mHeld.load(std::memory_order_relaxed);
        return d;
    }

private:
    static constexpr int64_t UPDATE_FREQ = 200;

    const char *mName;
    LogSink &mLog;
    int64_t mN = 0, mSumNs = 0, mMinNs = 0, mMaxNs = 0;
    std::atomic<int32_t> mMeanUs{0}, mMaxUs{0};
    std::atomic<size_t> mHeld{0};
};

struct LatencyProbe {
    struct Entry { int64_t ptsUs; int64_t queueNs; };
    static constexpr uint64_t CAP = 64;
    Entry mBuf[CAP] = {};
    std::atomic<uint64_t> mWrite{0};
    std::atomic<uint64_t> mRead{0};

    void record(int64_t ptsUs, int64_t queueNs) {
        const uint64_t w = mWrite.load(std::memory_order_relaxed);
        mBuf[w % CAP] = {ptsUs, queueNs};
        mWrite.store(w + 1, std::memory_order_release);
    }

    int64_t match(int64_t ptsUs, int64_t nowNs) {
        uint64_t r = mRead.load(std::memory_order_relaxed);
        const uint64_t w = mWrite.load(std::memory_order_acquire);
        for (; r < w; r++) {
            if (mBuf[r % CAP].ptsUs == ptsUs) {
                const int64_t lat = nowNs - mBuf[r % CAP].queueNs;
                mRead.store(r + 1, std::memory_order_release);
                return lat;
            }
        }
        return -1;
    }

    size_t inFlight() const {
        return (size_t)(mWrite.load(std::memory_order_relaxed) -
                        mRead.load(std::memory_order_relaxed));
    }
};

class Decoder {
public:
    virtual ~Decoder() = default;
    virtual bool decode(const uint8_t *data, size_t len, int64_t ptsNs) = 0;
};

/* Direct pass-through for uncompressed S16LE interleaved stereo PCM (ct = 1). */
class PcmDecoder : public Decoder {
public:
    PcmDecoder(TimelineBuffer &timeline, LatencyReporter &lat)
        : mTimeline(timeline), mLat(lat) {}

    bool decode(const uint8_t *data, size_t len, int64_t ptsNs) override {
        const size_t samples = len / sizeof(int16_t);
        if (samples == 0) return false;
        const int64_t t0 = monoNs();
        mTimeline.write(reinterpret_cast<const int16_t *>(data), samples, ptsNs);
        mLat.record(monoNs() - t0);
        mLat.setHeld(0);
        return true;
    }

private:
    TimelineBuffer &mTimeline;
    LatencyReporter &mLat;
};

static inline void buildAlacMagicCookie(uint8_t out[24], int sampleRate, int channels, int spf) {
    const int frameLength = spf;
    const int bitDepth = 16, pb = 40, mb = 10, kb = 14;
    out[0] = (frameLength >> 24) & 0xFF;
    out[1] = (frameLength >> 16) & 0xFF;
    out[2] = (frameLength >> 8) & 0xFF;
    out[3] = frameLength & 0xFF;
    out[4] = 0;
    out[5] = (uint8_t)bitDepth;
    out[6] = (uint8_t)pb;
    out[7] = (uint8_t)mb;
    out[8] = (uint8_t)kb;
    out[9] = (uint8_t)channels;
    out[10] = 0; out[11] = 0xFF;
    out[12] = out[13] = out[14] = out[15] = 0;
    out[16] = out[17] = out[18] = out[19] = 0;
    out[20] = (sampleRate >> 24) & 0xFF;
    out[21] = (sampleRate >> 16) & 0xFF;
    out[22] = (sampleRate >> 8) & 0xFF;
    out[23] = sampleRate & 0xFF;
}

static inline void buildAlacAtomCookie(uint8_t out[36], int sampleRate, int channels, int spf) {
    static const uint8_t hdr[12] = {0x00, 0x00, 0x00, 0x24, 'a', 'l', 'a', 'c', 0, 0, 0, 0};
    memcpy(out, hdr, sizeof(hdr));
    buildAlacMagicCookie(out + 12, sampleRate, channels, spf);
}

static constexpr const char *AAC_INPROC_DECODER = "c2.android.inproc.aac.decoder";

struct CodecConfig {
    const char *mime;
    const char *preferName;
    int aacProfile;
    int sampleRate, channels;
    uint8_t csd[36];
    size_t csdLen;
};

static inline std::optional<CodecConfig> codecConfig(int ct, int spf, int sampleRate, int channels) {
    CodecConfig c{};
    c.sampleRate = sampleRate;
    c.channels = channels;
    if (ct == CT_ALAC) {
        c.mime = "audio/alac";
        buildAlacAtomCookie(c.csd, sampleRate, channels, spf > 0 ? spf : 352);
        c.csdLen = 36;
        return c;
    }
    c.mime = "audio/mp4a-latm";
    c.preferName = AAC_INPROC_DECODER;
    if (ct == CT_AAC_ELD) {
        c.aacProfile = 39; c.csd[0] = 0xF8; c.csd[1] = 0xE8;
        c.csd[2] = (spf == 512) ? 0x40 : 0x50; c.csd[3] = 0x00; c.csdLen = 4;
    } else if (ct == CT_AAC_LC) {
        c.aacProfile = 2; c.csd[0] = 0x12;
        c.csd[1] = (spf == 960) ? 0x14 : 0x10; c.csdLen = 2;
    } else {
        return std::nullopt;
    }
    return c;
}

class MediaCodecDecoder : public Decoder {
public:
    MediaCodecDecoder(AMediaCodec *codec, TimelineBuffer &timeline, LatencyReporter &lat)
        : mCodec(codec), mTimeline(timeline), mLat(lat) {
        mDrainRun.store(true, std::memory_order_relaxed);
        mDrainThread = std::thread(&MediaCodecDecoder::drainLoop, this);
    }

    ~MediaCodecDecoder() override {
        if (mDrainThread.joinable()) {
            mDrainRun.store(false, std::memory_order_relaxed);
            mDrainThread.join();
        }
        if (mCodec) { AMediaCodec_stop(mCodec); AMediaCodec_delete(mCodec); }
    }

    bool decode(const uint8_t *data, size_t len, int64_t ptsNs) override {
        ssize_t ii = -1;
        for (int attempt = 0; attempt < 3 && ii < 0; attempt++) {
            ii = AMediaCodec_dequeueInputBuffer(mCodec, 5000);
        }
        if (ii < 0) return false;
        size_t cap = 0;
        uint8_t *in = AMediaCodec_getInputBuffer(mCodec, (size_t)ii, &cap);
        if (!in) return false;
        const size_t n = len < cap ? len : cap;
        memcpy(in, data, n);
        mProbe.record((int64_t)(ptsNs / 1000), monoNs());
        return AMediaCodec_queueInputBuffer(mCodec, (size_t)ii, 0, n, (uint64_t)(ptsNs / 1000), 0) ==
                   AMEDIA_OK && n == len;
    }

private:
    static constexpr int64_t DRAIN_TIMEOUT_US = 20000;
    static constexpr int ENCODING_PCM_FLOAT = 4;

    void drainLoop() {
        AMediaCodecBufferInfo info;
        std::vector<int16_t> floatScratch;
        int pcmEncoding = 2;
        while (mDrainRun.load(std::memory_order_relaxed)) {
            ssize_t oi = AMediaCodec_dequeueOutputBuffer(mCodec, &info, DRAIN_TIMEOUT_US);
            if (oi == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat *ofmt = AMediaCodec_getOutputFormat(mCodec);
                if (ofmt) {
                    int32_t enc = 2;
                    if (AMediaFormat_getInt32(ofmt, "pcm-encoding", &enc)) {
                        pcmEncoding = enc;
                    }
                    AMediaFormat_delete(ofmt);
                }
                continue;
            }
            if (oi < 0) continue;
            size_t osz = 0;
            uint8_t *out = AMediaCodec_getOutputBuffer(mCodec, (size_t)oi, &osz);
            if (out && info.size > 0) {
                const uint8_t *payload = out + info.offset;
                if (pcmEncoding == ENCODING_PCM_FLOAT) {
                    const size_t n = (size_t)info.size / sizeof(float);
                    if (floatScratch.size() < n) floatScratch.resize(n);
                    const float *src = reinterpret_cast<const float *>(payload);
                    for (size_t i = 0; i < n; i++) {
                        float v = std::clamp(src[i], -1.0f, 1.0f) * 32767.0f;
                        floatScratch[i] = static_cast<int16_t>(lrintf(v));
                    }
                    mTimeline.write(floatScratch.data(), n, (int64_t)info.presentationTimeUs * 1000);
                } else {
                    mTimeline.write(reinterpret_cast<const int16_t *>(payload),
                                    (size_t)info.size / sizeof(int16_t),
                                    (int64_t)info.presentationTimeUs * 1000);
                }
            }
            const int64_t nowNs = monoNs();
            AMediaCodec_releaseOutputBuffer(mCodec, (size_t)oi, false);
            const int64_t lat = mProbe.match((int64_t)info.presentationTimeUs, nowNs);
            if (lat >= 0) mLat.record(lat);
            mLat.setHeld(mProbe.inFlight());
        }
    }

    AMediaCodec *mCodec;
    TimelineBuffer &mTimeline;
    LatencyReporter &mLat;
    LatencyProbe mProbe;
    std::thread mDrainThread;
    std::atomic<bool> mDrainRun{false};
};

static inline AMediaCodec *startCodec(const char *mime, const char *name, AMediaFormat *fmt,
                                      LogSink &log) {
    const char *label = name ? name : "default";
    AMediaCodec *codec = name ? AMediaCodec_createCodecByName(name) : AMediaCodec_createDecoderByType(mime);
    if (!codec) {
        log.warn("%s decoder (%s) create failed", mime, label);
        return nullptr;
    }
    const char *step = "configure";
    media_status_t st = AMediaCodec_configure(codec, fmt, nullptr, nullptr, 0);
    if (st == AMEDIA_OK) {
        step = "start";
        st = AMediaCodec_start(codec);
    }
    if (st != AMEDIA_OK) {
        log.warn("%s decoder (%s) %s failed (%d)", mime, label, step, (int)st);
        AMediaCodec_delete(codec);
        return nullptr;
    }
    log.info("%s decoder started (%s)", mime, label);
    return codec;
}

static inline AMediaCodec *startMediaCodec(const CodecConfig &c, LogSink &log, bool realtimePriority,
                                           bool lowLatency) {
    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, "mime", c.mime);
    AMediaFormat_setInt32(fmt, "sample-rate", c.sampleRate);
    AMediaFormat_setInt32(fmt, "channel-count", c.channels);
    if (c.aacProfile) {
        AMediaFormat_setInt32(fmt, "aac-profile", c.aacProfile);
        AMediaFormat_setInt32(fmt, "is-adts", 0);
    }
    if (realtimePriority) AMediaFormat_setInt32(fmt, "priority", 0);
    if (lowLatency) AMediaFormat_setInt32(fmt, "low-latency", 1);
    AMediaFormat_setBuffer(fmt, "csd-0", c.csd, c.csdLen);
    AMediaCodec *codec = c.preferName ? startCodec(c.mime, c.preferName, fmt, log) : nullptr;
    if (!codec) codec = startCodec(c.mime, nullptr, fmt, log);
    AMediaFormat_delete(fmt);
    return codec;
}

static inline std::unique_ptr<Decoder> makeDecoder(int ct, int spf, int sampleRate, int channels,
                                                   TimelineBuffer &timeline, LatencyReporter &lat,
                                                   LogSink &log, bool realtimePriority,
                                                   bool lowLatency) {
    if (ct == CT_PCM) {
        log.info("PCM S16LE decoder started");
        return std::make_unique<PcmDecoder>(timeline, lat);
    }
    const auto c = codecConfig(ct, spf, sampleRate, channels);
    if (!c) {
        log.error("Unknown audio ct=%d", ct);
        return nullptr;
    }
    if (AMediaCodec *codec = startMediaCodec(*c, log, realtimePriority, lowLatency)) {
        return std::make_unique<MediaCodecDecoder>(codec, timeline, lat);
    }
    log.error("%s decoder init failed (ct=%d)", c->mime, ct);
    return nullptr;
}

#endif  // AUDIO_DECODER_H
