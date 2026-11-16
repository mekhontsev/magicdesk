#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/landlock.h>
#include <pthread.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define READ_RIGHTS (LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR)
#define WRITE_RIGHTS (LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_REMOVE_DIR \
    | LANDLOCK_ACCESS_FS_REMOVE_FILE | LANDLOCK_ACCESS_FS_MAKE_CHAR \
    | LANDLOCK_ACCESS_FS_MAKE_DIR | LANDLOCK_ACCESS_FS_MAKE_REG \
    | LANDLOCK_ACCESS_FS_MAKE_SOCK | LANDLOCK_ACCESS_FS_MAKE_FIFO \
    | LANDLOCK_ACCESS_FS_MAKE_BLOCK | LANDLOCK_ACCESS_FS_MAKE_SYM)
/* Deliberately exercise ABI 1: no requirement for a host kernel newer than 5.15. */
#define HANDLED (READ_RIGHTS | WRITE_RIGHTS | LANDLOCK_ACCESS_FS_EXECUTE)

static void expect_error(long result, int error) {
    int actual = errno;
    if (result != -1 || actual != error) {
        fprintf(stderr, "result=%ld errno=%d expected=-1/%d\n", result, actual, error);
        abort();
    }
}
static void writable(const char *path) {
    int fd = open(path, O_WRONLY | O_APPEND); assert(fd >= 0);
    assert(write(fd, "x", 1) == 1); assert(!close(fd));
}
static void denied(const char *path) { expect_error(open(path, O_WRONLY), EACCES); }
static int ruleset(void) {
    struct landlock_ruleset_attr attr = {.handled_access_fs = HANDLED};
    int fd = syscall(SYS_landlock_create_ruleset, &attr, sizeof(attr), 0);
    assert(fd >= 0); return fd;
}
static void add(int set, const char *path, unsigned long long access) {
    int fd = open(path, O_PATH | O_CLOEXEC); assert(fd >= 0);
    struct landlock_path_beneath_attr rule = {.allowed_access = access, .parent_fd = fd};
    assert(!syscall(SYS_landlock_add_rule, set, LANDLOCK_RULE_PATH_BENEATH, &rule, 0));
    assert(!close(fd));
}
static void restrict_to(int set) {
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    assert(!syscall(SYS_landlock_restrict_self, set, 0));
}
static void reaped(pid_t pid, int code) {
    int status;
    /* EVENT_WAIT: exact child termination; the runner deadline fails a hang. */
    pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    assert(result == pid && WIFEXITED(status) && WEXITSTATUS(status) == code);
}
static void basic(void) {
    int old = open("denied/file", O_WRONLY); assert(old >= 0);
    int path = open("denied/file", O_PATH); assert(path >= 0);
    int set = ruleset();
    add(set, "/", READ_RIGHTS);
    add(set, "allowed", READ_RIGHTS | WRITE_RIGHTS);
    int alias = dup(set); assert(alias >= 0); assert(!close(set));
    restrict_to(alias);
    /* Changing a ruleset FD must not widen a previously installed snapshot. */
    add(alias, "denied", WRITE_RIGHTS);
    assert(!close(alias));
    writable("allowed/file");
    int fd = open("denied/file", O_RDONLY); assert(fd >= 0); assert(!close(fd));
    denied("denied/file");
    denied("allowed/escape");
    denied("allowed/../denied/file");
    /* A directory grant is about the selected path, not every hardlink alias. */
    writable("allowed/hardlink");
    denied("denied/hardlink");
    expect_error(open("denied/new", O_CREAT | O_WRONLY, 0600), EACCES);
    assert(!mkdir("allowed/newdir", 0700));
    assert(!rmdir("allowed/newdir"));
    expect_error(mkdir("denied/newdir", 0700), EACCES);
    assert(!symlink("file", "allowed/newlink"));
    assert(!unlink("allowed/newlink"));
    expect_error(symlink("file", "denied/newlink"), EACCES);
    assert(!mkfifo("allowed/fifo",0600)); assert(!unlink("allowed/fifo"));
    expect_error(mkfifo("denied/fifo",0600),EACCES);
    int socket_fd=socket(AF_UNIX,SOCK_STREAM,0); assert(socket_fd>=0);
    struct sockaddr_un address={.sun_family=AF_UNIX};
    strcpy(address.sun_path,"denied/socket");
    expect_error(bind(socket_fd,(struct sockaddr *)&address,sizeof(address)),EACCES);
    strcpy(address.sun_path,"allowed/socket");
    assert(!bind(socket_fd,(struct sockaddr *)&address,sizeof(address)));
    assert(!close(socket_fd) && !unlink("allowed/socket"));
    expect_error(unlink("denied/file"), EACCES);
    assert(!rename("allowed/file", "allowed/renamed"));
    writable("allowed/renamed");
    assert(!rename("allowed/renamed", "allowed/file"));
    /* ABI 1 disallows reparenting even between two writable directories. */
    expect_error(rename("allowed/file", "allowed/sub/file"), EXDEV);
    expect_error(link("allowed/file", "allowed/sub/link"), EXDEV);
    /* Existing data descriptions keep their authority; path reopening does not. */
    assert(write(old, "p", 1) == 1); assert(!close(old));
    char proc[64]; snprintf(proc, sizeof(proc), "/proc/self/fd/%d", path);
    denied(proc); assert(!close(path));
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) { denied("denied/file"); writable("allowed/file"); _exit(0); }
    reaped(pid, 0);
    char *argv[] = {"landlock-contract", NULL};
    char *env[] = {NULL};
    expect_error(execve("/proc/self/exe", argv, env), EACCES);
    puts("PASS landlock paths, aliases, preopened FDs, fork and exec denial");
}
static void validation(void) {
    struct landlock_ruleset_attr attr={0};
    expect_error(syscall(SYS_landlock_create_ruleset,&attr,sizeof(attr),0),ENOMSG);
    attr.handled_access_fs=1ULL<<63;
    expect_error(syscall(SYS_landlock_create_ruleset,&attr,sizeof(attr),0),EINVAL);
    attr.handled_access_fs=HANDLED;
    expect_error(syscall(SYS_landlock_create_ruleset,&attr,7,0),EINVAL);
    expect_error(syscall(SYS_landlock_create_ruleset,NULL,0,1U<<31),EINVAL);
    int set=ruleset(), file=open("allowed/file",O_PATH); assert(file>=0);
    struct landlock_path_beneath_attr rule={.allowed_access=LANDLOCK_ACCESS_FS_READ_DIR,.parent_fd=file};
    expect_error(syscall(SYS_landlock_add_rule,set,LANDLOCK_RULE_PATH_BENEATH,&rule,0),EINVAL);
    rule.allowed_access=0;
    expect_error(syscall(SYS_landlock_add_rule,set,LANDLOCK_RULE_PATH_BENEATH,&rule,0),ENOMSG);
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    expect_error(syscall(SYS_landlock_restrict_self,set,1U<<31),EINVAL);
    char byte;
    expect_error(read(set,&byte,1),EINVAL);
    assert(!close(file)); assert(!close(set));
    puts("PASS landlock attribute, access-mask, descriptor and flag validation");
}
static void exec_descriptor(void) {
    int fd=open("/proc/self/exe",O_PATH); assert(fd>=0);
    int set=ruleset(); add(set,"/",READ_RIGHTS); restrict_to(set); close(set);
    char *argv[]={"landlock-contract",NULL}; char *env[]={NULL};
    expect_error(syscall(SYS_execveat,fd,"",argv,env,AT_EMPTY_PATH),EACCES);
    assert(!close(fd));
    puts("PASS landlock execveat rechecks a preopened executable");
}
static void exec_native_descriptor(void) {
    int input=open("/proc/self/exe",O_RDONLY|O_CLOEXEC); assert(input>=0);
    int output=open("allowed/executable",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0700); assert(output>=0);
    char bytes[65536]; ssize_t n;
    while ((n=read(input,bytes,sizeof(bytes)))>0) assert(write(output,bytes,(size_t)n)==n);
    assert(!n); close(input); close(output);
    int fd=open("allowed/executable",O_PATH|O_CLOEXEC); assert(fd>=0);
    for (int detached=0;detached<2;++detached) {
        if (detached) assert(!unlink("allowed/executable"));
        pid_t pid=fork(); assert(pid>=0);
        if (!pid) {
            int set=ruleset(); add(set,"/",READ_RIGHTS); restrict_to(set); close(set);
            char *args[]={"landlock-contract",NULL}; char *env[]={NULL};
            expect_error(syscall(SYS_execveat,fd,"",args,env,AT_EMPTY_PATH),EACCES);
            _exit(0);
        }
        reaped(pid,0);
    }
    close(fd);
    puts("PASS landlock denies copied and open-unlinked native executable FDs");
}
static void read_denial(void) {
    struct landlock_ruleset_attr attr={.handled_access_fs=READ_RIGHTS};
    int set=syscall(SYS_landlock_create_ruleset,&attr,sizeof(attr),0); assert(set>=0);
    add(set,"allowed",READ_RIGHTS); restrict_to(set); close(set);
    int fd=open("allowed/file",O_RDONLY); assert(fd>=0); close(fd);
    expect_error(open("denied/file",O_RDONLY),EACCES);
    expect_error(open("denied",O_RDONLY|O_DIRECTORY),EACCES);
    expect_error(open("/proc/self/status",O_RDONLY),EACCES);
    expect_error(open("/proc/self/cmdline",O_RDONLY),EACCES);
    puts("PASS landlock denies data, directory and proc reads without grants");
}
static void stacking(void) {
    int first = ruleset(), second = ruleset();
    add(first, "/", READ_RIGHTS); add(first, "allowed", WRITE_RIGHTS);
    add(second, "/", READ_RIGHTS); add(second, "allowed/sub", WRITE_RIGHTS);
    restrict_to(first); restrict_to(second);
    assert(!close(first)); assert(!close(second));
    denied("allowed/file"); writable("allowed/sub/file-existing");
    int wider = ruleset(); add(wider, "/", HANDLED); restrict_to(wider); assert(!close(wider));
    denied("allowed/file"); writable("allowed/sub/file-existing");
    puts("PASS landlock stacked intersection cannot widen authority");
}
static void object_identity(void) {
    int set = ruleset(); add(set, "/", READ_RIGHTS); add(set, "allowed", WRITE_RIGHTS);
    assert(!rename("allowed", "moved"));
    assert(!mkdir("allowed", 0700));
    int fd = open("allowed/file", O_CREAT | O_EXCL | O_WRONLY, 0600); assert(fd >= 0); close(fd);
    restrict_to(set); assert(!close(set));
    writable("moved/file"); denied("allowed/file");
    puts("PASS landlock rule follows the retained directory, not a pathname replacement");
}
struct thread_case { int ready[2], done[2]; };
static void *restricted_thread(void *argument) {
    struct thread_case *c = argument;
    int set = ruleset(); add(set, "/", READ_RIGHTS); restrict_to(set); assert(!close(set));
    denied("allowed/file");
    assert(write(c->ready[1], "r", 1) == 1);
    char value;
    /* EVENT_WAIT: unrestricted sibling has exercised its own policy; deadline fails. */
    assert(read(c->done[0], &value, 1) == 1);
    denied("allowed/file");
    return NULL;
}
static void threads(void) {
    struct thread_case c; assert(!pipe(c.ready)); assert(!pipe(c.done));
    pthread_t worker; assert(!pthread_create(&worker, NULL, restricted_thread, &c));
    char value;
    /* EVENT_WAIT: worker has installed its thread-local policy; deadline fails. */
    assert(read(c.ready[0], &value, 1) == 1);
    writable("allowed/file");
    assert(write(c.done[1], "d", 1) == 1);
    assert(!pthread_join(worker, NULL));
    for (unsigned i = 0; i < 2; ++i) { close(c.ready[i]); close(c.done[i]); }
    puts("PASS landlock does not restrict an existing sibling sharing files and cwd");
}
static void exec_inheritance(void) {
    char cwd[PATH_MAX]; assert(getcwd(cwd, sizeof(cwd)));
    int set = ruleset(); add(set, "/", READ_RIGHTS | LANDLOCK_ACCESS_FS_EXECUTE);
    restrict_to(set); assert(!close(set));
    char *argv[] = {"landlock-contract", "--exec-child", cwd, NULL};
    char *env[] = {NULL};
    execve("/proc/self/exe", argv, env);
    perror("exec inheritance"); _exit(90);
}
static void run(void (*fn)(void)) {
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) { fn(); _exit(0); }
    reaped(pid, 0);
}
static void readable(const char *path,int allowed) {
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if (!allowed) { expect_error(fd,EACCES); return; }
    assert(fd>=0); assert(!close(fd));
}
static void mount_paths(void) {
    assert(!unshare(CLONE_NEWNS));
    assert(!mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL));
    assert(!mkdir("denied/mount",0700) && !mkdir("allowed/mount",0700));
    assert(!mount("tmpfs","denied/mount","tmpfs",0,NULL));
    assert(!mkdir("denied/mount/nested",0700));
    assert(!mount("ramfs","denied/mount/nested","ramfs",0,NULL));
    const char *paths[]={"denied/mount/file","denied/mount/nested/file"};
    for (unsigned i=0;i<2;++i) {
        int fd=open(paths[i],O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0); close(fd);
    }
    assert(!mount("denied/mount","allowed/mount",NULL,MS_BIND|MS_REC,NULL));
    for (unsigned variant=0;variant<5;++variant) {
        pid_t pid=fork(); assert(pid>=0);
        if (!pid) {
            int set=ruleset();
            const char *grant=variant==0 || variant==4 ? "/" : variant==1 ? "allowed"
                : variant==2 ? "denied" : "denied/mount";
            add(set,grant,READ_RIGHTS); restrict_to(set); close(set);
            if (variant==4) {
                set=ruleset(); add(set,"allowed",READ_RIGHTS); restrict_to(set); close(set);
            }
            readable("denied/mount/file",variant==0 || variant==2 || variant==3);
            readable("allowed/mount/file",variant!=2);
            readable("denied/mount/nested/file",variant==0 || variant==2 || variant==3);
            readable("allowed/mount/nested/file",variant!=2);
            denied("allowed/mount/file"); denied("denied/mount/nested/file");
            int dir=open("allowed/mount",O_PATH|O_DIRECTORY); assert(dir>=0);
            int fd=openat(dir,"file",O_RDONLY);
            if (variant==2) expect_error(fd,EACCES); else { assert(fd>=0); close(fd); }
            char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d/file",dir);
            readable(path,variant!=2); close(dir);
            _exit(0);
        }
        reaped(pid,0);
    }
    assert(!umount("allowed/mount/nested") && !umount("allowed/mount"));
    assert(!umount("denied/mount/nested") && !umount("denied/mount"));
    assert(!rmdir("allowed/mount") && !rmdir("denied/mount"));
    puts("PASS landlock mount ancestry, nested stores, bind aliases, dirfd, proc reopen and policy intersection");
}
static const char *files[] = {"allowed/file", "allowed/sub/file-existing", "denied/file"};
static void prepare(void) {
    assert(!mkdir("allowed", 0700)); assert(!mkdir("allowed/sub", 0700));
    assert(!mkdir("denied", 0700));
    for (unsigned i = 0; i < sizeof(files)/sizeof(*files); ++i) {
        int fd = open(files[i], O_CREAT | O_EXCL | O_WRONLY, 0600); assert(fd >= 0); close(fd);
    }
    assert(!symlink("../denied/file", "allowed/escape"));
    assert(!link("denied/file", "allowed/hardlink"));
    assert(!link("allowed/file", "denied/hardlink"));
}
static void cleanup(void) {
    assert(!unlink("allowed/escape")); assert(!unlink("allowed/hardlink"));
    assert(!unlink("denied/hardlink"));
    for (unsigned i = 0; i < sizeof(files)/sizeof(*files); ++i) assert(!unlink(files[i]));
    assert(!rmdir("allowed/sub")); assert(!rmdir("allowed")); assert(!rmdir("denied"));
}
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc == 3 && !strcmp(argv[1], "--exec-child")) {
        assert(!chdir(argv[2])); denied("allowed/file"); denied("denied/file");
        assert(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1);
        puts("PASS landlock restrictions survive exec"); return 0;
    }
    int setup_only = argc == 3 && !strcmp(argv[1], "--setup-only");
    int mounts = argc == 3 && !strcmp(argv[1], "--mounts");
    if (!setup_only) {
        errno = 0;
        long abi = syscall(SYS_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
        int error = errno;
        printf("LANDLOCK uid=%u abi=%ld errno=%d\n", getuid(), abi, error);
        if (abi < 1) return 77;
        if (argc == 2 && !strcmp(argv[1], "--probe")) return 0;
    }
    if (argc != 2 && !setup_only && !mounts) { fprintf(stderr, "Usage: %s [--setup-only|--mounts] DIRECTORY | --probe\n", argv[0]); return 2; }
    assert(!chdir(argv[setup_only || mounts ? 2 : 1]));
    char directory[] = "landlock-XXXXXX"; assert(mkdtemp(directory));
    int parent = open(".", O_PATH | O_DIRECTORY); assert(parent >= 0);
    assert(!chdir(directory)); prepare();
    if (mounts) run(mount_paths);
    if (!setup_only) {
        run(validation); run(basic); run(exec_descriptor); run(exec_native_descriptor); run(read_denial);
        run(stacking); run(threads); run(exec_inheritance); run(object_identity);
        assert(!unlink("allowed/file")); assert(!rmdir("allowed")); assert(!rename("moved", "allowed"));
    }
    cleanup(); assert(!fchdir(parent)); close(parent); assert(!rmdir(directory));
    puts(setup_only ? "PASS fixture setup/cleanup only; Landlock enforcement NOT tested"
        : "PASS landlock filesystem contract");
    return 0;
}
