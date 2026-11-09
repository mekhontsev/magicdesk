#define _GNU_SOURCE
#include "fs_storage.h"
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include <errno.h>
#include <stdlib.h>

struct md_fs_storage_connections {
    struct md_fs_storage_connections *next;
    struct md_inode_store *store;
};
static void release(struct md_fs_storage_cache *cache, struct md_fs_storage_connections **at) {
    struct md_fs_storage_connections *p=*at;
    *at=p->next; --cache->count;
    md_inode_store_close(p->store); free(p);
}
static void trim(struct md_fs_storage_cache *cache,unsigned limit) {
    while (cache->count>limit) {
        struct md_fs_storage_connections **oldest=NULL;
        for (struct md_fs_storage_connections **at=&cache->connections; *at; at=&(*at)->next)
            if ((*at)->store->refs==1) oldest=at;
        if (!oldest) break;
        release(cache,oldest);
    }
}
void md_fs_storage_prune(struct md_fs_storage_cache *cache) {
    if (!cache) return;
    for (struct md_fs_storage_connections **at=&cache->connections; *at;) {
        struct md_fs_storage_connections *p=*at;
        if (p->store->refs==1 && atomic_load_explicit(&p->store->lifetime->refs,memory_order_acquire)==1)
            release(cache,at);
        else at=&p->next;
    }
    trim(cache,MD_FS_STORAGE_CACHE_LIMIT);
}
int md_fs_storage_get(struct md_filesystem *fs, const struct md_view_mount *mount,
        struct md_inode_store **out) {
    if (!fs || !fs->store || !out || !md_view_inode(mount)) return -EINVAL;
    struct md_inode_lifetime *storage=mount ? mount->storage : NULL;
    if (!storage || md_inode_lifetime_same(storage,md_inode_store_lifetime(fs->store))) {
        *out=fs->store; return 0;
    }
    if (!fs->storage) return -ENOTSUP;
    /* A different projection may share this catalogue's kernel gate. Release
     * only the bounded operation window before reconnect or root selection;
     * ordinary nested read/write scopes must not be silently unlocked. */
    int paused=md_inode_operation_window_pause(fs->store);
    if (paused) return paused;
    for (struct md_fs_storage_connections **at=&fs->storage->connections; *at; at=&(*at)->next) {
        struct md_fs_storage_connections *p=*at;
        if (md_inode_lifetime_same(storage,md_inode_store_lifetime(p->store))) {
            *at=p->next; p->next=fs->storage->connections; fs->storage->connections=p;
            *out=p->store; return 0;
        }
    }
    md_fs_storage_prune(fs->storage);
    trim(fs->storage,MD_FS_STORAGE_CACHE_LIMIT-1);
    struct md_fs_storage_connections *p=calloc(1,sizeof(*p));
    if (!p) return -ENOMEM;
    int r=md_inode_store_reconnect(storage,&p->store);
    if (r) { free(p); return r; }
    p->next=fs->storage->connections; fs->storage->connections=p;
    ++fs->storage->count;
    *out=p->store; return 0;
}
void md_fs_storage_close(struct md_fs_storage_cache *cache) {
    if (!cache) return;
    while (cache->connections) release(cache,&cache->connections);
}
int md_fs_storage_readonly(struct md_filesystem *fs,const struct md_view_mount *mount) {
    if (!fs || !fs->store || !md_view_inode(mount)) return -EINVAL;
    const struct md_inode_lifetime *scope=mount ? mount->storage : NULL;
    if (!scope) return md_inode_store_readonly(fs->store);
    int r=md_inode_store_scope_readonly(fs->store,scope);
    if (r!=-EXDEV) return r;
    r=md_inode_operation_window_pause(fs->store);
    if (r) return r;
    /* Mount tables need only policy, not a connection per overlay projection.
     * Reuse a catalogue's gate and prepared query without evicting IO owners. */
    if (fs->storage) for (struct md_fs_storage_connections *p=fs->storage->connections;p;p=p->next) {
        r=md_inode_store_scope_readonly(p->store,scope);
        if (r!=-EXDEV) return r;
    }
    struct md_inode_store *store;
    r=md_fs_storage_get(fs,mount,&store);
    return r ? r : md_inode_store_readonly(store);
}
int md_fs_storage_enter(struct md_filesystem *fs, const struct md_view_mount *mount,
        struct md_fs_storage_scope *scope) {
    *scope=(struct md_fs_storage_scope){0};
    struct md_inode_store *store;
    int r=md_fs_storage_get(fs,mount,&store);
    if (r) return r;
    *scope=(struct md_fs_storage_scope){.store=md_inode_store_retain(store),.identity=store->identity,
        .root=store->root_id,.boundary=store->boundary,.mask=store->creation_mask,
        .landlock=store->landlock,.image_open=store->image_open};
    store->identity=fs->store->identity; store->creation_mask=fs->store->creation_mask;
    store->landlock=fs->store->landlock;
    store->image_open=fs->store->image_open;
    /* Pivoted roots retain their providers. Ordinary bind aliases still expose
     * real dentries until selected as roots, rather than inventing proc/dev. */
    store->boundary=store==fs->store && (!mount || !fs->mounts || mount==&fs->mounts->base
        || mount==md_view_root_mount(fs) || mount->root_providers) ? fs->store->boundary : NULL;
    store->root_id=store==fs->store ? fs->store->root_id : NULL;
    return 0;
}
void md_fs_storage_leave(struct md_fs_storage_scope *scope) {
    if (!scope->store) return;
    scope->store->identity=scope->identity; scope->store->root_id=scope->root;
    scope->store->boundary=scope->boundary; scope->store->creation_mask=scope->mask;
    scope->store->landlock=scope->landlock;
    scope->store->image_open=scope->image_open;
    md_inode_store_close(scope->store);
    scope->store=NULL;
}
