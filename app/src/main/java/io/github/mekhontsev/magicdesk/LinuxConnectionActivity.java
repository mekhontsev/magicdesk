package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import java.util.List;

/** Interactive authorization for a captured caller; the result is a revocable capability. */
public final class LinuxConnectionActivity extends Activity {
    private Model model;
    private AlertDialog dialog;
    private static final String AUDIO = "", NEW_X11 = "@new-x11", NO_MEDIA="@terminal";
    private RadioGroup selection;
    private android.widget.CheckBox internet,automation;
    private List<String> displayed;
    private final Runnable sessionsChanged = () -> runOnUiThread(this::refreshChoices);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String caller=getCallingPackage();
            if (caller==null) throw new SecurityException("Connection requires an Activity result caller");
            int uid=getPackageManager().getPackageUid(caller,0);
            var extras=getIntent().getExtras();
            var binder=extras==null ? null : extras.getBinder("endpoints");
            if (binder==null) throw new IllegalArgumentException("Missing executor endpoints");
            model=(Model)getLastNonConfigurationInstance();
            if (model==null) model=new Model(getApplicationContext(),uid,IExecutorUnixEndpoints.Stub.asInterface(binder),
                    caller,extras.getBoolean("sessionServices"),extras.getBoolean("privateNetwork"));
            if (model.uid!=uid) throw new SecurityException("Connection caller changed");
            model.changed=this::render;
            CharSequence label=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(caller,0));
            selection=new RadioGroup(this);
            int padding=Math.round(24*getResources().getDisplayMetrics().density);
            var choices=new android.widget.LinearLayout(this); choices.setOrientation(android.widget.LinearLayout.VERTICAL);
            choices.setPadding(padding,0,padding,0); choices.addView(selection);
            if (model.sessionServices) {
                if (model.privateNetwork) {
                    internet=new android.widget.CheckBox(this); internet.setText(R.string.linux_connection_internet);
                    internet.setFilterTouchesWhenObscured(true); choices.addView(internet);
                    internet.setChecked(model.internet);
                    internet.setOnCheckedChangeListener((button,checked)->{ model.internet=checked; render(); });
                }
                automation=new android.widget.CheckBox(this); automation.setText(R.string.linux_connection_mcp);
                automation.setFilterTouchesWhenObscured(true); choices.addView(automation);
                automation.setChecked(model.automation);
                automation.setOnCheckedChangeListener((button,checked)->{ model.automation=checked; render(); });
            }
            dialog=UiDialogs.builder(this).setTitle(R.string.linux_connection_title)
                    .setMessage(getString(model.sessionServices ? R.string.linux_connection_scope : R.string.linux_connection_permission,label))
                    .setView(UiToolLayout.scroll(choices,640))
                    .setNegativeButton(android.R.string.cancel,(d,w)->finish())
                    .setOnCancelListener(d->finish()).setPositiveButton(R.string.linux_connection_connect,null).create();
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->model.start(label.toString()));
            refreshChoices();
            render();
        } catch (Exception error) {
            setResult(RESULT_CANCELED,new Intent().putExtra("error",ShellAccess.usefulMessage(error))); finish();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        GraphicalSessions.listen(sessionsChanged);
        refreshChoices();
    }

    @Override protected void onStop() {
        GraphicalSessions.unlisten(sessionsChanged);
        super.onStop();
    }

    private void refreshChoices() {
        if (dialog==null || model.started || isFinishing() || isDestroyed()) return;
        var sessions=GraphicalSessions.list().stream().filter(GraphicalSessions.Session::ready).toList();
        var ids=sessions.stream().map(GraphicalSessions.Session::id).toList();
        if (ids.equals(displayed)) return;
        displayed=ids;
        if (!AUDIO.equals(model.selected) && !NEW_X11.equals(model.selected) && !NO_MEDIA.equals(model.selected)
                && model.selected!=null && !ids.contains(model.selected)) model.selected=null;
        selection.removeAllViews();
        if (model.sessionServices) addChoice(NO_MEDIA,getString(R.string.linux_connection_terminal));
        addChoice(NEW_X11,getString(R.string.linux_connection_new_x11));
        for (var session:sessions) addChoice(session.id(),getString(R.string.linux_connection_graphics,
                session.protocol().name(),session.name()));
        addChoice(AUDIO,getString(R.string.linux_connection_audio));
        render();
    }

    private void addChoice(String id,String label) {
        var row=new RadioButton(this);
        row.setId(View.generateViewId());
        row.setText(label);
        selection.addView(row);
        if (id.equals(model.selected)) selection.check(row.getId());
        row.setOnClickListener(v->{ model.selected=id; render(); });
    }

    private void render() {
        if (dialog==null || isFinishing()) return;
        if (model.connection!=null) {
            Bundle reply=new Bundle(); reply.putBinder("connection",model.connection);
            model.delivered=true;
            setResult(RESULT_OK,new Intent().putExtras(reply)); finish(); return;
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!model.started && model.selected!=null
                && (!NO_MEDIA.equals(model.selected) || model.internet || model.automation));
        if (internet!=null) internet.setEnabled(!model.started);
        if (automation!=null) automation.setEnabled(!model.started);
        for (int i=0;i<selection.getChildCount();i++) selection.getChildAt(i).setEnabled(!model.started);
        if (model.error!=null) dialog.setMessage(ShellAccess.usefulMessage(model.error));
        else if (model.started) dialog.setMessage(getString(R.string.linux_connection_pending));
    }

    @Override public Object onRetainNonConfigurationInstance() { return model; }
    @Override protected void onDestroy() {
        if (model!=null) {
            model.changed=null;
            if (isFinishing() && !model.delivered) model.cancel();
        }
        if (dialog!=null) dialog.dismiss();
        super.onDestroy();
    }

    private static final class Model {
        final android.content.Context context;
        final int uid;
        final String caller;
        final boolean sessionServices,privateNetwork;
        boolean internet,automation;
        final IExecutorUnixEndpoints endpoints;
        final OperationResources resources=new OperationResources();
        final Handler main=new Handler(Looper.getMainLooper());
        String selected=NEW_X11;
        GraphicalSessions.Session created;
        final Runnable ready=this::serverChanged;
        boolean started,delivered;
        Runnable changed;
        LinuxConnections.Connection connection;
        Throwable error;
        Model(android.content.Context context,int uid,IExecutorUnixEndpoints endpoints,String caller,boolean sessionServices,boolean privateNetwork) {
            this.context=context; this.uid=uid; this.endpoints=endpoints;
            this.caller=caller; this.sessionServices=sessionServices; this.privateNetwork=privateNetwork;
            if (sessionServices) selected=NO_MEDIA;
        }
        void start(String label) {
            if (started || selected==null) return;
            started=true; if (changed!=null) changed.run();
            try {
                if (NEW_X11.equals(selected)) {
                    String name=label.isBlank() ? "Linux" : label.strip();
                    created=GraphicalSessions.start(context,GraphicalProtocol.X11,name.substring(0,Math.min(name.length(),128)),
                            "","",DesktopExecBackend.APP,"",true);
                    created.listen(ready);
                    serverChanged();
                } else {
                    boolean graphical=!AUDIO.equals(selected) && !NO_MEDIA.equals(selected);
                    var graphics=graphical ? GraphicalSessions.find(selected) : null;
                    if (graphical && (graphics==null || !graphics.ready()))
                        throw new IllegalStateException("The selected graphical session is no longer ready");
                    acquire(graphics);
                }
            } catch (RuntimeException failure) { failed(failure); }
        }
        void serverChanged() {
            if (created==null) return;
            if (created.stopped()) { failed(new IllegalStateException(created.error().isBlank()
                    ? "The new X11 display stopped" : created.error())); return; }
            if (!created.ready()) return;
            created.unlisten(ready);
            acquire(created);
        }
        void acquire(GraphicalSessions.Session graphics) {
            new Thread(() -> {
                try {
                    var ready=LinuxConnections.acquire(context,uid,endpoints,graphics,!NO_MEDIA.equals(selected),
                            internet,automation,caller,resources);
                    main.post(() -> {
                        if (resources.isClosed()) return;
                        connection=ready;
                        if (created!=null) {
                            var owned=created;
                            ready.resources.reserve().attach(()->main.post(owned::close));
                            created=null;
                        }
                        if (changed!=null) changed.run();
                    });
                } catch (Exception problem) {
                    android.util.Log.w("LinuxConnection", "Cannot authorize Linux services", problem);
                    main.post(() -> failed(problem));
                }
            },"LinuxConnection").start();
        }
        void cancel() {
            if (created!=null) {
                created.unlisten(ready);
                created.close();
                created=null;
            }
            resources.close();
        }
        void failed(Throwable problem) {
            cancel();
            error=problem;
            if (changed!=null) changed.run();
        }
    }
}
