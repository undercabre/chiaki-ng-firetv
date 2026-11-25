// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>

#include <string.h>
#include <time.h>

#define INPUT_BUFFER_TIMEOUT_MS 10

static void *android_chiaki_video_decoder_output_thread_func(void *user);

static uint64_t now_us(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}

void android_chiaki_video_decoder_set_low_latency(AndroidChiakiVideoDecoder *decoder, bool low_latency)
{
	chiaki_mutex_lock(&decoder->codec_mutex);
	decoder->low_latency = low_latency;
	chiaki_mutex_unlock(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_get_stats(AndroidChiakiVideoDecoder *decoder, AndroidChiakiVideoDecoderStats *stats, bool *low_latency_active)
{
	chiaki_mutex_lock(&decoder->stats_mutex);
	*stats = decoder->stats;
	decoder->stats.decode_us_max = 0;
	*low_latency_active = decoder->low_latency_active;
	chiaki_mutex_unlock(&decoder->stats_mutex);
}

/**
 * Format for the decoder. With low_latency, ask for every low latency mode known on Android TV
 * chips. Unknown keys are ignored by decoders; if a decoder refuses them, configure() fails and
 * the caller retries without them.
 */
static AMediaFormat *create_format(AndroidChiakiVideoDecoder *decoder, const char *mime, bool low_latency)
{
	AMediaFormat *format = AMediaFormat_new();
	AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, decoder->target_height);
	// Big enough input buffers so a frame is never split in two (the decoder would wait for the rest)
	AMediaFormat_setInt32(format, "max-input-size", decoder->target_width * decoder->target_height * 3 / 2);
	if(low_latency)
	{
		AMediaFormat_setInt32(format, "low-latency", 1); // Android 11+ standard key
		AMediaFormat_setInt32(format, "priority", 0); // realtime
		AMediaFormat_setInt32(format, "operating-rate", 32767); // decode as fast as possible
		AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1); // Qualcomm
		AMediaFormat_setInt32(format, "vendor.qti-ext-dec-picture-order.enable", 1);
		AMediaFormat_setInt32(format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1); // HiSilicon
		AMediaFormat_setInt32(format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
		AMediaFormat_setInt32(format, "vendor.rtc-ext-dec-low-latency.enable", 1); // Exynos
		AMediaFormat_setInt32(format, "vendor.low-latency.enable", 1); // Amlogic (Codec2)
		AMediaFormat_setInt32(format, "vdec-lowlatency", 1); // Amlogic (OMX)
	}
	return format;
}

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec)
{
	decoder->log = log;
	decoder->codec = NULL;
	decoder->timestamp_cur = 0;
	decoder->target_width = target_width;
	decoder->target_height = target_height;
	decoder->target_codec = codec;
	decoder->shutdown_output = false;
	decoder->low_latency = false;
	decoder->low_latency_active = false;
	memset(&decoder->stats, 0, sizeof(decoder->stats));
	memset(decoder->queue_time_us, 0, sizeof(decoder->queue_time_us));
	ChiakiErrorCode err = chiaki_mutex_init(&decoder->stats_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;
	err = chiaki_mutex_init(&decoder->codec_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		chiaki_mutex_fini(&decoder->stats_mutex);
	return err;
}

static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->codec_mutex);
	decoder->shutdown_output = true;
	ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
	if(codec_buf_index >= 0)
	{
		CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, decoder->timestamp_cur++, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
		chiaki_thread_join(&decoder->output_thread, NULL);
	}
	else
	{
		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
	}
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;
	decoder->shutdown_output = false;
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
	if(decoder->codec)
		kill_decoder(decoder);
	chiaki_mutex_fini(&decoder->codec_mutex);
	chiaki_mutex_fini(&decoder->stats_mutex);
}

void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface)
{
	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!surface)
	{
		if(decoder->codec)
		{
			kill_decoder(decoder);
			CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
		}
		return;
	}

	if(decoder->codec)
	{
#if __ANDROID_API__ >= 23
		CHIAKI_LOGI(decoder->log, "Video decoder already initialized, swapping surface");
		ANativeWindow *new_window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
		AMediaCodec_setOutputSurface(decoder->codec, new_window);
		ANativeWindow_release(decoder->window);
		decoder->window = new_window;
#else
		CHIAKI_LOGE(decoder->log, "Video Decoder already initialized");
#endif
		goto beach;
	}

	decoder->window = ANativeWindow_fromSurface(env, surface);

	const char *mime = chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
	CHIAKI_LOGI(decoder->log, "Initializing decoder with mime %s", mime);

	decoder->codec = AMediaCodec_createDecoderByType(mime);
	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s", mime);
		goto error_surface;
	}

	AMediaFormat *format = create_format(decoder, mime, decoder->low_latency);
	media_status_t r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
	decoder->low_latency_active = decoder->low_latency && r == AMEDIA_OK;
	if(r != AMEDIA_OK && decoder->low_latency)
	{
		// Some decoders reject the low latency keys: fall back to the plain configuration
		CHIAKI_LOGW(decoder->log, "AMediaCodec_configure() with low latency failed (%d), retrying without", (int)r);
		AMediaFormat_delete(format);
		AMediaCodec_delete(decoder->codec);
		decoder->codec = AMediaCodec_createDecoderByType(mime);
		if(!decoder->codec)
		{
			CHIAKI_LOGE(decoder->log, "Failed to re-create AMediaCodec for mime type %s", mime);
			goto error_surface;
		}
		format = create_format(decoder, mime, false);
		r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
	}
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_configure() failed: %d", (int)r);
		AMediaFormat_delete(format);
		goto error_codec;
	}
	CHIAKI_LOGI(decoder->log, "Video decoder configured, low latency: %s", decoder->low_latency_active ? "yes" : "no");

	r = AMediaCodec_start(decoder->codec);
	AMediaFormat_delete(format);
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_start() failed: %d", (int)r);
		goto error_codec;
	}

	ChiakiErrorCode err = chiaki_thread_create(&decoder->output_thread, android_chiaki_video_decoder_output_thread_func, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create output thread for AMediaCodec");
		goto error_codec;
	}

	goto beach;

error_codec:
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;

error_surface:
	ANativeWindow_release(decoder->window);
	decoder->window = NULL;

beach:
	chiaki_mutex_unlock(&decoder->codec_mutex);
}

bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user)
{
	bool r = true;
	AndroidChiakiVideoDecoder *decoder = user;
	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Received video data, but decoder is not initialized!");
		goto beach;
	}

	if(frames_lost > 0)
	{
		chiaki_mutex_lock(&decoder->stats_mutex);
		decoder->stats.frames_lost += (uint64_t)frames_lost;
		chiaki_mutex_unlock(&decoder->stats_mutex);
	}

	while(buf_size > 0)
	{
		ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, INPUT_BUFFER_TIMEOUT_MS * 1000);
		if(codec_buf_index < 0)
		{
			CHIAKI_LOGE(decoder->log, "Failed to get input buffer");
			r = false;
			goto beach;
		}

		size_t codec_buf_size;
		uint8_t *codec_buf = AMediaCodec_getInputBuffer(decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
		size_t codec_sample_size = buf_size;
		if(codec_sample_size > codec_buf_size)
		{
			//CHIAKI_LOGD(decoder->log, "Sample is bigger than buffer, splitting");
			codec_sample_size = codec_buf_size;
		}
		memcpy(codec_buf, buf, codec_sample_size);
		// remember when this frame entered the decoder to measure the decode time at the output
		decoder->queue_time_us[decoder->timestamp_cur % ANDROID_CHIAKI_VIDEO_DECODER_QUEUE_TIMES] = now_us();
		media_status_t r = AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, codec_sample_size, decoder->timestamp_cur++, 0); // timestamp just raised by 1 for maximum realtime
		if(r != AMEDIA_OK)
		{
			CHIAKI_LOGE(decoder->log, "AMediaCodec_queueInputBuffer() failed: %d", (int)r);
		}
		buf += codec_sample_size;
		buf_size -= codec_sample_size;

	}

beach:
	chiaki_mutex_unlock(&decoder->codec_mutex);
	return r;
}

static void *android_chiaki_video_decoder_output_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;

	while(1)
	{
		AMediaCodecBufferInfo info;
		ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
		if(status >= 0)
		{
			uint64_t dropped = 0;
			// Low latency: if newer frames are already decoded, skip the older ones and show the newest
			while(decoder->low_latency && !(info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM))
			{
				AMediaCodecBufferInfo next_info;
				ssize_t next = AMediaCodec_dequeueOutputBuffer(decoder->codec, &next_info, 0);
				if(next < 0)
					break;
				AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, false);
				dropped++;
				status = next;
				info = next_info;
			}

			bool render = info.size != 0;
			if(render)
			{
				uint64_t queued = decoder->queue_time_us[(uint64_t)info.presentationTimeUs % ANDROID_CHIAKI_VIDEO_DECODER_QUEUE_TIMES];
				uint64_t now = now_us();
				chiaki_mutex_lock(&decoder->stats_mutex);
				decoder->stats.frames_rendered++;
				decoder->stats.frames_dropped += dropped;
				if(queued && now > queued && now - queued < 1000000)
				{
					uint64_t d = now - queued;
					decoder->stats.decode_us_sum += d;
					decoder->stats.decode_samples++;
					if(d > decoder->stats.decode_us_max)
						decoder->stats.decode_us_max = d;
				}
				chiaki_mutex_unlock(&decoder->stats_mutex);
			}

			AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, render);
			if(info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
			{
				CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
				break;
			}
		}
		else
		{
			chiaki_mutex_lock(&decoder->codec_mutex);
			bool shutdown = decoder->shutdown_output;
			chiaki_mutex_unlock(&decoder->codec_mutex);
			if(shutdown)
			{
				CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread detected shutdown after reported error");
				break;
			}
		}
	}

	CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");

	return NULL;
}