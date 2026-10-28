#define _GNU_SOURCE
#include "sysv_ipc_internal.h"
#include "interception.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>

static int value(struct md_ipc *s, int object, unsigned number, int *out, pid_t *pid,
        const struct md_pid_identity *observer) {
    sqlite3_stmt *q=NULL;
    int r=md_ipc_query(s,"SELECT value,pid,pids FROM sems WHERE object=?1 AND n=?2",&q);
    if (!r) {
        sqlite3_bind_int(q,1,object); sqlite3_bind_int(q,2,number); int step=sqlite3_step(q);
        if (step==SQLITE_ROW) {
            *out=sqlite3_column_int(q,0);
            if (pid) { *pid=md_ipc_pid_visible(q,2,observer,sqlite3_column_int(q,1)); if (*pid<0) r=*pid; }
        }
        else r=step==SQLITE_DONE ? -EINVAL : -EIO;
    }
    sqlite3_finalize(q); return r;
}
static int set(struct md_ipc *s, int object, unsigned number, int val, pid_t pid,
        const void *numbers,size_t size) {
    sqlite3_stmt *q=NULL;
    int r=md_ipc_query(s,"UPDATE sems SET value=?3,pid=?4,pids=?5 WHERE object=?1 AND n=?2",&q);
    if (!r) {
        sqlite3_bind_int(q,1,object); sqlite3_bind_int(q,2,number);
        sqlite3_bind_int(q,3,val); sqlite3_bind_int(q,4,pid);
        r=md_ipc_sql(sqlite3_bind_blob(q,5,numbers,size,SQLITE_STATIC));
        if (!r) r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); if (!r) s->changed=1; return r;
}
static int set_process(struct md_ipc *s,int object,unsigned number,int val,pid_t pid,
        const struct md_pid_identity *process) {
    struct md_pid_number_value numbers[MD_PID_LEVELS];
    unsigned count=md_pid_identity_export(process,numbers);
    return set(s,object,number,val,pid,numbers,count*sizeof(*numbers));
}
static int undo(struct md_ipc *s, struct md_ipc_pending *p, unsigned number, int delta) {
    sqlite3_stmt *q=NULL;
    int r=md_ipc_query(s,"SELECT delta FROM undo WHERE owner=?1 AND space=?2 AND object=?3 AND n=?4",&q);
    if (!r) {
        sqlite3_bind_int64(q,1,s->database.owner); sqlite3_bind_int64(q,2,p->undo);
        sqlite3_bind_int(q,3,p->call.args[0]); sqlite3_bind_int(q,4,number);
        int step=sqlite3_step(q);
        if (step==SQLITE_ROW) delta+=sqlite3_column_int(q,0); else if (step!=SQLITE_DONE) r=-EIO;
    }
    sqlite3_finalize(q); q=NULL;
    if (!r && (delta < -SEMAEM || delta > SEMAEM)) r=-ERANGE;
    if (!r) r=md_ipc_query(s,"INSERT INTO undo(owner,space,object,n,delta,pid,pids) VALUES(?1,?2,?3,?4,?5,?6,?7) "
        "ON CONFLICT(owner,space,object,n) DO UPDATE SET delta=excluded.delta,pid=excluded.pid,pids=excluded.pids",&q);
    if (!r) {
        sqlite3_bind_int64(q,1,s->database.owner); sqlite3_bind_int64(q,2,p->undo);
        sqlite3_bind_int(q,3,p->call.args[0]); sqlite3_bind_int(q,4,number);
        sqlite3_bind_int(q,5,delta); sqlite3_bind_int(q,6,p->pid);
        r=md_ipc_pid_bind(q,7,p->process); if (!r) r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); return r;
}
static int clear_undo(struct md_ipc *s, int object, int number) {
    sqlite3_stmt *q=NULL;
    int r=md_ipc_query(s,"DELETE FROM undo WHERE object=?1 AND (?2<0 OR n=?2)",&q);
    if (!r) { sqlite3_bind_int(q,1,object); sqlite3_bind_int(q,2,number); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q); return r;
}
int md_ipc_undo_apply(struct md_ipc *s, int64_t owner, uint64_t space) {
    sqlite3_stmt *q=NULL;
    /* Lease recovery spans namespaces; guest object lookup remains namespace-scoped. */
    int r=md_ipc_query(s,"SELECT u.object,u.n,u.delta,u.pid,u.pids,o.kind,o.info "
        "FROM undo u JOIN objects o ON o.id=u.object WHERE u.owner=?1 "
        "AND (?2=0 OR u.space=?2) ORDER BY u.space,u.object,u.n",&q);
    if (!r) {
        sqlite3_bind_int64(q,1,owner); sqlite3_bind_int64(q,2,space); int step;
        while ((step=sqlite3_step(q))==SQLITE_ROW) {
            int object=sqlite3_column_int(q,0), number=sqlite3_column_int(q,1), val;
            struct semid64_ds st;
            if (sqlite3_column_int(q,5)!=MD_IPC_SEM || sqlite3_column_bytes(q,6)!=sizeof(st)) {
                r=-EIO; break;
            }
            memcpy(&st,sqlite3_column_blob(q,6),sizeof(st));
            r=value(s,object,number,&val,NULL,NULL);
            if (r) break;
            int64_t next=(int64_t)val+sqlite3_column_int64(q,2);
            if (!r) r=set(s,object,number,next<0 ? 0 : next>SEMVMX ? SEMVMX : next,sqlite3_column_int(q,3),
                sqlite3_column_blob(q,4),sqlite3_column_bytes(q,4));
            if (!r) { st.sem_otime=time(NULL); r=md_ipc_save(s,object,&st,sizeof(st)); }
            if (r) break;
        }
        if (!r && step!=SQLITE_DONE) r=-EIO;
    }
    sqlite3_finalize(q); q=NULL;
    if (!r) r=md_ipc_query(s,"DELETE FROM undo WHERE owner=?1 AND (?2=0 OR space=?2)",&q);
    if (!r) { sqlite3_bind_int64(q,1,owner); sqlite3_bind_int64(q,2,space); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q); return r;
}
static long get(struct md_ipc *s, struct md_ipc_pending *p) {
    int key=p->call.args[0], count=p->call.args[1], flags=p->call.args[2], object=0;
    if (count<0 || count>SEMMSL) return -EINVAL;
    int r=md_ipc_get(s,MD_IPC_SEM,key,flags,&object);
    struct semid64_ds st={0};
    if (!r && object) {
        r=md_ipc_load(s,MD_IPC_SEM,object,&st,sizeof(st));
        if (!r && (unsigned)count>st.sem_nsems) r=-EINVAL;
        if (!r) r=md_ipc_perm_access(s->user_namespace,&p->identity,&st.sem_perm,((flags>>6)|(flags>>3)|flags)&7);
    } else if (!r) {
        if (!count) return -EINVAL;
        st.sem_perm=(struct ipc64_perm){.key=key,.uid=p->identity.uid.effective,.cuid=p->identity.uid.effective,
            .gid=p->identity.gid.effective,.cgid=p->identity.gid.effective,.mode=flags&0777};
        st.sem_nsems=count; st.sem_ctime=time(NULL);
        r=md_ipc_create(s,MD_IPC_SEM,key,&st,sizeof(st),&object);
        sqlite3_stmt *q=NULL;
        if (!r) r=md_ipc_query(s,"INSERT INTO sems(object,n,value,pid) VALUES(?1,?2,0,0)",&q);
        for (int i=0; !r && i<count; i++) {
            sqlite3_bind_int(q,1,object); sqlite3_bind_int(q,2,i); r=md_ipc_sql(sqlite3_step(q)); sqlite3_reset(q);
        }
        sqlite3_finalize(q);
    }
    return r ? r : object;
}
static long operate(struct md_ipc *s, struct md_ipc_pending *p, struct semid64_ds *st) {
    const struct sembuf *ops=p->input;
    int object=p->call.args[0]; size_t count=p->size/sizeof(*ops);
    unsigned mode=0;
    for (size_t i=0;i<count;i++) {
        if (ops[i].sem_num>=st->sem_nsems) return -EFBIG;
        mode|=ops[i].sem_op ? 2 : 4;
    }
    int r=md_ipc_perm_access(s->user_namespace,&p->identity,&st->sem_perm,mode);
    for (size_t i=0; !r && i<count; i++) {
        int val;
        r=value(s,object,ops[i].sem_num,&val,NULL,NULL); if (r) break;
        if ((!ops[i].sem_op && val) || (ops[i].sem_op<0 && val < -ops[i].sem_op)) {
            if (ops[i].sem_flg&IPC_NOWAIT) return -EAGAIN;
            p->number=ops[i].sem_num; p->zero=!ops[i].sem_op;
            return MD_IPC_WAIT;
        }
        if (val+ops[i].sem_op>SEMVMX) return -ERANGE;
        if (ops[i].sem_flg&SEM_UNDO) r=undo(s,p,ops[i].sem_num,-ops[i].sem_op);
        if (!r) r=set_process(s,object,ops[i].sem_num,val+ops[i].sem_op,p->pid,p->process);
    }
    if (!r) { st->sem_otime=time(NULL); r=md_ipc_save(s,object,st,sizeof(*st)); }
    return r;
}
long md_ipc_sem(struct md_ipc *s, struct md_ipc_pending *p) {
    if (p->call.nr==SYS_semget) return get(s,p);
    const unsigned long *a=p->call.args;
    int object=a[0], number=a[1]; unsigned command=(unsigned)a[2]&~IPC_64;
    struct semid64_ds st;
    int r=md_ipc_load(s,MD_IPC_SEM,object,&st,sizeof(st));
    if (r) return r==-EINVAL && p->blocked ? -EIDRM : r;
    if (p->call.nr==SYS_semop || p->call.nr==SYS_semtimedop) return operate(s,p,&st);
    if (command==IPC_RMID || command==IPC_SET) {
        r=md_ipc_perm_owner(s->user_namespace,&p->identity,&st.sem_perm);
        if (r) return r;
        if (command==IPC_RMID) return md_ipc_remove(s,object);
        struct semid64_ds from;
        r=md_ipc_memory(p,a[3],&from,sizeof(from),0);
        if (!r) r=md_ipc_perm_set(&p->identity,&st.sem_perm,from.sem_perm.uid,from.sem_perm.gid,from.sem_perm.mode);
        if (!r) {
            st.sem_ctime=time(NULL);
            r=md_ipc_save(s,object,&st,sizeof(st));
        }
        return r;
    }
    r=md_ipc_perm_access(s->user_namespace,&p->identity,&st.sem_perm,command==SETVAL || command==SETALL ? 2 : 4);
    if (r) return r;
    if (command==IPC_STAT) {
        md_ipc_perm_visible(&p->identity,&st.sem_perm);
        return md_ipc_memory(p,a[3],&st,sizeof(st),1);
    }
    if (command==GETALL || command==SETALL) {
        size_t bytes=st.sem_nsems*sizeof(unsigned short);
        unsigned short *array=malloc(bytes); if (!array) return -ENOMEM;
        if (command==SETALL) r=md_ipc_memory(p,a[3],array,bytes,0);
        if (command==SETALL) for (unsigned i=0; !r && i<st.sem_nsems; i++) if (array[i]>SEMVMX) r=-ERANGE;
        for (unsigned i=0; !r && i<st.sem_nsems; i++) {
            int val;
            if (command==GETALL) { r=value(s,object,i,&val,NULL,NULL); if (!r) array[i]=val; }
            else r=set_process(s,object,i,array[i],p->pid,p->process);
        }
        if (!r && command==GETALL) r=md_ipc_memory(p,a[3],array,bytes,1);
        if (!r && command==SETALL) {
            r=clear_undo(s,object,-1); st.sem_ctime=time(NULL);
            if (!r) r=md_ipc_save(s,object,&st,sizeof(st));
        }
        free(array); return r;
    }
    if (number<0 || (unsigned)number>=st.sem_nsems) return -EINVAL;
    int val=0; pid_t pid=0;
    if (command==GETVAL || command==GETPID) {
        r=value(s,object,number,&val,command==GETPID ? &pid : NULL,p->process);
        return r ? r : command==GETVAL ? val : pid;
    }
    if (command==SETVAL) {
        val=(int)a[3]; if (val<0 || val>SEMVMX) return -ERANGE;
        r=set_process(s,object,number,val,p->pid,p->process);
        if (!r) r=clear_undo(s,object,number);
        st.sem_ctime=time(NULL); if (!r) r=md_ipc_save(s,object,&st,sizeof(st)); return r;
    }
    if (command==GETNCNT || command==GETZCNT) {
        sqlite3_stmt *q=NULL;
        r=md_ipc_query(s,"SELECT count(*) FROM waiters WHERE object=?1 AND n=?2 AND zero=?3",&q);
        if (!r) {
            sqlite3_bind_int(q,1,object); sqlite3_bind_int(q,2,number); sqlite3_bind_int(q,3,command==GETZCNT);
            if (sqlite3_step(q)==SQLITE_ROW) val=sqlite3_column_int(q,0); else r=-EIO;
        }
        sqlite3_finalize(q); return r ? r : val;
    }
    return -ENOTSUP;
}
