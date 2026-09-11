package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;

/** Bounded, shell-reported OSC 133 history. Missing marks never imply successful execution. */
public final class TerminalCommandHistory {
    private static final int LIMIT = 128;
    private final TerminalBuffer buffer;
    private final ArrayList<Command> commands = new ArrayList<>();
    private long nextId;
    private Command current;

    TerminalCommandHistory(TerminalBuffer buffer) { this.buffer = buffer; }

    public record Snapshot(long id, String state, String command, boolean commandKnown,
            Integer exitCode, TerminalMarker.Position position, boolean outputAvailable) { }

    public record Range(TerminalMarker.Position start, TerminalMarker.Position end) { }

    public Range range(long id, boolean output) {
        for (Command command : commands) {
            if (command.id == id) return rangeBetween(output ? command.output : command.input,
                    output ? command.end : command.output);
        }
        return null;
    }

    private static Range rangeBetween(TerminalMarker start, TerminalMarker end) {
        if (start == null || end == null) return null;
        TerminalMarker.Position a = start.position(), b = end.position();
        if (a == null || b == null || a.row() > b.row()
                || a.row() == b.row() && a.column() > b.column()) return null;
        return new Range(a, b);
    }

    public List<Snapshot> snapshots() {
        ArrayList<Snapshot> result = new ArrayList<>();
        for (Command command : commands) result.add(command.snapshot());
        return List.copyOf(result);
    }

    public String state() { return current == null ? "unknown" : current.state; }

    public String output(long id) {
        for (Command command : commands) {
            if (command.id == id) return buffer.textBetween(command.output, command.end);
        }
        return null;
    }

    boolean accept(String parameters, int column, int row) {
        String[] parts = parameters.split(";", -1);
        if (parts.length == 0 || parts[0].length() != 1) return false;
        switch (parts[0]) {
            case "A":
                if (current != null && !current.state.equals("completed") && !current.state.equals("cancelled")) {
                    current.state = "incomplete";
                }
                current = new Command(++nextId, buffer.mark(column, row));
                commands.add(current);
                if (commands.size() > LIMIT) commands.remove(0).release();
                return true;
            case "B":
                if (current == null || !current.state.equals("prompt")) return false;
                current.input = buffer.mark(column, row);
                current.state = "input";
                return true;
            case "C":
                if (current == null || !current.state.equals("input")) return false;
                current.output = buffer.mark(column, row);
                String text = buffer.textBetween(current.input, current.output);
                current.commandKnown = text != null;
                if (text != null) current.text = bounded(text.strip(), 4096);
                current.state = "running";
                return true;
            case "D":
                if (current == null) return false;
                if (current.state.equals("input")) {
                    current.state = "cancelled";
                    return true;
                }
                if (!current.state.equals("running")) return false;
                Integer exit = null;
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    try {
                        exit = Integer.valueOf(parts[1]);
                        if (exit < 0 || exit > 255) return false;
                    } catch (NumberFormatException ignored) { return false; }
                }
                current.end = buffer.mark(column, row);
                current.exitCode = exit;
                current.state = "completed";
                return true;
            default: return false;
        }
    }

    public void clear() {
        for (Command command : commands) command.release();
        commands.clear();
        current = null;
    }

    static String bounded(String text, int length) {
        if (text.length() <= length) return text;
        if (Character.isHighSurrogate(text.charAt(length - 1))) length--;
        return text.substring(0, length);
    }

    private final class Command {
        final long id;
        final TerminalMarker prompt;
        TerminalMarker input, output, end;
        String state = "prompt";
        String text = "";
        boolean commandKnown;
        Integer exitCode;

        Command(long id, TerminalMarker prompt) { this.id = id; this.prompt = prompt; }

        Snapshot snapshot() {
            TerminalMarker.Position position = prompt.position();
            if (position == null && input != null) position = input.position();
            if (position == null && output != null) position = output.position();
            return new Snapshot(id, state, text, commandKnown, exitCode, position,
                    rangeBetween(output, end) != null);
        }

        void release() {
            prompt.release();
            if (input != null) input.release();
            if (output != null) output.release();
            if (end != null) end.release();
        }
    }
}
