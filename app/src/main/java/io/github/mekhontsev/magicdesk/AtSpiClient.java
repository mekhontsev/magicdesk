package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Toolkit protocol adapter. All addresses belong to the captured guest accessibility bus. */
final class AtSpiClient {
    static final String PREFIX = "org.a11y.atspi.", ROOT = "/org/a11y/atspi/accessible/root";
    private static final String REGISTRY = "org.a11y.atspi.Registry", REGISTRY_PATH = "/org/a11y/atspi/registry";
    private static final String RULE = "type='signal',interface='org.a11y.atspi.Event.Object'";
    private static final String WINDOW_RULE = "type='signal',interface='org.a11y.atspi.Event.Window'";
    private static final String FOCUS_RULE = "type='signal',interface='org.a11y.atspi.Event.Focus'";
    private static final String OWNER_RULE = "type='signal',interface='org.freedesktop.DBus',member='NameOwnerChanged'";
    final LinuxBusConnection bus;
    private boolean observing, modernRegistry = true;
    private final Set<String> registeredEvents = new LinkedHashSet<>(), matchRules = new LinkedHashSet<>();
    private Ref root;
    record Ref(String destination, String path) { }
    record Node(Ref ref, JSONObject data, JSONArray children, List<String> actionNames, String identity, boolean textComplete) { }

    AtSpiClient(LinuxBusConnection bus) { this.bus = bus; }

    void observe() throws IOException, JSONException { observe(deadline()); }

    synchronized void observe(long deadline) throws IOException, JSONException {
        if (observing) return;
        // Keep acknowledged registrations across a short observation deadline.
        // The next observation resumes setup; explicit release owns cleanup.
        for (String rule : List.of(RULE, OWNER_RULE, WINDOW_RULE, FOCUS_RULE)) {
            if (!matchRules.contains(rule)) {
                bus.request(2, 2, new JSONObject().put("rule", rule), remaining(deadline));
                matchRules.add(rule);
            }
        }
        for (String event : List.of("object:", "window:", "focus:")) {
            if (!registeredEvents.contains(event)) {
                try { registry("RegisterEvent", modernRegistry
                        ? args(v("s", event), v("as", new JSONArray()), v("s", ""))
                        : args(v("s", event), v("as", new JSONArray())), deadline); }
                catch (LinuxBusConnection.BusError error) {
                    if (!error.name.equals("org.freedesktop.DBus.Error.InvalidArgs")) throw error;
                    modernRegistry = false;
                    registry("RegisterEvent", args(v("s", event), v("as", new JSONArray())), deadline);
                }
                registeredEvents.add(event);
            }
        }
        String owner = scalar(bus.call(2, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                "GetNameOwner", args(v("s", REGISTRY)), remaining(deadline)), 0);
        root = new Ref(owner, ROOT); observing = true;
    }

    synchronized void release() {
        for (String event : registeredEvents) try {
            registry("DeregisterEvent", modernRegistry ? args(v("s", event), v("s", "")) : args(v("s", event)), deadline());
        } catch (IOException | JSONException ignored) { }
        registeredEvents.clear();
        for (String rule : matchRules) try {
            bus.request(3, 2, new JSONObject().put("rule", rule), 1000);
        } catch (IOException | JSONException ignored) { }
        matchRules.clear(); observing = false; root = null;
    }

    List<Ref> applications(long deadline) throws IOException, JSONException {
        observe(deadline);
        return references(array(call(root, "Accessible", "GetChildren", new JSONArray(), deadline), 0));
    }

    Node read(Ref ref, long deadline) throws IOException, JSONException {
        JSONObject props;
        try { props = dictionary(call(ref, "org.freedesktop.DBus.Properties", "GetAll", args(v("s", PREFIX + "Accessible")), deadline)); }
        catch (LinuxBusConnection.BusError error) {
            if (!missingMember(error)) throw error;
            props = new JSONObject();
            for (String name : List.of("Name", "Description", "Parent")) props.put(name,
                    unwrap(call(ref, "org.freedesktop.DBus.Properties", "Get", args(v("s", PREFIX + "Accessible"), v("s", name)), deadline).getJSONObject(0)));
        }
        int role = Integer.parseInt(scalar(call(ref, "Accessible", "GetRole", new JSONArray(), deadline), 0));
        String roleName = scalar(call(ref, "Accessible", "GetRoleName", new JSONArray(), deadline), 0);
        JSONArray states = array(call(ref, "Accessible", "GetState", new JSONArray(), deadline), 0);
        if (state(states, 6)) throw new IOException("AT-SPI object is defunct");
        JSONArray interfaces = array(call(ref, "Accessible", "GetInterfaces", new JSONArray(), deadline), 0);
        Set<String> available = new HashSet<>();
        for (int i = 0; i < interfaces.length(); ++i) available.add(scalar(interfaces, i));
        boolean password = role == 40;
        String name = property(props, "Name", ""), description = property(props, "Description", "");
        String text = name;
        boolean textComplete = true;
        JSONArray actions = new JSONArray();
        List<String> actionNames = new ArrayList<>();
        if (available.contains(PREFIX + "Action")) {
            JSONArray countReply = call(ref, "org.freedesktop.DBus.Properties", "Get",
                    args(v("s", PREFIX + "Action"), v("s", "NActions")), deadline);
            int count = Integer.parseInt(unwrap(countReply.getJSONObject(0)).getString("value"));
            if (count < 0 || count > 128) throw new IOException("AT-SPI action limit");
            for (int i = 0; i < count; ++i) {
                String actionName = scalar(call(ref, "Action", "GetName", args(v("i", i)), deadline), 0);
                actionNames.add(actionName); actions.put("atspi:" + actionName);
                if (actionName.equals("click")) actions.put("click");
            }
        }
        if (available.contains(PREFIX + "Component") && state(states, 11)) actions.put("focus");
        if (available.contains(PREFIX + "EditableText") && state(states, 7)) actions.put("set_text");
        if (available.contains(PREFIX + "Text") && !password) {
            int count = Integer.parseInt(unwrap(call(ref, "org.freedesktop.DBus.Properties", "Get",
                    args(v("s", PREFIX + "Text"), v("s", "CharacterCount")), deadline).getJSONObject(0)).getString("value"));
            if (count < 0) throw new IOException("Invalid AT-SPI character count");
            textComplete = count <= 32768;
            text = scalar(call(ref, "Text", "GetText", args(v("i", 0), v("i", Math.min(count, 32768))), deadline), 0);
            if (textComplete) actions.put("select_text");
        }
        JSONArray children = array(call(ref, "Accessible", "GetChildren", new JSONArray(), deadline), 0);
        JSONObject data = new JSONObject().put("backend", "atspi").put("applicationId", ref.destination)
                .put("accessibleId", property(props, "AccessibleId", "")).put("role", roleName).put("roleId", role)
                .put("name", password ? JSONObject.NULL : name).put("text", password ? JSONObject.NULL : text)
                .put("description", password ? JSONObject.NULL : description).put("password", password)
                .put("enabled", state(states, 24)).put("visible", state(states, 25) && state(states, 30))
                .put("focused", state(states, 12)).put("selected", state(states, 23)).put("checked", state(states, 4))
                .put("editable", state(states, 7)).put("actions", actions).put("childCount", children.length())
                .put("interfaces", interfaces).put("textComplete", textComplete);
        if (available.contains(PREFIX + "Component")) {
            JSONArray rect = array(call(ref, "Component", "GetExtents", args(v("u", 0)), deadline), 0);
            int x = integer(rect, 0), y = integer(rect, 1), width = integer(rect, 2), height = integer(rect, 3);
            data.put("bounds", new JSONObject().put("left", x).put("top", y).put("right", (long)x + width)
                    .put("bottom", (long)y + height).put("space", "linux_screen_pixels"));
        }
        String identity = role + "\n" + props.opt("Parent") + "\n" + property(props, "AccessibleId", "")
                + "\n" + (password ? "" : name + "\n" + description + "\n" + text);
        return new Node(ref, data, children, List.copyOf(actionNames), identity, textComplete);
    }

    boolean perform(Node saved, JSONObject args) throws IOException, JSONException {
        long deadline = deadline();
        Node live = read(saved.ref, deadline);
        if (!saved.identity.equals(live.identity)) throw new IllegalArgumentException("UI element changed; inspect again");
        String action = args.getString("action");
        JSONArray actions = live.data.getJSONArray("actions");
        boolean supported = false;
        for (int i = 0; i < actions.length(); ++i) supported |= action.equals(actions.getString(i));
        if (!supported || !live.data.getBoolean("enabled") || !live.data.getBoolean("visible"))
            throw new IllegalArgumentException("UI element does not currently support " + action);
        JSONArray result;
        if (action.equals("focus")) result = call(live.ref, "Component", "GrabFocus", new JSONArray(), deadline);
        else if (action.equals("set_text")) {
            String text = args.getString("text");
            if (text.length() > 32768) throw new IllegalArgumentException("Text exceeds 32768 UTF-16 units");
            result = call(live.ref, "EditableText", "SetTextContents", args(v("s", text)), deadline);
        } else if (action.equals("select_text")) {
            String text = live.data.getString("text");
            int start = UiSelector.integer(args, "start", -1, 0, text.length());
            int end = UiSelector.integer(args, "end", -1, 0, text.length());
            if (start < 0 || end < start || splits(text, start) || splits(text, end)) throw new IllegalArgumentException("Invalid text selection");
            int count = Integer.parseInt(scalar(call(live.ref, "Text", "GetNSelections", new JSONArray(), deadline), 0));
            JSONArray selection = args(v("i", text.codePointCount(0, start)), v("i", text.codePointCount(0, end)));
            if (count > 0) selection = args(v("i", 0), selection.getJSONObject(0), selection.getJSONObject(1));
            result = call(live.ref, "Text", count > 0 ? "SetSelection" : "AddSelection", selection, deadline);
        } else {
            String name = action.equals("click") ? "click" : action.substring("atspi:".length());
            int index = live.actionNames.indexOf(name);
            if (index < 0 || live.actionNames.lastIndexOf(name) != index) throw new IllegalArgumentException("Ambiguous AT-SPI action");
            result = call(live.ref, "Action", "DoAction", args(v("i", index)), deadline);
        }
        return "true".equals(scalar(result, 0));
    }

    static List<Ref> references(JSONArray children) throws JSONException, IOException {
        List<Ref> result = new ArrayList<>();
        for (int i = 0; i < children.length(); ++i) {
            JSONArray pair = children.getJSONObject(i).getJSONArray("value");
            String destination = scalar(pair, 0), path = scalar(pair, 1);
            if (path.equals("/org/a11y/atspi/null")) continue;
            if (!destination.startsWith(":")) throw new IOException("AT-SPI reference lacks a unique application owner");
            result.add(new Ref(destination, path));
        }
        return result;
    }
    JSONArray call(Ref ref, String iface, String member, JSONArray args, long deadline) throws IOException, JSONException {
        String name = iface.contains(".") ? iface : PREFIX + iface;
        int timeout = Math.min(1000, remaining(deadline));
        try { return bus.call(2, ref.destination, ref.path, name, member, args, timeout); }
        catch (LinuxBusConnection.BusError error) {
            throw new LinuxBusConnection.BusError(error.name,
                    ref.destination + " " + ref.path + " " + name + "." + member + " (" + timeout + " ms): " + error.getMessage());
        }
    }
    private void registry(String member, JSONArray args, long deadline) throws IOException, JSONException {
        bus.call(2, REGISTRY, REGISTRY_PATH, REGISTRY, member, args, remaining(deadline));
    }
    static final class ObservationDeadline extends IOException {
        ObservationDeadline() { super("AT-SPI observation deadline"); }
    }
    static int remaining(long deadline) throws ObservationDeadline {
        long remaining = deadline - android.os.SystemClock.uptimeMillis();
        if (remaining <= 0) throw new ObservationDeadline();
        return (int)Math.min(3000, remaining);
    }
    static long deadline() { return android.os.SystemClock.uptimeMillis() + 3000; }
    static JSONArray args(JSONObject... values) { JSONArray array = new JSONArray(); for (JSONObject value : values) array.put(value); return array; }
    static JSONObject v(String type, Object value) throws JSONException { return LinuxBusWire.value(type, value); }
    static String scalar(JSONArray values, int index) throws JSONException { return values.getJSONObject(index).getString("value"); }
    static int integer(JSONArray values, int index) throws JSONException { return Integer.parseInt(scalar(values, index)); }
    static JSONArray array(JSONArray values, int index) throws JSONException { return values.getJSONObject(index).getJSONArray("value"); }
    static JSONObject unwrap(JSONObject value) throws JSONException { return value.getString("type").equals("v") ? value.getJSONObject("value") : value; }
    static JSONObject dictionary(JSONArray reply) throws JSONException {
        JSONObject result = new JSONObject(); JSONArray entries = array(reply, 0);
        for (int i = 0; i < entries.length(); ++i) {
            JSONArray pair = entries.getJSONObject(i).getJSONArray("value");
            result.put(scalar(pair, 0), unwrap(pair.getJSONObject(1)));
        }
        return result;
    }
    private static String property(JSONObject props, String name, String fallback) throws JSONException {
        JSONObject property = props.optJSONObject(name); return property == null ? fallback : property.getString("value");
    }
    private static boolean state(JSONArray states, int bit) throws JSONException {
        return states.length() > bit / 32 && (Long.parseLong(scalar(states, bit / 32)) & (1L << bit % 32)) != 0;
    }
    private static boolean splits(String text, int at) { return at > 0 && at < text.length() && Character.isHighSurrogate(text.charAt(at - 1)) && Character.isLowSurrogate(text.charAt(at)); }
    static boolean missingMember(LinuxBusConnection.BusError error) {
        return Set.of("org.freedesktop.DBus.Error.UnknownMethod", "org.freedesktop.DBus.Error.UnknownInterface",
                "org.freedesktop.DBus.Error.UnknownProperty").contains(error.name);
    }
}
