#include "wayland_server.h"
#include "frame_fd.h"
#include "android_keycodes.h"
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define JNI(method) Java_io_github_mekhontsev_magicdesk_wayland_WaylandServer_##method

struct Bridge {
    MdwServer *server;
    JNIEnv *env;
    jobject owner;
    jmethodID window, shell, geometry, toplevel_action, frame, wanted, can_render, error;
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

static void shell_event(void *context, uint64_t id, const MdwShellSurface *surface) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    if ((*env)->ExceptionCheck(env)) return;
    jbyteArray name = bytes(env, surface ? surface->name : NULL);
    if (!name) return;
    (*env)->CallVoidMethod(env, bridge->owner, bridge->shell, (jlong)id, name,
        (jboolean)(surface && surface->mapped), (jboolean)(surface && surface->configure_needed),
        (jint)(surface ? surface->layer : 0),
        (jint)(surface ? surface->keyboard : 0), (jint)(surface ? surface->anchors : 0),
        (jlong)(surface ? surface->width : 0), (jlong)(surface ? surface->height : 0),
        (jint)(surface ? surface->margin_left : 0), (jint)(surface ? surface->margin_top : 0),
        (jint)(surface ? surface->margin_right : 0), (jint)(surface ? surface->margin_bottom : 0),
        (jint)(surface ? surface->exclusive_zone : 0), (jboolean)(surface == NULL));
    (*env)->DeleteLocalRef(env, name);
}

static void toplevel_action(void *context, uint64_t id, MdwToplevelAction action) {
    struct Bridge *bridge = context;
    if (!(*bridge->env)->ExceptionCheck(bridge->env))
        (*bridge->env)->CallVoidMethod(bridge->env, bridge->owner, bridge->toplevel_action, (jlong)id, (jint)action);
}

JNIEXPORT jboolean JNICALL JNI(nativeToplevel)(JNIEnv *env, jclass type, jlong handle, jlong id,
        jbyteArray title, jbyteArray app_id, jboolean active, jboolean maximized, jboolean fullscreen, jboolean removed) {
    (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    char name[4097] = {0}, app[1025] = {0};
    if (!removed) {
        if (!title || !app_id) return false;
        jsize name_len = (*env)->GetArrayLength(env, title), app_len = (*env)->GetArrayLength(env, app_id);
        if (name_len > 4096 || app_len > 1024) return false;
        (*env)->GetByteArrayRegion(env, title, 0, name_len, (jbyte *)name);
        (*env)->GetByteArrayRegion(env, app_id, 0, app_len, (jbyte *)app);
        if ((*env)->ExceptionCheck(env)) return false;
    }
    return mdw_server_toplevel(bridge->server, (uint64_t)id, name, app, active, maximized, fullscreen, removed);
}

static void geometry_event(void *context, const MdwViewGeometry *geometry) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    if ((*env)->ExceptionCheck(env)) return;
    jintArray input = (*env)->NewIntArray(env, geometry->input_count * 4);
    if (!input) return;
    jint rects[MDW_MAX_INPUT_RECTS * 4];
    for (size_t i = 0; i < geometry->input_count; ++i) {
        rects[i * 4] = geometry->input[i].left;
        rects[i * 4 + 1] = geometry->input[i].top;
        rects[i * 4 + 2] = geometry->input[i].right;
        rects[i * 4 + 3] = geometry->input[i].bottom;
    }
    if (geometry->input_count) (*env)->SetIntArrayRegion(env, input, 0, geometry->input_count * 4, rects);
    if (!(*env)->ExceptionCheck(env))
        (*env)->CallVoidMethod(env, bridge->owner, bridge->geometry, (jlong)geometry->id, (jlong)geometry->revision,
            (jboolean)geometry->mapped, (jint)geometry->paint.left, (jint)geometry->paint.top,
            (jint)geometry->paint.right, (jint)geometry->paint.bottom, (jboolean)geometry->input_complete, input,
            (jboolean)geometry->dependents);
    (*env)->DeleteLocalRef(env, input);
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

static bool can_render(void *context, MdwOutput *output) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    if ((*env)->ExceptionCheck(env)) return false;
    bool ready = (*env)->CallBooleanMethod(env, bridge->owner, bridge->can_render, (jlong)(intptr_t)output);
    return !(*env)->ExceptionCheck(env) && ready;
}

JNIEXPORT jlong JNICALL JNI(nativeStart)(JNIEnv *env, jobject owner) {
    struct Bridge *bridge = calloc(1, sizeof(*bridge));
    if (!bridge) return 0;
    bridge->env = env;
    bridge->owner = (*env)->NewGlobalRef(env, owner);
    if (!bridge->owner) { free(bridge); return 0; }
    jclass type = (*env)->GetObjectClass(env, owner);
    bridge->window = (*env)->GetMethodID(env, type, "onWindow", "(JJ[B[BZIIZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->shell = (*env)->GetMethodID(env, type, "onShell", "(J[BZZIIIJJIIIIIZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->geometry = (*env)->GetMethodID(env, type, "onGeometry", "(JJZIIIIZ[IZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->toplevel_action = (*env)->GetMethodID(env, type, "onToplevelAction", "(JI)V");
    if (!(*env)->ExceptionCheck(env)) bridge->frame = (*env)->GetMethodID(env, type, "onFrame", "(JIII)V");
    if (!(*env)->ExceptionCheck(env)) bridge->wanted = (*env)->GetMethodID(env, type, "frameWanted", "(J)Z");
    if (!(*env)->ExceptionCheck(env)) bridge->can_render = (*env)->GetMethodID(env, type, "canRender", "(J)Z");
    if (!(*env)->ExceptionCheck(env)) bridge->error = (*env)->GetMethodID(env, type, "onError", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, type);
    if (!(*env)->ExceptionCheck(env)) bridge->server = mdw_server_create();
    if (!bridge->server) {
        (*env)->DeleteGlobalRef(env, bridge->owner);
        free(bridge);
        return 0;
    }
    MdwEvents events = {.window = window_event, .shell = shell_event, .geometry = geometry_event, .toplevel_action = toplevel_action,
        .frame = frame_event, .can_render = can_render,
        .error = error_event, .context = bridge};
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

JNIEXPORT jlong JNICALL JNI(nativeBorrowDependents)(JNIEnv *env, jclass type, jlong parent) {
    (void)env; (void)type;
    return (jlong)(intptr_t)mdw_output_borrow_dependents((void *)(intptr_t)parent);
}

JNIEXPORT jboolean JNICALL JNI(nativeViewport)(JNIEnv *env, jclass type, jlong output,
        jint x, jint y, jint width, jint height) {
    (void)env; (void)type;
    return mdw_output_viewport((void *)(intptr_t)output, x, y, width, height);
}

JNIEXPORT void JNICALL JNI(nativeReleaseOutput)(JNIEnv *env, jclass type, jlong output) {
    (void)env; (void)type;
    mdw_output_destroy((void *)(intptr_t)output);
}

JNIEXPORT jboolean JNICALL JNI(nativeSetVisible)(JNIEnv *env, jclass type, jlong output, jboolean visible) {
    (void)env; (void)type;
    return mdw_output_set_visible((void *)(intptr_t)output, visible);
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

JNIEXPORT void JNICALL JNI(nativeKey)(JNIEnv *env, jclass type, jlong output, jint android_key, jint scan_code, jboolean down) {
    (void)env; (void)type;
    int code = hosted_evdev_keycode(android_key, scan_code);
    if (code) mdw_output_key((void *)(intptr_t)output, code, down);
}

JNIEXPORT void JNICALL JNI(nativeCloseWindow)(JNIEnv *env, jclass type, jlong handle, jlong window, jboolean force) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    if (force) mdw_window_disconnect(bridge->server, window);
    else mdw_window_close(bridge->server, window);
}

JNIEXPORT jboolean JNICALL JNI(nativeShellOutput)(JNIEnv *env, jclass type, jlong handle,
        jint width, jint height) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return mdw_server_shell_output(bridge->server, width, height);
}

JNIEXPORT jboolean JNICALL JNI(nativeConfigureShell)(JNIEnv *env, jclass type, jlong handle,
        jlong surface, jint x, jint y, jint width, jint height) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return mdw_shell_surface_configure(bridge->server, surface, x, y, width, height);
}
