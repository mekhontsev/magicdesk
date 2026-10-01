#!/usr/bin/env python3
"""Stock qemu-user: x86-64 translation, file IO and fork/wait inside ARM64 guest."""
import argparse
from pathlib import Path
from test_oci_services import Suite, write_script


SOURCE = r'''.global _start
.text
_start:
    mov $257, %rax
    mov $-100, %rdi
    lea path(%rip), %rsi
    mov $578, %rdx
    mov $384, %r10
    syscall
    test %rax, %rax
    js fail
    mov %rax, %r12
    mov $1, %rax
    mov %r12, %rdi
    lea value(%rip), %rsi
    mov $3, %rdx
    syscall
    cmp $3, %rax
    jne fail
    mov $8, %rax
    mov %r12, %rdi
    xor %rsi, %rsi
    xor %rdx, %rdx
    syscall
    test %rax, %rax
    jne fail
    xor %rax, %rax
    lea buffer(%rip), %rsi
    mov $3, %rdx
    syscall
    cmp $3, %rax
    jne fail
    cmpw $0x3234, buffer(%rip)
    jne fail
    mov $3, %rax
    mov %r12, %rdi
    syscall
    mov $57, %rax
    syscall
    test %rax, %rax
    js fail
    jz child
    mov %rax, %rdi
    mov $61, %rax
    lea status(%rip), %rsi
    xor %rdx, %rdx
    xor %r10, %r10
    syscall
    cmp %rdi, %rax
    jne fail
    cmpl $0, status(%rip)
    jne fail
    mov $1, %rax
    mov $1, %rdi
    lea message(%rip), %rsi
    mov $message_size, %rdx
    syscall
child:
    xor %rdi, %rdi
    jmp exit
fail:
    mov $90, %rdi
exit:
    mov $60, %rax
    syscall
.section .rodata
path: .asciz "/tmp/md-qemu-value"
value: .ascii "42\n"
message: .ascii "PASS QEMU x86-64 file IO and fork/wait\n"
.set message_size, .-message
.bss
.align 8
buffer: .skip 8
status: .skip 8
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('instance')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    try:
        write_script(suite, args.instance, '/tmp/md-qemu.S', SOURCE)
        result = suite.command(suite.run(args.instance, '--', '/bin/sh', '-ec',
            'x86_64-linux-gnu-as /tmp/md-qemu.S -o /tmp/md-qemu.o\n'
            'x86_64-linux-gnu-ld /tmp/md-qemu.o -o /tmp/md-qemu\n'
            'qemu-x86_64 /tmp/md-qemu\n'))
        assert 'PASS QEMU x86-64 file IO and fork/wait' in result
        assert suite.command(suite.run(args.instance, '--', '/bin/cat', '/tmp/md-qemu-value')) == '42\n'
        suite.report['passed'] = True
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
