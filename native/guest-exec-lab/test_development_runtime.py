#!/usr/bin/env python3
"""GCC/G++ and an in-guest debugger in a disposable Debian OCI instance."""
import argparse
from pathlib import Path
from test_oci_services import Suite, write_script


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--debian', type=Path)
    source.add_argument('--instance')
    parser.add_argument('--build', type=Path)
    parser.add_argument('--install', action='store_true', help='Install Debian compiler/debugger packages')
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    try:
        store = suite.prepare('development', args.debian) if args.debian else args.instance
        if args.install:
            suite.command(suite.run(store, '--env', 'DEBIAN_FRONTEND=noninteractive', '--', '/bin/sh', '-ec',
                                    'printf "nameserver 1.1.1.1\\n" >/etc/resolv.conf\n'
                                    'apt-get update\napt-get install -y --no-install-recommends gcc g++ gdb libc6-dev\n'
                                    'dpkg --audit'))
        sources = {
            '/tmp/md-library.c': 'int answer(int a,int b) { return a*b; }\n',
            '/tmp/md-program.cpp': '''#include <cassert>
#include <future>
#include <fstream>
#include <iostream>
extern "C" int answer(int,int);
int main() {
    auto job=std::async(std::launch::async,[]{return answer(6,7);});
    int result=job.get(); assert(result==42);
    { std::ofstream out("/tmp/md-compiled-result"); out<<result; }
    int saved=0; std::ifstream("/tmp/md-compiled-result")>>saved; assert(saved==42);
    std::cout<<"PASS C/C++ shared library, pthread worker and file IO\\n";
}
''',
            '/tmp/md-debug.c': '''__attribute__((noinline)) int answer(int input) {
    int result=input+1;
    return result;
}
int main(void) { return answer(41)==42 ? 0 : 1; }
''',
            '/tmp/md-debug-events.c': '''#include <assert.h>
#include <pthread.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
static volatile sig_atomic_t received;
static void handler(int signo) { received=signo; }
__attribute__((noinline)) void checkpoint(int value) { assert(value==42); }
static void *worker(void *arg) { (void)arg; checkpoint(42); return 0; }
int main(void) {
    signal(SIGUSR1,handler);
    raise(SIGUSR1); assert(received==SIGUSR1);
    pthread_t thread; assert(!pthread_create(&thread,0,worker,0));
    assert(!pthread_join(thread,0));
    pid_t child=fork(); assert(child>=0);
    if (!child) { execl("/tmp/md-debug","md-debug",(char *)0); _exit(99); }
    int status; assert(waitpid(child,&status,0)==child);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    checkpoint(42); return 0;
}
''',
            '/tmp/md-gdb-events.commands': '''set pagination off
set confirm off
set startup-with-shell off
set follow-fork-mode parent
handle SIGUSR1 nostop noprint pass
file /tmp/md-debug-events
set $checkpoints = 0
break checkpoint
commands
silent
python assert int(gdb.parse_and_eval("value")) == 42
set $checkpoints = $checkpoints + 1
continue
end
run
python assert int(gdb.parse_and_eval("$_exitcode")) == 0
python assert int(gdb.parse_and_eval("$checkpoints")) == 2
echo PASS GDB threads, signal delivery, fork detach and child exec\\n
''',
            '/tmp/md-gdb.commands': '''set pagination off
set confirm off
set startup-with-shell off
file /tmp/md-debug
break answer
run
python assert gdb.selected_frame().name() == "answer"
python assert int(gdb.parse_and_eval("input")) == 41
next
print result
backtrace
python assert int(gdb.parse_and_eval("result")) == 42
continue
python assert int(gdb.parse_and_eval("$_exitcode")) == 0
echo PASS GDB breakpoint, step, locals, backtrace and normal exit\\n
'''
        }
        for name, content in sources.items():
            write_script(suite, store, name, content)
        compiler = {'name': 'gcc-g++', 'passed': False}
        suite.report['cases'].append(compiler)
        suite.command(suite.run(store, '--', '/bin/sh', '-ec',
                                'gcc -O0 -g -fPIC -shared /tmp/md-library.c -o /tmp/libmd-answer.so\n'
                                'g++ -O0 -g -pthread /tmp/md-program.cpp -L/tmp -Wl,-rpath,/tmp -lmd-answer -o /tmp/md-program\n'
                                'gcc -O0 -g /tmp/md-debug.c -o /tmp/md-debug\n'
                                'gcc -O0 -g -pthread /tmp/md-debug-events.c -o /tmp/md-debug-events\n'
                                '/tmp/md-program\n/tmp/md-debug\n/tmp/md-debug-events'))
        assert suite.command(suite.run(store, '--', '/bin/cat', '/tmp/md-compiled-result')) == '42'
        compiler['passed'] = True
        debugger = {'name': 'gdb-live-debugging', 'passed': False}
        suite.report['cases'].append(debugger)
        try:
            output = suite.command(suite.run(store, '--', 'gdb', '--batch', '-nx', '-x', '/tmp/md-gdb.commands'))
            assert 'PASS GDB breakpoint, step, locals, backtrace and normal exit' in output
            output = suite.command(suite.run(store, '--', 'gdb', '--batch', '-nx', '-x', '/tmp/md-gdb-events.commands'))
            assert 'PASS GDB threads, signal delivery, fork detach and child exec' in output
            debugger['passed'] = True
        except Exception as error:
            debugger['error'] = repr(error)
        suite.report['passed'] = all(case['passed'] for case in suite.report['cases'])
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        suite.finish()
    assert suite.report['passed'], suite.report['cases']


if __name__ == '__main__':
    main()
