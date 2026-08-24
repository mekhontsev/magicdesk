package io.github.mekhontsev.magicdesk.display;

/** Read-only physical display timing used by shared selection policy. */
public interface DisplayTiming {
    int width();

    int height();

    int refreshRate();

    int pictureAspect();

    default boolean isValid() {
        return width() > 0
                && height() > 0
                && refreshRate() > 0
                && pictureAspect() >= 0;
    }

    default String timingKey() {
        return width() + "x" + height() + "@" + refreshRate();
    }

    default String vendorValue() {
        return width() + " " + height() + " "
                + refreshRate() + " " + pictureAspect();
    }
}
