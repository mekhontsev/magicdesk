#ifndef MAGICDESK_WAYLAND_SERVER_H
#define MAGICDESK_WAYLAND_SERVER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct MdwServer MdwServer;
typedef struct MdwOutput MdwOutput;
typedef enum { MDW_PRIMARY, MDW_MIDDLE, MDW_SECONDARY } MdwButton;

typedef struct {
	uint64_t id;
	uint64_t parent;
	const char *title;
	const char *app_id;
	bool mapped;
	int width;
	int height;
} MdwWindow;

typedef struct {
	const void *pixels;
	uint32_t format;
	size_t stride;
	int width;
	int height;
} MdwFrame;

typedef struct {
	void (*window)(void *context, uint64_t id, const MdwWindow *window);
	void (*frame)(void *context, MdwOutput *output, const MdwFrame *frame);
	void (*error)(void *context, const char *message);
	void *context;
} MdwEvents;

MdwServer *mdw_server_create(void);
void mdw_server_set_events(MdwServer *server, const MdwEvents *events);
const char *mdw_server_socket(const MdwServer *server);
int mdw_server_fd(MdwServer *server);
int mdw_server_connect(MdwServer *server);
int mdw_server_dispatch(MdwServer *server, int timeout_ms);
void mdw_server_destroy(MdwServer *server);
MdwOutput *mdw_output_create(MdwServer *server, uint64_t window, int width, int height);
bool mdw_output_resize(MdwOutput *output, int width, int height);
bool mdw_output_refresh(MdwOutput *output);
bool mdw_output_focus(MdwOutput *output, bool focused);
bool mdw_output_pointer(MdwOutput *output, double x, double y);
bool mdw_output_button(MdwOutput *output, MdwButton button, bool down);
bool mdw_output_scroll(MdwOutput *output, double horizontal, double vertical);
bool mdw_output_key(MdwOutput *output, uint32_t evdev_code, bool down);
void mdw_output_destroy(MdwOutput *output);
bool mdw_window_close(MdwServer *server, uint64_t window);

#endif