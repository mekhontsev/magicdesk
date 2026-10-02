#define _GNU_SOURCE
#include "image_launch.h"
#include "image_io.h"
#include "image_json.h"
#include "inode_internal.h"
#include "fs_engine.h"
#include "guest_accounts.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int emit(struct md_inode_store *s, const char *path, size_t *total) {
    struct stat node;
    int status = md_inode_stat(s, -1, path, 0, &node);
    if (status == -ENOENT || status == -EACCES) return 0;
    if (status) return status;
    if (!S_ISREG(node.st_mode)) return 0;
    int fd = md_inode_open(s, -1, path, O_RDONLY | O_NONBLOCK | O_CLOEXEC, 0);
    if (fd == -ENOENT || fd == -EACCES) return 0;
    if (fd < 0) return fd;
    struct stat st;
    int r = fstat(fd, &st) ? -errno : 0;
    char *text = NULL;
    if (!r && S_ISREG(st.st_mode) && st.st_size <= 65536) r = md_image_read(fd, 65536, &text);
    close(fd);
    if (!text) return r;
    *total += strlen(text);
    if (*total > 2*1024*1024) r = -E2BIG;
    sqlite3_stmt *q = NULL;
    if (!r && sqlite3_prepare_v2(s->db, "SELECT json_object('path',?1,'text',?2)", -1, &q, NULL) != SQLITE_OK) r = -EIO;
    if (!r) {
        sqlite3_bind_text(q, 1, path, -1, SQLITE_STATIC); sqlite3_bind_text(q, 2, text, -1, SQLITE_STATIC);
        if (sqlite3_step(q) != SQLITE_ROW || puts((const char *)sqlite3_column_text(q, 0)) == EOF) r = -EIO;
    }
    sqlite3_finalize(q); free(text); return r;
}
static int scan(struct md_inode_store *s, const char *path, unsigned depth, unsigned *count, size_t *bytes) {
    if (depth > 8) return -E2BIG;
    int fd = md_inode_open(s, -1, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (fd == -ENOENT || fd == -EACCES) return 0;
    if (fd < 0) return fd;
    char buffer[8192]; int r = 0;
    while (!r) {
        ssize_t n = md_inode_getdents(s, fd, buffer, sizeof(buffer));
        if (n <= 0) { r = (int)n; break; }
        for (size_t at = 0; !r && at < (size_t)n;) {
            struct md_inode_dirent *e = (void *)(buffer+at);
            if (!e->size || e->size > (size_t)n-at) { r = -EIO; break; }
            at += e->size;
            if (!strcmp(e->name, ".") || !strcmp(e->name, "..")) continue;
            if (++*count > 4096) { r = -E2BIG; break; }
            char child[PATH_MAX];
            if (snprintf(child, sizeof(child), "%s/%s", path, e->name) >= (int)sizeof(child)) { r = -ENAMETOOLONG; break; }
            struct stat st;
            r = md_inode_stat(s, fd, e->name, AT_SYMLINK_NOFOLLOW, &st);
            if (r == -ENOENT) { r = 0; continue; }
            if (!r && S_ISDIR(st.st_mode)) r = scan(s, child, depth+1, count, bytes);
            else if (!r && strlen(e->name) > 8 && !strcmp(e->name+strlen(e->name)-8, ".desktop")) r = emit(s, child, bytes);
        }
    }
    close(fd); return r;
}
static int icon(struct md_inode_store *s, const char *path) {
    struct stat node;
    int status = md_inode_stat(s, -1, path, 0, &node);
    if (status) return status;
    if (!S_ISREG(node.st_mode)) return -EINVAL;
    int fd = md_inode_open(s, -1, path, O_RDONLY | O_NONBLOCK | O_CLOEXEC, 0);
    if (fd < 0) return fd;
    struct stat st; unsigned char data[16384];
    int r = fstat(fd, &st) ? -errno : 0;
    if (!r && (!S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size > (off_t)sizeof(data))) r = -EFBIG;
    if (!r && pread(fd, data, st.st_size, 0) != st.st_size) r = -EIO;
    close(fd);
    if (!r && (st.st_size < 8 || memcmp(data, "\211PNG\r\n\032\n", 8))) r = -EINVAL;
    if (!r) { for (off_t i = 0; i < st.st_size; ++i) printf("%02x", data[i]); putchar('\n'); }
    return r;
}
int md_image_applications(const char *store, const char *name) {
    struct md_inode_store *s = NULL;
    int r = md_inode_store_open(store, 0, &s), guest = 0;
    struct md_filesystem fs = {.store=s};
    struct md_identity identity = {0}; struct md_guest_account account = {0};
    char *json = NULL;
    if (!r) r = md_image_config_read(&fs, &json);
    if (!r) r = md_image_config_account(&fs, json, NULL, &identity, &account, &guest);
    char **env = NULL; size_t env_count = 0;
    if (!r) r = md_json_array(s->db, json, "$.config.Env", &env, &env_count);
    const char *user_home = md_image_environment_value(env, env_count, "HOME");
    if (!user_home || *user_home != '/') user_home = account.home;
    const char *data_home = md_image_environment_value(env, env_count, "XDG_DATA_HOME");
    char home[PATH_MAX];
    if (!r && snprintf(home, sizeof(home), "%s/.local/share", user_home) >= (int)sizeof(home)) r = -ENAMETOOLONG;
    const char *directories = md_image_environment_value(env, env_count, "XDG_DATA_DIRS");
    char *dirs = strdup(directories && *directories ? directories : "/usr/local/share:/usr/share");
    if (!dirs && !r) r = -ENOMEM;
    const char *roots[33] = {data_home && *data_home == '/' ? data_home : home};
    unsigned root_count = 1;
    char *cursor = dirs, *part;
    while (!r && (part = strsep(&cursor, ":"))) {
        if (*part != '/') continue;
        if (root_count == 33) r = -E2BIG;
        else roots[root_count++] = part;
    }
    if (!r && name) {
        if (*name == '/') { if (icon(s, name)) puts(""); }
        else if (strlen(name) > 1024 || strchr(name, '/')) r = -EINVAL;
        else {
            int found = 0; const int sizes[] = {96,64,48,128,32,256,24,16};
            for (unsigned root = 0; root < root_count && !found; ++root) {
                char path[PATH_MAX];
                for (unsigned i = 0; i < sizeof(sizes)/sizeof(*sizes) && !found; ++i) {
                    int n = snprintf(path, sizeof(path), "%s/icons/hicolor/%dx%d/apps/%s%s", roots[root], sizes[i], sizes[i],
                        name, strlen(name) >= 4 && !strcmp(name+strlen(name)-4, ".png") ? "" : ".png");
                    if (n > 0 && n < (int)sizeof(path)) found = !icon(s, path);
                }
                int n = snprintf(path, sizeof(path), "%s/pixmaps/%s%s", roots[root], name,
                    strlen(name) >= 4 && !strcmp(name+strlen(name)-4, ".png") ? "" : ".png");
                if (!found && n > 0 && n < (int)sizeof(path)) found = !icon(s, path);
            }
            if (!found) puts("");
        }
    } else if (!r) {
        unsigned count = 0; size_t bytes = 0;
        for (unsigned i = 0; i < root_count && !r; ++i) {
            char path[PATH_MAX];
            if (snprintf(path, sizeof(path), "%s/applications", roots[i]) >= (int)sizeof(path)) r = -ENAMETOOLONG;
            else r = scan(s, path, 0, &count, &bytes);
        }
    }
    md_inode_store_close(s); md_identity_release(&identity); md_json_array_free(env, env_count);
    free(dirs); free(json); return r;
}
