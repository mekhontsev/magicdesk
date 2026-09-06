package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
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

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.Executor;

import io.github.mekhontsev.magicdesk.FileHandlerRepository.Target;

/** Resolves file handlers without opening Android's desktop-obscuring resolver. */
final class FileOpenWithController {
    interface Launcher {
        void launchAndroid(Intent intent);

        void launchDesktop(
                DesktopApplicationShortcut shortcut,
                DesktopLaunchArguments arguments,
                String desktopFilePath);

        void noHandler();

        void failed(Throwable error);
    }

    private final Activity mActivity;
    private final Executor mWorker;
    private final DesktopDialogPresenter mDialogPresenter;
    private WeakReference<AlertDialog> mDialog = new WeakReference<>(null);
    private ContentRequestScope mRequest;
    private boolean mClosed;

    FileOpenWithController(final Activity activity, final Executor worker) {
        this(activity, worker, null);
    }

    FileOpenWithController(
            final Activity activity,
            final Executor worker,
            final DesktopDialogPresenter dialogPresenter) {
        mActivity = activity;
        mWorker = worker;
        mDialogPresenter = dialogPresenter;
    }

    void open(
            final Intent source,
            final DesktopLaunchArguments arguments,
            final boolean alwaysAsk,
            final Launcher launcher) {
        if (mClosed) {
            return;
        }
        cancelRequest();
        final ContentRequestScope request = new ContentRequestScope(mWorker);
        mRequest = request;
        final Intent intent = new Intent(source);
        request.submit(cancelled -> FileHandlerRepository.load(mActivity, intent, alwaysAsk), null)
                .thenAccept(completion -> request.deliver(mActivity::runOnUiThread, () -> {
                    if (!isCurrent(request)) {
                        return;
                    }
                    final FileHandlerRepository.Selection selection = completion.value;
                    try {
                        if (completion.failure != null) {
                            launcher.failed(completion.failure);
                        } else if (selection.targets.isEmpty()) {
                            launcher.noHandler();
                        } else {
                            final Target direct = selection.directTarget(alwaysAsk);
                            if (direct != null) {
                                launch(intent, direct, arguments, launcher);
                            } else if (!showDialog(request, intent, arguments,
                                    selection.targets, selection.preferred, launcher)) {
                                launcher.failed(new IllegalStateException("file chooser is unavailable"));
                            }
                        }
                    } catch (RuntimeException error) {
                        launcher.failed(error);
                    }
                }));
    }

    void close() {
        mClosed = true;
        cancelRequest();
    }

    private void cancelRequest() {
        if (mRequest != null) {
            mRequest.close();
            mRequest = null;
        }
        final AlertDialog dialog = mDialog.get();
        mDialog.clear();
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private boolean isCurrent(final ContentRequestScope request) {
        return request == mRequest && !mClosed
                && !mActivity.isFinishing() && !mActivity.isDestroyed();
    }

    private boolean showDialog(
            final ContentRequestScope request,
            final Intent source,
            final DesktopLaunchArguments arguments,
            final List<Target> targets,
            final Target preferred,
            final Launcher launcher) {
        if (mDialogPresenter != null) {
            return mDialogPresenter.show(host -> !isCurrent(request) ? null : createDialog(
                    request,
                    host,
                    source,
                    arguments,
                    targets,
                    preferred,
                    launcher));
        }
        final AlertDialog dialog = createDialog(
                request,
                mActivity,
                source,
                arguments,
                targets,
                preferred,
                launcher);
        dialog.show();
        return true;
    }

    private AlertDialog createDialog(
            final ContentRequestScope request,
            final Activity host,
            final Intent source,
            final DesktopLaunchArguments arguments,
            final List<Target> targets,
            final Target preferred,
            final Launcher launcher) {
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
        // The presenter owns dismissal. Keep only a weak cancellation handle
        // so replacing this request can dismiss its dialog without retaining
        // a destroyed presenter Activity or replacing its lifecycle listener.
        mDialog = new WeakReference<>(dialog);
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
                if (selected != null && isCurrent(request)) {
                    launch(source, selected, arguments, launcher);
                    dialog.dismiss();
                }
            });
            always.setOnClickListener(view -> {
                final Target selected = adapter.selected();
                if (selected == null || !selected.android() || !isCurrent(request)) {
                    return;
                }
                list.setEnabled(false);
                once.setEnabled(false);
                always.setEnabled(false);
                request.submit(cancelled -> {
                    FileHandlerRepository.setPreferred(source, targets, selected);
                    return null;
                }, null).thenAccept(completion -> request.deliver(
                        mActivity::runOnUiThread, () -> {
                            if (!isCurrent(request) || !dialog.isShowing()) {
                                return;
                            }
                            if (completion.failure == null) {
                                launch(source, selected, arguments, launcher);
                                dialog.dismiss();
                            } else {
                                list.setEnabled(true);
                                updateButtons(adapter, once, always);
                                Toast.makeText(host, host.getString(
                                        R.string.file_manager_default_failed,
                                        ShellAccess.usefulMessage(completion.failure)), Toast.LENGTH_LONG).show();
                            }
                        }));
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

    private static final class TargetAdapter extends BaseAdapter {
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
