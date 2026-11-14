#ifndef MD_TASK_CALL_ARGUMENTS_H
#define MD_TASK_CALL_ARGUMENTS_H

/* A native effect, independent of the resident/stopped execution transport. */
enum { MD_TASK_CALL_INTERRUPTIBLE=1 };
struct md_task_call_arguments { long number; unsigned long value[6]; unsigned flags; };

#endif
