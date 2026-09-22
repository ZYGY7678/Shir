#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>

#include "model.hpp"
#include "dsp.hpp"
#include <Eigen/Dense>
#include <Eigen/Core>
#include <libnyquist/Common.h>
#include <libnyquist/Decoders.h>
#include <libnyquist/Encoders.h>

#define LOG_TAG "SHIR"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<demucscpp::demucs_model> g_model;
static std::mutex g_model_mutex;
static std::mutex g_run_mutex;
static volatile float g_progress = 0.0f;
static volatile int g_running = 0;
static std::string g_status = "idle";

static std::string base_name(const std::string& path) {
    size_t slash = path.find_last_of("/\\");
    std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
    size_t dot = name.find_last_of('.');
    if (dot != std::string::npos) name.resize(dot);
    return name;
}

static void set_status(const std::string& s) {
    g_status = s;
    LOGI("%s", s.c_str());
}

static bool ensure_model_loaded(const std::string& modelPath) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model) return true;
    std::unique_ptr<demucscpp::demucs_model> candidate(new demucscpp::demucs_model());
    if (!demucscpp::load_demucs_model(modelPath, candidate.get())) {
        LOGE("Model load failed: %s", modelPath.c_str());
        return false;
    }
    g_model = std::move(candidate);
    return true;
}

static Eigen::MatrixXf resample_audio(const Eigen::MatrixXf& in, int sampleRate) {
    if (sampleRate == demucscpp::SUPPORTED_SAMPLE_RATE) return in;
    const long srcN = in.cols();
    const long dstN = (long)std::llround((double)srcN * demucscpp::SUPPORTED_SAMPLE_RATE / (double)sampleRate);
    Eigen::MatrixXf out(2, dstN);
    for (long i = 0; i < dstN; ++i) {
        double src = (double)i * (double)(srcN - 1) / (double)std::max(1L, dstN - 1);
        long a = (long)std::floor(src);
        long b = std::min(srcN - 1, a + 1);
        float t = (float)(src - a);
        for (int ch = 0; ch < 2; ++ch) out(ch, i) = in(ch, a) * (1.0f - t) + in(ch, b) * t;
    }
    return out;
}

static bool write_wave(const Eigen::MatrixXf& wav, const std::string& path) {
    std::shared_ptr<nqr::AudioData> data(new nqr::AudioData());
    data->sampleRate = demucscpp::SUPPORTED_SAMPLE_RATE;
    data->channelCount = 2;
    data->samples.resize((size_t)wav.cols() * 2);
    for (long i = 0; i < wav.cols(); ++i) {
        data->samples[(size_t)i * 2] = wav(0, i);
        data->samples[(size_t)i * 2 + 1] = wav(1, i);
    }
    int status = nqr::encode_wav_to_disk({data->channelCount, nqr::PCM_S16, nqr::DITHER_TRIANGLE}, data.get(), path);
    return status == 0;
}

static Eigen::MatrixXf source_matrix(const Eigen::Tensor3dXf& out, int source, long samples) {
    Eigen::MatrixXf w(2, samples);
    for (int ch = 0; ch < 2; ++ch)
        for (long i = 0; i < samples; ++i)
            w(ch, i) = out(source, ch, i);
    return w;
}

static Eigen::MatrixXf sum_sources(const Eigen::Tensor3dXf& out, const std::vector<int>& sources, long samples) {
    Eigen::MatrixXf w = Eigen::MatrixXf::Zero(2, samples);
    for (int source : sources)
        for (int ch = 0; ch < 2; ++ch)
            for (long i = 0; i < samples; ++i)
                w(ch, i) += out(source, ch, i);
    float peak = 0.0f;
    for (long i = 0; i < samples; ++i)
        for (int ch = 0; ch < 2; ++ch) peak = std::max(peak, std::fabs(w(ch, i)));
    if (peak > 0.999f) w *= (0.999f / peak);
    return w;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shir_stems_NativeSeparator_nativeSeparate(
        JNIEnv* env, jclass, jstring jInput, jstring jModel, jstring jOutput) {
    if (g_running) return JNI_FALSE;
    std::lock_guard<std::mutex> runLock(g_run_mutex);
    g_running = 1;
    g_progress = 0.0f;
    set_status("loading");

    const char* inputC = env->GetStringUTFChars(jInput, nullptr);
    const char* modelC = env->GetStringUTFChars(jModel, nullptr);
    const char* outputC = env->GetStringUTFChars(jOutput, nullptr);
    std::string input(inputC ? inputC : "");
    std::string modelPath(modelC ? modelC : "");
    std::string outDir(outputC ? outputC : "");
    env->ReleaseStringUTFChars(jInput, inputC);
    env->ReleaseStringUTFChars(jModel, modelC);
    env->ReleaseStringUTFChars(jOutput, outputC);

    try {
        if (!ensure_model_loaded(modelPath)) {
            set_status("model_error");
            g_running = 0;
            return JNI_FALSE;
        }

        set_status("decoding");
        nqr::AudioData audioData;
        nqr::NyquistIO loader;
        loader.Load(&audioData, input);

        if (audioData.samples.empty() || audioData.channelCount < 1 || audioData.sampleRate <= 0) {
            set_status("decode_error");
            g_running = 0;
            return JNI_FALSE;
        }

        const size_t srcN = audioData.samples.size() / audioData.channelCount;
        Eigen::MatrixXf audio(2, (long)srcN);
        for (size_t i = 0; i < srcN; ++i) {
            float left = audioData.samples[i * audioData.channelCount];
            float right = audioData.channelCount > 1 ? audioData.samples[i * audioData.channelCount + 1] : left;
            audio(0, (long)i) = left;
            audio(1, (long)i) = right;
        }

        set_status("resampling");
        audio = resample_audio(audio, audioData.sampleRate);

        set_status("separating");
        demucscpp::ProgressCallback cb = [](float p, const std::string& message) {
            g_progress = std::max(0.0f, std::min(0.99f, p));
            g_status = message;
        };

        Eigen::Tensor3dXf stems = demucscpp::demucs_inference(*g_model, audio, cb);
        const long n = audio.cols();

        std::string stemNames[4] = {"drums", "bass", "other", "vocals"};
        for (int s = 0; s < 4; ++s) {
            std::string file = outDir + "/" + stemNames[s] + ".wav";
            if (!write_wave(source_matrix(stems, s, n), file)) {
                set_status("write_error");
                g_running = 0;
                return JNI_FALSE;
            }
            g_progress = 0.78f + 0.05f * s;
        }

        if (!write_wave(sum_sources(stems, {0,1,2}, n), outDir + "/instrumental.wav")) {
            set_status("write_error");
            g_running = 0;
            return JNI_FALSE;
        }
        if (!write_wave(sum_sources(stems, {0,1,2,3}, n), outDir + "/mix.wav")) {
            set_status("write_error");
            g_running = 0;
            return JNI_FALSE;
        }

        g_progress = 1.0f;
        set_status("done");
        g_running = 0;
        return JNI_TRUE;
    } catch (...) {
        set_status("native_error");
        g_running = 0;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_shir_stems_NativeSeparator_nativeProgress(JNIEnv*, jclass) {
    return g_progress;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shir_stems_NativeSeparator_nativeStatus(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_status.c_str());
}
