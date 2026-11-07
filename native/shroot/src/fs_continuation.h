#ifndef MD_FS_CONTINUATION_H
#define MD_FS_CONTINUATION_H
#include "fs_operation.h"
struct md_filesystem;
/* Provider-only identity, never accepted from adapter/wire operands. */
struct md_fs_task_subject { pid_t task; uint64_t generation; };
/* Transport-owned execution, provider-owned storage. Only a suspended stage
 * may request a native descriptor; completion transfers it exactly once. */
struct md_fs_task_input {
    struct md_fs_task_capture request;
    struct md_fs_task_subject subject;
    int descriptor, error, completed;
    struct stat metadata;
    size_t directory_limit;
    void *directory;
    size_t directory_size;
};
/* Allocated only by asynchronous providers. Admission owns all operands before
 * the original call returns. No store lock, worker identity or borrowed output
 * pointer survives suspension. resume advances a saved stage, never replays a
 * committed filesystem mutation. The transport retains the task context. */
struct md_fs_continuation {
    struct md_fs_request request;
    void *arguments;
    struct md_fs_task_input *task_input;
    int (*ready)(struct md_fs_continuation *);
    void (*resume)(struct md_fs_continuation *,struct md_filesystem *,
        struct md_fs_result *,const struct md_fs_output *);
    void (*destroy)(struct md_fs_continuation *);
};
int md_fs_continuation_capture(struct md_fs_continuation *,const struct md_fs_request *);
/* Releases captured operands; the concrete destroy callback frees its owner. */
void md_fs_continuation_clear(struct md_fs_continuation *);
/* Transfers the previous stage to its successor, or destroys it on completion.
 * A suspended operation must not also publish a partial result. */
int md_fs_continuation_pending(struct md_fs_continuation *previous,
    struct md_fs_continuation **next,struct md_fs_result *);
/* Compose a view operation with its next stage. The callback borrows the
 * intermediate result/origin; consume a returned FD by setting it to -1.
 * Only suspension allocates a chain or copies the outer request. */
typedef void (*md_fs_next_stage)(struct md_filesystem *,const struct md_fs_request *,
    struct md_fs_result *,struct md_fs_anchor **,struct md_fs_result *,const struct md_fs_output *);
typedef void (*md_fs_stage)(struct md_filesystem *,const struct md_fs_request *,
    struct md_fs_result *,const struct md_fs_output *);
void md_fs_then(struct md_filesystem *,const struct md_fs_request *outer,
    const struct md_fs_request *step,md_fs_stage,struct md_fs_result *,const struct md_fs_output *,md_fs_next_stage);
void md_fs_view_then(struct md_filesystem *,const struct md_fs_request *outer,
    const struct md_fs_request *step,struct md_fs_result *,const struct md_fs_output *,md_fs_next_stage);
#endif
