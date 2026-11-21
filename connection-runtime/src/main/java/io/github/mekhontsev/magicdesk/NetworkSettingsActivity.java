package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import io.github.mekhontsev.magicdesk.connections.R;
import java.util.ArrayList;
import java.util.List;

/** One editor for per-environment launch defaults; editing never touches a running tree. */
public class NetworkSettingsActivity extends Activity {
    public static final String SCOPE="scope";
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final ArrayList<Row> rows=new ArrayList<>();
    private LinearLayout body,ports,bridge;
    private RadioGroup modes;
    private Button save;
    private boolean loaded;
    private int host,managed,offline;
    private record Row(LinearLayout view,EditText host,EditText guest,CheckBox external,Spinner protocol) {}

    protected GuestNetworkProfile readProfile() throws Exception { return StandaloneNetwork.read(this); }
    protected void writeProfile(GuestNetworkProfile profile) throws Exception { StandaloneNetwork.write(this,profile); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var layout=column();
        var toolbar=new Toolbar(this); toolbar.setTitle(R.string.net_title);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        toolbar.setNavigationContentDescription(android.R.string.cancel); toolbar.setNavigationOnClickListener(v -> finish()); layout.addView(toolbar);
        var scroll=new ScrollView(this); layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=column(); int pad=Math.round(16*getResources().getDisplayMetrics().density); body.setPadding(pad,0,pad,pad); scroll.addView(body);
        modes=new RadioGroup(this); body.addView(modes);
        host=mode(R.string.net_host); managed=mode(R.string.net_bridge); offline=mode(R.string.net_none);
        bridge=column(); body.addView(bridge); ports=column(); bridge.addView(ports);
        var add=new Button(this); add.setText(R.string.net_add); bridge.addView(add);
        add.setOnClickListener(v -> { if (rows.size()<GuestNetworkProfile.MAX_PORTS) add("", "",false,false); });
        modes.setOnCheckedChangeListener((g,id) -> bridge.setVisibility(id==managed ? View.VISIBLE : View.GONE));
        save=new Button(this); save.setText(R.string.net_save); layout.addView(save); save.setOnClickListener(v -> save());
        layout.setOnApplyWindowInsetsListener((v,insets) -> {
            var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        setContentView(layout);
        if (state!=null && state.containsKey("mode")) {
            modes.check(switch(state.getString("mode","host")) { case "bridge" -> managed; case "none" -> offline; default -> host; });
            for (var row:state.getParcelableArrayList("rows",Bundle.class))
                add(row.getString("host"),row.getString("guest"),row.getBoolean("external"),row.getBoolean("udp"));
            loaded=true;
        } else {
            body.setVisibility(View.INVISIBLE); save.setEnabled(false);
            io.execute(() -> {
                try {
                    var p=readProfile(); runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        modes.check(p.mode()==GuestNetworkProfile.Mode.BRIDGE ? managed : p.mode()==GuestNetworkProfile.Mode.NONE ? offline : host);
                        for (var rule:p.publications()) {
                            var port=GuestNetworkProfile.Port.parse(rule);
                            add(Integer.toString(port.host()),Integer.toString(port.guest()),port.external(),port.udp());
                        }
                        loaded=true; body.setVisibility(View.VISIBLE); save.setEnabled(true);
                    });
                } catch (Exception e) { error(e); }
            });
        }
    }
    private LinearLayout column() { var box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private int mode(int label) {
        var b=new RadioButton(this); b.setId(View.generateViewId()); b.setText(label); modes.addView(b); return b.getId();
    }
    private EditText field(LinearLayout box,int label,String value) {
        var f=new EditText(this); f.setSingleLine(); f.setInputType(InputType.TYPE_CLASS_NUMBER);
        f.setHint(label); f.setContentDescription(getText(label)); f.setText(value);
        box.addView(f,new LinearLayout.LayoutParams(0,-2,1)); return f;
    }
    private void add(String host,String guest,boolean external,boolean udp) {
        var group=column(); ports.addView(group);
        var line=new LinearLayout(this); group.addView(line);
        var h=field(line,R.string.net_host_port,host); var g=field(line,R.string.net_guest_port,guest);
        var proto=new Spinner(this); var choices=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,List.of("TCP","UDP"));
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        proto.setAdapter(choices); proto.setSelection(udp ? 1 : 0); line.addView(proto);
        var remove=new ImageButton(this); remove.setImageResource(android.R.drawable.ic_menu_delete);
        remove.setContentDescription(getString(R.string.net_remove)); line.addView(remove);
        var lan=new CheckBox(this); lan.setText(R.string.net_external); lan.setChecked(external); group.addView(lan);
        var row=new Row(group,h,g,lan,proto); rows.add(row);
        remove.setOnClickListener(v -> { rows.remove(row); ports.removeView(group); });
    }
    private GuestNetworkProfile.Mode selected() {
        return modes.getCheckedRadioButtonId()==managed ? GuestNetworkProfile.Mode.BRIDGE
                : modes.getCheckedRadioButtonId()==offline ? GuestNetworkProfile.Mode.NONE : GuestNetworkProfile.Mode.HOST;
    }
    private int number(EditText f) {
        try { int n=Integer.parseInt(f.getText().toString()); if(n>=1 && n<=65535) return n; }
        catch (NumberFormatException ignored) { }
        f.requestFocus(); f.setError(getString(R.string.net_range)); return 0;
    }
    private void save() {
        var published=new ArrayList<GuestNetworkProfile.Port>();
        if (selected()==GuestNetworkProfile.Mode.BRIDGE) for (var row:rows) {
            int h=number(row.host); if (h==0) return;
            int g=number(row.guest); if (g==0) return;
            published.add(new GuestNetworkProfile.Port(h,g,row.protocol.getSelectedItemPosition()==1,row.external.isChecked()));
        }
        try {
            var profile=GuestNetworkProfile.forSettings(selected(),published); save.setEnabled(false); body.setVisibility(View.INVISIBLE);
            io.execute(() -> { try { writeProfile(profile); runOnUiThread(this::finish); } catch (Exception e) { error(e); } });
        } catch (IllegalArgumentException e) { error(e); }
    }
    private void error(Exception e) { runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        if (loaded) { body.setVisibility(View.VISIBLE); save.setEnabled(true); }
        new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton(android.R.string.ok,(d,i) -> { if (!loaded) finish(); }).show();
    }); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (!loaded) return;
        state.putString("mode",selected().name().toLowerCase(java.util.Locale.ROOT));
        var draft=new ArrayList<Bundle>();
        for (var row:rows) {
            var b=new Bundle(); b.putString("host",row.host.getText().toString()); b.putString("guest",row.guest.getText().toString());
            b.putBoolean("external",row.external.isChecked()); b.putBoolean("udp",row.protocol.getSelectedItemPosition()==1); draft.add(b);
        }
        state.putParcelableArrayList("rows",draft);
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
