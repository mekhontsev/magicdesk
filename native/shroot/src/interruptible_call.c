#define _GNU_SOURCE
#include "interruptible_call.h"
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <ucontext.h>

extern char md_interruptible_begin[], md_interruptible_end[], md_interruptible_cancelled[];
static pthread_once_t once=PTHREAD_ONCE_INIT;
static int setup_error;

static void interrupt_call(int signal, siginfo_t *info, void *context) {
    (void)signal; (void)info;
    ucontext_t *state=context;
    uintptr_t pc=state->uc_mcontext.pc;
    if (pc>=(uintptr_t)md_interruptible_begin && pc<(uintptr_t)md_interruptible_end)
        state->uc_mcontext.pc=(uintptr_t)md_interruptible_cancelled;
}
static void setup(void) {
    struct sigaction action={.sa_sigaction=interrupt_call,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGUSR2,&action,NULL)) setup_error=-errno;
}
int md_interruptible_prepare(void) {
    pthread_once(&once,setup);
    return setup_error;
}
