#ifndef MD_IMAGE_PUBLISH_H
#define MD_IMAGE_PUBLISH_H
#include <limits.h>
struct md_image_publish { int parent, stage; char name[NAME_MAX+1], temporary[NAME_MAX+1], path[PATH_MAX]; };
int md_image_publish_begin(const char *, struct md_image_publish *);
int md_image_publish_commit(struct md_image_publish *);
void md_image_publish_close(struct md_image_publish *);
#endif
