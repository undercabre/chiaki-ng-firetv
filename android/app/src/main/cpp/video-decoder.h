// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>

#include <chiaki/thread.h>
#include <chiaki/log.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

#define ANDROID_CHIAKI_VIDEO_DECODER_QUEUE_TIMES 64

typedef struct android_chiaki_video_decoder_stats_t
{
	uint64_t frames_rendered;
	uint64_t frames_dropped; // decoded but skipped because a newer frame was ready (low latency)
	uint64_t frames_lost; // lost on the network, reported by chiaki
	uint64_t decode_us_sum;
	uint64_t decode_samples;
	uint64_t decode_us_max; // reset on each read
} AndroidChiakiVideoDecoderStats;

typedef struct android_chiaki_video_decoder_t
{
	ChiakiLog *log;
	ChiakiMutex codec_mutex;
	AMediaCodec *codec;
	ANativeWindow *window;
	uint64_t timestamp_cur;
	ChiakiThread output_thread;
	bool shutdown_output;
	int32_t target_width;
	int32_t target_height;
	ChiakiCodec target_codec;

	/** Fire TV: ask the decoder for low latency and always show the newest decoded frame. */
	bool low_latency;
	bool low_latency_active; // the decoder accepted the low latency configuration
	ChiakiMutex stats_mutex;
	AndroidChiakiVideoDecoderStats stats;
	uint64_t queue_time_us[ANDROID_CHIAKI_VIDEO_DECODER_QUEUE_TIMES];
} AndroidChiakiVideoDecoder;

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user);
/** Must be called before the surface is set (the decoder is configured then). */
void android_chiaki_video_decoder_set_low_latency(AndroidChiakiVideoDecoder *decoder, bool low_latency);
/** Cumulative counters; decode_us_max is reset by each call. */
void android_chiaki_video_decoder_get_stats(AndroidChiakiVideoDecoder *decoder, AndroidChiakiVideoDecoderStats *stats, bool *low_latency_active);

#endif