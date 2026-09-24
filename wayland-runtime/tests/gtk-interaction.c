#include <gtk/gtk.h>
#include <stdio.h>

static GtkTargetEntry file_types[] = {{"text/uri-list", 0, 0}};
static void file_data(GtkWidget *widget, GdkDragContext *context, GtkSelectionData *selection,
        guint info, guint time, gpointer path) {
    (void)widget; (void)context; (void)info; (void)time;
    gchar *uri = g_filename_to_uri(path, NULL, NULL);
    gchar *uris[] = {uri, NULL};
    gtk_selection_data_set_uris(selection, uris);
    g_free(uri);
}
static void files_received(GtkWidget *widget, GdkDragContext *context, gint x, gint y,
        GtkSelectionData *selection, guint info, guint time, gpointer data) {
    (void)widget; (void)x; (void)y; (void)info; (void)data;
    gchar **uris = gtk_selection_data_get_uris(selection);
    gboolean ok = uris != NULL;
    for (int i = 0; uris && uris[i]; ++i) {
        gchar *path = g_filename_from_uri(uris[i], NULL, NULL), *contents = NULL;
        gsize size = 0;
        gboolean read = path && g_file_get_contents(path, &contents, &size, NULL);
        printf("FILE:%s:%zu:%s\n", path ? path : "invalid", (size_t)size, read ? contents : "UNREADABLE");
        ok = ok && read;
        g_free(contents); g_free(path);
    }
    fflush(stdout); g_strfreev(uris);
    gtk_drag_finish(context, ok, FALSE, time);
}

static void changed(GtkEditable *entry, gpointer data) {
    (void)data;
    printf("TEXT:%s\n", gtk_entry_get_text(GTK_ENTRY(entry)));
    fflush(stdout);
}
static void fullscreen(GtkButton *button, gpointer data) {
    (void)button;
    gtk_window_fullscreen(GTK_WINDOW(data));
}
static gboolean key(GtkWidget *widget, GdkEventKey *event, gpointer data) {
    (void)data;
    if (event->keyval != GDK_KEY_Escape) return FALSE;
    gtk_window_unfullscreen(GTK_WINDOW(widget));
    return TRUE;
}
static gboolean closing(GtkWidget *window, GdkEvent *event, gpointer data) {
    (void)event; (void)data;
    GtkWidget *dialog = gtk_message_dialog_new(GTK_WINDOW(window), GTK_DIALOG_MODAL,
        GTK_MESSAGE_QUESTION, GTK_BUTTONS_OK_CANCEL, "Close test window?");
    int response = gtk_dialog_run(GTK_DIALOG(dialog));
    gtk_widget_destroy(dialog);
    return response != GTK_RESPONSE_OK;
}
int main(int argc, char **argv) {
    gtk_init(&argc, &argv);
    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(window), "Wayland interaction");
    gtk_window_set_default_size(GTK_WINDOW(window), 600, 360);
    GtkWidget *box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_container_set_border_width(GTK_CONTAINER(box), 20);
    gtk_container_add(GTK_CONTAINER(window), box);
    GtkWidget *entry = gtk_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(entry), "IME and clipboard");
    gtk_box_pack_start(GTK_BOX(box), entry, FALSE, FALSE, 0);
    GtkWidget *text = gtk_text_view_new();
    gtk_text_buffer_set_text(gtk_text_view_get_buffer(GTK_TEXT_VIEW(text)), "Select, copy and drag this text.", -1);
    gtk_box_pack_start(GTK_BOX(box), text, TRUE, TRUE, 0);
    GtkWidget *files = gtk_button_new_with_label(argc > 1 ? "Drag file / drop file here" : "Drop file here");
    gtk_drag_dest_set(files, GTK_DEST_DEFAULT_ALL, file_types, 1, GDK_ACTION_COPY);
    g_signal_connect(files, "drag-data-received", G_CALLBACK(files_received), NULL);
    if (argc > 1) {
        gtk_drag_source_set(files, GDK_BUTTON1_MASK, file_types, 1, GDK_ACTION_COPY);
        g_signal_connect(files, "drag-data-get", G_CALLBACK(file_data), argv[1]);
    }
    gtk_box_pack_start(GTK_BOX(box), files, FALSE, FALSE, 0);
    GtkWidget *button = gtk_button_new_with_label("Fullscreen");
    gtk_box_pack_start(GTK_BOX(box), button, FALSE, FALSE, 0);
    g_signal_connect(entry, "changed", G_CALLBACK(changed), NULL);
    g_signal_connect(button, "clicked", G_CALLBACK(fullscreen), window);
    g_signal_connect(window, "key-press-event", G_CALLBACK(key), NULL);
    g_signal_connect(window, "delete-event", G_CALLBACK(closing), NULL);
    g_signal_connect(window, "destroy", G_CALLBACK(gtk_main_quit), NULL);
    gtk_widget_show_all(window);
    gtk_main();
}
