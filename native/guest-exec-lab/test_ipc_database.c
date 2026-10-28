#define _GNU_SOURCE
#include "sysv_ipc_internal.h"
#include "sysv_shm.h"
#include "private_tree.h"
#include "test_sql_plan.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <signal.h>
#include <pthread.h>
#include <unistd.h>

static int fixed_owner;
void test_storage_database(void);
void test_shm_bridge(const char *);
ssize_t __real_getrandom(void *,size_t,unsigned);
ssize_t __wrap_getrandom(void *data,size_t size,unsigned flags) {
    if (!fixed_owner || size!=sizeof(uint64_t)) return __real_getrandom(data,size,flags);
    memset(data,0x39,size); return (ssize_t)size;
}
static int owners(sqlite3 *db) {
    sqlite3_stmt *q=NULL;
    assert(sqlite3_prepare_v2(db,"SELECT count(*) FROM owners",-1,&q,NULL)==SQLITE_OK);
    assert(sqlite3_step(q)==SQLITE_ROW);
    int count=sqlite3_column_int(q,0); sqlite3_finalize(q); return count;
}
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned count=0;
    while (readdir(d)) ++count;
    closedir(d); return count;
}
static void ipc_lookup_indexes(sqlite3 *db) {
    const char *queries[]={
        "SELECT seq,type,data FROM messages WHERE object=?1 ORDER BY seq LIMIT 1",
        "SELECT seq,type,data FROM messages WHERE object=?1 AND type<=?2 ORDER BY type,seq LIMIT 1",
        "SELECT seq,type,data FROM messages WHERE object=?1 AND type!=?2 ORDER BY seq LIMIT 1",
        "SELECT seq,type,data FROM messages WHERE object=?1 AND type=?2 ORDER BY seq LIMIT 1",
        "SELECT 1 FROM undo WHERE object=?1",
        "SELECT u.object,u.n,u.delta,u.pid,u.pids,o.kind,o.info "
        "FROM undo u JOIN objects o ON o.id=u.object WHERE u.owner=?1 "
        "AND (?2=0 OR u.space=?2) ORDER BY u.space,u.object,u.n",
        "SELECT count(*) FROM waiters WHERE object=?1 AND n=?2 AND zero=?3"
    };
    for (unsigned i=0;i<sizeof(queries)/sizeof(*queries);++i) test_sql_indexed_lookup(db,queries[i]);
    puts("PASS IPC queue ordering and semaphore cleanup use indexed lookups");
}
static void *retain_shm(void *authority) {
    struct md_shm *s=authority;
    for (unsigned i=0;i<100000;++i) md_shm_close(md_shm_retain(s));
    md_shm_close(s);
    return NULL;
}
static void shared_authority_lifetime(const char *root) {
    unsigned before=descriptors();
    struct md_shm *s=NULL; assert(!md_shm_open(root,0,NULL,&s));
    pthread_t threads[4];
    for (unsigned i=0;i<4;++i)
        assert(!pthread_create(&threads[i],NULL,retain_shm,md_shm_retain(s)));
    md_shm_close(s);
    for (unsigned i=0;i<4;++i) assert(!pthread_join(threads[i],NULL));
    assert(descriptors()==before);
    puts("PASS SHM authority lifetime: concurrent namespace/mapping pins and once-only final close");
}
static void recorded_pid_identity(void) {
    struct md_pid_namespace *space=md_pid_namespace_new(NULL,NULL),*other=md_pid_namespace_new(NULL,NULL);
    assert(space && other);
    struct md_pid_identity *original=NULL,*replacement=NULL,*foreign=NULL;
    assert(!md_pid_identity_prepare(space,&original) && !md_pid_identity_publish(original,1234,1234));
    sqlite3 *db=NULL; sqlite3_stmt *q=NULL;
    assert(sqlite3_open(":memory:",&db)==SQLITE_OK);
    assert(sqlite3_prepare_v2(db,"SELECT ?1",-1,&q,NULL)==SQLITE_OK);
    assert(!md_ipc_pid_bind(q,1,original));
    md_pid_identity_reap(original); md_pid_identity_release(original);
    assert(!md_pid_identity_prepare(space,&replacement) && !md_pid_identity_publish(replacement,1234,1234));
    assert(!md_pid_identity_prepare(other,&foreign) && !md_pid_identity_publish(foreign,5678,5678));
    assert(sqlite3_step(q)==SQLITE_ROW);
    assert(md_pid_identity_number(replacement,space)==2);
    assert(md_ipc_pid_visible(q,0,replacement,1234)==1);
    assert(md_ipc_pid_visible(q,0,foreign,1234)==0);
    assert(md_ipc_pid_visible(q,0,NULL,1234)==1234);
    sqlite3_reset(q); assert(!md_ipc_pid_bind(q,1,NULL));
    assert(sqlite3_step(q)==SQLITE_ROW);
    assert(md_ipc_pid_visible(q,0,replacement,1234)==0);
    assert(md_ipc_pid_visible(q,0,NULL,1234)==1234);
    sqlite3_finalize(q); assert(sqlite3_close(db)==SQLITE_OK);
    md_pid_identity_release(replacement); md_pid_identity_release(foreign);
    md_pid_namespace_release(space); md_pid_namespace_release(other);
    puts("PASS IPC PID snapshots: native/nested/unrelated readers, reaping and native PID reuse");
}
static void dead_owner_other_namespace(const char *root) {
    unsigned before=descriptors();
    int ready[2]; assert(!pipe2(ready,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]);
        struct md_ipc *ipc=NULL; assert(!md_ipc_open(root,42,NULL,&ipc));
        struct md_ipc_pending p={.identity=md_identity_new(1000,1000),.pid=getpid(),.undo=1,
            .call={.nr=SYS_semget,.args={IPC_PRIVATE,2,IPC_CREAT|0600}}};
        assert(!md_ipc_begin(ipc));
        int id=(int)md_ipc_sem(ipc,&p); assert(id>0);
        struct sembuf ops[]={{0,1,SEM_UNDO},{0,-1,SEM_UNDO},{1,1,SEM_UNDO}};
        p.call.nr=SYS_semop; p.call.args[0]=id; p.input=ops; p.size=sizeof(ops);
        assert(!md_ipc_sem(ipc,&p));
        assert(!md_ipc_finish(ipc,0));
        assert(write(ready[1],&id,sizeof(id))==sizeof(id));
        for (;;) pause();
    }
    close(ready[1]);
    int id; assert(read(ready[0],&id,sizeof(id))==sizeof(id)); close(ready[0]);
    struct md_ipc *observer=NULL,*foreign=NULL;
    assert(!md_ipc_open(root,42,NULL,&observer));
    struct md_ipc_pending p={.identity=md_identity_new(1000,1000),
        .call={.nr=SYS_semctl,.args={id,1,GETVAL}}};
    assert(!md_ipc_begin(observer));
    assert(md_ipc_sem(observer,&p)==1);
    assert(!md_ipc_finish(observer,0));
    assert(!kill(child,SIGKILL));
    int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status));
    assert(!md_ipc_open(root,43,NULL,&foreign));
    assert(!md_ipc_begin(foreign));
    assert(md_ipc_sem(foreign,&p)==-EINVAL);
    assert(!md_ipc_finish(foreign,0));
    assert(!md_ipc_begin(observer));
    assert(owners(observer->database.handle)==2);
    for (unsigned i=0;i<2;++i) {
        p.call.args[1]=i;
        assert(md_ipc_sem(observer,&p)==0);
    }
    sqlite3_stmt *q=NULL;
    assert(!md_ipc_query(observer,"SELECT count(*) FROM undo",&q));
    assert(sqlite3_step(q)==SQLITE_ROW && !sqlite3_column_int(q,0));
    sqlite3_finalize(q);
    assert(!md_ipc_finish(observer,0));
    md_ipc_close(foreign); md_ipc_close(observer); md_identity_release(&p.identity);
    assert(descriptors()==before);
    puts("PASS IPC dead-owner recovery across namespaces: undo, zero adjustment, isolation and cleanup");
}
static void exported_shm(const char *root) {
    unsigned before=descriptors();
    struct md_identity user=md_identity_new(1000,1000),other=md_identity_new(1001,1001);
    struct md_shm *s=NULL,*foreign=NULL;
    assert(!md_shm_open(root,42,NULL,&s));
    assert(!md_shm_open(root,43,NULL,&foreign));
    struct md_shm_space *space=md_shm_space_new(); assert(space);
    struct md_shm_mapping *pending=NULL;
    unsigned long a[4]={IPC_PRIVATE,4096,IPC_CREAT|0600,0};
    int id=(int)md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_GET,a,NULL); assert(id>0);
    int fds[2]; uint64_t size=0;
    assert(md_shm_export(foreign,&user,getpid(),NULL,id,0,fds,&size)==-EINVAL);
    assert(fds[0]==-1 && fds[1]==-1 && !size);
    assert(md_shm_export(s,&other,getpid(),NULL,id,0,fds,&size)==-EACCES);
    assert(md_shm_export(s,&user,getpid(),NULL,id,2,fds,&size)==-EINVAL);
    assert(!md_shm_export(s,&user,getpid(),NULL,id,1,fds,&size) && size==4096);
    assert((fcntl(fds[0],F_GETFL)&O_ACCMODE)==O_RDONLY);
    assert(pwrite(fds[0],"bad",3,0)==-1 && errno==EBADF);
    assert(mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fds[0],0)==MAP_FAILED);
    close(fds[0]); close(fds[1]);
    assert(!md_shm_export(s,&user,getpid(),NULL,id,0,fds,&size));
    char *map=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fds[0],0); assert(map!=MAP_FAILED);
    strcpy(map,"shared");
    a[0]=id;
    struct shmid64_ds info;
    assert(!md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_STAT,a,&info));
    assert(info.shm_nattch==1);
    assert(!md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_REMOVE,a,NULL));
    md_shm_close(s); s=NULL;
    /* Only the external descriptor owns this removed segment now. */
    assert(!md_shm_open(root,42,NULL,&s));
    assert(!md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_STAT,a,&info));
    assert(info.shm_nattch==1);
    int second[2];
    assert(!md_shm_export(s,&user,getpid(),NULL,id,1,second,&size));
    char bytes[8]={0}; assert(pread(second[0],bytes,6,0)==6 && !strcmp(bytes,"shared"));
    close(second[0]); close(second[1]);
    int duplicate=dup(fds[1]); assert(duplicate>=0);
    close(fds[0]); close(fds[1]); munmap(map,4096);
    assert(!md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_STAT,a,&info));
    assert(info.shm_nattch==1);
    close(duplicate);
    /* A dead external lease cannot resurrect an already removed segment. */
    assert(md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_STAT,a,&info)==-EINVAL);
    assert(md_shm_export(s,&user,getpid(),NULL,id,0,fds,&size)==-EINVAL);
    a[0]=IPC_PRIVATE; a[1]=4096; a[2]=IPC_CREAT|0600;
    id=(int)md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_GET,a,NULL); assert(id>0);
    assert(!md_shm_export(s,&user,getpid(),NULL,id,0,fds,&size));
    a[0]=id;
    assert(!md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_REMOVE,a,NULL));
    int barrier[2]; assert(!pipe2(barrier,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(barrier[0]);
        assert(write(barrier[1],"R",1)==1);
        for (;;) pause();
    }
    close(barrier[1]); close(fds[0]); close(fds[1]);
    char ready; assert(read(barrier[0],&ready,1)==1); close(barrier[0]);
    assert(!md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_STAT,a,&info));
    assert(info.shm_nattch==1);
    assert(!kill(child,SIGKILL)); int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status));
    assert(md_shm_call(s,space,&pending,&user,getpid(),NULL,MD_SHM_STAT,a,&info)==-EINVAL);
    md_shm_space_release(space); md_shm_close(s); md_shm_close(foreign);
    md_identity_release(&user); md_identity_release(&other);
    assert(descriptors()==before);
    puts("PASS shared-memory exports: namespace, permissions, readonly FD, RMID, creator exit, dup lifetime, cleanup");
}
void test_ipc_database(void) {
    test_storage_database();
    recorded_pid_identity();
    char root[]="ipc-database-XXXXXX"; assert(mkdtemp(root));
    shared_authority_lifetime(root);
    exported_shm(root);
    test_shm_bridge(root);
    dead_owner_other_namespace(root);
    struct md_ipc *ipc=NULL,*rejected=NULL;
    fixed_owner=1;
    assert(!md_ipc_open(root,0,NULL,&ipc));
    ipc_lookup_indexes(ipc->database.handle);
    assert(owners(ipc->database.handle)==1);
    assert(md_ipc_open(root,0,NULL,&rejected)==-EEXIST && !rejected);
    assert(owners(ipc->database.handle)==1);
    fixed_owner=0;
    md_ipc_close(ipc);

    struct md_shm *shm=NULL,*rejected_shm=NULL;
    fixed_owner=1;
    assert(!md_shm_open(root,0,NULL,&shm));
    char path[256]; snprintf(path,sizeof(path),"%s/ipc/v2/shm/segments.db",root);
    sqlite3 *db=NULL; assert(sqlite3_open(path,&db)==SQLITE_OK);
    test_sql_indexed_lookup(db,"SELECT info,(SELECT coalesce(sum(n),0) FROM refs WHERE segment=?1) "
        "FROM segments WHERE id=?1 AND namespace=?2");
    assert(owners(db)==1);
    assert(md_shm_open(root,0,NULL,&rejected_shm)==-EEXIST && !rejected_shm);
    assert(owners(db)==1);
    fixed_owner=0;
    md_shm_close(shm);
    assert(owners(db)==0); assert(sqlite3_close(db)==SQLITE_OK);
    char orphan[256]; snprintf(orphan,sizeof(orphan),"%s/ipc/v2/shm/owner-0000000000000011",root);
    int lease=open(orphan,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(lease>=0);
    assert(!flock(lease,LOCK_EX|LOCK_NB));
    assert(!md_shm_open(root,0,NULL,&shm));
    assert(!access(orphan,F_OK)); md_shm_close(shm);
    close(lease);
    assert(!md_shm_open(root,0,NULL,&shm));
    assert(access(orphan,F_OK)==-1 && errno==ENOENT); md_shm_close(shm);
    assert(sqlite3_open(path,&db)==SQLITE_OK);
    assert(sqlite3_exec(db,"UPDATE metadata SET value=NULL WHERE name='boot'",NULL,NULL,NULL)==SQLITE_OK);
    assert(sqlite3_close(db)==SQLITE_OK);
    unsigned count=descriptors();
    for (unsigned i=0;i<8;++i) {
        assert(md_shm_open(root,0,NULL,&shm)==-EIO && !shm);
        assert(descriptors()==count);
    }
    snprintf(path,sizeof(path),"%s/ipc/v2/sysv/ipc.db",root);
    assert(sqlite3_open(path,&db)==SQLITE_OK);
    assert(sqlite3_exec(db,"UPDATE metadata SET value=NULL WHERE name='boot'",NULL,NULL,NULL)==SQLITE_OK);
    assert(sqlite3_close(db)==SQLITE_OK);
    for (unsigned i=0;i<8;++i) {
        assert(md_ipc_open(root,0,NULL,&ipc)==-EIO && !ipc);
        assert(descriptors()==count);
    }
    assert(!md_private_tree_discard(AT_FDCWD,root));
    puts("PASS IPC admission failure preserves live ownership and closes corrupt-database resources");
}
