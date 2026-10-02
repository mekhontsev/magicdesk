#ifndef MD_GUEST_ACCOUNTS_H
#define MD_GUEST_ACCOUNTS_H
#include "guest_identity.h"
#include <linux/limits.h>
struct md_guest_account {
    char name[1025], home[PATH_MAX], shell[PATH_MAX];
};
struct md_filesystem;
/* Resolve only the selected guest's account files, never Android's accounts. */
int md_guest_user_resolve(struct md_filesystem *, const char *, struct md_identity *);
int md_guest_group_argument(const struct md_identity *, char **);
int md_guest_account_resolve(struct md_filesystem *, uint32_t, struct md_guest_account *);
#endif
