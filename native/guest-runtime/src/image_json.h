#ifndef MD_IMAGE_JSON_H
#define MD_IMAGE_JSON_H
#include <sqlite3.h>
#include <stddef.h>
int md_json_validate(sqlite3 *, const char *, size_t);
int md_json_query(sqlite3 *, const char *, const char *, sqlite3_stmt **);
int md_json_string(sqlite3 *, const char *, const char *, char **);
int md_json_array(sqlite3 *, const char *, const char *, char ***, size_t *);
void md_json_array_free(char **, size_t);
#endif
