#ifndef MD_SYSV_IPC_INTERNAL_H
#define MD_SYSV_IPC_INTERNAL_H
#include "sysv_ipc.h"
#include "ipc_permissions.h"
#include "ipc_database.h"
#include "ipc_pid.h"
#include <linux/sem.h>
#include <linux/msg.h>
#include <sqlite3.h>

enum { MD_IPC_SEM = 1, MD_IPC_MSG = 2, MD_MSGMAX = 8192, MD_MSGMNB = 16384 };
struct md_ipc { struct md_ipc_database database; int events; uint64_t namespace_id; int changed;
    struct md_user_namespace *user_namespace; };
struct md_ipc_undo { unsigned refs; int used; uint64_t id; };
struct md_ipc_pending {
    struct md_ipc_request call;
    struct md_identity identity;
    struct md_ipc_transport *transport;
    pid_t tid, pid;
    struct md_pid_identity *process;
    uint64_t undo;
    int watcher, registered, number, zero, blocked;
    int64_t deadline;
    void *input;
    size_t size;
};
int md_ipc_sql(int);
int md_ipc_exec(struct md_ipc *, const char *);
int md_ipc_query(struct md_ipc *, const char *, sqlite3_stmt **);
int md_ipc_begin(struct md_ipc *);
int md_ipc_finish(struct md_ipc *, int);
int md_ipc_memory(struct md_ipc_pending *, uintptr_t, void *, size_t, int write);
int md_ipc_get(struct md_ipc *, int kind, int key, int flags, int *);
int md_ipc_load(struct md_ipc *, int kind, int id, void *, size_t);
int md_ipc_save(struct md_ipc *, int id, const void *, size_t);
int md_ipc_create(struct md_ipc *, int kind, int key, const void *, size_t, int *);
int md_ipc_remove(struct md_ipc *, int id);
int md_ipc_undo_apply(struct md_ipc *, int64_t owner, uint64_t id);
long md_ipc_sem(struct md_ipc *, struct md_ipc_pending *);
long md_ipc_msg(struct md_ipc *, struct md_ipc_pending *);
#endif
