// SPDX-License-Identifier: GPL-2.0-or-later
// The offline linker resolves this address against the inspected stock ELF.
.text
.global md_allocate_entry
.type md_allocate_entry, %function
md_allocate_entry:
    adrp x16, md_allocate_slot
    ldr x16, [x16, :lo12:md_allocate_slot]
    br x16
.size md_allocate_entry, .-md_allocate_entry
.section .note.GNU-stack,"",%progbits
