#ifndef MD_CONTROL_SUBSCRIPTION_H
#define MD_CONTROL_SUBSCRIPTION_H
#include "control_file.h"

/* Own only kernel registrations, never a duplicate of the guest data FD.
 * The provider remains borrowed under the interest's weak target identity.
 * An optional gate reports provider state changes independently of native IO. */
int md_control_subscription(struct md_control_file,int native,int gate,
    uint32_t events,struct md_control_file *);
#endif
