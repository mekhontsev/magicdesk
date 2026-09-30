#include "interception_stacks.h"
#include <errno.h>
#include <stdlib.h>

struct slot { uintptr_t top; int used; struct slot *next; };
struct md_interception_stacks { unsigned references; struct slot *slots; };

struct md_interception_stacks *md_stacks_new(void) {
    struct md_interception_stacks *s = calloc(1, sizeof(*s));
    if (s) s->references = 1;
    return s;
}
struct md_interception_stacks *md_stacks_fork(struct md_interception_stacks *s, int shared) {
    if (shared) { s->references++; return s; }
    struct md_interception_stacks *copy = md_stacks_new();
    if (!copy) return NULL;
    /* fork inherits mappings, but not other threads or active continuations. */
    for (struct slot *p = s->slots; p; p = p->next) {
        if (md_stacks_add(copy, p->top)) { md_stacks_release(copy); return NULL; }
        md_stacks_return(copy, p->top);
    }
    return copy;
}
void md_stacks_release(struct md_interception_stacks *s) {
    if (!s || --s->references) return;
    while (s->slots) { struct slot *next = s->slots->next; free(s->slots); s->slots = next; }
    free(s);
}
uintptr_t md_stacks_take(struct md_interception_stacks *s) {
    for (struct slot *p = s->slots; p; p = p->next)
        if (!p->used) { p->used = 1; return p->top; }
    return 0;
}
int md_stacks_add(struct md_interception_stacks *s, uintptr_t top) {
    struct slot *p = malloc(sizeof(*p));
    if (!p) return -ENOMEM;
    *p = (struct slot){.top = top, .used = 1, .next = s->slots}; s->slots = p;
    return 0;
}
void md_stacks_return(struct md_interception_stacks *s, uintptr_t top) {
    for (struct slot *p = s->slots; p; p = p->next)
        if (p->top == top) { p->used = 0; return; }
}
