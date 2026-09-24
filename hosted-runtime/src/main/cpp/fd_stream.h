#pragma once
#include <stdbool.h>
#include <stddef.h>

enum { MDH_STREAM_BYTES = 16384, MDH_STREAM_FDS = 28 };
/* One bounded in-flight chunk. Received descriptors remain owned until sent or discarded. */
typedef struct {
    char bytes[MDH_STREAM_BYTES];
    size_t used, sent;
    int fds[MDH_STREAM_FDS], count;
    bool eof;
} MdhFdStream;

void mdh_stream_clear(MdhFdStream *stream);
int mdh_stream_receive(MdhFdStream *stream, int fd, int (*admit)(int, void *), void *context);
int mdh_stream_send(MdhFdStream *stream, int fd);
