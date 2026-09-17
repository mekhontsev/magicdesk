#include <jni.h>
#include <android/native_window_jni.h>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include "embedded.h"
#include "android_keycodes.h"

#define JNI(name) Java_io_github_mekhontsev_magicdesk_x11_##name

namespace {
struct Connection {
    JNIEnv* env;
    jobject owner;
    jmethodID frame, disconnected, window, windows, data;
    LorieConnection* native;
};

void throwState(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}

const LorieCallbacks callbacks = {
    .frame = [](void* ptr, uint32_t output, uint32_t window, int width, int height, bool available) {
        auto* c = (Connection*)ptr;
        c->env->CallVoidMethod(c->owner, c->frame, (jint)output, (jint)window, width, height, available ? 1 : 0);
    },
    .disconnected = [](void* ptr) {
        auto* c = (Connection*)ptr;
        c->env->CallVoidMethod(c->owner, c->disconnected);
    },
    .window = [](void* ptr, uint32_t id, const char* name, const uint32_t* pixels, bool removed, bool mapped) {
        auto* c = (Connection*)ptr;
        JNIEnv* env = c->env;
        if (env->ExceptionCheck()) return;
        jintArray icon = nullptr;
        if (pixels) {
            icon = env->NewIntArray(64 * 64);
            if (!icon) return;
            env->SetIntArrayRegion(icon, 0, 64 * 64, (const jint*)pixels);
        }
        jsize size = (jsize)strlen(name);
        jbyteArray title = env->NewByteArray(size);
        if (title) {
            env->SetByteArrayRegion(title, 0, size, (const jbyte*)name);
            env->CallVoidMethod(c->owner, c->window, (jint)id, title, icon, (jboolean)removed, (jboolean)mapped);
            env->DeleteLocalRef(title);
        }
        if (icon) env->DeleteLocalRef(icon);
    },
    .windowsCommitted = [](void* ptr) {
        auto* c = (Connection*)ptr;
        c->env->CallVoidMethod(c->owner, c->windows);
    },
    .data = [](void* ptr, int operation, int channel, uint32_t serial, uint32_t offer,
            uint32_t output, uint32_t window, int x, int y, const char* type, int descriptor) {
        auto* c = (Connection*)ptr;
        if (c->env->ExceptionCheck()) { if (descriptor >= 0) close(descriptor); return; }
        jstring mime = c->env->NewStringUTF(type);
        if (!mime) { if (descriptor >= 0) close(descriptor); return; }
        c->env->CallVoidMethod(c->owner, c->data, operation, channel, (jint)serial, (jint)offer,
                (jint)output, (jint)window, x, y, mime, descriptor);
        c->env->DeleteLocalRef(mime);
    }
};

JavaVM* serverVm;
jobject server;
jmethodID readyMethod;
void serverReady(void*, const char* display) {
    JNIEnv* env = nullptr;
    if (serverVm->AttachCurrentThread(&env, nullptr) != JNI_OK) _exit(1);
    jstring value = env->NewStringUTF(display);
    if (value) {
        env->CallVoidMethod(server, readyMethod, value);
        env->DeleteLocalRef(value);
    }
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); _exit(1); }
    serverVm->DetachCurrentThread();
}
}

extern "C" JNIEXPORT jlong JNICALL JNI(X11Session_nativeCreate)(JNIEnv* env, jobject owner) {
    auto* c = (Connection*)calloc(1, sizeof(Connection));
    if (!c) return 0;
    c->env = env;
    c->owner = env->NewGlobalRef(owner);
    jclass cls = env->GetObjectClass(owner);
    c->frame = env->GetMethodID(cls, "onNativeFrame", "(IIIII)V");
    c->disconnected = env->GetMethodID(cls, "onNativeDisconnected", "()V");
    c->window = env->GetMethodID(cls, "onNativeWindow", "(I[B[IZZ)V");
    c->windows = env->GetMethodID(cls, "onNativeWindowsCommitted", "()V");
    c->data = env->GetMethodID(cls, "onNativeData", "(IIIIIIIILjava/lang/String;I)V");
    env->DeleteLocalRef(cls);
    if (!env->ExceptionCheck() && c->owner) c->native = lorieConnectionCreate(&callbacks, c);
    if (c->native) return (jlong)c;
    if (c->owner) env->DeleteGlobalRef(c->owner);
    free(c);
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL JNI(X11Session_nativeConnect)(JNIEnv*, jclass, jlong ptr, jint fd) {
    return lorieConnectionConnect(((Connection*)ptr)->native, fd);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeSurface)(JNIEnv* env, jclass, jlong ptr,
        jint output, jobject surface, jboolean release) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (surface && !window) { throwState(env, "Invalid X11 Surface"); return; }
    bool ok = lorieConnectionSurface(((Connection*)ptr)->native, output, window, release);
    if (window) ANativeWindow_release(window);
    if (!ok) throwState(env, "Cannot configure X11 output surface");
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeCommand)(JNIEnv*, jclass, jlong ptr,
        jint output, jint window, jint operation, jint x, jint y, jint detail, jboolean down) {
    if (operation == LORIE_OUTPUT_KEY && detail == 0 && x >= 0 &&
            (size_t)x < sizeof(android_to_linux_keycode) / sizeof(android_to_linux_keycode[0]))
        detail = android_to_linux_keycode[x] ? android_to_linux_keycode[x] + 8 : 0;
    lorieConnectionCommand(((Connection*)ptr)->native, output, window, operation, x, y, detail, down);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeText)(JNIEnv* env, jclass, jlong ptr,
        jint output, jint window, jstring text) {
    const jchar* chars = env->GetStringChars(text, nullptr);
    if (!chars) return;
    jsize length = env->GetStringLength(text);
    for (jsize i = 0; i < length; ++i) {
        uint32_t point = chars[i];
        if (point >= 0xd800 && point <= 0xdbff && i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
            point = 0x10000 + ((point - 0xd800) << 10) + (chars[++i] - 0xdc00);
        lorieConnectionCommand(((Connection*)ptr)->native, output, window, LORIE_OUTPUT_TEXT, point, 0, 0, false);
    }
    env->ReleaseStringChars(text, chars);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeData)(JNIEnv* env, jclass, jlong ptr,
        jint operation, jint channel, jint serial, jint offer, jint output, jint window, jint x, jint y,
        jstring mime, jint descriptor) {
    const char* type = mime ? env->GetStringUTFChars(mime, nullptr) : nullptr;
    if (mime && !type) return;
    lorieConnectionData(((Connection*)ptr)->native, operation, channel, serial, offer, output, window, x, y, type, descriptor);
    if (type) env->ReleaseStringUTFChars(mime, type);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeDestroy)(JNIEnv* env, jclass, jlong ptr) {
    auto* c = (Connection*)ptr;
    lorieConnectionDestroy(c->native);
    env->DeleteGlobalRef(c->owner);
    free(c);
}

extern "C" JNIEXPORT jint JNICALL JNI(X11DataExchange_nativeBytes)(JNIEnv* env, jclass, jbyteArray bytes) {
    jsize length = env->GetArrayLength(bytes);
    if (length > 1024 * 1024) return -1;
    int fd = (int)syscall(__NR_memfd_create, "x11-content", MFD_CLOEXEC);
    if (fd < 0) return -1;
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (!data) { close(fd); return -1; }
    bool ok = !length || pwrite(fd, data, length, 0) == length;
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    if (!ok) { close(fd); return -1; }
    return fd;
}

extern "C" JNIEXPORT jboolean JNICALL JNI(X11Server_nativeStart)(JNIEnv* env, jobject owner, jobjectArray args) {
    if (server) return false;
    int count = env->GetArrayLength(args);
    auto** arguments = (char**)calloc(count + 1, sizeof(char*));
    if (!arguments) return false;
    bool ok = true;
    for (int i = 0; i < count && ok; ++i) {
        auto value = (jstring)env->GetObjectArrayElement(args, i);
        const char* text = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
        arguments[i] = text ? strdup(text) : nullptr;
        if (text) env->ReleaseStringUTFChars(value, text);
        if (value) env->DeleteLocalRef(value);
        ok = arguments[i] != nullptr;
    }
    if (ok) {
        env->GetJavaVM(&serverVm);
        server = env->NewGlobalRef(owner);
        jclass cls = env->GetObjectClass(owner);
        readyMethod = env->GetMethodID(cls, "onNativeReady", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        ok = server && readyMethod && lorieServerStart(count, arguments, serverReady, nullptr);
        if (!ok && server) { env->DeleteGlobalRef(server); server = nullptr; }
    }
    for (int i = 0; i < count; ++i) free(arguments[i]);
    free(arguments);
    return ok;
}

extern "C" JNIEXPORT jint JNICALL JNI(X11Server_nativeConnect)(JNIEnv*, jclass) { return lorieServerConnect(); }
extern "C" JNIEXPORT void JNICALL JNI(X11Server_nativeStop)(JNIEnv*, jclass) { lorieServerStop(); }
