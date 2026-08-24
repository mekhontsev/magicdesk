# Native Display Mode Helper

`MagicDesk Display Fixes` is an independent, optional APK for firmware that
exposes a monitor's complete DisplayPort timing list only to root. It is not a
MagicDesk runtime dependency and is not published automatically with every
MagicDesk build.

The helper currently supports the Nubia display interface verified on the Z80
Ultra. It has no model allowlist. On each launch it checks for all required
runtime evidence instead:

- a wired Android external display is connected;
- direct `su` access was granted by the user's superuser manager;
- `/sys/kernel/lcd_enhance/edid_modes` is present and readable;
- the node contains at least one valid advertised timing.

If any check fails, the helper changes nothing and reports the missing
capability. It does not use or depend on Shizuku.

## Operation

The launcher activity performs one bounded operation and then remains idle:

1. Request direct root through `su -c`.
2. Read and parse the firmware's advertised EDID timing list.
3. Select the highest native resolution and its highest refresh rate, avoiding
   a cinema-aspect duplicate when an equivalent normal timing is present.
4. Clear an old Android user-preferred mode for the connected display.
5. Apply Nubia's normal display-refresh and HPD sequence.
6. Wait until Android reports the requested timing in three consecutive
   observations.

The dimensions are never hardcoded and are never accepted from user input.
The HPD node is restored to the connected state if the root command is
interrupted after disconnecting it. No daemon, boot receiver, persistent root
process, Shizuku binding, or Magisk module is installed.

The common EDID parser and native-mode selection policy live in the pure Java
`display-mode-core` module. MagicDesk and the helper therefore cannot silently
choose different native timings.

## Use

1. Connect the monitor or glasses while Android is already exposing a wired
   external display.
2. Open **MagicDesk Display Fixes** and grant its one-time root request.
3. Wait for **Native mode applied** or **Native mode already active**.
4. In MagicDesk, leave **Output mode** at **System / native**, then start the
   external desktop normally.

Running the helper again is safe: it reports the mode as already active when
the selected timing is already visible to Android. Disconnecting the display
or rebooting may return ownership to the firmware, so the helper can be run
again before a later MagicDesk session.

## Build

```sh
./gradlew :display-fixes:assembleDebug
```

The APK is generated under `display-fixes/build/outputs/apk/debug/`. Normal CI
builds and checks this module to prevent it from drifting, but rolling and
tagged MagicDesk releases publish only the main application unless a helper
build is intentionally distributed for a hardware test.
