#define _GNU_SOURCE
#include "task_resources.h"
#include "task_registry.h"
#include "task_status.h"
#include "mqueue_view.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

void test_fd_origins(void);
void test_ofd_order(void);
void test_user_namespace(void);
void test_control_poll(void);
void test_descriptor_inode(void);
void test_ipc_database(void);
static int descriptors(void) {
    DIR *dir = opendir("/proc/self/fd"); assert(dir);
    int count = 0;
    while (readdir(dir)) count++;
    closedir(dir); return count;
}
static void retained_proc_query(void) {
    int proc=open("/proc",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    int other=open("/dev",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    assert(proc>=0 && other>=0);
    const int flags[]={0,O_DIRECTORY,O_CLOEXEC,O_NOFOLLOW,O_NONBLOCK,O_LARGEFILE,O_NOCTTY,O_PATH};
    for (unsigned i=0;i<sizeof(flags)/sizeof(*flags);++i) {
        int value=O_RDONLY|O_DIRECTORY|flags[i];
        assert(md_domain_proc_fd_query(proc,"self/fd/",value));
        assert(md_domain_proc_fd_query(proc,"self/fd",value));
        assert(!md_domain_proc_fd_query(other,"self/fd/",value));
        assert(!md_domain_proc_fd_query(proc,"/proc/self/fd",value));
        assert(!md_domain_proc_fd_query(proc,"self/fd/0",value));
        assert(!md_domain_proc_fd_query(proc,"self/fd/../root",value));
        assert(!md_domain_proc_fd_query(proc,"self/fd",value|O_RDWR));
        assert(!md_domain_proc_fd_query(proc,"self/fd",value|O_WRONLY));
        assert(!md_domain_proc_fd_query(proc,"self/fd",value|O_CREAT));
        assert(!md_domain_proc_fd_query(proc,"self/fd",value|O_TRUNC));
        assert(!md_domain_proc_fd_query(proc,"self/fd",value|O_TMPFILE));
        assert(!md_domain_proc_fd_query(proc,"self/fd",value|(1U<<31)));
    }
    assert(!close(proc) && !close(other));
}
struct root_thread {
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    pid_t tid;
    int release;
};
static void *hold_thread(void *argument) {
    struct root_thread *state=argument;
    assert(!pthread_mutex_lock(&state->mutex));
    state->tid=(pid_t)syscall(SYS_gettid);
    assert(!pthread_cond_signal(&state->condition));
    /* EVENT_WAIT: the fixture releases the owner after root/copy validation;
     * the fixture alarm bounds missing progress. */
    while (!state->release) assert(!pthread_cond_wait(&state->condition,&state->mutex));
    assert(!pthread_mutex_unlock(&state->mutex));
    return NULL;
}
static void nonleader_root(void) {
    int dumpable=prctl(PR_GET_DUMPABLE); assert(dumpable>=0);
    for (int protected=0;protected<2;++protected) {
        assert(!prctl(PR_SET_DUMPABLE,!protected));
        struct root_thread state={.mutex=PTHREAD_MUTEX_INITIALIZER,.condition=PTHREAD_COND_INITIALIZER};
        pthread_t thread; assert(!pthread_create(&thread,NULL,hold_thread,&state));
        assert(!pthread_mutex_lock(&state.mutex));
        /* EVENT_WAIT: the exact non-leader publishes its identity while alive. */
        while (!state.tid) assert(!pthread_cond_wait(&state.condition,&state.mutex));
        assert(state.tid!=getpid());
        struct md_guest_domain *root=md_domain_new(); assert(root);
        assert(!md_domain_restrict(root,state.tid,"/proc/self/fdinfo"));
        assert(!md_domain_chdir_root(root));
        struct md_guest_domain *copy=md_domain_fork(root,0); assert(copy);
        md_domain_release(root);
        assert(!md_domain_chdir_root(copy));
        assert(md_domain_path_error(copy)==-EACCES);
        md_domain_release(copy);
        state.release=1; assert(!pthread_cond_signal(&state.condition));
        assert(!pthread_mutex_unlock(&state.mutex));
        /* EVENT_WAIT: exact thread completion before destroying shared state. */
        assert(!pthread_join(thread,NULL));
        assert(!pthread_cond_destroy(&state.condition));
        assert(!pthread_mutex_destroy(&state.mutex));
    }
    assert(!prctl(PR_SET_DUMPABLE,dumpable));
}
static void contract(void) {
    struct md_task_resources parent, child, sibling;
    struct md_mount_namespace *mounts=md_mount_namespace_new(); assert(mounts);
    assert(!md_resources_init(&parent,mounts));
    md_mount_namespace_release(mounts);
    assert(!md_resources_fork(&parent, CLONE_FS | CLONE_SYSVSEM, &child));
    assert(child.fs == parent.fs && child.sem_undo == parent.sem_undo);
    assert(child.mounts==parent.mounts);
    assert(!md_resources_fork(&parent, 0, &sibling));
    assert(sibling.fs != parent.fs && sibling.sem_undo != parent.sem_undo);
    assert(sibling.files!=parent.files);
    assert(sibling.mounts==parent.mounts);
    struct md_resource_change change;
    assert(!md_resources_unshare_prepare(&child, CLONE_FS | CLONE_SYSVSEM,NULL,&change));
    assert(change.next.fs != child.fs && change.next.sem_undo != child.sem_undo);
    assert(!md_resources_unshare_finish(&child,NULL,&change, -EINVAL));
    assert(child.fs == parent.fs && child.sem_undo == parent.sem_undo);
    assert(!change.next.fs && !change.next.sem_undo && !change.next.mounts);
    assert(!md_resources_unshare_prepare(&child, CLONE_FILES,NULL,&change));
    assert(!change.next.fs && !change.next.sem_undo);
    assert(!md_resources_unshare_finish(&child,NULL,&change, 0));
    assert(child.fs == parent.fs && child.sem_undo == parent.sem_undo);
    assert(!md_resources_unshare_prepare(&child, CLONE_FS,NULL,&change));
    assert(!md_resources_unshare_finish(&child,NULL,&change, 0));
    assert(child.fs != parent.fs && child.sem_undo == parent.sem_undo);
    assert(child.mounts==parent.mounts);
    assert(!md_resources_unshare_prepare(&child, CLONE_SYSVSEM,NULL,&change));
    assert(!md_resources_unshare_finish(&child,NULL,&change, 0));
    assert(child.sem_undo != parent.sem_undo);
    md_domain_pin(child.fs);
    assert(!md_resources_unshare_prepare(&child, CLONE_FS,NULL,&change));
    assert(change.next.fs == child.fs); // A sole owner needs no duplicate.
    assert(!md_resources_unshare_finish(&child,NULL,&change, 0));
    md_domain_unpin(child.fs);
    assert(!md_domain_restrict(child.fs, getpid(), "/proc/self/fdinfo"));
    assert(!md_domain_chdir_root(child.fs));
    assert(md_domain_restricted(child.fs) && !md_domain_restricted(parent.fs));
    assert(!md_resources_release(&sibling));
    assert(!md_resources_fork(&child, CLONE_FS, &sibling));
    assert(!md_resources_unshare_prepare(&sibling, CLONE_FS,NULL,&change));
    assert(change.next.fs != child.fs);
    assert(!md_resources_unshare_finish(&sibling,NULL,&change, 0));
    assert(md_domain_restricted(sibling.fs) && md_domain_path_error(sibling.fs) == -EACCES);
    assert(!md_resources_release(&sibling));
    assert(!md_resources_release(&child));
    assert(!md_resources_release(&parent));
}
static void root_is(struct md_guest_domain *fs, const struct md_fs_location *expected) {
    struct md_fs_location root; struct md_fs_anchor *cwd=NULL;
    md_domain_paths(fs,&root,&cwd);
    assert(root.mount==expected->mount && !strcmp(root.object,expected->object));
    md_fs_anchor_release(cwd);
}
static void pending_root_relocation(void) {
    struct md_mount_namespace *view=md_mount_namespace_new(), *other=md_mount_namespace_new();
    assert(view && other);
    struct md_task_resources parent, child;
    assert(!md_resources_init(&parent,view));
    const struct md_fs_location old={.mount=71,.object="11111111111111111111111111111111"};
    const struct md_fs_location next={.mount=72,.object="22222222222222222222222222222222"};
    assert(!md_domain_set_root(parent.fs,&old));
    assert(!md_resources_fork(&parent,CLONE_FS,&child));
    struct md_resource_change pending;
    assert(!md_resources_unshare_prepare(&child,CLONE_FS,NULL,&pending));
    assert(pending.next.fs && pending.next.fs!=parent.fs);
    struct md_guest_domain *forked=md_domain_fork(parent.fs,0); assert(forked);
    struct md_guest_domain *isolated=md_domain_fork(parent.fs,0); assert(isolated);
    md_domain_bind_mounts(isolated,other);
    /* Neither candidate has a task registry entry. Root relocation must cover
     * copied-but-unpublished contexts, without touching another namespace. */
    md_domain_relocate_roots(view,&old,&next,NULL,NULL);
    root_is(parent.fs,&next); root_is(pending.next.fs,&next); root_is(forked,&next);
    root_is(isolated,&old);
    assert(!md_resources_unshare_finish(&child,NULL,&pending,0)); root_is(child.fs,&next);
    md_domain_release(forked); md_domain_release(isolated);
    assert(!md_resources_release(&child)); assert(!md_resources_release(&parent));
    md_mount_namespace_release(view); md_mount_namespace_release(other);
}
static void pid_resource_transaction(void) {
    struct md_task_resources owner, child;
    struct md_identity identity=md_identity_new(0,0);
    struct md_resource_change change;
    assert(!md_resources_init(&owner,NULL));
    assert(md_resources_clone_supported(CLONE_NEWPID));
    assert(!md_resources_clone_supported(CLONE_NEWPID|CLONE_THREAD));
    assert(md_resources_clone_supported(CLONE_NEWPID|CLONE_PARENT));
    assert(!md_resources_unshare_prepare(&owner,CLONE_NEWPID,&identity,&change));
    assert(change.next.pid_children && !owner.pid_children && !owner.pid);
    assert(!md_resources_unshare_finish(&owner,NULL,&change,-EINVAL));
    assert(!owner.pid_children && !change.next.pid_children);
    assert(!md_resources_unshare_prepare(&owner,CLONE_NEWPID,&identity,&change));
    assert(!md_resources_unshare_finish(&owner,NULL,&change,0));
    assert(owner.pid_children && !owner.pid);
    assert(!md_resources_fork(&owner,0,&child));
    assert(child.pid_children==owner.pid_children && !child.pid);
    assert(md_resources_unshare_prepare(&owner,CLONE_NEWPID,&identity,&change)==-EINVAL);
    assert(!md_pid_identity_prepare(child.pid_children,&child.pid));
    assert(!md_pid_identity_publish(child.pid,20001,20001));
    child.pid_group=md_pid_identity_retain(child.pid);
    assert(!md_resources_unshare_prepare(&child,CLONE_NEWPID,&identity,&change));
    assert(md_pid_namespace_parent(change.next.pid_children)==owner.pid_children);
    assert(!md_resources_unshare_finish(&child,NULL,&change,0));
    assert(child.pid_children!=owner.pid_children);
    assert(!md_resources_release(&owner));
    assert(md_pid_identity_number(child.pid,md_pid_identity_namespace(child.pid))==1);
    assert(!md_resources_release(&child));
    md_identity_release(&identity);
}
static void network_resource_transaction(void) {
    struct md_task_resources owner, child;
    struct md_identity identity=md_identity_new(0,0);
    struct md_resource_change change;
    assert(!md_resources_init(&owner,NULL));
    assert(md_resources_clone_supported(CLONE_NEWNET));
    assert(!md_network_namespace_managed(owner.network));
    assert(!md_resources_fork(&owner,0,&child) && child.network==owner.network);
    assert(!md_resources_unshare_prepare(&owner,CLONE_NEWNET,&identity,&change));
    assert(change.next.network && md_network_namespace_managed(change.next.network));
    assert(!md_resources_unshare_finish(&owner,NULL,&change,-EINVAL));
    assert(child.network==owner.network && !change.next.network);
    assert(!md_resources_unshare_prepare(&owner,CLONE_NEWNET,&identity,&change));
    assert(!md_resources_unshare_finish(&owner,NULL,&change,0));
    assert(owner.network!=child.network && md_network_namespace_managed(owner.network));
    assert(md_network_namespace_inode(owner.network)!=md_network_namespace_inode(child.network));
    struct md_fd_object *socket=md_network_socket_new(owner.network); assert(socket);
    assert(!md_resources_exec(&owner) && md_network_socket_offline(socket));
    assert(!md_resources_release(&owner));
    assert(md_network_socket_offline(socket)); md_fd_object_release(socket);
    assert(!md_network_namespace_managed(child.network));
    assert(!md_resources_release(&child)); md_identity_release(&identity);
}
static void file_table_snapshot(void) {
    struct md_task_registry *registry=md_task_registry_create(); assert(registry);
    struct md_identity id=md_identity_new(0,0);
    struct md_task_resources task; assert(!md_resources_init(&task,NULL));
    pid_t tid=getpid()+1234;
    assert(!md_task_registry_publish(registry,tid,tid,&id,&task));
    struct md_task_snapshot proc;
    assert(!md_task_registry_proc(registry,tid,&proc));
    assert(proc.tid==tid && proc.pid==task.pid && proc.cgroup==task.cgroup && !proc.fs && !proc.files);
    md_task_snapshot_release(&proc);
    struct md_fd_origins *files;
    assert(md_task_registry_files(registry,tid,tid+1,&files)==-EPERM && !files);
    assert(md_task_registry_files(registry,tid+1,0,&files)==-ESRCH && !files);
    assert(!md_task_registry_files(registry,tid,tid,&files) && files==task.files);
    md_task_registry_retire(registry,tid,&id);
    assert(!md_task_registry_proc(registry,tid,&proc));
    assert(proc.tid==tid && !proc.fs && !proc.files);
    md_task_snapshot_release(&proc);
    struct md_fd_origins *missing;
    assert(md_task_registry_files(registry,tid,0,&missing)==-ESRCH && !missing);
    md_task_registry_reap(registry,tid); md_task_registry_destroy(registry);
    assert(!md_resources_release(&task));
    md_fd_origins_unpin(files); md_identity_release(&id);
    puts("PASS file-table snapshot: shared admission, retained identity, denied peer and retired task");
}
struct mask_reader {
    const struct md_task_snapshot *snapshot;
    pthread_barrier_t *start;
};
static void *read_mask(void *argument) {
    struct mask_reader *reader=argument;
    /* EVENT_WAIT: all readers race the same first proc-handle publication. */
    int result=pthread_barrier_wait(reader->start);
    assert(!result || result==PTHREAD_BARRIER_SERIAL_THREAD);
    for (unsigned i=0;i<32;++i) assert(md_task_snapshot_umask(reader->snapshot)==0027);
    return NULL;
}
static void live_umask_snapshot(void) {
    int before=descriptors();
    pid_t zombie=fork(); assert(zombie>=0);
    if (!zombie) _exit(0);
    siginfo_t death={0};
    /* EVENT_WAIT: WNOWAIT retains a dead proc entry after exit_fs(). */
    assert(!waitid(P_PID,zombie,&death,WEXITED|WNOWAIT));
    assert(death.si_pid==zombie && md_task_umask(zombie)==-ESRCH);
    assert(waitpid(zombie,NULL,0)==zombie);
    struct md_task_registry *registry=md_task_registry_create(); assert(registry);
    struct md_identity id=md_identity_new(0,0);
    struct root_thread state={.mutex=PTHREAD_MUTEX_INITIALIZER,.condition=PTHREAD_COND_INITIALIZER};
    pthread_t owner; assert(!pthread_create(&owner,NULL,hold_thread,&state));
    assert(!pthread_mutex_lock(&state.mutex));
    /* EVENT_WAIT: the fixture retains this native task until proc checks finish. */
    while (!state.tid) assert(!pthread_cond_wait(&state.condition,&state.mutex));
    assert(!md_task_registry_publish(registry,state.tid,getpid(),&id,NULL));
    struct md_task_snapshot first,second;
    assert(!md_task_registry_read(registry,state.tid,0,&first));
    mode_t saved=umask(0027);
    pthread_barrier_t start; assert(!pthread_barrier_init(&start,NULL,8));
    struct mask_reader reader={&first,&start}; pthread_t readers[8];
    for (unsigned i=0;i<8;++i) assert(!pthread_create(&readers[i],NULL,read_mask,&reader));
    /* EVENT_WAIT: join every reader before changing the shared kernel umask. */
    for (unsigned i=0;i<8;++i) assert(!pthread_join(readers[i],NULL));
    assert(!pthread_barrier_destroy(&start));
    assert(descriptors()==before+1);
    for (unsigned mask=0;mask<=0777;mask+=7) {
        umask(mask); assert(md_task_snapshot_umask(&first)==mask);
    }
    umask(0071);
    id.no_new_privs=1;
    assert(!md_task_registry_publish(registry,state.tid,getpid(),&id,NULL));
    assert(!md_task_registry_read(registry,state.tid,0,&second));
    assert(first.owner!=second.owner && md_task_snapshot_umask(&second)==0071);
    assert(descriptors()==before+2);
    md_task_snapshot_release(&first);
    assert(descriptors()==before+1);
    state.release=1; assert(!pthread_cond_signal(&state.condition));
    assert(!pthread_mutex_unlock(&state.mutex));
    /* EVENT_WAIT: join observes clear_child_tid, which can precede exit_fs.
     * Wait for kernel task removal before testing the retained proc handle;
     * the two-second bound fails rather than treating a live umask as dead. */
    assert(!pthread_join(owner,NULL));
    struct timespec deadline,now;
    assert(!clock_gettime(CLOCK_MONOTONIC,&deadline)); deadline.tv_sec+=2;
    while (!syscall(SYS_tgkill,getpid(),state.tid,0)) {
        assert(!clock_gettime(CLOCK_MONOTONIC,&now));
        assert(now.tv_sec<deadline.tv_sec || (now.tv_sec==deadline.tv_sec && now.tv_nsec<deadline.tv_nsec));
        sched_yield();
    }
    assert(errno==ESRCH);
    long exited_mask=md_task_snapshot_umask(&second);
    if (exited_mask!=-ESRCH) fprintf(stderr,"exited task umask: %ld\n",exited_mask);
    assert(exited_mask==-ESRCH);
    md_task_registry_retire(registry,state.tid,&id);
    md_task_registry_destroy(registry);
    assert(descriptors()==before+1);
    md_task_snapshot_release(&second);
    assert(descriptors()==before);
    umask(saved); md_identity_release(&id);
    assert(!pthread_cond_destroy(&state.condition));
    assert(!pthread_mutex_destroy(&state.mutex));
    puts("PASS live umask: concurrent handle publication, CLONE_FS updates, generation release and task exit");
}
struct snapshot_reader {
    struct md_task_registry *registry;
    pid_t tid;
    struct md_fd_origins *files[2];
};
static void *read_snapshots(void *argument) {
    const struct snapshot_reader *reader=argument;
    for (unsigned i=0;i<4096;++i) {
        struct md_task_snapshot value;
        assert(!md_task_registry_read(reader->registry,reader->tid,reader->tid,&value));
        unsigned index=value.identity.uid.fs-101;
        assert(index<2 && value.files==reader->files[index]);
        assert(value.owner && value.tid==reader->tid);
        md_task_snapshot_release(&value);
    }
    return NULL;
}
static void resource_generations(void) {
    int before=descriptors();
    struct md_task_registry *registry=md_task_registry_create(); assert(registry);
    struct md_task_resources task[2];
    struct md_identity id[2]={md_identity_new(101,101),md_identity_new(102,102)};
    for (unsigned i=0;i<2;++i) assert(!md_resources_init(&task[i],NULL));
    pid_t tid=getpid()+1234;
    assert(!md_task_registry_publish(registry,tid,tid,&id[0],&task[0]));
    struct md_task_snapshot first,second,changed,denied;
    assert(!md_task_registry_read(registry,tid,0,&first));
    assert(!md_task_registry_publish(registry,tid,tid,&id[0],&task[0]));
    struct md_fs_location root={.object="0123456789abcdef0123456789abcdef"};
    assert(!md_domain_set_root(task[0].fs,&root));
    assert(!md_task_registry_read(registry,tid,0,&second));
    assert(first.owner==second.owner && !first.root.object[0]);
    assert(!strcmp(second.root.object,root.object));
    md_task_snapshot_release(&second);
    assert(!md_task_registry_publish(registry,tid,tid,&id[0],&task[1]));
    assert(!md_task_registry_read(registry,tid,0,&changed));
    assert(changed.owner!=first.owner && changed.files==task[1].files && first.files==task[0].files);
    assert(md_task_registry_read(registry,tid,tid+1,&denied)==-EPERM && !denied.owner);
    assert(!md_task_registry_publish(registry,tid,tid,&id[1],&task[1]));
    assert(!md_task_registry_read(registry,tid,0,&second));
    assert(second.owner!=changed.owner && changed.identity.uid.fs==101 && second.identity.uid.fs==102);
    md_task_snapshot_release(&changed); md_task_snapshot_release(&second);
    struct snapshot_reader reader={registry,tid,{task[0].files,task[1].files}};
    pthread_t thread; assert(!pthread_create(&thread,NULL,read_snapshots,&reader));
    for (unsigned i=0;i<4096;++i)
        assert(!md_task_registry_publish(registry,tid,tid,&id[i%2],&task[i%2]));
    /* EVENT_WAIT: reader completes before retiring its registry identity. */
    assert(!pthread_join(thread,NULL));
    md_task_registry_retire(registry,tid,&id[1]);
    assert(md_task_registry_probe(registry,tid,0,&denied)==-ESTALE && !denied.owner);
    md_task_registry_reap(registry,tid);
    assert(!md_task_registry_publish(registry,tid,tid,&id[1],&task[1]));
    assert(!md_task_registry_read(registry,tid,0,&second));
    assert(second.owner!=first.owner && first.identity.uid.fs==101 && second.identity.uid.fs==102);
    md_task_registry_forget(registry,tid); md_task_registry_destroy(registry);
    for (unsigned i=0;i<2;++i) {
        assert(!md_resources_release(&task[i])); md_identity_release(&id[i]);
    }
    assert(first.identity.uid.fs==101 && second.identity.uid.fs==102);
    md_task_snapshot_release(&first); md_task_snapshot_release(&second);
    assert(descriptors()==before);
    puts("PASS task generations: atomic resource/identity publication, fresh roots, reuse and retained lifetime");
}
static void ipc_mount_lifetime(void) {
    char path[]="/data/local/tmp/md-mqueue-owner-XXXXXX";
    assert(mkdtemp(path));
    int parent=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
    int before=descriptors();
    for (unsigned i=0;i<40;++i) {
        struct md_identity id=md_identity_new(0,0);
        struct md_task_resources task; assert(!md_resources_init(&task,NULL));
        struct md_task_registry *registry=md_task_registry_create(); assert(registry);
        struct md_mqueue_view *view=md_ipc_namespace_queues(task.ipc,parent); assert(view);
        assert(view==md_ipc_namespace_queues(task.ipc,parent));
        assert(!md_ipc_namespace_active(task.ipc));
        assert(!md_task_registry_publish(registry,getpid(),getpid(),&id,&task));
        struct md_task_snapshot snapshot;
        assert(!md_task_registry_read(registry,getpid(),0,&snapshot));
        assert(snapshot.ipc==task.ipc);
        md_task_registry_forget(registry,getpid());
        md_task_registry_destroy(registry);
        assert(!md_resources_release(&task));
        struct stat st;
        assert(!md_mqueue_stat(view,md_mqueue_root(view),&st) && st.st_size==40);
        assert(md_ipc_namespace_queues(snapshot.ipc,-1)==view);
        md_task_snapshot_release(&snapshot);
        md_identity_release(&id);
        assert(descriptors()==before);
    }
    close(parent);
    assert(!rmdir(path));
    puts("PASS mqueue namespace custody: snapshots, final-owner cleanup and native FD baseline");
}
static int no_landlock_rule(void *context,const struct md_landlock_key *key) {
    (void)context; (void)key; assert(0); return 0;
}
static void landlock_resources(void) {
    errno=0; assert(!md_landlock_ruleset_new(0) && errno==ENOMSG);
    errno=0; assert(!md_landlock_ruleset_new(MD_LL_ALL+1) && errno==EINVAL);
    struct md_landlock_ruleset *rules=md_landlock_ruleset_new(MD_LL_WRITE_FILE); assert(rules);
    struct md_task_resources parent,child;
    assert(!md_resources_init(&parent,NULL));
    for (unsigned i=0;i<MD_LANDLOCK_LAYERS;++i) {
        struct md_landlock_policy *next=md_landlock_restrict(parent.landlock,rules); assert(next);
        md_landlock_release(parent.landlock); parent.landlock=next;
    }
    errno=0; assert(!md_landlock_restrict(parent.landlock,rules) && errno==E2BIG);
    md_landlock_ruleset_release(rules);
    assert(md_landlock_check(parent.landlock,MD_LL_WRITE_FILE,no_landlock_rule,NULL)==-EACCES);
    assert(!md_landlock_check(parent.landlock,MD_LL_READ_FILE,no_landlock_rule,NULL));
    assert(!md_resources_fork(&parent,CLONE_FILES|CLONE_FS,&child));
    assert(child.landlock==parent.landlock);
    struct md_task_registry *registry=md_task_registry_create(); assert(registry);
    struct md_identity id=md_identity_new(0,0);
    assert(!md_task_registry_publish(registry,getpid(),getpid(),&id,&parent));
    struct md_task_snapshot snapshot;
    assert(!md_task_registry_read(registry,getpid(),0,&snapshot));
    assert(snapshot.landlock==parent.landlock);
    assert(!md_resources_exec(&child) && child.landlock==parent.landlock);
    assert(!md_resources_release(&parent));
    md_task_registry_forget(registry,getpid()); md_task_registry_destroy(registry);
    assert(!md_resources_release(&child));
    assert(md_landlock_check(snapshot.landlock,MD_LL_WRITE_FILE,no_landlock_rule,NULL)==-EACCES);
    md_task_snapshot_release(&snapshot); md_identity_release(&id);
    puts("PASS Landlock immutable domains, layer bounds, fork/exec and worker snapshot custody");
}
void test_network_model(void);
void test_network_route(void);
void test_network_route_lifetimes(void);
void test_socket_filter(void);
void test_network_ethernet(void);
void test_network_endpoint(void);
void test_network_nft(void);
void test_network_launch(void);
void test_network_ipv6(void);
void test_network_tun(void);
void test_task_registry_exit(void);
void test_task_mapping(void);
int main(int argc,char **argv) {
    alarm(60);
    if(argc==2 && !strcmp(argv[1],"--tun")) { test_network_tun(); return 0; }
    test_task_mapping();
    test_ipc_database();
    test_task_registry_exit();
    test_network_model();
    test_network_route();
    test_network_route_lifetimes();
    test_socket_filter();
    test_network_ethernet();
    test_network_endpoint();
    test_network_nft();
    test_network_launch();
    test_network_ipv6();
    test_network_tun();
    struct md_identity identity=md_identity_new(0,0);
    struct md_task_resources r; assert(!md_resources_init(&r,NULL));
    struct md_resource_change change;
    assert(md_resources_unshare_prepare(&r,CLONE_NEWTIME|CLONE_FS,NULL,&change)==-ENOTSUP);
    assert(!change.next.fs && !change.next.sem_undo);
    assert(md_resources_clone_supported(CLONE_NEWCGROUP));
    struct md_cgroup_namespace *initial=md_cgroup_namespace_retain(r.cgroup_namespace);
    struct md_cgroup_member *member=r.cgroup;
    assert(!md_resources_unshare_prepare(&r,CLONE_NEWCGROUP,&identity,&change));
    assert(change.next.cgroup_namespace && change.next.cgroup_namespace!=initial);
    assert(!md_resources_unshare_finish(&r,NULL,&change,-EINVAL) && r.cgroup_namespace==initial);
    assert(!md_resources_unshare_prepare(&r,CLONE_NEWCGROUP,&identity,&change));
    assert(!md_resources_unshare_finish(&r,NULL,&change,0));
    assert(r.cgroup_namespace!=initial && r.cgroup==member);
    assert(md_cgroup_namespace_inode(r.cgroup_namespace)!=md_cgroup_namespace_inode(initial));
    md_cgroup_namespace_release(initial);
    assert(md_resources_clone_supported(CLONE_NEWUTS));
    assert(md_resources_clone_supported(CLONE_NEWIPC));
    assert(!md_resources_clone_supported(CLONE_NEWIPC|CLONE_SYSVSEM));
    struct md_task_resources sibling;
    assert(!md_resources_fork(&r,0,&sibling) && sibling.uts==r.uts);
    assert(!md_uts_namespace_set(r.uts,0,"parent",6));
    assert(!md_resources_unshare_prepare(&r,CLONE_NEWUTS,&identity,&change));
    assert(change.next.uts && change.next.uts!=r.uts);
    assert(!md_resources_unshare_finish(&r,NULL,&change,-EINVAL) && r.uts==sibling.uts);
    assert(!md_resources_unshare_prepare(&r,CLONE_NEWUTS,&identity,&change));
    assert(!md_resources_unshare_finish(&r,NULL,&change,0) && r.uts!=sibling.uts);
    struct utsname names;
    md_uts_namespace_read(r.uts,&names); assert(!strcmp(names.nodename,"parent"));
    assert(!md_uts_namespace_set(r.uts,0,"child",5));
    md_uts_namespace_read(sibling.uts,&names); assert(!strcmp(names.nodename,"parent"));
    assert(!md_resources_release(&sibling));
    assert(md_resources_clone_supported(CLONE_NEWNS));
    assert(!md_resources_clone_supported(CLONE_NEWNS|CLONE_FS));
    assert(md_resources_unshare_prepare(&r,CLONE_NEWNS,NULL,&change)==-ENOTSUP);
    r.mounts=md_mount_namespace_new(); assert(r.mounts);
    assert(!md_resources_unshare_prepare(&r,CLONE_NEWNS,&identity,&change));
    assert(change.copy_mounts && !change.next.fs && !change.next.mounts);
    assert(md_resources_unshare_finish(&r,NULL,&change,0)==-EINVAL);
    assert(md_resources_unshare_prepare(&r, 1UL << 63,NULL,&change) == -EINVAL);
    assert(!md_resources_release(&r));
    int before = descriptors();
    retained_proc_query();
    file_table_snapshot();
    live_umask_snapshot();
    resource_generations();
    landlock_resources();
    for (unsigned i = 0; i < 100; i++) contract();
    for (unsigned i = 0; i < 100; i++) pending_root_relocation();
    nonleader_root();
    pid_resource_transaction();
    network_resource_transaction();
    ipc_mount_lifetime();
    assert(descriptors() == before);
    test_fd_origins();
    test_ofd_order();
    test_user_namespace();
    test_control_poll();
    test_descriptor_inode();
    puts("PASS resource ownership, transactional unshare, retained roots and descriptor lifetime");
    alarm(0);
    return 0;
}
