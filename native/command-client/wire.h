#ifndef MAGICDESK_COMMAND_WIRE_H
#define MAGICDESK_COMMAND_WIRE_H
#include <stdint.h>
#include <stddef.h>

#define MD_COMMAND_MAGIC UINT32_C(0x8d444301)
#define MD_COMMAND_REQUEST_LIMIT (1024U * 1024U)
#define MD_COMMAND_RESPONSE_LIMIT (32U * 1024U * 1024U)
enum { MD_COMMAND_LEASE = 1, MD_COMMAND_INVOKE = 2 };
enum { MD_COMMAND_READ = 1, MD_COMMAND_OUT = 2, MD_COMMAND_ERR = 3, MD_COMMAND_EXIT = 4 };
int md_command_connect(const char *endpoint, const char *build, uint32_t operation);
int md_command_lease(void);
int md_command_send(int fd, const void *data, size_t size);
int md_command_receive(int fd, void *data, size_t size);
int md_command_number(int fd, uint32_t value);
int md_command_read_number(int fd, uint32_t *value);
int md_command_text(int fd, const char *text);
int md_command_read_text(int fd, char *text, size_t capacity);
#endif
