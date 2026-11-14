#include "pid_namespace.h"
#include "guest_identity.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <linux/reboot.h>
#include <signal.h>

static void native_index(void) {
    struct md_pid_namespace *outer=md_pid_namespace_new(NULL,NULL);
    struct md_pid_namespace *inner=md_pid_namespace_new(outer,NULL);
    assert(outer && inner);
    struct md_pid_identity *ids[260];
    for (unsigned i=0;i<260;++i) {
        assert(!md_pid_identity_prepare(inner,&ids[i]));
        assert(!md_pid_namespace_find(inner,40000+(pid_t)i));
        assert(!md_pid_identity_publish(ids[i],40000+(pid_t)i,40000+(pid_t)i));
    }
    for (unsigned i=1;i<260;++i) {
        for (unsigned level=0;level<2;++level) {
            struct md_pid_identity *p=md_pid_namespace_find(level ? outer : inner,40000+(pid_t)i);
            assert(p==ids[i]); md_pid_identity_release(p);
        }
        assert(!md_pid_identity_exit(ids[i]));
        struct md_pid_identity *zombie=md_pid_namespace_find(inner,40000+(pid_t)i);
        assert(zombie==ids[i]); md_pid_identity_release(zombie);
    }
    /* Native PID reuse must not let an old retained identity remove its
     * replacement from either namespace's reverse index. */
    struct md_pid_identity *replacement;
    assert(!md_pid_identity_prepare(inner,&replacement));
    assert(!md_pid_identity_publish(replacement,40001,40001));
    for (unsigned i=1;i<260;++i) {
        md_pid_identity_reap(ids[i]); md_pid_identity_reap(ids[i]);
        md_pid_identity_release(ids[i]);
        struct md_pid_identity *p=md_pid_namespace_find(outer,40000+(pid_t)i);
        assert(i==1 ? p==replacement : !p);
        md_pid_identity_release(p);
    }
    md_pid_identity_release(replacement);
    assert(!md_pid_namespace_find(inner,40001));
    assert(md_pid_identity_exit(ids[0])); md_pid_identity_release(ids[0]);
    md_pid_namespace_release(inner); md_pid_namespace_release(outer);
    puts("PASS PID reverse index: nested visibility, collisions, zombies, native reuse and repeated retirement");
}

static void reboot_admission(void) {
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_pid_namespace *space=md_pid_namespace_new(NULL,NULL); assert(space);
    struct md_pid_identity *init=NULL,*child=NULL;
    assert(!md_pid_identity_prepare(space,&init) && !md_pid_identity_publish(init,30001,30001));
    assert(!md_pid_identity_prepare(space,&child) && !md_pid_identity_publish(child,30002,30002));
    assert(md_pid_namespace_reboot(NULL,&root,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,LINUX_REBOOT_CMD_POWER_OFF)==-EPERM);
    assert(md_pid_namespace_reboot(space,&user,0,0,0)==-EPERM);
    assert(md_pid_namespace_reboot(space,&root,0,LINUX_REBOOT_MAGIC2,LINUX_REBOOT_CMD_POWER_OFF)==-EINVAL);
    assert(md_pid_namespace_reboot(space,&root,LINUX_REBOOT_MAGIC1,0,LINUX_REBOOT_CMD_POWER_OFF)==-EINVAL);
    assert(md_pid_namespace_reboot(space,&root,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,LINUX_REBOOT_CMD_CAD_OFF)==-EINVAL);
    assert(!md_pid_identity_reboot_signal(init));
    unsigned magics[]={LINUX_REBOOT_MAGIC2,LINUX_REBOOT_MAGIC2A,LINUX_REBOOT_MAGIC2B,LINUX_REBOOT_MAGIC2C};
    unsigned commands[]={LINUX_REBOOT_CMD_POWER_OFF,LINUX_REBOOT_CMD_HALT,LINUX_REBOOT_CMD_RESTART,LINUX_REBOOT_CMD_RESTART2};
    for (unsigned i=0;i<4;++i) {
        int signal=i<2 ? SIGINT : SIGHUP;
        assert(md_pid_namespace_reboot(space,&root,LINUX_REBOOT_MAGIC1,magics[i],commands[i])==signal);
        md_pid_namespace_reboot_started(space,signal);
        assert(md_pid_identity_reboot_signal(init)==signal && !md_pid_identity_reboot_signal(child));
    }
    assert(md_pid_identity_exit(init));
    assert(md_pid_namespace_reboot(space,&root,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,LINUX_REBOOT_CMD_POWER_OFF)==-ESRCH);
    assert(md_pid_identity_reboot_signal(init)==SIGHUP);
    md_pid_identity_release(child); md_pid_identity_release(init); md_pid_namespace_release(space);
    md_identity_release(&user); md_identity_release(&root);
    puts("PASS namespace reboot admission: no host target, credentials, magic, commands and retained init status");
}

int main(void) {
    native_index();
    reboot_admission();
    struct md_pid_identity *a=NULL,*b=NULL,*c=NULL;
    assert(!md_pid_identity_prepare(NULL,&a) && !a);
    struct md_pid_namespace *outer=md_pid_namespace_new(NULL,NULL); assert(outer);
    assert(md_pid_namespace_depth(outer)==1 && md_pid_namespace_contains(NULL,outer));
    assert(!md_pid_identity_prepare(outer,&a) && md_pid_identity_number(a,outer)==1);
    assert(!md_pid_namespace_lookup(outer,1));
    md_pid_identity_release(a);
    assert(!md_pid_identity_prepare(outer,&a) && md_pid_identity_number(a,outer)==1);
    assert(!md_pid_identity_publish(a,20001,20001) && md_pid_identity_publish(a,20002,20002)==-EINVAL);
    assert(md_pid_identity_number(a,NULL)==20001 && md_pid_identity_live(a));
    struct md_identity privileged=md_identity_new(0,0),unprivileged=md_identity_new(1000,1000);
    pid_t selected=42;
    assert(md_pid_identity_prepare_selected(outer,&unprivileged,&selected,1,&b)==-EPERM && !b);
    assert(!md_pid_identity_prepare_selected(outer,&privileged,&selected,1,&b));
    assert(md_pid_identity_number(b,outer)==selected);
    assert(md_pid_identity_prepare_selected(outer,&privileged,&selected,1,&c)==-EEXIST && !c);
    assert(!md_pid_identity_publish(b,20100,20100) && !md_pid_identity_exit(b));
    assert(md_pid_identity_prepare_selected(outer,&privileged,&selected,1,&c)==-EEXIST && !c);
    md_pid_identity_reap(b);
    assert(!md_pid_identity_prepare_selected(outer,&privileged,&selected,1,&c));
    md_pid_identity_release(b);
    assert(!md_pid_identity_publish(c,20101,20101));
    struct md_pid_identity *reused=md_pid_namespace_lookup(outer,selected); assert(reused==c);
    md_pid_identity_release(reused); md_pid_identity_release(c);
    md_identity_release(&privileged); md_identity_release(&unprivileged);
    struct md_pid_namespace *inner=md_pid_namespace_new(outer,NULL); assert(inner);
    assert(!md_pid_identity_prepare(inner,&b) && !md_pid_identity_publish(b,20002,20002));
    assert(md_pid_identity_number(b,inner)==1 && md_pid_identity_number(b,outer)==2);
    assert(!md_pid_identity_number(a,inner) && md_pid_namespace_contains(outer,inner));
    assert(!md_pid_namespace_contains(inner,outer));
    assert(!md_pid_identity_prepare(inner,&c) && !md_pid_identity_publish(c,20003,20003));
    assert(md_pid_identity_number(c,inner)==2 && md_pid_identity_number(c,outer)==3);
    struct md_pid_identity *found=md_pid_namespace_lookup(outer,3); assert(found==c); md_pid_identity_release(found);
    found=md_pid_namespace_find(inner,20003); assert(found==c); md_pid_identity_release(found);
    assert(!md_pid_namespace_find(inner,20001));
    assert(md_pid_identity_exit(b)==1 && md_pid_namespace_dead(inner));
    assert(!md_pid_identity_exit(b) && !md_pid_identity_live(b));
    struct md_pid_identity *rejected=NULL;
    assert(md_pid_identity_prepare(inner,&rejected)==-ENOMEM && !rejected);
    assert(!md_pid_namespace_dead(outer));
    found=md_pid_namespace_lookup(inner,1); assert(found==b && !md_pid_identity_live(found));
    md_pid_identity_release(found);
    assert(!md_pid_identity_exit(c));
    md_pid_identity_reap(c);
    assert(!md_pid_namespace_lookup(outer,3) && !md_pid_namespace_find(outer,20003));
    assert(md_pid_identity_number(c,outer)==3);
    md_pid_identity_release(c);
    md_pid_identity_release(b); md_pid_namespace_release(inner);
    assert(!md_pid_namespace_lookup(outer,2));
    assert(md_pid_identity_exit(a)); md_pid_identity_release(a); md_pid_namespace_release(outer);
    outer=md_pid_namespace_new(NULL,NULL); assert(outer);
    assert(!md_pid_identity_prepare(outer,&a) && !md_pid_identity_publish(a,20001,20001));
    assert(!md_pid_identity_prepare(outer,&b) && !md_pid_identity_publish(b,20002,20001));
    assert(!md_pid_identity_exit(a) && !md_pid_namespace_dead(outer));
    md_pid_identity_exec(b,a);
    assert(md_pid_identity_live(a) && !md_pid_identity_live(b) && !md_pid_namespace_dead(outer));
    md_pid_identity_release(b);
    assert(!md_pid_identity_prepare(outer,&c) && !md_pid_identity_publish(c,20003,20001));
    assert(!md_pid_identity_exit(a) && !md_pid_namespace_dead(outer));
    assert(md_pid_identity_exit(c) && md_pid_namespace_dead(outer));
    md_pid_identity_release(a); md_pid_identity_release(c); md_pid_namespace_release(outer);
    outer=md_pid_namespace_new(NULL,NULL); assert(outer);
    for (unsigned i=1;i<32;++i) {
        inner=md_pid_namespace_new(outer,NULL); assert(inner);
        md_pid_namespace_release(outer); outer=inner;
    }
    assert(!md_pid_namespace_new(outer,NULL) && errno==ENOSPC);
    md_pid_namespace_release(outer);
    puts("PASS PID namespace model: nested identities, unpublished rollback, ancestor visibility, retained exit and closed init");
    return 0;
}
