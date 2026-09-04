package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves file handlers without opening Android's desktop-obscuring resolver. */
final class FileOpenWithController {
    interface Launcher {
        void launchAndroid(Intent intent);

        void launchDesktop(
                DesktopApplicationShortcut shortcut,
                DesktopLaunchArguments arguments,
                String desktopFilePath);
    }

    private final Activity mActivity;
    private final DesktopDialogPresenter mDialogPresenter;
    private AlertDialog mDialog;

    FileOpenWithController(final Activity activity) {
        this(activity, null);
    }

    FileOpenWithController(
            final Activity activity,
            final DesktopDialogPresenter dialogPresenter) {
        mActivity = activity;
        mDialogPresenter = dialogPresenter;
    }

    boolean open(
            final Intent source,
            final DesktopLaunchArguments arguments,
            final boolean alwaysAsk,
            final Launcher launcher) {
        final List<Target> androidTargets = queryAndroidTargets(source);
        final Target preferred = preferredTarget(source, androidTargets);
        if (!alwaysAsk && preferred != null) {
            launch(
                    source,
                    preferred,
                    arguments,
                    launcher);
            return true;
        }
        final List<Target> targets = new ArrayList<>(androidTargets);
        addDesktopTargets(source.getType(), targets);
        if (targets.isEmpty()) {
            return false;
        }
        targets.sort(Comparator
                .comparing((Target target) -> target.label,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Target::key));
        if (!alwaysAsk && targets.size() == 1) {
            launch(source, targets.get(0), arguments, launcher);
            return true;
        }
        return showDialog(source, arguments, targets, preferred, launcher);
    }

    void close() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private List<Target> queryAndroidTargets(final Intent source) {
        final PackageManager packageManager = mActivity.getPackageManager();
        final List<ResolveInfo> matches = packageManager.queryIntentActivities(
                source,
                PackageManager.ResolveInfoFlags.of(
                        PackageManager.MATCH_DEFAULT_ONLY));
        final Map<ComponentName, Target> unique = new LinkedHashMap<>();
        for (final ResolveInfo match : matches) {
            final ActivityInfo activityInfo = match.activityInfo;
            if (activityInfo == null || !activityInfo.exported) {
                continue;
            }
            final ComponentName component = new ComponentName(
                    activityInfo.packageName, activityInfo.name);
            unique.put(component, new Target(
                    component,
                    null,
                    String.valueOf(match.loadLabel(packageManager)),
                    activityInfo.packageName,
                    loadIcon(packageManager, match),
                    match.match));
        }
        return new ArrayList<>(unique.values());
    }

    private void addDesktopTargets(
            final String mimeType,
            final List<Target> targets) {
        try {
            for (final DesktopApplicationRepository.Entry handler
                    : DesktopApplicationRepository.queryHandlers(mimeType)) {
                final DesktopApplicationShortcut shortcut = handler.shortcut;
                final int detailsResource = shortcut.execBackend
                        == DesktopExecBackend.TERMUX
                        ? R.string.file_manager_termux_command
                        : R.string.file_manager_shell_command;
                targets.add(new Target(
                        null,
                        handler,
                        shortcut.name,
                        mActivity.getString(detailsResource),
                        loadDesktopIcon(shortcut),
                        0));
            }
        } catch (IOException ignored) {
            // Android handlers remain usable when shell lookup is absent.
        }
    }

    private Target preferredTarget(
            final Intent source,
            final List<Target> targets) {
        final ResolveInfo resolved = mActivity.getPackageManager()
                .resolveActivity(
                        source,
                        PackageManager.ResolveInfoFlags.of(
                                PackageManager.MATCH_DEFAULT_ONLY));
        if (resolved == null || resolved.activityInfo == null) {
            return null;
        }
        final ComponentName component = new ComponentName(
                resolved.activityInfo.packageName,
                resolved.activityInfo.name);
        for (final Target target : targets) {
            if (component.equals(target.component)) {
                return target;
            }
        }
        try {
            final String encoded = ShellAccess.getSelectedFileHandler(
                    source.getType(), source.getDataString());
            final ComponentName selected = encoded == null
                    ? null : ComponentName.unflattenFromString(encoded);
            if (selected != null) {
                for (final Target target : targets) {
                    if (selected.equals(target.component)) {
                        return target;
                    }
                }
            }
        } catch (IOException ignored) {
            // The custom chooser remains usable when shell lookup is absent.
        }
        return null;
    }

    private boolean showDialog(
            final Intent source,
            final DesktopLaunchArguments arguments,
            final List<Target> targets,
            final Target preferred,
            final Launcher launcher) {
        close();
        if (mDialogPresenter != null) {
            return mDialogPresenter.show(host -> createDialog(
                    host,
                    source,
                    arguments,
                    targets,
                    preferred,
                    launcher,
                    false));
        }
        final AlertDialog dialog = createDialog(
                mActivity,
                source,
                arguments,
                targets,
                preferred,
                launcher,
                true);
        mDialog = dialog;
        dialog.show();
        return true;
    }

    private AlertDialog createDialog(
            final Activity host,
            final Intent source,
            final DesktopLaunchArguments arguments,
            final List<Target> targets,
            final Target preferred,
            final Launcher launcher,
            final boolean retainLocally) {
        final int preferredIndex = preferred == null
                ? -1 : targets.indexOf(preferred);
        final TargetAdapter adapter = new TargetAdapter(
                host, targets, preferred, preferredIndex);
        final ListView list = new ListView(host);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(adapter);
        final AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(R.string.file_manager_open_with)
                .setView(list)
                .setNegativeButton(R.string.file_manager_just_once, null)
                .setPositiveButton(R.string.file_manager_always, null)
                .create();
        if (retainLocally) {
            dialog.setOnDismissListener(ignored -> {
                if (mDialog == dialog) {
                    mDialog = null;
                }
            });
        }
        dialog.setOnShowListener(ignored -> {
            final Button once = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            final Button always = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            updateButtons(adapter, once, always);
            list.setOnItemClickListener((parent, view, position, id) -> {
                adapter.select(position);
                updateButtons(adapter, once, always);
            });
            once.setOnClickListener(view -> {
                final Target selected = adapter.selected();
                if (selected != null) {
                    launch(source, selected, arguments, launcher);
                    dialog.dismiss();
                }
            });
            always.setOnClickListener(view -> {
                final Target selected = adapter.selected();
                if (selected == null || !selected.android()) {
                    return;
                }
                try {
                    ShellAccess.setPreferredFileHandler(
                            source.getType(),
                            encodedComponents(targets),
                            selected.component.flattenToString(),
                            bestMatch(targets));
                    launch(source, selected, arguments, launcher);
                    dialog.dismiss();
                } catch (IOException error) {
                    Toast.makeText(
                            host,
                            host.getString(
                                    R.string.file_manager_default_failed,
                                    ShellAccess.usefulMessage(error)),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
        return dialog;
    }

    private static void updateButtons(
            final TargetAdapter adapter,
            final Button once,
            final Button always) {
        final boolean selected = adapter.selected() != null;
        once.setEnabled(selected);
        always.setEnabled(selected && adapter.selected().android());
    }

    private static String[] encodedComponents(final List<Target> targets) {
        final List<String> encoded = new ArrayList<>();
        for (final Target target : targets) {
            if (target.android()) {
                encoded.add(target.component.flattenToString());
            }
        }
        return encoded.toArray(new String[0]);
    }

    private static int bestMatch(final List<Target> targets) {
        int best = 0;
        for (final Target target : targets) {
            if (target.android()) {
                best = Math.max(best, target.match);
            }
        }
        return best;
    }

    private static void launch(
            final Intent source,
            final Target target,
            final DesktopLaunchArguments arguments,
            final Launcher launcher) {
        if (target.android()) {
            launcher.launchAndroid(explicitIntent(source, target));
            return;
        }
        launcher.launchDesktop(
                target.desktopHandler.shortcut,
                arguments == null
                        ? DesktopLaunchArguments.empty() : arguments,
                target.desktopHandler.desktopFilePath);
    }

    private static Intent explicitIntent(
            final Intent source,
            final Target target) {
        return new Intent(source).setComponent(target.component);
    }

    private static Drawable loadIcon(
            final PackageManager packageManager,
            final ResolveInfo resolveInfo) {
        try {
            return resolveInfo.loadIcon(packageManager);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Drawable loadDesktopIcon(
            final DesktopApplicationShortcut shortcut) {
        return DesktopApplicationIconResolver.resolve(mActivity, shortcut);
    }

    private final class TargetAdapter extends BaseAdapter {
        private final Activity mHost;
        private final List<Target> mTargets;
        private final Target mPreferred;
        private int mSelectedIndex;

        TargetAdapter(
                final Activity host,
                final List<Target> targets,
                final Target preferred,
                final int selectedIndex) {
            mHost = host;
            mTargets = targets;
            mPreferred = preferred;
            mSelectedIndex = selectedIndex;
        }

        void select(final int position) {
            if (position >= 0 && position < mTargets.size()) {
                mSelectedIndex = position;
                notifyDataSetChanged();
            }
        }

        Target selected() {
            return mSelectedIndex >= 0 && mSelectedIndex < mTargets.size()
                    ? mTargets.get(mSelectedIndex) : null;
        }

        @Override
        public int getCount() {
            return mTargets.size();
        }

        @Override
        public Target getItem(final int position) {
            return mTargets.get(position);
        }

        @Override
        public long getItemId(final int position) {
            return getItem(position).key().hashCode();
        }

        @Override
        public View getView(
                final int position,
                final View recycled,
                final ViewGroup parent) {
            final Row row = recycled instanceof LinearLayout
                    && recycled.getTag() instanceof Row
                    ? (Row) recycled.getTag() : createRow();
            final Target target = getItem(position);
            row.icon.setImageDrawable(target.icon);
            row.label.setText(target.label);
            row.packageName.setText(target == mPreferred && target.android()
                    ? mHost.getString(
                            R.string.file_manager_system_default,
                            target.details)
                    : target.details);
            row.selection.setChecked(position == mSelectedIndex);
            return row.root;
        }

        private Row createRow() {
            final LinearLayout root = new LinearLayout(mHost);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(12), dp(6), dp(12), dp(6));
            root.setMinimumHeight(dp(58));

            final ImageView icon = new ImageView(mHost);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            root.addView(icon, new LinearLayout.LayoutParams(
                    dp(42), dp(42)));

            final LinearLayout labels = new LinearLayout(mHost);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, 0, 0);
            final TextView label = new TextView(mHost);
            label.setTextColor(Color.rgb(232, 238, 245));
            label.setTextSize(15f);
            label.setSingleLine(true);
            final TextView packageName = new TextView(mHost);
            packageName.setTextColor(Color.rgb(157, 170, 184));
            packageName.setTextSize(11f);
            packageName.setSingleLine(true);
            labels.addView(label);
            labels.addView(packageName);
            root.addView(labels, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final RadioButton selection = new RadioButton(mHost);
            selection.setClickable(false);
            selection.setFocusable(false);
            root.addView(selection, new LinearLayout.LayoutParams(
                    dp(42), dp(42)));

            final Row row = new Row(
                    root, icon, label, packageName, selection);
            root.setTag(row);
            return row;
        }

        private int dp(final int value) {
            return Math.round(value * mHost.getResources()
                    .getDisplayMetrics().density);
        }
    }

    private static final class Target {
        final ComponentName component;
        final DesktopApplicationRepository.Entry desktopHandler;
        final String label;
        final String details;
        final Drawable icon;
        final int match;

        Target(
                final ComponentName component,
                final DesktopApplicationRepository.Entry desktopHandler,
                final String label,
                final String details,
                final Drawable icon,
                final int match) {
            this.component = component;
            this.desktopHandler = desktopHandler;
            this.label = label;
            this.details = details;
            this.icon = icon;
            this.match = match;
        }

        boolean android() {
            return component != null;
        }

        String key() {
            return android()
                    ? component.flattenToString()
                    : desktopHandler.desktopFilePath;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final RadioButton selection;

        Row(
                final LinearLayout root,
                final ImageView icon,
                final TextView label,
                final TextView packageName,
                final RadioButton selection) {
            this.root = root;
            this.icon = icon;
            this.label = label;
            this.packageName = packageName;
            this.selection = selection;
        }
    }
}
