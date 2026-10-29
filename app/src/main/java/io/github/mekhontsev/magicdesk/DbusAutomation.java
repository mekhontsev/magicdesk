package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Authorized diagnostic adapter. It never discovers buses by scanning another process's environment. */
final class DbusAutomation implements AutoCloseable {
    private record Subscription(LinuxBusConnection connection, int bus, String rule) { }
    private final Map<String, Subscription> subscriptions = new HashMap<>();

    JSONObject execute(String operation, JSONObject args) throws IOException, JSONException {
        LinuxBusConnection connection = LinuxAutomationLaunch.target(args.getString("sessionId")).bus();
        int bus = LinuxBusConnection.bus(args.optString("bus", "session"));
        int timeout = UiSelector.integer(args, "timeoutMillis", 3000, 1, 10000);
        switch (operation) {
            case "dbus.list": return new JSONObject().put("values", connection.call(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus", "ListNames", new JSONArray(), timeout));
            case "dbus.introspect": return new JSONObject().put("values", connection.call(bus, args.getString("destination"), args.getString("path"),
                    "org.freedesktop.DBus.Introspectable", "Introspect", new JSONArray(), timeout));
            case "dbus.call": return connection.request(1, bus, args, timeout);
            case "dbus.subscribe": {
                String rule = args.getString("rule");
                if (rule.length() > 4096) throw new IllegalArgumentException("D-Bus match rule too long");
                synchronized (subscriptions) {
                    subscriptions.values().removeIf(value -> !value.connection.alive());
                    if (subscriptions.size() >= 32) throw new IOException("D-Bus subscription limit");
                    connection.request(2, bus, args, timeout);
                    String id = UUID.randomUUID().toString();
                    subscriptions.put(id, new Subscription(connection, bus, rule));
                    return new JSONObject().put("subscriptionId", id).put("sequence", connection.generation());
                }
            }
            case "dbus.events": {
                long after = args.optLong("after", 0);
                if (after < 0 || after > connection.generation()) throw new IllegalArgumentException("Invalid event sequence");
                if (args.optBoolean("wait") && after == connection.generation()) {
                    try {
                        // EVENT_WAIT: signal or peer shutdown; expiry returns an empty event page.
                        connection.after(after).get(timeout, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException ignored) { }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("D-Bus wait interrupted", error); }
                    catch (ExecutionException error) { throw new IOException("D-Bus event stream ended", error); }
                }
                return connection.events(after);
            }
            case "dbus.release": {
                if (args.has("fd") == args.has("subscriptionId")) throw new IllegalArgumentException("Select exactly one subscriptionId or fd");
                if (args.has("fd")) connection.request(5, bus, args, timeout);
                else synchronized (subscriptions) {
                    String id = args.getString("subscriptionId"); Subscription subscription = subscriptions.get(id);
                    if (subscription == null || subscription.connection != connection) throw new IllegalArgumentException("Unknown D-Bus subscription");
                    connection.request(3, subscription.bus, new JSONObject().put("rule", subscription.rule), timeout);
                    subscriptions.remove(id);
                }
                return new JSONObject().put("released", true);
            }
            default: throw new IllegalArgumentException("Unknown D-Bus operation");
        }
    }
    @Override public void close() {
        List<Subscription> owned;
        synchronized (subscriptions) { owned = List.copyOf(subscriptions.values()); subscriptions.clear(); }
        for (Subscription subscription : owned) try {
            subscription.connection.request(3, subscription.bus, new JSONObject().put("rule", subscription.rule), 1000);
        } catch (IOException | JSONException ignored) { }
    }
}
