#ifndef MD_IPC_DATABASE_H
#define MD_IPC_DATABASE_H
#include <sqlite3.h>
#include <stdint.h>

/* One authority's native storage and lease. Embedded, lazy and confined to
 * its supervisor thread; guest object semantics remain with the caller. */
struct md_ipc_database {
    sqlite3 *handle;
    int directory, gate, lease, locked, admitted;
    int64_t owner;
};
int md_ipc_database_sql(int);
int md_ipc_database_exec(struct md_ipc_database *,const char *);
int md_ipc_database_query(struct md_ipc_database *,const char *,sqlite3_stmt **);
/* Admission-only, under the gate. Existing transient objects retain their data. */
int md_ipc_database_blob_column(struct md_ipc_database *,const char *table,const char *column);
/* Success retains the gate for schema/admission; failure closes partial state.
 * Only a committed owner registration sets admitted, never a created lease. */
int md_ipc_database_open(struct md_ipc_database *,const char *store,const char *kind,const char *name);
void md_ipc_database_close(struct md_ipc_database *);
int md_ipc_database_begin(struct md_ipc_database *);
/* Commit/rollback retains the gate for provider-specific post-commit cleanup. */
int md_ipc_database_commit(struct md_ipc_database *,int error);
void md_ipc_database_unlock(struct md_ipc_database *);
int md_ipc_database_boot(struct md_ipc_database *,const char *reset);
/* An exported lease is an OFD lock: SCM_RIGHTS/dup retain ownership until the
 * final descriptor closes. Caller holds the database gate from creation through
 * registration, so recovery cannot collect a not-yet-locked owner file. */
int md_ipc_database_lease(struct md_ipc_database *,int64_t *owner);
void md_ipc_database_discard_lease(struct md_ipc_database *,int64_t owner,int fd);
/* Admission-only recovery of a crash between lease creation and registration.
 * Caller holds the gate; select_owner binds ?1 and returns a row for live metadata. */
int md_ipc_database_sweep_leases(struct md_ipc_database *,const char *select_owner);
int md_ipc_database_reap(struct md_ipc_database *,const char *select_owners,
    int (*remove)(void *,int64_t),void *context);
#endif
