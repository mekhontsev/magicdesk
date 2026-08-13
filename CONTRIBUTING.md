# Contributing

MagicDesk uses Gradle as its project definition. Open the repository root in
Android Studio, IntelliJ IDEA, or another Gradle-aware editor; do not open the
`app` directory as a standalone project.

## Compatibility Direction

The project aims to support as many compatible devices and firmware versions
as practical through one MagicDesk APK and one codebase. Prefer runtime
capability probing, shared Android behavior, and focused platform drivers over
model checks, product flavors, or device-specific forks. A missing optional
vendor feature should disable only that feature and remain visible in
Diagnostics.

## Build Environment

- JDK 17 or newer
- Android SDK platform and build tools 37
- Android NDK 27.3.13750724, installed as **NDK (Side by side)**

Android Studio can install the SDK and NDK components from SDK Manager. Gradle
finds a side-by-side NDK through the configured Android SDK. An explicit
`ANDROID_NDK_HOME` remains available for command-line and CI environments.

If Gradle cannot locate the Android SDK, create an untracked
`local.properties` file:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Termux builds use `$PREFIX/bin/clang` and do not require the desktop NDK
toolchain.

## Verification

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Run the complete local verification before submitting a change:

```sh
./gradlew verifyDevelopment
```

Debug and pull-request builds do not require release-signing credentials.

## Repository Hygiene

Do not commit IDE metadata, `local.properties`, generated build output,
keystores, device captures, or diagnostic reports containing local device
information. Before submitting a change, check `git status` and run the
verification commands above.
