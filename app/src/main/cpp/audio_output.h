#ifndef AUDIO_OUTPUT_H
#define AUDIO_OUTPUT_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>

#include "log_sink.h"
#include "timeline_buffer.h"

class AudioOutput;

class OboeCallbacks : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
    OboeCallbacks(std::shared_ptr<TimelineBuffer> timeline, std::shared_ptr<LogSink> log,
                  int channels)
        : mTimeline(std::move(timeline)), mLog(std::move(log)), mChannels(channels) {}

    void setClientGain(float g) { mClientGain.store(g, std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData,
                                          int32_t numFrames) override {
        auto *out = static_cast<int16_t *>(audioData);
        mTimeline->read(out, numFrames);

        const float gain = mClientGain.load(std::memory_order_relaxed);
        if (gain < 1.0f) {
            const size_t n = (size_t)numFrames * mChannels;
            for (size_t i = 0; i < n; i++) out[i] = (int16_t)(out[i] * gain);
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override;

private:
    friend class AudioOutput;

    std::shared_ptr<TimelineBuffer> mTimeline;
    std::shared_ptr<LogSink> mLog;
    const int mChannels;
    std::atomic<float> mClientGain{1.0f};
    std::weak_ptr<AudioOutput> mOwner;
};

class AudioOutput {
public:
    static std::shared_ptr<AudioOutput> create(int sampleRate, int channels, int oboeBufferFrames,
                                               bool lowLatency,
                                               std::shared_ptr<TimelineBuffer> timeline,
                                               std::shared_ptr<LogSink> log) {
        auto out = std::make_shared<AudioOutput>(sampleRate, channels, oboeBufferFrames,
                                                 lowLatency, std::move(timeline), std::move(log));
        out->mCallbacks->mOwner = out;
        return out;
    }

    AudioOutput(int sampleRate, int channels, int oboeBufferFrames, bool lowLatency,
                std::shared_ptr<TimelineBuffer> timeline, std::shared_ptr<LogSink> log)
        : mSampleRate(sampleRate), mChannels(channels), mOboeBufferFrames(oboeBufferFrames),
          mLowLatency(lowLatency), mTimeline(std::move(timeline)), mLog(std::move(log)),
          mCallbacks(std::make_shared<OboeCallbacks>(mTimeline, mLog, channels)) {}

    ~AudioOutput() { stop(); }

    bool start() {
        std::lock_guard<std::mutex> lk(mLock);
        mClosing.store(false);
        if (!openLocked()) return false;
        if (mStream->requestStart() != oboe::Result::OK) {
            mStream->close();
            mStream.reset();
            return false;
        }
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lk(mLock);
        mClosing.store(true);
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
        }
    }

    void setClientGain(float g) { mCallbacks->setClientGain(g); }

    struct __attribute__((packed)) Debug {
        int32_t xrun;
    };
    Debug debugInfo() {
        std::unique_lock<std::mutex> lk(mLock, std::try_to_lock);
        if (lk.owns_lock() && mStream) {
            auto r = mStream->getXRunCount();
            if (r) mLastXrun = r.value();
        }
        return Debug{mLastXrun};
    }

private:
    friend class OboeCallbacks;

    void reopenAfterError() {
        std::lock_guard<std::mutex> lk(mLock);
        if (mClosing.load()) return;
        mStream.reset();
        mTimeline->reprime();
        if (openLocked()) mStream->requestStart();
    }

    bool openLocked() {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(mChannels)
            ->setSampleRate(mSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(mCallbacks)
            ->setErrorCallback(mCallbacks);
        if (mLowLatency) {
            b.setPerformanceMode(oboe::PerformanceMode::LowLatency)->setUsage(oboe::Usage::Game);
        } else {
            b.setPerformanceMode(oboe::PerformanceMode::PowerSaving)->setUsage(oboe::Usage::Media);
        }
        oboe::Result r = b.openStream(mStream);
        if (r != oboe::Result::OK) {
            mLog->error("Failed to open Oboe stream: %s", oboe::convertToText(r));
            return false;
        }
        if (mOboeBufferFrames > 0) {
            mStream->setBufferSizeInFrames(mOboeBufferFrames);
        } else if (mLowLatency) {
            mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
        }

        const bool aaudio = mStream->getAudioApi() == oboe::AudioApi::AAudio;
        const char *api = aaudio ? "AAudio"
                        : mStream->getAudioApi() == oboe::AudioApi::OpenSLES ? "OpenSLES" : "?";
        const bool lowLatency = mStream->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
        mLog->info("Oboe out: %s%s, buffer=%d/%d frames, burst=%d, %d Hz",
                  api, lowLatency ? " (low-latency)" : "",
                  mStream->getBufferSizeInFrames(), mStream->getBufferCapacityInFrames(),
                  mStream->getFramesPerBurst(), mStream->getSampleRate());
        mTimeline->noteOutputBufferFrames(mStream->getBufferSizeInFrames());
        return true;
    }

    const int mSampleRate;
    const int mChannels;
    const int mOboeBufferFrames;
    const bool mLowLatency;

    std::shared_ptr<TimelineBuffer> mTimeline;
    std::shared_ptr<LogSink> mLog;
    std::atomic<bool> mClosing{false};
    std::shared_ptr<OboeCallbacks> mCallbacks;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mLock;
    int32_t mLastXrun = 0;
};

inline void OboeCallbacks::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    mLog->error("Oboe stream error: %s - reopening", oboe::convertToText(error));
    if (auto owner = mOwner.lock()) owner->reopenAfterError();
}

#endif  // AUDIO_OUTPUT_H
