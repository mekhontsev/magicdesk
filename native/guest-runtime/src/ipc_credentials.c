#define _GNU_SOURCE
#include "ipc_credentials.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <limits.h>
#include <sqlite3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/random.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

struct md_ipc_credentials { sqlite3 *db; int lease, gate; };
struct snapshot { struct md_ipc_identity ids; uint32_t groups[]; };
static int sql(int rc) { return rc == SQLITE_OK || rc == SQLITE_DONE || rc == SQLITE_ROW ? 0 : -EIO; }
static int statement(struct md_ipc_credentials *s, const char *text, sqlite3_stmt **q) {
    return sql(sqlite3_prepare_v2(s->db, text, -1, q, NULL));
}
static int execute(struct md_ipc_credentials *s, const char *text) {
    return sql(sqlite3_exec(s->db, text, NULL, NULL, NULL));
}
void md_ipc_credentials_close(struct md_ipc_credentials *s) {
    if (!s) return;
    if (s->db) sqlite3_close(s->db);
    if (s->gate >= 0) close(s->gate);
    if (s->lease >= 0) close(s->lease);
    free(s);
}
int md_ipc_credentials_open(const char *store, struct md_ipc_credentials **out) {
    struct md_ipc_credentials *s = calloc(1, sizeof(*s));
    if (!s) return -ENOMEM;
    s->lease = s->gate = -1;
    int root = open(store, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW), dir = -1;
    int r = root < 0 ? -errno : 0;
    if (!r && mkdirat(root, "ipc", 0700) && errno != EEXIST) r = -errno;
    if (!r && (dir = openat(root, "ipc", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) < 0) r = -errno;
    if (!r && (s->gate = openat(dir, "gate", O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600)) < 0) r = -errno;
    if (!r && flock(s->gate, LOCK_EX)) r = -errno;
    if (!r && (s->lease = openat(dir, "live", O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600)) < 0) r = -errno;
    int first = !r && !flock(s->lease, LOCK_EX | LOCK_NB);
    if (!r && !first && errno != EWOULDBLOCK) r = -errno;
    char link[64], path[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dir);
    ssize_t length = r ? -1 : readlink(link, path, sizeof(path)-sizeof("/credentials.db"));
    if (!r && length < 0) r = -errno;
    if (!r) memcpy(path+length, "/credentials.db", sizeof("/credentials.db"));
    if (!r) r = sql(sqlite3_open_v2(path, &s->db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOFOLLOW, NULL));
    if (!r && first) r = execute(s, "DROP TABLE IF EXISTS endpoints;DROP TABLE IF EXISTS connections;DROP TABLE IF EXISTS messages;");
    if (!r) r = execute(s, "PRAGMA journal_mode=WAL;PRAGMA synchronous=NORMAL;"
        "CREATE TABLE IF NOT EXISTS endpoints(name BLOB PRIMARY KEY,cookie INTEGER,revision INTEGER,identity BLOB,ready INTEGER);"
        "CREATE TABLE IF NOT EXISTS connections(cookie INTEGER PRIMARY KEY,name BLOB,identity BLOB,peer BLOB,endpoint BLOB,revision INTEGER,hidden INTEGER,valid INTEGER,link BLOB);"
        "CREATE INDEX IF NOT EXISTS connection_name ON connections(name);"
        "CREATE TABLE IF NOT EXISTS messages(nonce BLOB PRIMARY KEY,link BLOB,device INTEGER,inode INTEGER,pid INTEGER,uid INTEGER,gid INTEGER);");
    /* The first live owner discards stale metadata, including cookies from an
     * earlier boot. This directory is not part of OCI/image inode metadata. */
    if (!r && flock(s->lease, LOCK_SH)) r = -errno;
    if (s->gate >= 0) flock(s->gate, LOCK_UN);
    if (dir >= 0) close(dir);
    if (root >= 0) close(root);
    if (r) md_ipc_credentials_close(s); else *out = s;
    return r;
}
static int socket_key(int fd, uint64_t *cookie) {
    int domain = 0, type = 0;
    socklen_t n = sizeof(domain);
    if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &n)) return -errno;
    if (domain != AF_UNIX) return -EAFNOSUPPORT;
    n = sizeof(type);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &n)) return -errno;
    if (type != SOCK_STREAM && type != SOCK_SEQPACKET && type != SOCK_DGRAM) return -EPROTOTYPE;
    n = sizeof(*cookie);
    return getsockopt(fd, SOL_SOCKET, SO_COOKIE, cookie, &n) ? -errno : 0;
}
static int address(int fd, int peer, struct sockaddr_un *out, int *size) {
    socklen_t n = sizeof(*out);
    memset(out, 0, sizeof(*out));
    if ((peer ? getpeername(fd, (void *)out, &n) : getsockname(fd, (void *)out, &n))) return -errno;
    if (out->sun_family != AF_UNIX || n > sizeof(*out) || n < offsetof(struct sockaddr_un, sun_path)) return -EINVAL;
    *size = (int)n - offsetof(struct sockaddr_un, sun_path); return 0;
}
static int decode_name(const char *hex, struct sockaddr_un *a, int *size) {
    *a = (struct sockaddr_un){.sun_family=AF_UNIX};
    size_t n = hex ? strlen(hex) : 0;
    if (!n || n % 2 || n > 2 * sizeof(a->sun_path)) return -EINVAL;
    for (size_t i = 0; i < n / 2; ++i) {
        const char *hi = strchr("0123456789abcdef", hex[2*i]), *lo = strchr("0123456789abcdef", hex[2*i+1]);
        if (!hi || !lo) return -EINVAL;
        a->sun_path[i] = (char)(((hi - "0123456789abcdef") << 4) | (lo - "0123456789abcdef"));
    }
    *size = (int)n/2; return 0;
}
static struct snapshot *snapshot(const struct md_identity *id, pid_t pid, size_t *bytes) {
    unsigned n = md_identity_group_count(id);
    *bytes = sizeof(struct snapshot) + n * sizeof(uint32_t);
    struct snapshot *p = malloc(*bytes);
    if (!p) return NULL;
    p->ids = (struct md_ipc_identity){pid, id->uid.effective, id->gid.effective, n};
    if (n) memcpy(p->groups, md_identity_group_data(id), n * sizeof(uint32_t));
    return p;
}
static int blob_copy(sqlite3_stmt *q, int column, void **out, size_t *bytes) {
    int n = sqlite3_column_bytes(q, column);
    const void *p = sqlite3_column_blob(q, column);
    if (!p || n < (int)sizeof(struct md_ipc_identity)) return -EPROTO;
    const struct md_ipc_identity *id = p;
    if (id->group_count > MD_IDENTITY_GROUPS_MAX || (size_t)n != sizeof(*id) + id->group_count * sizeof(uint32_t)) return -EPROTO;
    *out = malloc((size_t)n);
    if (!*out) return -ENOMEM;
    memcpy(*out, p, (size_t)n); *bytes = (size_t)n; return 0;
}
static int save_connection(struct md_ipc_credentials *s, uint64_t key, const struct sockaddr_un *name, int size,
        const void *self, size_t self_size, const void *peer, size_t peer_size,
        const struct sockaddr_un *endpoint, int endpoint_size, int64_t revision, int hidden, int valid, const unsigned char link[16]) {
    sqlite3_stmt *q = NULL;
    int r = execute(s, "BEGIN IMMEDIATE");
    if (!r && size) {
        /* A kernel-bound address cannot be reused while its socket is alive.
         * Keep old peer snapshots, but retire its name-to-connection mapping. */
        r = statement(s, "UPDATE connections SET name=NULL WHERE name=?1 AND cookie<>?2", &q);
        if (!r) {
            sqlite3_bind_blob(q, 1, name->sun_path, size, SQLITE_STATIC);
            sqlite3_bind_int64(q, 2, (sqlite3_int64)key);
            r = sql(sqlite3_step(q));
        }
        sqlite3_finalize(q); q = NULL;
    }
    if (!r) r = statement(s, "INSERT OR REPLACE INTO connections VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)", &q);
    if (!r) {
        sqlite3_bind_int64(q, 1, (sqlite3_int64)key);
        sqlite3_bind_blob(q, 2, name ? name->sun_path : NULL, size, SQLITE_STATIC);
        sqlite3_bind_blob(q, 3, self, (int)self_size, SQLITE_STATIC);
        sqlite3_bind_blob(q, 4, peer, (int)peer_size, SQLITE_STATIC);
        sqlite3_bind_blob(q, 5, endpoint ? endpoint->sun_path : NULL, endpoint_size, SQLITE_STATIC);
        sqlite3_bind_int64(q, 6, revision); sqlite3_bind_int(q, 7, hidden); sqlite3_bind_int(q, 8, valid);
        sqlite3_bind_blob(q, 9, link, 16, SQLITE_STATIC);
        r = sql(sqlite3_step(q));
    }
    sqlite3_finalize(q);
    if (!r) r = execute(s, "COMMIT");
    if (r) execute(s, "ROLLBACK");
    return r;
}
static int listen_update(struct md_ipc_credentials *s, const struct md_fs_request *req,
        uint64_t key, const void *self, size_t bytes) {
    struct sockaddr_un name; int n;
    int r = address(req->directory[0], 0, &name, &n);
    if (r || !n) return r ? r : -EINVAL;
    sqlite3_stmt *q = NULL;
    if (req->flags == MD_IPC_LISTEN_BEGIN) {
        r = statement(s, "INSERT INTO endpoints VALUES(?1,?2,1,?3,0) ON CONFLICT(name) DO UPDATE SET "
            "cookie=excluded.cookie,revision=revision+1,identity=excluded.identity,ready=0", &q);
    } else {
        r = statement(s, "UPDATE endpoints SET ready=?3 WHERE name=?1 AND cookie=?2", &q);
    }
    if (!r) {
        sqlite3_bind_blob(q, 1, name.sun_path, n, SQLITE_STATIC); sqlite3_bind_int64(q, 2, (sqlite3_int64)key);
        if (req->flags == MD_IPC_LISTEN_BEGIN) sqlite3_bind_blob(q, 3, self, (int)bytes, SQLITE_STATIC);
        else sqlite3_bind_int(q, 3, req->offset == 0);
        r = sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); return r;
}
static int connect_begin(struct md_ipc_credentials *s, const struct md_fs_request *req,
        uint64_t key, const void *self, size_t bytes, struct md_fs_result *result) {
    struct sockaddr_un endpoint, local; int size, local_size;
    int type; socklen_t type_size = sizeof(type);
    if (getsockopt(req->directory[0], SOL_SOCKET, SO_TYPE, &type, &type_size)) return -errno;
    if (type == SOCK_DGRAM) return 0;
    if (!address(req->directory[0], 1, &endpoint, &size)) return 0;
    int r = decode_name(req->path[0], &endpoint, &size);
    sqlite3_stmt *q = NULL;
    if (!r) r = statement(s, "SELECT identity,revision,ready FROM endpoints WHERE name=?1", &q);
    void *peer = NULL; size_t peer_size = 0; int64_t revision = 0;
    if (!r) {
        sqlite3_bind_blob(q, 1, endpoint.sun_path, size, SQLITE_STATIC);
        int step = sqlite3_step(q);
        if (step == SQLITE_DONE) { sqlite3_finalize(q); return 0; } // External endpoint.
        if (step != SQLITE_ROW) r = -EIO;
        else if (!sqlite3_column_int(q, 2)) r = -EAGAIN;
        else { r = blob_copy(q, 0, &peer, &peer_size); revision = sqlite3_column_int64(q, 1); }
    }
    sqlite3_finalize(q);
    int hidden = 0;
    if (!r) r = address(req->directory[0], 0, &local, &local_size);
    if (!r && local_size) {
        sqlite3_stmt *old = NULL;
        r = statement(s, "SELECT hidden FROM connections WHERE cookie=?1", &old);
        if (!r) {
            sqlite3_bind_int64(old, 1, (sqlite3_int64)key);
            int step = sqlite3_step(old);
            if (step == SQLITE_ROW) hidden = sqlite3_column_int(old, 0);
            else if (step != SQLITE_DONE) r = -EIO;
        }
        sqlite3_finalize(old);
    }
    if (!r && !local_size) {
        /* A private transport name identifies this exact connecting socket to
         * accept(), without payload framing, PID guesses or netlink privilege.
         * The address adapter hides this implementation name from the guest. */
        unsigned char random[16];
        if (getrandom(random, sizeof(random), 0) != sizeof(random)) r = -EIO;
        if (!r) {
            memcpy(local.sun_path + 1, "md-guest-peer-", 14);
            for (unsigned i = 0; i < sizeof(random); i++) snprintf(local.sun_path + 15 + 2*i, 3, "%02x", random[i]);
            local_size = 47;
            if (bind(req->directory[0], (void *)&local, (socklen_t)(offsetof(struct sockaddr_un, sun_path)+local_size))) r = -errno;
            else hidden = 1;
        }
    }
    unsigned char link[16];
    if (!r && getrandom(link, sizeof(link), 0) != sizeof(link)) r = -EIO;
    if (!r) r = save_connection(s, key, &local, local_size, self, bytes, peer, peer_size,
        &endpoint, size, revision, hidden, 2, link);
    if (!r) result->position = 1;
    free(peer); return r;
}
static int connect_end(struct md_ipc_credentials *s, const struct md_fs_request *req, uint64_t key) {
    sqlite3_stmt *q = NULL;
    int r = statement(s, "UPDATE connections SET valid=CASE WHEN ?2=0 AND EXISTS(SELECT 1 FROM endpoints "
        "WHERE endpoints.name=connections.endpoint AND endpoints.revision=connections.revision AND ready=1) "
        "THEN 1 ELSE CASE WHEN ?2=0 THEN 0 ELSE -1 END END WHERE cookie=?1", &q);
    if (!r) {
        sqlite3_bind_int64(q, 1, (sqlite3_int64)key); sqlite3_bind_int64(q, 2, req->offset);
        r = sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); return r;
}
static int accept_peer(struct md_ipc_credentials *s, int fd, uint64_t key) {
    struct sockaddr_un peer; int size;
    int r = address(fd, 1, &peer, &size);
    if (r || !size) return r;
    sqlite3_stmt *q = NULL;
    r = statement(s, "SELECT identity,link FROM connections WHERE name=?1", &q);
    void *id = NULL; size_t bytes = 0;
    unsigned char link[16];
    if (!r) {
        sqlite3_bind_blob(q, 1, peer.sun_path, size, SQLITE_STATIC);
        int step = sqlite3_step(q);
        if (step == SQLITE_ROW) {
            r = blob_copy(q, 0, &id, &bytes);
            if (!r && sqlite3_column_bytes(q, 1) != sizeof(link)) r = -EPROTO;
            if (!r) memcpy(link, sqlite3_column_blob(q, 1), sizeof(link));
        }
        else r = step == SQLITE_DONE ? 0 : -EIO;
    }
    sqlite3_finalize(q);
    if (!r && id) r = save_connection(s, key, NULL, 0, NULL, 0, id, bytes, NULL, 0, 0, 0, 1, link);
    free(id); return r;
}
static int peer_snapshot(struct md_ipc_credentials *s, int fd, uint64_t key, struct md_fs_result *result) {
    sqlite3_stmt *q = NULL;
    int r = statement(s, "SELECT peer,valid FROM connections WHERE cookie=?1", &q);
    void *id = NULL; size_t bytes = 0;
    if (!r) {
        sqlite3_bind_int64(q, 1, (sqlite3_int64)key);
        int step = sqlite3_step(q);
        if (step == SQLITE_ROW) {
            if (sqlite3_column_int(q, 1) < 0) r = -ENODATA;
            else if (sqlite3_column_int(q, 1) != 1) r = -ESTALE;
            else r = blob_copy(q, 0, &id, &bytes);
        } else r = step == SQLITE_DONE ? -ENODATA : -EIO;
    }
    sqlite3_finalize(q);
    struct ucred actual; socklen_t size = sizeof(actual);
    if (!r && getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &actual, &size)) r = -errno;
    if (!r && actual.pid != ((struct md_ipc_identity *)id)->pid) r = -ESTALE;
    int copy = -1;
    if (!r && (copy = (int)syscall(SYS_memfd_create, "guest-ipc-identity", MFD_CLOEXEC | MFD_ALLOW_SEALING)) < 0) r = -errno;
    if (!r && write(copy, id, bytes) != (ssize_t)bytes) r = -EIO;
    if (!r && fcntl(copy, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL)) r = -errno;
    if (!r) result->fd = copy; else if (copy >= 0) close(copy);
    free(id); return r;
}
struct message_token { uint64_t magic; unsigned char nonce[16]; };
#define MESSAGE_MAGIC UINT64_C(0x4d44495043494431)
static int message_identity(struct md_ipc_credentials *s, const struct md_identity *id,
        const struct md_fs_request *req, uint64_t key, struct md_fs_result *out) {
    sqlite3_stmt *q = NULL;
    unsigned char link[16];
    int r = statement(s, "SELECT link,valid FROM connections WHERE cookie=?1", &q);
    if (!r) {
        sqlite3_bind_int64(q, 1, (sqlite3_int64)key);
        int step = sqlite3_step(q);
        if (step != SQLITE_ROW) r = step == SQLITE_DONE ? -ENODATA : -EIO;
        else if (sqlite3_column_int(q, 1) != 1 || sqlite3_column_bytes(q, 0) != sizeof(link)) r = -ESTALE;
        else memcpy(link, sqlite3_column_blob(q, 0), sizeof(link));
    }
    sqlite3_finalize(q); q = NULL;
    if (r) return r;
    struct message_token token = {.magic=MESSAGE_MAGIC};
    struct stat st;
    int fd = -1;
    if (req->flags == MD_IPC_MESSAGE_CREATE) {
        if ((pid_t)req->offset != req->peer
                || (id->uid.effective && req->attributes.uid != id->uid.real
                    && req->attributes.uid != id->uid.effective && req->attributes.uid != id->uid.saved)
                || (id->uid.effective && req->attributes.gid != id->gid.real
                    && req->attributes.gid != id->gid.effective && req->attributes.gid != id->gid.saved)) return -EPERM;
        if (getrandom(token.nonce, sizeof(token.nonce), 0) != sizeof(token.nonce)) return -EIO;
        fd = (int)syscall(SYS_memfd_create, "guest-ipc-message", MFD_CLOEXEC | MFD_ALLOW_SEALING);
        if (fd < 0) return -errno;
        if (write(fd, &token, sizeof(token)) != sizeof(token)) r = -EIO;
        if (!r && fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL)) r = -errno;
        if (!r && fstat(fd, &st)) r = -errno;
        if (!r) r = statement(s, "INSERT INTO messages VALUES(?1,?2,?3,?4,?5,?6,?7)", &q);
        if (!r) {
            sqlite3_bind_blob(q, 1, token.nonce, sizeof(token.nonce), SQLITE_STATIC);
            sqlite3_bind_blob(q, 2, link, sizeof(link), SQLITE_STATIC);
            sqlite3_bind_int64(q, 3, st.st_dev); sqlite3_bind_int64(q, 4, st.st_ino);
            sqlite3_bind_int(q, 5, req->peer); sqlite3_bind_int64(q, 6, req->attributes.uid); sqlite3_bind_int64(q, 7, req->attributes.gid);
            r = sql(sqlite3_step(q));
        }
        if (!r) out->fd = fd; else close(fd);
    } else {
        fd = req->directory[1];
        int seals = fcntl(fd, F_GET_SEALS), required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
        if (seals < 0 || (seals & required) != required || fstat(fd, &st) || st.st_size != sizeof(token)
                || pread(fd, &token, sizeof(token), 0) != sizeof(token) || token.magic != MESSAGE_MAGIC) return -ENODATA;
        r = statement(s, "SELECT pid,uid,gid FROM messages WHERE nonce=?1 AND link=?2 AND device=?3 AND inode=?4", &q);
        if (!r) {
            sqlite3_bind_blob(q, 1, token.nonce, sizeof(token.nonce), SQLITE_STATIC);
            sqlite3_bind_blob(q, 2, link, sizeof(link), SQLITE_STATIC);
            sqlite3_bind_int64(q, 3, st.st_dev); sqlite3_bind_int64(q, 4, st.st_ino);
            int step = sqlite3_step(q);
            if (step != SQLITE_ROW) r = step == SQLITE_DONE ? -ENODATA : -EIO;
            else { out->position = sqlite3_column_int(q, 0); out->info.uid = (uint32_t)sqlite3_column_int64(q, 1); out->info.gid = (uint32_t)sqlite3_column_int64(q, 2); }
        }
    }
    sqlite3_finalize(q); return r;
}
void md_ipc_credentials_execute(struct md_ipc_credentials *s, const struct md_identity *identity,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    *out = (struct md_fs_result){.fd=-1};
    if (!s) { out->error = -ENOTSUP; return; }
    uint64_t key;
    int r = socket_key(q->directory[0], &key);
    if (!r && flock(s->gate, LOCK_EX)) r = -errno;
    if (r) { out->error = r; return; }
    size_t bytes = 0;
    struct snapshot *self = NULL;
    if (q->flags == MD_IPC_LISTEN_BEGIN || q->flags == MD_IPC_CONNECT_BEGIN || q->flags == MD_IPC_PAIR) {
        self = snapshot(identity, q->peer, &bytes);
        if (!self) r = -ENOMEM;
    }
    if (!r) switch (q->flags) {
    case MD_IPC_LISTEN_BEGIN: case MD_IPC_LISTEN_END: r = listen_update(s, q, key, self, bytes); break;
    case MD_IPC_CONNECT_BEGIN: r = connect_begin(s, q, key, self, bytes, out); break;
    case MD_IPC_CONNECT_END: r = connect_end(s, q, key); break;
    case MD_IPC_ACCEPT: r = accept_peer(s, q->directory[0], key); break;
    case MD_IPC_PAIR: {
        uint64_t other;
        unsigned char link[16];
        r = socket_key(q->directory[1], &other);
        if (!r && getrandom(link, sizeof(link), 0) != sizeof(link)) r = -EIO;
        if (!r) r = save_connection(s, key, NULL, 0, self, bytes, self, bytes, NULL, 0, 0, 0, 1, link);
        if (!r) r = save_connection(s, other, NULL, 0, self, bytes, self, bytes, NULL, 0, 0, 0, 1, link);
        break;
    }
    case MD_IPC_PEER: r = peer_snapshot(s, q->directory[0], key, out); break;
    case MD_IPC_MESSAGE_CREATE: case MD_IPC_MESSAGE_READ: r = message_identity(s, identity, q, key, out); break;
    case MD_IPC_NAME: {
        struct sockaddr_un name; int n;
        r = decode_name(q->path[0], &name, &n);
        sqlite3_stmt *query = NULL;
        if (!r) r = statement(s, "SELECT hidden FROM connections WHERE name=?1", &query);
        if (!r) {
            sqlite3_bind_blob(query, 1, name.sun_path, n, SQLITE_STATIC);
            int step = sqlite3_step(query);
            if (step == SQLITE_ROW) out->position = sqlite3_column_int(query, 0);
            else if (step != SQLITE_DONE) r = -EIO;
        }
        sqlite3_finalize(query); break;
    }
    default: r = -EINVAL; break;
    }
    (void)output;
    free(self); flock(s->gate, LOCK_UN); out->error = r;
}
