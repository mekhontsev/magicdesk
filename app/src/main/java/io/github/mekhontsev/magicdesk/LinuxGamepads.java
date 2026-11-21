package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.VibrationEffect;
import android.system.Os;
import android.system.OsConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** A graphics-launch input lease; only Shroot consumes its private endpoint.
 * Android focus supplies the recipient, not X11/Wayland keyboard protocols. */
final class LinuxGamepads implements Closeable, InputManager.InputDeviceListener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static LinuxGamepads active;
    private final InputManager inputs;
    private final Map<Integer, AndroidGamepad> devices = new LinkedHashMap<>();
    private final ArrayList<Connection> connections = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ExecutorSocketEndpoint endpoint;
    private final Runnable updateRumble = this::renderRumble;
    private Object focusOwner;
    private boolean observing;

    static LinuxGamepads prepare(Context context, CommandExecution commands) throws IOException {
        return commands.termux == null ? new LinuxGamepads(context, commands.uid) : null;
    }

    private LinuxGamepads(Context context, int uid) throws IOException {
        inputs = context.getSystemService(InputManager.class);
        endpoint = new ExecutorSocketEndpoint(uid, 0, ShellAccess::openUnixEndpoint, socket -> {
            Connection connection = new Connection(ParcelFileDescriptor.dup(socket.getFileDescriptor()));
            MAIN.post(() -> attach(connection));
        }, error -> {
            android.util.Log.w("MagicDeskGamepads", "Gamepad endpoint closed", error);
            close();
        });
        if (closed.get()) endpoint.close();
    }

    String exports() {
        return closed.get() ? "" : "export MAGICDESK_GAMEPAD_ENDPOINT=" + ShellCommandLine.quote(endpoint.name) + "\n";
    }

    private void attach(Connection connection) {
        if (closed.get()) { connection.close(); return; }
        if (!observing && inputs != null) {
            observing = true; inputs.registerInputDeviceListener(this, MAIN);
            for (int id : inputs.getInputDeviceIds()) updateDevice(id);
        }
        connections.add(connection);
        for (AndroidGamepad pad : devices.values()) connection.send(pad.description());
        connection.send(GamepadWire.packet(GamepadWire.READY, 0, 0).array());
        connection.start();
    }

    private void detach(Connection connection) {
        connections.remove(connection);
        if (!connections.isEmpty()) { renderRumble(); return; }
        if (observing && inputs != null) inputs.unregisterInputDeviceListener(this);
        observing = false;
        neutralize(); devices.clear();
    }

    private void send(byte[] packet) {
        if (packet == null) return;
        for (Connection connection : connections) connection.send(packet);
    }

    private void updateDevice(int id) {
        removeDevice(id);
        InputDevice device = inputs.getInputDevice(id);
        if (!AndroidGamepad.accepts(device)) return;
        boolean[] used = new boolean[GamepadWire.DEVICES];
        for (AndroidGamepad pad : devices.values()) used[pad.slot] = true;
        int slot = 0; while (slot < used.length && used[slot]) slot++;
        if (slot == used.length) return;
        AndroidGamepad pad = new AndroidGamepad(device, slot);
        devices.put(id, pad); send(pad.description());
    }

    private void removeDevice(int id) {
        AndroidGamepad previous = devices.remove(id);
        if (previous != null) {
            previous.neutralize();
            for (Connection connection : connections)
                connection.rumble.values().removeIf(effect -> effect.slot == previous.slot);
            send(GamepadWire.packet(GamepadWire.REMOVE, previous.slot, 0).array());
        }
    }
    @Override public void onInputDeviceAdded(int id) { updateDevice(id); }
    @Override public void onInputDeviceChanged(int id) { updateDevice(id); }
    @Override public void onInputDeviceRemoved(int id) { removeDevice(id); }

    void focus(Object owner, boolean focused) {
        if (closed.get()) return;
        if (focused) {
            if (active != null && active != this) active.neutralize();
            active = this; focusOwner = owner;
        } else if (focusOwner == owner) {
            neutralize(); focusOwner = null;
            if (active == this) active = null;
        }
    }

    private void neutralize() {
        MAIN.removeCallbacks(updateRumble);
        devices.values().forEach(AndroidGamepad::neutralize);
        for (Connection connection : connections) connection.rumble.clear();
        send(GamepadWire.packet(GamepadWire.NEUTRAL, 0, 0).array());
    }

    boolean key(Object owner, KeyEvent event) {
        if (!AndroidGamepad.accepts(event.getDevice())) return false;
        AndroidGamepad pad = devices.get(event.getDeviceId());
        if (pad == null || !pad.handlesKey(event.getKeyCode())) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return true;
        focus(owner, true);
        send(pad.key(event));
        return true;
    }

    boolean motion(Object owner, MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || !AndroidGamepad.accepts(event.getDevice())) return false;
        AndroidGamepad pad = devices.get(event.getDeviceId());
        if (pad == null) return false;
        focus(owner, true);
        send(pad.motion(event));
        return true;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (endpoint != null) endpoint.close();
        MAIN.post(() -> {
            if (active == this) active = null;
            focusOwner = null; neutralize();
            for (Connection connection : java.util.List.copyOf(connections)) connection.close();
            if (observing && inputs != null) inputs.unregisterInputDeviceListener(this);
            observing = false; devices.clear();
        });
    }

    private final class Connection implements Closeable {
        private final ParcelFileDescriptor socket;
        private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(128);
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Map<Integer, Rumble> rumble = new LinkedHashMap<>();
        private Thread writer;
        private record Rumble(int slot, int strong, int weak, long start, long end) { }

        Connection(ParcelFileDescriptor socket) { this.socket = socket; }

        void send(byte[] packet) {
            if (!stopped.get() && !outbound.offer(packet)) close();
        }

        void start() {
            if (stopped.get()) return;
            writer = new Thread(() -> {
                try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(socket.getFileDescriptor()))) {
                    while (!stopped.get()) {
                        // EVENT_WAIT: a published input packet or close interruption; no periodic scan.
                        output.write(outbound.take());
                    }
                } catch (IOException | InterruptedException | RuntimeException error) { close(); }
            }, "GamepadEvents");
            writer.start();
            new Thread(this::read, "GamepadFeedback").start();
        }

        private void read() {
            try (var input = new java.io.DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(socket.getFileDescriptor())))) {
                byte[] bytes = new byte[GamepadWire.HEADER + 24];
                while (!stopped.get()) {
                    // EVENT_WAIT: feedback frame; shutdown terminates a blocked socket read.
                    input.readFully(bytes);
                    ByteBuffer frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    if (frame.getInt() != GamepadWire.MAGIC || frame.getInt() != GamepadWire.VERSION
                            || frame.getInt() != GamepadWire.RUMBLE) throw new IOException("Invalid gamepad feedback");
                    int slot = frame.getInt(), size = frame.getInt(), effect = frame.getInt();
                    int strong = frame.getInt(), weak = frame.getInt(), duration = frame.getInt(), delay = frame.getInt(), repeats = frame.getInt();
                    if (slot < 0 || slot >= GamepadWire.DEVICES || size != 24 || effect < 0 || effect >= 16
                            || strong < 0 || strong > 65535 || weak < 0 || weak > 65535
                            || duration < 0 || duration > 65535 || delay < 0 || delay > 65535 || repeats < 0)
                        throw new IOException("Invalid gamepad effect");
                    MAIN.post(() -> feedback(slot, effect, strong, weak, duration, delay, repeats));
                }
            } catch (IOException | RuntimeException error) {
                if (!stopped.get()) android.util.Log.d("MagicDeskGamepads", "Guest input disconnected", error);
            } finally { close(); }
        }

        private void feedback(int slot, int effect, int strong, int weak, int duration, int delay, int repeats) {
            if (stopped.get() || active != LinuxGamepads.this || closed.get()) return;
            int key = slot * 16 + effect;
            if (repeats == 0) rumble.remove(key);
            else {
                long start = android.os.SystemClock.uptimeMillis() + delay;
                long length = duration == 0 ? Long.MAX_VALUE - start : (long) duration * repeats;
                rumble.put(key, new Rumble(slot, strong, weak, start, start + length));
            }
            renderRumble();
        }

        @Override public void close() {
            if (!stopped.compareAndSet(false, true)) return;
            if (writer != null) writer.interrupt();
            try { Os.shutdown(socket.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (android.system.ErrnoException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
            MAIN.post(() -> { rumble.clear(); detach(this); });
        }
    }

    private void renderRumble() {
        MAIN.removeCallbacks(updateRumble);
        if (active != this || closed.get()) return;
        long now = android.os.SystemClock.uptimeMillis(), next = Long.MAX_VALUE;
        for (Connection connection : connections) {
            connection.rumble.values().removeIf(effect -> effect.end <= now);
            for (Connection.Rumble effect : connection.rumble.values())
                next = Math.min(next, effect.start > now ? effect.start : effect.end);
        }
        for (AndroidGamepad pad : devices.values()) {
            if (!pad.rumble) continue;
            int amplitude = 0;
            for (Connection connection : connections)
                for (Connection.Rumble effect : connection.rumble.values())
                    if (effect.slot == pad.slot && effect.start <= now)
                        amplitude = Math.max(amplitude, Math.max(effect.strong, effect.weak));
            try {
                var vibrator = pad.device.getVibratorManager().getDefaultVibrator();
                if (amplitude == 0) vibrator.cancel();
                else {
                    int strength = vibrator.hasAmplitudeControl()
                            ? Math.max(1, Math.round(amplitude * 255f / 65535)) : VibrationEffect.DEFAULT_AMPLITUDE;
                    VibrationEffect effect = next == Long.MAX_VALUE
                            ? VibrationEffect.createWaveform(new long[]{0, 1000}, new int[]{0, strength}, 0)
                            : VibrationEffect.createOneShot(Math.max(1, next - now), strength);
                    vibrator.vibrate(effect);
                }
            } catch (RuntimeException error) {
                android.util.Log.d("MagicDeskGamepads", "Controller vibration unavailable", error);
            }
        }
        // Protocol timer: advance delayed/overlapping force-feedback effects at their exact boundary.
        if (next != Long.MAX_VALUE) MAIN.postAtTime(updateRumble, next);
    }
}
