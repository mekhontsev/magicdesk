package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ShizukuInputRoutingCommand {
    private static final long HEARTBEAT_TIMEOUT_MILLIS = 6_000L;
    private static final long VIRTUAL_KEYBOARD_TIMEOUT_MILLIS = 3_000L;
    private static final long VIRTUAL_KEYBOARD_POLL_MILLIS = 100L;
    private static final String VIRTUAL_KEYBOARD_LOCATION =
            "magicdesk-shizuku-keyboard";

    private ShizukuInputRoutingCommand() {
    }

    public static void main(final String[] args) {
        ConsoleInputRoutingSession routing = null;
        try {
            if (args.length == 1
                    && "cleanup-stale".equals(args[0])) {
                final int cleaned =
                        ConsoleInputRoutingSession
                                .cleanupStaleAssociations();
                System.out.println(
                        "MAGICDESK_SHIZUKU_ROUTING_CLEAN"
                                + " associations=" + cleaned);
                System.out.flush();
                return;
            }
            final List<ConsoleKeyboardDevice> keyboards =
                    waitForVirtualKeyboard();
            final List<ConsoleMouseDevice> mice =
                    ConsoleInputDeviceDiscovery.findMice();
            routing = ConsoleInputRoutingSession.open(keyboards, mice);
            final ConsoleInputRoutingSession openedRouting = routing;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    openedRouting::close,
                    "MagicDeskInputRoutingCleanup"));

            System.out.println(
                    "MAGICDESK_SHIZUKU_ROUTING_READY display="
                            + routing.consoleDisplayId()
                            + " associations="
                            + routing.associationCount()
                            + " keyboards="
                            + routing.keyboardAssociationCount());
            System.out.flush();
            waitForHeartbeats();
        } catch (Exception error) {
            System.err.println(
                    "MAGICDESK_SHIZUKU_ROUTING_ERROR " + error);
            error.printStackTrace(System.err);
            System.exit(1);
        } finally {
            if (routing != null) {
                routing.close();
            }
        }
    }

    private static List<ConsoleKeyboardDevice> waitForVirtualKeyboard()
            throws IOException, InterruptedException {
        final long deadline = SystemClock.uptimeMillis()
                + VIRTUAL_KEYBOARD_TIMEOUT_MILLIS;
        List<ConsoleKeyboardDevice> keyboards;
        do {
            keyboards =
                    ConsoleInputDeviceDiscovery.findRoutableKeyboards();
            for (final ConsoleKeyboardDevice keyboard : keyboards) {
                if (VIRTUAL_KEYBOARD_LOCATION.equals(
                        keyboard.location)) {
                    return keyboards;
                }
            }
            Thread.sleep(VIRTUAL_KEYBOARD_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "MagicDesk virtual keyboard did not appear in EventHub");
    }

    private static void waitForHeartbeats()
            throws InterruptedException {
        final AtomicBoolean inputOpen = new AtomicBoolean(true);
        final AtomicLong lastHeartbeat = new AtomicLong(
                SystemClock.uptimeMillis());
        final Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    lastHeartbeat.set(SystemClock.uptimeMillis());
                }
            } catch (IOException ignored) {
                // A broken parent pipe is also a stop request.
            } finally {
                inputOpen.set(false);
            }
        }, "MagicDeskInputRoutingHeartbeat");
        reader.setDaemon(true);
        reader.start();

        while (inputOpen.get()
                && SystemClock.uptimeMillis() - lastHeartbeat.get()
                        <= HEARTBEAT_TIMEOUT_MILLIS) {
            Thread.sleep(250L);
        }
    }
}
