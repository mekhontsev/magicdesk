package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

/** A launch menu with options that live only until this menu is dismissed. */
final class ApplicationContextMenu {
    static final class Options {
        boolean newWindow;

        DesktopLaunchPresentation apply(DesktopLaunchPresentation presentation) {
            return presentation.withInstancePolicy(newWindow
                    ? DesktopTaskInstancePolicy.CREATE_NEW : DesktopTaskInstancePolicy.REUSE_EXISTING);
        }
    }

    final Options options = new Options();
    private final Activity activity;
    private final View anchor;
    private final DesktopUiFactory ui;
    private final DesktopAutomationUiRegistry automation;
    private final LinearLayout panel;
    private final boolean allowNewWindow;
    private PopupWindow popup;

    ApplicationContextMenu(Activity activity, View anchor, String title, boolean allowNewWindow,
            DesktopAutomationUiRegistry automation) {
        this.activity = activity;
        this.anchor = anchor;
        this.allowNewWindow = allowNewWindow;
        this.automation = automation;
        ui = new DesktopUiFactory(activity);
        panel = FileItemContextMenu.createPanel(activity, ui);
        panel.addView(ui.menuHeader(title, android.text.TextUtils.TruncateAt.END));
    }

    void add(int label, Runnable action) { add(label, true, action); }

    void add(int label, boolean enabled, Runnable action) {
        final var button = ui.menuItem(label, UiColor.SURFACE);
        button.setEnabled(enabled);
        button.setOnClickListener(view -> {
            popup.dismiss();
            action.run();
        });
        panel.addView(button, new LinearLayout.LayoutParams(-1, ui.menuItemHeight()));
        automation.register(button, "context.action." + activity.getResources().getResourceEntryName(label),
                "menu_item", activity.getString(label));
    }

    void show() {
        if (allowNewWindow) {
            final var checkbox = ui.menuCheckBox(activity.getString(R.string.start_new_window), false);
            checkbox.setOnClickListener(view -> options.newWindow = checkbox.isChecked());
            panel.addView(checkbox, new LinearLayout.LayoutParams(-1, ui.menuItemHeight()));
            automation.register(checkbox, "context.action.start_new_window", "checkbox", checkbox.getText());
        }
        popup = ui.showMenuPopup(anchor, panel, true);
        final var listener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { popup.dismiss(); }
        };
        anchor.addOnAttachStateChangeListener(listener);
        popup.setOnDismissListener(() -> anchor.removeOnAttachStateChangeListener(listener));
    }
}
