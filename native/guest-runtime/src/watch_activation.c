#include "watch_activation.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
enum { BYTES = (MD_WATCH_SELECTOR_MASK + 1) / 8 };
struct md_watch_activation { unsigned references; unsigned char *bits; };
struct md_watch_activation *md_watch_activation_new(void) {
    struct md_watch_activation *a = calloc(1, sizeof(*a));
    if (a) a->references = 1;
    return a;
}
struct md_watch_activation *md_watch_activation_fork(struct md_watch_activation *a, int shared) {
    if (shared) { a->references++; return a; }
    struct md_watch_activation *copy = md_watch_activation_new();
    if (copy && a->bits) {
        copy->bits = malloc(BYTES);
        if (!copy->bits) { free(copy); return NULL; }
        memcpy(copy->bits, a->bits, BYTES);
    }
    return copy;
}
void md_watch_activation_release(struct md_watch_activation *a) {
    if (!a || --a->references) return;
    free(a->bits); free(a);
}
int md_watch_activation_has(struct md_watch_activation *a, unsigned fd) {
    fd &= MD_WATCH_SELECTOR_MASK;
    /* Allocate before installing an irreversible kernel filter. */
    if (!a->bits && !(a->bits = calloc(1, BYTES))) return -ENOMEM;
    return !!(a->bits[fd / 8] & (1U << (fd % 8)));
}
int md_watch_activation_mark(struct md_watch_activation *a, unsigned fd) {
    fd &= MD_WATCH_SELECTOR_MASK;
    if (!a->bits) return -EINVAL;
    a->bits[fd / 8] |= (1U << (fd % 8)); return 0;
}
