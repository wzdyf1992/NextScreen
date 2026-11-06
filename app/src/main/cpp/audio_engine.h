#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AudioEngine AudioEngine;
typedef struct LogSink LogSink;

void audio_engine_set_default_stream_values(int sampleRate, int framesPerBurst);

bool audio_engine_configure(AudioEngine *engine, int cushionMs, int percentilePct,
                            int oboeBufferFrames, bool realtimePriority, bool lowLatency);

void audio_engine_set_client_gain(AudioEngine *engine, float gain);
void audio_engine_on_format(AudioEngine *engine, int ct, int spf);
bool audio_engine_start(AudioEngine *engine);
void audio_engine_pause(AudioEngine *engine);
void audio_engine_destroy(AudioEngine *engine);
void audio_engine_decode(AudioEngine *engine, const uint8_t *data, int len, int ct, int64_t pts_ns);

#ifdef __cplusplus
}

#include <memory>

extern "C++" AudioEngine *audio_engine_create(std::shared_ptr<LogSink> log, int sampleRate,
                                              int channels);
#endif

#endif  // AUDIO_ENGINE_H
