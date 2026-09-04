package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Built-in window for application-specific desktop presentation settings. */
public final class AppPresentationSettingsActivity extends Activity
        implements AppPresentationSettingsView.Actions {
    private static final String EXTRA_PACKAGE = "package";

    private final OnBackInvokedCallback mBackCallback = this::handleBack;
    private AppPresentationSettingsView mView;
    private String mPackageName;
    private boolean mReturnToList;
    private boolean mApplying;

    static Intent createIntent(final Context context) {
        return new Intent(context, AppPresentationSettingsActivity.class);
    }

    static Intent createIntent(
            final Context context,
            final String packageName) {
        return createIntent(context).putExtra(EXTRA_PACKAGE, packageName);
    }

    static AppLaunchTarget launchTarget() {
        return BuiltInDesktopAppCatalog.appPresentationSettingsTarget();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DesktopTaskDescription.apply(
                this,
                R.string.app_presentation_profiles_title,
                R.mipmap.ic_launcher);
        BuiltInWindowRegistry.register(this);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);
        renderIntent(getIntent(), false);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderIntent(intent, false);
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                mBackCallback);
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }

    private void handleBack() {
        if (mPackageName != null && mReturnToList && !mApplying) {
            renderList();
            return;
        }
        if (!mApplying) {
            finish();
        }
    }

    @Override
    public void useSystemScale(final String packageName) {
        mutate(packageName, callback ->
                AppPresentationProfileManager.reset(packageName, callback));
    }

    @Override
    public void setCustomScale(
            final String packageName,
            final int scalePercent) {
        mutate(packageName, callback ->
                AppPresentationProfileManager.setScale(
                        packageName, scalePercent, callback));
    }

    @Override
    public void openProfile(final String packageName) {
        if (mApplying) {
            return;
        }
        mReturnToList = true;
        renderPackage(packageName);
    }

    private void mutate(
            final String packageName,
            final ProfileMutation mutation) {
        if (mApplying || !packageName.equals(mPackageName)) {
            return;
        }
        mApplying = true;
        mView.setEnabled(false);
        mutation.run(result -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            mApplying = false;
            renderPackage(packageName);
            if (!result.success) {
                Toast.makeText(
                        this,
                        result.message,
                        Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void renderIntent(
            final Intent intent,
            final boolean returnToList) {
        final String packageName = intent == null
                ? null : intent.getStringExtra(EXTRA_PACKAGE);
        if (PackageNameValidator.isSafe(packageName)
                && !BuildConfig.APPLICATION_ID.equals(packageName)) {
            mReturnToList = returnToList;
            renderPackage(packageName);
        } else {
            renderList();
        }
    }

    private void renderList() {
        mPackageName = null;
        mReturnToList = false;
        mView = new AppPresentationSettingsView(this, this);
        setContentView(mView.createList());
    }

    private void renderPackage(final String packageName) {
        mPackageName = packageName;
        mView = new AppPresentationSettingsView(this, this);
        setContentView(mView.createDetail(packageName));
        mView.setEnabled(!mApplying);
    }

    private interface ProfileMutation {
        void run(TaskRepository.ActionCallback callback);
    }
}
