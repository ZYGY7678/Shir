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
#include <atomic>
#include <cstdint>
#include <new>
#include <cctype>

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
static std::atomic<float> g_progress(0.0f);
static std::atomic<int> g_running(0);
static std::mutex g_status_mutex;
static std::string g_status = "idle";

static bool supported_extension(const std::string& path) {
    size_t slash = path.find_last_of("/\\");
    std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
    size_t dot = name.find_last_of('.');
    if (dot == std::string::npos || dot + 1 >= name.size()) return false;
    std::string ext = name.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c){ return (char)std::tolower(c); });
    return ext=="mp3" || ext=="wav" || ext=="wave" || ext=="ogg" || ext=="opus" ||
           ext=="flac" || ext=="wv" || ext=="mpc" || ext=="mpp";
}

static std::string base_name(const std::string& path) {
    size_t slash = path.find_last_of("/\\");
    std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
    size_t dot = name.find_last_of('.');
    if (dot != std::string::npos) name.resize(dot);
    return name;
}

static void set_status(const std::string& s) {
    { std::lock_guard<std::mutex> lock(g_status_mutex); g_status = s; }
    LOGI("%s", s.c_str());
}

static bool ensure_model_loaded(const std::string& modelPath) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model) return true;
    set_status("טוען את מודל ההפרדה…");
    g_progress.store(0.01f);
    std::unique_ptr<demucscpp::demucs_model> candidate(new demucscpp::demucs_model());
    LOGI("Starting model load");
    if (!demucscpp::load_demucs_model(modelPath, candidate.get())) {
        LOGE("Model load failed: %s", modelPath.c_str());
        return false;
    }
    g_model = std::move(candidate);
    g_progress.store(0.05f);
    set_status("מודל נטען — מתחיל לקרוא את השיר…");
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
    int status = nqr::encode_wav_to_disk({data->channelCount, nqr::PCM_16, nqr::DITHER_TRIANGLE}, data.get(), path);
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

static void write_u16(std::ofstream& f, uint16_t v) {
    char b[2] = {(char)(v & 255), (char)((v >> 8) & 255)};
    f.write(b, 2);
}
static void write_u32(std::ofstream& f, uint32_t v) {
    char b[4] = {(char)(v & 255), (char)((v >> 8) & 255), (char)((v >> 16) & 255), (char)((v >> 24) & 255)};
    f.write(b, 4);
}
static void write_wav_header(std::ofstream& f, uint32_t frames, uint32_t sampleRate) {
    f.write("RIFF",4); write_u32(f,36u + frames * 4u); f.write("WAVE",4);
    f.write("fmt ",4); write_u32(f,16); write_u16(f,1); write_u16(f,2);
    write_u32(f,sampleRate); write_u32(f,sampleRate*4u); write_u16(f,4); write_u16(f,16);
    f.write("data",4); write_u32(f,frames*4u);
}
static int16_t pcm16(float v) {
    if (!std::isfinite(v)) v = 0.0f;
    v = std::max(-0.999969f, std::min(0.999969f, v));
    return (int16_t)std::lrintf(v * 32767.0f);
}
static bool append_wav_frames(std::ofstream& f, const Eigen::MatrixXf& w, long from, long count) {
    if (!f.good()) return false;
    for (long i=0;i<count;++i) {
        write_u16(f,(uint16_t)pcm16(w(0,from+i)));
        write_u16(f,(uint16_t)pcm16(w(1,from+i)));
    }
    return f.good();
}

static bool separate_chunked(const std::string& input, const std::string& outDir,
                             const nqr::AudioData& audioData, demucscpp::demucs_model& model) {
    const size_t srcN = audioData.samples.size() / audioData.channelCount;
    Eigen::MatrixXf audio(2, (long)srcN);
    for (size_t i=0;i<srcN;++i) {
        float l=audioData.samples[i*audioData.channelCount];
        float r=audioData.channelCount>1 ? audioData.samples[i*audioData.channelCount+1] : l;
        audio(0,(long)i)=l; audio(1,(long)i)=r;
    }
    audio = resample_audio(audio, audioData.sampleRate);
    const long total = audio.cols();
    if (total <= 0) return false;
    const long maxFrames = (long)(12.0 * 60.0 * demucscpp::SUPPORTED_SAMPLE_RATE);
    if (total > maxFrames) {
        set_status("input_too_long");
        return false;
    }

    const long chunk = (long)(20.0 * demucscpp::SUPPORTED_SAMPLE_RATE);
    const long overlap = (long)(1.0 * demucscpp::SUPPORTED_SAMPLE_RATE);
    const long stride = chunk - overlap;
    const uint32_t outFrames = (uint32_t)total;

    std::ofstream files[6];
    const char* names[6]={"drums.wav","bass.wav","other.wav","vocals.wav","instrumental.wav","mix.wav"};
    for(int i=0;i<6;++i) {
        files[i].open(outDir+"/"+names[i],std::ios::binary|std::ios::trunc);
        if(!files[i].is_open()) return false;
        write_wav_header(files[i],outFrames,(uint32_t)demucscpp::SUPPORTED_SAMPLE_RATE);
    }

    std::vector<Eigen::MatrixXf> pending(4);
    long pendingLen=0;
    long offset=0;
    int chunkIndex=0;
    const int totalChunks=(int)((total+stride-1)/stride);

    while(offset<total) {
        long len=std::min(chunk,total-offset);
        Eigen::MatrixXf part=audio.block(0,offset,2,len);
        demucscpp::ProgressCallback cb=[&](float p,const std::string& msg) {
            float global=((float)chunkIndex + std::max(0.0f,std::min(1.0f,p)))/(float)totalChunks;
            g_progress.store(std::min(0.98f,0.10f + global*0.88f));
            std::lock_guard<std::mutex> lock(g_status_mutex);
            g_status="הפרדה: "+std::to_string((int)((0.10f + global*0.88f)*100.0f))+"%";
            LOGI("%s",msg.c_str());
        };
        Eigen::Tensor3dXf out=demucscpp::demucs_inference(model,part,cb);

        std::vector<Eigen::MatrixXf> current(4);
        for(int s=0;s<4;++s) current[s]=source_matrix(out,s,len);

        long blend=std::min(overlap,std::min(pendingLen,len));
        bool hadPending = pendingLen > 0;
        if(pendingLen>0) {
            Eigen::MatrixXf merged(2,blend);
            for(int s=0;s<4;++s) {
                for(long i=0;i<blend;++i) {
                    float a=(float)(blend-i)/(float)(blend+1);
                    current[s](0,i)=pending[s](0,pendingLen-blend+i)*a + current[s](0,i)*(1.0f-a);
                    current[s](1,i)=pending[s](1,pendingLen-blend+i)*a + current[s](1,i)*(1.0f-a);
                }
            }
            for(int s=0;s<4;++s) {
                Eigen::MatrixXf full(2,blend);
                full=current[s].leftCols(blend);
                append_wav_frames(files[s],full,0,blend);
            }
            pendingLen=0;
        }

        long keep = (offset+len<total) ? std::min(overlap,len) : 0;
        long flush = len-keep;
        long flushFrom = hadPending ? blend : 0;
        for(int s=0;s<4;++s) append_wav_frames(files[s],current[s],flushFrom,flush);
        if(keep>0) {
            pendingLen=keep;
            for(int s=0;s<4;++s) pending[s]=current[s].rightCols(keep);
        } else {
            pendingLen=0;
        }
        ++chunkIndex;
        offset += stride;
    }

    if(pendingLen>0) {
        for(int s=0;s<4;++s) append_wav_frames(files[s],pending[s],0,pendingLen);
    }

    files[4].flush(); files[5].flush();
    for(int i=0;i<4;++i) {
        files[i].close();
    }
    for(int i=0;i<4;++i) {
        std::ifstream in(outDir+"/"+names[i],std::ios::binary|std::ios::ate);
        if(!in.good()) return false;
        in.close();
    }

    // Build instrumental and mix from the four already-created PCM WAVs.
    // This pass is streaming, so it does not allocate the whole song.
    std::ifstream ins[4];
    for(int i=0;i<4;++i) { ins[i].open(outDir+"/"+names[i],std::ios::binary); if(!ins[i]) return false; ins[i].seekg(44); }
    const long blockFrames=8192;
    std::vector<int16_t> b[4];
    for(int i=0;i<4;++i) b[i].resize(blockFrames*2);
    while(true) {
        std::streamsize got=ins[0].read((char*)b[0].data(),b[0].size()*sizeof(int16_t)).gcount();
        if(got<=0) break;
        long samples=(long)got/(long)sizeof(int16_t);
        for(int i=1;i<4;++i) ins[i].read((char*)b[i].data(),samples*sizeof(int16_t));
        for(long k=0;k<samples;k+=2) {
            float instL=((int)b[0][k]+(int)b[1][k]+(int)b[2][k])/32767.0f;
            float instR=((int)b[0][k+1]+(int)b[1][k+1]+(int)b[2][k+1])/32767.0f;
            float mixL=instL+b[3][k]/32767.0f;
            float mixR=instR+b[3][k+1]/32767.0f;
            write_u16(files[4],(uint16_t)pcm16(instL));
            write_u16(files[4],(uint16_t)pcm16(instR));
            write_u16(files[5],(uint16_t)pcm16(mixL));
            write_u16(files[5],(uint16_t)pcm16(mixR));
        }
    }
    for(int i=0;i<4;++i) ins[i].close();
    files[4].close(); files[5].close();
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shir_stems_NativeSeparator_nativeSeparate(
        JNIEnv* env, jclass, jstring jInput, jstring jModel, jstring jOutput) {
    if (g_running.load() != 0) return JNI_FALSE;
    std::lock_guard<std::mutex> runLock(g_run_mutex);
    if (g_running.exchange(1) != 0) return JNI_FALSE;
    g_progress.store(0.0f);
    set_status("loading");

    const char* inputC=env->GetStringUTFChars(jInput,nullptr);
    const char* modelC=env->GetStringUTFChars(jModel,nullptr);
    const char* outputC=env->GetStringUTFChars(jOutput,nullptr);
    std::string input(inputC?inputC:""), modelPath(modelC?modelC:""), outDir(outputC?outputC:"");
    env->ReleaseStringUTFChars(jInput,inputC);
    env->ReleaseStringUTFChars(jModel,modelC);
    env->ReleaseStringUTFChars(jOutput,outputC);

    try {
        if(input.empty() || modelPath.empty() || outDir.empty()) { set_status("קלט לא תקין"); g_running=0; return JNI_FALSE; }
        if(!supported_extension(input)) { set_status("unsupported_format"); g_running=0; return JNI_FALSE; }
        if(!ensure_model_loaded(modelPath)) { set_status("model_error"); g_running=0; return JNI_FALSE; }

        g_progress.store(std::max(g_progress.load(), 0.06f));
        set_status("קורא את קובץ השיר…");
        nqr::AudioData audioData;
        nqr::NyquistIO loader;
        try {
            loader.Load(&audioData,input);
        } catch (const std::bad_alloc&) {
        LOGE("out of memory");
        set_status("out_of_memory");
        g_running=0;
        return JNI_FALSE;
    } catch (const std::exception& e) {
            LOGE("decoder exception: %s",e.what());
            set_status("decode_error");
            g_running=0;
            return JNI_FALSE;
        }
        if(audioData.samples.empty() || audioData.channelCount<1 || audioData.sampleRate<=0) {
            set_status("decode_error"); g_running=0; return JNI_FALSE;
        }

        g_progress.store(0.10f);
        set_status("מתחיל הפרדת ערוצים…");
        bool ok=false;
        try {
            ok=separate_chunked(input,outDir,audioData,*g_model);
        } catch (const std::bad_alloc&) {
            cleanup_outputs(outDir);
            set_status("out_of_memory");
            g_running=0;
            return JNI_FALSE;
        }
        if(!ok) {
            cleanup_outputs(outDir);
            std::string st;
            { std::lock_guard<std::mutex> lock(g_status_mutex); st=g_status; }
            if(st!="input_too_long" && st!="decode_error" && st!="unsupported_format" && st!="out_of_memory") set_status("write_error");
            g_running=0;
            return JNI_FALSE;
        }
        g_progress.store(1.0f);
        set_status("done");
        g_running=0;
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        LOGE("out of memory");
        set_status("out_of_memory");
        cleanup_outputs(outDir);
        g_running=0;
        return JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("native exception: %s",e.what());
        set_status(std::string("מנוע: ")+e.what());
        g_running=0;
        return JNI_FALSE;
    } catch (...) {
        LOGE("native unknown exception");
        set_status("מנוע: חריגה לא ידועה");
        g_running=0;
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_shir_stems_NativeSeparator_nativeProgress(JNIEnv*, jclass) {
    return g_progress.load();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shir_stems_NativeSeparator_nativeStatus(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_status_mutex);
    return env->NewStringUTF(g_status.c_str());
}
