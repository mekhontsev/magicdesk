.macro md_svc_slot reg,attempt=0
    mov \reg,sp
    eor \reg,\reg,\reg,lsr#16
    ubfx \reg,\reg,#8,#6
    .if \attempt
    eor \reg,\reg,#MD_SVC_SLOT_XOR(\attempt)
    .endif
    movk \reg,#0,lsl#16
    movk \reg,#MD_SVC_POOL_HIGH,lsl#32
    movk \reg,#0,lsl#48
    bfi \reg,\reg,#MD_SVC_SLOT_SHIFT,#6
    and \reg,\reg,#0xffffffffffffffc0
.endm
.macro md_svc_gpr_pair paired,single,base,lo,hi,omit=-1
    .if \lo == \omit
    \single x\hi,[\base,#8*\hi]
    .elseif \hi == \omit
    \single x\lo,[\base,#8*\lo]
    .else
    \paired x\lo,x\hi,[\base,#8*\lo]
    .endif
.endm
.macro md_svc_gprs paired,single,base,omit=-1
    .irp pair,"0,1","2,3","4,5","6,7","8,9","10,11","12,13","14,15","16,17","18,19","20,21","22,23","24,25","26,27","28,29"
    md_svc_gpr_pair \paired,\single,\base,\pair,\omit
    .endr
.endm
.macro md_svc_save_gprs base
    md_svc_gprs stp,str,\base
.endm
.macro md_svc_restore_gprs base, omit=-1
    md_svc_gprs ldp,ldr,\base,\omit
.endm
