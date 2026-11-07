#define _GNU_SOURCE
#include "host_open.h"
#include "open_request.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/limits.h>
#include <sys/stat.h>

static long no_symlinks(int base,const char *path,const struct open_how *how) {
    int directory=-1;
    long result;
    if (*path=='/') {
        result=RAW4(openat,AT_FDCWD,"/",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
        if (result<0) return result;
        directory=(int)result; base=directory;
        while (*path=='/') ++path;
    }
    for (;;) {
        const char *end=path;
        while (*end && *end!='/') ++end;
        size_t length=(size_t)(end-path);
        if (length>NAME_MAX) { result=-ENAMETOOLONG; break; }
        char name[NAME_MAX+1]; memcpy(name,path,length); name[length]=0;
        int slash=*end=='/';
        while (*end=='/') ++end;
        if (!*end) {
            /* An empty final component denotes the pinned root, not ENOENT.
             * O_NOFOLLOW enforces the rule at the final atomic open as well. */
            const char *leaf=length ? name : ".";
            unsigned flags=how->flags|O_NOFOLLOW|(slash ? O_DIRECTORY : 0);
            int reject_link=slash || (how->flags&(O_PATH|O_NOFOLLOW))!=(O_PATH|O_NOFOLLOW);
            result=RAW4(openat,base,leaf,flags,how->mode);
            if (result>=0 && (flags&O_PATH) && !(flags&O_DIRECTORY) && reject_link) {
                /* Inspect the retained FD, not a path probe followed by a
                 * second open: the final name can be replaced concurrently. */
                struct stat st;
                long error=RAW2(fstat,result,&st);
                if (!error && S_ISLNK(st.st_mode)) error=-ELOOP;
                if (error) { RAW1(close,result); result=error; }
            } else if (result==-ENOTDIR && (flags&O_DIRECTORY) && reject_link) {
                /* O_DIRECTORY|O_NOFOLLOW reports ENOTDIR for a symlink.
                 * Refine that failure only; never reopen a successful path. */
                long probe=RAW4(openat,base,leaf,O_PATH|O_NOFOLLOW|O_CLOEXEC,0);
                if (probe>=0) {
                    struct stat st;
                    long error=RAW2(fstat,probe,&st); RAW1(close,probe);
                    if (!error && S_ISLNK(st.st_mode)) result=-ELOOP;
                }
            }
            break;
        }
        result=RAW4(openat,base,name,O_PATH|O_NOFOLLOW|O_CLOEXEC,0);
        if (result<0) break;
        int next=(int)result;
        struct stat st;
        result=RAW2(fstat,next,&st);
        if (!result && S_ISLNK(st.st_mode)) result=-ELOOP;
        if (!result && !S_ISDIR(st.st_mode)) result=-ENOTDIR;
        if (result<0) { RAW1(close,next); break; }
        if (directory>=0) RAW1(close,directory);
        base=directory=next; path=end;
    }
    if (directory>=0) RAW1(close,directory);
    return result;
}
long md_host_open_resolved(int base,const char *path,const struct open_how *how) {
    long result=RAW4(openat2,base,path,how,sizeof(*how));
    if (result!=-ENOSYS) return result;
    result=md_open_how_validate(how);
    if (result) return result;
    if (!*path) return -ENOENT;
    if (how->resolve&RESOLVE_CACHED) return -EAGAIN;
    if (!how->resolve) return RAW4(openat,base,path,how->flags,how->mode);
    /* A scoped lookup of the directory itself has no links, mount crossings
     * or rename race. Keep it atomic even when openat2 is denied by the host.
     * Nontrivial scoped walks still require native containment enforcement. */
    if (how->resolve&(RESOLVE_BENEATH|RESOLVE_IN_ROOT|RESOLVE_NO_XDEV)) {
        if (*path=='/' && (how->resolve&RESOLVE_BENEATH)) return -EXDEV;
        const char *p=path;
        if (*p!='/' || (how->resolve&RESOLVE_IN_ROOT)) {
            while (*p) {
                while (*p=='/') ++p;
                if (*p=='.' && (!p[1] || p[1]=='/')) ++p;
                else break;
            }
            if (!*p) return RAW4(openat,base,".",how->flags,how->mode);
            if ((how->resolve&RESOLVE_BENEATH) && p[0]=='.' && p[1]=='.'
                    && (!p[2] || p[2]=='/')) {
                long anchor=RAW4(openat,base,".",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
                if (anchor<0) return anchor;
                RAW1(close,anchor); return -EXDEV;
            }
        }
    }
    if (!(how->resolve&RESOLVE_NO_SYMLINKS)
            || (how->resolve&~(RESOLVE_NO_SYMLINKS|RESOLVE_NO_MAGICLINKS))) return -EOPNOTSUPP;
    return no_symlinks(base,path,how);
}
