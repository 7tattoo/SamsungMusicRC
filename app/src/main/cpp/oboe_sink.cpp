// Native Oboe-backed audio output for Halcyon.
//
// Media3/ExoPlayer's DefaultAudioSink writes PCM to a Java AudioTrack. To route audio through
// AAudio or OpenSL ES instead, OboeAudioSink (Kotlin) forwards decoded PCM to this native layer,
// which opens an Oboe output stream in blocking-write mode and pushes the buffers to it.
//
// Encoding ids (must match SettingsManager / OboeAudioOutput):
//   0 = PCM 16-bit, 1 = PCM 24-bit (packed), 2 = PCM 32-bit, 3 = float32
// AudioApi ids: 0 = unspecified (let Oboe choose), 1 = AAudio, 2 = OpenSL ES

#include <jni.h>
#include <oboe/Oboe.h>
#include <mutex>

namespace {

struct OboeSink {
    std::shared_ptr<oboe::AudioStream> stream;
    std::mutex mutex;
    int channelCount = 2;
    int bytesPerFrame = 4;
    int audioApi = 0; // 1=AAudio 2=OpenSL ES（暂停策略按后端区分）
};

oboe::AudioFormat toOboeFormat(int encoding) {
    switch (encoding) {
        case 0: return oboe::AudioFormat::I16;
        case 1: return oboe::AudioFormat::I24;
        case 2: return oboe::AudioFormat::I32;
        case 3: return oboe::AudioFormat::Float;
        default: return oboe::AudioFormat::I16;
    }
}

int bytesPerSample(oboe::AudioFormat format) {
    switch (format) {
        case oboe::AudioFormat::I16: return 2;
        case oboe::AudioFormat::I24: return 3;
        case oboe::AudioFormat::I32: return 4;
        case oboe::AudioFormat::Float: return 4;
        default: return 2;
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativeOpen(
        JNIEnv*, jobject, jint audioApi, jint sampleRate, jint channelCount,
        jint encoding, jboolean exclusive, jint deviceId) {
    auto* sink = new OboeSink();

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setSharingMode(exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            // LowLatency targets tiny HW buffers (~8ms); music playback needs jitter
            // tolerance instead. vivo OpenSL/AAudio HALs audibly crackle the moment
            // any DSP work (EQ) or GC pause lands on the playback thread. None mode
            // plus an explicit multi-burst buffer below is the streaming recipe.
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setFormat(toOboeFormat(encoding))
            ->setChannelCount(channelCount)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music);

    // 音乐流：不追低延迟，给足抖动余量（~200ms）。vivo 的 OpenSL/AAudio HAL
    // 默认队列只有几 ms，音频线程上任何 EQ/GC 抖动立刻欠载（电流声/刺啦）。
    {
        const int32_t estBurst = sampleRate / 100; // 按 ~10ms burst 估
        int32_t capacity = sampleRate / 5;         // 200ms
        if (estBurst > 0) capacity = std::max(capacity, estBurst * 8);
        builder.setBufferCapacityInFrames(capacity);
    }

    if (audioApi == 1) {
        builder.setAudioApi(oboe::AudioApi::AAudio);
    } else if (audioApi == 2) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
    }
    if (deviceId > 0) {
        builder.setDeviceId(deviceId);
    }

    oboe::Result result = builder.openStream(sink->stream);
    if ((result != oboe::Result::OK || !sink->stream) && exclusive) {
        // Exclusive USB DAC is requested, but not every device/firmware can open it.
        // Retry the same stream without exclusive mode so the backend can still play.
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(sink->stream);
    }
    if (result != oboe::Result::OK || !sink->stream) {
        delete sink;
        return 0;
    }
    sink->audioApi = audioApi;
    sink->channelCount = sink->stream->getChannelCount();
    sink->bytesPerFrame = sink->channelCount * bytesPerSample(sink->stream->getFormat());
    // Start immediately so ExoPlayer can pre-buffer before it calls AudioSink.play().
    // The old APK's prebuilt library has this behavior too; keep source and packaged ABI aligned.
    sink->stream->requestStart();
    return reinterpret_cast<jlong>(sink);
}

// Blocking write of a direct ByteBuffer region. Returns the number of BYTES consumed, or -1 on error.
JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativeWrite(
        JNIEnv* env, jobject, jlong handle, jobject buffer, jint offset, jint length, jlong timeoutNanos) {
    auto* sink = reinterpret_cast<OboeSink*>(handle);
    if (sink == nullptr || !sink->stream) return -1;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) return -1;

    std::lock_guard<std::mutex> lock(sink->mutex);
    const int numFrames = length / sink->bytesPerFrame;
    if (numFrames <= 0) return 0;

    auto result = sink->stream->write(base + offset, numFrames, timeoutNanos);
    if (!result) {
        // ErrorDisconnected -> -2（Kotlin 重开流后续播）；
        // 其余（ErrorTimeout / ErrorInvalidState / ErrorUnavailable 等）都是瞬态 -> 0，
        // Kotlin 侧重试并用楔死计数器兜底，绝不把一次超时当致命错误。
        return (result.error() == oboe::Result::ErrorDisconnected) ? -2 : 0;
    }
    return result.value() * sink->bytesPerFrame;
}

// Frames actually consumed by the device — used to derive the current playback position.
JNIEXPORT jlong JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativeGetFramesRead(JNIEnv*, jobject, jlong handle) {
    auto* sink = reinterpret_cast<OboeSink*>(handle);
    if (sink == nullptr || !sink->stream) return 0;
    return static_cast<jlong>(sink->stream->getFramesRead());
}

JNIEXPORT jint JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativeGetSampleRate(JNIEnv*, jobject, jlong handle) {
    auto* sink = reinterpret_cast<OboeSink*>(handle);
    if (sink == nullptr || !sink->stream) return 0;
    return sink->stream->getSampleRate();
}

JNIEXPORT void JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativePause(JNIEnv*, jobject, jlong handle) {
    auto* sink = reinterpret_cast<OboeSink*>(handle);
    if (sink != nullptr && sink->stream) {
        std::lock_guard<std::mutex> lock(sink->mutex);
        // vivo OpenSL HAL 有 requestPause 被静默忽略的问题（trace: 暂停 59.8s
        // 期间 framesRead 仍前进 1549760 帧 ≈ 32s 音频）。OpenSL 用 requestStop
        // 强停消费；AAudio 的 requestPause 工作正常（多组 delta=0 证据），保持不变。
        if (sink->audioApi == 2) {
            sink->stream->requestStop();
        } else {
            sink->stream->requestPause();
        }
    }
}

JNIEXPORT void JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativeStart(JNIEnv*, jobject, jlong handle) {
    auto* sink = reinterpret_cast<OboeSink*>(handle);
    if (sink != nullptr && sink->stream) {
        std::lock_guard<std::mutex> lock(sink->mutex);
        sink->stream->requestStart();
    }
}


JNIEXPORT void JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativeFlush(JNIEnv*, jobject, jlong handle) {
    auto* sink = reinterpret_cast<OboeSink*>(handle);
    if (sink == nullptr || !sink->stream) return;
    std::lock_guard<std::mutex> lock(sink->mutex);
    sink->stream->requestPause();
    sink->stream->requestFlush();
}

JNIEXPORT void JNICALL
Java_com_spotify_music_audio_OboeAudioOutput_nativeClose(JNIEnv*, jobject, jlong handle) {
    auto* sink = reinterpret_cast<OboeSink*>(handle);
    if (sink == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(sink->mutex);
        if (sink->stream) {
            sink->stream->requestStop();
            sink->stream->close();
            sink->stream.reset();
        }
    }
    delete sink;
}

} // extern "C"
