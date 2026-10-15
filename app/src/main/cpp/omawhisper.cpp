// JNI for dev.omalauncher.watch.Whisper: one model, beam search for accuracy, text out.
#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_dev_omalauncher_watch_Whisper_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *model = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model, params);
    env->ReleaseStringUTFChars(path, model);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_omalauncher_watch_Whisper_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jint threads, jstring hint) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;
    // Accuracy over speed: beam search with the reference settings, temperature fallback on.
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
    params.greedy.best_of = 5;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.no_context = true;
    params.suppress_blank = true;
    params.n_threads = threads;
    // Words the owner says that a general model misspells.
    const char *prompt = env->GetStringUTFChars(hint, nullptr);
    params.initial_prompt = prompt;
    jsize count = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    int status = whisper_full(ctx, params, samples, count);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(hint, prompt);
    if (status != 0) return nullptr;
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) text += whisper_full_get_segment_text(ctx, i);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_omalauncher_watch_Whisper_nativeFree(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}
