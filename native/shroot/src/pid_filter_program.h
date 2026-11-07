#ifndef MD_PID_FILTER_PROGRAM_H
#define MD_PID_FILTER_PROGRAM_H
#include <linux/filter.h>
#include <stdint.h>

#define MD_PID_FILTER_INSTRUCTIONS 128
struct md_pid_filter_program {
    unsigned count;
    struct sock_filter code[MD_PID_FILTER_INSTRUCTIONS];
};
void md_pid_filter_build(uintptr_t gate,uintptr_t task,struct md_pid_filter_program *);
#endif
