#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// --- InputStream loader ---

struct input_stream_context {
    JNIEnv *env;
    jobject input_stream;
    jmethodID mid_available;
    jmethodID mid_read;
};

static size_t inputStreamRead(void *ctx, void *output, size_t read_size) {
    struct input_stream_context *is = (struct input_stream_context *)ctx;
    jint avail = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint to_copy = read_size < (size_t)avail ? (jint)read_size : avail;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, to_copy);
    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, to_copy);

    jbyte *elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, elements, n_read > 0 ? n_read : 0);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, elements, JNI_ABORT);
    (*is->env)->DeleteLocalRef(is->env, byte_array);

    return n_read > 0 ? (size_t)n_read : 0;
}

static bool inputStreamEof(void *ctx) {
    struct input_stream_context *is = (struct input_stream_context *)ctx;
    return (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available) <= 0;
}

static void inputStreamClose(void *ctx) {
    UNUSED(ctx);
}

// --- Asset loader ---

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *)ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *)ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *)ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env, jobject assetManager, const char *asset_path) {
    LOGI("Loading model from asset '%s'", asset_path);
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(mgr, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open asset '%s'", asset_path);
        return NULL;
    }

    struct whisper_model_loader loader = {
        .context = asset,
        .read    = &asset_read,
        .eof     = &asset_is_eof,
        .close   = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

// --- JNI exports ---

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct input_stream_context inp_ctx = {
        .env = env,
        .input_stream = input_stream,
    };

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    struct whisper_model_loader loader = {
        .context = &inp_ctx,
        .read    = inputStreamRead,
        .eof     = inputStreamEof,
        .close   = inputStreamClose
    };

    struct whisper_context *context = whisper_init_with_params(&loader, whisper_context_default_params());
    return (jlong)context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    const char *path = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    struct whisper_context *context = whisper_init_from_asset(env, assetManager, path);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, path);
    return (jlong)context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *context = whisper_init_from_file_with_params(path, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, path);
    return (jlong)context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_free((struct whisper_context *)context_ptr);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *)context_ptr;
    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_len = (*env)->GetArrayLength(env, audio_data);

    const char *lang = (*env)->GetStringUTFChars(env, language_str, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.language         = lang;
    params.n_threads        = num_threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;

    whisper_reset_timings(context);

    LOGI("Running whisper_full (lang=%s, threads=%d, samples=%d)", lang, num_threads, audio_len);
    if (whisper_full(context, params, audio, audio_len) != 0) {
        LOGW("whisper_full failed");
    } else {
        whisper_print_timings(context);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language_str, lang);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *)context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text((struct whisper_context *)context_ptr, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
