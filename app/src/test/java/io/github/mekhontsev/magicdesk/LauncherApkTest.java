package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LauncherApkTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path template() { return Path.of(System.getProperty("magicdesk.launcherTemplate")); }

    private byte[] apk(String label) throws Exception {
        byte[] icon;
        try (var zip = new ZipFile(template().toFile())) {
            var image = zip.stream().filter(entry -> entry.getName().startsWith("res/") && entry.getName().endsWith(".png"))
                    .findFirst().orElseThrow();
            try (var input = zip.getInputStream(image)) { icon = input.readAllBytes(); }
        }
        try (var input = Files.newInputStream(template())) {
            return LauncherApk.assemble(input, "example.magicdesk", "example.magicdesk.launcher.e" + "a".repeat(32), label, icon);
        }
    }

    @Test public void independentAndroidVerifierAcceptsSignatureAndRejectsTampering() throws Exception {
        Path keys = temporary.getRoot().toPath().resolve("test.p12");
        String exe = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        run(true, Path.of(System.getProperty("java.home"), "bin", "keytool" + exe).toString(), "-genkeypair", "-keystore", keys.toString(),
                "-storetype", "PKCS12", "-storepass", "password", "-keypass", "password", "-alias", "test", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=LauncherTest", "-validity", "1");
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keys)) { store.load(input, "password".toCharArray()); }
        byte[] signed = LauncherApk.sign(apk("GIMP \u4e2d\u6587 \ud83d\udd27"), (PrivateKey) store.getKey("test", "password".toCharArray()),
                (X509Certificate) store.getCertificate("test"));
        Path output = temporary.getRoot().toPath().resolve("launcher.apk");
        Files.write(output, signed);
        verify(true, output);
        // Modify an entry byte without touching ZIP layout, independently exercising the content digest.
        signed[150] ^= 1;
        Files.write(output, signed);
        verify(false, output);
    }

    @Test public void rebuiltTemplateKeepsDexAndAlignedResourcesWithoutDependencies() throws Exception {
        byte[] unsigned = apk("Very long \u0440\u0443\u0441\u0441\u043a\u043e\u0435 name");
        Path output = temporary.getRoot().toPath().resolve("unsigned.apk");
        Files.write(output, unsigned);
        try (var before = new ZipFile(template().toFile()); var after = new ZipFile(output.toFile())) {
            assertArrayEquals(before.getInputStream(before.getEntry("classes.dex")).readAllBytes(),
                    after.getInputStream(after.getEntry("classes.dex")).readAllBytes());
            assertEquals(java.util.zip.ZipEntry.STORED, after.getEntry("resources.arsc").getMethod());
        }
        var in = ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; in.getInt(at) == 0x04034b50;) {
            int size = in.getInt(at + 18), name = Short.toUnsignedInt(in.getShort(at + 26));
            int extra = Short.toUnsignedInt(in.getShort(at + 28));
            int start = at + 30 + name + extra;
            assertEquals(0, start & 3);
            at = start + size;
        }
    }

    @Test public void runtimePayloadIsAlignedAndStreamingSignatureCoversAllChunks() throws Exception {
        Path root=temporary.getRoot().toPath(), keys=root.resolve("runtime.p12");
        run(true,Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-keystore",keys.toString(),
                "-storetype","PKCS12","-storepass","password","-keypass","password","-alias","test","-keyalg","RSA",
                "-keysize","2048","-dname","CN=RuntimeTest","-validity","1");
        var store=KeyStore.getInstance("PKCS12");
        try (var input=Files.newInputStream(keys)) { store.load(input,"password".toCharArray()); }
        Path template=Path.of(System.getProperty("magicdesk.guestTemplate")), library=root.resolve("runtime.so");
        byte[] payload=new byte[3*1024*1024+357]; new java.util.Random(7).nextBytes(payload); Files.write(library,payload);
        byte[] icon;
        try (var zip=new ZipFile(template.toFile())) {
            var entry=zip.stream().filter(item -> item.getName().endsWith(".png")).findFirst().orElseThrow();
            icon=zip.getInputStream(entry).readAllBytes();
        }
        Path unsigned=root.resolve("runtime-unsigned.apk"), signed=root.resolve("runtime.apk");
        var markers=Map.of("io.github.mekhontsev.magicdesk.guesttemplate","example.linux.e"+"b".repeat(32),
                "MAGICDESK_LINUX_LABEL","Alpine");
        try (var input=Files.newInputStream(template)) {
            LauncherApk.assemble(input,markers,icon,Map.of("libtest.so",library),"{}".getBytes(),unsigned);
        }
        try (var zip=new ZipFile(unsigned.toFile())) {
            assertArrayEquals(payload,zip.getInputStream(zip.getEntry("lib/arm64-v8a/libtest.so")).readAllBytes());
        }
        var bytes=ByteBuffer.wrap(Files.readAllBytes(unsigned)).order(ByteOrder.LITTLE_ENDIAN);
        for (int at=0;bytes.getInt(at)==0x04034b50;) {
            int size=bytes.getInt(at+18), name=Short.toUnsignedInt(bytes.getShort(at+26)), extra=Short.toUnsignedInt(bytes.getShort(at+28));
            int start=at+30+name+extra;
            String path=new String(bytes.array(),at+30,name,java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(path,0,start & (path.startsWith("lib/") ? 16383 : 3)); at=start+size;
        }
        var key=(PrivateKey)store.getKey("test","password".toCharArray());
        var cert=(X509Certificate)store.getCertificate("test");
        LauncherApk.sign(unsigned,signed,key,cert);
        verify(true,signed);
        var permissions=permissions(signed);
        assertTrue(permissions.remove("uses-permission: name='android.permission.INTERNET'"));
        assertTrue(permissions.remove("uses-permission: name='android.permission.ACCESS_LOCAL_NETWORK'"));
        var offline=root.resolve("offline.apk");
        try (var input=Files.newInputStream(template)) {
            var manifest=Files.readAllBytes(Path.of(System.getProperty("magicdesk.offlineManifest")));
            LauncherApk.assemble(input,markers,icon,Map.of("libtest.so",library),"{}".getBytes(),Map.of(),manifest,unsigned);
        }
        LauncherApk.sign(unsigned,offline,key,cert);
        verify(true,offline);
        assertEquals(permissions,permissions(offline));
        try (var file=new java.io.RandomAccessFile(signed.toFile(),"rw")) {
            long at=2*1024*1024; file.seek(at); int value=file.read(); file.seek(at); file.write(value^1);
        }
        verify(false,signed);
    }

    @Test public void malformedOrUnexpectedTemplatesAreRejected() throws Exception {
        assertThrows(java.io.IOException.class, () -> LauncherManifest.replace(new byte[7], Map.of()));
        try (var zip = new ZipFile(template().toFile())) {
            byte[] manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readAllBytes();
            assertThrows(java.io.IOException.class, () -> LauncherManifest.replace(manifest, Map.of("missing", "value")));
            byte[] bad = manifest.clone(); bad[4] ^= 1;
            assertThrows(java.io.IOException.class, () -> LauncherManifest.replace(bad, Map.of()));
            assertThrows(java.io.IOException.class, () -> LauncherManifest.replace(manifest,
                    Map.of("MAGICDESK_LAUNCHER_LABEL", "bad\0label")));
        }
        assertThrows(java.io.IOException.class, () -> LauncherApk.assemble(new ByteArrayInputStream(new byte[0]), "a", "b", "c", new byte[0]));
    }

    private static void verify(boolean expected, Path apk) throws Exception {
        String signer = System.getProperty("magicdesk.apksigner");
        if (signer.endsWith(".bat")) run(expected, "cmd", "/c", signer, "verify", "--min-sdk-version", "34", apk.toString());
        else run(expected, signer, "verify", "--min-sdk-version", "34", apk.toString());
    }

    private static java.util.Set<String> permissions(Path apk) throws Exception {
        String aapt=Path.of(System.getProperty("magicdesk.apksigner")).resolveSibling("aapt2").toString();
        var lines=run(true,aapt,"dump","permissions",apk.toString()).lines().toList();
        return new java.util.HashSet<>(lines);
    }

    private static String run(boolean expected, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output, expected, process.waitFor() == 0);
        return output;
    }
}
