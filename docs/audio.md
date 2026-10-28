# Linux Audio

Linux playback is a shared Android 14+ service, independent of Desktop, display
placement, X11, Wayland and the selected Linux launcher. `LinuxAudio` acquires a
launch-scoped capability; `LinuxAudioService` hosts one PipeWire graph in the
private `:audio` app process. Failure to prepare audio is recorded without
preventing terminal or graphical startup. No root, microphone permission,
Android audio capture or Linux sound device is required.

## Ownership and Transport

The selected command executor admits connections. Shell uses its existing
`ShellUnixEndpoint`; Termux uses an authenticated `app_process` endpoint broker
under its own UID. The broker passes each accepted socket FD once. Neither
Binder nor Java forwards PCM packets. Owner death closes admission and revokes
that owner's connected clients without disconnecting other launches.

Terminal audio belongs to its PTY, not its Activity. Graphical audio belongs to
the retained protocol session, not a viewer. Background Shroot commands retain
audio until command completion or cancellation. Closing the final lease releases
the graph, its descriptors and temporary files. A bound but unused lease does
not load PipeWire or open an Android output.

Native PipeWire clients use the standard shared-buffer protocol. Pulse clients
connect to PipeWire's protocol-pulse module; there is no PulseAudio daemon or
libpulse in the APK. Only the playback modules and SPA converters are packaged.
External native clients can create client nodes, not server-side adapters or
arbitrary libraries. Recording links are not created, and Pulse record requests
and server module loading are rejected.

PipeWire warning/error messages go to the `MagicDeskPipeWire` Android log tag;
ordinary frame processing does not produce logs.

## Android Output

PipeWire mixes and converts client formats into 48 kHz float stereo. A bounded
single-producer/single-consumer ring connects its data callback to AAudio. Audio
callbacks allocate no Java objects, acquire no Java locks and make no Binder
calls. AAudio callback events drive graph processing; there is no periodic
Java audio poll. Android selects the physical output route and system volume.

The control thread owns Android media audio focus and the media-playback
foreground notification. Loss of focus pauses output; ducking changes the ring
consumer's gain. An Android route-disconnection event permits a bounded reopen
from the control thread. Other output errors are reported, not retried forever.
An idle graph closes AAudio and relinquishes focus and foreground status.

Compatibility reports expose lease count, graph demand, output error, received
and rendered frames, padding, overruns, queue depth and Android xruns. Reading
diagnostics does not start an audio service. These counters are graph-wide,
not per-client accounting.
Zero output errors or xruns do not certify uninterrupted source audio: a client
or guest tunnel can supply silence before the Android ring. Audible continuity
requires inspecting PCM from the affected stream under the reproducing workload,
alongside transport and scheduling observations.
`silenceFrames` counts padding by the Android ring consumer, not zero-valued PCM
received from a client. PipeWire's graph deadline counters are also distinct from
Pulse stream underruns: its Pulse server can fill an empty client buffer with
silence while completing the graph cycle on time. Continuity checks should use
a known continuous source, observe the client stream and guest sink separately,
and verify the measurement with a bounded producer-stall control. A quiet PCM
block alone is not an error for arbitrary application content.

## Linux Environments

Termux native programs receive `PIPEWIRE_REMOTE` and `PULSE_SERVER` directly.
PRoot recipes bind the executor's private endpoint directories. Prepared chroot
recipes explicitly bind the socket files before switching to the guest user.
Shroot routes `/tmp/magicdesk-audio/pipewire` and `/tmp/magicdesk-audio/pulse` to
the authorized abstract endpoints; shell does not need to create filesystem
socket nodes on Android. No launcher silently changes privilege identity.

`install_linux.sh` installs PipeWire client support with GUI profiles. Individual
applications connect directly. The shared `magicdesk-desktop-session` launcher
uses `magicdesk-audio-session`, which owns a guest PipeWire core, WirePlumber and
PipeWire's Pulse compatibility server. Its client context owns the standard
pulse-tunnel sink, sending the guest mix to Android without a core-to-itself
connection. Guest volume controls operate on the guest graph; Android
volume remains independent. ALSA applications use the distribution's
PipeWire ALSA plugin. Hardware discovery remains disabled in the guest session.
Xfce, Weston, GNOME devkit and Plasma launch through the same audio owner,
including direct launches in a prepared graphical console. The desktop launcher
reuses the supplied D-Bus session or creates one; an already active audio session
is reused. Resume refreshes installed GUI helpers and mixer dependencies.
The shared Android sink does not accept guest-wide volume changes; a desktop's
device-volume control belongs to its private guest graph.
The tunnel is published as the guest's output device (`node.virtual = false`),
so standard desktop mixers show it without enabling hidden virtual devices.
Its network transport and software volume control remain explicit.
The guest graph and Pulse request floor are 1024 frames at 48 kHz (about 21 ms),
with a 60 ms tunnel target. This bounds scheduling pressure in an environment
without hardware-clock ownership or guaranteed real-time scheduling. It does
not change the Android output quantum or direct-client audio policy.
The installer supplies matching PipeWire client/server and WirePlumber drop-ins
for a dedicated data loop with ordinary mutexes (`loop.prio-inherit = false`).
Shroot's logical TIDs cannot be used as native PI-futex owners. The private
session uses the same policy; it does not disable the data thread or native
scheduling permitted by Android. NixOS installs these files through activation.

The wrapper uses private per-session runtime/configuration directories and
PipeWire startup receipts and output-port events, not fixed sleeps. Applications
start after WirePlumber has configured both sink channels. It does not boot systemd, change a
user's global audio configuration or replace a running system audio service.
Without an audio capability it runs the original desktop command unchanged.
WirePlumber 0.4 uses its Lua configuration; 0.5+ uses feature profiles. Both
disable guest hardware discovery while retaining normal stream policy.
The launch-scoped `DISABLE_RTKIT` switch prevents requests to unavailable
realtime services without disabling native scheduling or rewriting distribution
PipeWire configuration.

## Build and Verification

`audio-runtime/native-deps` pins PipeWire sources and checksums, minimal Bionic
compatibility patches and explicit FD admission. The Android module packages
only its library allowlist and license texts. Windows builds consume
`:audio-runtime:exportRuntime` with `-PmagicDeskAudioRuntime=PATH`.

- `audio-runtime/tests/ring.c`: wraparound, bounds, gain and concurrent transfer.
- `audio-runtime/tests/smoke.py`: real Pulse and native PipeWire playback through
  the Android native test host, followed by output and process cleanup.
- `LinuxAudioInstrumentation`: actual executor admission, protocol playback and
  Android output counters across UIDs, concurrent ownership and client revocation,
  without changing system volume.
- `audio-runtime/tests/admission.py`: rejected recording/module requests and
  native factory access, followed by successful playback through the same lease.
- `LinuxAudioEnvironmentTest` and installer fixtures: literal environment values,
  optional routes and guest session construction.
- `test_installer_audio.py`: the installed desktop wrapper, private default sink,
  volume/mute notifications during silent playback and owned-process cleanup.

Device tests use silence or an inaudible signal. They establish protocol and
output operation, not a subjective listening or physical-speaker quality test.
