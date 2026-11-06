/*
 * Native low-latency audio pipeline for AirPlay receiver:
 *   decoding (PCM / ALAC / AAC-LC / AAC-ELD) -> jitter timeline buffer -> Oboe PCM output
 */

#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include "audio_engine.h"
#include "audio_decoder.h"
#include "audio_output.h"
#include "log_sink.h"
#include "timeline_buffer.h"

struct AudioConfig {
    const int staticCushionMs;
    const int percentilePct;
    const int oboeBufferFrames;
    const bool realtimePriority;
    const bool lowLatency;
};

struct CodecFormat {
    int spf = 0;
    bool operator==(const CodecFormat &o) const { return spf == o.spf; }
    bool operator!=(const CodecFormat &o) const { return !(*this == o); }
};

struct AudioEngine {
    AudioEngine(std::shared_ptr<LogSink> log, int sampleRate, int channels)
        : mSampleRate(sampleRate), mChannels(channels), mLog(std::move(log)) {
        mQueued[ctIndex(CT_PCM)].store(CodecFormat{352}, std::memory_order_relaxed);
        mQueued[ctIndex(CT_ALAC)].store(CodecFormat{352}, std::memory_order_relaxed);
        mQueued[ctIndex(CT_AAC_LC)].store(CodecFormat{1024}, std::memory_order_relaxed);
        mQueued[ctIndex(CT_AAC_ELD)].store(CodecFormat{480}, std::memory_order_relaxed);
    }

    ~AudioEngine() {
        delete mPending.load(std::memory_order_relaxed);
    }

    bool configure(const AudioConfig &cfg) {
        std::lock_guard<std::mutex> lk(mRebuildLock);
        if (!mApplied) {
            initInternals(cfg);
            return true;
        }
        delete mPending.exchange(new AudioConfig(cfg), std::memory_order_release);
        return true;
    }

    bool start() {
        std::lock_guard<std::mutex> lk(mRebuildLock);
        mRunning = true;
        if (mOutput && !mOutputActive) {
            mTimeline->flushAndReprime();
            mOutputActive = mOutput->start();
        }
        return mOutputActive;
    }

    void pause() {
        std::lock_guard<std::mutex> lk(mRebuildLock);
        mRunning = false;
        if (mOutput && mOutputActive) {
            mOutput->stop();
            mOutputActive = false;
        }
    }

    void stop() {
        if (mOutput) mOutput->stop();
        mOutputActive = false;
        mDecoder = {};
    }

    void setClientGain(float g) {
        std::lock_guard<std::mutex> lk(mRebuildLock);
        mClientGain = g;
        if (mOutput) mOutput->setClientGain(g);
    }

    void onFormat(int ct, int spf) {
        const int i = ctIndex(ct);
        if (i < 0 || spf <= 0) return;
        mQueued[i].store(CodecFormat{spf}, std::memory_order_release);
    }

    void decode(const uint8_t *data, size_t len, int ct, int64_t ptsNs) {
        if (!mTimeline) return;
        if (mPending.load(std::memory_order_relaxed)) {
            if (AudioConfig *raw = mPending.exchange(nullptr, std::memory_order_acquire)) {
                std::unique_ptr<AudioConfig> c(raw);
                mDecoder = {};
                std::lock_guard<std::mutex> lk(mRebuildLock);
                if (mOutputActive) mOutput->stop();
                mOutput.reset();
                initInternals(*c);
                mOutputActive = mRunning ? mOutput->start() : false;
            }
        }

        const int ci = ctIndex(ct);
        const CodecFormat wantConfig = ci >= 0 ? mQueued[ci].load(std::memory_order_acquire) : CodecFormat{};
        const bool changed = ct != mDecoder.ct || wantConfig != mDecoder.format;
        if (changed || (!mDecoder.decoder && monoNs() >= mRetryAtNs)) {
            mDecoder = {};
            mDecoder = {ct, wantConfig,
                        makeDecoder(ct, wantConfig.spf, mSampleRate, mChannels, *mTimeline,
                                    mDecLatency, *mLog, mApplied->realtimePriority, mApplied->lowLatency)};
            mRetryAtNs = mDecoder.decoder ? 0 : monoNs() + DECODER_RETRY_NS;
            mTimeline->reanchorTracker();
        }
        if (mDecoder.decoder && !mDecoder.decoder->decode(data, len, ptsNs))
            mDecodeErrors.fetch_add(1, std::memory_order_relaxed);
    }

private:
    static int ctIndex(int ct) {
        switch (ct) {
            case CT_PCM:     return 0;
            case CT_ALAC:    return 1;
            case CT_AAC_LC:  return 2;
            case CT_AAC_ELD: return 3;
            default:         return -1;
        }
    }

    void initInternals(const AudioConfig &cfg) {
        mTimeline = std::make_shared<TimelineBuffer>(mSampleRate, mChannels,
                                                     cfg.staticCushionMs, cfg.percentilePct);
        mOutput = AudioOutput::create(mSampleRate, mChannels, cfg.oboeBufferFrames,
                                      cfg.lowLatency, mTimeline, mLog);
        mOutput->setClientGain(mClientGain);
        mApplied = std::make_unique<AudioConfig>(cfg);
    }

    const int mSampleRate;
    const int mChannels;
    std::unique_ptr<AudioConfig> mApplied;
    std::shared_ptr<LogSink> mLog;

    std::mutex mRebuildLock;
    std::shared_ptr<TimelineBuffer> mTimeline;
    std::shared_ptr<AudioOutput> mOutput;
    bool mRunning = false;
    bool mOutputActive = false;
    float mClientGain = 1.0f;

    LatencyReporter mDecLatency{"decode", *mLog};
    struct CreatedDecoder {
        int ct = -1;
        CodecFormat format;
        std::unique_ptr<Decoder> decoder;
    };
    CreatedDecoder mDecoder;
    static constexpr int64_t DECODER_RETRY_NS = 1'000'000'000LL;
    int64_t mRetryAtNs = 0;

    static constexpr int NUM_CT = 4;
    std::atomic<CodecFormat> mQueued[NUM_CT] = {};
    std::atomic<AudioConfig *> mPending{nullptr};
    std::atomic<int32_t> mDecodeErrors{0};
};

AudioEngine *audio_engine_create(std::shared_ptr<LogSink> log, int sampleRate, int channels) {
    if (!log) return nullptr;
    return new AudioEngine(std::move(log), sampleRate, channels);
}

extern "C" {

void audio_engine_set_default_stream_values(int sampleRate, int framesPerBurst) {
    if (sampleRate > 0) oboe::DefaultStreamValues::SampleRate = sampleRate;
    if (framesPerBurst > 0) oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst;
}

bool audio_engine_configure(AudioEngine *engine, int cushionMs, int percentilePct,
                            int oboeBufferFrames, bool realtimePriority, bool lowLatency) {
    if (!engine) return false;
    return engine->configure({cushionMs, percentilePct, oboeBufferFrames, realtimePriority, lowLatency});
}

void audio_engine_set_client_gain(AudioEngine *engine, float gain) {
    if (engine) engine->setClientGain(gain);
}

void audio_engine_on_format(AudioEngine *engine, int ct, int spf) {
    if (engine) engine->onFormat(ct, spf);
}

bool audio_engine_start(AudioEngine *engine) {
    return engine && engine->start();
}

void audio_engine_pause(AudioEngine *engine) {
    if (engine) engine->pause();
}

void audio_engine_destroy(AudioEngine *engine) {
    if (!engine) return;
    engine->stop();
    delete engine;
}

void audio_engine_decode(AudioEngine *engine, const uint8_t *data, int len, int ct, int64_t pts_ns) {
    if (engine && data && len > 0) {
        engine->decode(data, (size_t)len, ct, pts_ns);
    }
}

}
