#include "interception.h"
extern long md_guest_task_control(void);
/* The supervisor owns installation and its per-group admission state. */
long md_pid_filter_install(void) {
    return md_guest_task_control();
}
