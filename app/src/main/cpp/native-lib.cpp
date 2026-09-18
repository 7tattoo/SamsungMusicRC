// Samsung Music 原生音频链：AAudio 直驱（独占模式优先，失败自动回退共享）。
// 架构：Kotlin(AudioSink) → 无锁 SPSC 环形缓冲 → AAudio 数据回调（内置软件 EQ DSP）→ DAC。
// 参考 google/oboe 对 AAudio 的封装思路，直接使用 NDK 自带 libaaudio，零第三方依赖。

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <cmath>
#include <algorithm>

#define LOG_TAG "SamsungAudio"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// ── 环形缓冲：65536 帧（44.1kHz 下约 1.5 秒） ──
constexpr uint64_t kRingFrames = 1ull << 16;
constexpr int kMaxChannels = 8;

float* g_ring_buf = nullptr;                   // 交错存储 [frame * ch + c]
int g_ringChannels = 2;
std::atomic<uint64_t> g_readPos{0};            // 回调已消费的帧数（= 已播放位置）
std::atomic<uint64_t> g_writePos{0};           // Kotlin 已写入的帧数

AAudioStream* g_stream = nullptr;
int32_t g_rate = 48000;
int32_t g_channels = 2;
bool g_everStarted = false;
std::atomic<bool> g_streamDead{false};
std::atomic<bool> g_exclusive{false};
std::atomic<float> g_volume{1.0f};

// ── 软件 EQ DSP（与 Kotlin 版 EqualizerProcessor 同一套算法） ──
constexpr int kBandCount = 10;
constexpr int kDelaySamples = 512; // 环绕增强延迟线（~12ms @44.1k）

struct Biquad {
    float b0 = 1.f, b1 = 0.f, b2 = 0.f, a1 = 0.f, a2 = 0.f;
    float s1[kMaxChannels] = {0};
    float s2[kMaxChannels] = {0};

    void reset() {
        for (int c = 0; c < kMaxChannels; c++) { s1[c] = 0.f; s2[c] = 0.f; }
    }
    inline float process(float x, int c) {
        const float y = b0 * x + s1[c];
        s2[c] = b2 * x - a2 * y;
        s1[c] = b1 * x - a1 * y + s2[c];
        return y;
    }
    void identity() { b0 = 1.f; b1 = 0.f; b2 = 0.f; a1 = 0.f; a2 = 0.f; reset(); }
};

Biquad g_eqBand[kBandCount];
Biquad g_eqBass, g_eqTreble;

// EQ 参数（UI 线程写，音频线程读；版本号触发重算）
std::atomic<int> g_eqVersion{0};
int g_appliedVersion = -1;
int g_appliedSr = -1;
std::atomic<bool> g_eqEnabled{false};
std::atomic<float> g_preampDb{0.f};
std::atomic<float> g_bassDb{0.f};
std::atomic<float> g_trebleDb{0.f};
std::atomic<float> g_width{0.f};
std::atomic<float> g_bandDb[kBandCount];

float g_preampGain = 1.f;
float g_eqDelay[kMaxChannels > 2 ? 2 : 2][kDelaySamples] = {{0}};
int g_delayIdx = 0;

inline float softClip(float x) {
    if (x > 0.96f) return 0.96f + 0.04f * std::tanh((x - 0.96f) * 12.f);
    if (x < -0.96f) return -0.96f + 0.04f * std::tanh((x + 0.96f) * 12.f);
    return x;
}

void peaking(Biquad& s, float f0, float fs, float gainDb) {
    const float a = std::pow(10.f, gainDb / 40.f);
    const float w0 = 2.f * (float) M_PI * f0 / fs;
    const float cw = std::cos(w0), sw = std::sin(w0);
    const float alpha = sw / (2.f * 1.1f);
    const float a0 = 1.f + alpha / a;
    s.b0 = (1.f + alpha * a) / a0;
    s.b1 = (-2.f * cw) / a0;
    s.b2 = (1.f - alpha * a) / a0;
    s.a1 = (-2.f * cw) / a0;
    s.a2 = (1.f - alpha / a) / a0;
    s.reset();
}

void lowShelf(Biquad& s, float f0, float fs, float gainDb) {
    const float a = std::pow(10.f, gainDb / 40.f);
    const float w0 = 2.f * (float) M_PI * f0 / fs;
    const float cw = std::cos(w0), sw = std::sin(w0);
    const float sqrtA = std::sqrt(a);
    const float alpha = sw / 2.f * std::sqrt(2.f);
    const float twoSqrtAAlpha = 2.f * sqrtA * alpha;
    const float a0 = (a + 1.f) + (a - 1.f) * cw + twoSqrtAAlpha;
    s.b0 = (a * ((a + 1.f) - (a - 1.f) * cw + twoSqrtAAlpha)) / a0;
    s.b1 = (2.f * a * ((a - 1.f) - (a + 1.f) * cw)) / a0;
    s.b2 = (a * ((a + 1.f) - (a - 1.f) * cw - twoSqrtAAlpha)) / a0;
    s.a1 = (-2.f * ((a - 1.f) + (a + 1.f) * cw)) / a0;
    s.a2 = ((a + 1.f) + (a - 1.f) * cw - twoSqrtAAlpha) / a0;
    s.reset();
}

void highShelf(Biquad& s, float f0, float fs, float gainDb) {
    const float a = std::pow(10.f, gainDb / 40.f);
    const float w0 = 2.f * (float) M_PI * f0 / fs;
    const float cw = std::cos(w0), sw = std::sin(w0);
    const float sqrtA = std::sqrt(a);
    const float alpha = sw / 2.f * std::sqrt(2.f);
    const float twoSqrtAAlpha = 2.f * sqrtA * alpha;
    const float a0 = (a + 1.f) - (a - 1.f) * cw + twoSqrtAAlpha;
    s.b0 = (a * ((a + 1.f) + (a - 1.f) * cw + twoSqrtAAlpha)) / a0;
    s.b1 = (-2.f * a * ((a - 1.f) + (a + 1.f) * cw)) / a0;
    s.b2 = (a * ((a + 1.f) + (a - 1.f) * cw - twoSqrtAAlpha)) / a0;
    s.a1 = (2.f * ((a - 1.f) - (a + 1.f) * cw)) / a0;
    s.a2 = ((a + 1.f) - (a - 1.f) * cw - twoSqrtAAlpha) / a0;
    s.reset();
}

bool eqBypassing() {
    if (!g_eqEnabled.load(std::memory_order_relaxed)) return true;
    if (g_preampDb.load(std::memory_order_relaxed) == 0.f &&
        g_bassDb.load(std::memory_order_relaxed) == 0.f &&
        g_trebleDb.load(std::memory_order_relaxed) == 0.f &&
        g_width.load(std::memory_order_relaxed) == 0.f) {
        for (int i = 0; i < kBandCount; i++)
            if (g_bandDb[i].load(std::memory_order_relaxed) != 0.f) return false;
        return true;
    }
    return false;
}

void recomputeEq(int sampleRate) {
    const float fs = (float) (sampleRate < 8000 ? 8000 : sampleRate);
    constexpr float kFreqs[kBandCount] = {31.f, 62.f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 16000.f};
    for (int i = 0; i < kBandCount; i++) {
        const float g = g_bandDb[i].load(std::memory_order_relaxed);
        if (g == 0.f) g_eqBand[i].identity();
        else peaking(g_eqBand[i], kFreqs[i], fs, g);
    }
    const float bass = g_bassDb.load(std::memory_order_relaxed);
    if (bass == 0.f) g_eqBass.identity(); else lowShelf(g_eqBass, 100.f, fs, bass);
    const float treble = g_trebleDb.load(std::memory_order_relaxed);
    if (treble == 0.f) g_eqTreble.identity(); else highShelf(g_eqTreble, 6000.f, fs, treble);
    g_preampGain = std::pow(10.f, g_preampDb.load(std::memory_order_relaxed) / 20.f);
    g_appliedVersion = g_eqVersion.load(std::memory_order_relaxed);
    g_appliedSr = sampleRate;
    g_delayIdx = 0;
    for (int c = 0; c < 2; c++)
        for (int i = 0; i < kDelaySamples; i++) g_eqDelay[c][i] = 0.f;
}

// 单帧 DSP：EQ 链 → 环绕增强 → 总增益 → 软限幅
inline float processFrame(float x, int c, int ch) {
    float y = g_eqBass.process(x, c);
    for (int i = 0; i < kBandCount; i++) y = g_eqBand[i].process(y, c);
    y = g_eqTreble.process(y, c);
    const float w = g_width.load(std::memory_order_relaxed);
    if (w > 0.f && ch == 2) {
        const int other = 1 - c;
        const float dSelf = g_eqDelay[c][g_delayIdx];
        const float dOther = g_eqDelay[other][g_delayIdx];
        y = y + w * (dOther - dSelf);
        g_eqDelay[c][g_delayIdx] = x;
        if (c == 1) g_delayIdx = (g_delayIdx + 1) % kDelaySamples;
    }
    return softClip(y * g_preampGain);
}

void CloseStreamInternal();

// 注意 AAudio 回调签名：userData 在 audioData 之前
aaudio_data_callback_result_t DataCallback(AAudioStream*, void*, void* data, int32_t numFrames) {
    if (g_ring_buf == nullptr) return AAUDIO_CALLBACK_RESULT_STOP;
    float* out = static_cast<float*>(data);
    uint64_t r = g_readPos.load(std::memory_order_acquire);
    const uint64_t w = g_writePos.load(std::memory_order_acquire);
    const float v = g_volume.load(std::memory_order_relaxed);
    const int ch = g_ringChannels;

    if (g_eqVersion.load(std::memory_order_relaxed) != g_appliedVersion ||
        g_appliedSr != g_rate) {
        recomputeEq(g_rate);
    }
    const bool bypass = eqBypassing();

    for (int32_t i = 0; i < numFrames; i++) {
        if (r < w) {
            float* src = &g_ring_buf[(size_t)(r % kRingFrames) * ch];
            if (bypass) {
                for (int c = 0; c < ch; c++) out[(size_t) i * ch + c] = src[c] * v;
            } else {
                for (int c = 0; c < ch; c++) out[(size_t) i * ch + c] = processFrame(src[c], c, ch) * v;
            }
            r++;
        } else {
            for (int c = 0; c < ch; c++) out[(size_t) i * ch + c] = 0.0f;
        }
    }
    g_readPos.store(r, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void ErrorCallback(AAudioStream*, void*, aaudio_result_t error) {
    ALOGE("AAudio stream error: %s", AAudio_convertResultToText(error));
    g_streamDead.store(true);
}

void ResetRing() {
    g_readPos.store(0, std::memory_order_release);
    g_writePos.store(0, std::memory_order_release);
}

void CloseStreamInternal() {
    if (g_stream != nullptr) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
    g_everStarted = false;
    ResetRing();
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeCreateStream(
        JNIEnv*, jobject, jint rate, jint channels, jboolean exclusive, jint deviceId) {
    if (channels < 1 || channels > kMaxChannels) return -3;
    if (g_stream != nullptr && g_rate == rate && g_channels == channels) return 0; // 已按此规格打开
    if (g_stream != nullptr) CloseStreamInternal();
    g_rate = rate;
    g_channels = channels;
    g_ringChannels = channels;
    if (g_ring_buf == nullptr) {
        g_ring_buf = new float[kRingFrames * kMaxChannels];
    }
    ResetRing();
    g_streamDead.store(false);
    g_appliedSr = -1; // 采样率可能变化，触发 EQ 系数重算

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return -1;
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, DataCallback, nullptr);
    AAudioStreamBuilder_setErrorCallback(builder, ErrorCallback, nullptr);
    if (deviceId > 0) {
        // 输出通道选择（与 AudioDeviceInfo.getId() 同一 id 空间）
        AAudioStreamBuilder_setDeviceId(builder, (int32_t) deviceId);
    }
    if (exclusive) {
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    }
    result = AAudioStreamBuilder_openStream(builder, &g_stream);
    if (result != AAUDIO_OK && exclusive) {
        // 独占（MMAP）不被设备/采样率支持：回退共享模式（走系统混音）
        ALOGI("exclusive open failed (%s), fallback to shared", AAudio_convertResultToText(result));
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        result = AAudioStreamBuilder_openStream(builder, &g_stream);
    }
    if (result != AAUDIO_OK && deviceId > 0) {
        // 指定设备打不开（已拔出等）：回退自动路由
        ALOGI("device %d open failed, fallback to auto", deviceId);
        AAudioStreamBuilder_setDeviceId(builder, AAUDIO_UNSPECIFIED);
        result = AAudioStreamBuilder_openStream(builder, &g_stream);
    }
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        ALOGE("open stream failed: %s", AAudio_convertResultToText(result));
        g_stream = nullptr;
        return -2;
    }
    g_exclusive.store(AAudioStream_getSharingMode(g_stream) == AAUDIO_SHARING_MODE_EXCLUSIVE);
    const int32_t burst = AAudioStream_getFramesPerBurst(g_stream);
    AAudioStream_setBufferSizeInFrames(g_stream, burst * 4);
    ALOGI("stream open: rate=%d ch=%d exclusive=%d burst=%d perf=%d",
          g_rate, g_channels, (int) g_exclusive.load(), burst,
          (int) AAudioStream_getPerformanceMode(g_stream));
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeStart(JNIEnv*, jobject) {
    if (g_stream == nullptr) return JNI_FALSE;
    // 幂等：已在启动/启动中视为成功（play/flush/play 反复调用不能把流搞哑）
    const aaudio_stream_state_t st = AAudioStream_getState(g_stream);
    if (st == AAUDIO_STREAM_STATE_STARTED || st == AAUDIO_STREAM_STATE_STARTING) {
        g_everStarted = true;
        return JNI_TRUE;
    }
    const aaudio_result_t r = AAudioStream_requestStart(g_stream);
    if (r == AAUDIO_OK) g_everStarted = true;
    else ALOGE("requestStart failed: %s (state=%s)", AAudio_convertResultToText(r),
               AAudio_convertStreamStateToText(AAudioStream_getState(g_stream)));
    return r == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativePause(JNIEnv*, jobject) {
    if (g_stream == nullptr) return JNI_FALSE;
    return AAudioStream_requestPause(g_stream) == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

// flush 语义对齐 DefaultAudioSink：清空缓冲但**保留流的播放状态**。
// 此前这里 requestStop 把流停死，之后没有任何 play() 跟进 → 回调永久不跑 → 无声。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeFlushStream(JNIEnv*, jobject) {
    ResetRing();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeCloseStream(JNIEnv*, jobject) {
    CloseStreamInternal();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeWriteFloats(
        JNIEnv* env, jobject, jfloatArray arr, jint frames) {
    if (g_ring_buf == nullptr || g_streamDead.load(std::memory_order_relaxed) || frames <= 0) return 0;
    const uint64_t w = g_writePos.load(std::memory_order_acquire);
    const uint64_t r = g_readPos.load(std::memory_order_acquire);
    const size_t freeFrames = (size_t) (kRingFrames - (w - r));
    const jint n = (jint) std::min((uint64_t) frames, freeFrames);
    if (n <= 0) return 0;
    jfloat* src = env->GetFloatArrayElements(arr, nullptr);
    const int ch = g_ringChannels;
    for (jint i = 0; i < n; i++) {
        float* dst = &g_ring_buf[(size_t) ((w + i) % kRingFrames) * ch];
        const jfloat* s = src + (size_t) i * ch;
        for (int c = 0; c < ch; c++) dst[c] = s[c];
    }
    env->ReleaseFloatArrayElements(arr, src, JNI_ABORT);
    g_writePos.store(w + n, std::memory_order_release);
    return n;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeFramesWritten(JNIEnv*, jobject) {
    // readPos = 回调已取走的帧数 = 已进入 DAC 的帧数，即播放位置
    return (jlong) g_readPos.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeQueuedFrames(JNIEnv*, jobject) {
    return (jint) (g_writePos.load(std::memory_order_acquire) -
                   g_readPos.load(std::memory_order_acquire));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeFreeFrames(JNIEnv*, jobject) {
    return (jint) (kRingFrames - (g_writePos.load(std::memory_order_acquire) -
                                  g_readPos.load(std::memory_order_acquire)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeGetXRuns(JNIEnv*, jobject) {
    if (g_stream == nullptr) return 0;
    return (jint) AAudioStream_getXRunCount(g_stream);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeIsExclusive(JNIEnv*, jobject) {
    return g_exclusive.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeStreamDead(JNIEnv*, jobject) {
    return g_streamDead.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeBufferSizeFrames(JNIEnv*, jobject) {
    if (g_stream == nullptr) return 0;
    return AAudioStream_getBufferSizeInFrames(g_stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeSetVolume(JNIEnv*, jobject, jfloat v) {
    g_volume.store(v, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_spotify_music_audio_OboeAudioSink_nativeSetEq(
        JNIEnv* env, jobject, jboolean enabled, jfloat preampDb, jfloat bassDb,
        jfloat trebleDb, jfloat width, jfloatArray bands) {
    g_eqEnabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);
    g_preampDb.store(preampDb, std::memory_order_relaxed);
    g_bassDb.store(bassDb, std::memory_order_relaxed);
    g_trebleDb.store(trebleDb, std::memory_order_relaxed);
    g_width.store(width, std::memory_order_relaxed);
    if (bands != nullptr) {
        jsize len = env->GetArrayLength(bands);
        jfloat* p = env->GetFloatArrayElements(bands, nullptr);
        for (int i = 0; i < kBandCount && i < len; i++) {
            g_bandDb[i].store(p[i], std::memory_order_relaxed);
        }
        env->ReleaseFloatArrayElements(bands, p, JNI_ABORT);
    }
    g_eqVersion.fetch_add(1, std::memory_order_release);
}
