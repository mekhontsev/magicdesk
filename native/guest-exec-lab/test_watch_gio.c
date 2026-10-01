#include <gio/gio.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "gio watch line=%d %s\n", __LINE__, #x); exit(1); } } while (0)
static GMainLoop *loop;
static unsigned seen;
static void changed(GFileMonitor *monitor, GFile *file, GFile *other,
        GFileMonitorEvent event, gpointer context) {
    (void)monitor; (void)context;
    if (event == G_FILE_MONITOR_EVENT_CHANGES_DONE_HINT) return;
    GFileMonitorEvent expected[] = {G_FILE_MONITOR_EVENT_CREATED,
        G_FILE_MONITOR_EVENT_RENAMED, G_FILE_MONITOR_EVENT_DELETED};
    const char *names[] = {"payload", "payload", "renamed"};
    char *name = g_file_get_basename(file);
    CHECK(seen < G_N_ELEMENTS(expected) && event == expected[seen] && !strcmp(name, names[seen]));
    g_free(name);
    if (event == G_FILE_MONITOR_EVENT_RENAMED) {
        CHECK(other); name = g_file_get_basename(other); CHECK(!strcmp(name, "renamed")); g_free(name);
    }
    if (++seen == G_N_ELEMENTS(expected)) g_main_loop_quit(loop);
}
static gboolean expired(gpointer context) {
    (void)context; fprintf(stderr, "GIO watch deadline expired: events=%u\n", seen); exit(1);
}
int main(int argc, char **argv) {
    CHECK(argc == 2);
    GFile *file = g_file_new_for_path(argv[1]);
    GError *error = NULL;
    GFileMonitor *monitor = g_file_monitor_directory(file, G_FILE_MONITOR_WATCH_MOVES, NULL, &error);
    if (error) fprintf(stderr, "%s\n", error->message);
    CHECK(monitor && !error && !strcmp(G_OBJECT_TYPE_NAME(monitor), "GInotifyFileMonitor"));
    loop = g_main_loop_new(NULL, FALSE);
    g_signal_connect(monitor, "changed", G_CALLBACK(changed), NULL);
    /* EVENT_WAIT: GIO delivers namespace changes from an independent writer;
     * expiry fails, never substitutes a file poll or synthetic event. */
    guint deadline = g_timeout_add_seconds(10, expired, NULL);
    puts("READY"); fflush(stdout);
    g_main_loop_run(loop);
    g_source_remove(deadline);
    CHECK(g_file_monitor_cancel(monitor));
    g_object_unref(monitor); g_object_unref(file); g_main_loop_unref(loop);
    puts("PASS stock GIO inotify backend delivers create/rename/delete across launches");
    return 0;
}
