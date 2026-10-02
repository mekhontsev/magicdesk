#!/usr/bin/env python3
"""Gentoo stage3 userspace, compiler and explicit Portage package workflow."""
import argparse
from pathlib import Path
from test_oci_services import Suite, write_script


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--build', type=Path)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--layout', type=Path)
    source.add_argument('--instance')
    parser.add_argument('--packages', action='store_true', help='Sync the signed Gentoo tree and build GNU hello')
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    try:
        store = suite.prepare('gentoo', args.layout) if args.layout else args.instance
        suite.report['instance'] = store
        suite.command(suite.run(store, '--', '/bin/sh', '-ec',
            'cat /etc/os-release\ntest "$(id -u)" = 0\n'
            'test -c /dev/null\nprintf discarded >/dev/null\ntest -z "$(cat /dev/null)"\n'
            'gcc --version\npython3 --version\nemerge --info\n'))
        write_script(suite, store, '/tmp/md-gentoo.c', '''#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>
static void *worker(void *unused) { (void)unused; return (void *)42; }
int main(void) {
    pthread_t thread; void *value;
    assert(!pthread_create(&thread,0,worker,0) && !pthread_join(thread,&value) && value==(void *)42);
    pid_t child=fork(); assert(child>=0);
    if (!child) { execl("/bin/true","true",(char *)0); _exit(99); }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    FILE *out=fopen("/tmp/md-gentoo-value","w"); assert(out && fprintf(out,"42") == 2 && !fclose(out));
    puts("PASS Gentoo GCC, pthread, fork/exec and file IO");
}
''')
        suite.command(suite.run(store, '--', '/bin/sh', '-ec',
            'gcc -O2 -pthread /tmp/md-gentoo.c -o /tmp/md-gentoo\n/tmp/md-gentoo\n'
            'test "$(cat /tmp/md-gentoo-value)" = 42\n'))
        suite.report['cases'].append({'name': 'gentoo-userspace-compiler', 'passed': True})
        if args.packages:
            suite.command(suite.run(store, '--', '/bin/sh', '-ec',
                'printf "nameserver 1.1.1.1\\n" >/etc/resolv.conf\nemerge-webrsync\n'), timeout=900)
            # hello is keyworded ~arm64; keep Portage sandbox FEATURES unchanged.
            suite.command(suite.run(store, '--env', 'ACCEPT_KEYWORDS=~arm64', '--env', 'MAKEOPTS=-j2',
                '--', '/bin/sh', '-ec', 'emerge --oneshot --usepkg=n app-misc/hello\n'
                'test "$(hello --greeting=MD-GENTOO)" = MD-GENTOO\n'
                'emerge --unmerge app-misc/hello\n! command -v hello\n'), timeout=900)
            suite.report['cases'].append({'name': 'portage-source-build-install-remove', 'passed': True})
        suite.report['passed'] = True
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
