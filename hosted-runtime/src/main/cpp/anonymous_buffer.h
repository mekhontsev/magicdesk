#pragma once
enum { MDH_LABEL_BYTES = 512 };
typedef struct { char source[MDH_LABEL_BYTES], target[MDH_LABEL_BYTES]; } MdhBufferAccess;
int mdh_buffer_label(int fd, char label[MDH_LABEL_BYTES]);
/* Returns a labelled probe memfd owned by the caller, or -1 without changing policy. */
int mdh_buffer_access_init(MdhBufferAccess *access, const char *target);
int mdh_buffer_admit(int fd, void *access);
