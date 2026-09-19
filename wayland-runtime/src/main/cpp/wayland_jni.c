#include "wayland_server.h"
#include "frame_fd.h"
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define JNI(method) Java_io_github_mekhontsev_magicdesk_wayland_WaylandServer_##method

struct Bridge {
    MdwServer *server;
    JNIEnv *env;
    jobject owner;
    jmethodID window, frame, wanted, error;
};

static jbyteArray bytes(JNIEnv *env, const char *value) {
    size_t length = value ? strlen(value) : 0;
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result && length) (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)value);
    return result;
}

static void window_event(void *context, uint64_t id, const MdwWindow *window) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    jbyteArray title = bytes(env, window ? window->title : NULL);
    if (!title) return;
    jbyteArray app_id = bytes(env, window ? window->app_id : NULL);
    if (app_id) {
        (*env)->CallVoidMethod(env, bridge->owner, bridge->window, (jlong)id,
            (jlong)(window ? window->parent : 0), title, app_id,
            (jboolean)(window && window->mapped), (jint)(window ? window->width : 0),
            (jint)(window ? window->height : 0), (jboolean)(window == NULL));
        (*env)->DeleteLocalRef(env, app_id);
    }
    (*env)->DeleteLocalRef(env, title);
}

static void error_event(void *context, const char *message) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    if ((*env)->ExceptionCheck(env)) return;
    jstring text = (*env)->NewStringUTF(env, message);
    if (text) {
        (*env)->CallVoidMethod(env, bridge->owner, bridge->error, text);
        (*env)->DeleteLocalRef(env, text);
    }
}

static void frame_event(void *context, MdwOutput *output, const MdwFrame *frame) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    if ((*env)->ExceptionCheck(env)) return;
    jlong pointer = (jlong)(intptr_t)output;
    if (!frame) {
        (*env)->CallVoidMethod(env, bridge->owner, bridge->frame, pointer, (jint)-1, (jint)0, (jint)0);
        return;
    }
    bool wanted = (*env)->CallBooleanMethod(env, bridge->owner, bridge->wanted, pointer);
    if ((*env)->ExceptionCheck(env) || !wanted) return;
    int descriptor = mdw_frame_export(frame);
    if (descriptor < 0) { error_event(bridge, "Cannot export Wayland software frame"); return; }
    (*env)->CallVoidMethod(env, bridge->owner, bridge->frame, pointer, (jint)descriptor,
        (jint)frame->width, (jint)frame->height);
}

JNIEXPORT jlong JNICALL JNI(nativeStart)(JNIEnv *env, jobject owner) {
    struct Bridge *bridge = calloc(1, sizeof(*bridge));
    if (!bridge) return 0;
    bridge->env = env;
    bridge->owner = (*env)->NewGlobalRef(env, owner);
    if (!bridge->owner) { free(bridge); return 0; }
    jclass type = (*env)->GetObjectClass(env, owner);
    bridge->window = (*env)->GetMethodID(env, type, "onWindow", "(JJ[B[BZIIZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->frame = (*env)->GetMethodID(env, type, "onFrame", "(JIII)V");
    if (!(*env)->ExceptionCheck(env)) bridge->wanted = (*env)->GetMethodID(env, type, "frameWanted", "(J)Z");
    if (!(*env)->ExceptionCheck(env)) bridge->error = (*env)->GetMethodID(env, type, "onError", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, type);
    if (!(*env)->ExceptionCheck(env)) bridge->server = mdw_server_create();
    if (!bridge->server) {
        (*env)->DeleteGlobalRef(env, bridge->owner);
        free(bridge);
        return 0;
    }
    MdwEvents events = {.window = window_event, .frame = frame_event, .error = error_event, .context = bridge};
    mdw_server_set_events(bridge->server, &events);
    return (jlong)(intptr_t)bridge;
}

JNIEXPORT jint JNICALL JNI(nativeEventFd)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return mdw_server_fd(bridge->server);
}

JNIEXPORT jint JNICALL JNI(nativeDispatch)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return mdw_server_dispatch(bridge->server, 0);
}

JNIEXPORT jint JNICALL JNI(nativeConnect)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return mdw_server_connect(bridge->server);
}

JNIEXPORT jstring JNICALL JNI(nativeSocket)(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return (*env)->NewStringUTF(env, mdw_server_socket(bridge->server));
}

JNIEXPORT void JNICALL JNI(nativeStop)(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    mdw_server_set_events(bridge->server, NULL);
    mdw_server_destroy(bridge->server);
    (*env)->DeleteGlobalRef(env, bridge->owner);
    free(bridge);
}

JNIEXPORT jlong JNICALL JNI(nativeOpenOutput)(JNIEnv *env, jclass type, jlong handle,
        jlong window, jint width, jint height) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return (jlong)(intptr_t)mdw_output_create(bridge->server, window, width, height);
}

JNIEXPORT jboolean JNICALL JNI(nativeResize)(JNIEnv *env, jclass type, jlong output, jint width, jint height) {
    (void)env; (void)type;
    return mdw_output_resize((void *)(intptr_t)output, width, height);
}

JNIEXPORT void JNICALL JNI(nativeReleaseOutput)(JNIEnv *env, jclass type, jlong output) {
    (void)env; (void)type;
    mdw_output_destroy((void *)(intptr_t)output);
}

JNIEXPORT void JNICALL JNI(nativeRefresh)(JNIEnv *env, jclass type, jlong output) {
    (void)env; (void)type;
    mdw_output_refresh((void *)(intptr_t)output);
}

JNIEXPORT void JNICALL JNI(nativeFocus)(JNIEnv *env, jclass type, jlong output, jboolean focused) {
    (void)env; (void)type;
    mdw_output_focus((void *)(intptr_t)output, focused);
}

JNIEXPORT void JNICALL JNI(nativePointer)(JNIEnv *env, jclass type, jlong output, jdouble x, jdouble y) {
    (void)env; (void)type;
    mdw_output_pointer((void *)(intptr_t)output, x, y);
}

JNIEXPORT void JNICALL JNI(nativeButton)(JNIEnv *env, jclass type, jlong output, jint button, jboolean down) {
    (void)env; (void)type;
    mdw_output_button((void *)(intptr_t)output, button, down);
}

JNIEXPORT void JNICALL JNI(nativeScroll)(JNIEnv *env, jclass type, jlong output, jdouble horizontal, jdouble vertical) {
    (void)env; (void)type;
    mdw_output_scroll((void *)(intptr_t)output, horizontal, vertical);
}

JNIEXPORT void JNICALL JNI(nativeKey)(JNIEnv *env, jclass type, jlong output, jint code, jboolean down) {
    (void)env; (void)type;
    mdw_output_key((void *)(intptr_t)output, code, down);
}

JNIEXPORT void JNICALL JNI(nativeCloseWindow)(JNIEnv *env, jclass type, jlong handle, jlong window) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    mdw_window_close(bridge->server, window);
}