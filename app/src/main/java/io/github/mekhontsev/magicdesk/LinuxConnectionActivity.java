package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Interactive authorization for a captured caller; the result is a revocable capability. */
public final class LinuxConnectionActivity extends Activity {
    private Model model;
    private AlertDialog dialog;
    private android.widget.Spinner selection;

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
            if (model==null) model=new Model(getApplicationContext(),uid,IExecutorUnixEndpoints.Stub.asInterface(binder));
            if (model.uid!=uid) throw new SecurityException("Connection caller changed");
            model.changed=this::render;
            CharSequence label=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(caller,0));
            selection=new android.widget.Spinner(this);
            var labels=new java.util.ArrayList<String>(); labels.add(getString(R.string.linux_connection_audio));
            for (var session:model.sessions) labels.add(getString(R.string.linux_connection_graphics,session.protocol().name(),session.name()));
            UiChoiceAdapter.bind(selection,labels);
            selection.setSelection(model.selected);
            selection.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
                @Override public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id) {
                    if (!model.started) model.selected=position;
                }
            });
            var view=new android.widget.LinearLayout(this);
            int padding=Math.round(24*getResources().getDisplayMetrics().density);
            view.setPadding(padding,0,padding,0); view.addView(selection,new android.widget.LinearLayout.LayoutParams(-1,-2));
            dialog=UiDialogs.builder(this).setTitle(R.string.linux_connection_title)
                    .setMessage(getString(R.string.linux_connection_permission,label))
                    .setView(view)
                    .setNegativeButton(android.R.string.cancel,(d,w)->finish())
                    .setOnCancelListener(d->finish()).setPositiveButton(R.string.linux_connection_connect,null).create();
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->model.start());
            render();
        } catch (Exception error) {
            setResult(RESULT_CANCELED,new Intent().putExtra("error",ShellAccess.usefulMessage(error))); finish();
        }
    }

    private void render() {
        if (dialog==null || isFinishing()) return;
        if (model.connection!=null) {
            Bundle reply=new Bundle(); reply.putBinder("connection",model.connection);
            model.delivered=true;
            setResult(RESULT_OK,new Intent().putExtras(reply)); finish(); return;
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!model.started);
        selection.setEnabled(!model.started);
        if (model.error!=null) dialog.setMessage(ShellAccess.usefulMessage(model.error));
        else if (model.started) dialog.setMessage(getString(R.string.linux_connection_pending));
    }

    @Override public Object onRetainNonConfigurationInstance() { return model; }
    @Override protected void onDestroy() {
        if (model!=null) {
            model.changed=null;
            if (isFinishing() && !model.delivered) model.resources.close();
        }
        if (dialog!=null) dialog.dismiss();
        super.onDestroy();
    }

    private static final class Model {
        final android.content.Context context;
        final int uid;
        final IExecutorUnixEndpoints endpoints;
        final OperationResources resources=new OperationResources();
        final Handler main=new Handler(Looper.getMainLooper());
        final java.util.List<GraphicalSessions.Session> sessions;
        int selected;
        boolean started,delivered;
        Runnable changed;
        LinuxConnections.Connection connection;
        Throwable error;
        Model(android.content.Context context,int uid,IExecutorUnixEndpoints endpoints) {
            this.context=context; this.uid=uid; this.endpoints=endpoints;
            sessions=GraphicalSessions.list().stream().filter(GraphicalSessions.Session::ready).toList();
        }
        void start() {
            if (started) return;
            started=true; if (changed!=null) changed.run();
            var graphics=selected==0 ? null : sessions.get(selected-1);
            new Thread(() -> {
                try {
                    var ready=LinuxConnections.acquire(context,uid,endpoints,graphics,resources);
                    main.post(() -> { connection=ready; if (changed!=null) changed.run(); });
                } catch (Exception problem) {
                    main.post(() -> { error=problem; if (changed!=null) changed.run(); });
                }
            },"LinuxConnection").start();
        }
    }
}
