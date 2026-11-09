#ifndef MD_SIGNAL_ORIGIN_H
#define MD_SIGNAL_ORIGIN_H
#include "pid_namespace.h"
#include <signal.h>

/* Notification-time identity, not a process handle. Reaping may release all
 * task resources and PID lookup reservations while this value remains valid. */
struct md_signal_origin {
    siginfo_t info;
    uid_t uid;
    unsigned count;
    int valid, reboot_signal;
    struct md_pid_number_value numbers[MD_PID_LEVELS];
};
static inline int md_signal_origin_matches(const struct md_signal_origin *o,const siginfo_t *i) {
    return o && o->valid && i->si_signo==SIGCHLD && o->info.si_signo==i->si_signo
        && o->info.si_code==i->si_code && o->info.si_pid==i->si_pid && o->info.si_uid==i->si_uid
        && o->info.si_status==i->si_status && o->info.si_utime==i->si_utime && o->info.si_stime==i->si_stime;
}
typedef void (*md_signal_origin_capture)(void *,const siginfo_t *,struct md_signal_origin *);
static inline int md_signal_child_notice(const siginfo_t *info) {
    return info->si_signo==SIGCHLD && info->si_code>=CLD_EXITED && info->si_code<=CLD_CONTINUED;
}
static inline int md_signal_native_sender(const siginfo_t *info) {
    return md_signal_child_notice(info) || info->si_code==SI_USER || info->si_code==SI_TKILL;
}
#endif
