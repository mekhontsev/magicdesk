#pragma once

struct md_inode_store;
struct archive_entry;

int md_image_acl_record(struct md_inode_store *, struct archive_entry *, const char *);
int md_image_acls_finish(struct md_inode_store *);
