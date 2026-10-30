#ifndef MD_LWIP_ARCH_CC_H
#define MD_LWIP_ARCH_CC_H
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_PLATFORM_DIAG(message) do { printf message; } while (0)
#define LWIP_PLATFORM_ASSERT(message) do { fprintf(stderr,"lwIP: %s\n",message); abort(); } while (0)
#define LWIP_RAND() ((uint32_t)arc4random())
#endif
