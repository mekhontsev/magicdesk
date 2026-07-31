package io.github.mekhontsev.magicdesk;

import java.util.Locale;

final class RedmagicHardwareSnapshot {
    static final int UNKNOWN = Integer.MIN_VALUE;

    static final RedmagicHardwareSnapshot UNAVAILABLE =
            new RedmagicHardwareSnapshot(
                    false, UNKNOWN, UNKNOWN, UNKNOWN,
                    false, UNKNOWN, UNKNOWN, UNKNOWN,
                    UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);

    final boolean fanAvailable;
    final int fanEnabled;
    final int fanLevel;
    final int fanRpm;
    final boolean pumpAvailable;
    final int pumpEnabled;
    final int pumpFrequency;
    final int pumpSpeed;
    final int cpuMilliCelsius;
    final int gpuMilliCelsius;
    final int skinMilliCelsius;
    final int batteryMilliCelsius;

    RedmagicHardwareSnapshot(
            final boolean fanAvailable,
            final int fanEnabled,
            final int fanLevel,
            final int fanRpm,
            final boolean pumpAvailable,
            final int pumpEnabled,
            final int pumpFrequency,
            final int pumpSpeed,
            final int cpuMilliCelsius,
            final int gpuMilliCelsius,
            final int skinMilliCelsius,
            final int batteryMilliCelsius) {
        this.fanAvailable = fanAvailable;
        this.fanEnabled = fanEnabled;
        this.fanLevel = fanLevel;
        this.fanRpm = fanRpm;
        this.pumpAvailable = pumpAvailable;
        this.pumpEnabled = pumpEnabled;
        this.pumpFrequency = pumpFrequency;
        this.pumpSpeed = pumpSpeed;
        this.cpuMilliCelsius = cpuMilliCelsius;
        this.gpuMilliCelsius = gpuMilliCelsius;
        this.skinMilliCelsius = skinMilliCelsius;
        this.batteryMilliCelsius = batteryMilliCelsius;
    }

    static RedmagicHardwareSnapshot parse(final String output) {
        int fanEnabled = UNKNOWN;
        int fanLevel = UNKNOWN;
        int fanRpm = UNKNOWN;
        int pumpEnabled = UNKNOWN;
        int pumpFrequency = UNKNOWN;
        int pumpSpeed = UNKNOWN;
        int cpu = UNKNOWN;
        int gpu = UNKNOWN;
        int skin = UNKNOWN;
        int battery = UNKNOWN;

        if (output != null) {
            for (final String rawLine : output.split("\\r?\\n")) {
                final String line = rawLine.trim();
                if (line.startsWith("thermal=")) {
                    final int separator = line.lastIndexOf('|');
                    if (separator <= "thermal=".length()) {
                        continue;
                    }
                    final String type = line.substring(
                            "thermal=".length(), separator)
                            .trim().toLowerCase(Locale.ROOT);
                    final int value = parseTemperature(
                            line.substring(separator + 1));
                    if (value == UNKNOWN || isThreshold(type)) {
                        continue;
                    }
                    if (isCpu(type)) {
                        cpu = maxKnown(cpu, value);
                    } else if (isGpu(type)) {
                        gpu = maxKnown(gpu, value);
                    } else if (isSkin(type)) {
                        skin = maxKnown(skin, value);
                    } else if (isBattery(type)) {
                        battery = maxKnown(battery, value);
                    }
                    continue;
                }

                final int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                final String key = line.substring(0, separator);
                final int value = parseInteger(line.substring(separator + 1));
                if ("node.fan_enable".equals(key)) {
                    fanEnabled = value;
                } else if ("node.fan_level".equals(key)) {
                    fanLevel = value;
                } else if ("node.fan_rpm".equals(key)) {
                    fanRpm = value;
                } else if ("node.pump_enable".equals(key)) {
                    pumpEnabled = value;
                } else if ("node.pump_frequency".equals(key)) {
                    pumpFrequency = value;
                } else if ("node.pump_speed".equals(key)) {
                    pumpSpeed = value;
                }
            }
        }

        return new RedmagicHardwareSnapshot(
                fanEnabled != UNKNOWN && fanLevel != UNKNOWN,
                fanEnabled, fanLevel, fanRpm,
                pumpEnabled != UNKNOWN && pumpFrequency != UNKNOWN
                        && pumpSpeed != UNKNOWN,
                pumpEnabled, pumpFrequency, pumpSpeed,
                cpu, gpu, skin, battery);
    }

    boolean isAvailable() {
        return fanAvailable || pumpAvailable
                || cpuMilliCelsius != UNKNOWN
                || gpuMilliCelsius != UNKNOWN
                || skinMilliCelsius != UNKNOWN
                || batteryMilliCelsius != UNKNOWN;
    }

    int controlTemperatureMilliCelsius() {
        return maxKnown(cpuMilliCelsius, gpuMilliCelsius);
    }

    private static boolean isThreshold(final String type) {
        return type.contains("trip") || type.contains("bcl")
                || type.contains("lvl");
    }

    private static boolean isCpu(final String type) {
        return type.startsWith("cpullc-")
                || type.startsWith("cpu-")
                || type.startsWith("qmx-");
    }

    private static boolean isGpu(final String type) {
        return type.startsWith("gpuss-") || type.startsWith("gpu-");
    }

    private static boolean isSkin(final String type) {
        return type.equals("skin") || type.startsWith("skin-");
    }

    private static boolean isBattery(final String type) {
        return type.equals("battery");
    }

    private static int parseTemperature(final String value) {
        final int parsed = parseInteger(value);
        return parsed >= -40_000 && parsed <= 150_000
                ? parsed : UNKNOWN;
    }

    private static int parseInteger(final String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return UNKNOWN;
        }
    }

    private static int maxKnown(final int first, final int second) {
        if (first == UNKNOWN) {
            return second;
        }
        if (second == UNKNOWN) {
            return first;
        }
        return Math.max(first, second);
    }
}
