#include "terminal_calls.h"
#include "guest_execution.h"
#include "raw.h"
#include <errno.h>

_Static_assert(sizeof(struct termios)==36 && sizeof(struct termios2)==44,"ARM64 terminal ABI");
static const unsigned rates[]={0,50,75,110,134,150,200,300,600,1200,1800,2400,
    4800,9600,19200,38400,57600,115200,230400,460800,500000,576000,921600,
    1000000,1152000,1500000,2000000,2500000,3000000,3500000,4000000};
static int speed(unsigned flags,unsigned *out) {
    unsigned code=flags&CBAUD;
    if (code==BOTHER) return -EOPNOTSUPP;
    unsigned index=code&CBAUDEX ? (code&~CBAUDEX)+15 : code;
    if (index>=sizeof(rates)/sizeof(*rates)) return -EINVAL;
    *out=rates[index]; return 0;
}
static int encode(unsigned *flags,unsigned shift,unsigned rate) {
    if (((*flags>>shift)&CBAUD)!=BOTHER) return 0;
    for (unsigned i=0;i<sizeof(rates)/sizeof(*rates);++i) if (rates[i]==rate) {
        unsigned code=i<16 ? i : CBAUDEX|(i-15);
        *flags=(*flags&~(CBAUD<<shift))|(code<<shift); return 0;
    }
    return -EOPNOTSUPP;
}
long md_terminal_attributes(const unsigned long *a) {
    unsigned command=(unsigned)a[1];
    long result=command==TCGETS2 ? RAW3(ioctl,a[0],command,a[2])
        : md_guest_native_call(SYS_ioctl,(unsigned long[6]){a[0],command,a[2]});
    if (result!=-EACCES && result!=-ENOTTY) return result;
    /* Some Android policies allow the original terminal ABI but not termios2.
     * Keep the same task and FD: this does not acquire a privileged executor. */
    struct termios2 value={0};
    if (command==TCGETS2) {
        result=RAW3(ioctl,a[0],TCGETS,&value);
        if (!result) result=speed(value.c_cflag,&value.c_ospeed);
        if (!result) result=speed((value.c_cflag&CIBAUD) ? value.c_cflag>>IBSHIFT
            : value.c_cflag,&value.c_ispeed);
        return result ? result : md_write_memory((void *)a[2],&value,sizeof(value));
    }
    result=md_read_memory(&value,(void *)a[2],sizeof(value));
    if (!result) result=encode(&value.c_cflag,0,value.c_ospeed);
    if (!result) result=encode(&value.c_cflag,IBSHIFT,value.c_ispeed);
    unsigned legacy=command==TCSETS2 ? TCSETS : command==TCSETSW2 ? TCSETSW : TCSETSF;
    return result ? result : md_guest_native_call(SYS_ioctl,(unsigned long[6]){a[0],legacy,(uintptr_t)&value});
}
