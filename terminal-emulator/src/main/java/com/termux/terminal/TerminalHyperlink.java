package com.termux.terminal;

/** An OSC 8 target attached to painted cells, not an instruction to open a URI. */
public record TerminalHyperlink(String uri, String id) {
    static TerminalHyperlink parse(String parameters) {
        int separator = parameters.indexOf(';');
        if (separator < 0) return null;
        String uri = parameters.substring(separator + 1);
        if (uri.isEmpty() || uri.length() > 4096 || hasControls(uri)) return null;
        String id = "";
        for (String parameter : parameters.substring(0, separator).split(":")) {
            if (parameter.startsWith("id=")) id = parameter.substring(3);
        }
        if (id.length() > 256 || hasControls(id)) return null;
        return new TerminalHyperlink(uri, id);
    }

    static boolean hasControls(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) return true;
        }
        return false;
    }
}
