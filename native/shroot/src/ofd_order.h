#ifndef MD_OFD_ORDER_H
#define MD_OFD_ORDER_H
#include "resource_order.h"

struct md_ofd_orders;
struct md_ofd_entry;
/* An active operation pins its exact kernel open description. This is not a
 * descriptor catalogue: the last operation removes the entry and closes it.
 * Without safe native OFD comparison, a queue conservatively orders every
 * active description of that inode; each operation still uses its own FD. */
struct md_ofd_order {
    struct md_ofd_orders *owner;
    struct md_ofd_entry *entry;
    struct md_resource_order order;
};
int md_ofd_orders_new(struct md_ofd_orders **);
/* Active turns retain the registry beyond namespace-worker teardown. */
void md_ofd_orders_release(struct md_ofd_orders *);
/* reserve creates a turn when absent. A synchronous namespace-worker operation
 * uses reserve=0: no active match means immediate execution without allocation
 * or a retained FD. Both sides must execute on that same filesystem worker;
 * only completion/cancellation can cross event loops. */
int md_ofd_order_begin(struct md_ofd_orders *, int fd, int reserve,
        struct md_ofd_order *, struct md_resource_wakeup *);
enum md_resource_order_state md_ofd_order_state(const struct md_ofd_order *);
int md_ofd_order_start(struct md_ofd_order *);
int md_ofd_order_cancel(struct md_ofd_order *);
int md_ofd_order_complete(struct md_ofd_order *);
#endif
