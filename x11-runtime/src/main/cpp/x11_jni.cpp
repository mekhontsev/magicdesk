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
    JavaVM* vm;
    jobject owner;
    jmethodID frame, disconnected, window, windowRemoved, windows, data, cursor;
    jmethodID shell, shellState, presented, family;
    jclass managementClass;
    jmethodID managementConstructor;
    jclass inspectionNodeClass;
    jmethodID inspectionNodeConstructor, inspectionNode, inspectionDone;
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
    .window = [](void* ptr, uint32_t id, const LorieWindowInfo* info) {
        auto* c = (Connection*)ptr;
        JNIEnv* env = c->env;
        if (env->ExceptionCheck()) return;
        if (!info) {
            env->CallVoidMethod(c->owner, c->windowRemoved, (jint)id);
            return;
        }
        jintArray icon = nullptr;
        if (info->icon) {
            icon = env->NewIntArray(64 * 64);
            if (!icon) return;
            env->SetIntArrayRegion(icon, 0, 64 * 64, (const jint*)info->icon);
        }
        auto bytes = [env](const char* value) -> jbyteArray {
            if (env->ExceptionCheck()) return nullptr;
            jsize size = (jsize)strlen(value);
            jbyteArray result = env->NewByteArray(size);
            if (result) env->SetByteArrayRegion(result, 0, size, (const jbyte*)value);
            return result;
        };
        jbyteArray title = bytes(info->title);
        jbyteArray instance = bytes(info->instance);
        jbyteArray className = bytes(info->className);
        if (title && instance && className && !env->ExceptionCheck()) {
            const auto& state = info->management;
            jobject management = env->NewObject(c->managementClass, c->managementConstructor,
                    (jboolean)state.managed, (jint)state.request.serial,
                    (jboolean)state.request.fullscreen, (jboolean)state.actual.fullscreen);
            if (management) {
                env->CallVoidMethod(c->owner, c->window, (jint)id, title, icon, (jboolean)info->mapped, (jint)info->role, management, instance, className);
            }
            if (management) {
                env->DeleteLocalRef(management);
            }
        }
        if (title) env->DeleteLocalRef(title);
        if (icon) env->DeleteLocalRef(icon);
        if (instance) env->DeleteLocalRef(instance);
        if (className) env->DeleteLocalRef(className);
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
    },
    .inspectionNode = [](void* ptr, uint32_t serial, const LorieInspectionNode* node) {
        auto* c = (Connection*)ptr;
        JNIEnv* env = c->env;
        if (env->ExceptionCheck()) return;
        jsize length = (jsize)strnlen(node->title, sizeof(node->title));
        jbyteArray title = env->NewByteArray(length);
        if (!title) return;
        env->SetByteArrayRegion(title, 0, length, (const jbyte*)node->title);
        jobject value = env->NewObject(c->inspectionNodeClass, c->inspectionNodeConstructor,
                (jint)node->id, (jint)node->parent, (jint)node->transientFor, (jint)node->leader,
                title, node->x, node->y, node->width, node->height, (jint)node->flags, (jint)node->type);
        if (value) {
            env->CallVoidMethod(c->owner, c->inspectionNode, (jint)serial, value);
            env->DeleteLocalRef(value);
        }
        env->DeleteLocalRef(title);
    },
    .inspectionDone = [](void* ptr, uint32_t serial, const LorieInspectionResult* result) {
        auto* c = (Connection*)ptr;
        if (c->env->ExceptionCheck()) return;
        c->env->CallVoidMethod(c->owner, c->inspectionDone, (jint)serial, (jint)result->window,
                (jint)result->focus, (jint)result->focusKind, result->screenWidth, result->screenHeight,
                (jint)result->count, (jboolean)result->found, (jboolean)result->truncated);
    },
    .cursor = [](void* ptr, uint32_t output, uint32_t window, const LorieCursorInfo* info, const uint32_t* pixels) {
        auto* c = (Connection*)ptr;
        JNIEnv* env = c->env;
        if (env->ExceptionCheck()) return;
        jintArray image = nullptr;
        jsize count = (jsize)lorieCursorPixelCount(info);
        if (count) {
            image = env->NewIntArray(count);
            if (!image) return;
            env->SetIntArrayRegion(image, 0, count, (const jint*)pixels);
        }
        if (!env->ExceptionCheck()) env->CallVoidMethod(c->owner, c->cursor, (jint)output, (jint)window,
                (jint)info->kind, (jint)info->width, (jint)info->height,
                (jint)info->hotspotX, (jint)info->hotspotY, image);
        if (image) env->DeleteLocalRef(image);
    },
    .shell = [](void* ptr, uint32_t owner, uint32_t window, const LorieShellInfo* info) {
        auto* c = (Connection*)ptr;
        JNIEnv* env = c->env;
        jintArray values = nullptr;
        if (info) {
            jint fields[23 + LORIE_SHELL_INPUT_LIMIT * 4] = {(jint)info->role, (jint)info->mapped,
                (jint)info->inputComplete, info->x, info->y, info->width, info->height,
                info->paint.left, info->paint.top, info->paint.right, info->paint.bottom};
            for (unsigned i = 0; i < 12; i++) fields[11+i] = (jint)info->strut[i];
            for (unsigned i = 0; i < info->inputCount; i++) {
                fields[23+4*i] = info->input[i].left; fields[24+4*i] = info->input[i].top;
                fields[25+4*i] = info->input[i].right; fields[26+4*i] = info->input[i].bottom;
            }
            int count = 23 + 4 * info->inputCount;
            values = env->NewIntArray(count);
            if (!values) return;
            env->SetIntArrayRegion(values, 0, count, fields);
        }
        if (!env->ExceptionCheck()) env->CallVoidMethod(c->owner, c->shell, (jint)owner, (jint)window, values);
        if (values) env->DeleteLocalRef(values);
    },
    .shellState = [](void* ptr, uint32_t owner, bool available) {
        auto* c = (Connection*)ptr;
        c->env->CallVoidMethod(c->owner, c->shellState, (jint)owner, (jboolean)available);
    },
    .family = [](void* ptr, uint32_t output, const LorieFamilyGeometry* info) {
        auto* c = (Connection*)ptr;
        JNIEnv* env = c->env;
        jint fields[7 + LORIE_SHELL_INPUT_LIMIT * 4] = {info->width, info->height,
            (jint)info->inputComplete, info->paint.left, info->paint.top, info->paint.right, info->paint.bottom};
        for (unsigned i = 0; i < info->inputCount; i++) {
            fields[7+4*i] = info->input[i].left; fields[8+4*i] = info->input[i].top;
            fields[9+4*i] = info->input[i].right; fields[10+4*i] = info->input[i].bottom;
        }
        int count = 7 + 4 * info->inputCount;
        jintArray values = env->NewIntArray(count);
        if (!values) return;
        env->SetIntArrayRegion(values, 0, count, fields);
        if (!env->ExceptionCheck()) env->CallVoidMethod(c->owner, c->family, (jint)output, values);
        env->DeleteLocalRef(values);
    },
    .presented = [](void* ptr, uint32_t output, uint32_t serial, bool success) {
        auto* c = (Connection*)ptr;
        JNIEnv* env = nullptr;
        bool attach = c->vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK;
        if (attach && c->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        env->CallVoidMethod(c->owner, c->presented, (jint)output, (jint)serial, (jboolean)success);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        if (attach) c->vm->DetachCurrentThread();
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
    env->GetJavaVM(&c->vm);
    c->shell = env->GetMethodID(cls, "onNativeShell", "(II[I)V");
    c->shellState = env->GetMethodID(cls, "onNativeShellState", "(IZ)V");
    c->family = env->GetMethodID(cls, "onNativeFamily", "(I[I)V");
    c->presented = env->GetMethodID(cls, "onNativePresented", "(IIZ)V");
    c->disconnected = env->GetMethodID(cls, "onNativeDisconnected", "()V");
    c->window = env->GetMethodID(cls, "onNativeWindow", "(I[B[IZILio/github/mekhontsev/magicdesk/x11/X11WindowManagement;[B[B)V");
    c->windowRemoved = env->GetMethodID(cls, "onNativeWindowRemoved", "(I)V");
    c->windows = env->GetMethodID(cls, "onNativeWindowsCommitted", "()V");
    c->data = env->GetMethodID(cls, "onNativeData", "(IIIIIIIILjava/lang/String;I)V");
    c->inspectionNode = env->GetMethodID(cls, "onNativeInspectionNode", "(ILio/github/mekhontsev/magicdesk/x11/X11WindowInspection$Node;)V");
    c->inspectionDone = env->GetMethodID(cls, "onNativeInspectionDone", "(IIIIIIIZZ)V");
    c->cursor = env->GetMethodID(cls, "onNativeCursor", "(IIIIIII[I)V");
    env->DeleteLocalRef(cls);
    if (!env->ExceptionCheck()) {
        jclass management = env->FindClass("io/github/mekhontsev/magicdesk/x11/X11WindowManagement");
        if (management) {
            c->managementClass = (jclass)env->NewGlobalRef(management);
            c->managementConstructor = env->GetMethodID(management, "<init>", "(ZIZZ)V");
            env->DeleteLocalRef(management);
        }
    }
    if (!env->ExceptionCheck()) {
        jclass node = env->FindClass("io/github/mekhontsev/magicdesk/x11/X11WindowInspection$Node");
        if (node) {
            c->inspectionNodeClass = (jclass)env->NewGlobalRef(node);
            c->inspectionNodeConstructor = env->GetMethodID(node, "<init>", "(IIII[BIIIIII)V");
            env->DeleteLocalRef(node);
        }
    }
    if (!env->ExceptionCheck() && c->owner) c->native = lorieConnectionCreate(&callbacks, c);
    if (c->native) return (jlong)c;
    if (c->owner) env->DeleteGlobalRef(c->owner);
    if (c->managementClass) env->DeleteGlobalRef(c->managementClass);
    if (c->inspectionNodeClass) env->DeleteGlobalRef(c->inspectionNodeClass);
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

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeBind)(JNIEnv*, jclass, jlong ptr, jint output, jint window) {
    lorieOutputBind(((Connection*)ptr)->native, output, window);
}
extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeBindShell)(JNIEnv*, jclass, jlong ptr, jint output, jint window) {
    lorieOutputBindShell(((Connection*)ptr)->native, output, window);
}
extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeBindDependents)(JNIEnv*, jclass, jlong ptr, jint output, jint window, jint parent) {
    lorieOutputBindDependents(((Connection*)ptr)->native, output, window, parent);
}
extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeShell)(JNIEnv*, jclass, jlong ptr, jint owner, jint width, jint height) {
    lorieConfigureShell(((Connection*)ptr)->native, owner, width, height);
}
extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativePresent)(JNIEnv*, jclass, jlong ptr, jint output, jint window,
        jint serial, jint left, jint top, jint right, jint bottom) {
    loriePresentShell(((Connection*)ptr)->native, output, window, serial, {left, top, right, bottom});
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeResize)(JNIEnv*, jclass, jlong ptr,
        jint output, jint window, jint width, jint height) {
    lorieOutputResize(((Connection*)ptr)->native, output, window, width, height);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativePointer)(JNIEnv*, jclass, jlong ptr,
        jint output, jint window, jfloat x, jfloat y, jint button, jboolean down) {
    lorieOutputPointer(((Connection*)ptr)->native, output, window, x, y, button, down);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeKey)(JNIEnv*, jclass, jlong ptr,
        jint output, jint window, jint androidKey, jint scanCode, jboolean down) {
    int evdev = hosted_evdev_keycode(androidKey, scanCode);
    int xKeyCode = evdev ? evdev + 8 : 0;
    lorieOutputKey(((Connection*)ptr)->native, output, window, xKeyCode, down);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeFocus)(JNIEnv*, jclass, jlong ptr, jint output, jint window) {
    lorieOutputFocus(((Connection*)ptr)->native, output, window);
}
extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeBlur)(JNIEnv*, jclass, jlong ptr, jint output, jint window) {
    lorieOutputBlur(((Connection*)ptr)->native, output, window);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeRelease)(JNIEnv*, jclass, jlong ptr, jint output, jint window) {
    lorieOutputRelease(((Connection*)ptr)->native, output, window);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeObserveWindows)(JNIEnv*, jclass, jlong ptr) {
    lorieObserveWindows(((Connection*)ptr)->native);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeInspectWindow)(JNIEnv*, jclass, jlong ptr,
        jint serial, jint window, jint limit) {
    lorieInspectWindow(((Connection*)ptr)->native, serial, window, (uint16_t)limit);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeCloseWindow)(JNIEnv*, jclass, jlong ptr, jint window, jboolean force) {
    lorieCloseWindow(((Connection*)ptr)->native, window, force);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeDpi)(JNIEnv*, jclass, jlong ptr, jint dpi) {
    lorieSetScreenDpi(((Connection*)ptr)->native, dpi);
}

extern "C" JNIEXPORT void JNICALL JNI(X11Session_nativeConfirmWindowState)(JNIEnv*, jclass, jlong ptr,
        jint window, jint requestSerial, jboolean fullscreen) {
    lorieConfirmWindowState(((Connection*)ptr)->native, window, requestSerial, {.fullscreen = fullscreen != 0});
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
        lorieOutputText(((Connection*)ptr)->native, output, window, point);
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
    env->DeleteGlobalRef(c->managementClass);
    env->DeleteGlobalRef(c->inspectionNodeClass);
    free(c);
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
