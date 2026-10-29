#include "namespace_internal.h"
#include "raw.h"
#include "fuse_options.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/mount.h>
#include <sys/syscall.h>

long md_namespace_mount(const struct md_fs *fs, long nr, const unsigned long *a) {
    char target[PATH_MAX], source[PATH_MAX], data[MD_FUSE_MOUNT_MAX];
    source[0]=data[0]=0;
    struct md_fs_request q={.operation=MD_FS_MOUNT,.directory={AT_FDCWD,-1},
        .path={target,NULL}};
    long r=md_read_string(target,sizeof(target),(const char *)a[nr==SYS_mount ? 1 : 0]);
    if (r<0) return r;
    if (!*target) return -ENOENT;
    if (nr==SYS_pivot_root) {
        q.mode=MD_FS_MOUNT_PIVOT;
        r=md_read_string(source,sizeof(source),(const char *)a[1]);
    } else if (nr==SYS_umount2) { q.mode=MD_FS_MOUNT_UMOUNT; q.flags=a[1]; }
    else {
        if (a[3]>UINT32_MAX) return -EINVAL;
        q.flags=a[3];
        /* Linux's legacy magic prefix and diagnostic-only flag carry no mount
         * policy. Normalize once at the syscall ABI, before selecting a verb. */
        if ((q.flags&MS_MGC_MSK)==MS_MGC_VAL) q.flags&=~MS_MGC_MSK;
        q.flags&=~MS_SILENT;
        if (q.flags&(1U<<31)) return -EINVAL; /* Kernel-private MS_NOUSER. */
        if (q.flags&MS_REMOUNT) {
            q.mode=MD_FS_MOUNT_REMOUNT;
            /* Legacy remount changes one mount, even when REC is present.
             * Recursive policy changes belong to mount_setattr instead. */
            q.flags&=~MS_REC;
            if (!(q.flags&MS_BIND) && a[4]) {
                r=md_read_string(data,PATH_MAX,(const char *)a[4]);
                if (r>=0) { q.value=data; q.value_size=(uint32_t)md_length(data)+1; }
            }
        }
        else if (q.flags&MS_BIND) {
            q.mode=MD_FS_MOUNT_BIND;
            /* path_mount selects bind before propagation and passes only REC.
             * A bind inherits source policy, not these ignored caller flags. */
            q.flags&=MS_BIND|MS_REC;
            r=md_read_string(source,sizeof(source),(const char *)a[0]);
        } else if (q.flags&(MS_PRIVATE|MS_SLAVE|MS_SHARED|MS_UNBINDABLE)) q.mode=MD_FS_MOUNT_PROPAGATION;
        else if (q.flags&MS_MOVE) {
            q.mode=MD_FS_MOUNT_MOVE;
            r=md_read_string(source,sizeof(source),(const char *)a[0]);
        } else {
            /* REC belongs to bind/propagation, not a new superblock. Linux
             * discards it before invoking the filesystem's mount operation. */
            q.flags&=~MS_REC;
            const char *type=data;
            r=md_read_string(data,PATH_MAX,(const char *)a[2]);
            if (r<0) return r;
            if (md_equal(type,"tmpfs")) q.mode=MD_FS_MOUNT_TMPFS;
            else if (md_equal(type,"ramfs")) q.mode=MD_FS_MOUNT_RAMFS;
            else if (md_equal(type,"overlay")) q.mode=MD_FS_MOUNT_OVERLAY;
            else if (md_equal(type,"proc")) q.mode=MD_FS_MOUNT_PROC;
            else if (md_equal(type,"devpts")) q.mode=MD_FS_MOUNT_DEVPTS;
            else if (md_equal(type,"cgroup")) q.mode=MD_FS_MOUNT_CGROUP;
            else if (md_equal(type,"cgroup2")) q.mode=MD_FS_MOUNT_CGROUP2;
            else if (md_equal(type,"sysfs")) q.mode=MD_FS_MOUNT_SYSFS;
            else if (md_equal(type,"mqueue")) q.mode=MD_FS_MOUNT_MQUEUE;
            else if (md_equal(type,"binfmt_misc")) q.mode=MD_FS_MOUNT_BINFMT;
            else if (md_equal(type,"fuse") || (type[0]=='f' && type[1]=='u' && type[2]=='s'
                    && type[3]=='e' && type[4]=='.' && type[5])) q.mode=MD_FS_MOUNT_FUSE;
            else return -ENODEV;
            if (q.mode==MD_FS_MOUNT_FUSE) {
                size_t used=md_length(data)+1;
                data[used]=0;
                if (a[0]) r=md_read_string(data+used,PATH_MAX,(const char *)a[0]);
                if (r<0) return r;
                used+=md_length(data+used)+1;
                data[used]=0;
                if (a[4]) r=md_read_string(data+used,PATH_MAX,(const char *)a[4]);
                if (r<0) return r;
                q.value=data; q.value_size=(uint32_t)(used+md_length(data+used)+1);
                struct md_fuse_mount mount;
                r=md_fuse_mount_parse(q.value,q.value_size,&mount);
                if (r>=0) { q.directory[1]=mount.options.descriptor; q.path[1]=""; }
            } else if (a[4]) {
                r=md_read_string(data,PATH_MAX,(const char *)a[4]);
                if (r>=0) { q.value=data; q.value_size=(uint32_t)md_length(data)+1; }
            }
        }
    }
    if (r<0) return r;
    if ((q.mode==MD_FS_MOUNT_BIND || q.mode==MD_FS_MOUNT_MOVE || q.mode==MD_FS_MOUNT_PIVOT)
            && !*source) return -ENOENT;
    if (*source) { q.directory[1]=AT_FDCWD; q.path[1]=source; }
    return md_namespace_paths_request(fs,&q);
}
