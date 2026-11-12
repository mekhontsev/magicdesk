#include "guest_transfer.h"
#include "raw.h"
#include <errno.h>

long md_guest_transfer(struct md_guest_transfer *request) {
    if (request->size>sizeof(request->data) || request->address>UINTPTR_MAX-request->size) return -EFAULT;
    void *address=(void *)request->address;
    switch (request->kind) {
    case MD_TRANSFER_READ: return md_read_memory(request->data,address,request->size);
    case MD_TRANSFER_WRITE: return md_write_memory(address,request->data,request->size);
    case MD_TRANSFER_STRING: return md_read_string((char *)request->data,request->size,address);
    }
    return -EINVAL;
}
