#ifndef MD_UTS_VIEW_H
#define MD_UTS_VIEW_H
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/utsname.h>

enum { MD_UTS_VIEW_WORDS=(sizeof(struct utsname)+sizeof(uint64_t)-1)/sizeof(uint64_t) };
struct md_uts_view {
    atomic_uint_fast64_t sequence;
    atomic_uint_fast64_t words[MD_UTS_VIEW_WORDS];
};
/* The namespace serializes writers. Readers never spin behind a descheduled
 * publisher: contention retains the ordinary operation owner. Atomic payload
 * words keep concurrent reads valid under the C memory model. */
static inline void md_uts_view_publish(struct md_uts_view *view,const struct utsname *names) {
    uint64_t words[MD_UTS_VIEW_WORDS]={0};
    memcpy(words,names,sizeof(*names));
    uint64_t sequence=atomic_load_explicit(&view->sequence,memory_order_relaxed)|1;
    atomic_store_explicit(&view->sequence,sequence,memory_order_release);
    atomic_thread_fence(memory_order_release);
    for (unsigned i=0;i<MD_UTS_VIEW_WORDS;++i)
        atomic_store_explicit(&view->words[i],words[i],memory_order_relaxed);
    atomic_store_explicit(&view->sequence,sequence+1,memory_order_release);
}
static inline int md_uts_view_read(const struct md_uts_view *view,struct utsname *names) {
    uint64_t sequence=atomic_load_explicit(&view->sequence,memory_order_acquire);
    if (!sequence || (sequence&1)) return 0;
    uint64_t words[MD_UTS_VIEW_WORDS];
    for (unsigned i=0;i<MD_UTS_VIEW_WORDS;++i)
        words[i]=atomic_load_explicit(&view->words[i],memory_order_relaxed);
    atomic_thread_fence(memory_order_acquire);
    if (sequence!=atomic_load_explicit(&view->sequence,memory_order_relaxed)) return 0;
    memcpy(names,words,sizeof(*names));
    return 1;
}
#endif
