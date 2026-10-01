#define _GNU_SOURCE
#include "image_io.h"
#include "image_json.h"
#include "image_layer.h"
#include "image_launch.h"
#include "image_oci.h"
#include "image_publish.h"
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
static int import(const char *source, const char *destination, const char *reference) {
    int layout = open(source, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (layout < 0) return -errno;
    int blobs_root = openat(layout, "blobs", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    int r = blobs_root < 0 ? -errno : 0;
    int blobs = -1;
    if (!r && (blobs = openat(blobs_root, "sha256", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)) < 0) r = -errno;
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
        if (!r) r = md_image_layer(store, fd, &bytes, &entries);
        if (fd >= 0) close(fd);
        md_image_descriptor_free(&d);
        ++layers;
    }
    sqlite3_finalize(q);
    if (!r && layers != diff_count) r = -EINVAL;
    if (!r && layers) r = md_image_layers_finish(store);
    if (!r) r = save(store->root, "image-config.json", oci.config);
    if (!r) r = save(store->root, "image-manifest.json", oci.manifest);
    if (!r) r = md_inode_store_seal(store);
    if (!r && fsync(store->root)) r = -errno;
    md_inode_store_close(store);
    if (json) sqlite3_close(json);
    if (!r) r = md_image_publish_commit(&publish);
    if (!r) printf("Imported %s layers=%zu entries=%llu bytes=%llu identity=current\n", oci.identity.digest,
        layers, (unsigned long long)entries, (unsigned long long)bytes);
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
        if (fd < 0) { r = -errno; break; }
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
    const char *reference = NULL;
    int r = -EINVAL;
    if ((argc == 5 || argc == 7) && !strcmp(argv[1], "import") && !strcmp(argv[4], "--map-current-user")) {
        if (argc == 7 && strcmp(argv[5], "--reference")) return 2;
        if (argc == 7) reference = argv[6];
        r = import(argv[2], argv[3], reference);
    } else if (argc == 4 && !strcmp(argv[1], "create")) r = create(argv[2], argv[3]);
    else if (argc == 3 && !strcmp(argv[1], "inspect")) r = md_image_inspect(argv[2]);
    else if (argc >= 3 && !strcmp(argv[1], "run")) r = md_image_launch(argc-2, argv+2);
    else fprintf(stderr, "Usage: image import OCI_LAYOUT NEW_IMAGE --map-current-user [--reference TAG]\n"
        "       image create IMAGE NEW_INSTANCE\n"
        "       image inspect IMAGE_OR_INSTANCE\n"
        "       image run INSTANCE [--user current] [--cwd PATH] [--entrypoint PROGRAM] [--env KEY=VALUE]\n"
        "             [--bind HOST GUEST] [--bind-ro HOST GUEST] [-- COMMAND...]\n");
    if (r) fprintf(stderr, "Guest image: %s (errno=%d)\n", strerror(-r), -r);
    return r ? 1 : 0;
}
