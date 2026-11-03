#define _GNU_SOURCE
#include "cgroup_resources.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

const struct md_cgroup_control md_cgroup_resource_controls[MD_CG_RESOURCE_COUNT]={
    [MD_CG_CPU_STAT]={"cpu.stat",0444,1,0,1},
    [MD_CG_CPU_WEIGHT]={"cpu.weight",0644,1,MD_CG_CPU,0},
    [MD_CG_CPU_MAX]={"cpu.max",0644,1,MD_CG_CPU,0},
    [MD_CG_CPU_BURST]={"cpu.max.burst",0644,1,MD_CG_CPU,0},
    [MD_CG_CPUSET_CPUS]={"cpuset.cpus",0644,1,MD_CG_CPUSET,0},
    [MD_CG_CPUSET_MEMS]={"cpuset.mems",0644,1,MD_CG_CPUSET,0},
    [MD_CG_CPUSET_CPUS_EFFECTIVE]={"cpuset.cpus.effective",0444,1,MD_CG_CPUSET,1},
    [MD_CG_CPUSET_MEMS_EFFECTIVE]={"cpuset.mems.effective",0444,1,MD_CG_CPUSET,1},
    [MD_CG_MEMORY_CURRENT]={"memory.current",0444,1,MD_CG_MEMORY,1},
    [MD_CG_MEMORY_PEAK]={"memory.peak",0444,1,MD_CG_MEMORY,1},
    [MD_CG_MEMORY_MIN]={"memory.min",0644,1,MD_CG_MEMORY,0},
    [MD_CG_MEMORY_LOW]={"memory.low",0644,1,MD_CG_MEMORY,0},
    [MD_CG_MEMORY_HIGH]={"memory.high",0644,1,MD_CG_MEMORY,0},
    [MD_CG_MEMORY_MAX]={"memory.max",0644,1,MD_CG_MEMORY,0},
    [MD_CG_MEMORY_SWAP_CURRENT]={"memory.swap.current",0444,1,MD_CG_MEMORY,1},
    [MD_CG_MEMORY_SWAP_MAX]={"memory.swap.max",0644,1,MD_CG_MEMORY,0},
    [MD_CG_MEMORY_STAT]={"memory.stat",0444,1,MD_CG_MEMORY,1},
    [MD_CG_MEMORY_EVENTS]={"memory.events",0444,1,MD_CG_MEMORY,1},
    [MD_CG_MEMORY_EVENTS_LOCAL]={"memory.events.local",0444,1,MD_CG_MEMORY,1},
    [MD_CG_MEMORY_OOM_GROUP]={"memory.oom.group",0644,1,MD_CG_MEMORY,0}
};
static const struct { const char *name; unsigned bit; } controllers[]={
    {"cpu",MD_CG_CPU},{"cpuset",MD_CG_CPUSET},{"memory",MD_CG_MEMORY},{"pids",MD_CG_PIDS}
};
unsigned md_cgroup_controller(const char *name) {
    for (unsigned i=0;i<sizeof(controllers)/sizeof(*controllers);++i)
        if (!strcmp(name,controllers[i].name)) return controllers[i].bit;
    return 0;
}
void md_cgroup_controller_text(FILE *stream,unsigned mask) {
    int separator=0;
    for (unsigned i=0;i<sizeof(controllers)/sizeof(*controllers);++i) if (mask&controllers[i].bit) {
        fprintf(stream,"%s%s",separator ? " " : "",controllers[i].name); separator=1;
    }
    fputc('\n',stream);
}
void md_cgroup_policy_init(struct md_cgroup_policy *p) {
    *p=(struct md_cgroup_policy){.period=100000};
    p->values[MD_CG_CPU_WEIGHT]=100;
    const unsigned unlimited[]={MD_CG_CPU_MAX,MD_CG_MEMORY_HIGH,MD_CG_MEMORY_MAX,MD_CG_MEMORY_SWAP_MAX};
    for (unsigned i=0;i<sizeof(unlimited)/sizeof(*unlimited);++i) p->values[unlimited[i]]=UINT64_MAX;
}
void md_cgroup_policy_reset(struct md_cgroup_policy *p,unsigned mask) {
    struct md_cgroup_policy defaults; md_cgroup_policy_init(&defaults);
    for (unsigned i=0;i<MD_CG_RESOURCE_COUNT;++i)
        if (md_cgroup_resource_controls[i].controller&mask) p->values[i]=defaults.values[i];
    if (mask&MD_CG_CPU) p->period=defaults.period;
    if (mask&MD_CG_CPUSET) { CPU_ZERO(&p->cpus); CPU_ZERO(&p->mems); }
}
static int integer(const char *text,int unlimited,uint64_t *out) {
    if (unlimited && !strcmp(text,"max")) { *out=UINT64_MAX; return 0; }
    if (!*text || strspn(text,"0123456789")!=strlen(text)) return -EINVAL;
    errno=0; unsigned long long value=strtoull(text,NULL,10);
    if (errno || value>INT64_MAX) return -ERANGE;
    *out=value; return 0;
}
static int parse_set(const char *text,cpu_set_t *out) {
    CPU_ZERO(out);
    while (*text) {
        if (*text<'0' || *text>'9') return -EINVAL;
        char *end; errno=0; unsigned long first=strtoul(text,&end,10),last=first;
        if (errno || first>=CPU_SETSIZE) return -ERANGE;
        if (*end=='-') {
            text=end+1; if (*text<'0' || *text>'9') return -EINVAL;
            last=strtoul(text,&end,10);
            if (errno || last>=CPU_SETSIZE) return -ERANGE;
            if (last<first) return -EINVAL;
        }
        for (unsigned long i=first;i<=last;++i) CPU_SET(i,out);
        if (*end && (*end!=',' || !end[1])) return -EINVAL;
        text=*end ? end+1 : end;
    }
    return 0;
}
static void set_text(FILE *stream,const cpu_set_t *set) {
    int separator=0;
    for (unsigned i=0;i<CPU_SETSIZE;++i) if (CPU_ISSET(i,set)) {
        unsigned last=i;
        while (last+1<CPU_SETSIZE && CPU_ISSET(last+1,set)) ++last;
        fprintf(stream,"%s%u",separator ? "," : "",i);
        if (last!=i) fprintf(stream,"-%u",last);
        separator=1; i=last;
    }
    fputc('\n',stream);
}
static int read_text(const char *path,char *out,size_t size) {
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if (fd<0) return -errno;
    ssize_t n;
    do n=read(fd,out,size-1); while (n<0 && errno==EINTR);
    int error=n<0 ? -errno : (size_t)n==size-1 ? -EOVERFLOW : 0;
    close(fd);
    if (!error) out[n]=0;
    return error;
}
int md_cgroup_machine_sets(cpu_set_t *cpus,cpu_set_t *mems) {
    if (sched_getaffinity(0,sizeof(*cpus),cpus)) return -errno;
    char text[4096];
    int error=read_text("/sys/devices/system/node/online",text,sizeof(text));
    if (error==-ENOENT) { CPU_ZERO(mems); CPU_SET(0,mems); return 0; }
    if (error) return error;
    text[strcspn(text,"\n")]=0;
    return parse_set(text,mems);
}
int md_cgroup_policy_write(struct md_cgroup_policy *p,unsigned file,char *text,
        const cpu_set_t *cpus,const cpu_set_t *mems) {
    if (file>=MD_CG_RESOURCE_COUNT || !(md_cgroup_resource_controls[file].mode&0200)) return -EACCES;
    if (file==MD_CG_CPUSET_CPUS || file==MD_CG_CPUSET_MEMS) {
        cpu_set_t next,intersection;
        int error=parse_set(text,&next); if (error) return error;
        CPU_AND(&intersection,&next,file==MD_CG_CPUSET_CPUS ? cpus : mems);
        if (!CPU_EQUAL(&next,&intersection)) return -EINVAL;
        *(file==MD_CG_CPUSET_CPUS ? &p->cpus : &p->mems)=next; return 0;
    }
    uint64_t value,period=p->period;
    if (file==MD_CG_CPU_MAX) {
        char *cursor=NULL,*quota=strtok_r(text," \t",&cursor),*interval=strtok_r(NULL," \t",&cursor);
        if (!quota || strtok_r(NULL," \t",&cursor)) return -EINVAL;
        int error=integer(quota,1,&value);
        if (!error && interval) error=integer(interval,0,&period);
        if (error) return error;
        if (value<1000 || period<1000 || period>1000000) return -EINVAL;
        p->values[file]=value; p->period=period; return 0;
    }
    int unlimited=file==MD_CG_MEMORY_MAX || file==MD_CG_MEMORY_HIGH || file==MD_CG_MEMORY_SWAP_MAX;
    int error=integer(text,unlimited,&value); if (error) return error;
    if ((file==MD_CG_CPU_WEIGHT && (!value || value>10000))
            || (file==MD_CG_CPU_BURST && value>1000000)
            || (file==MD_CG_MEMORY_OOM_GROUP && value>1)) return -EINVAL;
    p->values[file]=value; return 0;
}
void md_cgroup_resource_text(FILE *stream,const struct md_cgroup_policy *p,unsigned file,
        const struct md_cgroup_usage *usage,const cpu_set_t *cpus,const cpu_set_t *mems) {
    uint64_t value=p->values[file];
    switch (file) {
    case MD_CG_CPU_STAT:
        fprintf(stream,"usage_usec %" PRIu64 "\nuser_usec %" PRIu64 "\nsystem_usec %" PRIu64
            "\nnr_periods 0\nnr_throttled 0\nthrottled_usec 0\n",usage->user+usage->system,usage->user,usage->system);
        return;
    case MD_CG_CPU_MAX:
        if (value==UINT64_MAX) fputs("max",stream); else fprintf(stream,"%" PRIu64,value);
        fprintf(stream," %" PRIu64 "\n",p->period); return;
    case MD_CG_CPUSET_CPUS: set_text(stream,&p->cpus); return;
    case MD_CG_CPUSET_MEMS: set_text(stream,&p->mems); return;
    case MD_CG_CPUSET_CPUS_EFFECTIVE: set_text(stream,cpus); return;
    case MD_CG_CPUSET_MEMS_EFFECTIVE: set_text(stream,mems); return;
    case MD_CG_MEMORY_CURRENT: value=usage->anon+usage->file+usage->shmem; break;
    case MD_CG_MEMORY_PEAK: value=usage->peak; break;
    case MD_CG_MEMORY_SWAP_CURRENT: value=usage->swap; break;
    case MD_CG_MEMORY_STAT:
        fprintf(stream,"anon %" PRIu64 "\nfile %" PRIu64 "\nshmem %" PRIu64
            "\npgfault %" PRIu64 "\npgmajfault %" PRIu64 "\n",
            usage->anon,usage->file+usage->shmem,usage->shmem,usage->minor+usage->major,usage->major); return;
    case MD_CG_MEMORY_EVENTS: case MD_CG_MEMORY_EVENTS_LOCAL:
        fputs("low 0\nhigh 0\nmax 0\noom 0\noom_kill 0\noom_group_kill 0\n",stream); return;
    default: break;
    }
    if (value==UINT64_MAX) fputs("max\n",stream); else fprintf(stream,"%" PRIu64 "\n",value);
}
int md_cgroup_task_sample(pid_t tgid,pid_t tid,struct md_cgroup_task_usage *out) {
    char path[96],text[4096];
    snprintf(path,sizeof(path),"/proc/%d/task/%d/stat",tgid,tid);
    int error=read_text(path,text,sizeof(text)); if (error) return error;
    char *end=strrchr(text,')');
    if (!end || end[1]!=' ') return -EPROTO;
    char *cursor=NULL; unsigned field=3;
    struct md_cgroup_task_usage sample={0};
    for (char *word=strtok_r(end+2," \n",&cursor);word;word=strtok_r(NULL," \n",&cursor),++field) {
        uint64_t *value=field==10 ? &sample.minor : field==12 ? &sample.major
            : field==14 ? &sample.user : field==15 ? &sample.system : NULL;
        if (value && integer(word,0,value)) return -EPROTO;
        if (field==15) { *out=sample; return 0; }
    }
    return -EPROTO;
}
int md_cgroup_memory_sample(pid_t tgid,pid_t tid,struct md_cgroup_usage *out) {
    char path[96],text[16384];
    snprintf(path,sizeof(path),"/proc/%d/task/%d/status",tgid,tid);
    int error=read_text(path,text,sizeof(text)); if (error) return error;
    struct md_cgroup_usage sample={0}; unsigned found=0;
    for (char *line=text;line && *line;) {
        char *next=strchr(line,'\n'); if (next) *next++=0;
        uint64_t *value=!strncmp(line,"RssAnon:",8) ? &sample.anon
            : !strncmp(line,"RssFile:",8) ? &sample.file : !strncmp(line,"RssShmem:",9) ? &sample.shmem
            : !strncmp(line,"VmSwap:",7) ? &sample.swap : NULL;
        if (value) {
            char *number=strchr(line,':')+1; number+=strspn(number," \t");
            char *end=number+strspn(number,"0123456789");
            if (strcmp(end," kB") && strcmp(end,"\tkB")) return -EPROTO;
            *end=0;
            if (integer(number,0,value) || *value>UINT64_MAX/1024) return -EPROTO;
            *value*=1024; ++found;
        }
        line=next;
    }
    if (found!=4) return -ESRCH;
    *out=sample; return 0;
}
