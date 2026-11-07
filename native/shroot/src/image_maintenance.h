#ifndef MD_IMAGE_MAINTENANCE_H
#define MD_IMAGE_MAINTENANCE_H
int md_image_remove(const char *directory, int layer);
int md_image_sources(const char *directory);
int md_image_rebase_sources(const char *directory, const char *destination);
int md_image_relocate_library(const char *source, const char *destination);
#endif
