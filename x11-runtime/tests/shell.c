#include <gtk/gtk.h>
#include <gdk/gdkx.h>
#include <X11/Xatom.h>
#include <stdio.h>
#include <string.h>

static GtkWidget *panel;
static gboolean grab_broken(GtkWidget *widget, GdkEventGrabBroken *event, gpointer unused) {
    (void)widget; (void)unused;
    g_print("x11-shell grab-broken keyboard=%d implicit=%d replacement=%p\n",
            event->keyboard, event->implicit, (void*)event->grab_window);
    return FALSE;
}
static void menu_hidden(GtkWidget *widget, gpointer unused) {
    (void)widget; (void)unused;
    g_print("x11-shell menu=hidden\n");
}
static int probe_grabs(void) {
    Display *display = XOpenDisplay(NULL);
    if (!display) return 1;
    Window focus;
    int revert;
    XGetInputFocus(display, &focus, &revert);
    int pointer = XGrabPointer(display, DefaultRootWindow(display), False, 0,
            GrabModeAsync, GrabModeAsync, None, None, CurrentTime);
    if (pointer == GrabSuccess) XUngrabPointer(display, CurrentTime);
    int keyboard = XGrabKeyboard(display, DefaultRootWindow(display), False,
            GrabModeAsync, GrabModeAsync, CurrentTime);
    if (keyboard == GrabSuccess) XUngrabKeyboard(display, CurrentTime);
    printf("x11-shell probe focus=%lu pointer=%d keyboard=%d\n", focus, pointer, keyboard);
    XCloseDisplay(display);
    return 0;
}
static void text_changed(GtkEntry *entry, gpointer unused) {
    (void)unused;
    g_print("x11-shell text=%s\n", gtk_entry_get_text(entry));
}
static void action(GtkMenuItem *item, gpointer unused) {
    (void)item; (void)unused;
    g_print("x11-shell menu=clicked\n");
}
static void guest_wm(GtkButton *button, gpointer unused) {
    (void)button; (void)unused;
    GdkWindow *window = gtk_widget_get_window(panel);
    Display *display = GDK_WINDOW_XDISPLAY(window);
    XSetSelectionOwner(display, XInternAtom(display, "WM_S0", False), GDK_WINDOW_XID(window), CurrentTime);
    XFlush(display);
    g_print("x11-shell guest-wm=claimed\n");
}
static void realized(GtkWidget *widget, gpointer unused) {
    (void)unused;
    GdkWindow *window = gtk_widget_get_window(widget);
    Display *display = GDK_WINDOW_XDISPLAY(window);
    unsigned long strut[12] = {0, 0, 50, 0, 0, 0, 0, 0, 0, 999, 0, 0};
    XChangeProperty(display, GDK_WINDOW_XID(window), XInternAtom(display, "_NET_WM_STRUT_PARTIAL", False),
            XA_CARDINAL, 32, PropModeReplace, (unsigned char*)strut, 12);
    g_print("x11-shell window=%lu\n", GDK_WINDOW_XID(window));
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--probe-grabs")) return probe_grabs();
    gtk_init(&argc, &argv);
    panel = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(panel), "MagicDesk X11 shell fixture");
    gtk_window_set_type_hint(GTK_WINDOW(panel), GDK_WINDOW_TYPE_HINT_DOCK);
    gtk_window_set_decorated(GTK_WINDOW(panel), FALSE);
    gtk_window_set_default_size(GTK_WINDOW(panel), 1000, 50);
    gtk_window_move(GTK_WINDOW(panel), 0, 0);
    GtkWidget *box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    gtk_container_add(GTK_CONTAINER(panel), box);
    GtkWidget *entry = gtk_entry_new();
    gtk_box_pack_start(GTK_BOX(box), entry, TRUE, TRUE, 0);
    g_signal_connect(entry, "changed", G_CALLBACK(text_changed), NULL);
    GtkWidget *menu_button = gtk_menu_button_new();
    gtk_button_set_label(GTK_BUTTON(menu_button), "Menu");
    GtkWidget *menu = gtk_menu_new();
    g_signal_connect(menu, "grab-broken-event", G_CALLBACK(grab_broken), NULL);
    g_signal_connect(menu, "hide", G_CALLBACK(menu_hidden), NULL);
    GtkWidget *item = gtk_menu_item_new_with_label("Test action");
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);
    g_signal_connect(item, "activate", G_CALLBACK(action), NULL);
    gtk_widget_show_all(menu);
    gtk_menu_button_set_popup(GTK_MENU_BUTTON(menu_button), menu);
    gtk_box_pack_start(GTK_BOX(box), menu_button, FALSE, FALSE, 0);
    GtkWidget *wm = gtk_button_new_with_label("Claim WM");
    gtk_box_pack_start(GTK_BOX(box), wm, FALSE, FALSE, 0);
    g_signal_connect(wm, "clicked", G_CALLBACK(guest_wm), NULL);
    g_signal_connect(panel, "realize", G_CALLBACK(realized), NULL);
    g_signal_connect(panel, "destroy", G_CALLBACK(gtk_main_quit), NULL);
    gtk_widget_show_all(panel);
    gtk_main();
    return 0;
}
