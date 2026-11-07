#ifndef MD_TEST_KERNEL_PROFILE_H
#define MD_TEST_KERNEL_PROFILE_H

/* Test-only host capability ceiling, not a guest ABI or kernel emulator. */
enum { MD_TEST_CAPTURE_FD=900 };
int md_test_kernel_profile_prepare(int argc, char **argv);
int md_test_kernel_profile_install(int profile);

#endif
