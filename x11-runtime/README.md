# MagicDesk X11 Runtime

MagicDesk's Android library for embedded X11, supporting API 34+. It owns Java,
Binder and JNI integration and builds `libXlorie.so` from the pinned native engine
in [vendor/magicdesk-x11](../vendor/magicdesk-x11).

## Scope

The module owns the X server entry point, authenticated Binder connection,
renderer outputs, protocol input and content-transfer adapters. The application
owns command execution, session retention, Android placement, clipboard focus
and URI grants. Neither Desktop nor HOME is a module prerequisite.

The server runs under the selected Termux UID or MagicDesk's app UID; client
commands can use a separate authorized executor. Rendering always uses the
host app UID. The native [embedding contract](../vendor/magicdesk-x11/docs/embedding.md)
is independent of Java classes and Android application policy.

See [Embedded X11](../docs/x11.md) for lifecycle, startup protocol, host boundaries,
limits and verification. The [example](example) is a test-only Gradle application
for output and server lifecycle checks; it is not part of the MagicDesk APK.

## Verification

From the repository root:

```sh
./gradlew :x11-runtime:testDebugUnitTest :x11-runtime:lintDebug :app:assembleDebug
./gradlew -p x11-runtime/example :app:assembleDebug :app:lintDebug
sh scripts/verify-native.sh
```

Build checks do not replace device testing; Android 14 device coverage remains
pending. See the [verification procedure](../docs/x11.md#verification).

## Licensing

The module is part of MagicDesk. The native engine and its dependencies retain
their own notices, collected into the APK by this module's build. See
[Third-party notices](../THIRD_PARTY_NOTICES.md) and
[Licensing and source availability](../docs/licensing.md).
