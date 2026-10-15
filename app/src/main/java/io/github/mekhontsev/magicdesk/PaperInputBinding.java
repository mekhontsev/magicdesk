package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.json.JSONException;

/** A key chord, independent of keyboard layout and Android event ownership. */
record PaperInputBinding(int key, int modifiers, PaperInputAction action) {
    static final PaperInputBinding NONE = new PaperInputBinding(0, 0, PaperInputAction.SYSTEM);

    boolean matches(int keyCode, int modifierMask) {
        return key > 0 && key == keyCode && modifiers == modifierMask;
    }

    static int modifiers(boolean ctrl, boolean alt, boolean shift, boolean meta) {
        return (ctrl ? 1 : 0) | (alt ? 2 : 0) | (shift ? 4 : 0) | (meta ? 8 : 0);
    }

    static PaperInputBinding fromJson(JSONObject json) {
        if (json == null) return NONE;
        int key = json.optInt("key", 0), modifiers = json.optInt("modifiers", 0);
        PaperInputAction action = PaperInputAction.parse(json.optString("action"));
        if (key <= 0 || modifiers < 0 || modifiers > 15 || action == PaperInputAction.SYSTEM) return NONE;
        return new PaperInputBinding(key, modifiers, action);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("key", key).put("modifiers", modifiers).put("action", action.name());
    }
}
