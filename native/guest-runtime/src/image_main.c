#define _GNU_SOURCE
#include "image_io.h"
#include "image_json.h"
#include "image_layer.h"
#include "image_launch.h"
#include "image_oci.h"
#include "image_publish.h"
#include "image_pool.h"
#include "image_backup.h"
#include "image_maintenance.h"
#include "inode_internal.h"
#include "launch_identity.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int save(int directory, const char *name, const char *json) {
    int fd = openat(directory, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -errno;
    int r = md_image_write(fd, json, strlen(json));
    if (!r && fsync(fd)) r = -errno;
    close(fd); return r;
}
static int import(const char *source, const char *destination, const char *reference, int preserve, const char *pool, const char *blob_path) {
    int layout = open(source, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (layout < 0) return -errno;
    int blobs_root = blob_path ? -1 : openat(layout, "blobs", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    int r = !blob_path && blobs_root < 0 ? -errno : 0;
    int blobs = -1;
    if (!r) {
        blobs = blob_path ? open(blob_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
            : openat(blobs_root, "sha256", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (blobs < 0) r = -errno;
    }
    struct md_image_publish publish = {.parent=-1, .stage=-1};
    if (!r) r = md_image_publish_begin(destination, &publish);
    struct md_inode_store *store = NULL;
    if (!r) r = md_inode_store_open(publish.path, 1, &store);
    sqlite3 *json = NULL;
    if (!r && sqlite3_open(":memory:", &json) != SQLITE_OK) r = -ENOMEM;
    struct md_image_oci oci = {0};
    if (!r) r = md_image_oci_read(json, layout, blobs, publish.stage, reference, &oci);
    char **diffs = NULL; size_t diff_count = 0;
    if (!r) r = md_json_array(json, oci.config, "$.rootfs.diff_ids", &diffs, &diff_count);
    sqlite3_stmt *q = NULL;
    if (!r) r = md_json_query(json, oci.manifest, "SELECT json_type(?1,'$.layers')", &q);
    if (!r && (sqlite3_step(q) != SQLITE_ROW || sqlite3_column_type(q, 0) != SQLITE_TEXT
            || strcmp((const char *)sqlite3_column_text(q, 0), "array"))) r = -EINVAL;
    sqlite3_finalize(q); q = NULL;
    if (!r) r = md_json_query(json, oci.manifest, "SELECT type,value FROM json_each(?1,'$.layers')", &q);
    uint64_t bytes = 0, entries = 0;
    size_t layers = 0;
    while (!r) {
        int rc = sqlite3_step(q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW || strcmp((const char *)sqlite3_column_text(q, 0), "object") || layers >= diff_count) {
            r = -EINVAL; break;
        }
        struct md_image_descriptor d = {0};
        r = md_image_descriptor_read(json, (const char *)sqlite3_column_text(q, 1), &d);
        int fd = -1;
        if (!r) r = md_image_unpack(blobs, publish.stage, &d, diffs[layers], &fd);
        struct md_image_pool shared = {.root=-1, .objects=-1};
        if (!r && pool) r = md_image_pool_open(pool, diffs[layers], fd, &shared);
        if (!r) r = pool ? md_image_layer_shared(store, fd, shared.objects, &bytes, &entries)
            : md_image_layer(store, fd, &bytes, &entries);
        md_image_pool_close(&shared);
        if (fd >= 0) close(fd);
        md_image_descriptor_free(&d);
        ++layers;
    }
    sqlite3_finalize(q);
    if (!r && layers != diff_count) r = -EINVAL;
    if (!r) r = md_image_layers_finish(store, preserve);
    if (!r) r = save(store->root, "image-config.json", oci.config);
    if (!r) r = save(store->root, "image-manifest.json", oci.manifest);
    if (!r) r = md_inode_store_seal(store);
    if (!r && fsync(store->root)) r = -errno;
    md_inode_store_close(store);
    if (json) sqlite3_close(json);
    if (!r) r = md_image_publish_commit(&publish);
    if (!r) printf("Imported %s layers=%zu entries=%llu bytes=%llu identity=%s\n", oci.identity.digest,
        layers, (unsigned long long)entries, (unsigned long long)bytes, preserve ? "guest" : "current");
    md_json_array_free(diffs, diff_count); md_image_oci_free(&oci);
    md_image_publish_close(&publish);
    if (blobs >= 0) close(blobs);
    if (blobs_root >= 0) close(blobs_root);
    close(layout); return r;
}
static int create(const char *source, const char *destination) {
    struct md_inode_store *image = NULL, *instance = NULL;
    int r = md_inode_store_open(source, 0, &image);
    struct md_image_publish publish = {.parent=-1, .stage=-1};
    if (!r) r = md_image_publish_begin(destination, &publish);
    if (!r) r = md_inode_store_open(publish.path, 1, &instance);
    if (!r) r = md_inode_snapshot(image, instance);
    const char *files[] = {"image-config.json", "image-manifest.json"};
    for (unsigned i = 0; !r && i < sizeof(files)/sizeof(*files); ++i) {
        int fd = openat(image->root, files[i], O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) { if (i && errno == ENOENT) continue; r = -errno; break; }
        char *json = NULL;
        r = md_image_read(fd, 8*1024*1024, &json);
        if (!r) r = save(instance->root, files[i], json);
        free(json); close(fd);
    }
    if (!r && fsync(instance->root)) r = -errno;
    md_inode_store_close(instance); md_inode_store_close(image);
    if (!r) r = md_image_publish_commit(&publish);
    md_image_publish_close(&publish);
    if (!r) printf("Created instance %s\n", destination);
    return r;
}
int main(int argc, char **argv) {
#ifndef MD_INODE_TESTING
    if (!md_launch_identity(getuid(), geteuid(), getgid(), getegid())) return 2;
#endif
    umask(0);
    const char *reference = NULL, *pool = NULL, *blobs = NULL;
    int r = -EINVAL;
    if (argc >= 5 && !strcmp(argv[1], "import")
            && (!strcmp(argv[4], "--map-current-user") || !strcmp(argv[4], "--preserve-ownership"))) {
        for (int i=5; i<argc; i+=2) {
            if (i+1 == argc) return 2;
            if (!strcmp(argv[i], "--reference") && !reference) reference = argv[i+1];
            else if (!strcmp(argv[i], "--layers") && !pool) pool = argv[i+1];
            else if (!strcmp(argv[i], "--blobs") && !blobs) blobs = argv[i+1];
            else return 2;
        }
        r = import(argv[2], argv[3], reference, !strcmp(argv[4], "--preserve-ownership"), pool, blobs);
    } else if (argc == 4 && !strcmp(argv[1], "create")) r = create(argv[2], argv[3]);
    else if (argc == 4 && !strcmp(argv[1], "backup")) r = md_image_backup(argv[2], argv[3]);
    else if (argc == 4 && !strcmp(argv[1], "restore")) r = md_image_archive_import(argv[2], argv[3], 1);
    else if (argc == 4 && !strcmp(argv[1], "rootfs")) r = md_image_archive_import(argv[2], argv[3], 0);
    else if (argc == 3 && !strcmp(argv[1], "remove")) r = md_image_remove(argv[2], 0);
    else if (argc == 3 && !strcmp(argv[1], "remove-layer")) r = md_image_remove(argv[2], 1);
    else if (argc == 3 && !strcmp(argv[1], "inspect")) r = md_image_inspect(argv[2]);
    else if (argc >= 3 && !strcmp(argv[1], "run")) r = md_image_launch(MD_IMAGE_RUN, argc-2, argv+2);
    else if (argc >= 3 && !strcmp(argv[1], "exec")) r = md_image_launch(MD_IMAGE_EXEC, argc-2, argv+2);
    else if (argc >= 3 && !strcmp(argv[1], "login")) r = md_image_launch(MD_IMAGE_LOGIN, argc-2, argv+2);
    else fprintf(stderr, "Usage: image import OCI_LAYOUT NEW_IMAGE (--map-current-user|--preserve-ownership) [--reference TAG] [--layers DIRECTORY] [--blobs DIRECTORY]\n"
        "       image create IMAGE NEW_INSTANCE\n"
        "       image rootfs ARCHIVE NEW_IMAGE\n"
        "       image backup STORE NEW_ARCHIVE\n"
        "       image restore ARCHIVE NEW_INSTANCE\n"
        "       image remove STORE | remove-layer LAYER (caller verifies persistent dependents)\n"
        "       image inspect IMAGE_OR_INSTANCE\n"
        "       image exec INSTANCE [OPTIONS] -- COMMAND... | login INSTANCE [OPTIONS] [-- SHELL...]\n"
        "       image run INSTANCE [--user current|UID[:GID]|NAME[:GROUP]] [--cwd PATH] [--entrypoint PROGRAM] [--env KEY=VALUE]\n"
        "             [--hostname NAME] [--bind HOST GUEST] [--bind-ro HOST GUEST] [-- COMMAND...]\n");
    if (r) fprintf(stderr, "Guest image: %s (errno=%d)\n", strerror(-r), -r);
    return r ? 1 : 0;
}
