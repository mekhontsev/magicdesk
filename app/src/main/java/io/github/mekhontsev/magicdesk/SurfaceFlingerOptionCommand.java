package io.github.mekhontsev.magicdesk;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Applies the narrow SurfaceFlinger policy needed by MagicDesk Console Mode. */
public final class SurfaceFlingerOptionCommand {
    private static final int WIRED_PRIVACY_MODE_OPTION = 1102;
    private static final int DEFAULT_WIRED_PRIVACY_MODE = 1;
    private static final String PROJECTION_PREFERENCES =
            "/data/user/0/cn.nubia.touping/shared_prefs/SCREEN_PROJECTION.xml";
    private static final String WIRED_PRIVACY_MODE_KEY = "PRIVATE_MODE_WIRED";

    private SurfaceFlingerOptionCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 1
                || (!"enable-captions".equals(args[0])
                && !"restore-privacy".equals(args[0]))) {
            usage();
            return;
        }

        final boolean enableCaptions = "enable-captions".equals(args[0]);
        final int value = enableCaptions ? 0 : readWiredPrivacyModePreference();
        try {
            final Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
            final Method setter = surfaceControl.getDeclaredMethod(
                    "setSFOption", int.class, int.class);
            setter.invoke(null, WIRED_PRIVACY_MODE_OPTION, value);
            System.out.println("external-task-captions="
                    + (enableCaptions ? "enabled" : "restored")
                    + " sf-option=" + WIRED_PRIVACY_MODE_OPTION
                    + " value=" + value);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("SurfaceFlinger option failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.err.println("SurfaceFlinger option failed: " + e);
            System.exit(1);
        }
    }

    private static int readWiredPrivacyModePreference() {
        final File preferences = new File(PROJECTION_PREFERENCES);
        if (!preferences.isFile()) {
            return DEFAULT_WIRED_PRIVACY_MODE;
        }
        try (FileInputStream input = new FileInputStream(preferences)) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG
                        || !"boolean".equals(parser.getName())
                        || !WIRED_PRIVACY_MODE_KEY.equals(
                                parser.getAttributeValue(null, "name"))) {
                    continue;
                }
                return Boolean.parseBoolean(
                        parser.getAttributeValue(null, "value")) ? 1 : 0;
            }
        } catch (Exception e) {
            System.err.println("cannot read wired privacy preference: " + e);
        }
        return DEFAULT_WIRED_PRIVACY_MODE;
    }

    private static void usage() {
        System.err.println(
                "usage: SurfaceFlingerOptionCommand <enable-captions|restore-privacy>");
        System.exit(64);
    }
}
