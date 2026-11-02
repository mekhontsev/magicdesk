#define _GNU_SOURCE
#include "fs_engine.h"
#include "render_device.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

void md_fs_render_prepare(struct md_filesystem *fs) {
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},
        .path={"/etc/shroot/render-node",NULL},.flags=O_RDONLY|O_CLOEXEC|O_NONBLOCK};
    struct md_fs_result out;
    md_fs_view_execute(fs,&q,&out,NULL);
    char profile[32]={0}; struct stat st;
    ssize_t length=!out.error && !out.host_path && !out.open_completion.kind && out.fd>=0
        && !fstat(out.fd,&st) && S_ISREG(st.st_mode) ? read(out.fd,profile,sizeof(profile)) : -1;
    static const char completion[]="turnip-completion-v1\n";
    int completion_owned=length==(ssize_t)sizeof(completion)-1 && !memcmp(profile,completion,sizeof(completion)-1);
    int selected=completion_owned || (length==7 && !memcmp(profile,"turnip\n",7));
    if (out.fd>=0) close(out.fd);
    if (!selected) return;
    int error=md_render_device_admit(&fs->render_device,completion_owned);
    if (error) fprintf(stderr,"Shroot: render-node unavailable: %s; retaining existing graphics path\n",strerror(-error));
}
