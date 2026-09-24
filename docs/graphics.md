# Hosted Graphics

`hosted-runtime/src/main/cpp/graphics` owns the shared X11/Wayland composition
backend. It uses Android's Vulkan driver when the required capabilities are
available and retains a software backend. It does not require Desktop, root,
Termux, a DRM device, or EGL. The Android baseline is API 34.

## Boundaries

- `MdgDevice` owns rendering resources on one serialized worker/event loop.
  Devices are independent; there is no process-global rendering lock.
- `MdgImage` retains an AHardwareBuffer or owns/borrows CPU storage. Pixel format,
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

The Vulkan backend dynamically loads the system driver and checks required
extensions. AHardwareBuffer is the Android interchange allocation, not a claim
that arbitrary Linux DMA-BUFs are compatible with the device's driver. The
graphics library does not bundle Mesa or a device-specific driver. Native buffer
references follow the [Vulkan AHardwareBuffer import contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkImportAndroidHardwareBufferInfoANDROID.html).

## Protocol Adapters

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

Wayland implements wlroots' renderer and allocator interfaces without modifying
wlroots. SHM clients are uploaded into textures; composition targets retained
HardwareBuffers. The adapter advertises only formats it can import. Linux
DMA-BUF client import is not advertised. Client GL/Vulkan acceleration and
compositor acceleration are separate capabilities.

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

Native fixtures compare GPU/software colors, partial updates, RGBA/BGRA imports,
clipping, blending, lifetime and transforms. The Wayland renderer fixture compares
its results to wlroots' Pixman implementation. Android instrumentation checks the
cross-UID frame path, visible pixels, input, transparency and Surface replacement.
Desktop self-tests do not replace these checks. Device coverage on one Android
release is not proof of compatibility on another.
