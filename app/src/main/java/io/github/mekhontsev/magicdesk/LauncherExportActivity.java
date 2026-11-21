package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;

/** User-confirmed export/install UI. Generation survives rotation, never retaining an Activity. */
public final class LauncherExportActivity extends Activity {
    private static final int INSTALL_PERMISSION = 1, INSTALL = 2, REMOVE = 3;
    private Model model;
    private AlertDialog dialog;
    private boolean waiting;
    private android.widget.CheckBox network;
    private android.widget.Button processWarning;
    private boolean processLimitOn;

    static void openEnvironment(Activity activity,GuestEnvironmentCatalog.Entry entry) {
        try {
            var target=GuestApplicationExports.capture(activity,entry);
            activity.startActivity(new Intent(activity,LauncherExportActivity.class).putExtra("target",target.json().toString()),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(activity.getDisplay().getDisplayId()).toBundle());
        } catch (Exception error) {
            UiDialogs.builder(activity).setTitle(R.string.guest_install_application).setMessage(ShellAccess.usefulMessage(error))
                    .setPositiveButton(android.R.string.ok,null).show();
        }
    }

    static void open(Activity activity, DesktopApplicationRepository.Entry entry, String termuxPackage) {
        try {
            var target = LauncherExports.capture(activity, entry, termuxPackage);
            activity.startActivity(new Intent(activity, LauncherExportActivity.class).putExtra("target", target.json().toString()),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(activity.getDisplay().getDisplayId()).toBundle());
        } catch (Exception error) {
            UiDialogs.builder(activity).setTitle(R.string.launcher_export_title)
                    .setMessage(activity.getString(R.string.launcher_export_failed, ShellAccess.usefulMessage(error)))
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            model = (Model) getLastNonConfigurationInstance();
            if (model == null) model = new Model(getApplicationContext(), LauncherExportStore.Target.parse(
                    new org.json.JSONObject(getIntent().getStringExtra("target"))));
            if (state != null) {
                waiting = state.getBoolean("waiting");
                if (state.containsKey("network") && !model.started) {
                    model.network=state.getBoolean("network");
                    model.confirmed=state.getBoolean("confirmed");
                }
                if (model.uri == null && state.getString("uri") != null) {
                    model.uri = Uri.parse(state.getString("uri"));
                    model.id = state.getString("id");
                    if (state.getString("archive")!=null) model.archive=Uri.parse(state.getString("archive"));
                }
            }
            model.changed = this::render;
            var builder = UiDialogs.builder(this).setTitle(model.environment() ? R.string.guest_install_application : R.string.launcher_export_title)
                    .setMessage(R.string.launcher_export_preparing)
                    .setPositiveButton(R.string.launcher_export_install, null)
                    .setNeutralButton(R.string.launcher_export_remove, null)
                    .setNegativeButton(android.R.string.cancel, (ignored, which) -> finish());
            if (model.environment()) {
                network=new android.widget.CheckBox(this);
                network.setText(io.github.mekhontsev.magicdesk.connections.R.string.net_apk_permission);
                network.setOnCheckedChangeListener((v,checked) -> { if (model.choosing) model.network=checked; });
                var options = new android.widget.LinearLayout(this);
                options.setOrientation(android.widget.LinearLayout.VERTICAL);
                options.addView(network);
                processWarning = new DesktopUiFactory(this).menuItem(R.string.child_process_warning, UiColor.ATTENTION);
                processWarning.setSingleLine(false);
                processWarning.setEllipsize(null);
                processWarning.setMinHeight(UiToolLayout.dp(this, 48));
                processWarning.setOnClickListener(v -> IntegrationStatusDialogs.showProcessLimit(this));
                options.addView(processWarning);
                builder.setView(options);
            }
            dialog=builder.create();
            dialog.setOnCancelListener(ignored -> finish());
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (model.choosing) {
                        model.choosing=false; model.confirmed=true; render(); model.prepare();
                    } else install();
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> remove());
                render();
            });
            dialog.show();
            model.prepare();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, getString(R.string.launcher_export_failed, ShellAccess.usefulMessage(error)),
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public Object onRetainNonConfigurationInstance() { return model; }
    @Override protected void onResume() {
        super.onResume();
        if (processWarning != null) {
            processLimitOn = Boolean.FALSE.equals(SystemChildProcessSetting.disabledOrUnknown(this));
            render();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("waiting", waiting);
        if (model!=null && model.network!=null) {
            state.putBoolean("network",model.network); state.putBoolean("confirmed",model.confirmed);
        }
        if (model != null && model.uri != null) { state.putString("uri", model.uri.toString()); state.putString("id", model.id); }
        if (model!=null && model.archive!=null) state.putString("archive",model.archive.toString());
    }
    @Override protected void onDestroy() {
        if (model != null) {
            model.changed = null;
            if (isFinishing()) model.resources.close();
        }
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    private void render() {
        if (dialog == null || !dialog.isShowing() || isFinishing() || isDestroyed()) return;
        boolean ready = model.entry != null && model.uri != null && !waiting && model.error == null;
        dialog.setMessage(model.error != null ? getString(R.string.launcher_export_failed, ShellAccess.usefulMessage(model.error))
                : model.choosing ? getString(io.github.mekhontsev.magicdesk.connections.R.string.net_apk_choice)
                : model.entry == null ? getString(R.string.launcher_export_preparing) : model.entry.label());
        if (network!=null) {
            network.setVisibility(model.choosing ? android.view.View.VISIBLE : android.view.View.GONE);
            processWarning.setVisibility(model.choosing && processLimitOn ? android.view.View.VISIBLE : android.view.View.GONE);
            if (model.choosing) network.setChecked(Boolean.TRUE.equals(model.network));
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(ready || model.choosing);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(model.choosing ? io.github.mekhontsev.magicdesk.connections.R.string.net_prepare
                : model.entry != null && LauncherExports.installed(this, model.entry)
                ? R.string.launcher_export_update : R.string.launcher_export_install);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(model.entry == null ? android.view.View.GONE : android.view.View.VISIBLE);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(model.entry != null && !waiting);
    }

    private void install() {
        if (waiting || model.uri == null || model.entry == null) return;
        try {
            if (LauncherExports.installed(this, model.entry) && !LauncherExports.signedByUs(this, model.entry))
                throw new SecurityException("An installed package has a different signing identity");
            waiting = true;
            if (!getPackageManager().canRequestPackageInstalls()) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION);
            } else {
                // Public installer UI owns approval and cancellation; no shell install or silent fallback.
                var intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(model.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
                intent.setClipData(android.content.ClipData.newRawUri("launcher", model.uri));
                startActivityForResult(intent, INSTALL);
            }
            render();
        } catch (RuntimeException error) { waiting = false; model.error = error; render(); }
    }

    private void remove() {
        if (waiting || model.entry == null) return;
        if (!LauncherExports.installed(this, model.entry)) { forget(); return; }
        try {
            waiting = true;
            startActivityForResult(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                    Uri.parse("package:" + model.entry.packageName(getPackageName())))
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true), REMOVE);
            render();
        } catch (RuntimeException error) { waiting = false; model.error = error; render(); }
    }

    private void forget() {
        waiting = true;
        LauncherExports.IO.execute(() -> {
            try { LauncherExports.store(this).remove(model.entry.id()); runOnUiThread(this::finish); }
            catch (Exception error) { runOnUiThread(() -> { waiting = false; model.error = error; render(); }); }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        waiting = false;
        if (request == INSTALL_PERMISSION && getPackageManager().canRequestPackageInstalls()) { install(); return; }
        if (request == REMOVE && model.entry != null && !LauncherExports.installed(this, model.entry)) { forget(); return; }
        if (request == INSTALL && result == RESULT_OK && model.entry != null && LauncherExports.signedByUs(this, model.entry)) {
            if (model.environment()) {
                Intent open=new Intent(Intent.ACTION_MAIN).setComponent(new android.content.ComponentName(
                        model.entry.packageName(getPackageName()),"io.github.mekhontsev.magicdesk.guestapp.EnvironmentActivity"));
                if (model.archive!=null) {
                    open.setData(model.archive).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    open.setClipData(android.content.ClipData.newRawUri("Linux backup",model.archive));
                }
                try { startActivity(open); }
                catch (RuntimeException error) { model.error=error; render(); return; }
            }
            finish(); return;
        }
        if (request == INSTALL && result != RESULT_CANCELED && result != RESULT_OK)
            model.error = new IllegalStateException(getString(R.string.launcher_export_install_failed));
        render();
    }

    private static final class Model {
        final android.content.Context context;
        final LauncherExportStore.Target target;
        final Handler main = new Handler(Looper.getMainLooper());
        final OperationResources resources = new OperationResources();
        Runnable changed;
        LauncherExportStore.Entry entry;
        Uri uri;
        Uri archive;
        String id;
        Throwable error;
        boolean started;
        Boolean network;
        boolean choosing,confirmed;
        Model(android.content.Context context, LauncherExportStore.Target target) { this.context = context; this.target = target; }
        boolean environment() { return target.source().equals("environment"); }

        void prepare() {
            if (started || choosing) return;
            started = true;
            LauncherExports.IO.execute(() -> {
                try {
                    if (uri != null && id != null) {
                        var previous = LauncherExports.store(context).get(id);
                        if (!previous.target().equals(target)) throw new SecurityException("Launcher source changed");
                        main.post(() -> { entry = previous; notifyChanged(); });
                        return;
                    }
                    var previous = LauncherExports.store(context).read().stream()
                            .filter(export -> export.target().equals(target)).findFirst().orElse(null);
                    if (environment() && !confirmed) {
                        boolean allowed=previous==null || !LauncherExports.installed(context,previous)
                                || context.getPackageManager().checkPermission(android.Manifest.permission.INTERNET,
                                    previous.packageName(context.getPackageName()))==android.content.pm.PackageManager.PERMISSION_GRANTED;
                        main.post(() -> {
                            if (network==null) network=allowed;
                            entry=previous; started=false; choosing=true; notifyChanged();
                        });
                        return;
                    }
                    if (previous != null) main.post(() -> { entry = previous; notifyChanged(); });
                    if (environment()) {
                        var prepared=GuestApplicationExports.prepare(context,target,resources,Boolean.TRUE.equals(network));
                        main.post(() -> { entry=prepared.entry(); id=entry.id(); uri=prepared.apk(); archive=prepared.archive(); notifyChanged(); });
                        return;
                    }
                    var shortcut = LauncherExports.resolve(context, target);
                    main.post(() -> ApplicationIcons.get(context).request(ApplicationIcons.key(shortcut, target.termuxPackage()),
                            icon -> packageApk(shortcut, icon)));
                } catch (Exception failure) { failed(failure); }
            });
        }

        void packageApk(DesktopApplicationShortcut shortcut, Bitmap artwork) {
            Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            var canvas = new Canvas(bitmap);
            if (artwork != null) {
                var paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                float ratio = Math.min(192f / artwork.getWidth(), 192f / artwork.getHeight());
                float width = artwork.getWidth() * ratio, height = artwork.getHeight() * ratio;
                canvas.drawBitmap(artwork, null, new android.graphics.RectF((192 - width) / 2, (192 - height) / 2,
                        (192 + width) / 2, (192 + height) / 2), paint);
            } else {
                canvas.drawColor(0xffeeeeee);
                var icon = context.getDrawable(R.drawable.ic_file_console).mutate();
                icon.setTint(0xff303030); icon.setBounds(24, 24, 168, 168); icon.draw(canvas);
            }
            LauncherExports.IO.execute(() -> {
                try {
                    LauncherExports.requireIdentity(context, target);
                    var png = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new java.io.IOException("Could not encode launcher icon");
                    var identity = LauncherSigningIdentity.load();
                    var export = LauncherExports.store(context).put(target, shortcut.name, identity.fingerprint());
                    byte[] unsigned;
                    try (var template = context.getAssets().open("launcher/template.apk")) {
                        unsigned = LauncherApk.assemble(template, context.getPackageName(), export.packageName(context.getPackageName()),
                                shortcut.name, png.toByteArray());
                    }
                    byte[] signed = LauncherApk.sign(unsigned, identity.key(), identity.certificate());
                    Uri content = GeneratedContentProvider.publish(context, "Linux-launcher.apk", out -> out.write(signed));
                    main.post(() -> { entry = export; id = export.id(); uri = content; notifyChanged(); });
                } catch (Exception failure) { failed(failure); }
                finally { bitmap.recycle(); }
            });
        }

        void failed(Throwable failure) { main.post(() -> { error = failure; notifyChanged(); }); }
        void notifyChanged() { if (changed != null) changed.run(); }
    }
}
