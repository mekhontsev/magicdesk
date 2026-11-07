package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;

/** Lazy artwork service, independent of catalog discovery and Desktop sessions. */
final class ApplicationIcons {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "MagicDeskIcons"));
    private static volatile ApplicationIcons instance;
    private final Context context;
    private final ApplicationIconCache<ApplicationIconKey, Bitmap> termux, guest;
    private String termuxIdentity = "";
    private long termuxEpoch;
    private TermuxConnectionStatus.State connection;
    private int guestUid = -1;

    static ApplicationIcons get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Icon subscriptions require UI thread");
        if (instance == null) instance = new ApplicationIcons(context.getApplicationContext());
        return instance;
    }

    private ApplicationIcons(Context context) {
        this.context = context;
        termux = new ApplicationIconCache<>(TermuxIconCommand.BATCH_SIZE, 256, this::loadTermux, MAIN::post);
        guest = new ApplicationIconCache<>(TermuxIconCommand.BATCH_SIZE, 256, this::loadGuest, MAIN::post);
        inspectGuest();
        ShellAccess.addStateListener(state -> MAIN.post(() -> guestIdentity(state)));
        TermuxConnectionStatus.get().addListener(this::inspectTermux);
    }

    static ApplicationIconKey key(DesktopApplicationShortcut shortcut, String termuxPackage) {
        return ApplicationIconKey.of(shortcut, termuxPackage == null || termuxPackage.isEmpty()
                ? IntegrationPackage.TERMUX.selected() : termuxPackage);
    }

    static Bitmap cached(ApplicationIconKey key) {
        var current = instance;
        return current == null || key == null ? null : current.cache(key).snapshot().get(key);
    }

    Runnable subscribe(ApplicationIconKey key, Runnable changed) {
        return key == null ? () -> { } : cache(key).subscribe(key, changed);
    }

    /** One-shot consumers share the same batches, including a completed lookup with no artwork. */
    Runnable request(ApplicationIconKey key, Consumer<Bitmap> complete) {
        if (key == null) { complete.accept(null); return () -> { }; }
        final var cache = cache(key);
        final class Request implements Runnable {
            boolean closed;
            Runnable unsubscribe;
            @Override public void run() {
                if (closed || !cache.snapshot().containsKey(key)) return;
                Bitmap artwork = cache.snapshot().get(key);
                close();
                complete.accept(artwork);
            }
            void close() { if (!closed) { closed = true; unsubscribe.run(); } }
        }
        var request = new Request();
        request.unsubscribe = cache.subscribe(key, request);
        request.run();
        return request::close;
    }

    static void refresh() {
        if (instance != null) { instance.termux.reset(); instance.guest.reset(); }
    }

    private ApplicationIconCache<ApplicationIconKey, Bitmap> cache(ApplicationIconKey key) {
        return key.kind() == ApplicationIconKey.Kind.TERMUX ? termux : guest;
    }

    private void inspectGuest() {
        guestIdentity(ShellAccess.currentSnapshot());
    }

    private void guestIdentity(ShellAccess.Snapshot state) {
        int uid = state.isReady() ? state.uid : -1;
        if (guestUid != uid) { guestUid = uid; guest.reset(); }
    }

    private TermuxIntegration.Endpoint inspectTermux() {
        var endpoint = TermuxIntegration.inspect(context);
        String identity = endpoint.packageName + "|" + endpoint.service + "|" + endpoint.uid
                + "|" + endpoint.homeDirectory + "|" + endpoint.error;
        var state = TermuxConnectionStatus.get().current(endpoint).state();
        if (!identity.equals(termuxIdentity) || state == TermuxConnectionStatus.State.READY && connection != state) {
            termuxIdentity = identity;
            termuxEpoch++;
            termux.reset();
        }
        connection = state;
        return endpoint;
    }

    private void loadTermux(List<ApplicationIconKey> keys, Consumer<Map<ApplicationIconKey, Bitmap>> complete) {
        long owner = termuxEpoch;
        var endpoint = inspectTermux();
        if (owner != termuxEpoch) return; // Reset scheduled a request in the new source epoch.
        var names = keys.stream().filter(key -> key.owner().equals(endpoint.packageName)).map(ApplicationIconKey::name).distinct().toList();
        if (!endpoint.available() || names.isEmpty()) { complete.accept(Map.of()); return; }
        TermuxApplicationIcons.load(context, endpoint, IO, names, values -> {
            inspectTermux();
            var images = new HashMap<ApplicationIconKey, Bitmap>();
            for (var key : keys) if (key.owner().equals(endpoint.packageName) && values.containsKey(key.name()))
                images.put(key, values.get(key.name()));
            complete.accept(images);
        });
    }

    private void loadGuest(List<ApplicationIconKey> keys, Consumer<Map<ApplicationIconKey, Bitmap>> complete) {
        int previous = guestUid;
        inspectGuest();
        if (previous != guestUid) return;
        int owner = guestUid;
        IO.execute(() -> {
            var images = new HashMap<ApplicationIconKey, Bitmap>();
            for (var backend : new DesktopExecBackend[]{DesktopExecBackend.APP, DesktopExecBackend.SHELL}) {
                try {
                    var selected = keys.stream().filter(key -> key.backend() == backend).toList();
                    if (selected.isEmpty() || backend == DesktopExecBackend.SHELL
                            && (!ShellAccess.isReady() || ShellAccess.currentSnapshot().uid != owner)) continue;
                    var args = new java.util.ArrayList<String>();
                    args.add("icons");
                    selected.forEach(key -> args.add(key.guestReference()));
                    String text = GuestEnvironmentOperations.read(new CommandExecution(context, backend), "", args.toArray(String[]::new));
                    if (text.length() > keys.size() * 34000) throw new IllegalArgumentException("Icon reply too large");
                    var records = new JSONArray(text);
                    for (int i = 0; i < records.length(); i++) {
                        var record = records.getJSONObject(i);
                        var key = ApplicationIconKey.guest(record.getString("id"), backend);
                        String png = record.optString("png");
                        if (!keys.contains(key) || png.isEmpty() || png.length() > 32768) continue;
                        try {
                            var image = ApplicationIconBitmap.decode(java.util.HexFormat.of().parseHex(png));
                            if (image != null) images.put(key, image);
                        } catch (IllegalArgumentException ignored) { }
                    }
                } catch (Exception error) {
                    android.util.Log.w("MagicDeskIcons", backend.wireName + " guest artwork unavailable", error);
                }
            }
            MAIN.post(() -> { inspectGuest(); complete.accept(images); });
        });
    }
}
