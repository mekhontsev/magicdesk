package io.github.mekhontsev.magicdesk;

final class RedmagicFanCurve {
    private static final int HYSTERESIS_MILLI_CELSIUS = 3_000;
    private static final int[] LEVEL_STARTS_MILLI_CELSIUS = {
            42_000, 52_000, 62_000, 72_000, 82_000
    };

    private RedmagicFanCurve() {
    }

    static int levelFor(
            final int temperatureMilliCelsius,
            final int currentLevel) {
        if (temperatureMilliCelsius == RedmagicHardwareSnapshot.UNKNOWN) {
            return Math.max(0, Math.min(5, currentLevel));
        }
        int target = 0;
        for (int index = 0;
                index < LEVEL_STARTS_MILLI_CELSIUS.length;
                index++) {
            if (temperatureMilliCelsius
                    >= LEVEL_STARTS_MILLI_CELSIUS[index]) {
                target = index + 1;
            }
        }
        final int normalizedCurrent = Math.max(0, Math.min(5, currentLevel));
        if (target >= normalizedCurrent || normalizedCurrent == 0) {
            return target;
        }
        final int currentStart =
                LEVEL_STARTS_MILLI_CELSIUS[normalizedCurrent - 1];
        return temperatureMilliCelsius
                < currentStart - HYSTERESIS_MILLI_CELSIUS
                ? target : normalizedCurrent;
    }

    static boolean needsApply(
            final int targetLevel,
            final int appliedLevel,
            final int actualEnabled,
            final int actualLevel) {
        if (targetLevel != appliedLevel) {
            return true;
        }
        if (targetLevel == 0) {
            return actualEnabled != 0;
        }
        return actualEnabled != 1 || actualLevel != targetLevel;
    }
}
