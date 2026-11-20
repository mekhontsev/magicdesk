package io.github.mekhontsev.magicdesk.guestapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Ordinary Android host for its own retained Linux session. No MagicDesk dependency. */
public final class EnvironmentActivity extends Activity {
    private EnvironmentService service;
    private EnvironmentTerminalView terminal;
    private TextView status;
    private boolean bound;
    private Uri pending;
    private boolean pendingExport;
    private boolean waitingForArchive;
    private Intent pendingConnection;
    private boolean pendingConnectionResult;
    private AlertDialog confirmation;
    private final Runnable updated=this::refresh;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            service=((EnvironmentService.Connection)binder).service();
            terminal.attach(service);
            service.changed=updated;
            deliverConnectionResult();
            openPending();
        }
        @Override public void onServiceDisconnected(ComponentName name) { detach(); finish(); }
    };

    private void openPending() {
        if (service==null) return;
        if (waitingForArchive) { refresh(); return; }
        if (pending!=null && pendingExport) {
            Uri archive=pending; pending=null; pendingExport=false;
            if (service.installed() && !service.active()) service.exportArchive(archive);
            else service.status="End the session before saving a backup";
        } else if (pending!=null && !service.installed() && !service.active()) {
            Uri archive=pending;
            EnvironmentService selected=service;
            if (confirmation!=null) confirmation.dismiss();
            confirmation=new AlertDialog.Builder(this).setTitle("Import Linux backup?")
                    .setMessage("This creates a private copy for this application.")
                    .setNegativeButton(android.R.string.cancel,(dialog,which) -> pending=null)
                    .setOnCancelListener(dialog -> pending=null)
                    .setPositiveButton("Import",(dialog,which) -> { pending=null; selected.importArchive(archive); }).show();
        } else service.open();
        refresh();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); pending=intent.getData(); pendingExport=false; openPending();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pending=state==null ? getIntent().getData() : state.getParcelable("pending",Uri.class);
        pendingExport=state!=null && state.getBoolean("pendingExport");
        waitingForArchive=state!=null && state.getBoolean("waitingForArchive");
        pendingConnection=state==null ? null : state.getParcelable("connection",Intent.class);
        pendingConnectionResult=state!=null && state.getBoolean("connectionResult");
        var layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        var toolbar=new android.widget.Toolbar(this);
        toolbar.setTitle(getApplicationInfo().loadLabel(getPackageManager()));
        layout.addView(toolbar,new LinearLayout.LayoutParams(-1,-2)); setActionBar(toolbar);
        status=new TextView(this); status.setPadding(16,8,16,8);
        layout.addView(status,new LinearLayout.LayoutParams(-1,-2));
        terminal=new EnvironmentTerminalView(this);
        layout.addView(terminal,new LinearLayout.LayoutParams(-1,0,1));
        layout.setOnApplyWindowInsetsListener((view,insets) -> {
            var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        setContentView(layout);
        Intent intent=new Intent(this,EnvironmentService.class);
        startForegroundService(intent); bound=bindService(intent,connection,BIND_AUTO_CREATE);
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},2);
    }
    private void refresh() {
        if (service==null) return;
        if (service.closed()) { finish(); return; }
        String text=service.installed() ? service.status : service.active() ? service.status : "No Linux environment";
        status.setText(text); status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        terminal.invalidate(); invalidateOptionsMenu();
    }
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0,1,0,"Import backup"); menu.add(0,2,1,"New terminal"); menu.add(0,3,2,"End session");
        if (android.os.Build.VERSION.SDK_INT>=37) menu.add(0,4,3,"Local network access");
        menu.add(0,5,4,"Connect to MagicDesk");
        menu.add(0,6,5,"Disconnect from MagicDesk");
        menu.add(0,7,6,"Export backup");
        menu.add(0,8,7,io.github.mekhontsev.magicdesk.connections.R.string.folder_title);
        return true;
    }
    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(1).setEnabled(service!=null && !service.installed() && !service.active());
        menu.findItem(2).setEnabled(service!=null && service.installed() && !service.active());
        menu.findItem(3).setEnabled(service!=null && service.active());
        menu.findItem(7).setEnabled(service!=null && service.installed() && !service.active());
        menu.findItem(5).setEnabled(service!=null && !service.connected());
        menu.findItem(6).setEnabled(service!=null && service.connected()); return true;
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (service==null) return false;
        if (item.getItemId()==1) {
            waitingForArchive=true;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),1);
        } else if (item.getItemId()==2) service.startTerminal();
        else if (item.getItemId()==3) {
            EnvironmentService selected=service;
            confirmation=new AlertDialog.Builder(this).setTitle("End Linux session?")
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton("End",(dialog,which) -> selected.endTerminal()).show();
        } else if (item.getItemId()==4 && android.os.Build.VERSION.SDK_INT>=37)
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_LOCAL_NETWORK},3);
        else if (item.getItemId()==5) {
            try { startActivityForResult(service.connectHost(),5); }
            catch (Exception error) {
                service.disconnectHost();
                new AlertDialog.Builder(this).setMessage(error.toString()).setPositiveButton(android.R.string.ok,null).show();
            }
        } else if (item.getItemId()==6) { service.disconnectHost(); refresh(); }
        else if (item.getItemId()==7) {
            waitingForArchive=true;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zstd")
                    .putExtra(Intent.EXTRA_TITLE,"linux-backup.tar.zst"),7);
        }
        else if (item.getItemId()==8) startActivity(new Intent(this,
                io.github.mekhontsev.magicdesk.documents.AndroidFoldersActivity.class)
                .putExtra(io.github.mekhontsev.magicdesk.documents.AndroidFoldersActivity.SCOPE,
                        io.github.mekhontsev.magicdesk.documents.FolderMounts.INDEPENDENT));
        else return super.onOptionsItemSelected(item);
        return true;
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==1 || request==7) waitingForArchive=false;
        if ((request==1 || request==7) && result==RESULT_OK && data!=null && data.getData()!=null) {
            pending=data.getData();
            pendingExport=request==7;
            openPending();
        }
        if (request==5) {
            pendingConnection=result==RESULT_OK ? data : null;
            pendingConnectionResult=true;
            deliverConnectionResult();
        }
    }
    private void deliverConnectionResult() {
        if (service==null || !pendingConnectionResult) return;
        Intent reply=pendingConnection;
        pendingConnection=null; pendingConnectionResult=false;
        if (reply!=null) service.acceptHost(reply); else service.disconnectHost();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putParcelable("pending",pending);
        state.putBoolean("pendingExport",pendingExport);
        state.putBoolean("waitingForArchive",waitingForArchive);
        state.putParcelable("connection",pendingConnection);
        state.putBoolean("connectionResult",pendingConnectionResult);
    }
    private void detach() {
        if (service!=null) { if (service.changed==updated) service.changed=null; service=null; }
        if (terminal!=null) terminal.attach(null);
    }
    @Override protected void onDestroy() {
        if (confirmation!=null) confirmation.dismiss();
        detach(); if (bound) unbindService(connection); super.onDestroy();
    }
}
