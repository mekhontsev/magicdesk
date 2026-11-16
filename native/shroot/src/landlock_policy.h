#ifndef MD_LANDLOCK_POLICY_H
#define MD_LANDLOCK_POLICY_H
#include <stdint.h>
#include "inode_lifetime.h"
#include "control_file.h"

enum { MD_LANDLOCK_ABI=1, MD_LANDLOCK_LAYERS=16 };
#define MD_LL_EXECUTE (1ULL<<0)
#define MD_LL_WRITE_FILE (1ULL<<1)
#define MD_LL_READ_FILE (1ULL<<2)
#define MD_LL_READ_DIR (1ULL<<3)
#define MD_LL_REMOVE_DIR (1ULL<<4)
#define MD_LL_REMOVE_FILE (1ULL<<5)
#define MD_LL_MAKE_CHAR (1ULL<<6)
#define MD_LL_MAKE_DIR (1ULL<<7)
#define MD_LL_MAKE_REG (1ULL<<8)
#define MD_LL_MAKE_SOCK (1ULL<<9)
#define MD_LL_MAKE_FIFO (1ULL<<10)
#define MD_LL_MAKE_BLOCK (1ULL<<11)
#define MD_LL_MAKE_SYM (1ULL<<12)
#define MD_LL_ALL ((1ULL<<13)-1)
#define MD_LL_FILE (MD_LL_EXECUTE|MD_LL_READ_FILE|MD_LL_WRITE_FILE)

/* Store lifetime plus logical object survives copy-up and rename. An open
 * description is not a path rule and is never retained for data access here. */
struct md_landlock_key { struct md_inode_lifetime *store; char object[33]; };
/* Operation-scoped grants from mount ancestors, indexed by policy layer.
 * The local inode walk stops at root instead of following a bind source's
 * unrelated original ancestors. No paths or mutable policy are cached. */
struct md_landlock_scope { uint64_t inherited[MD_LANDLOCK_LAYERS]; char root[33]; };
struct md_landlock_ruleset;
struct md_landlock_policy;
struct md_landlock_ruleset *md_landlock_ruleset_new(uint64_t handled);
struct md_landlock_ruleset *md_landlock_ruleset_retain(struct md_landlock_ruleset *);
void md_landlock_ruleset_release(struct md_landlock_ruleset *);
/* A nonnegative native FD is borrowed and retained by the immutable rule pin. */
int md_landlock_rule_add(struct md_landlock_ruleset *,const struct md_landlock_key *,uint64_t,int native_fd);
struct md_landlock_policy *md_landlock_restrict(const struct md_landlock_policy *,const struct md_landlock_ruleset *);
struct md_landlock_policy *md_landlock_retain(struct md_landlock_policy *);
void md_landlock_release(struct md_landlock_policy *);
/* Matching runs under the same namespace transaction as the operation. A
 * negative match is a lookup failure, not permission to skip that rule. */
int md_landlock_check(const struct md_landlock_policy *,uint64_t,
    int (*matches)(void *,const struct md_landlock_key *),void *,const uint64_t *inherited);
int md_landlock_collect(const struct md_landlock_policy *,
    int (*matches)(void *,const struct md_landlock_key *),void *,uint64_t *grants);
uint64_t md_landlock_open_access(int flags,mode_t kind);
uint64_t md_landlock_make_access(mode_t kind);
int md_landlock_ruleset_open(struct md_landlock_ruleset *,const struct md_identity *,
    const struct md_control_file_ops **,void **);
struct md_landlock_ruleset *md_landlock_ruleset_control(struct md_control_file);
#endif
