#include "launch_registry.h"
#include <errno.h>
#include <stdio.h>
#include <sqlite3.h>

static int emit(const char *id, const struct md_launch_info *info, void *context) {
    sqlite3_stmt *q = context;
    sqlite3_bind_text(q, 1, id, -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(q, 2, info->pid);
    sqlite3_bind_int64(q, 3, info->executor_uid);
    sqlite3_bind_int64(q, 4, info->guest_uid);
    sqlite3_bind_text(q, 5, info->program, -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(q, 6, info->cwd, -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(q, 7, info->label, -1, SQLITE_TRANSIENT);
    int r = sqlite3_step(q) == SQLITE_ROW ? 0 : -EIO;
    if (!r) puts((const char *)sqlite3_column_text(q, 0));
    sqlite3_reset(q); return r;
}
int md_image_launches(const char *store) {
    sqlite3 *db = NULL; sqlite3_stmt *q = NULL;
    int r = sqlite3_open(":memory:", &db) == SQLITE_OK ? 0 : -ENOMEM;
    if (!r && sqlite3_prepare_v2(db, "SELECT json_object('launchId',?1,'pid',?2,'executorUid',?3,"
            "'guestUid',?4,'program',?5,'cwd',?6,'label',?7)", -1, &q, NULL) != SQLITE_OK) r = -EIO;
    if (!r) r = md_launch_list(store, emit, q);
    sqlite3_finalize(q); sqlite3_close(db); return r;
}
