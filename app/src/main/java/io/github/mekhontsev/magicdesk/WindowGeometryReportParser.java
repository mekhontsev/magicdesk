package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Diagnostic excerpts only: never supplies window policy or interprets missing transforms. */
final class WindowGeometryReportParser {
    static final int OUTPUT_LIMIT = 16 * 1024;
    private static final String NUMBER = "[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?";
    private static final String COORDINATES = "[\\d\\s.,+eE-]+";
    private static final Pattern TASK = Pattern.compile("(?:\\b[Tt]ask[= ]|\\btaskId=)(\\d+)(?!\\d)");
    private static final Pattern COMPONENT = Pattern.compile(
            "(?<![\\w.])([A-Za-z][\\w]*(?:\\.[\\w]+)+)/[\\w.$]+");
    private static final Pattern WINDOW = Pattern.compile("^\\s*Window #\\d+ Window\\{.*");
    private static final Pattern LAYER = Pattern.compile("^\\s*Layer \\[(\\d+)] (.*)");
    private static final Pattern INPUT = Pattern.compile("^\\s*\\d+: name=.*");
    private static final Pattern TASK_ID = Pattern.compile("\\btaskId=(\\d+)\\b");
    private static final Pattern DISPLAY = Pattern.compile("^\\s*Display: (\\d+)\\s*$");
    private static final Pattern STACK = Pattern.compile("^LayerStack=(\\d+)\\s*$");
    private static final Pattern FIELD = Pattern.compile(
            "\\b([A-Za-z][\\w]*(?:\\(\\))?)=("
                    + "(?:\\[" + COORDINATES + "]){1,2}"
                    + "|(?:Rect|Point)\\(" + COORDINATES + "\\)"
                    + "|\\{" + COORDINATES + "}"
                    + "|<empty>|true|false|" + NUMBER + ")(?![\\w.])");
    private static final Pattern TRANSFORM = Pattern.compile(
            "\\btoDisplayTransform=\\{((?:(?:scale|translate|rotate|x|y|tx|ty|dsdx|dtdx|dsdy|dtdy)"
                    + "|[\\d\\s=.,+eE-])*)}");
    private static final Pattern MATRIX_ROW = Pattern.compile(
            "^\\s*" + NUMBER + "\\s+" + NUMBER + "\\s+" + NUMBER + "\\s*$");
    private static final Pattern MATRIX_HEADER = Pattern.compile(
            "^\\s*transform(?: \\([A-Z_0-9 |]+\\)){1,2}\\s*$");
    private static final Set<String> KEYS = Set.of(
            "mDisplayId", "displayId", "taskId", "id", "ownerUid", "ownerPid", "mOwnerUid",
            "alpha", "logicalSize", "frame", "parent", "display", "last", "surface",
            "mHasSurface", "mHaveFrame", "mObscured", "isReadyForDisplay()", "isOnScreen",
            "isVisible", "shown", "mLastHidden", "mXOffset", "mYOffset", "touchCropId",
            "touchableRegion", "mShownAlpha", "w", "h", "mRequestedWidth", "mRequestedHeight",
            "mDsDx", "mDtDx", "mDsDy", "mDtDy", "dsdx", "dtdx", "dsdy", "dtdy");

    private WindowGeometryReportParser() { }

    record Target(int taskId, String packageName) { }

    static String windows(String dump, List<Target> targets) {
        Excerpt out = new Excerpt();
        List<String> block = null;
        for (String line : dump.split("\\R")) {
            if (WINDOW.matcher(line).matches()) {
                appendWindow(out, block, targets);
                block = new ArrayList<>();
            } else if (block != null && !line.isBlank() && !line.startsWith("    ")) {
                appendWindow(out, block, targets);
                block = null;
            }
            if (block != null) block.add(line);
        }
        appendWindow(out, block, targets);
        return out.finish();
    }

    private static void appendWindow(Excerpt out, List<String> block, List<Target> targets) {
        if (block == null) return;
        Set<Integer> matches = new LinkedHashSet<>();
        boolean exact = false;
        for (int i = 1; i < block.size(); i++) {
            Matcher task = TASK_ID.matcher(block.get(i));
            if (!task.find()) continue;
            exact = true;
            for (Target target : targets) {
                if (task.group(1).equals(Integer.toString(target.taskId))) matches.add(target.taskId);
            }
            break;
        }
        if (!exact) matches = candidates(block.get(0), targets);
        if (matches.isEmpty()) return;
        out.record("window tasks=" + matches + " association=" + (exact ? "taskId" : "name-candidate"));
        for (int i = 1; i < block.size(); i++) {
            String line = block.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("mFullConfiguration=")) out.line("  configuration=current");
            if (trimmed.startsWith("mLastReportedConfiguration=")) out.line("  configuration=last-reported");
            if (trimmed.startsWith("mLastReportedActivityWindowInfo=")) out.line("  activity-window=last-reported");
            if (!line.contains("Surface(name=") && !line.contains("WindowStateAnimator{")) out.geometry(line);
        }
    }

    static String surfaces(String dump, List<Target> targets) {
        Excerpt out = new Excerpt();
        String section = null;
        String stack = "unknown";
        boolean selected = false;
        for (String line : dump.split("\\R")) {
            if (line.equals("Composition list (bottom to top)") || line.equals("Input list")) {
                section = line.startsWith("Composition") ? "composition" : "input";
                selected = false;
                stack = "unknown";
                continue;
            }
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))
                    && !STACK.matcher(line).matches()) {
                section = null;
                selected = false;
            }
            if (section == null) continue;
            Matcher layerStack = STACK.matcher(line);
            if (layerStack.matches()) {
                stack = layerStack.group(1);
                selected = false;
                continue;
            }
            Matcher layer = LAYER.matcher(line);
            if (layer.matches()) {
                Set<Integer> matches = candidates(layer.group(2), targets);
                selected = !matches.isEmpty();
                if (selected) out.record("layer=" + layer.group(1) + " section=" + section
                        + " layerStack=" + stack + " candidateTasks=" + matches);
            } else if (selected && line.startsWith("    ")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("visible reason=")) { out.line("  visible=true"); continue; }
                if (trimmed.startsWith("invisible reason=")) { out.line("  visible=false"); continue; }
                out.geometry(line);
                Matcher transform = TRANSFORM.matcher(line);
                if (transform.find()) out.line("  toDisplayTransform={" + transform.group(1) + "}");
            } else if (!line.isBlank()) {
                selected = false;
            }
        }
        return out.finish();
    }

    static String input(String dump, List<Target> targets) {
        Excerpt out = new Excerpt();
        boolean selected = false;
        boolean displayHeader = false;
        int matrixRows = 0;
        String display = "unknown";
        for (String line : TaskInputWindowParser.currentDispatcherState(dump).split("\\R")) {
            Matcher displayMatch = DISPLAY.matcher(line);
            if (displayMatch.matches()) {
                display = displayMatch.group(1);
                out.line("display=" + display);
                displayHeader = true;
                selected = false;
                matrixRows = 0;
            } else if (line.trim().equals("Windows:")) {
                displayHeader = false;
                matrixRows = 0;
            } else if (INPUT.matcher(line).matches()) {
                Set<Integer> matches = candidates(line, targets);
                selected = !matches.isEmpty();
                displayHeader = false;
                matrixRows = 0;
                if (selected) {
                    out.record("input-window display=" + display + " candidateTasks=" + matches);
                    int fields = line.indexOf(", id=");
                    if (fields >= 0) out.geometry(line.substring(fields + 2)
                            .replaceAll("applicationInfo\\.name=.*?, applicationInfo\\.token=", "applicationInfo.token="));
                }
            } else if (selected || displayHeader) {
                if (MATRIX_HEADER.matcher(line).matches()) {
                    out.line("  " + line.trim());
                    matrixRows = 3;
                } else if (matrixRows > 0 && MATRIX_ROW.matcher(line).matches()) {
                    out.line("    " + line.trim());
                    matrixRows--;
                } else if (displayHeader && line.trim().matches("logicalSize=\\d+x\\d+")) {
                    out.line("  " + line.trim());
                } else {
                    selected = false;
                    displayHeader = false;
                    matrixRows = 0;
                }
            }
        }
        return out.finish();
    }

    private static Set<Integer> candidates(String name, List<Target> targets) {
        Set<Integer> matches = new LinkedHashSet<>();
        Matcher task = TASK.matcher(name);
        while (task.find()) {
            for (Target target : targets) {
                if (task.group(1).equals(Integer.toString(target.taskId))) matches.add(target.taskId);
            }
        }
        Matcher component = COMPONENT.matcher(name);
        while (component.find()) {
            for (Target target : targets) {
                if (component.group(1).equals(target.packageName)) matches.add(target.taskId);
            }
        }
        return matches;
    }

    private static final class Excerpt {
        final StringBuilder text = new StringBuilder();
        int records;
        int fields;
        boolean truncated;

        void record(String value) { records++; line(value); }

        void line(String value) {
            if (text.length() + value.length() + 1 > OUTPUT_LIMIT) { truncated = true; return; }
            text.append(value).append('\n');
        }

        void geometry(String source) {
            Matcher field = FIELD.matcher(source);
            while (field.find()) {
                String key = field.group(1);
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                if (KEYS.contains(key) || lower.contains("scale") || lower.contains("bounds")
                        || lower.contains("crop") || lower.contains("position")
                        || lower.contains("frame") || lower.contains("inset")) {
                    fields++;
                    line("  " + key + '=' + field.group(2));
                }
            }
        }

        String finish() {
            return "matchedRecords=" + records + " geometryFields=" + fields
                    + " excerptTruncated=" + truncated + '\n' + text
                    + (records == 0 || fields == 0
                            ? "Geometry unavailable: no matching supported records/fields\n" : "");
        }
    }
}
