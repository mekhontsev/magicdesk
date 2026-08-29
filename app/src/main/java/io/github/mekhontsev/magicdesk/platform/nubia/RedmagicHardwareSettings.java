package io.github.mekhontsev.magicdesk.platform.nubia;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Typed, read-only view of the stock RedMagic cooling policy settings. */
final class RedmagicHardwareSettings {
    static final String FAN_MANUAL = "fan_state_of_manual";
    static final String FAN_MODE = "fan_state_of_mode";
    static final String FAN_EFFECTIVE = "game_fan_off_on";
    static final String PUMP_MAIN = "liquid_cooling_main_switch";
    static final String PUMP_FLOW = "liquid_cooling_flow_speed_mode";
    static final String PUMP_EFFECTIVE = "liquid_cooling_off_on";

    private static final String[] ALL_KEYS = {
            FAN_MANUAL,
            FAN_MODE,
            FAN_EFFECTIVE,
            PUMP_MAIN,
            PUMP_FLOW,
            PUMP_EFFECTIVE
    };

    interface CommandRunner {
        String run(String command) throws IOException;
    }

    static final class Snapshot {
        private final Map<RedmagicSettingsNamespace, Map<String, Observation>>
                mValues;

        private Snapshot(
                final Map<RedmagicSettingsNamespace, Map<String, Observation>>
                        values) {
            final EnumMap<RedmagicSettingsNamespace,
                    Map<String, Observation>> copy =
                    new EnumMap<>(RedmagicSettingsNamespace.class);
            for (final Map.Entry<RedmagicSettingsNamespace,
                    Map<String, Observation>> entry : values.entrySet()) {
                copy.put(
                        entry.getKey(),
                        Collections.unmodifiableMap(
                                new LinkedHashMap<>(entry.getValue())));
            }
            mValues = Collections.unmodifiableMap(copy);
        }

        boolean observed(
                final RedmagicSettingsNamespace namespace,
                final String key) {
            final Map<String, Observation> values = mValues.get(namespace);
            return values != null && values.containsKey(key);
        }

        boolean readable(
                final RedmagicSettingsNamespace namespace,
                final String key) {
            final Observation observation = observation(namespace, key);
            return observation != null && observation.exitCode == 0;
        }

        String value(
                final RedmagicSettingsNamespace namespace,
                final String key) {
            final Observation observation = observation(namespace, key);
            return observation == null || observation.exitCode != 0
                    ? null : observation.value;
        }

        String error(
                final RedmagicSettingsNamespace namespace,
                final String key) {
            final Observation observation = observation(namespace, key);
            return observation == null || observation.exitCode == 0
                    ? ""
                    : "settings exit " + observation.exitCode
                            + (observation.value.isEmpty()
                                    ? "" : ": " + observation.value);
        }

        int observedCount() {
            int count = 0;
            for (final Map<String, Observation> values : mValues.values()) {
                count += values.size();
            }
            return count;
        }

        int readableCount() {
            int count = 0;
            for (final Map<String, Observation> values : mValues.values()) {
                for (final Observation observation : values.values()) {
                    if (observation.exitCode == 0) {
                        count++;
                    }
                }
            }
            return count;
        }

        RedmagicSettingsNamespace selectNamespace(
                final String first,
                final String second) {
            final int systemScore = score(
                    RedmagicSettingsNamespace.SYSTEM, first, second);
            final int globalScore = score(
                    RedmagicSettingsNamespace.GLOBAL, first, second);
            if (systemScore == globalScore) {
                return null;
            }
            return globalScore > systemScore
                    ? RedmagicSettingsNamespace.GLOBAL
                    : RedmagicSettingsNamespace.SYSTEM;
        }

        private int score(
                final RedmagicSettingsNamespace namespace,
                final String first,
                final String second) {
            int score = 0;
            if (isPresent(value(namespace, first))) {
                score++;
            }
            if (isPresent(value(namespace, second))) {
                score++;
            }
            return score;
        }

        private Observation observation(
                final RedmagicSettingsNamespace namespace,
                final String key) {
            final Map<String, Observation> values = mValues.get(namespace);
            return values == null ? null : values.get(key);
        }
    }

    private static final class Observation {
        final int exitCode;
        final String value;

        Observation(final int exitCode, final String value) {
            this.exitCode = exitCode;
            this.value = value == null ? "" : value;
        }
    }

    private RedmagicHardwareSettings() {
    }

    static Snapshot readAll(final CommandRunner runner) throws IOException {
        return read(runner, ALL_KEYS);
    }

    static Snapshot read(
            final CommandRunner runner,
            final String... keys) throws IOException {
        if (runner == null) {
            throw new IOException("cooling settings command runner unavailable");
        }
        final String command = readCommand(keys);
        return parse(runner.run(command));
    }

    static Snapshot parse(final String output) {
        final EnumMap<RedmagicSettingsNamespace,
                Map<String, Observation>> values =
                new EnumMap<>(RedmagicSettingsNamespace.class);
        for (final RedmagicSettingsNamespace namespace
                : RedmagicSettingsNamespace.values()) {
            values.put(namespace, new LinkedHashMap<>());
        }
        if (output != null) {
            for (final String rawLine : output.split("\\r?\\n")) {
                final String line = rawLine.trim();
                for (final RedmagicSettingsNamespace namespace
                        : RedmagicSettingsNamespace.values()) {
                    final String prefix = "setting." + namespace.shellName
                            + ".";
                    if (!line.startsWith(prefix)) {
                        continue;
                    }
                    final int separator = line.indexOf('=', prefix.length());
                    if (separator <= prefix.length()) {
                        break;
                    }
                    final String key = line.substring(
                            prefix.length(), separator);
                    if (isKnownSetting(key)) {
                        final String encoded = line.substring(separator + 1);
                        final int valueSeparator = encoded.indexOf('|');
                        if (valueSeparator <= 0) {
                            break;
                        }
                        try {
                            values.get(namespace).put(
                                    key,
                                    new Observation(
                                            Integer.parseInt(encoded.substring(
                                                    0, valueSeparator)),
                                            encoded.substring(
                                                    valueSeparator + 1)
                                                    .trim()));
                        } catch (NumberFormatException ignored) {
                            // A malformed line is an unavailable observation.
                        }
                    }
                    break;
                }
            }
        }
        return new Snapshot(values);
    }

    static boolean isControlSetting(final String key) {
        return FAN_MANUAL.equals(key)
                || FAN_MODE.equals(key)
                || PUMP_MAIN.equals(key)
                || PUMP_FLOW.equals(key);
    }

    static void appendDiagnostics(
            final StringBuilder report,
            final Snapshot settings,
            final String readError) {
        final int expectedObservations = ALL_KEYS.length
                * RedmagicSettingsNamespace.values().length;
        final boolean readSucceeded = settings != null
                && readError == null
                && settings.observedCount() == expectedObservations
                && settings.readableCount() == expectedObservations;
        final boolean partialRead = settings != null
                && readError == null && !readSucceeded;
        appendDiagnostic(
                report,
                "hardware.settings.read",
                readSucceeded ? "available" : (partialRead ? "partial" : "error"),
                readSucceeded
                        ? "read-only shell settings snapshot"
                        : (partialRead
                                ? settings.readableCount() + "/"
                                        + expectedObservations
                                        + " settings readable"
                                : readError));
        for (final String key : ALL_KEYS) {
            for (final RedmagicSettingsNamespace namespace
                    : RedmagicSettingsNamespace.values()) {
                appendSettingDiagnostic(
                        report,
                        settings,
                        namespace,
                        key,
                        readError);
            }
        }
        final RedmagicSettingsNamespace fanNamespace = settings == null
                ? null : settings.selectNamespace(FAN_MANUAL, FAN_MODE);
        final RedmagicSettingsNamespace pumpNamespace = settings == null
                ? null : settings.selectNamespace(PUMP_MAIN, PUMP_FLOW);
        appendNamespaceDiagnostic(report, "fan", fanNamespace);
        appendNamespaceDiagnostic(report, "pump", pumpNamespace);
        appendControlDiagnostic(
                report, "fan", fanNamespace, readError);
        appendControlDiagnostic(
                report, "pump", pumpNamespace, readError);
        appendStateDiagnostic(
                report,
                "fan",
                settings,
                RedmagicSettingsNamespace.GLOBAL,
                FAN_EFFECTIVE,
                readError);
        appendStateDiagnostic(
                report,
                "pump",
                settings,
                RedmagicSettingsNamespace.SYSTEM,
                PUMP_EFFECTIVE,
                readError);
    }

    private static String readCommand(final String... requestedKeys) {
        final Set<String> keys = new LinkedHashSet<>();
        if (requestedKeys != null) {
            for (final String key : requestedKeys) {
                if (!isKnownSetting(key)) {
                    throw new IllegalArgumentException(
                            "unknown RedMagic cooling setting");
                }
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "no RedMagic cooling settings requested");
        }
        final StringBuilder command = new StringBuilder();
        for (final RedmagicSettingsNamespace namespace
                : RedmagicSettingsNamespace.values()) {
            for (final String key : keys) {
                if (command.length() > 0) {
                    command.append("; ");
                }
                command.append("v=$(/system/bin/settings get ")
                        .append(namespace.shellName).append(' ').append(key)
                        .append(" 2>&1); s=$?; ")
                        .append("v=$(printf '%s' \"$v\" | tr '\\r\\n' ' '); ")
                        .append("printf 'setting.")
                        .append(namespace.shellName)
                        .append('.').append(key)
                        .append("=%s|%s\\n' \"$s\" \"$v\"");
            }
        }
        return command.toString();
    }

    private static boolean isKnownSetting(final String key) {
        for (final String known : ALL_KEYS) {
            if (known.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPresent(final String value) {
        return value != null && !value.isEmpty() && !"null".equals(value);
    }

    private static void appendSettingDiagnostic(
            final StringBuilder report,
            final Snapshot settings,
            final RedmagicSettingsNamespace namespace,
            final String key,
            final String readError) {
        final boolean observed = settings != null
                && settings.observed(namespace, key);
        final boolean readable = observed && settings.readable(namespace, key);
        final String value = observed ? settings.value(namespace, key) : null;
        final boolean absent = readable && !isPresent(value);
        appendDiagnostic(
                report,
                "hardware.setting." + namespace.shellName + "." + key,
                !readable ? "error" : (absent ? "absent" : "present"),
                !observed
                        ? (readError == null ? "no observation" : readError)
                        : (!readable
                                ? settings.error(namespace, key)
                                : (absent ? "" : value)));
    }

    private static void appendNamespaceDiagnostic(
            final StringBuilder report,
            final String group,
            final RedmagicSettingsNamespace namespace) {
        appendDiagnostic(
                report,
                "hardware.settings." + group,
                namespace == null ? "unresolved" : namespace.shellName,
                "");
    }

    private static void appendControlDiagnostic(
            final StringBuilder report,
            final String group,
            final RedmagicSettingsNamespace namespace,
            final String readError) {
        appendDiagnostic(
                report,
                "hardware.cooling." + group + ".control",
                namespace == null ? "unavailable" : "available",
                namespace == null
                        ? (readError == null
                                ? "control settings absent or ambiguous"
                                : readError)
                        : "namespace=" + namespace.shellName);
    }

    private static void appendStateDiagnostic(
            final StringBuilder report,
            final String group,
            final Snapshot settings,
            final RedmagicSettingsNamespace namespace,
            final String key,
            final String readError) {
        final String value = settings == null
                ? null : settings.value(namespace, key);
        final boolean available = "0".equals(value) || "1".equals(value);
        appendDiagnostic(
                report,
                "hardware.cooling." + group + ".state",
                available ? "available" : "unknown",
                available
                        ? "namespace=" + namespace.shellName
                                + "; enabled=" + value
                        : (readError == null
                                ? "effective state unavailable"
                                : readError));
    }

    private static void appendDiagnostic(
            final StringBuilder report,
            final String key,
            final String state,
            final String detail) {
        report.append(key).append('=').append(state);
        final String cleanDetail = detail == null
                ? "" : detail.trim().replace('\r', ' ').replace('\n', ' ');
        if (!cleanDetail.isEmpty()) {
            report.append(" | ").append(cleanDetail);
        }
        report.append('\n');
    }
}
