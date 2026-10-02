#define _GNU_SOURCE
#include "sysv_ipc_internal.h"
#include "interception.h"
#include <errno.h>
#include <limits.h>
#include <string.h>
#include <sys/syscall.h>

static long get(struct md_ipc *s, struct md_ipc_pending *p) {
    int key=p->call.args[0], flags=p->call.args[1], object=0;
    int r=md_ipc_get(s,MD_IPC_MSG,key,flags,&object);
    struct msqid64_ds st={0};
    if (!r && object) {
        r=md_ipc_load(s,MD_IPC_MSG,object,&st,sizeof(st));
        if (!r) r=md_ipc_permission(&p->identity,&st.msg_perm,((flags>>6)|(flags>>3)|flags)&7);
    } else if (!r) {
        st.msg_perm=(struct ipc64_perm){.key=key,.uid=p->identity.uid.effective,.cuid=p->identity.uid.effective,
            .gid=p->identity.gid.effective,.cgid=p->identity.gid.effective,.mode=flags&0777};
        st.msg_qbytes=MD_MSGMNB; st.msg_ctime=time(NULL);
        r=md_ipc_create(s,MD_IPC_MSG,key,&st,sizeof(st),&object);
    }
    return r ? r : object;
}
static long send(struct md_ipc *s, struct md_ipc_pending *p, struct msqid64_ds *st) {
    int r=md_ipc_permission(&p->identity,&st->msg_perm,2);
    if (r) return r;
    if (p->size>st->msg_qbytes || st->msg_cbytes>st->msg_qbytes-p->size || st->msg_qnum>=st->msg_qbytes)
        return p->call.args[3]&IPC_NOWAIT ? -EAGAIN : MD_IPC_WAIT;
    long type; memcpy(&type,p->input,sizeof(type));
    sqlite3_stmt *q=NULL;
    r=md_ipc_query(s,"INSERT INTO messages(object,type,data) VALUES(?1,?2,?3)",&q);
    if (!r) {
        sqlite3_bind_int(q,1,p->call.args[0]); sqlite3_bind_int64(q,2,type);
        sqlite3_bind_blob(q,3,(char *)p->input+sizeof(type),p->size,SQLITE_STATIC);
        r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q);
    if (!r) {
        st->msg_qnum++; st->msg_cbytes+=p->size; st->msg_stime=time(NULL); st->msg_lspid=p->pid;
        r=md_ipc_save(s,p->call.args[0],st,sizeof(*st));
    }
    return r;
}
static long receive(struct md_ipc *s, struct md_ipc_pending *p, struct msqid64_ds *st) {
    const unsigned long *a=p->call.args;
    int r=md_ipc_permission(&p->identity,&st->msg_perm,4);
    if (r) return r;
    if (a[4]&MSG_COPY) return -ENOTSUP;
    long type=(long)a[3];
    const char *query=type==0 ? "SELECT seq,type,data FROM messages WHERE object=?1 ORDER BY seq LIMIT 1"
        : type<0 ? "SELECT seq,type,data FROM messages WHERE object=?1 AND type<=?2 ORDER BY type,seq LIMIT 1"
        : a[4]&MSG_EXCEPT ? "SELECT seq,type,data FROM messages WHERE object=?1 AND type!=?2 ORDER BY seq LIMIT 1"
        : "SELECT seq,type,data FROM messages WHERE object=?1 AND type=?2 ORDER BY seq LIMIT 1";
    sqlite3_stmt *q=NULL;
    r=md_ipc_query(s,query,&q);
    int64_t sequence=0; size_t size=0, copied=0;
    if (!r) {
        sqlite3_bind_int(q,1,a[0]);
        if (type) sqlite3_bind_int64(q,2,type==LONG_MIN ? LONG_MAX : type<0 ? -type : type);
        int step=sqlite3_step(q);
        if (step==SQLITE_DONE) { sqlite3_finalize(q); return a[4]&IPC_NOWAIT ? -ENOMSG : MD_IPC_WAIT; }
        if (step!=SQLITE_ROW) r=-EIO;
        else {
            sequence=sqlite3_column_int64(q,0); type=sqlite3_column_int64(q,1);
            size=sqlite3_column_bytes(q,2); copied=size>a[2] ? a[2] : size;
            if (size>a[2] && !(a[4]&MSG_NOERROR)) r=-E2BIG;
            if (!r) r=md_ipc_memory(p,a[1],&type,sizeof(type),1);
            if (!r && a[1]>UINTPTR_MAX-sizeof(type)) r=-EFAULT;
            if (!r) r=md_ipc_memory(p,a[1]+sizeof(type),(void *)sqlite3_column_blob(q,2),copied,1);
        }
    }
    sqlite3_finalize(q); q=NULL;
    if (!r) r=md_ipc_query(s,"DELETE FROM messages WHERE seq=?1",&q);
    if (!r) { sqlite3_bind_int64(q,1,sequence); r=md_ipc_sql(sqlite3_step(q)); }
    sqlite3_finalize(q);
    if (!r) {
        st->msg_qnum--; st->msg_cbytes-=size; st->msg_rtime=time(NULL); st->msg_lrpid=p->pid;
        r=md_ipc_save(s,a[0],st,sizeof(*st));
    }
    return r ? r : (long)copied;
}
long md_ipc_msg(struct md_ipc *s, struct md_ipc_pending *p) {
    if (p->call.nr==SYS_msgget) return get(s,p);
    const unsigned long *a=p->call.args;
    struct msqid64_ds st;
    int r=md_ipc_load(s,MD_IPC_MSG,a[0],&st,sizeof(st));
    if (r) return r==-EINVAL && p->blocked ? -EIDRM : r;
    if (p->call.nr==SYS_msgsnd) return send(s,p,&st);
    if (p->call.nr==SYS_msgrcv) return receive(s,p,&st);
    unsigned command=(unsigned)a[1]&~IPC_64;
    if (command==IPC_STAT) {
        r=md_ipc_permission(&p->identity,&st.msg_perm,4);
        return r ? r : md_ipc_memory(p,a[2],&st,sizeof(st),1);
    }
    if (command!=IPC_SET && command!=IPC_RMID) return -ENOTSUP;
    r=md_ipc_owner(&p->identity,&st.msg_perm); if (r) return r;
    if (command==IPC_RMID) return md_ipc_remove(s,a[0]);
    struct msqid64_ds from;
    r=md_ipc_memory(p,a[2],&from,sizeof(from),0);
    if (!r && from.msg_qbytes>MD_MSGMNB && !md_identity_capable(&p->identity,CAP_SYS_RESOURCE)) r=-EPERM;
    if (!r && from.msg_qbytes>1024*1024) r=-ERANGE;
    if (!r) {
        st.msg_perm.uid=from.msg_perm.uid; st.msg_perm.gid=from.msg_perm.gid;
        st.msg_perm.mode=from.msg_perm.mode&0777; st.msg_qbytes=from.msg_qbytes; st.msg_ctime=time(NULL);
        r=md_ipc_save(s,a[0],&st,sizeof(st));
    }
    return r;
}
