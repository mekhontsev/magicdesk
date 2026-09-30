ENTRY(_start)
PHDRS { text PT_LOAD FLAGS(5); }
SECTIONS {
    . = 0x40000000;
    .text : { *(.text*) } :text
    /DISCARD/ : { *(.comment) *(.note*) }
}
