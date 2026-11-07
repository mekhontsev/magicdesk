#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_schema.h"
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <unistd.h>

/* Format 17 is the compatibility baseline, not a performance/cache revision. */
int md_inode_store_format(void) { return 17; }
#define PUBLICATION_SCHEMA \
    "CREATE TABLE native_publication(slot INTEGER PRIMARY KEY CHECK(slot=1)," \
    "object TEXT NOT NULL CHECK(length(object)=32),parent TEXT NOT NULL REFERENCES objects," \
    "name BLOB NOT NULL CHECK(length(name) BETWEEN 1 AND 255)," \
    "device INTEGER NOT NULL,inode INTEGER NOT NULL," \
    "uid INTEGER NOT NULL CHECK(uid>=0 AND uid<4294967295)," \
    "gid INTEGER NOT NULL CHECK(gid>=0 AND gid<4294967295)," \
    "mode INTEGER NOT NULL CHECK(mode>=0 AND mode<=4095)) STRICT;" \
    "CREATE TABLE native_cookies(cookie INTEGER PRIMARY KEY AUTOINCREMENT," \
    "parent TEXT NOT NULL REFERENCES objects ON DELETE CASCADE,position INTEGER NOT NULL," \
    "UNIQUE(parent,position)) STRICT;"
#define XATTR_SCHEMA \
    "CREATE TABLE inode_xattrs(object TEXT NOT NULL REFERENCES objects ON DELETE CASCADE," \
    "name TEXT NOT NULL CHECK(length(CAST(name AS BLOB)) BETWEEN 1 AND 255)," \
    "value BLOB CHECK(length(value)<=65536),PRIMARY KEY(object,name)) STRICT;"
#define XATTR_UPGRADE XATTR_SCHEMA \
    "INSERT INTO inode_xattrs SELECT object,CASE name " \
    "WHEN 1 THEN 'user.overlay.whiteout' WHEN 2 THEN 'user.overlay.opaque' " \
    "WHEN 3 THEN 'trusted.overlay.whiteout' WHEN 4 THEN 'trusted.overlay.opaque' END,value " \
    "FROM overlay_attributes; DROP TABLE overlay_attributes;"
#define DENTRY_SCHEMA \
    "CREATE TABLE dentry_paths(id INTEGER PRIMARY KEY,cookie INTEGER UNIQUE," \
    "object TEXT NOT NULL REFERENCES objects ON DELETE CASCADE," \
    "parent TEXT NOT NULL REFERENCES objects,name BLOB NOT NULL) STRICT;" \
    "CREATE INDEX dentry_paths_object ON dentry_paths(object,id);" \
    "CREATE INDEX dentry_paths_parent ON dentry_paths(parent);" \
    "CREATE TRIGGER dentry_added AFTER INSERT ON names " \
    "WHEN (SELECT kind FROM objects WHERE object=NEW.object)!=16384 BEGIN " \
    "INSERT INTO dentry_paths VALUES(NEW.cookie,NEW.cookie,NEW.object,NEW.parent,NEW.name); END;" \
    "CREATE TRIGGER dentry_removed AFTER DELETE ON names BEGIN " \
    "UPDATE dentry_paths SET cookie=NULL WHERE cookie=OLD.cookie; END;"
#define DENTRY_UPGRADE DENTRY_SCHEMA \
    "INSERT INTO dentry_paths SELECT n.cookie,n.cookie,n.object,n.parent,n.name " \
    "FROM names n JOIN objects o ON n.object=o.object WHERE o.kind!=16384;"
/* Revision 5 admits store-relative source paths; existing absolute paths remain
 * readable. The marker prevents older readers from misinterpreting new rows. */
static const char *const revisions[]={PUBLICATION_SCHEMA,XATTR_UPGRADE,DENTRY_UPGRADE,"SELECT 1"};
const struct mdi_schema_plan mdi_schema_current = {revisions,sizeof(revisions)/sizeof(*revisions)};
int md_inode_store_revision(void) { return 1 + (int)mdi_schema_current.count; }

int mdi_schema_indexes(struct md_inode_store *s) {
    /* Foreign-key checks run per removed parent. All child lookups must be
     * indexed; otherwise collecting a view repeatedly scans the whole store.
     * This is cold admission work, not part of retained worker reconnects. */
    int r=mdi_sql(s,"BEGIN IMMEDIATE;"
        "CREATE INDEX IF NOT EXISTS objects_parent ON objects(parent);"
        "CREATE INDEX IF NOT EXISTS objects_backing ON objects(backing);"
        "CREATE INDEX IF NOT EXISTS backings_object ON backings(object);"
        "CREATE INDEX IF NOT EXISTS inode_views_upper ON inode_views(upper);"
        "CREATE INDEX IF NOT EXISTS inode_views_work ON inode_views(work);"
        "CREATE INDEX IF NOT EXISTS overlay_origins_object ON overlay_origins(object);"
        "CREATE INDEX IF NOT EXISTS overlay_links_upper ON overlay_links(upper);"
        "CREATE INDEX IF NOT EXISTS file_paths_parent ON file_paths(parent);"
        "CREATE INDEX IF NOT EXISTS native_publication_parent ON native_publication(parent);"
        "CREATE INDEX IF NOT EXISTS inode_channels_channel ON inode_channels(channel);"
        "CREATE INDEX IF NOT EXISTS events_object ON events(object,sequence);"
        "COMMIT;");
    if (r) mdi_sql(s,"ROLLBACK");
    return r;
}
static int journal_mode(struct md_inode_store *s, const char *sql, const char *expected) {
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,sql,&q);
    if (!r) {
        int rc=mdi_step(s,q);
        if (rc!=SQLITE_ROW) r=mdi_sql_failure(rc);
        else {
            const char *mode=(const char *)sqlite3_column_text(q,0);
            if (!mode || strcmp(mode,expected)) r=-ENOTSUP;
        }
    }
    sqlite3_finalize(q); return r;
}

static int scalar(struct md_inode_store *s, const char *sql, int *value) {
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,sql,&q);
    if (!r) {
        int rc=mdi_step(s,q);
        if (rc!=SQLITE_ROW) r=mdi_sql_failure(rc);
        else if (sqlite3_column_type(q,0)!=SQLITE_INTEGER
                || sqlite3_column_int64(q,0)<0 || sqlite3_column_int64(q,0)>INT_MAX) r=-EIO;
        else *value=sqlite3_column_int(q,0);
    }
    sqlite3_finalize(q); return r;
}

static int metadata_only(void *context,int action,const char *a,const char *b,const char *db,const char *trigger) {
    (void)context; (void)b; (void)db; (void)trigger;
    /* ALTER TABLE invokes this read-only integrity check internally. */
    if (action==SQLITE_PRAGMA && a && !sqlite3_stricmp(a,"quick_check")) return SQLITE_OK;
    return action==SQLITE_TRANSACTION || action==SQLITE_SAVEPOINT || action==SQLITE_PRAGMA
        || action==SQLITE_ATTACH || action==SQLITE_DETACH ? SQLITE_DENY : SQLITE_OK;
}

static int set_revision(struct md_inode_store *s,int revision) {
    char sql[128];
    snprintf(sql,sizeof(sql),"INSERT INTO properties VALUES('schema-revision',%d) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",revision);
    return mdi_sql(s,sql);
}

static int advance(struct md_inode_store *s, const char *step, int next) {
    int r=mdi_sql_error(sqlite3_set_authorizer(s->db,metadata_only,NULL));
    if (!r) r=mdi_sql(s,step);
    int reset=mdi_sql_error(sqlite3_set_authorizer(s->db,NULL,NULL));
    if (!r) r=reset;
    if (!r) r=set_revision(s,next);
    return r;
}

int mdi_schema_admit(struct md_inode_store *s, int allow_update, int exclusive,
        const struct mdi_schema_plan *plan) {
    if (!s->locked || !plan || plan->count>=INT_MAX || (plan->count && !plan->steps)) return -EINVAL;
    int format=0, application=0, revision=0;
    int r=scalar(s,"PRAGMA user_version",&format);
    if (!r && format!=md_inode_store_format()) r=-EPROTONOSUPPORT;
    if (!r) r=scalar(s,"PRAGMA application_id",&application);
    if (!r && application!=0x53485254) r=-EPROTONOSUPPORT;
    if (!r) r=scalar(s,"SELECT value FROM properties WHERE key='schema-revision'",&revision);
    int current=1+(int)plan->count;
    if (!r && (revision<1 || revision>current)) r=-EPROTONOSUPPORT;
    if (r) return r;
    int mode=journal_mode(s,"PRAGMA journal_mode","wal");
    if (mode && mode!=-ENOTSUP) return mode;
    if (revision==current && !mode) return 0;
    if (!allow_update) return -EPROTONOSUPPORT;
    for (int next=revision; next<current; ++next)
        if (!plan->steps[next-1] || !*plan->steps[next-1]) return -EPROTONOSUPPORT;
    if (atomic_load_explicit(&s->lifetime->refs,memory_order_relaxed)!=1) return -EBUSY;
    /* Conversion may release the shared flock on failure. This is admission,
     * before any origins/clients escape; its caller must close on any error. */
    if (!exclusive && flock(s->lifetime->root,LOCK_EX|LOCK_NB))
        return errno==EWOULDBLOCK ? -EBUSY : -errno;
    r=mdi_sql(s,"PRAGMA synchronous=FULL");
    /* Journal conversion cannot be inside the DDL transaction. It changes no
     * logical data; retry accepts either journal mode with the old revision. */
    if (!r && mode) r=journal_mode(s,"PRAGMA journal_mode=WAL","wal");
    while (!r && revision<current) {
        r=mdi_sql(s,"BEGIN IMMEDIATE");
        if (!r) r=advance(s,plan->steps[revision-1],revision+1);
        if (!r) r=mdi_sql(s,"COMMIT");
        if (r) mdi_sql(s,"ROLLBACK");
        else ++revision;
    }
    if (!r) r=mdi_checkpoint(s);
    if (!r) r=mdi_sync(s,s->lifetime->root);
    if (!r && !exclusive && flock(s->lifetime->root,LOCK_SH|LOCK_NB)) r=-errno;
    return r;
}

int mdi_schema_configure(struct md_inode_store *s, int create) {
    int r=create ? journal_mode(s,"PRAGMA journal_mode=WAL","wal") : 0;
    /* Connection policy is independent of persistent format and revision. */
    if (!r) r=mdi_sql(s,s->lifetime->temporary ? "PRAGMA synchronous=OFF" : "PRAGMA synchronous=NORMAL");
    /* The default 2 MiB repeatedly evicts directory/index pages in broad path
     * walks. This is a demand-grown page-cache target, not an allocation. */
    if (!r) r=mdi_sql(s,"PRAGMA cache_size=-8192; PRAGMA wal_autocheckpoint=1024; PRAGMA journal_size_limit=4194304");
    return r;
}

int mdi_schema_admit_offline(int root,sqlite3 *db) {
    struct md_inode_lifetime lifetime={.refs=1,.root=root};
    struct md_inode_store store={.db=db,.locked=1,.objects=-1,.lifetime=&lifetime};
    return mdi_schema_admit(&store,1,1,&mdi_schema_current);
}

int mdi_schema_begin_create(struct md_inode_store *s) {
    int r=mdi_sql(s, "BEGIN IMMEDIATE;"
            "PRAGMA application_id=1397248596; PRAGMA user_version=17;"
            "CREATE TABLE allocation_reserve(backing TEXT PRIMARY KEY CHECK(length(backing)=32)) STRICT;"
            "CREATE TABLE objects(object TEXT PRIMARY KEY CHECK(length(object)=32),kind INTEGER NOT NULL,"
                "device INTEGER NOT NULL,inode INTEGER NOT NULL,parent TEXT REFERENCES objects,"
                "name_count INTEGER NOT NULL DEFAULT 0 CHECK(name_count>=0),"
                "directory_count INTEGER NOT NULL DEFAULT 0 CHECK(directory_count>=0),"
                "backing TEXT NOT NULL CHECK(length(backing)=32),shared INTEGER NOT NULL DEFAULT 0 CHECK(shared IN (0,1)),"
                "logical_inode INTEGER NOT NULL,logical_device INTEGER NOT NULL,"
                "source INTEGER NOT NULL DEFAULT 0 CHECK(source>=0 AND source<256),"
                "mode INTEGER NOT NULL DEFAULT -1 CHECK(mode>=-1 AND mode<=4095),"
                "uid INTEGER NOT NULL DEFAULT 0 CHECK(uid>=0 AND uid<4294967295),"
                "gid INTEGER NOT NULL DEFAULT 0 CHECK(gid>=0 AND gid<4294967295),"
                "acl_mask INTEGER NOT NULL DEFAULT 0 CHECK(acl_mask>=0 AND acl_mask<=3),"
                "scope TEXT NOT NULL CHECK(length(scope)=32),"
                "rdev INTEGER NOT NULL DEFAULT 0 CHECK(rdev>=0),"
                "UNIQUE(scope,device,inode),CHECK(kind IN (32768,16384,40960,49152,4096,8192,24576)),"
                "CHECK((kind=16384)=(parent IS NOT NULL))) STRICT;"
            "CREATE TABLE sources(id INTEGER NOT NULL,path TEXT NOT NULL,device INTEGER NOT NULL,inode INTEGER NOT NULL,"
                "scope TEXT NOT NULL CHECK(length(scope)=32),PRIMARY KEY(scope,id),"
                "UNIQUE(scope,device,inode),CHECK(id>0 AND id<256)) STRICT;"
            "CREATE TABLE backings(backing TEXT NOT NULL,object TEXT NOT NULL REFERENCES objects,"
                "device INTEGER NOT NULL,inode INTEGER NOT NULL,source INTEGER NOT NULL,"
                "scope TEXT NOT NULL CHECK(length(scope)=32),"
                "UNIQUE(scope,device,inode),PRIMARY KEY(scope,backing,source)) STRICT;"
            "CREATE TRIGGER objects_added AFTER INSERT ON objects BEGIN "
                "INSERT INTO backings VALUES(NEW.backing,NEW.object,NEW.device,NEW.inode,NEW.source,NEW.scope); END;"
            "CREATE TRIGGER objects_copied AFTER UPDATE OF backing ON objects WHEN NEW.backing!=OLD.backing BEGIN "
                "INSERT INTO backings VALUES(NEW.backing,NEW.object,NEW.device,NEW.inode,NEW.source,NEW.scope); END;"
            "CREATE TABLE inode_views(scope TEXT PRIMARY KEY REFERENCES objects,kind INTEGER NOT NULL,"
                "upper TEXT REFERENCES objects,work TEXT REFERENCES objects,"
                "writable INTEGER NOT NULL DEFAULT 0 CHECK(writable IN (0,1)),"
                "user_xattr INTEGER NOT NULL DEFAULT 0 CHECK(user_xattr IN (0,1)),"
                "CHECK(length(scope)=32)) STRICT;"
            "CREATE TABLE overlay_origins(scope TEXT NOT NULL,device INTEGER NOT NULL,inode INTEGER NOT NULL,"
                "origin TEXT NOT NULL,object TEXT NOT NULL REFERENCES objects ON DELETE CASCADE,"
                "PRIMARY KEY(scope,device,inode,origin)) WITHOUT ROWID;"
            "CREATE TABLE overlay_links(object TEXT PRIMARY KEY REFERENCES objects ON DELETE CASCADE,"
                "upper TEXT REFERENCES objects,lower INTEGER NOT NULL CHECK(lower IN (0,1))) STRICT;"
            "CREATE TABLE overlay_lower_names(parent TEXT NOT NULL REFERENCES objects ON DELETE CASCADE,"
                "name BLOB NOT NULL,PRIMARY KEY(parent,name)) WITHOUT ROWID;"
            XATTR_SCHEMA
            "CREATE TABLE properties(key TEXT PRIMARY KEY,value INTEGER NOT NULL) STRICT;"
            "INSERT INTO properties VALUES('sealed',0);"
            "CREATE TABLE names(cookie INTEGER PRIMARY KEY AUTOINCREMENT,"
                "parent TEXT NOT NULL REFERENCES objects,name BLOB NOT NULL,"
                "object TEXT NOT NULL REFERENCES objects,UNIQUE(parent,name),"
                "CHECK(cookie>0 AND cookie<9223372036854775805)) STRICT;"
            "CREATE INDEX names_object ON names(object);"
            "CREATE INDEX names_cursor ON names(parent,cookie);"
            "CREATE TABLE file_paths(object TEXT PRIMARY KEY REFERENCES objects,"
                "parent TEXT NOT NULL REFERENCES objects,name BLOB NOT NULL,"
                "ambiguous INTEGER NOT NULL CHECK(ambiguous IN (0,1))) STRICT;"
            "CREATE TRIGGER names_added AFTER INSERT ON names BEGIN "
                "UPDATE objects SET name_count=name_count+1 WHERE object=NEW.object;"
                "INSERT INTO file_paths SELECT NEW.object,NEW.parent,NEW.name,name_count>1 "
                    "FROM objects WHERE object=NEW.object AND kind!=16384 "
                    "ON CONFLICT(object) DO UPDATE SET parent=excluded.parent,name=excluded.name,"
                    "ambiguous=max(file_paths.ambiguous,excluded.ambiguous);"
                "UPDATE objects SET directory_count=directory_count+1 WHERE object=NEW.parent "
                    "AND (SELECT kind FROM objects WHERE object=NEW.object)=16384; END;"
            "CREATE TRIGGER names_removed AFTER DELETE ON names BEGIN "
                "UPDATE objects SET name_count=name_count-1 WHERE object=OLD.object;"
                "UPDATE objects SET directory_count=directory_count-1 WHERE object=OLD.parent "
                    "AND (SELECT kind FROM objects WHERE object=OLD.object)=16384; END;"
            "CREATE TRIGGER names_immutable BEFORE UPDATE ON names BEGIN "
                "SELECT RAISE(ABORT,'replace namespace entries with delete and insert'); END;"
            "CREATE TABLE sockets(object TEXT PRIMARY KEY REFERENCES objects, address TEXT NOT NULL) STRICT;"
            "CREATE TABLE inode_channels(object TEXT PRIMARY KEY REFERENCES objects ON DELETE CASCADE,"
                "scope TEXT NOT NULL CHECK(length(scope)=32),channel TEXT NOT NULL CHECK(length(channel)=32),"
                "source INTEGER NOT NULL DEFAULT 0 CHECK(source>=0 AND source<256),UNIQUE(scope,channel)) STRICT;"
            "CREATE TRIGGER channel_added AFTER INSERT ON objects WHEN NEW.kind IN (4096,49152) BEGIN "
                "INSERT INTO inode_channels(object,scope,channel) VALUES(NEW.object,NEW.scope,NEW.object); END;"
            "CREATE TABLE fifo_pins(channel TEXT NOT NULL CHECK(length(channel)=32),owner TEXT NOT NULL CHECK(length(owner)=32),"
                "pid INTEGER NOT NULL,descriptor INTEGER NOT NULL,device INTEGER NOT NULL,inode INTEGER NOT NULL,"
                "PRIMARY KEY(channel,owner)) STRICT;"
            "CREATE INDEX fifo_identity ON fifo_pins(device,inode);"
            "CREATE TABLE file_capabilities(object TEXT PRIMARY KEY REFERENCES objects, value BLOB NOT NULL "
                "CHECK(length(value) IN (12,20,24))) STRICT;"
            "CREATE TABLE inode_acls(object TEXT NOT NULL REFERENCES objects,type INTEGER NOT NULL CHECK(type IN (1,2)),"
                "value BLOB NOT NULL CHECK(length(value)>=28 AND length(value)<=65532),PRIMARY KEY(object,type)) STRICT;"
            "CREATE TRIGGER acl_added AFTER INSERT ON inode_acls BEGIN "
                "UPDATE objects SET acl_mask=acl_mask|NEW.type WHERE object=NEW.object; END;"
            "CREATE TRIGGER acl_removed AFTER DELETE ON inode_acls BEGIN "
                "UPDATE objects SET acl_mask=acl_mask&~OLD.type WHERE object=OLD.object; END;"
            "CREATE TABLE events(sequence INTEGER PRIMARY KEY AUTOINCREMENT, parent TEXT NOT NULL,"
            "object TEXT NOT NULL,name BLOB NOT NULL,mask INTEGER NOT NULL,cookie INTEGER NOT NULL) STRICT;");
    if (!r) r=mdi_sql(s,PUBLICATION_SCHEMA DENTRY_SCHEMA);
    if (!r) r=set_revision(s,md_inode_store_revision());
    return r;
}
