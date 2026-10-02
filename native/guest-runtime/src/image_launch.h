#ifndef MD_IMAGE_LAUNCH_H
#define MD_IMAGE_LAUNCH_H
enum md_image_command { MD_IMAGE_RUN, MD_IMAGE_EXEC, MD_IMAGE_LOGIN };
int md_image_launch(enum md_image_command, int argc, char **argv);
int md_image_inspect(const char *);
#endif
