#ifndef MD_FD_ADMISSION_H
#define MD_FD_ADMISSION_H
#include "fd_origins.h"
#include "fd_selector.h"

/* Cold, event-loop-owned selector admission for one retained authority.
 * The caller reserves the authority's dynamic requirements BEFORE creating
 * this inventory. It supplies every live (file table, thread group) pair;
 * repeated threads are deduplicated. Queued transfers must use the authority's
 * current requirements when they publish a new descriptor.
 *
 * No native descriptors, task operations or blocking waits belong here.
 * The owner installs each requested filter in the actual thread group, then
 * acknowledges its kernel result. Application denial remains an error.
 * Cancellation retains already installed selectors: kernel filters cannot be
 * undone. Their dispatch must still handle passive owners and reused numbers. */
struct md_fd_admission;
struct md_fd_admission_request {
    struct md_fd_selector *group;
    enum md_fd_selector_kind kind;
    int number;
};
struct md_fd_admission *md_fd_admission_new(struct md_fd_object *,unsigned selectors);
int md_fd_admission_add(struct md_fd_admission *,struct md_fd_origins *,struct md_fd_selector *);
/* Enqueue table turns. Call without owning any table turn. The launch owner
 * serializes cold admissions, including different authorities, but continues
 * normal task/worker events while they wait. wake remains owned by that loop. */
int md_fd_admission_begin(struct md_fd_admission *,struct md_resource_wakeup *);
/* 0: awaiting exact table completion, 1: all current tables held. The caller
 * must rescan live owners here: a preceding fork/unshare may have completed
 * while a table was queued. add remains available until seal, including new
 * tables; prepare then waits for those too. Once no owner is missing, seal
 * captures aliases under the held turns and releases tables without an alias.
 * Holder dup/fork operations remain queued; incoming retained objects use their
 * reserved dynamic requirements even in a previously empty table. */
int md_fd_admission_prepare(struct md_fd_admission *);
int md_fd_admission_seal(struct md_fd_admission *);
/* 1: exact request, 2: all requirements met. Requires a sealed inventory.
 * At 2 the owner commits its reservation BEFORE releasing this admission.
 * Negative errno leaves the transaction cancellable, never committed. */
int md_fd_admission_next(struct md_fd_admission *,struct md_fd_admission_request *);
int md_fd_admission_observed(struct md_fd_admission *,const struct md_fd_admission_request *,long result);
/* Only after the outer task registry confirms no live member of this group.
 * An outstanding task call must have returned or reached terminal cleanup.
 * Other owners and their table turns remain part of the same transaction. */
int md_fd_admission_retire(struct md_fd_admission *,struct md_fd_selector *);
/* Retires only owned table turns and metadata. Caller must first finish any
 * in-flight task call and commit/abort its authority reservation. */
void md_fd_admission_release(struct md_fd_admission *);
#endif
