#define _GNU_SOURCE
#include "wayland_server.h"
#include "frame_fd.h"
#include <android/hardware_buffer_jni.h>
#include "android_keycodes.h"
#include "anonymous_buffer.h"
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/memfd.h>
#include <sys/syscall.h>

#define JNI(method) Java_io_github_mekhontsev_magicdesk_wayland_WaylandServer_##method

JNIEXPORT jstring JNICALL JNI(nativeMemoryLabel)(JNIEnv *env, jclass type) {
    (void)type;
    int fd = syscall(SYS_memfd_create, "MagicDesk-buffer-label", MFD_CLOEXEC);
    if (fd < 0) return NULL;
    char label[MDH_LABEL_BYTES];
    int result = mdh_buffer_label(fd, label);
    close(fd);
    return result == 0 ? (*env)->NewStringUTF(env, label) : NULL;
}

struct Bridge {
    MdwServer *server;
    JNIEnv *env;
    jobject owner;
    jmethodID window, window_gesture, shell, geometry, toplevel_action, frame, wanted, can_render, error, text_input, cursor;
    jmethodID content_offer, content_request, content_reply, drag_event;
};

static void content_offer(void *context, int channel, uint64_t id, MdwOutput *output, const char *types) {
    struct Bridge *b = context;
    JNIEnv *env = b->env;
    if ((*env)->ExceptionCheck(env)) return;
    jstring list = (*env)->NewStringUTF(env, types);
    if (!list) return;
    (*env)->CallVoidMethod(env, b->owner, b->content_offer, (jint)channel, (jlong)id, (jlong)(intptr_t)output, list);
    (*env)->DeleteLocalRef(env, list);
}
static void content_request(void *context, int channel, uint64_t id, uint64_t request, const char *type) {
    struct Bridge *b = context;
    JNIEnv *env = b->env;
    if ((*env)->ExceptionCheck(env)) return;
    jstring mime = (*env)->NewStringUTF(env, type);
    if (!mime) return;
    (*env)->CallVoidMethod(env, b->owner, b->content_request, (jint)channel, (jlong)id, (jlong)request, mime);
    (*env)->DeleteLocalRef(env, mime);
}
static void content_reply(void *context, uint64_t request, int fd) {
    struct Bridge *b = context;
    if ((*b->env)->ExceptionCheck(b->env)) return;
    int owned = fd < 0 ? -1 : fcntl(fd, F_DUPFD_CLOEXEC, 0);
    (*b->env)->CallVoidMethod(b->env, b->owner, b->content_reply, (jlong)request, (jint)owned);
}
static void drag_event(void *context, MdwOutput *output, uint64_t offer, bool finished, bool accepted) {
    struct Bridge *b = context;
    if (!(*b->env)->ExceptionCheck(b->env))
        (*b->env)->CallVoidMethod(b->env, b->owner, b->drag_event,
            (jlong)(intptr_t)output, (jlong)offer, (jboolean)finished, (jboolean)accepted);
}
JNIEXPORT void JNICALL JNI(nativeDrag)(JNIEnv *env, jclass type, jlong handle,
        jlong output, jint action, jlong offer, jdouble x, jdouble y, jboolean accepted) {
    (void)env; (void)type;
    struct Bridge *b = (void *)(intptr_t)handle;
    mdw_content_drag(b->server, (void *)(intptr_t)output, action, offer, x, y, accepted);
}

JNIEXPORT void JNICALL JNI(nativeContentEnable)(JNIEnv *env, jclass type, jlong handle, jboolean enabled) {
    (void)env; (void)type;
    struct Bridge *b = (void *)(intptr_t)handle;
    mdw_content_enable(b->server, enabled);
}
JNIEXPORT jboolean JNICALL JNI(nativeContentPublish)(JNIEnv *env, jclass type, jlong handle,
        jint channel, jlong id, jstring types) {
    (void)type;
    struct Bridge *b = (void *)(intptr_t)handle;
    const char *list = types ? (*env)->GetStringUTFChars(env, types, NULL) : NULL;
    if (!list) return false;
    bool result = mdw_content_publish(b->server, channel, id, list);
    (*env)->ReleaseStringUTFChars(env, types, list);
    return result;
}
JNIEXPORT void JNICALL JNI(nativeContentRead)(JNIEnv *env, jclass type, jlong handle,
        jint channel, jlong id, jlong request, jstring mime) {
    (void)type;
    struct Bridge *b = (void *)(intptr_t)handle;
    const char *name = mime ? (*env)->GetStringUTFChars(env, mime, NULL) : NULL;
    if (!name) return;
    bool result = mdw_content_read(b->server, channel, id, request, name);
    (*env)->ReleaseStringUTFChars(env, mime, name);
    if (!result) content_reply(b, request, -1);
}
JNIEXPORT void JNICALL JNI(nativeContentReply)(JNIEnv *env, jclass type, jlong handle, jlong request, jint fd) {
    (void)env; (void)type;
    struct Bridge *b = (void *)(intptr_t)handle;
    mdw_content_reply(b->server, request, fd);
}

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
            (jint)(window ? window->height : 0),
            (jint)(window ? window->min_width : 0), (jint)(window ? window->min_height : 0),
            (jint)(window ? window->max_width : 0), (jint)(window ? window->max_height : 0),
            (jlong)(window ? window->request_serial : 0),
            (jboolean)(window && window->fullscreen),
            (jlong)(window ? window->maximize_serial : 0), (jboolean)(window && window->maximized),
            (jboolean)(window == NULL));
        (*env)->DeleteLocalRef(env, app_id);
    }
    (*env)->DeleteLocalRef(env, title);
}

static void window_gesture(void *context, uint64_t id, uint32_t edges) {
    struct Bridge *bridge = context;
    if (!(*bridge->env)->ExceptionCheck(bridge->env))
        (*bridge->env)->CallVoidMethod(bridge->env, bridge->owner, bridge->window_gesture, (jlong)id, (jint)edges);
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

static void text_input_event(void *context, MdwOutput *output, const MdwTextState *state) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    if ((*env)->ExceptionCheck(env)) return;
    size_t length = state->surrounding ? strlen(state->surrounding) : 0;
    jbyteArray text = state->surrounding ? (*env)->NewByteArray(env, length) : NULL;
    if (text) (*env)->SetByteArrayRegion(env, text, 0, length, (const jbyte *)state->surrounding);
    if (!(*env)->ExceptionCheck(env))
        (*env)->CallVoidMethod(env, bridge->owner, bridge->text_input,
            (jlong)(intptr_t)output, (jlong)state->editor, (jlong)state->revision, text,
            (jint)state->cursor, (jint)state->anchor, (jint)state->purpose, (jint)state->hints,
            (jboolean)state->caret_valid, (jfloat)state->caret[0], (jfloat)state->caret[1],
            (jfloat)state->caret[2], (jfloat)state->caret[3], (jboolean)state->input_method_change);
    if (text) (*env)->DeleteLocalRef(env, text);
}

static void cursor_event(void *context, MdwOutput *output, const uint32_t *pixels,
        int width, int height, int x, int y, bool hidden) {
    struct Bridge *bridge = context;
    JNIEnv *env = bridge->env;
    if ((*env)->ExceptionCheck(env)) return;
    jintArray image = pixels ? (*env)->NewIntArray(env, width * height) : NULL;
    if (pixels && !image) return;
    if (image) (*env)->SetIntArrayRegion(env, image, 0, width * height, (const jint *)pixels);
    if (!(*env)->ExceptionCheck(env))
        (*env)->CallVoidMethod(env, bridge->owner, bridge->cursor, (jlong)(intptr_t)output,
            image, (jint)width, (jint)height, (jint)x, (jint)y, (jboolean)hidden);
    if (image) (*env)->DeleteLocalRef(env, image);
}

JNIEXPORT jboolean JNICALL JNI(nativeToplevel)(JNIEnv *env, jclass type, jlong handle, jlong id,
        jbyteArray title, jbyteArray app_id, jboolean active, jboolean maximized, jboolean fullscreen, jboolean minimized, jboolean removed) {
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
    return mdw_server_toplevel(bridge->server, (uint64_t)id, name, app, active, maximized, fullscreen, minimized, removed);
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
        (*env)->CallVoidMethod(env, bridge->owner, bridge->frame, pointer, NULL, (jint)-1, (jint)-1, (jint)0, (jint)0);
        return;
    }
    bool wanted = (*env)->CallBooleanMethod(env, bridge->owner, bridge->wanted, pointer);
    if ((*env)->ExceptionCheck(env) || !wanted) return;
    AHardwareBuffer *buffer = mdg_image_buffer(frame->image);
    jobject hardware = buffer ? AHardwareBuffer_toHardwareBuffer(env, buffer) : NULL;
    if ((*env)->ExceptionCheck(env)) return;
    int descriptor = hardware ? -1 : mdw_frame_export(frame);
    if (!hardware && descriptor < 0) { error_event(bridge, "Cannot export Wayland frame"); return; }
    int fence = -1;
    if (hardware && !mdg_image_fence(frame->image, &fence)) {
        (*env)->DeleteLocalRef(env, hardware);
        error_event(bridge, "Cannot export Wayland completion fence");
        return;
    }
    (*env)->CallVoidMethod(env, bridge->owner, bridge->frame, pointer, hardware, (jint)descriptor, (jint)fence,
        (jint)frame->width, (jint)frame->height);
    if (hardware) (*env)->DeleteLocalRef(env, hardware);
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
    bridge->window = (*env)->GetMethodID(env, type, "onWindow", "(JJ[B[BZIIIIIIJZJZZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->window_gesture = (*env)->GetMethodID(env, type, "onWindowGesture", "(JI)V");
    if (!(*env)->ExceptionCheck(env)) bridge->shell = (*env)->GetMethodID(env, type, "onShell", "(J[BZZIIIJJIIIIIZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->geometry = (*env)->GetMethodID(env, type, "onGeometry", "(JJZIIIIZ[IZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->toplevel_action = (*env)->GetMethodID(env, type, "onToplevelAction", "(JI)V");
    if (!(*env)->ExceptionCheck(env)) bridge->frame = (*env)->GetMethodID(env, type, "onFrame", "(JLandroid/hardware/HardwareBuffer;IIII)V");
    if (!(*env)->ExceptionCheck(env)) bridge->wanted = (*env)->GetMethodID(env, type, "frameWanted", "(J)Z");
    if (!(*env)->ExceptionCheck(env)) bridge->can_render = (*env)->GetMethodID(env, type, "canRender", "(J)Z");
    if (!(*env)->ExceptionCheck(env)) bridge->error = (*env)->GetMethodID(env, type, "onError", "(Ljava/lang/String;)V");
    if (!(*env)->ExceptionCheck(env)) bridge->text_input = (*env)->GetMethodID(env, type, "onTextInput", "(JJJ[BIIIIZFFFFZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->cursor = (*env)->GetMethodID(env, type, "onCursor", "(J[IIIIIZ)V");
    if (!(*env)->ExceptionCheck(env)) bridge->content_offer = (*env)->GetMethodID(env, type, "onContentOffer", "(IJJLjava/lang/String;)V");
    if (!(*env)->ExceptionCheck(env)) bridge->content_request = (*env)->GetMethodID(env, type, "onContentRequest", "(IJJLjava/lang/String;)V");
    if (!(*env)->ExceptionCheck(env)) bridge->content_reply = (*env)->GetMethodID(env, type, "onContentReply", "(JI)V");
    if (!(*env)->ExceptionCheck(env)) bridge->drag_event = (*env)->GetMethodID(env, type, "onDragEvent", "(JJZZ)V");
    (*env)->DeleteLocalRef(env, type);
    if (!(*env)->ExceptionCheck(env)) bridge->server = mdw_server_create();
    if (!bridge->server) {
        (*env)->DeleteGlobalRef(env, bridge->owner);
        free(bridge);
        return 0;
    }
    MdwEvents events = {.window = window_event, .window_gesture = window_gesture,
        .shell = shell_event, .geometry = geometry_event, .toplevel_action = toplevel_action,
        .frame = frame_event, .can_render = can_render,
        .error = error_event, .text_input = text_input_event, .cursor = cursor_event, .context = bridge,
        .content_offer = content_offer, .content_request = content_request, .content_reply = content_reply, .drag_event = drag_event};
    mdw_server_set_events(bridge->server, &events);
    return (jlong)(intptr_t)bridge;
}

JNIEXPORT jint JNICALL JNI(nativeEventFd)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    return mdw_server_fd(bridge->server);
}

JNIEXPORT void JNICALL JNI(nativeConfirmMaximized)(JNIEnv *env, jclass type, jlong handle,
        jlong window, jlong serial, jboolean maximized) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    mdw_window_confirm_maximized(bridge->server, window, serial, maximized);
}

JNIEXPORT void JNICALL JNI(nativeConfirmFullscreen)(JNIEnv *env, jclass type, jlong handle,
        jlong window, jlong serial, jboolean fullscreen) {
    (void)env; (void)type;
    struct Bridge *bridge = (void *)(intptr_t)handle;
    mdw_window_confirm_fullscreen(bridge->server, window, serial, fullscreen);
}

static bool text_bytes(JNIEnv *env, jbyteArray bytes, jint cursor, char text[4001]) {
    if (!bytes) return false;
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length > 4000 || cursor < 0 || cursor > length) return false;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)text);
    text[length] = 0;
    return !(*env)->ExceptionCheck(env) && !memchr(text, 0, length);
}

JNIEXPORT void JNICALL JNI(nativeText)(JNIEnv *env, jclass type, jlong output,
        jlong editor, jbyteArray bytes, jboolean composing, jint cursor) {
    (void)type;
    char text[4001];
    if (text_bytes(env, bytes, cursor, text))
        mdw_output_text((void *)(intptr_t)output, editor, text, composing, cursor);
}

JNIEXPORT void JNICALL JNI(nativeDeleteText)(JNIEnv *env, jclass type, jlong output,
        jlong editor, jlong revision, jint before, jint after, jbyteArray preedit, jint cursor) {
    (void)type;
    if (revision < 0 || revision > UINT32_MAX || before < 0 || after < 0) return;
    char text[4001];
    if (text_bytes(env, preedit, cursor, text))
        mdw_output_delete_text((void *)(intptr_t)output, editor, revision, before, after, text, cursor);
}

JNIEXPORT jboolean JNICALL JNI(nativeScale)(JNIEnv *env, jclass type, jlong output, jdouble scale) {
    (void)env; (void)type;
    return mdw_output_scale((void *)(intptr_t)output, scale);
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

JNIEXPORT void JNICALL JNI(nativeFrameConsumed)(JNIEnv *env, jclass type, jlong output, jboolean refresh) {
    (void)env; (void)type;
    mdw_output_frame_consumed((void *)(intptr_t)output);
    if (refresh) mdw_output_refresh((void *)(intptr_t)output);
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
