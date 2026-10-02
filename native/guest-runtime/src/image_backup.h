#ifndef MD_IMAGE_BACKUP_H
#define MD_IMAGE_BACKUP_H
int md_image_backup(const char *store, const char *destination);
int md_image_archive_import(const char *archive, const char *destination, int backup);
#endif
