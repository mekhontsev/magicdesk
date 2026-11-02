#define _GNU_SOURCE
#include "procfs_view.h"
#include "provider_tree.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <stdlib.h>
#include <unistd.h>

static const struct md_provider_tree_ops tree_ops={.filesystem=PROC_SUPER_MAGIC,.release=free};
const struct md_procfs_policy *md_procfs_tree_policy(const struct md_provider_tree *tree) {
    return md_provider_tree_context((struct md_provider_tree *)tree,&tree_ops);
}
int md_procfs_policy_valid(const struct md_procfs_policy *p) {
    return p && (p->hidepid<=2 || p->hidepid==4) && p->pidonly<=1
        && p->gid!=UINT32_MAX && p->pressure<=1;
}
int md_procfs_export(const struct md_provider_tree *tree,struct md_procfs_policy *out) {
    const struct md_procfs_policy *p=md_procfs_tree_policy(tree);
    if (!p) return 0;
    if (out) *out=*p;
    return 1;
}
static struct md_provider_tree *create(int directory,int lower,const struct md_procfs_policy *saved,int import) {
    if (!md_procfs_policy_valid(saved)) { errno=EPROTO; return NULL; }
    struct md_procfs_policy *p=malloc(sizeof(*p));
    if (!p) return NULL;
    *p=*saved;
    struct md_provider_tree *tree=import
        ? md_provider_tree_import_context(directory,lower,&tree_ops,p)
        : md_provider_tree_new(directory,lower,NULL,0,&tree_ops,p);
    int error=errno;
    if (!tree) free(p);
    errno=error; return tree;
}
struct md_provider_tree *md_procfs_tree_new(int parent,int lower,const struct md_procfs_policy *saved) {
    return create(parent,lower,saved,0);
}
struct md_provider_tree *md_procfs_import(int upper,const struct md_procfs_policy *saved) {
    if (!md_procfs_policy_valid(saved)) { errno=EPROTO; return NULL; }
    int lower=open("/proc",O_PATH|O_DIRECTORY|O_CLOEXEC);
    if (lower<0) return NULL;
    struct md_provider_tree *tree=create(upper,lower,saved,1);
    int error=errno;
    close(lower);
    errno=error; return tree;
}
