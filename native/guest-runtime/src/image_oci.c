#define _GNU_SOURCE
#include "image_oci.h"
#include "image_io.h"
#include "image_json.h"
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

void md_image_descriptor_free(struct md_image_descriptor *d) { free(d->media); memset(d, 0, sizeof(*d)); }
void md_image_oci_free(struct md_image_oci *o) {
    free(o->manifest); free(o->config); md_image_descriptor_free(&o->identity); memset(o, 0, sizeof(*o));
}
int md_image_descriptor_read(sqlite3 *db, const char *json, struct md_image_descriptor *d) {
    memset(d, 0, sizeof(*d));
    char *digest = NULL;
    int r = md_json_string(db, json, "$.digest", &digest);
    if (!r && !md_image_digest_valid(digest)) r = -EINVAL;
    if (!r) memcpy(d->digest, digest, sizeof(d->digest));
    free(digest);
    if (!r) r = md_json_string(db, json, "$.mediaType", &d->media);
    sqlite3_stmt *q = NULL;
    if (!r) r = md_json_query(db, json, "SELECT json_extract(?1,'$.size')", &q);
    if (!r) {
        if (sqlite3_step(q) != SQLITE_ROW || sqlite3_column_type(q, 0) != SQLITE_INTEGER
                || sqlite3_column_int64(q, 0) < 0 || sqlite3_column_int64(q, 0) > 8LL*1024*1024*1024) r = -EINVAL;
        else d->size = (uint64_t)sqlite3_column_int64(q, 0);
    }
    sqlite3_finalize(q);
    if (r) md_image_descriptor_free(d);
    return r;
}
static int file_json(sqlite3 *db, int directory, const char *name, char **out) {
    int fd = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -errno;
    int r = md_image_read(fd, 8*1024*1024, out);
    close(fd);
    if (!r) r = md_json_validate(db, *out, strlen(*out));
    return r;
}
static int blob_json(sqlite3 *db, int blobs, int scratch, const struct md_image_descriptor *d, char **out) {
    if (d->size > 8*1024*1024) return -EFBIG;
    int fd = -1;
    int r = md_image_blob(blobs, scratch, d->digest, d->size, &fd);
    if (!r) r = md_image_read(fd, 8*1024*1024, out);
    if (!r) r = md_json_validate(db, *out, (size_t)d->size);
    if (fd >= 0) close(fd);
    return r;
}
static int expect(sqlite3 *db, const char *json, const char *path, const char *value) {
    char *text = NULL;
    int r = md_json_string(db, json, path, &text);
    if (!r && strcmp(text, value)) r = -ENOTSUP;
    free(text); return r;
}
static int schema(sqlite3 *db, const char *json) {
    sqlite3_stmt *q = NULL;
    int r = md_json_query(db, json, "SELECT json_extract(?1,'$.schemaVersion')", &q);
    if (!r && (sqlite3_step(q) != SQLITE_ROW || sqlite3_column_type(q, 0) != SQLITE_INTEGER
            || sqlite3_column_int(q, 0) != 2)) r = -EINVAL;
    sqlite3_finalize(q); return r;
}
static int select_manifest(sqlite3 *db, int blobs, int scratch, const char *json, const char *reference,
        unsigned depth, struct md_image_oci *out) {
    if (depth > 4) return -ELOOP;
    int r = schema(db, json);
    sqlite3_stmt *q = NULL;
    /* An index can also contain attestations and other architectures. Missing
     * platform is allowed here; the selected config is always checked below. */
    if (!r) r = md_json_query(db, json,
        "SELECT value FROM json_each(?1,'$.manifests') WHERE type='object' "
        "AND (?2 IS NULL OR json_extract(value,'$.annotations.\"org.opencontainers.image.ref.name\"')=?2) "
        "AND (json_type(value,'$.platform') IS NULL OR "
        "(json_extract(value,'$.platform.os')='linux' AND json_extract(value,'$.platform.architecture')='arm64' "
        "AND coalesce(json_extract(value,'$.platform.variant'),'v8')='v8'))", &q);
    if (!r && reference) sqlite3_bind_text(q, 2, reference, -1, SQLITE_STATIC);
    char *selected = NULL;
    if (!r) {
        int rc = sqlite3_step(q);
        if (rc != SQLITE_ROW) r = rc == SQLITE_DONE ? -ENOENT : -EINVAL;
        else if (!(selected = strdup((const char *)sqlite3_column_text(q, 0)))) r = -ENOMEM;
        if (!r && sqlite3_step(q) != SQLITE_DONE) r = -ENOTUNIQ;
    }
    sqlite3_finalize(q);
    struct md_image_descriptor d = {0};
    if (!r) r = md_image_descriptor_read(db, selected, &d);
    free(selected);
    int index = !r && (!strcmp(d.media, "application/vnd.oci.image.index.v1+json")
        || !strcmp(d.media, "application/vnd.docker.distribution.manifest.list.v2+json"));
    if (!r && !index && strcmp(d.media, "application/vnd.oci.image.manifest.v1+json")
            && strcmp(d.media, "application/vnd.docker.distribution.manifest.v2+json")) r = -ENOTSUP;
    char *document = NULL;
    if (!r) r = blob_json(db, blobs, scratch, &d, &document);
    if (!r && index) r = select_manifest(db, blobs, scratch, document, NULL, depth+1, out);
    if (!r && !index) {
        out->manifest = document; document = NULL;
        out->identity = d; memset(&d, 0, sizeof(d));
    }
    free(document); md_image_descriptor_free(&d); return r;
}
int md_image_oci_read(sqlite3 *db, int layout, int blobs, int scratch, const char *reference,
        struct md_image_oci *out) {
    memset(out, 0, sizeof(*out));
    char *json = NULL;
    int r = file_json(db, layout, "oci-layout", &json);
    if (!r) r = expect(db, json, "$.imageLayoutVersion", "1.0.0");
    free(json); json = NULL;
    if (!r) r = file_json(db, layout, "index.json", &json);
    if (!r) r = select_manifest(db, blobs, scratch, json, reference, 0, out);
    free(json);
    if (!r) r = schema(db, out->manifest);
    sqlite3_stmt *q = NULL;
    struct md_image_descriptor config = {0};
    if (!r) r = md_json_query(db, out->manifest, "SELECT json_extract(?1,'$.config')", &q);
    if (!r) {
        if (sqlite3_step(q) != SQLITE_ROW || sqlite3_column_type(q, 0) != SQLITE_TEXT) r = -EINVAL;
        else r = md_image_descriptor_read(db, (const char *)sqlite3_column_text(q, 0), &config);
    }
    sqlite3_finalize(q);
    if (!r && strcmp(config.media, "application/vnd.oci.image.config.v1+json")
            && strcmp(config.media, "application/vnd.docker.container.image.v1+json")) r = -ENOTSUP;
    if (!r) r = blob_json(db, blobs, scratch, &config, &out->config);
    if (!r) r = expect(db, out->config, "$.os", "linux");
    if (!r) r = expect(db, out->config, "$.architecture", "arm64");
    if (!r) r = expect(db, out->config, "$.rootfs.type", "layers");
    md_image_descriptor_free(&config);
    if (r) md_image_oci_free(out);
    return r;
}
int md_image_unpack(int blobs, int scratch, const struct md_image_descriptor *d, const char *diff, int *out) {
    *out = -1;
    if (!md_image_digest_valid(diff)) return -EINVAL;
    int filter;
    if (!strcmp(d->media, "application/vnd.oci.image.layer.v1.tar")) filter = ARCHIVE_FILTER_NONE;
    else if (!strcmp(d->media, "application/vnd.oci.image.layer.v1.tar+gzip")
            || !strcmp(d->media, "application/vnd.docker.image.rootfs.diff.tar.gzip")) filter = ARCHIVE_FILTER_GZIP;
    else if (!strcmp(d->media, "application/vnd.oci.image.layer.v1.tar+zstd")) filter = ARCHIVE_FILTER_ZSTD;
    else return -ENOTSUP;
    int fd = -1, result = -1;
    int r = md_image_blob(blobs, scratch, d->digest, d->size, &fd);
    if (!r && filter == ARCHIVE_FILTER_NONE) { result = fd; fd = -1; }
    struct archive *ar = NULL;
    uint64_t size = filter == ARCHIVE_FILTER_NONE ? d->size : 0;
    if (!r && filter != ARCHIVE_FILTER_NONE) {
        ar = archive_read_new();
        if (!ar) r = -ENOMEM;
        if (!r && archive_read_support_format_raw(ar) != ARCHIVE_OK) r = -ENOTSUP;
        if (!r && archive_read_support_filter_by_code(ar, filter) != ARCHIVE_OK) r = -ENOTSUP;
        if (!r && lseek(fd, 0, SEEK_SET) < 0) r = -errno;
        if (!r && archive_read_open_fd(ar, fd, 65536) != ARCHIVE_OK) r = -EBADMSG;
        struct archive_entry *entry;
        if (!r && archive_read_next_header(ar, &entry) != ARCHIVE_OK) r = -EBADMSG;
        if (!r && (archive_filter_count(ar) != 2 || archive_filter_code(ar, 0) != filter)) r = -EBADMSG;
        if (!r && (result = openat(scratch, ".", O_TMPFILE | O_RDWR | O_CLOEXEC, 0600)) < 0) r = -errno;
        unsigned char buffer[65536];
        while (!r) {
            la_ssize_t n = archive_read_data(ar, buffer, sizeof(buffer));
            if (n < 0) { r = -EBADMSG; break; }
            if (!n) break;
            if ((uint64_t)n > 8ULL*1024*1024*1024-size) { r = -EFBIG; break; }
            size += (uint64_t)n; r = md_image_write(result, buffer, (size_t)n);
        }
        if (!r && archive_read_next_header(ar, &entry) != ARCHIVE_EOF) r = -EBADMSG;
        if (ar && archive_read_close(ar) != ARCHIVE_OK && !r) r = -EBADMSG;
        if (ar) archive_read_free(ar);
    }
    char hash[65];
    if (!r) r = md_image_hash(result, size, hash);
    if (!r && strcmp(hash, diff + 7)) r = -EBADMSG;
    if (fd >= 0) close(fd);
    if (r && result >= 0) close(result);
    if (!r) *out = result;
    return r;
}
