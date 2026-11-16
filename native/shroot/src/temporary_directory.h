#ifndef MD_TEMPORARY_DIRECTORY_H
#define MD_TEMPORARY_DIRECTORY_H
#define MD_TEMPORARY_DIRECTORY_MARKER ".shroot-transient"
/* Creates a private child in an explicitly selected backing directory.
 * Returns an independently shared-locked root description. Its lifetime may
 * cross SCM_RIGHTS without any process-local reference count in the receiver. */
int md_temporary_directory_new(int parent);
int md_temporary_directory_check(int root);
/* Consumes one locked description. Attempts retirement only after releasing
 * it; an independently opened collector must exclude every other kernel owner.
 * Lost processes/queued-right discard are recovered at the next admission.
 * Retirement uses ownership, reserved names and exclusive leases; damaged or
 * missing nondurable markers do not prevent cleanup after a host reboot.
 * Live import still requires md_temporary_directory_check(). */
void md_temporary_directory_release(int root);
int md_temporary_directory_collect(int parent);
#endif
