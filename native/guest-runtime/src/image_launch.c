#define _GNU_SOURCE
#include "linux_abi.h"
#include "image_launch.h"
#include "image_io.h"
#include "image_json.h"
#include "inode_internal.h"
#include "fs_mounts.h"
#include "guest_accounts.h"
#include "host_identity.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static int config(struct md_inode_store *s, char **json) {
    int fd = openat(s->root, "image-config.json", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -errno;
    int r = md_image_read(fd, 8*1024*1024, json);
    close(fd);
    if (!r) r = md_json_validate(s->db, *json, strlen(*json));
    return r;
}
int md_image_inspect(const char *path) {
    struct md_inode_store *s = NULL;
    int r = md_inode_store_open(path, 0, &s);
    char *json = NULL, *result = NULL;
    if (!r) r = mdi_begin(s, 0);
    if (!r) {
        r = config(s, &json);
        sqlite3_stmt *q = NULL;
        if (!r) r = md_json_query(s->db, json, "SELECT json_object('kind',?2,'config',json(?1),'sources',"
            "(SELECT json_group_array(json_object('path',path,'device',device,'inode',inode)) FROM sources),"
            "'guestUsers',(SELECT value FROM properties WHERE key='image-users'))", &q);
        if (!r) sqlite3_bind_text(q, 2, s->readonly ? "image" : "instance", -1, SQLITE_STATIC);
        if (!r) {
            int rc = sqlite3_step(q);
            if (rc != SQLITE_ROW) r = mdi_sql_failure(rc);
            else if (!(result = strdup((const char *)sqlite3_column_text(q, 0)))) r = -ENOMEM;
        }
        sqlite3_finalize(q);
        r = mdi_finish(s, r);
    }
    if (!r && puts(result) == EOF) r = -EIO;
    free(result); free(json); md_inode_store_close(s); return r;
}
static int optional(sqlite3 *db, const char *json, const char *path, char **out) {
    int r = md_json_string(db, json, path, out);
    return r == -ENOENT ? 0 : r;
}
static const char *environment(char **entries, size_t count, const char *name) {
    size_t n = strlen(name);
    for (size_t i = count; i; --i) if (!strncmp(entries[i-1], name, n) && entries[i-1][n] == '=') return entries[i-1]+n+1;
    return NULL;
}
static int program_path(struct md_filesystem *fs, const char *command, const char *cwd, const char *path, char result[PATH_MAX]) {
    if (!command || !*command) return -ENOENT;
    if (strchr(command, '/')) {
        int n = command[0] == '/' ? snprintf(result, PATH_MAX, "%s", command)
            : snprintf(result, PATH_MAX, "%s/%s", cwd, command);
        return n < 0 || n >= PATH_MAX ? -ENAMETOOLONG : 0;
    }
    if (!path) path = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    int denied = 0;
    for (;;) {
        const char *end = strchr(path, ':'); size_t n = end ? (size_t)(end-path) : strlen(path);
        if (n >= PATH_MAX) return -ENAMETOOLONG;
        char directory[PATH_MAX]; memcpy(directory, path, n); directory[n] = 0;
        int size = directory[0] == '/' ? snprintf(result, PATH_MAX, "%s/%s", directory, command)
            : snprintf(result, PATH_MAX, "%s/%s%s%s", cwd, directory, n ? "/" : "", command);
        if (size < 0 || size >= PATH_MAX) return -ENAMETOOLONG;
        struct md_fs_request request = {.operation=MD_FS_OPEN,.directory={-1,-1},.path={result,NULL},.flags=O_PATH | O_CLOEXEC};
        struct md_fs_result opened;
        md_fs_execute(fs, &request, &opened, NULL);
        int fd = opened.error ? opened.error : opened.fd;
        if (fd >= 0) {
            struct stat st;
            int r = fstat(fd, &st) ? -errno : 0;
            if (!r && !S_ISREG(st.st_mode)) r = -EACCES;
            if (!r) {
                struct md_fs_request access = {.operation=MD_FS_ACCESS,.directory={fd,-1},.mode=X_OK,.flags=MD_AT_EACCESS};
                struct md_fs_result permission;
                md_fs_execute(fs,&access,&permission,NULL); r=permission.error;
                if (r == -EXDEV) r=syscall(SYS_faccessat2,fd,"",X_OK,MD_AT_EACCESS|AT_EMPTY_PATH) ? -errno : 0;
            }
            close(fd);
            if (!r) return 0;
            if (r != -EACCES) return r;
            denied = 1;
        } else if (fd == -EACCES) denied = 1;
        else if (fd != -ENOENT && fd != -ENOTDIR) return fd;
        if (!end) break;
        path = end+1;
    }
    return denied ? -EACCES : -ENOENT;
}
int md_image_launch(enum md_image_command kind, int argc, char **argv) {
    if (argc < 1) return -EINVAL;
    const char *path = argv[0], *cwd_override = NULL, *entry_override = NULL;
    char *overrides[128]; size_t override_count = 0;
    struct md_fs_attachment attachments[MD_FS_MOUNTS_MAX]; unsigned attachment_count = 0;
    const char *selected_user = NULL;
    const char *hostname_override = NULL;
    int position = 1;
    while (position < argc && strcmp(argv[position], "--")) {
        if (!strcmp(argv[position], "--bind") || !strcmp(argv[position], "--bind-ro")) {
            if (position+2 >= argc || attachment_count == MD_FS_MOUNTS_MAX) return -EINVAL;
            attachments[attachment_count++] = (struct md_fs_attachment){.source=argv[position+1],
                .target=argv[position+2], .readonly=!strcmp(argv[position], "--bind-ro")};
            position += 3; continue;
        }
        if (position+1 >= argc) return -EINVAL;
        const char *option = argv[position++]; char *value = argv[position++];
        if (!strcmp(option, "--user") && !selected_user && *value) selected_user = value;
        else if (!strcmp(option, "--cwd") && value[0] == '/') cwd_override = value;
        else if (!strcmp(option, "--entrypoint") && kind == MD_IMAGE_RUN) entry_override = value;
        else if (!strcmp(option, "--hostname") && !hostname_override && md_hostname_valid(value)) hostname_override = value;
        else if (!strcmp(option, "--env") && override_count < 128) overrides[override_count++] = value;
        else return -EINVAL;
    }
    if (position < argc) ++position;
    if (kind == MD_IMAGE_EXEC && position == argc) return -EINVAL;
    if (kind != MD_IMAGE_RUN) entry_override = "";
    struct md_inode_store *s = NULL;
    int r = md_inode_store_open(path, 0, &s);
    if (!r && s->readonly) r = -EROFS;
    char hostname[MD_HOSTNAME_SIZE];
    struct stat instance;
    if (!r && fstat(s->root, &instance)) r = -errno;
    if (!r) snprintf(hostname, sizeof(hostname), "md-%llx-%llx",
        (unsigned long long)instance.st_dev, (unsigned long long)instance.st_ino);
    struct md_filesystem fs = {.store=s};
    if (!r) r = md_fs_mounts_open(&fs, attachments, attachment_count);
    char *json = NULL, *cwd = NULL, *user = NULL;
    char **entries = NULL, **cmd = NULL, **env = NULL;
    size_t entry_count = 0, cmd_count = 0, env_count = 0;
    int preserve = 0;
    if (!r) r = mdi_begin(s, 0);
    if (!r) {
        r = config(s, &json);
        if (!r) r = optional(s->db, json, "$.config.WorkingDir", &cwd);
        if (!r) r = optional(s->db, json, "$.config.User", &user);
        sqlite3_stmt *policy = NULL;
        if (!r) r = mdi_prepare(s,"SELECT value FROM properties WHERE key='image-users'",&policy);
        if (!r) {
            int rc=mdi_step(s,policy);
            if (rc==SQLITE_ROW) preserve=sqlite3_column_int(policy,0)!=0;
            else r=mdi_sql_failure(rc);
        }
        sqlite3_finalize(policy);
        if (!r) r = md_json_array(s->db, json, "$.config.Entrypoint", &entries, &entry_count);
        if (!r) r = md_json_array(s->db, json, "$.config.Cmd", &cmd, &cmd_count);
        if (!r) r = md_json_array(s->db, json, "$.config.Env", &env, &env_count);
        r = mdi_finish(s, r);
    }
    if (!r && user && *user && !selected_user && !preserve) {
        fprintf(stderr, "Image USER=%s has mapped ownership; select --user current or an explicit guest user.\n", user);
        r = -ENOTSUP;
    }
    struct md_identity identity = {0};
    char identity_text[32], *group_text = NULL;
    int guest_user = selected_user ? strcmp(selected_user,"current")!=0 : preserve;
    if (!r && guest_user) {
        r=md_guest_user_resolve(&fs,selected_user ? selected_user : user,&identity);
        if (!r) {
            snprintf(identity_text,sizeof(identity_text),"%u:%u",identity.uid.real,identity.gid.real);
            r=md_guest_group_argument(&identity,&group_text);
            s->identity=&identity;
        }
    }
    const char *working = cwd_override ? cwd_override : cwd && *cwd ? cwd : "/";
    if (!r && working[0] != '/') r = -EINVAL;
    if (!r && entry_override) { md_json_array_free(entries, entry_count); entries = NULL; entry_count = 0; }
    size_t explicit_count = entry_override && *entry_override ? 1 : 0;
    char **command = position < argc ? argv+position : cmd;
    size_t command_count = position < argc ? (size_t)(argc-position) : cmd_count;
    char *login[] = {"/bin/sh", "-l"};
    if (kind == MD_IMAGE_LOGIN && position == argc) { command = login; command_count = 2; }
    size_t total = entry_count + explicit_count + command_count;
    if (!r && (!total || total + 2*(env_count+override_count) + 3*attachment_count + 16 > 1000)) r = -E2BIG;
    const char *search = environment(overrides, override_count, "PATH");
    if (!search) search = environment(env, env_count, "PATH");
    char executable[PATH_MAX], runner[PATH_MAX];
    const char *first = explicit_count ? entry_override : entry_count ? entries[0] : command_count ? command[0] : NULL;
    if (!r) r = program_path(&fs, first, working, search, executable);
    if (!r) {
        ssize_t n = readlink("/proc/self/exe", runner, sizeof(runner)-1);
        if (n <= 0 || n >= (ssize_t)sizeof(runner)-1) r = -ENAMETOOLONG;
        else {
            runner[n] = 0; char *last = strrchr(runner, '/');
            if (!last || (size_t)(last-runner)+sizeof("/libmagicdesk_guest_run.so") > sizeof(runner)) r = -ENAMETOOLONG;
            else strcpy(last, "/libmagicdesk_guest_run.so");
        }
    }
    char **launch = !r ? calloc(1001, sizeof(*launch)) : NULL;
    if (!r && !launch) r = -ENOMEM;
    if (!r) {
        size_t n = 0;
        launch[n++] = runner; launch[n++] = "--store"; launch[n++] = (char *)path;
        launch[n++] = "--cwd"; launch[n++] = (char *)working;
        launch[n++] = "--hostname"; launch[n++] = (char *)(hostname_override ? hostname_override : hostname);
        if (guest_user) {
            launch[n++]="--user"; launch[n++]=identity_text;
            launch[n++]="--groups"; launch[n++]=group_text;
        }
        for (unsigned i = 0; i < attachment_count; ++i) {
            launch[n++] = attachments[i].readonly ? "--bind-ro" : "--bind";
            launch[n++] = (char *)attachments[i].source; launch[n++] = (char *)attachments[i].target;
        }
        for (size_t i = 0; i < env_count; ++i) { launch[n++] = "--env"; launch[n++] = env[i]; }
        for (size_t i = 0; i < override_count; ++i) { launch[n++] = "--env"; launch[n++] = overrides[i]; }
        launch[n++] = "--"; size_t start = n;
        if (explicit_count) launch[n++] = (char *)entry_override;
        for (size_t i = 0; i < entry_count; ++i) launch[n++] = entries[i];
        for (size_t i = 0; i < command_count; ++i) launch[n++] = command[i];
        launch[start] = executable;
        /* The ordinary launch guardian owns the process tree and cancellation. */
        md_fs_mounts_close(&fs); md_inode_store_close(s); s = NULL;
        execv(runner, launch); r = -errno;
    }
    free(launch); free(json); free(cwd); free(user);
    free(group_text); md_identity_release(&identity);
    md_json_array_free(entries, entry_count); md_json_array_free(cmd, cmd_count); md_json_array_free(env, env_count);
    md_fs_mounts_close(&fs); md_inode_store_close(s); return r;
}
