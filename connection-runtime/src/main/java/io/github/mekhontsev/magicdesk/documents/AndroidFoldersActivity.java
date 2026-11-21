package io.github.mekhontsev.magicdesk.documents;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.SimpleAdapter;
import io.github.mekhontsev.magicdesk.connections.R;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Non-exported. The calling environment, not a global "current Linux", owns the selection. */
public class AndroidFoldersActivity extends Activity {
    public static final String SCOPE="scope";
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private FolderMounts catalog;
    private String scope;
    private ListView list;
    private Button add;
    private List<FolderMounts.Mount> rows=List.of();
    private Uri pending;
    private int offered;
    private boolean picking;
    private boolean writeSelected;
    private AlertDialog accessDialog;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        scope=getIntent().getStringExtra(SCOPE);
        if (scope==null || scope.isBlank()) { finish(); return; }
        catalog=new FolderMounts(this);
        var layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        var toolbar=new android.widget.Toolbar(this); toolbar.setTitle(R.string.folder_title);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        toolbar.setNavigationContentDescription(android.R.string.cancel); toolbar.setNavigationOnClickListener(v -> finish());
        layout.addView(toolbar); list=new ListView(this);
        layout.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        add=new Button(this); add.setText(R.string.folder_connect); add.setOnClickListener(v -> pick()); layout.addView(add);
        layout.setOnApplyWindowInsetsListener((v,insets) -> {
            var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        setContentView(layout);
        list.setOnItemClickListener((parent,v,pos,id) -> {
            var row=rows.get(pos);
            new AlertDialog.Builder(this).setTitle(R.string.folder_forget).setMessage(getString(R.string.folder_forget_message,row.target()))
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.folder_forget,(dialog,i) ->
                        work(() -> { catalog.forget(row); changed(scope); return true; },ok -> refresh(false))).show();
        });
        if (state!=null) {
            pending=state.getParcelable("tree",Uri.class); offered=state.getInt("offered"); picking=state.getBoolean("picking");
            writeSelected=state.getBoolean("writeSelected");
        }
        refresh(state==null);
    }
    /** Publish the committed configuration to the selected executor. Runs on the IO worker. */
    protected void changed(String scope) throws Exception { }
    private void refresh(boolean first) {
        work(() -> {
            var entries=catalog.list(scope);
            var labels=entries.stream().map(m -> java.util.Map.of("path",m.target(),"mode",getString(!catalog.available(m)
                    ? R.string.folder_unavailable : m.writable() ? R.string.folder_rw : R.string.folder_ro))).toList();
            return new Display(entries,labels);
        }, display -> {
            rows=display.rows;
            list.setAdapter(new SimpleAdapter(this,display.labels,android.R.layout.simple_list_item_2,
                    new String[]{"path","mode"},new int[]{android.R.id.text1,android.R.id.text2}));
            if (pending!=null) access();
            else if (first && rows.isEmpty() && !picking) pick();
        });
    }
    private record Display(List<FolderMounts.Mount> rows,List<java.util.Map<String,String>> labels) { }
    private void pick() {
        picking=true;
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                FolderMounts.READ|FolderMounts.WRITE|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),1); }
        catch (RuntimeException e) { picking=false; error(e); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request!=1) return;
        picking=false;
        if (result!=RESULT_OK || data==null || data.getData()==null) { if (rows.isEmpty()) finish(); return; }
        if ((data.getFlags()&(FolderMounts.READ|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) !=
                (FolderMounts.READ|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) {
            error(new IllegalArgumentException("This provider did not grant persistent read access")); return;
        }
        offered=data.getFlags(); pending=data.getData(); writeSelected=false; access();
    }
    private void access() {
        if (accessDialog!=null) return;
        Uri tree=pending;
        boolean write=(offered&FolderMounts.WRITE)!=0;
        String ro=getString(R.string.folder_ro),rw=getString(R.string.folder_rw);
        var dialog=new AlertDialog.Builder(this).setTitle(R.string.folder_access)
                .setSingleChoiceItems(write ? new String[]{ro,rw} : new String[]{ro},write && writeSelected ? 1 : 0,
                        (d,i) -> writeSelected=i==1)
                .setNegativeButton(android.R.string.cancel,(d,i) -> pending=null)
                .setOnCancelListener(d -> pending=null)
                .setPositiveButton(R.string.folder_connect,(d,i) -> {
                    boolean writable=((AlertDialog)d).getListView().getCheckedItemPosition()==1;
                    pending=null;
                    work(() -> {
                        var source=new DocumentTree(getContentResolver(),new FolderMounts.Mount(scope,tree,"",writable));
                        var label=source.stat(source.root()).name();
                        var row=catalog.remember(scope,tree,label,offered,writable);
                        changed(scope);
                        return row;
                    },row -> {
                        android.widget.Toast.makeText(this,getString(R.string.folder_next,row.target()),android.widget.Toast.LENGTH_LONG).show();
                        refresh(false);
                    });
                }).create();
        accessDialog=dialog;
        dialog.setOnDismissListener(d -> accessDialog=null);
        dialog.show();
    }
    private <T> void work(Callable<T> task,Consumer<T> done) {
        add.setEnabled(false); list.setEnabled(false);
        worker.execute(() -> {
            try { var value=task.call(); runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) {
                add.setEnabled(true); list.setEnabled(true); done.accept(value);
            }}); }
            catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) {
                add.setEnabled(true); list.setEnabled(true); error(e);
            }}); }
        });
    }
    private void error(Exception e) {
        new AlertDialog.Builder(this).setMessage(e.getMessage()==null ? e.toString() : e.getMessage())
                .setPositiveButton(android.R.string.ok,null).show();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("tree",pending); state.putInt("offered",offered); state.putBoolean("picking",picking);
        state.putBoolean("writeSelected",writeSelected);
    }
    @Override protected void onDestroy() {
        if (accessDialog!=null) accessDialog.dismiss();
        worker.shutdown(); super.onDestroy();
    }
}
