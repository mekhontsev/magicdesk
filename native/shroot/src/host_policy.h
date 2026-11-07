#ifndef MD_HOST_POLICY_H
#define MD_HOST_POLICY_H

/* Selected once by native launch admission, never by a guest request. */
extern int md_app_uid_active;
int md_app_uid_admit(long uid, long euid, long gid, long egid);

#endif
