#define _GNU_SOURCE
#include "bootstrap.h"
#include "elf.h"
#include "namespace.h"
#include "proc_paths.h"
#include "file_calls.h"
#include "linux_abi.h"
#include "raw.h"
#include "socket_routes.h"
#include "fs_buffer.h"
#include "command_image.h"
#include "exec_prepare.h"
#include "interception.h"
#include "native_identity.h"
#include "namespace_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

static long prepare_retained(const struct md_fs *fs, int base, const char *path, int flags,
        int inaccessible, struct md_command_recipe *recipe, unsigned long prepared) {
    char routed[PATH_MAX];
    long error=md_copy(routed,sizeof(routed),path);
    if (error) return error;
    /* Preserve dirfd provenance. A proc descriptor's displayed PID is not a
     * pathname in the caller's current proc mount or PID namespace. */
    struct md_fs_request q={.operation=MD_FS_OPEN_IMAGE,.directory={base,-1},.path={routed,NULL},
        .flags=flags&AT_SYMLINK_NOFOLLOW ? O_NOFOLLOW : 0,
        .mode=inaccessible ? MD_EXEC_INACCESSIBLE : 0,.offset=base>=0 ? base : 0};
    struct md_proc_path ref=md_proc_path(routed);
    if (ref.kind && !*ref.tail && (flags&AT_SYMLINK_NOFOLLOW)) return -ELOOP;
    if (ref.kind==MD_PROC_EXE && !*ref.tail && fs->image) {
        if (*fs->image->executable_object) {
            q.path[1]=fs->image->executable_object;
            q.mode|=MD_EXEC_OBJECT;
            error=md_copy(routed,sizeof(routed),fs->image->executable_path);
        }
    } else if (ref.kind==MD_PROC_FD && ref.process==md_native_tid()) {
        const char *tail=ref.tail;
        while (*tail=='/') ++tail;
        /* Preserve a trailing slash as a directory lookup, not descriptor exec. */
        if (!*tail && *ref.tail) tail=".";
        memmove(routed,tail,md_length(tail)+1);
        q.directory[0]=ref.descriptor; q.offset=ref.descriptor;
    } else if (ref.kind==MD_PROC_ROOT || (ref.kind==MD_PROC_CWD && ref.process==md_native_tid())) {
        if (!*ref.tail) return -EACCES;
        const char *tail=ref.tail;
        if (ref.kind==MD_PROC_CWD) while (*tail=='/') ++tail;
        memmove(routed,*tail ? tail : ".",md_length(*tail ? tail : ".")+1);
        q.directory[0]=AT_FDCWD;
    }
    if (error) return error;
    if (*routed=='/') q.directory[0]=-1;
    error=md_fs_buffer_exec_prepare(&q,recipe,prepared);
    return error;
}
int md_command_initial(const struct md_fs *fs, struct md_command *c, const char *path, char *const argv[]) {
    int error=md_command_arguments(c,path,argv);
    if (error) return error;
    struct md_command_recipe recipe;
    error=(int)prepare_retained(fs,AT_FDCWD,path,0,0,&recipe,0);
    if (!error) error=md_command_apply_recipe(c,&recipe);
    if (!error) {
        long fd=RAW3(prctl,MD_GUEST_MAP_IMAGE,0,MD_IMAGE_TAKE_PROGRAM);
        if (fd<0) error=(int)fd; else c->fd=(int)fd;
    }
    if (!error && recipe.loader) {
        long fd=RAW3(prctl,MD_GUEST_MAP_IMAGE,0,MD_IMAGE_TAKE_LOADER);
        if (fd<0) error=(int)fd; else c->interpreter_fd=(int)fd;
    }
    if (!error && recipe.execfd) {
        long fd=RAW3(prctl,MD_GUEST_MAP_IMAGE,0,MD_IMAGE_TAKE_EXECFD);
        if (fd<0) error=(int)fd; else c->execfd=(int)fd;
    }
    if (error) { md_fs_buffer_exec_cancel(); md_command_close(c); }
    return error;
}
long md_guest_execat(const struct md_fs *fs, int base, const char *program, char *const argv[], char *const env[],
        int flags, unsigned long prepared) {
    if (flags & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW)) return -EINVAL;
    char path[PATH_MAX], identity[PATH_MAX];
    long r = md_read_string(path, sizeof(path), program);
    if (r < 0) return r;
    if (!*path && !(flags & AT_EMPTY_PATH)) return -ENOENT;
    int inaccessible = 0;
    if (!*path || (*path != '/' && base != AT_FDCWD)) {
        long descriptor_flags = RAW2(fcntl, base, F_GETFD);
        if (descriptor_flags < 0) return descriptor_flags;
        inaccessible = !!(descriptor_flags & FD_CLOEXEC);
        md_copy(identity, sizeof(identity), "/dev/fd/");
        md_decimal(identity + md_length(identity), (unsigned)base);
        if (*path) {
            r = md_append(identity, sizeof(identity), "/");
            if (!r) r = md_append(identity, sizeof(identity), path);
            if (r < 0) return r;
        }
    }
    /* Relative-dirfd scripts need the caller's descriptor spelling, not a
     * canonical name which could reopen a replaced entry after exec. */
    const char *invocation = *path && (*path == '/' || base == AT_FDCWD) ? path : identity;
    struct md_command c;
    int retained=!!fs->endpoint[0], loader=0, execfd=0;
    if (retained) {
        r=md_command_arguments(&c,invocation,argv);
        if (r<0) return r;
        struct md_command_recipe recipe;
        r=prepare_retained(fs,base,path,flags,inaccessible,&recipe,prepared);
        if (!r) { loader=recipe.loader; execfd=recipe.execfd; r=md_command_apply_recipe(&c,&recipe); }
        if (r<0) { md_fs_buffer_exec_cancel(); md_command_close(&c); return r; }
    } else {
        struct md_program input;
        r=!*path ? md_program_capture(fs,base,identity,&input)
            : md_program_acquire(fs,base,path,(flags&AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0,&input);
        if (r<0) return r;
        r=md_command_prepare(fs,&c,invocation,argv,&input,inaccessible);
        RAW1(close,input.fd);
        if (r<0) return r;
        loader=c.interpreter_fd>=0;
        r = RAW3(fcntl, c.fd, F_SETFD, 0);
        if (!r && loader) r = RAW3(fcntl, c.interpreter_fd, F_SETFD, 0);
    }
    if (r < 0) { md_command_close(&c); return r; }
    char number[24]; md_decimal(number, (unsigned)c.fd);
    char interpreter_number[24];
    char *next[24 + MD_SOCKET_ROUTES_MAX * 3];
    next[0] = md_bootstrap;
    next[1] = "--resume";
    unsigned n=2;
    if (retained) next[n++]="--retained-images";
    else { next[n++] = "--program-fd"; next[n++] = number; }
    if (c.object[0]) { next[n++] = "--program-object"; next[n++] = c.object; }
    if (loader) {
        if (retained) next[n++]="--retained-loader";
        else {
            md_decimal(interpreter_number, (unsigned)c.interpreter_fd);
            next[n++] = "--interpreter-fd";
            next[n++] = interpreter_number;
        }
    }
    if (execfd) next[n++]="--retained-execfd";
    // AT_EXECFN is the caller's spelling, not the canonical executable or the
    // final shebang interpreter. Linux uses /dev/fd/N for descriptor exec.
    next[n++] = "--execfn";
    next[n++] = (char *)invocation;
    const struct md_socket_routes *routes = fs->connections;
    for (unsigned i = 0; routes && i < routes->count; i++) {
        const struct md_socket_route *route = &routes->entries[i];
        next[n++] = (char *)md_socket_route_option(route);
        next[n++] = (char *)route->source;
        next[n++] = (char *)route->destination;
    }
    if(fs->endpoint[0]) { next[n++]="--namespace"; next[n++]=(char *)fs->endpoint; }
    else next[n++]=(char *)fs->root;
    next[n++]=c.path;
    r=md_arguments_reserve(&c.args,n+c.args.count+1);
    if (!r) {
        memmove(c.args.values+n,c.args.values,(c.args.count+1)*sizeof(char *));
        memcpy(c.args.values,next,n*sizeof(char *));
        r=RAW3(execve,md_bootstrap,c.args.values,env);
    }
    if (retained) md_fs_buffer_exec_cancel();
    md_command_close(&c);
    return r;
}
