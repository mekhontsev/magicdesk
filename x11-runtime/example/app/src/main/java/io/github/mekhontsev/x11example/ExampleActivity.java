package io.github.mekhontsev.x11example;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.mekhontsev.magicdesk.x11.IX11Server;
import io.github.mekhontsev.magicdesk.x11.X11Session;
import io.github.mekhontsev.magicdesk.x11.X11Server;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal embedding example: two outputs may share a server or use independent servers. */
public final class ExampleActivity extends Activity {
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private String token;
    private boolean registered;

    private final class Connection {
        final IBinder lifetime = new Binder();
        final Map<Integer, Pane> panes = new LinkedHashMap<>();
        final X11Session session = new X11Session(getMainExecutor(), new X11Session.Listener() {
            @Override public void onFrame(X11Session.Output output, int width, int height, boolean available) {
                Pane pane = panes.get(output.id());
                if (pane != null) {
                    pane.frameWidth = width;
                    pane.frameHeight = height;
                    pane.status.setText(pane.name + (available ? " / " + width + " x " + height : " / unmapped"));
                }
            }
            @Override public void onDisconnected() {
                for (Pane pane : panes.values()) pane.status.setText(pane.name + " / disconnected");
            }
        });
        IX11Server server;
    }

    private final BroadcastReceiver ready = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!token.equals(intent.getStringExtra("token"))) return;
            Connection connection = connections.get(intent.getStringExtra("session"));
            Bundle bundle = intent.getExtras();
            if (connection == null || bundle == null) return;
            IX11Server server = IX11Server.Stub.asInterface(bundle.getBinder("server"));
            if (server == null) return;
            try {
                if ("attach".equals(intent.getStringExtra("phase"))) {
                    if (connection.server != null) return;
                    server.retain(connection.lifetime);
                    connection.server = server;
                } else if ("ready".equals(intent.getStringExtra("phase")) && connection.server != null &&
                        server.asBinder().equals(connection.server.asBinder())) {
                    connection.session.connect(server.openConnection());
                }
            } catch (RemoteException | RuntimeException e) {
                for (Pane pane : connection.panes.values()) pane.status.setText(pane.name + " / " + e);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getStringExtra("token");
        if (token == null || token.length() < 24) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(layout);
        registerReceiver(ready, new IntentFilter(X11Server.ACTION), RECEIVER_EXPORTED);
        registered = true;
        for (String name : new String[] {"left", "right"}) {
            String identity = getIntent().getStringExtra(name + "Session");
            if (identity == null) identity = name;
            Connection connection = connections.get(identity);
            if (connection == null) {
                connection = new Connection();
                connections.put(identity, connection);
            }
            String window = getIntent().getStringExtra(name + "Window");
            X11Session.Output output = connection.session.openOutput(window == null ? 0 : Long.decode(window));
            Pane pane = new Pane(name + " / " + identity, output);
            connection.panes.put(output.id(), pane);
            layout.addView(pane, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!token.equals(intent.getStringExtra("token"))) return;
        Connection connection = connections.get(intent.getStringExtra("session"));
        if (connection == null || connection.server == null) return;
        String command = intent.getStringExtra("command");
        try {
            if ("reconnect".equals(command)) connection.session.connect(connection.server.openConnection());
            else if ("stop".equals(command)) connection.server.stop();
            else if ("recreate".equals(command)) {
                for (Pane pane : connection.panes.values().toArray(new Pane[0])) {
                    pane.output.close();
                    pane.output = connection.session.openOutput(pane.output.windowId());
                    if (pane.surface.getHolder().getSurface().isValid())
                        pane.output.setSurface(pane.surface.getHolder().getSurface(), pane.surface.getWidth(), pane.surface.getHeight());
                }
                Pane[] panes = connection.panes.values().toArray(new Pane[0]);
                connection.panes.clear();
                for (Pane pane : panes) connection.panes.put(pane.output.id(), pane);
            }
        } catch (RemoteException e) { throw new IllegalStateException(e); }
    }

    private final class Pane extends LinearLayout implements SurfaceHolder.Callback {
        final String name;
        X11Session.Output output;
        final SurfaceView surface;
        final TextView status;
        int frameWidth, frameHeight;

        Pane(String name, X11Session.Output output) {
            super(ExampleActivity.this);
            this.name = name;
            this.output = output;
            setOrientation(VERTICAL);
            status = new TextView(ExampleActivity.this);
            status.setText(name + " / waiting");
            addView(status);
            surface = new SurfaceView(ExampleActivity.this);
            surface.setFocusable(true);
            surface.setFocusableInTouchMode(true);
            surface.getHolder().addCallback(this);
            surface.setOnFocusChangeListener((v, focused) -> { if (focused) this.output.focus(); });
            surface.setOnKeyListener((v, key, event) -> {
                if (key == KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
                    this.output.key(key, event.getScanCode(), event.getAction() == KeyEvent.ACTION_DOWN);
                    return true;
                }
                return false;
            });
            surface.setOnTouchListener((v, event) -> {
                surface.requestFocus();
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP ||
                        action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_MOVE) {
                    sendPointer(event, action == MotionEvent.ACTION_MOVE ? 0 : 1, action == MotionEvent.ACTION_DOWN);
                    return true;
                }
                return false;
            });
            surface.setOnGenericMotionListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) { sendPointer(event, 0, false); return true; }
                if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS || event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                    int button = event.getActionButton() == MotionEvent.BUTTON_SECONDARY ? 3 :
                            event.getActionButton() == MotionEvent.BUTTON_TERTIARY ? 2 : 1;
                    sendPointer(event, button, event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS);
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    int button = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0 ? 4 : 5;
                    sendPointer(event, button, true);
                    sendPointer(event, button, false);
                    return true;
                }
                return false;
            });
            addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        private void sendPointer(MotionEvent event, int button, boolean down) {
            if (frameWidth <= 0 || frameHeight <= 0) return;
            float scale = Math.min(surface.getWidth() / (float)frameWidth, surface.getHeight() / (float)frameHeight);
            float width = frameWidth * scale, height = frameHeight * scale;
            output.pointer((event.getX() - (surface.getWidth() - width) / 2) / width,
                    (event.getY() - (surface.getHeight() - height) / 2) / height, button, down);
        }
        @Override public void surfaceCreated(SurfaceHolder holder) {}
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            output.setSurface(holder.getSurface(), width, height);
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { output.setSurface(null, 0, 0); }
    }

    @Override public void onDestroy() {
        if (registered) unregisterReceiver(ready);
        for (Connection connection : connections.values()) {
            connection.session.close();
            if (connection.server != null) try { connection.server.stop(); } catch (RemoteException ignored) {}
        }
        connections.clear();
        super.onDestroy();
    }
}
