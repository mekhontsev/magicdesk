#include <gtk/gtk.h>
#include <gtk-layer-shell.h>
#include <stdio.h>

static void text_changed(GtkEditable *entry, gpointer data) {
    (void)data;
    fprintf(stderr, "gtk-shell text=%s\n", gtk_entry_get_text(GTK_ENTRY(entry)));
    fflush(stderr);
}
static void menu_clicked(GtkMenuItem *button, gpointer data) {
    (void)button;
    fprintf(stderr, "gtk-shell menu=clicked\n");
    fflush(stderr);
    (void)data;
}
static gboolean key(GtkWidget *widget, GdkEventKey *event, gpointer data) {
    (void)widget; (void)data;
    fprintf(stderr, "gtk-shell key=%u\n", event->keyval);
    fflush(stderr);
    return FALSE;
}
int main(int argc, char **argv) {
    gtk_init(&argc, &argv);
    if (!gtk_layer_is_supported()) return 2;
    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_layer_init_for_window(GTK_WINDOW(window));
    gtk_layer_set_namespace(GTK_WINDOW(window), "magicdesk-gtk-test");
    gtk_layer_set_layer(GTK_WINDOW(window), GTK_LAYER_SHELL_LAYER_TOP);
    gtk_layer_set_keyboard_mode(GTK_WINDOW(window), GTK_LAYER_SHELL_KEYBOARD_MODE_ON_DEMAND);
    gtk_layer_set_anchor(GTK_WINDOW(window), GTK_LAYER_SHELL_EDGE_TOP, TRUE);
    gtk_layer_set_anchor(GTK_WINDOW(window), GTK_LAYER_SHELL_EDGE_LEFT, TRUE);
    gtk_layer_set_anchor(GTK_WINDOW(window), GTK_LAYER_SHELL_EDGE_RIGHT, TRUE);
    gtk_layer_auto_exclusive_zone_enable(GTK_WINDOW(window));
    GtkWidget *box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    gtk_container_set_border_width(GTK_CONTAINER(box), 8);
    gtk_container_add(GTK_CONTAINER(window), box);
    GtkWidget *entry = gtk_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(entry), "Panel keyboard test");
    gtk_box_pack_start(GTK_BOX(box), entry, TRUE, TRUE, 0);
    GtkWidget *menu = gtk_menu_button_new();
    gtk_button_set_label(GTK_BUTTON(menu), "Menu");
    gboolean popover = g_getenv("MAGICDESK_TEST_POPOVER") != NULL;
    GtkWidget *popup = popover ? gtk_popover_new(menu) : gtk_menu_new();
    GtkWidget *item = popover ? gtk_button_new_with_label("Test action") : gtk_menu_item_new_with_label("Test action");
    gtk_container_add(GTK_CONTAINER(popup), item);
    gtk_widget_show(item);
    if (popover) gtk_menu_button_set_popover(GTK_MENU_BUTTON(menu), popup);
    else gtk_menu_button_set_popup(GTK_MENU_BUTTON(menu), popup);
    gtk_box_pack_start(GTK_BOX(box), menu, FALSE, FALSE, 0);
    g_signal_connect(entry, "changed", G_CALLBACK(text_changed), NULL);
    g_signal_connect(window, "key-press-event", G_CALLBACK(key), NULL);
    g_signal_connect(item, popover ? "clicked" : "activate", G_CALLBACK(menu_clicked), NULL);
    g_signal_connect(window, "destroy", G_CALLBACK(gtk_main_quit), NULL);
    gtk_widget_show_all(window);
    gtk_main();
    return 0;
}
