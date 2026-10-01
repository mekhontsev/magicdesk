#ifndef MD_IMAGE_OCI_H
#define MD_IMAGE_OCI_H
#include <sqlite3.h>
#include <stdint.h>
struct md_image_descriptor { char digest[72]; char *media; uint64_t size; };
struct md_image_oci {
    char *manifest, *config;
    struct md_image_descriptor identity;
};
int md_image_oci_read(sqlite3 *, int layout, int blobs, int scratch, const char *reference,
    struct md_image_oci *);
int md_image_descriptor_read(sqlite3 *, const char *, struct md_image_descriptor *);
void md_image_descriptor_free(struct md_image_descriptor *);
void md_image_oci_free(struct md_image_oci *);
/* Returns a verified, uncompressed anonymous tar descriptor. */
int md_image_unpack(int blobs, int scratch, const struct md_image_descriptor *, const char *diff_id, int *);
#endif
