#define _GNU_SOURCE
#include "image_json.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>

int md_json_query(sqlite3 *db, const char *document, const char *sql, sqlite3_stmt **out) {
    *out = NULL;
    if (sqlite3_prepare_v2(db, sql, -1, out, NULL) != SQLITE_OK) return -EINVAL;
    if (sqlite3_bind_text(*out, 1, document, -1, SQLITE_STATIC) != SQLITE_OK) {
        sqlite3_finalize(*out); *out = NULL; return -ENOMEM;
    }
    return 0;
}
int md_json_validate(sqlite3 *db, const char *document, size_t size) {
    if (size > 8 * 1024 * 1024 || memchr(document, 0, size)) return -EINVAL;
    sqlite3_stmt *q = NULL;
    int r = md_json_query(db, document, "SELECT json_valid(?1)", &q);
    if (!r && (sqlite3_step(q) != SQLITE_ROW || !sqlite3_column_int(q, 0))) r = -EINVAL;
    sqlite3_finalize(q); q = NULL;
    if (!r) r = md_json_query(db, document,
        "SELECT 1 FROM json_tree(?1) t WHERE "
        "(type='text' AND instr(atom,char(0))>0) OR "
        "(typeof(key)='text' AND instr(key,char(0))>0) UNION ALL "
        "SELECT 1 FROM json_tree(?1) WHERE typeof(key)='text' GROUP BY parent,key HAVING count(*)>1", &q);
    if (!r && sqlite3_step(q) != SQLITE_DONE) r = -EINVAL;
    sqlite3_finalize(q); return r;
}
int md_json_string(sqlite3 *db, const char *document, const char *path, char **out) {
    *out = NULL;
    sqlite3_stmt *q = NULL;
    int r = md_json_query(db, document, "SELECT json_type(?1,?2),json_extract(?1,?2)", &q);
    if (!r && sqlite3_bind_text(q, 2, path, -1, SQLITE_STATIC) != SQLITE_OK) r = -ENOMEM;
    if (!r) {
        int rc = sqlite3_step(q);
        const char *type = rc == SQLITE_ROW ? (const char *)sqlite3_column_text(q, 0) : NULL;
        if (rc != SQLITE_ROW) r = -EINVAL;
        else if (!type || !strcmp(type, "null")) r = -ENOENT;
        else if (strcmp(type, "text")) r = -EINVAL;
        else if (!(*out = strdup((const char *)sqlite3_column_text(q, 1)))) r = -ENOMEM;
    }
    sqlite3_finalize(q); return r;
}
void md_json_array_free(char **values, size_t count) {
    for (size_t i = 0; i < count; ++i) free(values[i]);
    free(values);
}
int md_json_array(sqlite3 *db, const char *document, const char *path, char ***out, size_t *count) {
    *out = NULL; *count = 0;
    sqlite3_stmt *q = NULL;
    int absent = 0;
    int r = md_json_query(db, document, "SELECT json_type(?1,?2)", &q);
    if (!r) sqlite3_bind_text(q, 2, path, -1, SQLITE_STATIC);
    if (!r) {
        int rc = sqlite3_step(q);
        const char *type = rc == SQLITE_ROW ? (const char *)sqlite3_column_text(q, 0) : NULL;
        if (rc != SQLITE_ROW || (type && strcmp(type, "null") && strcmp(type, "array"))) r = -EINVAL;
        absent = !type || !strcmp(type, "null");
    }
    sqlite3_finalize(q); q = NULL;
    if (r || absent) return r;
    if (!r) r = md_json_query(db, document, "SELECT type,value FROM json_each(?1,?2)", &q);
    if (!r) sqlite3_bind_text(q, 2, path, -1, SQLITE_STATIC);
    size_t bytes = 0;
    while (!r) {
        int rc = sqlite3_step(q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW || strcmp((const char *)sqlite3_column_text(q, 0), "text")) { r = -EINVAL; break; }
        const char *text = (const char *)sqlite3_column_text(q, 1);
        bytes += strlen(text) + 1;
        if (*count == 900 || bytes > 65536) { r = -E2BIG; break; }
        char **more = realloc(*out, (*count + 2) * sizeof(**out));
        if (!more) { r = -ENOMEM; break; }
        *out = more;
        more[*count] = strdup(text);
        if (!more[*count]) { r = -ENOMEM; break; }
        more[++*count] = NULL;
    }
    sqlite3_finalize(q);
    if (r) { md_json_array_free(*out, *count); *out = NULL; *count = 0; }
    return r;
}
