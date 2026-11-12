#ifndef MD_PID_FILTER_PROGRAM_H
#define MD_PID_FILTER_PROGRAM_H
#include <linux/filter.h>
#include <stdint.h>

#define MD_PID_FILTER_INSTRUCTIONS 128
struct md_pid_filter_program {
    unsigned count;
    struct sock_filter code[MD_PID_FILTER_INSTRUCTIONS];
};
struct md_interception_abi;
void md_pid_filter_build(const struct md_interception_abi *,struct md_pid_filter_program *);
#endif
