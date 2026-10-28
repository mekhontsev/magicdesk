#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/capability.h>
#include <sys/syscall.h>
#include <sys/auxv.h>
#include <sys/fsuid.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

int setid_library_value(void);
int main(int argc,char **argv) {
    int report=STDOUT_FILENO;
    unsigned closed=0;
    for (int i=1;i+2<argc;++i) if (!strcmp(argv[i],"--report-fd")) {
        report=atoi(argv[i+1]); closed=(unsigned)atoi(argv[i+2]); break;
    }
    if (getauxval(AT_SECURE)) for (unsigned i=0;i<3;++i) if (closed&(1U<<i)) {
        struct stat st;
        unsigned minor=3;
#ifdef __GLIBC__
        if (!i) minor=7;
#endif
        if (fcntl((int)i,F_GETFD)<0 || fstat((int)i,&st) || !S_ISCHR(st.st_mode)
                || st.st_rdev!=makedev(1,minor)) return 3;
    }
    uid_t r, e, s;
    gid_t gr, ge, gs;
    if (getresuid(&r,&e,&s) || getresgid(&gr,&ge,&gs)) return 1;
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3};
    struct __user_cap_data_struct caps[2];
    if (syscall(SYS_capget,&header,caps)) return 2;
    dprintf(report,"{\"uid\":[%u,%u,%u,%u],\"gid\":[%u,%u,%u,%u],"
        "\"aux\":[%lu,%lu,%lu,%lu],\"secure\":%lu,\"env\":%d,\"library\":%d,"
        "\"caps\":[%llu,%llu]}\n",
        r,e,s,setfsuid((uid_t)-1),gr,ge,gs,setfsgid((gid_t)-1),
        getauxval(AT_UID),getauxval(AT_EUID),getauxval(AT_GID),getauxval(AT_EGID),
        getauxval(AT_SECURE),secure_getenv("LD_LIBRARY_PATH")!=NULL,setid_library_value(),
        (unsigned long long)caps[0].permitted|((unsigned long long)caps[1].permitted<<32),
        (unsigned long long)caps[0].effective|((unsigned long long)caps[1].effective<<32));
    return 0;
}
