#ifndef MD_SUPERVISOR_INTERNAL_H
#define MD_SUPERVISOR_INTERNAL_H

/* Shared launch services and diagnostics; operation protocols live in focused owners. */
#include "supervisor_thread.h"
#include "task_checkpoint.h"
#include "task_filter.h"
#include "task_copy.h"
#include "task_helper.h"
#include "fs_continuation.h"
#include "guest_debugger.h"
#include "interception_memory.h"
#include "namespace_broker.h"
#include "task_registry.h"
#include "entry_route.h"
#include "profile.h"
#include <stdio.h>

#define READY abi.ready
#define DONE abi.done
#define ALLOCATE abi.allocate
#define ALLOCATED abi.allocated
#define DISPATCH abi.dispatch
#define EXPORT abi.export_fd
#define EXPORTED abi.exported
#define RAW_GATE abi.raw_gate
#define COPY_BEGIN abi.copy_begin
#define COPY_END abi.copy_end

enum {
#define CHECKPOINT_COUNTER(field,symbol) CHECKPOINT_COUNT_##field,
    MD_CHECKPOINT_SITES(CHECKPOINT_COUNTER)
#undef CHECKPOINT_COUNTER
    CHECKPOINT_COUNT
};
struct interception_statistics {
    uint64_t ptrace_requests, unknown, native_seeks, stops;
    uint64_t ptrace_operations[2][64];
    uint64_t checkpoints[CHECKPOINT_COUNT];
    uint64_t buffer_commands[128];
    uint64_t event_polls, event_waits;
    uint64_t quiesce_interrupts, memory_repairs, entry_fence_transitions, entry_fence_drains, entry_fence_quiescences;
    uint64_t notification_rebounds, notification_readmissions_retired;
    uint64_t direct_status, direct_open, direct_fallback, direct_admit, direct_retry, compact_close, native_io;
    uint64_t captured_writes, captured_receives, entry_receives;
    uint64_t untracked_close, unchanged_fd_flags;
    uint64_t entry_executions, entry_completions, entry_promotions, entry_effects,entry_native;
    uint64_t entry_contexts[MD_CONTEXT_COUNT];
    uint64_t entry_stop_reasons[MD_CONTEXT_COUNT];
    uint64_t entry_local_native;
    uint64_t signal_frames[5];
    struct md_cost event_collect, event_sleep;
    struct { uint64_t trace, notification; } calls[512];
};
#define SUPERVISOR_TRACE(...) do { if (diagnostics) fprintf(stderr, __VA_ARGS__); } while (0)
#define SUPERVISOR_SIGNAL_TRACE(...) do { if (diagnostics || signal_diagnostics) fprintf(stderr, __VA_ARGS__); } while (0)
#define CHECK(x) do { if (!(x)) fail(#x, __FILE__, __LINE__); } while (0)

struct denial { unsigned nr, adapted, count; enum phase phase; };

enum { EVENT_SOURCES = 8 };

extern struct md_interception_abi abi;
extern int diagnostics;
extern int signal_diagnostics;
extern struct interception_statistics *statistics;
extern struct md_pending_queue retry_queues[2];
extern struct md_task_wait task_wait;
extern struct md_debugger *debugger;
extern pid_t leader;
extern unsigned live, total, images, signals, copy_faults, fstat_failures;
extern unsigned calls;
extern struct denial denials[64];
extern int result, signal_fd;
extern int listener;
extern int fd_ready;
extern struct md_resource_wakeup *fd_wakeup;
extern struct md_fd_exchanges *fd_exchanges;
extern int cancelling;
extern struct md_launch_registration registration;
extern unsigned external_stats, external_errors;
extern unsigned protected_stats;
extern unsigned retained_calls, retained_errors;
extern unsigned root_changes, restricted_denials;
extern unsigned group_stops, group_wakes, raced_wakes;
extern const char *admitted_path;
extern struct md_fs_worker *filesystem;
extern struct md_fs_buffer_service *buffer_service;
extern struct md_mount_copy_service *mount_copy_service;
extern struct md_ipc_origins *origin_service;
extern struct md_task_helpers *helpers;
extern int ipc_events;
extern int socket_events;
extern struct md_namespace_broker *broker;
extern struct md_task_registry *tasks;
extern struct md_identity launch_identity;
extern int virtual_identity;
extern struct md_admission admission;
extern unsigned admitted_images;
extern unsigned identity_changes;

void retry_clear(struct thread *t, enum retry_kind kind);
void retry_wait(struct thread *t, enum retry_kind kind);
void cleanup(void);
_Noreturn void fail(const char *what, const char *file, int line);
void adapter_progress(void);
void cancel_children(int signal);
void cancel_tree(int status);


#endif
