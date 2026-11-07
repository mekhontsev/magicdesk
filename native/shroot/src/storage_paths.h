#ifndef MD_STORAGE_PATHS_H
#define MD_STORAGE_PATHS_H
#include <stddef.h>
#include <sqlite3.h>

int md_storage_directory_path(int directory, char *path, size_t size);
int md_storage_relative_path(const char *directory, const char *target, char *path, size_t size);
/* Caller owns the metadata transaction and keeps both roots stable. */
int md_storage_sources_rebase(sqlite3 *, const char *previous, const char *destination);
#endif
