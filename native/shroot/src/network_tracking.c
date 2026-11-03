#include "network_tracking.h"
#include "network_flow.h"
#include <limits.h>
#include <string.h>
struct md_network_tracking_entry *md_network_tracking_find(struct md_network_tracking *t,uint64_t space) {
    for (unsigned i=0;i<t->count;++i) if (t->entries[i].space==space) return &t->entries[i];
    return NULL;
}
void md_network_tracking_init(struct md_network_tracking *t,struct md_network_fabric *f,const struct md_network_flow_path *path,
        uint64_t *serial,int64_t now) {
    *t=(struct md_network_tracking){.activity=now};
    for (unsigned i=0;i<path->count;++i) {
        const struct md_network_flow_step *s=&path->steps[i];
        struct md_network_tracking_entry *e=md_network_tracking_find(t,s->space);
        if (!e) { e=&t->entries[t->count++]; *e=(struct md_network_tracking_entry){.space=s->space,.id=++*serial}; }
        e->mark=s->ctmark;
    }
    md_network_tracking_limits(t,f);
}
void md_network_tracking_touch(struct md_network_tracking *t,struct md_network_fabric *f,uint64_t *serial,int64_t now) {
    t->activity=now;
    if (t->missing) {
        for (unsigned i=0;i<t->count;++i) if (!t->entries[i].id) t->entries[i].id=++*serial;
        t->missing=0; ++t->revision;
        md_network_tracking_limits(t,f);
    }
}
void md_network_tracking_forget(struct md_network_tracking *t,struct md_network_fabric *f,struct md_network_tracking_entry *e) {
    if (!e || !e->id) return;
    e->id=0; e->mark=0; ++t->missing; ++t->revision;
    md_network_tracking_limits(t,f);
}
void md_network_tracking_limits(struct md_network_tracking *t,struct md_network_fabric *f) {
    t->minimum_timeout[0]=t->minimum_timeout[1]=UINT32_MAX;
    for (unsigned i=0;i<t->count;++i) if (t->entries[i].id) {
        struct md_network_namespace *n=md_network_space_find(f,t->entries[i].space);
        for (unsigned j=0;j<2;++j) {
            uint32_t value=n ? n->conntrack_tcp_timeout[j] : 0;
            if (value<t->minimum_timeout[j]) t->minimum_timeout[j]=value;
        }
    }
}
int64_t md_network_tracking_next(const struct md_network_tracking *t) {
    return t->minimum_timeout[!!t->half_closed]==UINT32_MAX ? INT64_MAX
        : t->activity+(int64_t)t->minimum_timeout[!!t->half_closed]*1000;
}
int64_t md_network_tracking_deadline(const struct md_network_tracking *t,const struct md_network_namespace *n) {
    return t->activity+(int64_t)n->conntrack_tcp_timeout[!!t->half_closed]*1000;
}
int64_t md_network_tracking_expire(struct md_network_tracking *t,struct md_network_fabric *f,int64_t now) {
    if (md_network_tracking_next(t)>now) return md_network_tracking_next(t);
    int64_t earliest=INT64_MAX;
    for (unsigned i=0;i<t->count;++i) {
        struct md_network_tracking_entry *e=&t->entries[i];
        if (!e->id) continue;
        struct md_network_namespace *n=md_network_space_find(f,e->space);
        int64_t deadline=n ? md_network_tracking_deadline(t,n) : now;
        if (deadline<=now) md_network_tracking_forget(t,f,e);
        else if (deadline<earliest) earliest=deadline;
    }
    return earliest;
}
