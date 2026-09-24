#ifndef MAGICDESK_WAYLAND_SERVER_H
#define MAGICDESK_WAYLAND_SERVER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <graphics.h>

typedef struct MdwServer MdwServer;
typedef struct MdwOutput MdwOutput;
typedef enum { MDW_PRIMARY, MDW_MIDDLE, MDW_SECONDARY } MdwButton;
typedef enum { MDW_TOPLEVEL_ACTIVATE, MDW_TOPLEVEL_MAXIMIZE, MDW_TOPLEVEL_FULLSCREEN,
    MDW_TOPLEVEL_UNMAXIMIZE, MDW_TOPLEVEL_UNFULLSCREEN, MDW_TOPLEVEL_CLOSE } MdwToplevelAction;

typedef struct {
	uint64_t id;
	uint64_t parent;
	const char *title;
	const char *app_id;
	bool mapped;
	int width;
	int height;
    uint64_t request_serial;
    bool fullscreen;
} MdwWindow;

typedef enum { MDW_BACKGROUND, MDW_BOTTOM, MDW_TOP, MDW_OVERLAY } MdwLayer;
typedef enum { MDW_KEYBOARD_NONE, MDW_KEYBOARD_ON_DEMAND, MDW_KEYBOARD_EXCLUSIVE } MdwKeyboard;
enum { MDW_ANCHOR_LEFT = 1, MDW_ANCHOR_TOP = 2, MDW_ANCHOR_RIGHT = 4, MDW_ANCHOR_BOTTOM = 8 };

typedef struct {
    uint64_t id;
    const char *name;
    bool mapped;
    bool configure_needed;
    MdwLayer layer;
    MdwKeyboard keyboard;
    uint32_t anchors;
    uint32_t width, height;
    int32_t margin_left, margin_top, margin_right, margin_bottom;
    int32_t exclusive_zone;
} MdwShellSurface;

typedef struct { int32_t left, top, right, bottom; } MdwRect;
enum { MDW_MAX_INPUT_RECTS = 512 };
typedef struct {
    uint64_t id, revision;
    bool mapped, input_complete;
    MdwRect paint;
    const MdwRect *input; /* Borrowed for this callback; surface-family coordinates. */
    size_t input_count;
    bool dependents;
} MdwViewGeometry;

typedef struct {
    MdgImage *image; /* Borrowed for this frame callback. */
	int width;
	int height;
} MdwFrame;

typedef struct {
	void (*window)(void *context, uint64_t id, const MdwWindow *window);
    void (*shell)(void *context, uint64_t id, const MdwShellSurface *surface);
    void (*geometry)(void *context, const MdwViewGeometry *geometry);
    void (*toplevel_action)(void *context, uint64_t id, MdwToplevelAction action);
    void (*text_input)(void *context, MdwOutput *output, bool enabled);
    /* Borrowed ARGB pixels, never rendered into the client image. Null selects the host default. */
    void (*cursor)(void *context, MdwOutput *output, const uint32_t *pixels,
        int width, int height, int hotspot_x, int hotspot_y, bool hidden);
	void (*frame)(void *context, MdwOutput *output, const MdwFrame *frame);
	bool (*can_render)(void *context, MdwOutput *output);
	void (*error)(void *context, const char *message);
    void (*content_offer)(void *context, int channel, uint64_t id, MdwOutput *output, const char *types);
    void (*content_request)(void *context, int channel, uint64_t id, uint64_t request, const char *type);
    /* Borrowed sealed FD, or -1 when the producer rejected/timed out. */
    void (*content_reply)(void *context, uint64_t request, int fd);
    void (*drag_event)(void *context, MdwOutput *output, uint64_t offer, bool finished, bool accepted);
	void *context;
} MdwEvents;

enum { MDW_CONTENT_CLIPBOARD, MDW_CONTENT_DRAG };
void mdw_content_enable(MdwServer *server, bool enabled);
bool mdw_content_publish(MdwServer *server, int channel, uint64_t id, const char *types);
bool mdw_content_read(MdwServer *server, int channel, uint64_t id, uint64_t request, const char *type);
void mdw_content_reply(MdwServer *server, uint64_t request, int fd);
typedef enum { MDW_DRAG_BEGIN, MDW_DRAG_ENTER, MDW_DRAG_MOVE, MDW_DRAG_LEAVE,
    MDW_DRAG_DROP, MDW_DRAG_FINISH, MDW_DRAG_ABORT } MdwDragAction;
void mdw_content_drag(MdwServer *server, MdwOutput *output, MdwDragAction action,
    uint64_t offer, double x, double y, bool accepted);

MdwServer *mdw_server_create(void);
void mdw_server_set_events(MdwServer *server, const MdwEvents *events);
const char *mdw_server_socket(const MdwServer *server);
int mdw_server_fd(MdwServer *server);
int mdw_server_connect(MdwServer *server);
int mdw_server_dispatch(MdwServer *server, int timeout_ms);
void mdw_server_destroy(MdwServer *server);
/* Explicit shell admission. Removing the output closes its shell surfaces, not applications. */
bool mdw_server_shell_output(MdwServer *server, int width, int height);
bool mdw_server_toplevel(MdwServer *server, uint64_t id, const char *title, const char *app_id,
    bool active, bool maximized, bool fullscreen, bool removed);
bool mdw_shell_surface_configure(MdwServer *server, uint64_t id,
    int x, int y, int width, int height);
MdwOutput *mdw_output_create(MdwServer *server, uint64_t window, int width, int height);
MdwOutput *mdw_output_borrow_dependents(MdwOutput *parent);
bool mdw_output_resize(MdwOutput *output, int width, int height);
bool mdw_output_scale(MdwOutput *output, double scale);
/* Select rendered family coordinates without configuring the client's content size. */
bool mdw_output_viewport(MdwOutput *output, int x, int y, int width, int height);
bool mdw_output_set_visible(MdwOutput *output, bool visible);
bool mdw_output_refresh(MdwOutput *output);
void mdw_output_frame_consumed(MdwOutput *output);
bool mdw_output_focus(MdwOutput *output, bool focused);
bool mdw_output_pointer(MdwOutput *output, double x, double y);
bool mdw_output_button(MdwOutput *output, MdwButton button, bool down);
bool mdw_output_scroll(MdwOutput *output, double horizontal, double vertical);
bool mdw_output_key(MdwOutput *output, uint32_t evdev_code, bool down);
bool mdw_output_text(MdwOutput *output, const char *text, bool composing, int cursor);
void mdw_output_destroy(MdwOutput *output);
bool mdw_window_close(MdwServer *server, uint64_t window);
bool mdw_window_disconnect(MdwServer *server, uint64_t window);
bool mdw_window_confirm_fullscreen(MdwServer *server, uint64_t window, uint64_t request_serial, bool fullscreen);

#endif
