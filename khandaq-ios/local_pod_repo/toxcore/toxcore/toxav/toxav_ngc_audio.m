/* SPDX-License-Identifier: GPL-3.0-or-later */

#include "toxav.h"

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "opus.h"
#include "../toxcore/tox.h"

#define NGC__AUDIO_OPUS_COMPLEXITY (10)
#define NGC__AUDIO_OPUS_PACKET_LOSS_PERC (4)
#define NGC__AUDIO_MAX_ENCODED_DATA_BYTES (TOX_MAX_CUSTOM_PACKET_SIZE - 1 - 10)
#define NGC__AUDIO_MAX_PCM_DATA_BYTES (5760)

struct ToxAV_NGC_acoders {
    OpusEncoder *ngc__opus_encoder;
    OpusDecoder *ngc__opus_decoder;
    int32_t ngc__a_encoder_bitrate;
    int32_t ngc__a_encoder_sampling_rate;
    int32_t ngc__a_encoder_channel_count;
};

void *toxav_ngc_audio_init(const int32_t bit_rate, const int32_t sampling_rate, const int32_t channel_count)
{
    struct ToxAV_NGC_acoders *ngc_audio_coders = calloc(1, sizeof(struct ToxAV_NGC_acoders));

    if (!ngc_audio_coders) {
        return NULL;
    }

    int status_enc = OPUS_OK;
    OpusEncoder *opus_encoder = opus_encoder_create(sampling_rate, channel_count, OPUS_APPLICATION_VOIP, &status_enc);

    if (status_enc != OPUS_OK) {
        ngc_audio_coders->ngc__opus_encoder = NULL;
    } else {
        ngc_audio_coders->ngc__opus_encoder = opus_encoder;
    }

    ngc_audio_coders->ngc__a_encoder_sampling_rate = sampling_rate;
    ngc_audio_coders->ngc__a_encoder_channel_count = channel_count;
    ngc_audio_coders->ngc__a_encoder_bitrate = bit_rate;

    if (ngc_audio_coders->ngc__opus_encoder) {
        status_enc = opus_encoder_ctl(ngc_audio_coders->ngc__opus_encoder, OPUS_SET_BITRATE(bit_rate));

        if (status_enc != OPUS_OK) {
            opus_encoder_destroy(ngc_audio_coders->ngc__opus_encoder);
            ngc_audio_coders->ngc__opus_encoder = NULL;
        }
    }

    if (ngc_audio_coders->ngc__opus_encoder) {
        opus_encoder_ctl(ngc_audio_coders->ngc__opus_encoder, OPUS_SET_VBR(1));
        opus_encoder_ctl(ngc_audio_coders->ngc__opus_encoder, OPUS_SET_INBAND_FEC(1));
        opus_encoder_ctl(ngc_audio_coders->ngc__opus_encoder, OPUS_SET_PACKET_LOSS_PERC(NGC__AUDIO_OPUS_PACKET_LOSS_PERC));
        status_enc = opus_encoder_ctl(ngc_audio_coders->ngc__opus_encoder, OPUS_SET_COMPLEXITY(NGC__AUDIO_OPUS_COMPLEXITY));

        if (status_enc != OPUS_OK) {
            opus_encoder_destroy(ngc_audio_coders->ngc__opus_encoder);
            ngc_audio_coders->ngc__opus_encoder = NULL;
        }
    }

    int status_dec;
    ngc_audio_coders->ngc__opus_decoder = opus_decoder_create(sampling_rate, channel_count, &status_dec);

    if (status_dec != OPUS_OK) {
        if (ngc_audio_coders->ngc__opus_decoder) {
            opus_decoder_destroy(ngc_audio_coders->ngc__opus_decoder);
        }
        ngc_audio_coders->ngc__opus_decoder = NULL;
    }

    return (void *)ngc_audio_coders;
}

void toxav_ngc_audio_kill(void *angc)
{
    struct ToxAV_NGC_acoders *ngc_audio_coders = (struct ToxAV_NGC_acoders *)angc;

    if (!ngc_audio_coders) {
        return;
    }

    if (ngc_audio_coders->ngc__opus_encoder) {
        opus_encoder_destroy(ngc_audio_coders->ngc__opus_encoder);
        ngc_audio_coders->ngc__opus_encoder = NULL;
    }

    if (ngc_audio_coders->ngc__opus_decoder) {
        opus_decoder_destroy(ngc_audio_coders->ngc__opus_decoder);
        ngc_audio_coders->ngc__opus_decoder = NULL;
    }

    free(ngc_audio_coders);
}

bool toxav_ngc_audio_encode(void *angc, const int16_t *pcm, const int32_t sample_count_per_frame,
                            uint8_t *encoded_frame_bytes, uint32_t *encoded_frame_size_bytes)
{
    if (!pcm || sample_count_per_frame <= 0 || !encoded_frame_bytes || !encoded_frame_size_bytes) {
        return false;
    }

    struct ToxAV_NGC_acoders *ngc_audio_coders = (struct ToxAV_NGC_acoders *)angc;

    if (!ngc_audio_coders || !ngc_audio_coders->ngc__opus_encoder) {
        return false;
    }

    const int max_data_bytes = NGC__AUDIO_MAX_ENCODED_DATA_BYTES;
    const int encoded_bytes = opus_encode(ngc_audio_coders->ngc__opus_encoder,
                                          pcm,
                                          sample_count_per_frame,
                                          encoded_frame_bytes,
                                          max_data_bytes);

    if (encoded_bytes <= 0) {
        return false;
    }

    *encoded_frame_size_bytes = (uint32_t)encoded_bytes;
    return true;
}

int32_t toxav_ngc_audio_decode(void *angc, const uint8_t *encoded_frame_bytes,
                               const uint32_t encoded_frame_size_bytes,
                               int16_t *pcm_decoded)
{
    if (!pcm_decoded || !encoded_frame_bytes || encoded_frame_size_bytes < 1) {
        return -1;
    }

    struct ToxAV_NGC_acoders *ngc_audio_coders = (struct ToxAV_NGC_acoders *)angc;

    if (!ngc_audio_coders || !ngc_audio_coders->ngc__opus_decoder) {
        return -1;
    }

    const int max_frame_size = NGC__AUDIO_MAX_PCM_DATA_BYTES;
    const int samples_decoded = opus_decode(ngc_audio_coders->ngc__opus_decoder,
                                            encoded_frame_bytes,
                                            (int)encoded_frame_size_bytes,
                                            pcm_decoded,
                                            max_frame_size,
                                            0);

    if (samples_decoded <= 0) {
        return -1;
    }

    return samples_decoded;
}
