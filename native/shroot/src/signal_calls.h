#ifndef MD_SIGNAL_CALLS_H
#define MD_SIGNAL_CALLS_H
#include <stdint.h>

struct md_signal_query { uint64_t value; };
long md_signal_query_call(long,const unsigned long *);
long md_signal_queue_call(long,const unsigned long *);
#endif
