package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Read-only keyboard choices, independent of the UI and framework objects. */
record HardwareKeyboardLayouts(int physicalDevices, List<Choice> choices) {
    record Choice(String descriptor, String label, boolean selected) { }

    HardwareKeyboardLayouts {
        choices = List.copyOf(choices);
    }

    String toJson() throws JSONException {
        final JSONArray values = new JSONArray();
        for (final Choice choice : choices) values.put(new JSONObject()
                .put("descriptor", choice.descriptor()).put("label", choice.label())
                .put("selected", choice.selected()));
        return new JSONObject().put("physicalDevices", physicalDevices).put("choices", values).toString();
    }

    static HardwareKeyboardLayouts fromJson(String json) throws JSONException {
        final JSONObject value = new JSONObject(json);
        final JSONArray entries = value.getJSONArray("choices");
        final List<Choice> choices = new ArrayList<>();
        for (int index = 0; index < entries.length(); index++) {
            final JSONObject entry = entries.getJSONObject(index);
            choices.add(new Choice(entry.getString("descriptor"), entry.getString("label"),
                    entry.getBoolean("selected")));
        }
        return new HardwareKeyboardLayouts(value.getInt("physicalDevices"), choices);
    }
}
