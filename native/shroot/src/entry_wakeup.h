#ifndef MD_ENTRY_WAKEUP_H
#define MD_ENTRY_WAKEUP_H
#include "raw.h"
#include <signal.h>

/* Only the native supervisor receives this signal. Guest masks, handlers and
 * descriptors are untouched. Realtime queue exhaustion must use the listener. */
enum { MD_ENTRY_WAKE_SIGNAL=64, MD_ENTRY_WAKE_CODE=0x4d445745 };
static inline long md_entry_wakeup_send(int supervisor,int caller) {
    siginfo_t info={0};
    info.si_signo=MD_ENTRY_WAKE_SIGNAL; info.si_code=SI_QUEUE;
    info.si_pid=caller; info.si_value.sival_int=MD_ENTRY_WAKE_CODE;
    return RAW4(rt_tgsigqueueinfo,supervisor,supervisor,MD_ENTRY_WAKE_SIGNAL,&info);
}
/* Call after forking the guest, before creating native workers. The signal
 * remains reserved until supervisor exit; no guest inherits its disposition. */
int md_entry_wakeup_open(void);
int md_entry_wakeup_read(int fd,int *callers,unsigned capacity);
#endif
