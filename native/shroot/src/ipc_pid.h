#ifndef MD_IPC_PID_H
#define MD_IPC_PID_H
#include "pid_namespace.h"
#include "ipc_database.h"

/* Capture at mutation, translate for the reader. Retired/reused native PIDs
 * must not change an IPC object's recorded identity. */
static inline int md_ipc_pid_bind(sqlite3_stmt *q,int column,const struct md_pid_identity *process) {
    struct md_pid_number_value numbers[MD_PID_LEVELS];
    unsigned count=md_pid_identity_export(process,numbers);
    return md_ipc_database_sql(sqlite3_bind_blob(q,column,numbers,
        count*sizeof(*numbers),SQLITE_TRANSIENT));
}
static inline int md_ipc_pid_visible(sqlite3_stmt *q,int column,
        const struct md_pid_identity *observer,pid_t native) {
    return md_pid_numbers_visible(sqlite3_column_blob(q,column),sqlite3_column_bytes(q,column),
        md_pid_identity_namespace(observer),native);
}
#endif
