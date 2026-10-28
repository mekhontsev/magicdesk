#define _GNU_SOURCE
#include "sysv_shm.h"
#include "ipc_database.h"
#include "ipc_permissions.h"
#include "ipc_pid.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <sqlite3.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

struct md_shm { struct md_ipc_database database; uint64_t namespace_id; atomic_uint references;
    struct md_user_namespace *user_namespace; };
struct md_shm_mapping {
    struct md_shm_mapping *next;
    struct md_shm_space *space;
    struct md_shm *authority;
    int segment;
    uintptr_t address;
    uintptr_t base;
    size_t size;
};
struct md_shm_space { unsigned references; int used; uint64_t id; struct md_shm_mapping *maps; };
static uint64_t spaces;
static int sql(int rc) { return md_ipc_database_sql(rc); }
static int exec_sql(struct md_shm *s, const char *text) { return md_ipc_database_exec(&s->database,text); }
static int query(struct md_shm *s, const char *text, sqlite3_stmt **q) {
    return md_ipc_database_query(&s->database,text,q);
}
static void body(char *out, size_t size, int id) { snprintf(out, size, "segment-%d", id); }
static int refs(struct md_shm *s, uint64_t owner, uint64_t space, int segment, int delta) {
    sqlite3_stmt *q = NULL;
    int r = query(s, "INSERT INTO refs(owner,space,segment,n) VALUES(?1,?2,?3,?4) "
        "ON CONFLICT(owner,space,segment) DO UPDATE SET n=n+excluded.n", &q);
    if (!r) {
        sqlite3_bind_int64(q,1,owner); sqlite3_bind_int64(q,2,space);
        sqlite3_bind_int(q,3,segment); sqlite3_bind_int(q,4,delta); r = sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); return r;
}
static int load(struct md_shm *s, int id, struct shmid64_ds *out) {
    sqlite3_stmt *q = NULL;
    int r = query(s, "SELECT info,(SELECT coalesce(sum(n),0) FROM refs WHERE segment=?1) FROM segments WHERE id=?1 AND namespace=?2", &q);
    if (!r) {
        sqlite3_bind_int(q,1,id); sqlite3_bind_int64(q,2,s->namespace_id); int step = sqlite3_step(q);
        if (step == SQLITE_DONE) r = -EINVAL;
        else if (step != SQLITE_ROW || sqlite3_column_bytes(q,0) != sizeof(*out)) r = -EIO;
        else { memcpy(out,sqlite3_column_blob(q,0),sizeof(*out)); out->shm_nattch = sqlite3_column_int64(q,1); }
    }
    sqlite3_finalize(q); return r;
}
static int load_live(struct md_shm *s,int id,struct shmid64_ds *out) {
    int error=load(s,id,out);
    /* Removal after a dead external lease is already effective, even if the
     * next maintenance transaction has not collected its backing file yet. */
    return !error && (out->shm_perm.mode&01000) && !out->shm_nattch ? -EINVAL : error;
}
static int save(struct md_shm *s,int id,const struct shmid64_ds *info,int update_pid,const struct md_pid_identity *process) {
    sqlite3_stmt *q = NULL;
    int r = query(s,update_pid ? "UPDATE segments SET info=?2,key=?3,removed=?4,last_pids=?5 WHERE id=?1"
        : "UPDATE segments SET info=?2,key=?3,removed=?4 WHERE id=?1",&q);
    if (!r) {
        sqlite3_bind_int(q,1,id); sqlite3_bind_blob(q,2,info,sizeof(*info),SQLITE_STATIC);
        sqlite3_bind_int(q,3,info->shm_perm.key); sqlite3_bind_int(q,4,!!(info->shm_perm.mode & 01000));
        if (update_pid) r=md_ipc_pid_bind(q,5,process);
        if (!r) r = sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); return r;
}
static int visible_processes(struct md_shm *s,int segment,const struct md_pid_identity *observer,struct shmid64_ds *st) {
    sqlite3_stmt *q=NULL;
    int r=query(s,"SELECT creator_pids,last_pids FROM segments WHERE id=?1",&q);
    if (!r) {
        sqlite3_bind_int(q,1,segment);
        if (sqlite3_step(q)!=SQLITE_ROW) r=-EIO;
        else {
            int creator=md_ipc_pid_visible(q,0,observer,st->shm_cpid);
            int last=md_ipc_pid_visible(q,1,observer,st->shm_lpid);
            if (creator<0 || last<0) r=creator<0 ? creator : last;
            else { st->shm_cpid=creator; st->shm_lpid=last; }
        }
    }
    sqlite3_finalize(q); return r;
}
static int collect(struct md_shm *s) {
    return exec_sql(s,"UPDATE segments SET removed=1,key=0 WHERE namespace!=0 AND namespace NOT IN (SELECT namespace FROM owners);"
        "DELETE FROM refs WHERE n=0;"
        "INSERT OR IGNORE INTO garbage SELECT id FROM segments WHERE removed=1 "
        "AND NOT EXISTS(SELECT 1 FROM refs WHERE segment=id AND n>0);"
        "DELETE FROM segments WHERE id IN (SELECT id FROM garbage)");
}
/* Delete data only after its logical removal commits. Interrupted cleanup is
 * replayable; a failed transaction cannot resurrect a segment without its bytes. */
static int collect_bodies(struct md_shm *s) {
    sqlite3_stmt *q = NULL;
    int r = query(s,"SELECT id FROM garbage",&q);
    if (!r) {
        int step;
        while ((step = sqlite3_step(q)) == SQLITE_ROW) {
            char name[64]; body(name,sizeof(name),sqlite3_column_int(q,0));
            if (unlinkat(s->database.directory,name,0) && errno != ENOENT) { r = -errno; break; }
        }
        if (!r && step != SQLITE_DONE) r = -EIO;
    }
    sqlite3_finalize(q);
    if (!r) r = exec_sql(s,"DELETE FROM garbage");
    return r;
}
static int remove_owner(void *context,int64_t id) {
    struct md_shm *s=context;
    const char *statements[]={"DELETE FROM refs WHERE owner=?1","DELETE FROM owners WHERE owner=?1"};
    int r=0;
    for (unsigned i=0;i<2 && !r;++i) {
        sqlite3_stmt *q=NULL;
        r=query(s,statements[i],&q);
        if (!r) { sqlite3_bind_int64(q,1,id); r=sql(sqlite3_step(q)); }
        sqlite3_finalize(q);
    }
    return r;
}
static int dead_owners(struct md_shm *s) {
    return md_ipc_database_reap(&s->database,"SELECT owner FROM owners",remove_owner,s);
}
static int begin(struct md_shm *s) {
    int r=md_ipc_database_begin(&s->database);
    if (r) return r;
    r=dead_owners(s);
    if (r) { md_ipc_database_commit(&s->database,r); md_ipc_database_unlock(&s->database); }
    return r;
}
static int finish(struct md_shm *s, int error) {
    if (!error) error = collect(s);
    error=md_ipc_database_commit(&s->database,error);
    if (!error) error=collect_bodies(s);
    md_ipc_database_unlock(&s->database); return error;
}
static int collect_orphans(struct md_shm *s) {
    int fd=openat(s->database.directory,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (fd<0) return -errno;
    DIR *dir=fdopendir(fd);
    if (!dir) { int r=-errno; close(fd); return r; }
    sqlite3_stmt *q=NULL;
    int r=query(s,"SELECT 1 FROM segments WHERE id=?1",&q);
    while (!r) {
        errno=0; struct dirent *entry=readdir(dir);
        if (!entry) { if (errno) r=-errno; break; }
        int id=0; char end=0;
        if (sscanf(entry->d_name,"segment-%d%c",&id,&end)!=1 || id<=0) continue;
        sqlite3_bind_int(q,1,id); int rc=sqlite3_step(q);
        if (rc==SQLITE_DONE) { if (unlinkat(s->database.directory,entry->d_name,0)) r=-errno; }
        else if (rc!=SQLITE_ROW) r=-EIO;
        sqlite3_reset(q);
    }
    sqlite3_finalize(q); closedir(dir); return r;
}
int md_shm_open(const char *store, uint64_t namespace_id, struct md_user_namespace *owner, struct md_shm **out) {
    *out = NULL;
    struct md_shm *s = calloc(1,sizeof(*s));
    if (!s) return -ENOMEM;
    atomic_init(&s->references,1); s->namespace_id=namespace_id;
    s->user_namespace=md_user_namespace_retain(owner);
    int r=md_ipc_database_open(&s->database,store,"shm","segments.db");
    if (!r) r = exec_sql(s,
        "CREATE TABLE IF NOT EXISTS segments(id INTEGER PRIMARY KEY AUTOINCREMENT,key INTEGER,removed INTEGER,info BLOB,namespace INTEGER NOT NULL);"
        "CREATE UNIQUE INDEX IF NOT EXISTS segment_key ON segments(namespace,key) WHERE key!=0 AND removed=0;"
        "CREATE TABLE IF NOT EXISTS owners(owner INTEGER PRIMARY KEY,namespace INTEGER NOT NULL);"
        "CREATE TABLE IF NOT EXISTS garbage(id INTEGER PRIMARY KEY);"
        "CREATE TABLE IF NOT EXISTS refs(owner INTEGER,space INTEGER,segment INTEGER,n INTEGER,PRIMARY KEY(owner,space,segment));"
        "CREATE INDEX IF NOT EXISTS refs_segment ON refs(segment);");
    if (!r) r = exec_sql(s,"BEGIN IMMEDIATE");
    if (!r) r=md_ipc_database_blob_column(&s->database,"segments","creator_pids");
    if (!r) r=md_ipc_database_blob_column(&s->database,"segments","last_pids");
    if (!r) r=md_ipc_database_boot(&s->database,
        "DELETE FROM refs; DELETE FROM owners; UPDATE segments SET removed=1;");
    if (!r) r = dead_owners(s);
    if (!r) r = collect(s);
    if (!r) {
        sqlite3_stmt *q=NULL;
        r=query(s,"INSERT INTO owners VALUES(?1,?2)",&q);
        if (!r) {
            sqlite3_bind_int64(q,1,s->database.owner); sqlite3_bind_int64(q,2,s->namespace_id);
            r=sql(sqlite3_step(q));
        }
        sqlite3_finalize(q);
    }
    if (s->database.handle) r=md_ipc_database_commit(&s->database,r);
    if (!r) s->database.admitted=1;
    if (!r) r = collect_bodies(s);
    if (!r) r = collect_orphans(s);
    if (!r) r = md_ipc_database_sweep_leases(&s->database,"SELECT 1 FROM owners WHERE owner=?1");
    md_ipc_database_unlock(&s->database);
    if (r) md_shm_close(s); else *out = s;
    return r;
}
struct md_shm *md_shm_retain(struct md_shm *s) {
    if (s) atomic_fetch_add_explicit(&s->references,1,memory_order_relaxed);
    return s;
}
void md_shm_close(struct md_shm *s) {
    if (!s || atomic_fetch_sub_explicit(&s->references,1,memory_order_acq_rel)!=1) return;
    if (s->database.admitted && !begin(s)) finish(s,remove_owner(s,s->database.owner));
    md_ipc_database_close(&s->database);
    md_user_namespace_release(s->user_namespace);
    free(s);
}
struct md_shm_space *md_shm_space_new(void) {
    struct md_shm_space *m = calloc(1,sizeof(*m));
    if (m) { m->references = 1; m->id = ++spaces; }
    return m;
}
struct md_shm_space *md_shm_space_fork(struct md_shm_space *old, int shared) {
    if (shared) { ++old->references; return old; }
    struct md_shm_space *m = md_shm_space_new();
    if (m) m->used=old->used;
    if (!m || !old->maps) return m;
    int r = 0;
    struct md_shm_mapping *pending=NULL;
    for (struct md_shm_mapping *a = old->maps; !r && a; a = a->next) {
        struct md_shm_mapping *b = malloc(sizeof(*b));
        if (!b) { r = -ENOMEM; break; }
        *b=*a; b->space=m; b->next=pending; pending=b;
    }
    /* Publish a namespace's mappings only after its complete reference batch
     * commits. Failure leaves the remaining candidates unowned. */
    while (!r && pending) {
        struct md_shm *s=pending->authority;
        r=begin(s);
        if (r) break;
        for (struct md_shm_mapping *p=pending; !r && p; p=p->next)
            if (p->authority==s) r=refs(s,s->database.owner,m->id,p->segment,1);
        r=finish(s,r);
        if (r) break;
        struct md_shm_mapping **link=&pending;
        while (*link) {
            struct md_shm_mapping *p=*link;
            if (p->authority!=s) { link=&p->next; continue; }
            *link=p->next; p->next=m->maps; m->maps=p; md_shm_retain(s);
        }
    }
    while (pending) { struct md_shm_mapping *next=pending->next; free(pending); pending=next; }
    if (r) {
        md_shm_space_release(m); m = NULL;
    }
    return m;
}
int md_shm_space_used(const struct md_shm_space *m) { return m && m->used; }
int md_shm_space_release(struct md_shm_space *m) {
    if (!m || --m->references) return 0;
    int r = 0;
    while (m->maps) {
        struct md_shm *s=m->maps->authority;
        md_shm_retain(s);
        int error=begin(s);
        if (!error) {
            sqlite3_stmt *q=NULL;
            error=query(s,"DELETE FROM refs WHERE owner=?1 AND space=?2",&q);
            if (!error) {
                sqlite3_bind_int64(q,1,s->database.owner); sqlite3_bind_int64(q,2,m->id);
                error=sql(sqlite3_step(q));
            }
            sqlite3_finalize(q); error=finish(s,error);
        }
        if (!r) r=error;
        struct md_shm_mapping **link=&m->maps;
        while (*link) {
            struct md_shm_mapping *p=*link;
            if (p->authority!=s) { link=&p->next; continue; }
            *link=p->next; free(p); md_shm_close(s);
        }
        md_shm_close(s);
    }
    free(m); return r;
}
int md_shm_abort(struct md_shm_mapping **pending) {
    struct md_shm_mapping *p = *pending;
    if (!p) return 0;
    struct md_shm *s=p->authority;
    int r = begin(s);
    if (!r) r = finish(s,refs(s,s->database.owner,p->space->id,p->segment,-1));
    free(p); *pending = NULL; md_shm_close(s); return r;
}
size_t md_shm_pending_size(const struct md_shm_mapping *pending) {
    return pending ? pending->size : 0;
}
int md_shm_intersects(const struct md_shm_space *m, uintptr_t address, size_t size) {
    uintptr_t end = size > UINTPTR_MAX-address ? UINTPTR_MAX : address+size;
    for (const struct md_shm_mapping *p = m->maps; p; p = p->next)
        if (p->address < end && p->address+p->size > address) return 1;
    return 0;
}
int md_shm_unmapped(struct md_shm_space *m,uintptr_t address,size_t size,pid_t pid,const struct md_pid_identity *process) {
    if (!md_shm_intersects(m,address,size)) return 0;
    uintptr_t end = size > UINTPTR_MAX-address ? UINTPTR_MAX : address+size;
    int r = 0;
    struct md_shm_mapping **link = &m->maps;
    while (!r && *link) {
        struct md_shm_mapping *p = *link;
        uintptr_t stop = p->address+p->size;
        if (p->address >= end || stop <= address) { link = &p->next; continue; }
        struct md_shm *s=p->authority;
        r=begin(s); if (r) break;
        int released=0;
        struct md_shm_mapping *right=NULL;
        if (p->address < address && stop > end) {
            right = malloc(sizeof(*right));
            if (!right) { r = finish(s,-ENOMEM); break; }
            *right = *p; right->address = end; right->size = stop-end;
            r = refs(s,s->database.owner,m->id,p->segment,1);
        } else if (p->address>=address && stop<=end) {
            struct shmid64_ds st;
            r = refs(s,s->database.owner,m->id,p->segment,-1);
            if (!r) r = load(s,p->segment,&st);
            if (!r) { st.shm_dtime = time(NULL); st.shm_lpid = pid; r = save(s,p->segment,&st,1,process); }
            released=1;
        }
        r=finish(s,r);
        if (r) { free(right); break; }
        if (right) { p->size=address-p->address; p->next=right; md_shm_retain(s); link=&right->next; }
        else if (released) { *link=p->next; free(p); md_shm_close(s); }
        else if (p->address<address) { p->size=address-p->address; link=&p->next; }
        else { p->address=end; p->size=stop-end; link=&p->next; }
    }
    return r;
}
static int permission(const struct md_shm *s, const struct md_identity *id, const struct shmid64_ds *st, unsigned mode) {
    return md_ipc_perm_access(s->user_namespace,id,&st->shm_perm,mode);
}
int md_shm_export(struct md_shm *s,const struct md_identity *id,pid_t pid,const struct md_pid_identity *process,int segment,
        int readonly,int descriptors[2],uint64_t *size) {
    descriptors[0]=descriptors[1]=-1; *size=0;
    if (readonly!=0 && readonly!=1) return -EINVAL;
    int error=begin(s),data=-1,lease=-1;
    int64_t owner=0;
    if (error) return error;
    struct shmid64_ds st={0};
    error=load_live(s,segment,&st);
    if (!error) error=permission(s,id,&st,readonly ? 4 : 6);
    if (!error) {
        char name[64]; body(name,sizeof(name),segment);
        data=openat(s->database.directory,name,(readonly ? O_RDONLY : O_RDWR)|O_CLOEXEC|O_NOFOLLOW);
        if (data<0) error=-errno;
    }
    if (!error) {
        lease=md_ipc_database_lease(&s->database,&owner);
        if (lease<0) error=lease;
    }
    sqlite3_stmt *q=NULL;
    if (!error) error=query(s,"INSERT INTO owners VALUES(?1,?2)",&q);
    if (!error) {
        sqlite3_bind_int64(q,1,owner); sqlite3_bind_int64(q,2,s->namespace_id);
        error=sql(sqlite3_step(q));
    }
    sqlite3_finalize(q);
    if (!error) error=refs(s,owner,0,segment,1);
    if (!error) { st.shm_atime=time(NULL); st.shm_lpid=pid; error=save(s,segment,&st,1,process); }
    error=finish(s,error);
    if (error) {
        if (data>=0) close(data);
        md_ipc_database_discard_lease(&s->database,owner,lease);
    } else {
        descriptors[0]=data; descriptors[1]=lease; *size=st.shm_segsz;
    }
    return error;
}
long md_shm_call(struct md_shm *s, struct md_shm_space *m, struct md_shm_mapping **pending,
        const struct md_identity *id,pid_t pid,const struct md_pid_identity *process,unsigned op,const unsigned long a[4],struct shmid64_ds *info) {
    if (op == MD_SHM_ABORT) return md_shm_abort(pending);
    if (op == MD_SHM_ATTACHED) {
        struct md_shm_mapping *p = *pending;
        if (!p || p->space != m || !a[0]) return -EINVAL;
        p->address = p->base = a[0]; p->next = m->maps; m->maps = p; *pending = NULL; return 0;
    }
    if (op == MD_SHM_DETACH_ADDRESS) {
        for (struct md_shm_mapping *p = m->maps; p; p = p->next) if (p->base == a[0]) return p->address;
        return -EINVAL;
    }
    if (op == MD_SHM_DETACH_SIZE || op == MD_SHM_DETACHED) {
        for (struct md_shm_mapping *p = m->maps; p; p = p->next) if (p->address == a[0])
            return op == MD_SHM_DETACH_SIZE ? (long)p->size : md_shm_unmapped(m,p->address,p->size,pid,process);
        return -EINVAL;
    }
    int r = begin(s), fd = -1;
    long result = 0;
    if (r) return r;
    int segment = (int)a[0];
    struct shmid64_ds st = {0};
    if (op == MD_SHM_GET) {
        int key = (int)a[0], flags = (int)a[2];
        uint64_t size = a[1];
        if (flags & ~(0777|IPC_CREAT|IPC_EXCL|SHM_NORESERVE)) r = -ENOTSUP;
        sqlite3_stmt *q = NULL;
        if (!r) r = query(s,"SELECT id FROM segments WHERE key=?1 AND key!=0 AND removed=0 AND namespace=?2",&q);
        segment = 0;
        if (!r) { sqlite3_bind_int(q,1,key); sqlite3_bind_int64(q,2,s->namespace_id); int rc = sqlite3_step(q);
            if (rc == SQLITE_ROW) segment = sqlite3_column_int(q,0); else if (rc != SQLITE_DONE) r = -EIO; }
        sqlite3_finalize(q); q = NULL;
        if (!r && segment) {
            r = load(s,segment,&st);
            if (!r && (flags & (IPC_CREAT|IPC_EXCL)) == (IPC_CREAT|IPC_EXCL)) r = -EEXIST;
            if (!r && size > st.shm_segsz) r = -EINVAL;
            if (!r) r = permission(s,id,&st,((flags>>6)|(flags>>3)|flags)&7);
        } else if (!r) {
            if (key && !(flags & IPC_CREAT)) r = -ENOENT;
            else if (!size || size > LONG_MAX - (unsigned long)getpagesize()) r = -EINVAL;
            if (!r) r = query(s,"INSERT INTO segments(key,removed,info,namespace,creator_pids) VALUES(?1,0,?2,?3,?4)",&q);
            st.shm_perm.key = key; st.shm_perm.uid = st.shm_perm.cuid = id->uid.effective;
            st.shm_perm.gid = st.shm_perm.cgid = id->gid.effective; st.shm_perm.mode = flags&0777;
            st.shm_segsz = size; st.shm_ctime = time(NULL); st.shm_cpid = pid;
            if (!r) { sqlite3_bind_int(q,1,key); sqlite3_bind_blob(q,2,&st,sizeof(st),SQLITE_STATIC);
                sqlite3_bind_int64(q,3,s->namespace_id); r=md_ipc_pid_bind(q,4,process);
                if (!r) r = sql(sqlite3_step(q)); }
            sqlite3_finalize(q);
            if (!r) {
                sqlite3_int64 next = sqlite3_last_insert_rowid(s->database.handle);
                if (next <= 0 || next > INT_MAX) r = -ENOSPC; else segment = (int)next;
            }
            if (!r) {
                char name[64]; body(name,sizeof(name),segment);
                fd = openat(s->database.directory,name,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
                if (fd < 0) r = -errno;
                else if (ftruncate(fd,(off_t)((size+getpagesize()-1)&~(uint64_t)(getpagesize()-1)))) r = -errno;
                if (fd >= 0) close(fd);
                fd = -1;
                if (r) unlinkat(s->database.directory,name,0);
            }
        }
        result = segment;
    } else {
        r = load_live(s,segment,&st);
        if (!r && op == MD_SHM_OPEN) {
            m->used=1;
            if (*pending) r = -EBUSY;
            if (!r) r = permission(s,id,&st,(a[1]&SHM_RDONLY)?4:6);
            if (!r && (a[1]&SHM_EXEC)) r = permission(s,id,&st,1);
            if (!r) {
                char name[64]; body(name,sizeof(name),segment);
                fd = openat(s->database.directory,name,((a[1]&SHM_RDONLY)?O_RDONLY:O_RDWR)|O_CLOEXEC|O_NOFOLLOW);
                if (fd < 0) r = -errno;
            }
            struct md_shm_mapping *p = !r ? calloc(1,sizeof(*p)) : NULL;
            if (!r && !p) r = -ENOMEM;
            if (!r) r = refs(s,s->database.owner,m->id,segment,1);
            if (!r) { st.shm_atime = time(NULL); st.shm_lpid = pid; r = save(s,segment,&st,1,process); }
            if (!r) { *p = (struct md_shm_mapping){.space=m,.segment=segment,.authority=s,
                .size=(st.shm_segsz+getpagesize()-1)&~(size_t)(getpagesize()-1)}; *pending = p; result = fd; }
            else free(p);
        } else if (!r && op == MD_SHM_STAT) {
            r = permission(s,id,&st,4);
            if (!r) r=visible_processes(s,segment,process,&st);
            if (!r) { *info=st; md_ipc_perm_visible(id,&info->shm_perm); }
        } else if (!r && (op == MD_SHM_SET || op == MD_SHM_REMOVE)) {
            r=md_ipc_perm_owner(s->user_namespace,id,&st.shm_perm);
            if (!r) {
                if (op == MD_SHM_SET) {
                    r=md_ipc_perm_set(id,&st.shm_perm,(uint32_t)a[1],a[1]>>32,a[2]);
                    st.shm_ctime = time(NULL);
                } else { st.shm_perm.mode |= 01000; st.shm_perm.key = IPC_PRIVATE; }
                if (!r) r = save(s,segment,&st,0,NULL);
            }
        } else if (!r) r = -ENOTSUP;
    }
    r = finish(s,r);
    if (op == MD_SHM_OPEN && *pending) {
        if (r) { free(*pending); *pending=NULL; }
        else md_shm_retain(s);
    }
    if (r && fd >= 0) close(fd);
    return r ? r : result;
}
