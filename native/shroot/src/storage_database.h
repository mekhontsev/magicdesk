#ifndef MD_STORAGE_DATABASE_H
#define MD_STORAGE_DATABASE_H
#include <sqlite3.h>

/* Native-owner startup only, before connections or worker threads. The guest
 * environment is not a host filesystem location. NULL restores SQLite defaults. */
int md_database_temporary_directory(const char *);
int md_database_result(int sqlite_result);
/* Convert immediately, before another SQLite call replaces the native cause. */
int md_database_error(sqlite3 *, int sqlite_result);
/* SQLITE_ROW/SQLITE_DONE on progress, otherwise negative errno. */
int md_database_step(sqlite3_stmt *);
/* Caller retains the directory until after sqlite3_close(). Connections are
 * single-thread-owned; SQLite keeps native locking and journal semantics.
 * A failed open never returns a partially initialized connection. */
int md_database_open(int directory,const char *name,int create,sqlite3 **out);
int md_database_open_readonly(int directory,const char *name,sqlite3 **out);
#endif
