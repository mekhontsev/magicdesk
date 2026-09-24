#ifndef MAGICDESK_CONTENT_STREAM_H
#define MAGICDESK_CONTENT_STREAM_H
#include <stdbool.h>
#include <stdint.h>
#include <wayland-server-core.h>

struct MdwContentStream;
/* Completion borrows the sealed, seekable read result; -1 reports failure. */
typedef void (*MdwStreamDone)(void *context, uint64_t id, int result);
struct MdwContentStream *mdw_stream_create(struct wl_event_loop *loop,
    uint64_t id, int pipe, bool reading, MdwStreamDone done, void *context);
/* A writer waits for a seekable source, then streams without blocking the event loop. */
bool mdw_stream_source(struct MdwContentStream *stream, int descriptor);
void mdw_stream_cancel(struct MdwContentStream *stream);
#endif
