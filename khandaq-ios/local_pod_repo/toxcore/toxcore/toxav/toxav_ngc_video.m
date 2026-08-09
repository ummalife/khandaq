/* SPDX-License-Identifier: GPL-3.0-or-later */

#include "toxav.h"

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#import <VideoToolbox/VideoToolbox.h>
#import <CoreMedia/CoreMedia.h>

#include "../toxcore/tox.h"

#define NGC__VIDEO_WIDTH 480
#define NGC__VIDEO_HEIGHT 640
#define NGC__VIDEO_MAX_ENCODED_BYTES 36989

struct ToxAV_NGC_vcoders {
    VTCompressionSessionRef encoder;
    VTDecompressionSessionRef decoder;
    CMFormatDescriptionRef decoderFormat;
    uint32_t encoder_bitrate;
    uint32_t encoder_max_quantizer;
    uint8_t *encode_scratch;
    uint32_t encode_scratch_size;
    dispatch_semaphore_t encode_sem;
    bool encode_ok;
};

typedef struct NgcDecodeOutput {
    uint8_t *y;
    uint8_t *u;
    uint8_t *v;
    int32_t *ystride;
    int32_t *ustride;
    int32_t *vstride;
    uint16_t width;
    uint16_t height;
    bool got_frame;
} NgcDecodeOutput;

static NgcDecodeOutput g_ngc_decode_output;

static void ngc_append_annexb_nal(uint8_t *dst, uint32_t *offset, const uint8_t *nal, size_t nal_size)
{
    static const uint8_t start_code[4] = {0x00, 0x00, 0x00, 0x01};

    memcpy(dst + *offset, start_code, sizeof(start_code));
    *offset += (uint32_t)sizeof(start_code);
    memcpy(dst + *offset, nal, nal_size);
    *offset += (uint32_t)nal_size;
}

static void ngc_encode_callback(void *outputCallbackRefCon, void *sourceFrameRefCon, OSStatus status,
                                VTEncodeInfoFlags infoFlags, CMSampleBufferRef sampleBuffer)
{
    (void)sourceFrameRefCon;
    (void)infoFlags;

    struct ToxAV_NGC_vcoders *vc = (struct ToxAV_NGC_vcoders *)outputCallbackRefCon;

    if (!vc || status != noErr || !sampleBuffer) {
        if (vc) {
            vc->encode_ok = false;
            dispatch_semaphore_signal(vc->encode_sem);
        }
        return;
    }

    if (!CMSampleBufferDataIsReady(sampleBuffer)) {
        vc->encode_ok = false;
        dispatch_semaphore_signal(vc->encode_sem);
        return;
    }

    CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sampleBuffer);

    if (!block) {
        vc->encode_ok = false;
        dispatch_semaphore_signal(vc->encode_sem);
        return;
    }

    size_t total_length = 0;
    char *data_pointer = NULL;

    if (CMBlockBufferGetDataPointer(block, 0, NULL, &total_length, &data_pointer) != kCMBlockBufferNoErr) {
        vc->encode_ok = false;
        dispatch_semaphore_signal(vc->encode_sem);
        return;
    }

    uint32_t offset = 0;
    const uint8_t *src = (const uint8_t *)data_pointer;
    size_t pos = 0;

    while (pos + 4 <= total_length) {
        const uint32_t nal_size = ((uint32_t)src[pos] << 24) | ((uint32_t)src[pos + 1] << 16) |
                                  ((uint32_t)src[pos + 2] << 8) | (uint32_t)src[pos + 3];
        pos += 4;

        if (nal_size == 0 || pos + nal_size > total_length) {
            break;
        }

        if (offset + 4 + nal_size > NGC__VIDEO_MAX_ENCODED_BYTES) {
            vc->encode_ok = false;
            dispatch_semaphore_signal(vc->encode_sem);
            return;
        }

        ngc_append_annexb_nal(vc->encode_scratch, &offset, src + pos, nal_size);
        pos += nal_size;
    }

    vc->encode_scratch_size = offset;
    vc->encode_ok = offset > 0;
    dispatch_semaphore_signal(vc->encode_sem);
}

static void ngc_decoder_output_callback(void *decompressionOutputRefCon, void *sourceFrameRefCon, OSStatus status,
                                        VTDecodeInfoFlags infoFlags, CVImageBufferRef imageBuffer,
                                        CMTime presentationTimeStamp, CMTime presentationDuration)
{
    (void)decompressionOutputRefCon;
    (void)sourceFrameRefCon;
    (void)infoFlags;
    (void)presentationTimeStamp;
    (void)presentationDuration;

    if (status != noErr || !imageBuffer || !g_ngc_decode_output.y) {
        return;
    }

    // KHANDAQ (audit #8): the decode session pins its output format (see ngc_update_decoder_format),
    // but validate it here too - the copy loops below only understand planar/bi-planar 4:2:0. For a
    // non-planar buffer CVPixelBufferGetBaseAddressOfPlane() returns NULL and the chroma loop would
    // dereference it. Neither call needs the base address to be locked.
    const OSType pixel_format = CVPixelBufferGetPixelFormatType(imageBuffer);
    const size_t plane_count = CVPixelBufferGetPlaneCount(imageBuffer);
    const bool is_nv12 = pixel_format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
                         pixel_format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;
    const bool is_i420 = pixel_format == kCVPixelFormatType_420YpCbCr8Planar ||
                         pixel_format == kCVPixelFormatType_420YpCbCr8PlanarFullRange;

    if (!((is_nv12 && plane_count == 2) || (is_i420 && plane_count >= 3))) {
        return;
    }

    CVPixelBufferLockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);

    const uint8_t *src_y = CVPixelBufferGetBaseAddressOfPlane(imageBuffer, 0);
    const size_t src_y_stride = CVPixelBufferGetBytesPerRowOfPlane(imageBuffer, 0);
    const uint16_t out_w = g_ngc_decode_output.width;
    const uint16_t out_h = g_ngc_decode_output.height;

    // KHANDAQ (audit #5): a malicious group peer controls the H.264 SPS, so VideoToolbox may emit a
    // CVImageBuffer SMALLER than our fixed out_w/out_h clamp. The memcpy loops below would then read
    // far past the decoded plane (heap OOB -> crash, or adjacent-heap disclosure rendered into the
    // remote video). Drop any frame whose real Y plane is smaller than what we copy.
    const size_t real_y_w = CVPixelBufferGetWidthOfPlane(imageBuffer, 0);
    const size_t real_y_h = CVPixelBufferGetHeightOfPlane(imageBuffer, 0);
    if (real_y_w < out_w || real_y_h < out_h) {
        CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
        return;
    }

    // Same reasoning for the chroma planes: the loops below read out_w/2 x out_h/2 samples from
    // every plane after the first, whatever their real size is.
    for (size_t plane = 1; plane < plane_count; plane++) {
        if (CVPixelBufferGetWidthOfPlane(imageBuffer, plane) < (size_t)(out_w / 2) ||
            CVPixelBufferGetHeightOfPlane(imageBuffer, plane) < (size_t)(out_h / 2)) {
            CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
            return;
        }
    }

    for (uint16_t row = 0; row < out_h; row++) {
        memcpy(g_ngc_decode_output.y + row * out_w, src_y + row * src_y_stride, out_w);
    }

    if (plane_count >= 3) {
        const uint8_t *src_u = CVPixelBufferGetBaseAddressOfPlane(imageBuffer, 1);
        const uint8_t *src_v = CVPixelBufferGetBaseAddressOfPlane(imageBuffer, 2);
        const size_t src_u_stride = CVPixelBufferGetBytesPerRowOfPlane(imageBuffer, 1);
        const size_t src_v_stride = CVPixelBufferGetBytesPerRowOfPlane(imageBuffer, 2);
        const uint16_t chroma_h = out_h / 2;
        const uint16_t chroma_w = out_w / 2;

        for (uint16_t row = 0; row < chroma_h; row++) {
            memcpy(g_ngc_decode_output.u + row * chroma_w, src_u + row * src_u_stride, chroma_w);
            memcpy(g_ngc_decode_output.v + row * chroma_w, src_v + row * src_v_stride, chroma_w);
        }

        *g_ngc_decode_output.ystride = (int32_t)src_y_stride;
        *g_ngc_decode_output.ustride = (int32_t)src_u_stride;
        *g_ngc_decode_output.vstride = (int32_t)src_v_stride;
    } else {
        const uint8_t *src_uv = CVPixelBufferGetBaseAddressOfPlane(imageBuffer, 1);
        const size_t src_uv_stride = CVPixelBufferGetBytesPerRowOfPlane(imageBuffer, 1);
        const uint16_t chroma_h = out_h / 2;
        const uint16_t chroma_w = out_w / 2;

        for (uint16_t row = 0; row < chroma_h; row++) {
            for (uint16_t col = 0; col < chroma_w; col++) {
                g_ngc_decode_output.u[row * chroma_w + col] = src_uv[row * src_uv_stride + col * 2];
                g_ngc_decode_output.v[row * chroma_w + col] = src_uv[row * src_uv_stride + col * 2 + 1];
            }
        }

        *g_ngc_decode_output.ystride = (int32_t)src_y_stride;
        *g_ngc_decode_output.ustride = (int32_t)(src_uv_stride / 2);
        *g_ngc_decode_output.vstride = *g_ngc_decode_output.ustride;
    }

    CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
    g_ngc_decode_output.got_frame = true;
}

static bool ngc_create_encoder(struct ToxAV_NGC_vcoders *vc, uint32_t bitrate, uint32_t max_quantizer)
{
    if (vc->encoder) {
        VTCompressionSessionInvalidate(vc->encoder);
        CFRelease(vc->encoder);
        vc->encoder = NULL;
    }

    const OSStatus status = VTCompressionSessionCreate(NULL, NGC__VIDEO_WIDTH, NGC__VIDEO_HEIGHT,
                                                       kCMVideoCodecType_H264, NULL, NULL, NULL,
                                                       ngc_encode_callback, vc, &vc->encoder);

    if (status != noErr || !vc->encoder) {
        return false;
    }

    const float quality = (float)(51 - max_quantizer) / 31.0f;
    const float clamped_quality = quality < 0.0f ? 0.0f : (quality > 1.0f ? 1.0f : quality);

    VTSessionSetProperty(vc->encoder, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
    VTSessionSetProperty(vc->encoder, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Baseline_AutoLevel);
    VTSessionSetProperty(vc->encoder, kVTCompressionPropertyKey_AverageBitRate, (__bridge CFNumberRef)@(bitrate * 1000));
    VTSessionSetProperty(vc->encoder, kVTCompressionPropertyKey_ExpectedFrameRate, (__bridge CFNumberRef)@(10));
    VTSessionSetProperty(vc->encoder, kVTCompressionPropertyKey_MaxKeyFrameInterval, (__bridge CFNumberRef)@(15));
    VTSessionSetProperty(vc->encoder, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
    VTSessionSetProperty(vc->encoder, kVTCompressionPropertyKey_Quality, (__bridge CFNumberRef)@(clamped_quality));

    VTCompressionSessionPrepareToEncodeFrames(vc->encoder);
    vc->encoder_bitrate = bitrate;
    vc->encoder_max_quantizer = max_quantizer;
    return true;
}

static bool ngc_copy_i420_to_pixel_buffer(CVPixelBufferRef pixelBuffer, const uint8_t *y, const uint8_t *u, const uint8_t *v,
                                          uint16_t width, uint16_t height)
{
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);

    uint8_t *dst_y = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0);
    const size_t dst_y_stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0);

    for (uint16_t row = 0; row < height; row++) {
        memcpy(dst_y + row * dst_y_stride, y + row * width, width);
    }

    if (CVPixelBufferGetPlaneCount(pixelBuffer) >= 3) {
        uint8_t *dst_u = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1);
        uint8_t *dst_v = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 2);
        const size_t dst_u_stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1);
        const size_t dst_v_stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 2);
        const uint16_t chroma_h = height / 2;
        const uint16_t chroma_w = width / 2;

        for (uint16_t row = 0; row < chroma_h; row++) {
            memcpy(dst_u + row * dst_u_stride, u + row * chroma_w, chroma_w);
            memcpy(dst_v + row * dst_v_stride, v + row * chroma_w, chroma_w);
        }
    } else {
        uint8_t *dst_uv = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1);
        const size_t dst_uv_stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1);
        const uint16_t chroma_h = height / 2;
        const uint16_t chroma_w = width / 2;

        for (uint16_t row = 0; row < chroma_h; row++) {
            for (uint16_t col = 0; col < chroma_w; col++) {
                dst_uv[row * dst_uv_stride + col * 2] = u[row * chroma_w + col];
                dst_uv[row * dst_uv_stride + col * 2 + 1] = v[row * chroma_w + col];
            }
        }
    }

    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    return true;
}

static bool ngc_update_decoder_format(struct ToxAV_NGC_vcoders *vc, const uint8_t *data, uint32_t data_len)
{
    const uint8_t *sps = NULL;
    size_t sps_size = 0;
    const uint8_t *pps = NULL;
    size_t pps_size = 0;
    size_t pos = 0;

    while (pos + 4 <= data_len) {
        if (data[pos] == 0x00 && data[pos + 1] == 0x00 &&
            ((data[pos + 2] == 0x01) || (data[pos + 2] == 0x00 && data[pos + 3] == 0x01))) {
            const size_t start = pos + (data[pos + 2] == 0x01 ? 3 : 4);

            if (start >= data_len) {
                break;
            }

            const uint8_t nal_type = data[start] & 0x1F;
            size_t next = start + 1;

            while (next + 3 < data_len) {
                if (data[next] == 0x00 && data[next + 1] == 0x00 &&
                    (data[next + 2] == 0x01 || (data[next + 2] == 0x00 && data[next + 3] == 0x01))) {
                    break;
                }
                next++;
            }

            const size_t nal_size = (next > data_len ? data_len : next) - start;

            if (nal_type == 7) {
                sps = data + start;
                sps_size = nal_size;
            } else if (nal_type == 8) {
                pps = data + start;
                pps_size = nal_size;
            }

            pos = next;
        } else {
            pos++;
        }
    }

    if (!sps || !pps || sps_size == 0 || pps_size == 0) {
        return vc->decoder != NULL && vc->decoderFormat != NULL;
    }

    const uint8_t *params[2] = {sps, pps};
    const size_t sizes[2] = {sps_size, pps_size};
    CMFormatDescriptionRef format = NULL;

    if (CMVideoFormatDescriptionCreateFromH264ParameterSets(kCFAllocatorDefault, 2, params, sizes, 4, &format) != noErr) {
        return false;
    }

    if (vc->decoderFormat && CFEqual(format, vc->decoderFormat)) {
        CFRelease(format);
        return vc->decoder != NULL;
    }

    if (vc->decoder) {
        VTDecompressionSessionInvalidate(vc->decoder);
        CFRelease(vc->decoder);
        vc->decoder = NULL;
    }

    if (vc->decoderFormat) {
        CFRelease(vc->decoderFormat);
    }

    vc->decoderFormat = format;

    VTDecompressionOutputCallbackRecord cb = {0};
    cb.decompressionOutputCallback = ngc_decoder_output_callback;

    // KHANDAQ (audit #8): with NULL destination attributes VideoToolbox derives the output pixel
    // format from the attacker-controlled SPS, while ngc_decoder_output_callback() assumes a planar
    // 4:2:0 layout. Pin the output to NV12 4:2:0 (video- or full-range, we copy both the same way):
    // that is what the H.264 decoder natively emits on iOS, so honest 480x640 senders are unaffected
    // and no format conversion is forced on the session.
    NSDictionary *destination_attributes = @{
        (__bridge NSString *)kCVPixelBufferPixelFormatTypeKey: @[
            @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
            @(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
        ],
        (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{},
    };

    return VTDecompressionSessionCreate(kCFAllocatorDefault, format, NULL,
                                        (__bridge CFDictionaryRef)destination_attributes,
                                        &cb, &vc->decoder) == noErr;
}

static bool ngc_annexb_to_avcc(const uint8_t *annexb, uint32_t annexb_len, uint8_t **out_buf, uint32_t *out_len)
{
    uint8_t *buffer = malloc(annexb_len + 64);

    if (!buffer) {
        return false;
    }

    uint32_t offset = 0;
    size_t pos = 0;

    while (pos + 4 <= annexb_len) {
        if (annexb[pos] == 0x00 && annexb[pos + 1] == 0x00 &&
            ((annexb[pos + 2] == 0x01) || (annexb[pos + 2] == 0x00 && annexb[pos + 3] == 0x01))) {
            const size_t start = pos + (annexb[pos + 2] == 0x01 ? 3 : 4);

            if (start >= annexb_len) {
                break;
            }

            size_t next = start + 1;

            while (next + 3 < annexb_len) {
                if (annexb[next] == 0x00 && annexb[next + 1] == 0x00 &&
                    (annexb[next + 2] == 0x01 || (annexb[next + 2] == 0x00 && annexb[next + 3] == 0x01))) {
                    break;
                }
                next++;
            }

            const uint32_t nal_size = (uint32_t)((next > annexb_len ? annexb_len : next) - start);

            if (offset + 4 + nal_size > annexb_len + 64) {
                free(buffer);
                return false;
            }

            buffer[offset++] = (uint8_t)((nal_size >> 24) & 0xFF);
            buffer[offset++] = (uint8_t)((nal_size >> 16) & 0xFF);
            buffer[offset++] = (uint8_t)((nal_size >> 8) & 0xFF);
            buffer[offset++] = (uint8_t)(nal_size & 0xFF);
            memcpy(buffer + offset, annexb + start, nal_size);
            offset += nal_size;
            pos = next;
        } else {
            pos++;
        }
    }

    if (offset == 0) {
        free(buffer);
        return false;
    }

    *out_buf = buffer;
    *out_len = offset;
    return true;
}

void *toxav_ngc_video_init(const uint16_t v_bitrate, const uint16_t max_quantizer)
{
    struct ToxAV_NGC_vcoders *vc = calloc(1, sizeof(struct ToxAV_NGC_vcoders));

    if (!vc) {
        return NULL;
    }

    vc->encode_scratch = malloc(NGC__VIDEO_MAX_ENCODED_BYTES);
    vc->encode_sem = dispatch_semaphore_create(0);

    if (!vc->encode_scratch || !vc->encode_sem) {
        toxav_ngc_video_kill(vc);
        return NULL;
    }

    uint32_t bitrate = v_bitrate;
    uint32_t quantizer = max_quantizer;

    if (bitrate < 90 || bitrate > 2000) {
        bitrate = 200;
    }

    if (quantizer < 20 || quantizer > 51) {
        quantizer = 49;
    }

    if (!ngc_create_encoder(vc, bitrate, quantizer)) {
        toxav_ngc_video_kill(vc);
        return NULL;
    }

    return vc;
}

void toxav_ngc_video_kill(void *vngc)
{
    struct ToxAV_NGC_vcoders *vc = (struct ToxAV_NGC_vcoders *)vngc;

    if (!vc) {
        return;
    }

    if (vc->encoder) {
        VTCompressionSessionInvalidate(vc->encoder);
        CFRelease(vc->encoder);
    }

    if (vc->decoder) {
        VTDecompressionSessionInvalidate(vc->decoder);
        CFRelease(vc->decoder);
    }

    if (vc->decoderFormat) {
        CFRelease(vc->decoderFormat);
    }

    free(vc->encode_scratch);
    free(vc);
}

bool toxav_ngc_video_encode(void *vngc, const uint16_t vbitrate, const uint32_t max_quantizer,
                            const uint16_t width, const uint16_t height,
                            const uint8_t *y, const uint8_t *u, const uint8_t *v,
                            uint8_t *encoded_frame_bytes, uint32_t *encoded_frame_size_bytes)
{
    if (!vngc || !y || !u || !v || !encoded_frame_bytes || !encoded_frame_size_bytes) {
        return false;
    }

    struct ToxAV_NGC_vcoders *vc = (struct ToxAV_NGC_vcoders *)vngc;

    if (width != NGC__VIDEO_WIDTH || height != NGC__VIDEO_HEIGHT) {
        return false;
    }

    uint32_t bitrate = vbitrate;
    uint32_t quantizer = max_quantizer;

    if (bitrate < 90 || bitrate > 2000) {
        bitrate = 200;
    }

    if (quantizer < 20 || quantizer > 51) {
        quantizer = 49;
    }

    if (vc->encoder_bitrate != bitrate || vc->encoder_max_quantizer != quantizer) {
        if (!ngc_create_encoder(vc, bitrate, quantizer)) {
            return false;
        }
    }

    CVPixelBufferRef pixelBuffer = NULL;

    if (CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_420YpCbCr8Planar, NULL, &pixelBuffer) !=
        kCVReturnSuccess) {
        return false;
    }

    if (!ngc_copy_i420_to_pixel_buffer(pixelBuffer, y, u, v, width, height)) {
        CVPixelBufferRelease(pixelBuffer);
        return false;
    }

    vc->encode_scratch_size = 0;
    vc->encode_ok = false;

    const CMTime pts = CMTimeMake(1, 10);
    const OSStatus status = VTCompressionSessionEncodeFrame(vc->encoder, pixelBuffer, pts, kCMTimeInvalid, NULL, NULL, NULL);

    CVPixelBufferRelease(pixelBuffer);

    if (status != noErr) {
        return false;
    }

    VTCompressionSessionCompleteFrames(vc->encoder, kCMTimeInvalid);
    dispatch_semaphore_wait(vc->encode_sem, DISPATCH_TIME_FOREVER);

    if (!vc->encode_ok || vc->encode_scratch_size < 1) {
        return false;
    }

    memcpy(encoded_frame_bytes, vc->encode_scratch, vc->encode_scratch_size);
    *encoded_frame_size_bytes = vc->encode_scratch_size;
    return true;
}

bool toxav_ngc_video_decode(void *vngc, uint8_t *encoded_frame_bytes, uint32_t encoded_frame_size_bytes,
                            uint16_t width, uint16_t height,
                            uint8_t *y, uint8_t *u, uint8_t *v,
                            int32_t *ystride, int32_t *ustride, int32_t *vstride,
                            uint8_t flush_decoder)
{
    if (!vngc || !encoded_frame_bytes || encoded_frame_size_bytes < 1 || !y || !u || !v ||
        !ystride || !ustride || !vstride) {
        return false;
    }

    struct ToxAV_NGC_vcoders *vc = (struct ToxAV_NGC_vcoders *)vngc;

    if (flush_decoder && vc->decoder) {
        VTDecompressionSessionWaitForAsynchronousFrames(vc->decoder);
    }

    if (!ngc_update_decoder_format(vc, encoded_frame_bytes, encoded_frame_size_bytes)) {
        return false;
    }

    uint8_t *avcc = NULL;
    uint32_t avcc_len = 0;

    if (!ngc_annexb_to_avcc(encoded_frame_bytes, encoded_frame_size_bytes, &avcc, &avcc_len)) {
        return false;
    }

    CMBlockBufferRef block = NULL;

    if (CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, avcc, avcc_len, kCFAllocatorDefault, NULL, 0, avcc_len,
                                           0, &block) != kCMBlockBufferNoErr) {
        free(avcc);
        return false;
    }

    CMSampleBufferRef sample = NULL;
    const CMSampleTimingInfo timing = {CMTimeMake(1, 10), CMTimeMake(0, 1), kCMTimeInvalid};

    if (CMSampleBufferCreateReady(kCFAllocatorDefault, block, vc->decoderFormat, 1, 1, &timing, 0, NULL, &sample) != noErr) {
        CFRelease(block);
        return false;
    }

    CFRelease(block);

    g_ngc_decode_output.y = y;
    g_ngc_decode_output.u = u;
    g_ngc_decode_output.v = v;
    g_ngc_decode_output.ystride = ystride;
    g_ngc_decode_output.ustride = ustride;
    g_ngc_decode_output.vstride = vstride;
    g_ngc_decode_output.width = width > 0 ? width : NGC__VIDEO_WIDTH;
    g_ngc_decode_output.height = height > 0 ? height : NGC__VIDEO_HEIGHT;
    g_ngc_decode_output.got_frame = false;

    VTDecodeInfoFlags info = 0;
    const OSStatus decode_status = VTDecompressionSessionDecodeFrame(vc->decoder, sample, 0, NULL, &info);

    CFRelease(sample);

    if (decode_status != noErr) {
        return false;
    }

    VTDecompressionSessionWaitForAsynchronousFrames(vc->decoder);
    return g_ngc_decode_output.got_frame;
}
