#include "interception_stacks.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    struct md_interception_stacks *parent = md_stacks_new();
    assert(parent && !md_stacks_take(parent));
    assert(!md_stacks_add(parent, 0x10000));
    struct md_interception_stacks *thread = md_stacks_fork(parent, 1);
    assert(thread == parent && !md_stacks_take(thread));
    assert(!md_stacks_add(thread, 0x20000));
    struct md_interception_stacks *child = md_stacks_fork(parent, 0);
    assert(child && child != parent);
    assert(md_stacks_take(child) == 0x10000 && md_stacks_take(child) == 0x20000);
    assert(!md_stacks_take(child) && !md_stacks_take(parent));
    md_stacks_return(thread, 0x20000);
    for (unsigned i = 0; i < 10000; i++) {
        assert(md_stacks_take(parent) == 0x20000 && !md_stacks_take(thread));
        md_stacks_return(parent, 0x20000);
    }
    md_stacks_release(child);
    md_stacks_release(thread);
    md_stacks_return(parent, 0x10000);
    assert(md_stacks_take(parent) == 0x20000 && md_stacks_take(parent) == 0x10000);
    md_stacks_release(parent);
    puts("PASS adapter stacks: shared VM, independent fork, concurrent leases and bounded reuse");
    return 0;
}
