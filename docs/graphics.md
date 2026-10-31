# Hosted Graphics

`hosted-runtime/src/main/cpp/graphics` owns the shared X11/Wayland composition
backend. It uses Android's Vulkan driver when the required capabilities are
available and retains a software backend. It does not require Desktop, root,
Termux, a DRM device, or EGL. The Android baseline is API 34.

- [Boundaries](#boundaries)
- [Linux Buffer Import](#linux-buffer-import)
- [Protocol Adapters](#protocol-adapters)
- [Completion And Failure](#completion-and-failure)
- [Validation](#validation)

## Boundaries

App-side launches pass `GraphicalLaunchOptions` directly to both session backends.
Connection mode, file-environment identity, keyboard data and startup class are
runtime configuration, independent of optional Recent history. A launch without
a persisted shortcut retains those options without creating a history entry.

- `MdgDevice` owns rendering resources on one serialized worker/event loop.
  Devices are independent; there is no process-global rendering lock.
- `MdgImage` retains an AHardwareBuffer, imports a linear DMA-BUF, or owns/borrows CPU storage. Pixel format,
  row stride and acquire fences are explicit. Imported images retain their
  contents across partial updates.
- `MdgPass` records clipped textured quads and solid rectangles, with premultiplied
  alpha, nearest/bilinear filtering and all eight orthogonal transforms. Three
  reusable command slots retain their images through GPU completion. Descriptor
  sets and staging storage are reused, growing only when capacity is insufficient.
- `MdgSurface` owns Android WSI presentation. Buffer acquisition and presentation
  run on an output worker, never while holding a producer's pixel mutex. The
  software output uses the public ANativeWindow buffer API. Unsupported WSI
  capabilities select this output path without requiring a different protocol host.
- `MdgReadback` converts a retained image into CPU-readable RGBA storage without
  waiting on the compositor event loop. It returns owned readiness fences for
  producer completion, available command slots and target completion. The caller
  observes those events, retains the producer lease, and may cancel or replace
  the request. Same-sized target storage is reused; submitted GPU work retains
  resources independently of cancellation. Reading consumes the ready result.

The Vulkan backend dynamically loads the system driver and checks required
extensions. AHardwareBuffer is the Android interchange allocation, not a claim
that arbitrary Linux DMA-BUFs are compatible with the device's driver. The
graphics library does not bundle Mesa or a device-specific driver. Native buffer
references follow the [Vulkan AHardwareBuffer import contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkImportAndroidHardwareBufferInfoANDROID.html).

## Linux Buffer Import

`MdgLinearDmaBuf` describes one explicitly linear RGBA/RGBX/BGRA/BGRX plane,
including its byte offset and stride. The optional Vulkan transfer importer
retains the FD and copies its pixels into a reusable GPU texture. When native
Vulkan buffer requirements exceed the producer's allocation, it imports only
complete rows that fit and uploads the remaining suffix through cached staging
storage. Only that suffix is CPU-mapped/read; a small allocation can consist
entirely of the suffix. Actual Vulkan requirements are checked again for the
prefix. It does not reinterpret tiled allocations as linear or
construct Android hardware-buffer handles from raw FDs. Imported DMA-BUF images
are sampling sources, not render targets or CPU-readable images.

Capability discovery checks external-memory extensions and transfer-buffer
import support independently of compositor startup. Each allocation must also
pass layout, size, memory-type and kernel synchronization checks. Ordinary
memfds, unsupported layouts and missing fence ioctls are rejected. The software
backend does not claim DMA-BUF support; existing SHM/AHardwareBuffer paths remain
independent.

Every submission exports the producer's implicit write fences into a Vulkan
wait semaphore and publishes its completion as an implicit read fence. The
producer must honor those read fences before overwriting or recycling storage,
and retain the consumer's buffer lease through submission.
This uses the kernel's [DMA-BUF sync-file bridge](https://kernel.org/doc/html/next/driver-api/dma-buf.html),
not a settling delay. A CPU suffix needs completed producer writes: submission
returns `MDG_SUBMIT_DEFERRED` with an owned fence without changing pixels or
publishing a completion. The caller observes that event and retries the retained
recording, or cancels it. Wayland cancels the unsent pass, preserves scene damage
and watches the fence for that output only; replacement content can supersede the
wait. Other outputs and protocol dispatch remain independent. Pixel-lock workers
can use the bounded blocking submission helper. DMA-BUF CPU cache synchronization
brackets the suffix copy after readiness, not before it.

GPU resources are cached per import/command
slot; fence FDs have per-submission ownership. A failed read-fence publication
after submission rejects the frame and disables that GPU owner without releasing
in-flight resources or starting a racing software copy.

Import rejection reports the failing operation and its errno/Vulkan result,
with available/required byte counts for layout and allocation checks. Buffer
contents and file paths are not included.

Wayland exposes these capabilities through `linux-dmabuf` v3. Its focused
protocol adapter imports only the four advertised RGB formats with an explicit
linear modifier, validates buffer parameters and owns the client's allocation as
a `wlr_buffer`. Textures retain that buffer while used by the scene; submitted
GPU work independently retains its imported image and publishes implicit read
fences. `wl_buffer.release` releases the scene's reference, not a promise that
the producer may ignore those fences. Buffer imports and FD duplication occur
at creation, not per frame.

Version 3 does not require DRM feedback. wlroots' feedback implementation needs
a real DRM device identity, which an Android Vulkan driver need not expose; the
adapter uses wlroots' buffer/renderer interfaces without changing wlroots or
pretending that KGSL is a DRM render node. Clients binding v1/v2 receive no
formats because those versions cannot negotiate explicit modifiers. A renderer
without DMA-BUF support exposes no DMA-BUF global. SHM remains available.

## Protocol Adapters

### Client GL Loaders

Client EGL/GL libraries belong to the selected Linux environment, not the APK.
Mesa's Wayland EGL loader normally discovers a DRM render node through compositor
feedback or `wl_drm`. Android KGSL plus Vulkan WSI does not supply that identity.
Consequently, installing a working Vulkan driver does not by itself make EGL's
default Zink path work. Software GL remains independent of this limitation.

The optional [Mesa 26.2.3 patch in the Linux installer](../scripts/install_linux.sh)
routes explicitly selected Zink through Kopper. A non-DRM EGLDevice uses the
Gallium driver's acceleration capability and physical-device UUID rather than
assuming that a swrast loader means software rendering. Hardware devices do not
advertise nonexistent DRM nodes; CPU drivers retain the software device. The
same admission path handles Wayland, X11 and surfaceless contexts. On RM11/API 36,
Termux Mesa/Turnip with this client patch
reports OpenGL 4.6 and GLES 3.2 on Adreno 840; GTK4's GL renderer and GLArea present
linear DMA-BUFs and respond to pointer input. The unmodified loader fails before
that path; forcing only `GALLIUM_DRIVER=zink` can instead produce empty SHM frames.
This is a tested client-side patch, not a bundled driver or general Mesa support
claim. Different GPUs and distribution builds require their own checks.

The [Linux installer](shroot.md#linux-installer) builds a complete,
private Linux client stack for `--gpu turnip` and retains a software alternative.
All distribution recipes share this patch and build identity; only package
dependencies differ. Its `--print-mesa-patch` export is also the lab's patch
source. Existing environments need `--resume --gpu turnip` with the updated
installer; installing an APK alone does not rebuild their private Mesa.

Private Mesa libraries carry loader search paths for their own directory and
the installed prefix. GBM locates its backend through `$ORIGIN/gbm`, so moving
the stack into a Flatpak extension does not leave an inaccessible host path in
the allocator. These are build options, not an additional Mesa source patch.
Successful Turnip activation also publishes owned Vulkan and EGL manifests in
`/etc/vulkan/icd.d` and `/etc/glvnd/egl_vendor.d`, with absolute library paths.
The private EGL manifest uses an absolute path too. This lets capability-bearing
executables, including KWin with `cap_sys_nice`, discover the same stack even
when the loader removes `LD_LIBRARY_PATH` and Vulkan ignores environment
overrides. Distribution manifests and system libraries remain untouched;
software selection removes only MagicDesk's two manifests.

The GLX patch advertises callbacks from the effective loader backend, including
when an application sets `LIBGL_KOPPER_DISABLE`. The Turnip profile explicitly
selects Zink for both Kopper and the software-presentation frontend. Disabling
Kopper does not disable GPU rendering: the frontend can present a readback of
the GPU image. That fallback requires an actual presentation callback. Kopper's
loader-less DRI winsys advertises none, so flushing its offscreen images remains
a no-op instead of dereferencing absent loader functions. The GLX fixture checks
both GPU readback and actual X11 pixels, not just the renderer string.

For Vulkan X11 surfaces without DRI3, the shared Mesa patch uses GPU rendering
with CPU-readable presentation images when the X server supports Present.
Readback is uploaded to ordinary X pixmaps; Present completion and idle events
retain frame pacing and image ownership. This is not zero-copy presentation.
DRI3 and native Wayland surfaces retain their existing paths. The XCB and Xlib
fixtures exercise immediate, mailbox and FIFO presentation; SteamOS DDNet also
renders through Turnip with this path. Installing the APK alone does not update
the guest driver.

The same patch implements Turnip KGSL calibrated timestamps with
`IOCTL_KGSL_READ_CALIBRATED_TIMESTAMPS`. Extension discovery probes the selected
device: if the ioctl is unavailable or denied, neither the KHR nor EXT calibrated
timestamp extension is advertised. Ordinary GPU timestamp queries remain
available; Zink can use its queue-query fallback. This policy belongs to the
client driver, not a KWin-specific timer override in MagicDesk.

Build a separate EGL vendor library using the exact installed Mesa version and
the environment's normal build patches (including Termux's Android-detection
patch). Select it for the test process through a private GLVND JSON file and
`__EGL_VENDOR_LIBRARY_FILENAMES`; do not replace the system vendor library.
Use `MESA_LOADER_DRIVER_OVERRIDE=zink`, the intended Vulkan ICD and no
`LIBGL_ALWAYS_SOFTWARE`. Check `eglinfo -B -p wayland`, actual pixels, input and
`WAYLAND_DEBUG=client` buffer submissions. A renderer string alone is not proof
that the client presented a frame.

### Guest Desktop Services

`magicdesk-desktop-session` reuses an inherited D-Bus session, or creates a
private one when launched directly. When that bus belongs to a running systemd
user manager, the desktop runs in its own transient scope bound to
`graphical-session.target`. Scope lifetime supplies the dependency required by
session services such as desktop portals; closing one session does not manually
stop the shared target. OpenRC, runit and private D-Bus launches do not acquire a
systemd dependency.

The recipe's fallback Weston configuration invokes
`magicdesk-desktop-session --ready` after compositor startup to publish the child
display and audio endpoints for D-Bus activation. User and distribution Weston
configuration take precedence. A custom `[autolaunch]` program should invoke the
same ready hook before starting applications. The hook publishes only the
explicit display/audio variables, not the whole process environment.

### Flatpak GPU Drivers

Flatpak clients need a GL extension inside their runtime; host driver paths and
environment variables alone are insufficient. The Turnip recipe installs
`magicdesk-flatpak-gl`, which first tries to reuse the selected Mesa build.
It reads installed ARM64 runtime
metadata, relocates the EGL/Vulkan manifests, and publishes Flatpak's automatically
selected local `org.freedesktop.Platform.GL.host` extension. The helper runs after
graphics setup, on desktop-session startup and through a Flatpak install/update
trigger. It can also be run manually inside the guest:

```sh
magicdesk-flatpak-gl
```

These automatic paths only publish already-built libraries; they never install
an SDK or compile a second Mesa, even when an SDK is already installed.
NixOS uses the same publisher with Nix-resolved tools and immutable output
identity. With `services.flatpak.enable`, its trigger directory retains the
distribution's triggers and adds the GL publisher for both user installations
and the system helper. The applications profile also exports this directory for
direct Shroot sessions without PAM. Nix store contents remain unchanged.
Graphics profiles share environment cleanup: reapplying or switching profiles
removes only previously owned library paths and clears the prior driver
overrides, retaining unrelated caller library paths.

Each runtime sharing a GL branch must pass a sandboxed loader/dlopen check,
including nested GBM backends, before
publication. The probe is a small shared module with no linked libc dependency;
the target runtime's loader supplies its C functions. This also permits checking
glibc runtimes from a musl distribution. Success requires the probe's explicit
completion marker, not merely a zero loader exit code.
A runtime commit change repeats admission without copying unchanged
Mesa libraries. This detects missing dependencies/symbols, not every possible
driver/runtime incompatibility. If the runtime loader rejects the host libraries,
the helper reports the incompatibility without publishing that driver. This can
happen when the distribution's libc is newer than the Flatpak runtime. A separate
Mesa build using the same patched sources and options inside the matching SDK
is available only through an explicit command:

```sh
magicdesk-flatpak-gl --build-sdk
```

No host libc or binary Python modules enter that SDK build. This is optional
preparation, not part of the distribution installation or desktop launch.
It requires the retained source/build configuration from the FHS Turnip recipe.
Nix-managed Mesa does not retain that SDK build tree; an incompatible Nix output
must be replaced with a compatible Mesa build, not silently rebuilt in Flatpak.

The SDK must already be installed in the same user/system installation. A missing
SDK produces its exact `flatpak install` command; run it, then repeat
`magicdesk-flatpak-gl --build-sdk`. The helper does not start nested installation transactions
from Flatpak's trigger. SDK builds retain their objects on failure and reuse a
completed prefix keyed by source/configuration and SDK deployment; changing the
compiler job count does not invalidate it. Each consuming runtime must still pass
the loader check before publication. These builds can take as long as the original
Mesa build, but they are not repeated on ordinary application launches.

Admission failure is reported and withdraws the
owned extension; independently installed `GL.host` directories are not replaced.
New Mesa builds replace the extension atomically. Old bundles remain under the
installation's `.magicdesk-gl/retired-*` directory because running sandboxes can
still use them; they are not automatically reclaimed.

The helper adds Flatpak global `unset-environment` entries for inherited host
`VK_DRIVER_FILES`, `VK_ICD_FILENAMES`, `__EGL_VENDOR_LIBRARY_FILENAMES` and
`LIBGL_DRIVERS_PATH` and `GBM_BACKENDS_PATH`. Runtime loader discovery then selects the extension's
libraries, not inaccessible `/opt` paths. Existing explicit global driver choices,
per-app overrides and filesystem permissions remain intact. These unsets also
remain useful if the selected host GPU profile later changes.

SteamOS/Freedesktop 25.08 passed the actual install trigger, three ordinary
sandboxed Chromium Zink/Turnip WebGL pixel/normal-exit cycles, and a Wayland window
check with frames, pointer input and normal closure. Sandbox and GPU watchdog
stayed enabled. This checks Zink/OpenGL, not Chromium's separate ANGLE/Vulkan
Wayland output path, other runtime ABIs or document-portal support.

### Snap GPU Drivers

Every Turnip recipe also installs `magicdesk-snap-gpu`. This installs the helper,
not snapd or the content snap: preparation runs explicitly once the selected
Linux has the prerequisites. It is not an application-launch hook. Run it as root **inside the
selected Linux**, with systemd/snapd running, `core24` and `mesa-2404` installed
and mounted, and `python3`, `bubblewrap` and `squashfs-tools` available. Close GPU
Snap applications before preparation or changing the selected Mesa build:

```sh
sudo magicdesk-snap-gpu --jobs 2
```

The helper reuses the selected Mesa prefix from `/etc/magicdesk/mesa-prefix`.
It checks the libraries against the actual core24 loader and mesa-2404 libraries,
then packs a local `magicdesk-gpu-2404` content snap. No Mesa or browser rebuild
is performed. `--build-only` verifies and packages without installing or changing
connections; `--prefix PATH` selects another completed recipe build explicitly.

The local snap connects to `mesa-2404:kernel-gpu-2404`. The official provider's
component monitor publishes it, and applications retain their usual
`mesa-2404:gpu-2404` connection. This follows the provider's
[kernel GPU content contract](https://github.com/canonical/mesa-2404), not a
Firefox-specific mount or runtime exception. Absolute host driver paths are
replaced with paths inside the mounted content. The helper will not replace an
already connected third-party provider. A bounded inotify wait checks the
monitor's publication marker; starting its service alone is not success.
If the requested content is not yet published, preparation restarts the monitor
through snapd so it acquires the updated content mount. An unchanged, already
published bundle does not restart the service.

`snap install --dangerous` here admits the locally built unsigned driver package;
it does not request classic/devmode or disable the application's seccomp sandbox.
Shroot's existing AppArmor limitations still apply. Re-run preparation after
changing Mesa or the base/provider revision. Unchanged verified bundles are
reused; old package files remain cached. Remove the override with
`sudo snap disconnect mesa-2404:kernel-gpu-2404 magicdesk-gpu-2404:kernel-gpu-2404`
and restart the applications.

Ubuntu 24.04/core24 on Adreno 840 passed Vulkan enumeration, Wayland EGL
OpenGL 4.6 through Zink/Turnip, and Firefox Snap WebGL2 pixel rendering with
keyboard/pointer input, download, portal file selection and normal exit. With
the corrected non-DRM EGLDevice, Firefox 158 also reports hardware `WebRender`,
one accelerated window and effective content sandbox level 6, without forced
graphics preferences. This does not enable DRM-dependent video decoding or
Firefox's separately gated native compositor.
Other Mesa/base combinations must pass ABI admission; a newer host glibc is
not made compatible merely by copying its driver libraries.

### Server Composition

The installer's optional KWin patch is a Linux client-side nested-compositor
adapter. It accepts DMA-BUF v3 format/modifier events without inventing a DRM
device. A Wayland EGL display and linear DMA-heap allocator feed KWin's existing
`EglSwapchain`; the normal DRM/GBM path remains independent. The non-DRM path
requires real EGL import and native-fence capabilities and probes allocation,
image import and sync-file exchange before advertising GPU composition.
Its nested Wayland server publishes v3 import formats without DRM feedback.
Internal Qt windows use the backend's allocator rather than assuming GBM.
DRM syncobj and DMA-BUF screencasting require an actual DRM device.
DMA-BUF reservation fences cover output reuse, internal Qt rendering and sampled client buffers;
dependencies are queued on the GPU, not waited on by the compositor thread.
Software composition remains selectable in the Linux installer. These changes
do not alter MagicDesk's renderer identity, Android hosts or display ownership.
An unavailable forced OpenGL backend fails startup with a nonzero exit status;
it does not silently start a QPainter workspace.

X11's native `LorieGraphics` contract receives a host-owned implementation from
`x11-runtime`. The engine owns X protocol, output selection, window families and
Present ordering; it does not know Java classes or MagicDesk placement. Its
shared pixel mutex covers producer access through rendering completion. Android
buffer acquisition and presentation occur outside that mutex. Present serials
are acknowledged only after reading their source buffers has completed. A
missing registration waits for the registration event without consuming the
queued copy. Output snapshots reuse storage rather than allocate every frame.

The optional X11 EXA linear-DMA-BUF copy accelerator remains a separate
producer-side operation. It writes X pixmaps and falls back to Xorg CPU copies;
it is not a second window compositor or Android presentation path.
X11's DRI3 export additionally promotes private CPU pixmaps to live linear
DMA-heap storage when a client requests it. The X engine owns this allocation,
row-stride publication and CPU cache synchronization; the shared renderer still
borrows buffers through its existing interfaces. Export does not inspect private
Android hardware-buffer handles or require a DRM render node.

Wayland implements wlroots' renderer and allocator interfaces without modifying
wlroots. SHM clients are uploaded into textures; composition targets retained
HardwareBuffers. Linear DMA-BUF clients use the optional GPU transfer importer.
Client GL/Vulkan acceleration and compositor acceleration are separate
capabilities; an advertised layout still requires a compatible client allocation.

`HostedFrame` carries a HardwareBuffer with an acquire sync-fd across Binder,
or a sealed RGBA memfd for software-only storage. One outstanding frame per
output limits producer work. The wlroots buffer remains locked until the host
finishes reading it. `HostedFramePresenter` owns the Android output worker,
generation checks and imported-buffer cache. It draws the source into Android's
swapchain; this is a GPU copy, not direct scanout or CPU readback. Android's WSI
acquire fence is waited on that worker; it is not exported as a cross-process
semaphore. Session control and input remain independent of the output queue.

## Completion And Failure

Sync-fd export distinguishes an already completed operation from descriptor
duplication failure. A timeout does not authorize buffer reuse. Queued resources
remain retained through completion or device teardown; device loss cannot race
a CPU fallback against submitted GPU work. The software backend can be selected
explicitly in native fixtures and is used when Vulkan initialization is unavailable.
Output failures reject their presentation receipt rather than granting input
admission without pixels. Renderer teardown records backend/frame counters for
diagnosis without per-frame logging.

## Validation

Native fixtures compare GPU/software colors, partial updates, RGBA/BGRA imports,
clipping, blending, lifetime and transforms. The Wayland renderer fixture compares
its results to wlroots' Pixman implementation. Android instrumentation checks the
cross-UID frame path, visible pixels, input, transparency and Surface replacement.
Desktop self-tests do not replace these checks. Device coverage on one Android
release is not proof of compatibility on another.

The Android `graphics-dmabuf-test` fixture uses separate producer and consumer
Vulkan devices, GPU-written linear buffers, nonzero offsets, padded rows and all
four formats. It queues producer overwrites before waiting for consumer readback
to exercise acquire/release synchronization, and checks invalid-FD rejection,
structured import errors and retained-buffer lifetime. Test-owned imports also
exercise a CPU suffix, fully staged small images, deferred/cancelled submissions
and nonblocking submission of an independent output while a producer is gated.
Readback fixtures cover software/GPU pixels, target reuse, replacement and
cancellation before producer completion. Its default capability skip is distinct from a pass;
`--required` makes unavailable DMA-BUF support a test failure.

`wayland-dmabuf-test` exercises protocol advertisement, asynchronous/immediate
creation, invalid requests, all four GPU-produced formats, scene retention,
release and destruction of client protocol objects. Its software case checks
that no unsupported global is exposed. Android instrumentation accepts
`-e dmabuf true` with `wayland-window-test` to verify GPU-produced client pixels
through the compositor, cross-UID transport and Android Surface, with input and
closure through the same host contracts as SHM clients.

The Android-only `graphics-native-handle-test` probes the optional VNDK
`AHardwareBuffer_createFromHandle` entry point without adding a production
dependency on it. A separate process sends Android allocation metadata, native
handle FDs and a producer fence. The consumer clones the handle, closes the
received FDs, releases the AHardwareBuffer wrapper and verifies GPU/software
sampling after the producer exits, for RGBA, RGBX and BGRA. Missing entry points
return the explicit capability-skip status 77; failed imports or pixels fail.

MagicDesk does not advertise `android_wlegl`. Its
[protocol](https://github.com/libhybris/libhybris/blob/master/hybris/platforms/common/wayland-android.xml)
transports Android allocation metadata but has no acquire/release-fence messages.
A production adapter needs a compatible client allocator and completion-safe
buffer recycling, not only a successful handle import. Recipe Mesa clients use
the existing linear DMA-BUF path; an Android native-handle protocol alone does
not change their allocations. The private import capability is defined by
[AOSP's VNDK contract](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/nativewindow/include/vndk/hardware_buffer.h),
not guaranteed by Android API level or vendor identity.
