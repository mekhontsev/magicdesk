#define _POSIX_C_SOURCE 200809L
#include <dbus/dbus.h>
#include <stdio.h>
#include <time.h>

static long milliseconds(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec * 1000L + now.tv_nsec / 1000000;
}
int main(void) {
    DBusError error;
    dbus_error_init(&error);
    DBusConnection *bus = dbus_bus_get(DBUS_BUS_SESSION, &error);
    if (!bus) goto failed;
    dbus_bus_add_match(bus, "type='signal',sender='org.xfce.SessionManager',"
        "interface='org.xfce.Session.Manager',member='StateChanged',"
        "path='/org/xfce/SessionManager'", &error);
    if (dbus_error_is_set(&error)) goto failed;
    DBusMessage *request = dbus_message_new_method_call("org.xfce.SessionManager",
        "/org/xfce/SessionManager", "org.xfce.Session.Manager", "GetState");
    if (!request) return 1;
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, request, 5000, &error);
    dbus_message_unref(request);
    if (!reply) goto failed;
    dbus_uint32_t state;
    int valid = dbus_message_get_args(reply, &error, DBUS_TYPE_UINT32, &state, DBUS_TYPE_INVALID);
    dbus_message_unref(reply);
    if (!valid) goto failed;
    long deadline = milliseconds() + 60000;
    while (state != 1) {
        DBusMessage *event;
        while ((event = dbus_connection_pop_message(bus))) {
            if (dbus_message_is_signal(event, "org.xfce.Session.Manager", "StateChanged")) {
                dbus_uint32_t old;
                valid = dbus_message_get_args(event, &error, DBUS_TYPE_UINT32, &old,
                    DBUS_TYPE_UINT32, &state, DBUS_TYPE_INVALID);
            }
            dbus_message_unref(event);
            if (!valid) goto failed;
        }
        if (state == 1) break;
        long remaining = deadline - milliseconds();
        // EVENT_WAIT: Xfce StateChanged(Idle); timeout/disconnection fails readiness.
        if (remaining <= 0 || !dbus_connection_read_write(bus, (int)remaining)) {
            fprintf(stderr, "Xfce did not become idle (state=%u)\n", state);
            return 1;
        }
    }
    dbus_connection_unref(bus);
    puts("PASS Xfce session manager is idle");
    return 0;
failed:
    fprintf(stderr, "Xfce readiness: %s\n", error.message ? error.message : "D-Bus error");
    dbus_error_free(&error);
    if (bus) dbus_connection_unref(bus);
    return 1;
}
