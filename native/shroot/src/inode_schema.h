#ifndef MD_INODE_SCHEMA_H
#define MD_INODE_SCHEMA_H
#include <stddef.h>
#include <sqlite3.h>

struct md_inode_store;
/* Revision admits the complete persistent contract, including native metadata
 * encodings. Runtime tuning and rebuildable indexes do not advance it.
 * Append metadata-only SQL upgrades: steps[0] advances revision 1 to 2.
 * Each step and its revision marker commit together. Never delete old steps or
 * put backing-file IO, transaction control or journal pragmas in a step. */
struct mdi_schema_plan { const char *const *steps; size_t count; };
extern const struct mdi_schema_plan mdi_schema_current;
/* Admission only: caller holds the objects gate and a lifetime lease. Updating
 * requires an unshared owner; inspection and retained reconnects cannot update. */
int mdi_schema_admit(struct md_inode_store *, int allow_update, int exclusive,
        const struct mdi_schema_plan *);
int mdi_schema_configure(struct md_inode_store *, int create);
/* Offline metadata maintenance: caller holds the exclusive root lease. Does
 * not open shared backing sources (which may themselves be exclusively held). */
int mdi_schema_admit_offline(int root, sqlite3 *);
/* Leaves the creation transaction open for initial root publication. */
int mdi_schema_begin_create(struct md_inode_store *);
int mdi_schema_indexes(struct md_inode_store *);
#endif
