#define _GNU_SOURCE
#include "guest_accounts.h"
#include "guest_user.h"
#include "fs_engine.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int number(const char *s, uint32_t *out) {
    uint64_t n = 0;
    if (!*s) return 0;
    for (; *s; ++s) {
        if (*s < '0' || *s > '9') return 0;
        n = n * 10 + (unsigned)(*s - '0');
        if (n >= UINT32_MAX) return -EINVAL;
    }
    *out = (uint32_t)n; return 1;
}
static int accounts(struct md_filesystem *fs, const char *path, char **out) {
    *out = NULL;
    struct md_fs_request q = {.operation=MD_FS_OPEN,.directory={-1,-1},.path={path,NULL},.flags=O_RDONLY|O_CLOEXEC};
    struct md_fs_result opened;
    md_fs_execute(fs,&q,&opened,NULL);
    if (opened.error == -ENOENT) return 0;
    if (opened.error) return opened.error;
    struct stat st;
    int r = fstat(opened.fd,&st) ? -errno : !S_ISREG(st.st_mode) ? -EINVAL : 0;
    if (!r && (st.st_size < 0 || st.st_size > 1024*1024)) r = -E2BIG;
    char *text = !r ? malloc((size_t)st.st_size+1) : NULL;
    if (!r && !text) r = -ENOMEM;
    size_t at = 0;
    while (!r && at < (size_t)st.st_size) {
        ssize_t n = read(opened.fd,text+at,(size_t)st.st_size-at);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { r = n < 0 ? -errno : -ESTALE; break; }
        at += (size_t)n;
    }
    close(opened.fd);
    if (!r && memchr(text,0,at)) r = -EINVAL;
    if (r) free(text); else { text[at]=0; *out=text; }
    return r;
}
static int fields(char *line, char **out, unsigned count) {
    for (unsigned i=0;i<count;i++) { out[i]=strsep(&line,":"); if (!out[i]) return 0; }
    return !line;
}
int md_guest_user_resolve(struct md_filesystem *fs, const char *selection, struct md_identity *out) {
    if (!selection || !*selection) selection="0";
    if (strlen(selection)>1024) return -E2BIG;
    uint32_t direct_uid, direct_gid;
    if (!md_guest_user_parse(selection, &direct_uid, &direct_gid)) {
        *out = md_identity_new(direct_uid, direct_gid);
        return 0;
    }
    char *owned=strdup(selection); if (!owned) return -ENOMEM;
    char *group=strchr(owned,':'); if (group) *group++=0;
    uint32_t uid=0,gid=0;
    int numeric=number(owned,&uid), r=numeric<0 ? numeric : 0;
    char *passwd=NULL,*groups=NULL,*username=NULL;
    if (!r) r=accounts(fs,"/etc/passwd",&passwd);
    for (char *cursor=passwd,*line; !r && cursor && (line=strsep(&cursor,"\n"));) {
        char *f[7]; uint32_t candidate,primary;
        if (!fields(line,f,7) || number(f[2],&candidate)!=1 || number(f[3],&primary)!=1) continue;
        if (numeric ? candidate!=uid : strcmp(f[0],owned)) continue;
        uid=candidate; gid=primary; username=strdup(f[0]);
        if (!username) r=-ENOMEM;
        break;
    }
    if (!r && !numeric && !username) r=-ENOENT;
    int group_number=group ? number(group,&gid) : 0;
    if (!r && group && (!*group || group_number<0)) r=-EINVAL;
    if (!r) r=accounts(fs,"/etc/group",&groups);
    uint32_t *members=!r ? malloc(MD_IDENTITY_GROUPS_MAX*sizeof(*members)) : NULL;
    if (!r && !members) r=-ENOMEM;
    unsigned count=0; int found=!group || group_number==1;
    for (char *cursor=groups,*line; !r && cursor && (line=strsep(&cursor,"\n"));) {
        char *f[4]; uint32_t candidate;
        if (!fields(line,f,4) || number(f[2],&candidate)!=1) continue;
        if (group && !group_number && !strcmp(f[0],group)) { gid=candidate; found=1; }
        if (!group && username) for (char *member,*rest=f[3];(member=strsep(&rest,","));) {
            if (strcmp(member,username)) continue;
            if (count==MD_IDENTITY_GROUPS_MAX) r=-E2BIG; else members[count++]=candidate;
            break;
        }
    }
    if (!r && !found) r=-ENOENT;
    if (!r) { *out=md_identity_new(uid,gid); r=md_identity_groups(out,members,count); }
    free(members);free(username);free(groups);free(passwd);free(owned);return r;
}
int md_guest_group_argument(const struct md_identity *identity, char **out) {
    unsigned count=md_identity_group_count(identity);
    char *text=malloc((size_t)count*11+1); if (!text) return -ENOMEM;
    size_t at=0; const uint32_t *ids=md_identity_group_data(identity);
    for (unsigned i=0;i<count;i++) at+=(size_t)sprintf(text+at,"%s%u",i ? "," : "",ids[i]);
    text[at]=0; *out=text; return 0;
}
