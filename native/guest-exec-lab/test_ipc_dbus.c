#define _GNU_SOURCE
#include <gio/gio.h>
#include <gio/gunixfdlist.h>
#include <sys/mman.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#define NAME "io.github.magicdesk.CredentialsFixture"
static GMainLoop *loop;
static void fail(GError *error) { fprintf(stderr,"dbus fixture: %s\n",error?error->message:"failed"); exit(1); }
static void method(GDBusConnection *bus,const gchar *sender,const gchar *path,const gchar *interface,
        const gchar *name,GVariant *arguments,GDBusMethodInvocation *invocation,gpointer context) {
    (void)path; (void)interface; (void)context;
    if (!strcmp(name,"Quit")) {
        g_dbus_method_invocation_return_value(invocation,NULL);
        if (!g_dbus_connection_flush_sync(bus,NULL,NULL)) fail(NULL);
        g_main_loop_quit(loop); return;
    }
    GError *error=NULL;
    GVariant *user=g_dbus_connection_call_sync(bus,"org.freedesktop.DBus","/org/freedesktop/DBus",
        "org.freedesktop.DBus","GetConnectionUnixUser",g_variant_new("(s)",sender),G_VARIANT_TYPE("(u)"),
        G_DBUS_CALL_FLAGS_NONE,10000,NULL,&error);
    if (!user) fail(error);
    guint uid; g_variant_get(user,"(u)",&uid); g_variant_unref(user);
    if (uid!=0 || getuid()!=0) fail(NULL);
    gint index; g_variant_get(arguments,"(h)",&index);
    GUnixFDList *input=g_dbus_message_get_unix_fd_list(g_dbus_method_invocation_get_message(invocation));
    int fd=g_unix_fd_list_get(input,index,&error); if (fd<0) fail(error);
    char bytes[8]; if (pread(fd,bytes,8,0)!=8 || memcmp(bytes,"guest-fd",8)) fail(NULL);
    GUnixFDList *output=g_unix_fd_list_new();
    index=g_unix_fd_list_append(output,fd,&error); close(fd); if (index<0) fail(error);
    g_dbus_method_invocation_return_value_with_unix_fd_list(invocation,g_variant_new("(hu)",index,uid),output);
    g_object_unref(output);
}
int main(int argc,char **argv) {
    GError *error=NULL;
    GDBusConnection *bus=g_bus_get_sync(G_BUS_TYPE_SESSION,NULL,&error); if (!bus) fail(error);
    if (argc==2 && !strcmp(argv[1],"service")) {
        const char *xml="<node><interface name='" NAME "'><method name='Exchange'>"
            "<arg type='h' direction='in'/><arg type='h' direction='out'/><arg type='u' direction='out'/>"
            "</method><method name='Quit'/></interface></node>";
        GDBusNodeInfo *node=g_dbus_node_info_new_for_xml(xml,&error); if (!node) fail(error);
        GDBusInterfaceVTable callbacks={.method_call=method};
        guint registration=g_dbus_connection_register_object(bus,"/fixture",node->interfaces[0],&callbacks,NULL,NULL,&error);
        if (!registration) fail(error);
        guint owner=g_bus_own_name_on_connection(bus,NAME,G_BUS_NAME_OWNER_FLAGS_NONE,NULL,NULL,NULL,NULL);
        loop=g_main_loop_new(NULL,FALSE);
        /* EVENT_WAIT: activation requests and Quit; the guest launch deadline bounds failure. */
        g_main_loop_run(loop);
        g_bus_unown_name(owner); g_dbus_connection_unregister_object(bus,registration);
        g_main_loop_unref(loop); g_dbus_node_info_unref(node);
    } else {
        int fd=memfd_create("dbus-fixture",MFD_CLOEXEC); if (fd<0 || write(fd,"guest-fd",8)!=8) fail(NULL);
        GUnixFDList *input=g_unix_fd_list_new(), *output=NULL;
        int index=g_unix_fd_list_append(input,fd,&error); close(fd); if (index<0) fail(error);
        /* EVENT_WAIT: stock daemon activates the service and delivers its reply. */
        GVariant *reply=g_dbus_connection_call_with_unix_fd_list_sync(bus,NAME,"/fixture",NAME,"Exchange",
            g_variant_new("(h)",index),G_VARIANT_TYPE("(hu)"),G_DBUS_CALL_FLAGS_NONE,10000,input,&output,NULL,&error);
        if (!reply) fail(error);
        guint uid; g_variant_get(reply,"(hu)",&index,&uid); if (uid!=0) fail(NULL);
        fd=g_unix_fd_list_get(output,index,&error); if (fd<0) fail(error);
        char bytes[8]; if (pread(fd,bytes,8,0)!=8 || memcmp(bytes,"guest-fd",8)) fail(NULL);
        close(fd); g_variant_unref(reply); g_object_unref(input); g_object_unref(output);
        reply=g_dbus_connection_call_sync(bus,NAME,"/fixture",NAME,"Quit",NULL,NULL,G_DBUS_CALL_FLAGS_NONE,10000,NULL,&error);
        if (!reply) fail(error);
        g_variant_unref(reply);
        puts("PASS stock D-Bus activation, GDBus identity and bidirectional Unix FD delivery");
    }
    g_object_unref(bus); return 0;
}
