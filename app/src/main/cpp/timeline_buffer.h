#ifndef TIMELINE_BUFFER_H
#define TIMELINE_BUFFER_H

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>

#include "audio_time.h"

/*
 * Lock-free SPSC ring of int16 samples (interleaved frames).
 */
class SpscRing {
public:
    explicit SpscRing(size_t capacitySamples)
        : mCapacity(capacitySamples), mBuf(new int16_t[capacitySamples]) {}

    size_t available() const {
        return mWrite.load(std::memory_order_acquire) - mRead.load(std::memory_order_acquire);
    }

    size_t write(const int16_t *src, size_t n) {
        const size_t w = mWrite.load(std::memory_order_relaxed);
        const size_t r = mRead.load(std::memory_order_acquire);
        const size_t freeSpace = mCapacity - (w - r);
        if (n > freeSpace) n = freeSpace;
        const size_t wi = w % mCapacity;
        const size_t first = (n < mCapacity - wi) ? n : (mCapacity - wi);
        memcpy(&mBuf[wi], src, first * sizeof(int16_t));
        if (n > first) memcpy(&mBuf[0], src + first, (n - first) * sizeof(int16_t));
        mWrite.store(w + n, std::memory_order_release);
        return n;
    }

    size_t read(int16_t *dst, size_t n) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = w - r;
        if (n > avail) n = avail;
        const size_t ri = r % mCapacity;
        const size_t first = (n < mCapacity - ri) ? n : (mCapacity - ri);
        memcpy(dst, &mBuf[ri], first * sizeof(int16_t));
        if (n > first) memcpy(dst + first, &mBuf[0], (n - first) * sizeof(int16_t));
        mRead.store(r + n, std::memory_order_release);
        return n;
    }

    void skip(size_t n) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = w - r;
        if (n > avail) n = avail;
        mRead.store(r + n, std::memory_order_release);
    }

private:
    const size_t mCapacity;
    std::unique_ptr<int16_t[]> mBuf;
    std::atomic<size_t> mWrite{0};
    std::atomic<size_t> mRead{0};
};

class DelayTracker {
public:
    DelayTracker(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate), mChannels(channels),
          mAdaptive(staticCushionMs <= 0),
          mFloor(msToSamples(sampleRate, channels, mAdaptive ? MIN_CUSHION_MS : staticCushionMs)),
          mCeil(msToSamples(sampleRate, channels, mAdaptive ? MAX_CUSHION_MS : staticCushionMs)),
          mPercentilePct(percentilePct),
          mTuned(mFloor) {}

    void observe(int64_t ptsNs, int64_t arrivalNs, int64_t durNs) {
        if (!mAdaptive) return;

        const int64_t raw = arrivalNs - ptsNs;
        if (!mHaveBase) { mBaseNs = raw; mHaveBase = true; }
        else if (raw < mBaseNs) mBaseNs = raw;
        else mBaseNs += (raw - mBaseNs) >> BASE_CREEP_SHIFT;
        int64_t rel = raw - mBaseNs;
        if (rel < 0) rel = 0;

        int bin = (int)(rel / BUCKET_NS);
        if (bin >= NBUCKETS) bin = NBUCKETS - 1;

        int64_t sum = 0;
        for (int i = 0; i < NBUCKETS; i++) {
            mHist[i] -= (mHist[i] + (1 << FORGET_SHIFT) - 1) >> FORGET_SHIFT;
            sum += mHist[i];
        }
        mHist[bin] += ONE >> FORGET_SHIFT;
        sum += ONE >> FORGET_SHIFT;

        const int64_t thresh = sum * mPercentilePct / 100;
        int64_t cum = 0; int b = 0;
        for (; b < NBUCKETS - 1; b++) { cum += mHist[b]; if (cum >= thresh) break; }
        const int64_t marginNs = std::max(durNs, mOutputBufferNs.load(std::memory_order_relaxed));
        const int64_t targetNs = (int64_t)(b + 1) * BUCKET_NS + marginNs;
        size_t samples = (size_t)(targetNs * mSampleRate / NS_PER_SEC) * mChannels;
        if (samples < mFloor) samples = mFloor;
        if (samples > mCeil) samples = mCeil;
        mTuned.store(samples, std::memory_order_relaxed);
    }

    void reanchor() { mHaveBase = false; }
    size_t target() const { return mTuned.load(std::memory_order_relaxed); }

    void noteOutputBufferFrames(int frames) {
        mOutputBufferNs.store((int64_t)frames * NS_PER_SEC / mSampleRate, std::memory_order_relaxed);
    }

    size_t ceil() const { return mCeil; }

private:
    static size_t msToSamples(int sampleRate, int channels, int ms) {
        return (size_t)sampleRate * ms / 1000 * channels;
    }

    static constexpr int MIN_CUSHION_MS = 0;
    static constexpr int MAX_CUSHION_MS = 1000;
    static constexpr int BUCKET_MS = 5;
    static constexpr int NBUCKETS = MAX_CUSHION_MS / BUCKET_MS;
    static constexpr int64_t BUCKET_NS = (int64_t)BUCKET_MS * 1000000LL;
    static constexpr int FORGET_SHIFT = 8;
    static constexpr int BASE_CREEP_SHIFT = 10;
    static constexpr int64_t ONE = 1 << 20;

    const int mSampleRate;
    const int mChannels;
    const bool mAdaptive;
    const size_t mFloor;
    const size_t mCeil;
    const int mPercentilePct;
    std::atomic<size_t> mTuned;
    std::atomic<int64_t> mOutputBufferNs{0};
    int64_t mHist[NBUCKETS] = {};
    int64_t mBaseNs = 0;
    bool mHaveBase = false;
};

class TimelineMetrics {
public:
    struct __attribute__((packed)) Debug {
        uint32_t trims, drops, silences, underruns;
    };

    void countTrim()     { mTrims.fetch_add(1, std::memory_order_relaxed); }
    void countDrop()     { mDrops.fetch_add(1, std::memory_order_relaxed); }
    void countSilence()  { mSilences.fetch_add(1, std::memory_order_relaxed); }
    void countUnderrun() { mUnderruns.fetch_add(1, std::memory_order_relaxed); }

    Debug debugInfo() const {
        Debug d{};
        d.trims = mTrims.load(std::memory_order_relaxed);
        d.drops = mDrops.load(std::memory_order_relaxed);
        d.silences = mSilences.load(std::memory_order_relaxed);
        d.underruns = mUnderruns.load(std::memory_order_relaxed);
        return d;
    }

private:
    std::atomic<uint32_t> mTrims{0};
    std::atomic<uint32_t> mDrops{0};
    std::atomic<uint32_t> mSilences{0};
    std::atomic<uint32_t> mUnderruns{0};
};

class TimelineBuffer {
public:
    TimelineBuffer(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate),
          mChannels(channels),
          mTracker(sampleRate, channels, staticCushionMs, percentilePct),
          mRing(2 * capOf(mTracker.ceil()) + (size_t)sampleRate * TRIM_THROTTLE_MS / 1000 * channels) {}

    void noteOutputBufferFrames(int frames) { mTracker.noteOutputBufferFrames(frames); }

    void write(const int16_t *pcm, size_t samples, int64_t ptsNs) {
        if (samples == 0) return;
        if (ptsNs == 0) {
            mRing.write(pcm, samples);
            return;
        }
        const int64_t durNs = samples * NS_PER_SEC / mChannels / mSampleRate;

        if (mUnderran.exchange(false, std::memory_order_relaxed)) {
            mExpectedPtsNs = ptsNs;
        }
        const int64_t gapNs = ptsNs - mExpectedPtsNs;

        if (gapNs > MAX_GAP_NS || gapNs < -MAX_GAP_NS) mTracker.reanchor();
        mTracker.observe(ptsNs, monoNs(), durNs);

        if (gapNs > MAX_GAP_NS || gapNs < -MAX_GAP_NS) {
            // discontinuity: write immediately
        } else if (gapNs > SLACK_NS) {
            const size_t silenceFrames = (size_t)(gapNs * mSampleRate / NS_PER_SEC);
            writeSilenceFrames(silenceFrames);
            mMetrics.countSilence();
        } else if (gapNs < -SLACK_NS) {
            mMetrics.countDrop();
            return;
        }
        mRing.write(pcm, samples);
        mExpectedPtsNs = ptsNs + durNs;
    }

    void read(int16_t *out, int32_t frames) {
        const size_t need = (size_t)frames * mChannels;
        const size_t tuned = mTracker.target();
        const int64_t now = monoNs();

        const size_t avail = mRing.available();
        if (avail > capOf(tuned)) {
            if (mAboveCapSinceNs == 0) mAboveCapSinceNs = now;
        } else {
            mAboveCapSinceNs = 0;
        }
        if (mAboveCapSinceNs != 0 && now - mAboveCapSinceNs >= TRIM_SUSTAIN_NS
                && now - mLastTrimBlockNs >= TRIM_THROTTLE_NS) {
            mRing.skip(avail - tuned);
            mMetrics.countTrim();
            mLastTrimBlockNs = now;
            mAboveCapSinceNs = 0;
        }

        if (mPriming) {
            const size_t buffered = mRing.available();
            if (buffered == 0) mPrimeSilenceFrames = 0;
            else mPrimeSilenceFrames += (uint32_t)frames;
            const uint32_t starveFrames = (uint32_t)(2 * tuned / mChannels);
            const bool starved = buffered > 0 && mPrimeSilenceFrames >= starveFrames;
            if (buffered == 0 || (buffered < tuned && !starved)) {
                memset(out, 0, need * sizeof(int16_t));
                return;
            }
            mPriming = false;
            mPrimeSilenceFrames = 0;
        }

        const size_t got = mRing.read(out, need);
        if (got < need) {
            memset(out + got, 0, (need - got) * sizeof(int16_t));
            mPriming = true;
            mLastTrimBlockNs = now;
            mUnderran.store(true, std::memory_order_relaxed);
            mMetrics.countUnderrun();
        }
    }

    void reanchorTracker() { mTracker.reanchor(); }
    void reprime() { mPriming = true; }

    void flushAndReprime() {
        mRing.skip(mRing.available());
        mPriming = true;
    }

    struct __attribute__((packed)) Debug {
        uint16_t backlogMs;
        uint16_t tunedCushionMs;
        TimelineMetrics::Debug metrics;
    };
    Debug debugInfo() const {
        Debug d{};
        d.backlogMs = (uint16_t)(mRing.available() / mChannels * 1000 / mSampleRate);
        d.tunedCushionMs = (uint16_t)(mTracker.target() / mChannels * 1000 / mSampleRate);
        d.metrics = mMetrics.debugInfo();
        return d;
    }

private:
    size_t capOf(size_t cushionSamples) const {
        return std::max(cushionSamples * 2, (size_t)mSampleRate * TRIM_FLOOR_MS / 1000 * mChannels);
    }

    void writeSilenceFrames(size_t frames) {
        int16_t zeros[512] = {0};
        size_t samples = frames * mChannels;
        while (samples > 0) {
            const size_t n = std::min<size_t>(samples, 512);
            mRing.write(zeros, n);
            samples -= n;
        }
    }

    static constexpr int64_t SLACK_NS = 10'000'000LL;
    static constexpr int64_t MAX_GAP_NS = 750'000'000LL;
    static constexpr int TRIM_FLOOR_MS = 30;
    static constexpr int TRIM_THROTTLE_MS = 2000;
    static constexpr int64_t TRIM_THROTTLE_NS = (int64_t)TRIM_THROTTLE_MS * 1'000'000LL;
    static constexpr int TRIM_SUSTAIN_MS = 2000;
    static constexpr int64_t TRIM_SUSTAIN_NS = (int64_t)TRIM_SUSTAIN_MS * 1'000'000LL;

    const int mSampleRate;
    const int mChannels;
    DelayTracker mTracker;
    SpscRing mRing;
    TimelineMetrics mMetrics;
    bool mPriming = true;
    uint32_t mPrimeSilenceFrames = 0;
    int64_t mLastTrimBlockNs = 0;
    int64_t mAboveCapSinceNs = 0;
    std::atomic<bool> mUnderran{false};
    int64_t mExpectedPtsNs = 0;
};

#endif  // TIMELINE_BUFFER_H
