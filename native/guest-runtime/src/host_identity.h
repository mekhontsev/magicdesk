#ifndef MD_HOST_IDENTITY_H
#define MD_HOST_IDENTITY_H

#define MD_HOSTNAME_SIZE 65
/* Launch configuration, not a writable kernel UTS namespace. */
static inline int md_hostname_valid(const char *name) {
    unsigned n=0, label=0;
    for (; name[n]; ++n) {
        unsigned char c=name[n];
        if (n>=MD_HOSTNAME_SIZE-1) return 0;
        if (c=='.') { if (!label || name[n-1]=='-') return 0; label=0; }
        else {
            if (!((c>='a'&&c<='z') || (c>='A'&&c<='Z') || (c>='0'&&c<='9') || c=='-')) return 0;
            if (!label && c=='-') return 0;
            if (++label>63) return 0;
        }
    }
    return label && name[n-1]!='-';
}
#endif
