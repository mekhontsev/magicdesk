"""Build disposable host/offline-peer APKs around the exact debug DEX and native runtime."""
import argparse
import subprocess
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "rootfs", "output", "android-jar", "apksigner"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--aapt2", default="aapt2")
    args = parser.parse_args()
    key = Path.home() / ".android/debug.keystore"
    host = "io.github.mekhontsev.magicdesk.capsulecheck"
    peer = host + ".peer"
    fixture = "io.github.mekhontsev.magicdesk.CapsuleQualification"
    for package in (host, peer):
        online = package == host
        directory = args.output / ("host" if online else "peer")
        directory.mkdir(parents=True, exist_ok=True)
        permission = host + ".BIND_FIXTURE"
        permissions = (f'<permission android:name="{permission}" android:protectionLevel="signature"/>'
                       '<uses-permission android:name="android.permission.INTERNET"/>'
                       '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>'
                       if online else f'<uses-permission android:name="{permission}"/>')
        app = (f'<service android:name="{fixture}$Host" android:permission="{permission}" android:exported="true"/>'
               if online else "")
        runner = "android.app.Instrumentation" if online else fixture
        instrumentation = f'<instrumentation android:name="{runner}" android:targetPackage="{package}"/>'
        manifest = directory / "AndroidManifest.xml"
        manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">'
                            '<uses-sdk android:minSdkVersion="34" android:targetSdkVersion="37"/>'
                            f'{permissions}<queries><package android:name="{peer if online else host}"/></queries>'
                            f'<application android:debuggable="true" android:extractNativeLibs="true">{app}</application>'
                            f'{instrumentation}</manifest>')
        unsigned = directory / "unsigned.apk"
        subprocess.run([args.aapt2, "link", "-I", args.android_jar, "--manifest", manifest, "-o", unsigned], check=True)
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as out, zipfile.ZipFile(args.apk) as apk:
            for name in apk.namelist():
                if (name.startswith("classes") and name.endswith(".dex") or not online and name.startswith("lib/arm64-v8a/")
                        and ("/libshroot_" in name or name.endswith("libmagicdesk_linux_settings.so"))):
                    out.writestr(name, apk.read(name))
            if not online:
                out.write(args.rootfs, "assets/rootfs.tar.gz")
        subprocess.run([args.apksigner, "sign", "--ks", key, "--ks-pass", "pass:android",
                        "--out", directory / "fixture.apk", unsigned], check=True)
        unsigned.unlink()


if __name__ == "__main__":
    main()
