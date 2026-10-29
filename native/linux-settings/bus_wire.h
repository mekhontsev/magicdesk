#pragma once
#include <dbus/dbus.h>
#include <stdint.h>
#include <stddef.h>

#define MD_BUS_FRAME_LIMIT (1024U * 1024U)
#define MD_BUS_VALUE_LIMIT 16384U
#define MD_BUS_DEPTH_LIMIT 32U
#define MD_BUS_FD_LIMIT 64U
typedef struct { unsigned char *data; size_t size, at, capacity; unsigned values; int failed; } MdBusWire;
typedef struct { int fd[MD_BUS_FD_LIMIT]; uint64_t serial[MD_BUS_FD_LIMIT], next; } MdBusFds;
void md_bus_wire_clear(MdBusWire *wire);
void md_bus_write_u32(MdBusWire *wire, uint32_t value);
void md_bus_write_text(MdBusWire *wire, const char *text);
uint32_t md_bus_read_u32(MdBusWire *wire);
char *md_bus_read_text(MdBusWire *wire);
int md_bus_values_read(MdBusWire *wire, DBusMessage *message, MdBusFds *fds);
int md_bus_values_write(MdBusWire *wire, DBusMessage *message, MdBusFds *fds);
void md_bus_fds_init(MdBusFds *fds);
void md_bus_fds_clear(MdBusFds *fds);
int md_bus_fd_release(MdBusFds *fds, const char *handle);
