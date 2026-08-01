package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public final class ConsoleInputRoutingCommand {
    private static final long VIRTUAL_KEYBOARD_TIMEOUT_MILLIS = 3_000L;
    private static final long VIRTUAL_KEYBOARD_POLL_MILLIS = 100L;
    private static final String VIRTUAL_KEYBOARD_LOCATION_PREFIX =
            "magicdesk-shizuku-keyboard-";

    private ConsoleInputRoutingCommand() {
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
            if (args.length != 1) {
                throw new IllegalArgumentException(
                        "expected virtual keyboard count");
            }
            final int expectedVirtualKeyboards =
                    Integer.parseInt(args[0]);
            if (expectedVirtualKeyboards <= 0) {
                throw new IllegalArgumentException(
                        "virtual keyboard count must be positive");
            }
            final List<ConsoleKeyboardDevice> keyboards =
                    waitForVirtualKeyboards(expectedVirtualKeyboards);
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
                            + routing.keyboardAssociationCount()
                            + " virtualKeyboards="
                            + countVirtualKeyboards(keyboards));
            System.out.flush();
            processCommands(routing);
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

    private static List<ConsoleKeyboardDevice> waitForVirtualKeyboards(
            final int expectedCount)
            throws IOException, InterruptedException {
        final long deadline = SystemClock.uptimeMillis()
                + VIRTUAL_KEYBOARD_TIMEOUT_MILLIS;
        List<ConsoleKeyboardDevice> keyboards;
        do {
            keyboards =
                    ConsoleInputDeviceDiscovery.findRoutableKeyboards();
            if (countVirtualKeyboards(keyboards) == expectedCount) {
                return keyboards;
            }
            Thread.sleep(VIRTUAL_KEYBOARD_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "Expected " + expectedCount
                        + " MagicDesk virtual keyboards in EventHub");
    }

    private static int countVirtualKeyboards(
            final List<ConsoleKeyboardDevice> keyboards) {
        int count = 0;
        for (final ConsoleKeyboardDevice keyboard : keyboards) {
            if (keyboard.location.startsWith(
                    VIRTUAL_KEYBOARD_LOCATION_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    private static void processCommands(
            final ConsoleInputRoutingSession routing) {
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (!"refresh".equals(line)) {
                    continue;
                }
                try {
                    final int added = routing.refreshAssociations();
                    System.out.println(
                            "MAGICDESK_SHIZUKU_ROUTING_REFRESHED"
                                    + " added=" + added);
                    System.out.flush();
                } catch (Exception error) {
                    System.err.println(
                            "MAGICDESK_SHIZUKU_ROUTING_REFRESH_ERROR "
                                    + error);
                }
            }
        } catch (IOException ignored) {
            // A broken owner pipe is also a stop request.
        }
    }
}
