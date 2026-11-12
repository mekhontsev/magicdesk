#ifndef MD_GUEST_TRANSFER_H
#define MD_GUEST_TRANSFER_H
#include <stddef.h>
#include <stdint.h>

enum md_guest_transfer_kind { MD_TRANSFER_READ, MD_TRANSFER_WRITE, MD_TRANSFER_STRING };
/* Maintenance copies use the same protected C accessors as syscall adapters.
 * The stopped owner supplies one bounded loan, never register-packed data. */
struct md_guest_transfer {
    uintptr_t address;
    size_t size;
    enum md_guest_transfer_kind kind;
    unsigned char data[4096];
};
long md_guest_transfer(struct md_guest_transfer *);
#endif
