#include "bus_wire.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void put(MdBusWire *w, const void *p, size_t n) {
    if (w->failed || n > MD_BUS_FRAME_LIMIT - w->size) { w->failed = 1; return; }
    size_t needed = w->size + n;
    if (needed > w->capacity) {
        size_t capacity = w->capacity ? w->capacity : 4096;
        while (capacity < needed) capacity *= 2;
        if (capacity > MD_BUS_FRAME_LIMIT) capacity = MD_BUS_FRAME_LIMIT;
        unsigned char *data = realloc(w->data, capacity);
        if (!data) { w->failed = 1; return; }
        w->data = data; w->capacity = capacity;
    }
    memcpy(w->data + w->size, p, n); w->size += n;
}
void md_bus_wire_clear(MdBusWire *w) { free(w->data); memset(w, 0, sizeof(*w)); }
void md_bus_write_u32(MdBusWire *w, uint32_t v) {
    unsigned char p[] = {v >> 24, v >> 16, v >> 8, v}; put(w, p, sizeof(p));
}
void md_bus_write_text(MdBusWire *w, const char *text) {
    if (!text) text = "";
    size_t n = strlen(text);
    if (n > MD_BUS_FRAME_LIMIT) { w->failed = 1; return; }
    md_bus_write_u32(w, (uint32_t)n); put(w, text, n);
}
uint32_t md_bus_read_u32(MdBusWire *w) {
    if (w->failed || w->at > w->size || w->size - w->at < 4) { w->failed = 1; return 0; }
    const unsigned char *p = w->data + w->at; w->at += 4;
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}
char *md_bus_read_text(MdBusWire *w) {
    uint32_t n = md_bus_read_u32(w);
    if (w->failed || w->at > w->size || n > w->size - w->at) { w->failed = 1; return NULL; }
    char *s = malloc((size_t)n + 1);
    if (!s) { w->failed = 1; return NULL; }
    memcpy(s, w->data + w->at, n); s[n] = 0; w->at += n;
    if (memchr(s, 0, n) || !dbus_validate_utf8(s, NULL)) { free(s); w->failed = 1; return NULL; }
    return s;
}
void md_bus_fds_init(MdBusFds *f) {
    memset(f, 0, sizeof(*f)); for (unsigned i = 0; i < MD_BUS_FD_LIMIT; ++i) f->fd[i] = -1;
}
void md_bus_fds_clear(MdBusFds *f) {
    for (unsigned i = 0; i < MD_BUS_FD_LIMIT; ++i) if (f->fd[i] >= 0) { close(f->fd[i]); f->fd[i] = -1; }
}
static int fd_slot(MdBusFds *f, const char *s) {
    if (strncmp(s, "fd:", 3)) return -1;
    char *end; errno = 0; uint64_t id = strtoull(s + 3, &end, 10);
    if (errno || !id || *end) return -1;
    for (unsigned i = 0; i < MD_BUS_FD_LIMIT; ++i) if (f->fd[i] >= 0 && f->serial[i] == id) return (int)i;
    return -1;
}
int md_bus_fd_release(MdBusFds *f, const char *s) {
    int slot = fd_slot(f, s); if (slot < 0) return -1;
    close(f->fd[slot]); f->fd[slot] = -1; return 0;
}
static int append_scalar(DBusMessageIter *it, int type, const char *s, MdBusFds *fds) {
    union { unsigned char y; dbus_bool_t b; dbus_int16_t n; dbus_uint16_t q; dbus_int32_t i;
        dbus_uint32_t u; dbus_int64_t x; dbus_uint64_t t; double d; int fd; } v = {0};
    if (type == 's' || type == 'o' || type == 'g') {
        if ((type == 'o' && !dbus_validate_path(s, NULL)) || (type == 'g' && !dbus_signature_validate(s, NULL))) return -1;
        return dbus_message_iter_append_basic(it, type, &s) ? 0 : -1;
    }
    if (type == 'h') { int slot = fd_slot(fds, s); if (slot < 0) return -1; v.fd = fds->fd[slot]; }
    else if (type == 'b') { if (!strcmp(s, "true")) v.b = 1; else if (strcmp(s, "false")) return -1; }
    else {
        char *end; errno = 0;
        if (!*s || *s == ' ' || *s == '\t' || *s == '+') return -1;
        if (type == 'd') { v.d = strtod(s, &end); if (!isfinite(v.d)) return -1; }
        else if (type == 'n' || type == 'i' || type == 'x') {
            long long value = strtoll(s, &end, 10);
            if (type == 'n') { if (value < INT16_MIN || value > INT16_MAX) return -1; v.n = (dbus_int16_t)value; }
            else if (type == 'i') { if (value < INT32_MIN || value > INT32_MAX) return -1; v.i = (dbus_int32_t)value; }
            else v.x = value;
        } else {
            if (*s == '-') return -1;
            unsigned long long value = strtoull(s, &end, 10);
            switch (type) {
                case 'y': if (value > UINT8_MAX) return -1; v.y = (unsigned char)value; break;
                case 'q': if (value > UINT16_MAX) return -1; v.q = (dbus_uint16_t)value; break;
                case 'u': if (value > UINT32_MAX) return -1; v.u = (dbus_uint32_t)value; break;
                case 't': v.t = value; break;
                default: return -1;
            }
        }
        if (errno || *end) return -1;
    }
    return dbus_message_iter_append_basic(it, type, &v) ? 0 : -1;
}
static int read_value(MdBusWire *w, DBusMessageIter *out, MdBusFds *fds, const char *expected, unsigned depth) {
    if (depth > MD_BUS_DEPTH_LIMIT || ++w->values > MD_BUS_VALUE_LIMIT) return -1;
    char *sig = md_bus_read_text(w); int result = -1;
    if (!sig) return -1;
    // A dict entry is only valid inside its array, whose signature was already validated.
    int dict = expected && *expected == '{';
    if ((expected && strcmp(expected, sig)) || (!dict && !dbus_signature_validate_single(sig, NULL))) goto done;
    int type = dict ? DBUS_TYPE_DICT_ENTRY : *sig == '(' ? DBUS_TYPE_STRUCT : *sig;
    if (dbus_type_is_basic(type)) {
        char *value = md_bus_read_text(w);
        if (value) result = append_scalar(out, type, value, fds);
        free(value); goto done;
    }
    if (type != DBUS_TYPE_ARRAY && type != DBUS_TYPE_VARIANT && type != DBUS_TYPE_STRUCT && type != DBUS_TYPE_DICT_ENTRY) goto done;
    char *variant = type == DBUS_TYPE_VARIANT ? md_bus_read_text(w) : NULL;
    if (type == DBUS_TYPE_VARIANT && (!variant || !dbus_signature_validate_single(variant, NULL))) { free(variant); goto done; }
    uint32_t count = md_bus_read_u32(w);
    if (w->failed || count > MD_BUS_VALUE_LIMIT || (type == DBUS_TYPE_VARIANT && count != 1)) { free(variant); goto done; }
    DBusMessageIter sub;
    if (!dbus_message_iter_open_container(out, type, type == DBUS_TYPE_ARRAY ? sig + 1 : variant, &sub)) { free(variant); goto done; }
    DBusSignatureIter fields;
    if (type == DBUS_TYPE_STRUCT || type == DBUS_TYPE_DICT_ENTRY) {
        DBusSignatureIter container; dbus_signature_iter_init(&container, sig);
        dbus_signature_iter_recurse(&container, &fields);
    }
    result = 0;
    for (uint32_t i = 0; i < count; ++i) {
        char *field = NULL;
        const char *child = type == DBUS_TYPE_ARRAY ? sig + 1 : variant;
        if (type == DBUS_TYPE_STRUCT || type == DBUS_TYPE_DICT_ENTRY) {
            int t = dbus_signature_iter_get_current_type(&fields);
            if (t == DBUS_TYPE_INVALID || t == ')' || t == '}') { result = -1; break; }
            field = dbus_signature_iter_get_signature(&fields); child = field;
        }
        if (read_value(w, &sub, fds, child, depth + 1)) result = -1;
        if (field) { dbus_free(field); dbus_signature_iter_next(&fields); }
        if (result) break;
    }
    if (!result && (type == DBUS_TYPE_STRUCT || type == DBUS_TYPE_DICT_ENTRY)) {
        int t = dbus_signature_iter_get_current_type(&fields);
        if (t != DBUS_TYPE_INVALID && t != ')' && t != '}') result = -1;
    }
    if (result) dbus_message_iter_abandon_container(out, &sub);
    else if (!dbus_message_iter_close_container(out, &sub)) result = -1;
    free(variant);
done:
    free(sig); return w->failed ? -1 : result;
}
int md_bus_values_read(MdBusWire *w, DBusMessage *message, MdBusFds *fds) {
    DBusMessageIter out; dbus_message_iter_init_append(message, &out);
    uint32_t count = md_bus_read_u32(w);
    if (count > MD_BUS_VALUE_LIMIT) return -1;
    for (uint32_t i = 0; i < count; ++i) if (read_value(w, &out, fds, NULL, 0)) return -1;
    return w->failed ? -1 : 0;
}
static int write_value(MdBusWire *w, DBusMessageIter *it, MdBusFds *fds, unsigned depth) {
    if (depth > MD_BUS_DEPTH_LIMIT || ++w->values > MD_BUS_VALUE_LIMIT) return -1;
    int type = dbus_message_iter_get_arg_type(it);
    char *sig = dbus_message_iter_get_signature(it); if (!sig) return -1;
    md_bus_write_text(w, sig); dbus_free(sig);
    if (dbus_type_is_container(type)) {
        DBusMessageIter sub, scan; dbus_message_iter_recurse(it, &sub); scan = sub;
        if (type == DBUS_TYPE_VARIANT) {
            char *contained = dbus_message_iter_get_signature(&sub);
            md_bus_write_text(w, contained); dbus_free(contained);
        }
        unsigned count = 0;
        while (dbus_message_iter_get_arg_type(&scan) != DBUS_TYPE_INVALID) { ++count; dbus_message_iter_next(&scan); }
        md_bus_write_u32(w, count);
        for (unsigned i = 0; i < count; ++i) {
            if (write_value(w, &sub, fds, depth + 1)) return -1;
            dbus_message_iter_next(&sub);
        }
    } else {
        union { unsigned char y; dbus_bool_t b; dbus_int16_t n; dbus_uint16_t q; dbus_int32_t i;
            dbus_uint32_t u; dbus_int64_t x; dbus_uint64_t t; double d; int fd; const char *s; } v = {0};
        dbus_message_iter_get_basic(it, &v);
        char buffer[128]; const char *text = buffer;
        switch (type) {
            case 's': case 'o': case 'g': text = v.s; break;
            case 'b': text = v.b ? "true" : "false"; break;
            case 'y': snprintf(buffer, sizeof(buffer), "%u", v.y); break;
            case 'n': snprintf(buffer, sizeof(buffer), "%d", v.n); break;
            case 'q': snprintf(buffer, sizeof(buffer), "%u", v.q); break;
            case 'i': snprintf(buffer, sizeof(buffer), "%" PRId32, v.i); break;
            case 'u': snprintf(buffer, sizeof(buffer), "%" PRIu32, v.u); break;
            case 'x': snprintf(buffer, sizeof(buffer), "%" PRId64, (int64_t)v.x); break;
            case 't': snprintf(buffer, sizeof(buffer), "%" PRIu64, (uint64_t)v.t); break;
            case 'd': if (!isfinite(v.d)) return -1; snprintf(buffer, sizeof(buffer), "%.17g", v.d); break;
            case 'h': {
                unsigned slot = 0; while (slot < MD_BUS_FD_LIMIT && fds->fd[slot] >= 0) ++slot;
                if (v.fd < 0) return -1;
                if (slot == MD_BUS_FD_LIMIT || fds->next == UINT64_MAX) { close(v.fd); return -1; }
                fds->fd[slot] = v.fd; fds->serial[slot] = ++fds->next;
                snprintf(buffer, sizeof(buffer), "fd:%" PRIu64, fds->next); break;
            }
            default: return -1;
        }
        md_bus_write_text(w, text);
    }
    return w->failed ? -1 : 0;
}
int md_bus_values_write(MdBusWire *w, DBusMessage *message, MdBusFds *fds) {
    DBusMessageIter it, scan; dbus_message_iter_init(message, &it); scan = it;
    unsigned count = 0;
    while (dbus_message_iter_get_arg_type(&scan) != DBUS_TYPE_INVALID) { ++count; dbus_message_iter_next(&scan); }
    md_bus_write_u32(w, count);
    for (unsigned i = 0; i < count; ++i) {
        if (write_value(w, &it, fds, 0)) return -1;
        dbus_message_iter_next(&it);
    }
    return w->failed ? -1 : 0;
}
