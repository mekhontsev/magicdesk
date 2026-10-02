#define _GNU_SOURCE
#include "wire.h"
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/wait.h>
#include <unistd.h>

/* A host invocation owns access; execution stays in its inherited UID, PTY and process group. */
int main(int argc, char **argv) {
    if (argc < 3 || strcmp(argv[1], "--")) {
        fputs("usage: magicdesk-connect -- COMMAND [ARGUMENTS...]\n", stderr); return 2;
    }
    int owner = md_command_lease();
    if (owner < 0) { fprintf(stderr,"magicdesk-connect: %s\n",strerror(-owner)); return 3; }
    sigset_t mask, previous;
    sigemptyset(&mask);
    const int signals[] = {SIGCHLD, SIGTERM, SIGINT, SIGHUP, SIGQUIT};
    for (unsigned i=0;i<sizeof(signals)/sizeof(*signals);i++) sigaddset(&mask, signals[i]);
    pid_t parent = getppid();
    if (sigprocmask(SIG_BLOCK,&mask,&previous) || prctl(PR_SET_PDEATHSIG,SIGTERM) || getppid()!=parent) return 125;
    int events = signalfd(-1, &mask, SFD_CLOEXEC | SFD_NONBLOCK);
    if (events < 0) return 125;
    struct sigaction inherited_child, reap = {.sa_handler=SIG_DFL};
    sigemptyset(&reap.sa_mask);
    if (sigaction(SIGCHLD,&reap,&inherited_child)) return 125;
    pid_t host = getpid(), child = fork();
    if (child < 0) return 125;
    if (!child) {
        close(owner); close(events);
        if (prctl(PR_SET_PDEATHSIG,SIGKILL) || getppid()!=host
                || sigaction(SIGCHLD,&inherited_child,NULL) || sigprocmask(SIG_SETMASK,&previous,NULL)) _exit(125);
        execvp(argv[2],argv+2); perror("magicdesk-connect: exec"); _exit(127);
    }
    int status = 0, cancelling = 0;
    for (;;) {
        pid_t exited = waitpid(child,&status,WNOHANG);
        if (exited == child) break;
        if (exited < 0 && errno != EINTR) { status = 125<<8; break; }
        struct pollfd event = {.fd=events,.events=POLLIN};
        // EVENT_WAIT: child completion or cancellation. Cancellation grace ends in SIGKILL, not success.
        int ready = poll(&event,1,cancelling ? 2000 : -1);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) { kill(child,SIGKILL); cancelling=1; continue; }
        struct signalfd_siginfo signal;
        while (read(events,&signal,sizeof(signal)) == sizeof(signal)) {
            if (signal.ssi_signo != SIGCHLD) {
                if (!cancelling) { close(owner); owner=-1; }
                kill(child,(int)signal.ssi_signo); kill(child,SIGCONT); cancelling=1;
            }
        }
    }
    if (owner >= 0) close(owner);
    close(events);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128+WTERMSIG(status);
}
