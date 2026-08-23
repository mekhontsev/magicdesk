package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable runtime capability view with component-provider evidence. */
public final class PlatformCapabilitySnapshot {
    public static final class Entry {
        public final PlatformCapabilityId id;
        public final PlatformCapabilityState state;
        public final String providerId;
        public final String evidence;
        public final String detail;

        Entry(
                final PlatformCapabilityId id,
                final PlatformCapabilityState state,
                final String providerId,
                final String evidence,
                final String detail) {
            this.id = id;
            this.state = state;
            this.providerId = providerId;
            this.evidence = evidence;
            this.detail = detail;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id.wireName)
                    .put("state", state.wireName)
                    .put("provider", providerId)
                    .put("evidence", evidence)
                    .put("detail", detail);
        }
    }

    private interface Probe {
        boolean available();
    }

    private interface DetailProbe {
        String detail();
    }

    private final List<Entry> mEntries;

    private PlatformCapabilitySnapshot(final List<Entry> entries) {
        mEntries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public static PlatformCapabilitySnapshot capture(
            final PlatformDriver platform) {
        final List<Entry> entries = new ArrayList<>();
        final PlatformFeatures features = platform.features();
        add(entries, platform, PlatformCapabilityId.DESKTOP_PHONE,
                features.supportsDisplay(DesktopDisplayTarget.Kind.PHONE), "");
        add(entries, platform, PlatformCapabilityId.DESKTOP_SIMULATED,
                features.supportsDisplay(
                        DesktopDisplayTarget.Kind.SIMULATED), "");
        add(entries, platform, PlatformCapabilityId.DESKTOP_WIRED,
                features.supportsDisplay(DesktopDisplayTarget.Kind.WIRED), "");
        add(entries, platform, PlatformCapabilityId.DESKTOP_WIRELESS,
                features.supportsDisplay(
                        DesktopDisplayTarget.Kind.WIRELESS), "");
        probe(entries, platform, PlatformCapabilityId.MANAGED_WIRED,
                () -> platform.projection().ownsTransportLifecycle(
                        PlatformProjectionDriver.Transport.WIRED), () -> "");
        probe(entries, platform, PlatformCapabilityId.MANAGED_WIRELESS,
                () -> platform.projection().ownsTransportLifecycle(
                        PlatformProjectionDriver.Transport.WIRELESS), () -> "");
        probe(entries, platform, PlatformCapabilityId.OUTPUT_CONFIGURATION,
                () -> platform.projection().supportsOutputConfiguration(),
                () -> "");
        add(entries, platform, PlatformCapabilityId.EXTERNAL_INPUT_BRIDGE,
                features.externalInputBridge, "");
        probe(entries, platform, PlatformCapabilityId.ABSOLUTE_POINTER,
                () -> platform.pointer().isAvailable(), () -> "");
        probe(entries, platform, PlatformCapabilityId.MIRROR_TEXT_INPUT,
                () -> platform.textInput().isAvailable(),
                () -> platform.textInput().runtimeState().detail);
        probe(entries, platform, PlatformCapabilityId.PHONE_UI,
                () -> platform.phoneUi().isAvailable(), () -> "");
        probeAudioCapture(entries, platform);
        add(entries, platform, PlatformCapabilityId.VENDOR_HARDWARE,
                features.vendorHardware, "");
        return new PlatformCapabilitySnapshot(entries);
    }

    public List<Entry> entries() {
        return mEntries;
    }

    public Entry entry(final PlatformCapabilityId id) {
        for (final Entry entry : mEntries) {
            if (entry.id == id) {
                return entry;
            }
        }
        return null;
    }

    public JSONObject toJson() throws JSONException {
        final JSONArray values = new JSONArray();
        for (final Entry entry : mEntries) {
            values.put(entry.toJson());
        }
        return new JSONObject().put("capabilities", values);
    }

    private static void probe(
            final List<Entry> entries,
            final PlatformDriver platform,
            final PlatformCapabilityId id,
            final Probe probe,
            final DetailProbe detailProbe) {
        try {
            add(entries, platform, id, probe.available(),
                    detailProbe.detail());
        } catch (RuntimeException error) {
            addBroken(entries, platform, id, error);
        }
    }

    private static void probeAudioCapture(
            final List<Entry> entries,
            final PlatformDriver platform) {
        try {
            final PlatformAudioCaptureDriver driver =
                    platform.audioCapture();
            final PlatformAudioCaptureDriver.Availability availability =
                    driver.availability();
            final PlatformCapabilityState state;
            switch (availability) {
                case DECLARED:
                    state = PlatformCapabilityState.AVAILABLE;
                    break;
                case UNKNOWN:
                    state = PlatformCapabilityState.NOT_TESTED;
                    break;
                case MISSING:
                case UNSUPPORTED:
                default:
                    state = PlatformCapabilityState.UNAVAILABLE;
                    break;
            }
            add(entries, platform,
                    PlatformCapabilityId.INTERNAL_AUDIO_CAPTURE,
                    state,
                    driver.capabilityDescription());
        } catch (RuntimeException error) {
            addBroken(entries, platform,
                    PlatformCapabilityId.INTERNAL_AUDIO_CAPTURE,
                    error);
        }
    }

    private static void add(
            final List<Entry> entries,
            final PlatformDriver platform,
            final PlatformCapabilityId id,
            final boolean available,
            final String detail) {
        final PlatformSelection.Provider provider =
                platform.selection().provider(id.component);
        entries.add(new Entry(
                id,
                available
                        ? PlatformCapabilityState.AVAILABLE
                        : PlatformCapabilityState.UNAVAILABLE,
                provider == null ? platform.id() : provider.id,
                provider == null ? "" : provider.evidence,
                detail == null ? "" : detail));
    }

    private static void add(
            final List<Entry> entries,
            final PlatformDriver platform,
            final PlatformCapabilityId id,
            final PlatformCapabilityState state,
            final String detail) {
        final PlatformSelection.Provider provider =
                platform.selection().provider(id.component);
        entries.add(new Entry(
                id,
                state,
                provider == null ? platform.id() : provider.id,
                provider == null ? "" : provider.evidence,
                detail == null ? "" : detail));
    }

    private static void addBroken(
            final List<Entry> entries,
            final PlatformDriver platform,
            final PlatformCapabilityId id,
            final RuntimeException error) {
        final PlatformSelection.Provider provider =
                platform.selection().provider(id.component);
        entries.add(new Entry(
                id,
                PlatformCapabilityState.BROKEN,
                provider == null ? platform.id() : provider.id,
                provider == null ? "" : provider.evidence,
                usefulMessage(error)));
    }

    private static String usefulMessage(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName() : message;
    }
}
