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
    private static final String EXTRA_APPLICATION = "application";

    private final OnBackInvokedCallback mBackCallback = this::handleBack;
    private AppPresentationSettingsView mView;
    private AppIdentity mApplication;
    private boolean mReturnToList;
    private boolean mApplying;

    static Intent createIntent(final Context context) {
        return new Intent(context, AppPresentationSettingsActivity.class);
    }

    static Intent createIntent(
            final Context context,
            final AppIdentity application) {
        return createIntent(context).putExtra(EXTRA_APPLICATION, application.persistentKey());
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
        if (mApplication != null && mReturnToList && !mApplying) {
            renderList();
            return;
        }
        if (!mApplying) {
            finish();
        }
    }

    @Override
    public void useSystemScale(final AppIdentity application) {
        mutate(application, callback ->
                AppPresentationProfileManager.reset(application, callback));
    }

    @Override
    public void setCustomScale(
            final AppIdentity application,
            final int scalePercent) {
        mutate(application, callback ->
                AppPresentationProfileManager.setScale(
                        application, scalePercent, callback));
    }

    @Override
    public void openProfile(final AppIdentity application) {
        if (mApplying) {
            return;
        }
        mReturnToList = true;
        renderPackage(application);
    }

    private void mutate(
            final AppIdentity application,
            final ProfileMutation mutation) {
        if (mApplying || !application.equals(mApplication)) {
            return;
        }
        mApplying = true;
        mView.setEnabled(false);
        mutation.run(result -> runOnUiThread(
                () -> finishMutation(application, result)));
    }

    void finishMutation(final AppIdentity application,
            final TaskRepository.ActionResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mApplying = false;
        if (application.equals(mApplication)) {
            renderPackage(application);
        } else if (mApplication == null) {
            renderList();
        } else {
            mView.setEnabled(true);
        }
        if (!result.success) {
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
        }
    }

    private void renderIntent(
            final Intent intent,
            final boolean returnToList) {
        final String encoded = intent == null ? null : intent.getStringExtra(EXTRA_APPLICATION);
        if (encoded == null) {
            renderList();
            return;
        }
        try {
            final AppIdentity application = AppIdentity.fromPersistentKey(encoded);
            AppProfile.requireCurrent(this, application);
            AppPresentationProfileManager.requireUserApplication(application);
            mReturnToList = returnToList;
            renderPackage(application);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void renderList() {
        mApplication = null;
        mReturnToList = false;
        mView = new AppPresentationSettingsView(this, this);
        setContentView(mView.createList());
        mView.setEnabled(!mApplying);
    }

    private void renderPackage(final AppIdentity application) {
        mApplication = application;
        mView = new AppPresentationSettingsView(this, this);
        setContentView(mView.createDetail(application));
        mView.setEnabled(!mApplying);
    }

    private interface ProfileMutation {
        void run(TaskRepository.ActionCallback callback);
    }
}
