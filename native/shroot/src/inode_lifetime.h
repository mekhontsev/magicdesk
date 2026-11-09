#ifndef MD_INODE_LIFETIME_H
#define MD_INODE_LIFETIME_H

/* Backing custody, independent of a worker's SQLite connection. Retains only
 * store/source directories and their existing maintenance locks, not data OFDs.
 * The worker alone adds sources; other owners only retain/release or compare
 * immutable store identity. It does not make a mount view multi-worker safe. */
struct md_inode_store;
struct md_inode_lifetime;
/* Borrowed until store close; retain before publishing beyond that lifetime. */
struct md_inode_lifetime *md_inode_store_lifetime(const struct md_inode_store *);
struct md_inode_lifetime *md_inode_lifetime_retain(struct md_inode_lifetime *);
void md_inode_lifetime_release(struct md_inode_lifetime *);
int md_inode_lifetime_same(const struct md_inode_lifetime *, const struct md_inode_lifetime *);
int md_inode_lifetime_catalogue_same(const struct md_inode_lifetime *, const struct md_inode_lifetime *);
#endif
