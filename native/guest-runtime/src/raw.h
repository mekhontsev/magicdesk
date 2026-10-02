#ifndef MD_RAW_H
#define MD_RAW_H
#include <stddef.h>
#include <stdint.h>
#include <sys/syscall.h>

long md_raw(long, long, long, long, long, long, long);
extern void md_raw_return(void);
__attribute__((noreturn)) void md_enter(uintptr_t entry, uintptr_t stack, const void *image);
#define RAW0(n) md_raw(SYS_##n, 0, 0, 0, 0, 0, 0)
#define RAW1(n,a) md_raw(SYS_##n, (long)(a), 0, 0, 0, 0, 0)
#define RAW2(n,a,b) md_raw(SYS_##n, (long)(a), (long)(b), 0, 0, 0, 0)
#define RAW3(n,a,b,c) md_raw(SYS_##n, (long)(a), (long)(b), (long)(c), 0, 0, 0)
#define RAW4(n,a,b,c,d) md_raw(SYS_##n, (long)(a), (long)(b), (long)(c), (long)(d), 0, 0)
#define RAW5(n,a,b,c,d,e) md_raw(SYS_##n, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), 0)
#define RAW6(n,a,b,c,d,e,f) md_raw(SYS_##n, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), (long)(f))

size_t md_length(const char *);
void *memcpy(void *, const void *, size_t);
void *memset(void *, int, size_t);
void *memmove(void *, const void *, size_t);
int md_equal(const char *, const char *);
int md_prefix(const char *, const char *);
int md_copy(char *, size_t, const char *);
int md_append(char *, size_t, const char *);
void md_decimal(char *, unsigned long);
void md_error(const char *, long);
__attribute__((noreturn)) void md_die(const char *, long);
long md_read_memory(void *, const void *, size_t);
long md_write_memory(void *, const void *, size_t);
long md_read_string(char *, size_t, const char *);
extern size_t md_page_size;
#endif
