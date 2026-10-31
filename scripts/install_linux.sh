#!/bin/sh
# Run from a saved file in MagicDesk's Shell console; stdin is used for questions.
set -eu

# Raise this floor only when recipes require newer APK behavior.
MIN_MAGICDESK_VERSION_CODE=224
MIN_MAGICDESK_VERSION_NAME=2.1.2
readonly MIN_MAGICDESK_VERSION_CODE MIN_MAGICDESK_VERSION_NAME

usage() {
    cat <<'HELP'
Usage: sh install_linux.sh [--distro ID] [OPTIONS]

Install an ARM64 Linux userspace in Shroot. Choose applications or a desktop
independently of the distribution. Installation output stays in this console.
New environments use software rendering unless Turnip is selected; SteamOS
defaults to automatic Turnip detection and a Wayland Plasma desktop.

  --distro ID   Debian 13 (default), Ubuntu 24.04, Alpine 3.23, Fedora 44,
                Arch Linux ARM, openSUSE Tumbleweed, openKylin 3.0, Deepin 25,
                Manjaro ARM, Void (glibc), Armtix (runit), NixOS 26.05, or SteamOS ARM64
  --name NAME   Independent environment name (default: distribution name)
  --image REF   Alternative OCI reference for the SAME distribution/version
  --downloads DIR  Verified archive/wheel cache (default: $HOME/downloads)
  --gui MODE    none, apps (default), xfce, weston, plasma (Debian 13/SteamOS/NixOS),
                gnome (Fedora devkit), keep
                apps: Mousepad, Thunar, Xfce Terminal; xfce: X11 desktop;
                weston: Wayland desktop; plasma: X11 and nested Wayland desktops;
                gnome: experimental nested shell
  --protocol P  x11, wayland, both (default); installer application entries
  --gpu keep    Retain the graphics profile (default)
  --gpu auto    Try Turnip when KGSL is accessible, otherwise use software
  --gpu software  Use the distribution's software renderer
  --gpu turnip  Build private Mesa/Zink/Turnip for a supported Adreno KGSL GPU
  --jobs N      Compiler jobs (Mesa/KWin/Weston and Nix), 1-8 (default: 4)
  --locale L    system (Android locale; default for new installs), keep,
                or a UTF-8 locale such as en_US.UTF-8; resume defaults to keep
  --timezone Z  IANA zone, system (Android zone), or keep (default)
  --create-user USER  Create an optional guest account, with a locked password
                Select it later with login --user or the shortcut User field;
                this does not change the image account or grant Android root
  --fonts F    basic (default), cjk (also Chinese/Japanese/Korean), keep
  --package P   Additional repository package (repeatable)
  --cache C     keep (default), clean (package downloads only, not Mesa work)
  --dns POLICY  auto (default: current network), preserve (image DNS), or IP[,IP...]
                auto/system copies the current network's DNS once; VPN is allowed
                Android Private DNS needs an explicit guest DNS choice
  --yes         Accept the selected configuration without questions
  --resume      Continue in --name NAME; detect distribution, preserve GUI by default
  --list        Show available distribution/profile combinations
  --print-system-setup  Print the guest setup script without running it
  --print-nixos-setup  Print the NixOS setup script without running it
  --print-nixos-module  Print the generated NixOS module
  --print-graphics-setup  Print the guest graphics setup script without running it
  --print-weston-setup  Print the guest Weston compatibility recipe
  --print-mesa-patch  Print the embedded patch without running the installer
  --print-flatpak-gl-helper  Print the embedded Flatpak GL publisher
  --print-snap-gpu-helper  Print the embedded Snap GPU publisher
  --print-kwin-patch [6.6.6|6.3.6|6.2.5]  Print the matching embedded KWin patch
  --print-recovery-reader  Print the embedded SteamOS image converter

Turnip builds inside Linux, needs several GB of free space and can take a while.
The source version and SHA-256 are pinned; no Android or distribution drivers are replaced.
Failed builds retain the previous profile and can be resumed. New launches use
the selected profile; already running programs are not restarted.
Void: set FORCE_IPV4=1 before this command if the network has no working IPv6.

No Termux or Android root is required. Use trusted images: Shroot is not a
security sandbox. The current shell/root identity is never changed.
GUI profiles include PipeWire audio, SDL gamepad discovery and Wayland decorations.
Graphical Shroot launches receive Android gamepads automatically; no host device binds.
GNOME devkit needs a working Linux system bus; this installer does not boot system services.
SteamOS retains its supplied applications, fonts and system drivers. The default
--gui plasma --gpu auto adds a nested desktop and a private graphics profile.
It needs about 35 GB free during conversion, not a booted recovery/VR session.
Its packages are updated from the image's configured repositories before first use.
NixOS generates /etc/nixos/magicdesk.nix and selects a system generation with
nixos-rebuild boot. Edit configuration.nix for your own settings; --resume
retains it. Plasma is the default desktop. Packages use Nix attributes, for example --package ripgrep.
Graphical sessions run independently; selecting a generation does not boot its system services.
HELP
    printf '\nRequires MagicDesk %s or newer (versionCode %s).\n' \
        "$MIN_MAGICDESK_VERSION_NAME" "$MIN_MAGICDESK_VERSION_CODE"
}

configurations() {
    printf '%s\n' 'Distribution  Version     Source                                   GUI' \
        'debian        13          Debian official slim rootfs               none/apps/xfce/weston/plasma' \
        'ubuntu        24.04       Ubuntu Base official rootfs               none/apps/xfce/weston' \
        'alpine        3.23        Alpine official minirootfs                none/apps/xfce/weston' \
        'fedora        44          registry.fedoraproject.org/fedora:44       none/apps/xfce/weston/gnome' \
        'arch          rolling     Arch Linux ARM official rootfs            none/apps/xfce/weston' \
        'opensuse      Tumbleweed  openSUSE official LXC rootfs               none/apps/xfce/weston' \
        'openkylin     3.0         openkylin/openkylin:latest                 none/apps/xfce/weston' \
        'deepin        25          linuxdeepin/deepin:crimson-arm64            none/apps/xfce/weston' \
        'manjaro       rolling     Manjaro ARM official rootfs               none/apps/xfce/weston' \
        'void          rolling     Void official glibc rootfs                none/apps/xfce/weston' \
        'armtix        rolling     Armtix official runit rootfs              none/apps/xfce/weston' \
        'nixos         26.05       NixOS official LXC rootfs                 plasma/none/apps/xfce/weston' \
        'steamos       0.3.0       Valve Steam Frame recovery                plasma/keep (supplied applications)'
    printf '%s\n' 'GUI profiles include Android audio and gamepad client libraries.' \
        'GNOME devkit requires a Linux system bus.'
}

# Package-manager differences stay here; setup and the Mesa recipe share them.
distribution_support() {
    cat <<'DISTRO'
set -eu
umask 022
[ ! -r /etc/os-release ] || . /etc/os-release
ID=${ID:-unknown}
case "$ID" in
    debian|ubuntu|openkylin|deepin) family=apt ;;
    alpine) family=apk ;;
    fedora) family=dnf ;;
    arch|archarm|manjaro-arm|manjaro|artix) family=pacman ;;
    opensuse-tumbleweed) family=zypper ;;
    void) family=xbps ;;
    steamos) family=recovery ;;
DISTRO
    printf '%s\n' '    *) echo "Unsupported distribution: $ID" >&2; exit 1 ;;'
    cat <<'DISTRO'
esac
packages() {
    case "$family" in
        apt) apt-get install -y --no-install-recommends "$@" </dev/null ;;
        apk) apk add "$@" </dev/null ;;
        dnf) dnf -y --setopt=install_weak_deps=False install "$@" </dev/null ;;
        zypper) zypper --non-interactive install --no-recommends "$@" </dev/null ;;
        xbps) xbps-install -y "$@" </dev/null ;;
        pacman|recovery)
            # Respect installed providers (e.g. ninja-is-samurai). Explicit -S
            # otherwise replaces them even when the dependency is satisfied.
            missing=$(pacman -T "$@") || { code=$?; [ "$code" = 127 ] || return "$code"; }
            if [ -n "$missing" ]; then
                set -f
                set -- $missing
                set +f
                pacman -S --needed --noconfirm "$@" </dev/null
            fi ;;
        *) echo 'Package setup is unavailable for this image.' >&2; exit 1 ;;
    esac
}
prepare_initramfs() {
    mkdir -p /etc/magicdesk /usr/local/libexec /etc/pacman.d/hooks
    cat > /etc/magicdesk/mkinitcpio.conf <<'INITRAMFS_CONFIG'
# A container has no boot block device or matching physical module inventory.
# Keep portable images and fsck tools instead of detecting Android's hardware.
if [ -n "${container:-}" ] || [ "$(findmnt -n -o FSTYPE / 2>/dev/null)" = guestfs ]; then
    read -r -a _md_hooks <<< "${HOOKS[*]}"
    HOOKS=()
    for _md_hook in "${_md_hooks[@]}"; do
        [ "$_md_hook" = autodetect ] || HOOKS+=("$_md_hook")
    done
    unset _md_hooks _md_hook
fi
INITRAMFS_CONFIG
    cat > /usr/local/libexec/magicdesk-initramfs-config <<'INITRAMFS_HELPER'
#!/bin/sh
set -eu
config=/etc/mkinitcpio.conf
[ -f "$config" ] || exit 0
line='[ ! -r /etc/magicdesk/mkinitcpio.conf ] || . /etc/magicdesk/mkinitcpio.conf'
grep -qxF "$line" "$config" && exit 0
# Presets with ALL_config bypass mkinitcpio.conf.d, so use the main config.
trap 'rm -f "$config.magicdesk-new"' 0
cp -p "$config" "$config.magicdesk-new"
printf '\n%s\n' "$line" >> "$config.magicdesk-new"
bash -n "$config.magicdesk-new"
mv "$config.magicdesk-new" "$config"
INITRAMFS_HELPER
    chmod 755 /usr/local/libexec/magicdesk-initramfs-config
    /usr/local/libexec/magicdesk-initramfs-config
    cat > /etc/pacman.d/hooks/80-magicdesk-initramfs-config.hook <<'INITRAMFS_HOOK'
[Trigger]
Operation = Install
Operation = Upgrade
Type = Path
Target = etc/mkinitcpio.conf

[Action]
Description = Prepare portable container initramfs configuration
When = PostTransaction
Exec = /usr/local/libexec/magicdesk-initramfs-config
INITRAMFS_HOOK
}
refresh_packages() {
    case "$family" in pacman|recovery) prepare_initramfs ;; esac
    case "$family" in
        apt) apt-get update </dev/null ;;
        apk) apk update </dev/null ;;
        zypper) zypper --non-interactive refresh </dev/null ;;
        xbps)
            # XBPS must upgrade itself before the rest of a rolling rootfs.
            xbps-install -Suy xbps </dev/null
            xbps-install -uy </dev/null ;;
        pacman)
            case "$ID" in
                manjaro|manjaro-arm) keyrings='archlinuxarm manjaro manjaro-arm' ;;
                artix) keyrings=artix ;;
                arch|archarm) keyrings=archlinuxarm ;;
                *) keyrings= ;;
            esac
            if [ -n "$keyrings" ]; then
                pacman-key --init </dev/null
                pacman-key --populate $keyrings </dev/null
            fi
            # Rolling repositories require a complete userspace upgrade.
            pacman -Syu --noconfirm </dev/null ;;
        recovery)
            # Retain the image repository branch, keyring and signature policy.
            printf 'Updating SteamOS packages from the image repositories...\n'
            printf 'Kernel/service hooks may warn about the non-booted environment. Android is not updated.\n'
            pacman -Syu --noconfirm </dev/null ;;
    esac
}
package_cleanup() {
    # pacman-key's service is scoped to the package keyring, not a user's GPG home.
    if { [ "$family" = pacman ] || [ "$family" = recovery ]; } && command -v gpgconf >/dev/null 2>&1; then
        gpgconf --homedir /etc/pacman.d/gnupg --kill all || {
            echo 'Could not stop the package-keyring GPG services.' >&2
        }
    fi
}
DISTRO
}

mesa_patch() {
    cat <<'PATCH'
Mesa 26.2.3: explicitly selected Zink uses Kopper without a DRM render node.
Non-DRM EGL devices follow the actual Gallium renderer's acceleration and UUID,
including surfaceless contexts. Software drivers remain software devices.
KGSL calibrated timestamps use the kernel timestamp ioctl when available;
unsupported kernels do not advertise that extension. Queue queries are unchanged.
GLX loader capabilities follow the effective backend when Kopper is disabled.
Zink retains GPU rendering with sw_winsys readback presentation when the client
disables Kopper or the X server lacks DRI3. Direct Vulkan uses GPU readback into
ordinary X11 pixmaps when DRI3 is absent; Present retains pacing and buffer-release
events. CPU images do not acquire DMA-BUF synchronization resources.
Readback requires a presenting winsys; Kopper's loader-less DRI winsys does not
advertise that callback, so offscreen flushes retain their original no-op behavior.
Worker queues start on their first submitted job, not empty initialization, so
driver enumeration/context setup does not prevent a later single-thread sandbox.
Explicit worker counts and normal parallel scheduling are retained after startup.

--- a/src/util/u_queue.c
+++ b/src/util/u_queue.c
@@ -44,6 +44,10 @@

 /* Define 256MB */
 #define S_256MB (256 * 1024 * 1024)
+/* Private state: num_threads is the requested count until the first job.
+ * Empty queues must not create threads before an application enters a sandbox.
+ */
+#define UTIL_QUEUE_DEFERRED (1u << 31)

 static void
 util_queue_kill_threads(struct util_queue *queue, unsigned keep_num_threads,
@@ -325,6 +329,8 @@
 {
    struct thread_input *input =
       (struct thread_input *) malloc(sizeof(struct thread_input));
+   if (!input)
+      return false;
    input->queue = queue;
    input->thread_index = index;

@@ -362,6 +368,13 @@

    unsigned old_num_threads = queue->num_threads;

+   if (queue->flags & UTIL_QUEUE_DEFERRED) {
+      queue->num_threads = num_threads;
+      if (!locked)
+         mtx_unlock(&queue->lock);
+      return;
+   }
+
    if (num_threads == old_num_threads) {
       if (!locked)
          mtx_unlock(&queue->lock);
@@ -400,8 +413,6 @@
                 unsigned flags,
                 void *global_data)
 {
-   unsigned i;
-
    /* Form the thread name from process_name and name, limited to 13
     * characters. Characters 14-15 are reserved for the thread number.
     * Character 16 should be 0. Final form: "process:name12"
@@ -431,7 +442,7 @@
    }

    queue->create_threads_on_demand = true;
-   queue->flags = flags;
+   queue->flags = flags | UTIL_QUEUE_DEFERRED;
    queue->max_threads = num_threads;
    queue->num_threads = 1;
    queue->max_jobs = max_jobs;
@@ -452,20 +463,6 @@
    if (!queue->threads)
       goto fail;

-   /* start threads */
-   for (i = 0; i < queue->num_threads; i++) {
-      if (!util_queue_create_thread(queue, i)) {
-         if (i == 0) {
-            /* no threads created, fail */
-            goto fail;
-         } else {
-            /* at least one thread created, so use it */
-            queue->num_threads = i;
-            break;
-         }
-      }
-   }
-
    add_to_atexit_list(queue);
    return true;

@@ -491,6 +488,15 @@
    if (!locked)
       mtx_lock(&queue->lock);

+   if (queue->flags & UTIL_QUEUE_DEFERRED) {
+      queue->num_threads = MIN2(queue->num_threads, keep_num_threads);
+      if (!queue->num_threads)
+         queue->flags &= ~UTIL_QUEUE_DEFERRED;
+      if (!locked)
+         mtx_unlock(&queue->lock);
+      return;
+   }
+
    if (keep_num_threads >= queue->num_threads) {
       if (!locked)
          mtx_unlock(&queue->lock);
@@ -555,6 +561,19 @@

    if (!locked)
       mtx_lock(&queue->lock);
+   if (queue->flags & UTIL_QUEUE_DEFERRED) {
+      unsigned wanted = queue->num_threads;
+      queue->flags &= ~UTIL_QUEUE_DEFERRED;
+      queue->num_threads = 0;
+      util_queue_adjust_num_threads(queue, wanted, true);
+      if (!queue->num_threads) {
+         /* add_job has no error return: never drop work or leave an unsignalled
+          * fence when the first worker cannot be started.
+          */
+         fprintf(stderr, "Mesa: could not start worker queue %s\n", queue->name);
+         abort();
+      }
+   }
    if (queue->num_threads == 0) {
       if (!locked)
          mtx_unlock(&queue->lock);
@@ -699,7 +718,7 @@
    mtx_lock(&queue->lock);

    /* The number of threads can be changed to 0, e.g. by the atexit handler. */
-   if (!queue->num_threads) {
+   if (!queue->num_threads || (queue->flags & UTIL_QUEUE_DEFERRED)) {
       mtx_unlock(&queue->lock);
       return;
    }
@@ -736,7 +755,7 @@
 util_queue_get_thread_time_nano(struct util_queue *queue, unsigned thread_index)
 {
    /* Allow some flexibility by not raising an error. */
-   if (thread_index >= queue->num_threads)
+   if (thread_index >= queue->num_threads || (queue->flags & UTIL_QUEUE_DEFERRED))
       return 0;

    return util_thread_get_time_nano(queue->threads[thread_index]);

--- a/src/glx/drisw_glx.c
+++ b/src/glx/drisw_glx.c
@@ -634,6 +634,12 @@

    /* this is only relevant if zink bits are set */
    glx_driver &= (GLX_DRIVER_ZINK_INFER | GLX_DRIVER_ZINK_YES);
+   /* Without DRI3, use the frontend's readback presentation, not Vulkan WSI. */
+   if (glx_driver && !kopper_disable) {
+      int opcode, event, error;
+      kopper_disable = !XQueryExtension(priv->dpy, "DRI3", &opcode, &event, &error);
+   }
+
    const char *driver = glx_driver && !kopper_disable ? "zink" : "swrast";

    psc = calloc(1, sizeof *psc);
@@ -642,7 +648,7 @@
    psc->kopper = !strcmp(driver, "zink");
    psc->base.driverName = strdup(driver);

-   if (glx_driver)
+   if (psc->kopper)
       loader_extensions_local = kopper_extensions_noshm;
 #ifdef HAVE_SYS_SHM_H
    else if (!x11_xcb_display_supports_xshm(XGetXCBConnection(priv->dpy), &xshm_opcode))
@@ -650,7 +656,7 @@
 #endif
    else
       loader_extensions_local = loader_extensions_shm;
-   priv->driver = glx_driver ? GLX_DRIVER_ZINK_YES : GLX_DRIVER_SW;
+   priv->driver = psc->kopper ? GLX_DRIVER_ZINK_YES : GLX_DRIVER_SW;

    if (!dri_screen_init(&psc->base, priv, screen, -1, loader_extensions_local, driver_name_is_inferred)) {
       if (!glx_driver || !driver_name_is_inferred)
@@ -664,7 +670,7 @@
    psp->createDrawable = driswCreateDrawable;
    psp->swapBuffers = driswSwapBuffers;

-   if (!glx_driver)
+   if (!psc->kopper)
       psp->copySubBuffer = drisw_copy_sub_buffer;

    if (psc->kopper) {
--- a/src/egl/drivers/dri2/platform_wayland.c
+++ b/src/egl/drivers/dri2/platform_wayland.c
@@ -3297,7 +3297,7 @@ dri2_initialize_wayland_swrast(_EGLDisplay *disp)
 EGLBoolean
 dri2_initialize_wayland(_EGLDisplay *disp)
 {
-   if (disp->Options.ForceSoftware)
+   if (disp->Options.ForceSoftware || disp->Options.Zink)
       return dri2_initialize_wayland_swrast(disp);
    else
       return dri2_initialize_wayland_drm(disp);
--- a/src/egl/main/egldevice.h
+++ b/src/egl/main/egldevice.h
@@ -60,6 +60,9 @@
 _EGLDevice *
 _eglFindDevice(int fd, bool software);

+_EGLDevice *
+_eglFindNonDrmDevice(const unsigned char uuid[16]);
+
 enum _egl_device_extension {
    _EGL_DEVICE_SOFTWARE,
    _EGL_DEVICE_DRM,
--- a/src/egl/main/egldevice.c
+++ b/src/egl/main/egldevice.c
@@ -78,8 +78,8 @@
       dev_list = dev_list->Next;

 #ifdef HAVE_LIBDRM
-      assert(_eglDeviceSupports(dev, _EGL_DEVICE_DRM));
-      drmFreeDevice(&dev->device);
+      if (dev->device)
+         drmFreeDevice(&dev->device);
 #endif
       free(dev->device_info.vendor_name);
       free(dev->device_info.renderer_name);
@@ -113,6 +113,32 @@
    .EXT_device_drm_render_node = EGL_TRUE,
 };

+/* A render-capable driver can have a physical device without a DRM node.
+ * Retain its real UUID for identity; do not advertise unavailable DRM APIs.
+ */
+_EGLDevice *
+_eglFindNonDrmDevice(const unsigned char uuid[16])
+{
+   simple_mtx_lock(_eglGlobal.Mutex);
+   _EGLDevice *dev = _eglGlobal.DeviceList;
+   while (dev->Next) {
+      dev = dev->Next;
+      if (!dev->MESA_device_software && !dev->EXT_device_drm &&
+          !memcmp(dev->device_info.device_uuid, uuid, EGL_UUID_SIZE)) {
+         simple_mtx_unlock(_eglGlobal.Mutex);
+         return dev;
+      }
+   }
+   dev->Next = calloc(1, sizeof(*dev));
+   dev = dev->Next;
+   if (dev) {
+      memcpy(dev->device_info.device_uuid, uuid, EGL_UUID_SIZE);
+      dev->extensions = "";
+   }
+   simple_mtx_unlock(_eglGlobal.Mutex);
+   return dev;
+}
+
 #ifdef HAVE_LIBDRM
 extern const _EGLDriver _eglDriver;
 /*
@@ -143,8 +169,8 @@
    while (dev->Next) {
       dev = dev->Next;

-      assert(_eglDeviceSupports(dev, _EGL_DEVICE_DRM));
-      if (drmDevicesEqual(device, dev->device) != 0)
+      if (_eglDeviceSupports(dev, _EGL_DEVICE_DRM) &&
+          drmDevicesEqual(device, dev->device) != 0)
          return 1;
    }

@@ -328,10 +354,8 @@
 {
 #ifdef HAVE_LIBDRM
    drmDevicePtr device = _eglDeviceDrm(dev);
-   /* Software device does not have render node. */
-   const char *render_node =
-      _eglDeviceSupports(dev, _EGL_DEVICE_SOFTWARE) ?
-      "" : device->nodes[DRM_NODE_RENDER];
+   /* Software and non-DRM devices do not have a render node. */
+   const char *render_node = device ? device->nodes[DRM_NODE_RENDER] : "";
 #endif

    switch (name) {
@@ -434,6 +458,11 @@
    assert(_eglDeviceSupports(dev, _EGL_DEVICE_SOFTWARE));
    count++;

+   for (_EGLDevice *other = dev->Next; other; other = other->Next) {
+      if (!_eglDeviceSupports(other, _EGL_DEVICE_DRM))
+         count++;
+   }
+
 #ifdef HAVE_LIBDRM
    drmDevicePtr devices[64];
    int num_devs, ret;
--- a/src/egl/drivers/dri2/egl_dri2.c
+++ b/src/egl/drivers/dri2/egl_dri2.c
@@ -47,6 +47,7 @@
 #include <sys/stat.h>
 #include <sys/types.h>
 #include "dri_screen.h"
+#include "pipe/p_screen.h"

 #ifdef HAVE_WAYLAND_PLATFORM
 #include "linux-dmabuf-unstable-v1-client-protocol.h"
@@ -831,6 +832,21 @@
    _EGLDevice *dev;
    int render_fd;

+   /* A Gallium screen can use a non-DRM device even with a swrast loader. */
+   if (dri2_dpy->fd_render_gpu < 0 && dri2_dpy->dri_screen_render_gpu) {
+      struct pipe_screen *screen = dri2_dpy->dri_screen_render_gpu->base.screen;
+      dev = NULL;
+      if (!screen->caps.accelerated) {
+         dev = _eglFindDevice(-1, true);
+      } else if (screen->get_device_uuid) {
+         unsigned char uuid[16];
+         screen->get_device_uuid(screen, (char *)uuid);
+         dev = _eglFindNonDrmDevice(uuid);
+      }
+      disp->Device = dev;
+      return dev != NULL;
+   }
+
    /* If we're not software, we need a DRM node FD */
    assert(software || dri2_dpy->fd_render_gpu >= 0);

--- a/src/egl/drivers/dri2/platform_surfaceless.c
+++ b/src/egl/drivers/dri2/platform_surfaceless.c
@@ -343,17 +343,10 @@
 surfaceless_probe_device_sw(_EGLDisplay *disp)
 {
    struct dri2_egl_display *dri2_dpy = dri2_egl_display(disp);
-   struct _egl_device *device = _eglFindDevice(dri2_dpy->fd_render_gpu, true);
+   _EGLDevice *requested_device = disp->Device;

    dri2_dpy->fd_render_gpu = -1;

-   if (_eglHasAttrib(disp, EGL_DEVICE_EXT) && disp->Device != device) {
-      return false;
-   }
-
-   disp->Device = device;
-   assert(disp->Device);
-
    dri2_dpy->driver_name = strdup(disp->Options.Zink ? "zink" : "swrast");
    if (!dri2_dpy->driver_name)
       return false;
@@ -374,6 +367,14 @@
       return false;
    }

+   if (!dri2_setup_device(disp, true))
+      return false;
+
+   if (_eglHasAttrib(disp, EGL_DEVICE_EXT) && disp->Device != requested_device) {
+      disp->Device = requested_device;
+      return false;
+   }
+
    return true;
 }

--- a/src/freedreno/vulkan/tu_knl.h
+++ b/src/freedreno/vulkan/tu_knl.h
@@ -128,6 +128,7 @@
    VkResult (*device_init)(struct tu_device *dev);
    void (*device_finish)(struct tu_device *dev);
    int (*device_get_gpu_timestamp)(struct tu_device *dev, uint64_t *ts);
+   bool (*can_query_gpu_timestamp)(const struct tu_physical_device *dev);
    int (*device_get_suspend_count)(struct tu_device *dev, uint64_t *suspend_count);
    VkResult (*device_check_status)(struct tu_device *dev);
    int (*submitqueue_new)(struct tu_device *dev, struct tu_queue *queue);
--- a/src/freedreno/vulkan/tu_device.cc
+++ b/src/freedreno/vulkan/tu_device.cc
@@ -39,6 +39,7 @@
 #include "tu_descriptor_set.h"
 #include "tu_dynamic_rendering.h"
 #include "tu_image.h"
+#include "tu_knl.h"
 #include "tu_pass.h"
 #include "tu_query_pool.h"
 #include "tu_queue.h"
@@ -192,6 +193,9 @@
                       struct vk_device_extension_table *ext)
 {
    bool has_gralloc = vk_android_get_ugralloc() != NULL;
+   bool has_calibrated_timestamps = device->info->props.has_persistent_counter &&
+      (!device->instance->knl->can_query_gpu_timestamp ||
+       device->instance->knl->can_query_gpu_timestamp(device));
    /* device->has_raytracing contains the value of the SW fuse. If the
     * device doesn't have a fuse (i.e. a740), we have to ignore it because
     * kgsl returns false. If it does have a fuse, enable raytracing if the
@@ -207,7 +211,7 @@
       .KHR_acceleration_structure = has_raytracing,
       .KHR_bind_memory2 = true,
       .KHR_buffer_device_address = true,
-      .KHR_calibrated_timestamps = device->info->props.has_persistent_counter,
+      .KHR_calibrated_timestamps = has_calibrated_timestamps,
       .KHR_compute_shader_derivatives = true,
       .KHR_copy_commands2 = true,
       // TODO workaround for https://github.com/KhronosGroup/VK-GL-CTS/issues/525
@@ -304,7 +308,7 @@
       .EXT_attachment_feedback_loop_dynamic_state = true,
       .EXT_attachment_feedback_loop_layout = true,
       .EXT_border_color_swizzle = true,
-      .EXT_calibrated_timestamps = device->info->props.has_persistent_counter,
+      .EXT_calibrated_timestamps = has_calibrated_timestamps,
       .EXT_color_write_enable = true,
       .EXT_conditional_rendering = true,
       .EXT_conservative_rasterization = device->info->chip >= 7,
--- a/src/freedreno/vulkan/tu_knl_kgsl.cc
+++ b/src/freedreno/vulkan/tu_knl_kgsl.cc
@@ -1703,10 +1703,28 @@
 }

 static int
+kgsl_read_gpu_timestamp(int fd, uint64_t *ts)
+{
+   uint32_t domain = KGSL_CALIBRATED_TIME_DOMAIN_DEVICE;
+   struct kgsl_read_calibrated_timestamps request = {
+      .sources = (uintptr_t)&domain,
+      .ts = (uintptr_t)ts,
+      .count = 1,
+   };
+   return safe_ioctl(fd, IOCTL_KGSL_READ_CALIBRATED_TIMESTAMPS, &request);
+}
+
+static bool
+kgsl_can_query_gpu_timestamp(const struct tu_physical_device *dev)
+{
+   uint64_t timestamp;
+   return kgsl_read_gpu_timestamp(dev->local_fd, &timestamp) == 0;
+}
+
+static int
 kgsl_device_get_gpu_timestamp(struct tu_device *dev, uint64_t *ts)
 {
-   UNREACHABLE("");
-   return 0;
+   return kgsl_read_gpu_timestamp(dev->fd, ts);
 }

 static int
@@ -1754,6 +1772,7 @@
       .device_init = kgsl_device_init,
       .device_finish = kgsl_device_finish,
       .device_get_gpu_timestamp = kgsl_device_get_gpu_timestamp,
+      .can_query_gpu_timestamp = kgsl_can_query_gpu_timestamp,
       .device_get_suspend_count = kgsl_device_get_suspend_count,
       .device_check_status = kgsl_device_check_status,
       .submitqueue_new = kgsl_submitqueue_new,
@@ -1791,10 +1810,8 @@
 VkResult
 tu_knl_kgsl_load(struct tu_instance *instance, int fd)
 {
-   if (instance->vk.enabled_extensions.KHR_display) {
-      return vk_errorf(instance, VK_ERROR_INITIALIZATION_FAILED,
-                       "I can't KHR_display");
-   }
+   /* KGSL is render-only. The common display WSI accepts master_fd == -1
+    * and enumerates no displays; requesting the extension is still valid. */

    struct tu_physical_device *device = (struct tu_physical_device *)
       vk_zalloc(&instance->vk.alloc, sizeof(*device), 8,
--- a/src/gallium/drivers/zink/zink_screen.c
+++ b/src/gallium/drivers/zink/zink_screen.c
@@ -34,6 +34,9 @@
 #include "zink_public.h"
 #include "zink_query.h"
 #include "zink_resource.h"
+#include "frontend/sw_winsys.h"
+#include "util/u_inlines.h"
+#include "util/u_surface.h"
 #include "zink_state.h"
 #include "nir_to_spirv/nir_to_spirv.h" // for SPIRV_VERSION

@@ -1892,6 +1895,45 @@
 }

 static void
+zink_present_readback(struct zink_screen *screen, struct pipe_context *pipe,
+                      struct pipe_resource *pres, unsigned level, unsigned layer,
+                      void *drawable, unsigned nboxes, struct pipe_box *boxes)
+{
+   struct sw_winsys *ws = screen->winsys;
+   struct zink_resource *res = zink_resource(pres);
+   if (!ws || !ws->displaytarget_display || !(pres->bind & PIPE_BIND_DISPLAY_TARGET))
+      return;
+
+   if (res->sw_dt && res->sw_level != level) {
+      ws->displaytarget_destroy(ws, res->sw_dt);
+      res->sw_dt = NULL;
+   }
+   unsigned width = u_minify(pres->width0, level);
+   unsigned height = u_minify(pres->height0, level);
+   if (!res->sw_dt) {
+      res->sw_dt = ws->displaytarget_create(ws, pres->bind, pres->format,
+                                           width, height, 64, NULL, &res->sw_stride);
+      res->sw_level = level;
+      if (!res->sw_dt)
+         return;
+   }
+   struct pipe_transfer *transfer = NULL;
+   void *pixels = pipe_texture_map(pipe, pres, level, layer, PIPE_MAP_READ,
+                                  0, 0, width, height, &transfer);
+   if (!pixels)
+      return;
+   void *output = ws->displaytarget_map(ws, res->sw_dt, PIPE_MAP_WRITE);
+   if (output) {
+      util_copy_rect(output, pres->format, res->sw_stride, 0, 0, width, height,
+                     pixels, transfer->stride, 0, 0);
+      ws->displaytarget_unmap(ws, res->sw_dt);
+   }
+   pipe_texture_unmap(pipe, transfer);
+   if (output)
+      ws->displaytarget_display(ws, res->sw_dt, drawable, nboxes, boxes);
+}
+
+static void
 zink_flush_frontbuffer(struct pipe_screen *pscreen,
                        struct pipe_context *pctx,
                        struct pipe_resource *pres,
@@ -1904,9 +1946,12 @@
    struct zink_resource *res = zink_resource(pres);
    struct zink_context *ctx = zink_context(pctx);

-   /* if the surface is no longer a swapchain, this is a no-op */
-   if (!zink_is_swapchain(res))
+   /* A non-Kopper frontend owns presentation; Vulkan still renders the image. */
+   if (!zink_is_swapchain(res)) {
+      zink_present_readback(screen, pctx, pres, level, layer,
+                            winsys_drawable_handle, nboxes, sub_box);
       return;
+   }

    ctx = zink_tc_context_unwrap(pctx);

@@ -3862,9 +3907,10 @@
    struct zink_screen *ret = zink_internal_create_screen(config, -1, -1, 0);
    if (ret) {
       ret->drm_fd = -1;
-   }
-
-   return &ret->base;
+      ret->winsys = winsys;
+   }
+
+   return ret ? &ret->base : NULL;
 }

 static inline int
--- a/src/gallium/drivers/zink/zink_types.h
+++ b/src/gallium/drivers/zink/zink_types.h
@@ -1268,6 +1268,9 @@
 struct zink_resource {
    struct threaded_resource base;

+   struct sw_displaytarget *sw_dt;
+   unsigned sw_stride, sw_level;
+
    enum pipe_format internal_format:16;

    struct zink_resource_object *obj;
@@ -1367,6 +1370,8 @@

 struct zink_screen {
    struct pipe_screen base;
+
+   struct sw_winsys *winsys;

    const char *vendor_name;
    const char *device_name;
--- a/src/gallium/drivers/zink/zink_resource.c
+++ b/src/gallium/drivers/zink/zink_resource.c
@@ -47,6 +47,7 @@
 #include "util/u_upload_mgr.h"
 #include "util/os_file.h"
 #include "frontend/winsys_handle.h"
+#include "frontend/sw_winsys.h"

 #if !defined(__APPLE__)
 #define ZINK_USE_DMABUF
@@ -254,6 +255,9 @@
    struct zink_resource *res = zink_resource(pres);
    /* prevent double-free when unrefing internal surfaces */
    res->base.b.reference.count = 999;
+
+   if (res->sw_dt)
+      screen->winsys->displaytarget_destroy(screen->winsys, res->sw_dt);

 #ifdef HAVE_LIBDRM
    if (res->ro_scanout)
--- a/src/gallium/winsys/sw/dri/dri_sw_winsys.c
+++ b/src/gallium/winsys/sw/dri/dri_sw_winsys.c
@@ -428,7 +428,8 @@
    ws->base.displaytarget_map = dri_sw_displaytarget_map;
    ws->base.displaytarget_unmap = dri_sw_displaytarget_unmap;

-   ws->base.displaytarget_display = dri_sw_displaytarget_display;
+   /* Kopper supplies no readback loader; its offscreen flushes cannot present. */
+   ws->base.displaytarget_display = lf ? dri_sw_displaytarget_display : NULL;

    return &ws->base;
 }
--- a/src/vulkan/wsi/wsi_common_x11.c
+++ b/src/vulkan/wsi/wsi_common_x11.c
@@ -407,18 +407,6 @@
    vk_free(&wsi_dev->instance_alloc, conn);
 }

-static bool
-wsi_x11_check_for_dri3(struct wsi_x11_connection *wsi_conn)
-{
-   if (wsi_conn->has_dri3)
-      return true;
-   if (!wsi_conn->is_proprietary_x11) {
-      mesa_logi("vulkan: No DRI3 support detected - required for presentation\n"
-                "Note: you can probably enable DRI3 in your Xorg config\n");
-   }
-   return false;
-}
-
 /**
  * Get internal struct representing an xcb_connection_t.
  *
@@ -643,10 +631,8 @@
    if (!wsi_conn)
       return false;

-   if (!wsi_device->sw) {
-      if (!wsi_x11_check_for_dri3(wsi_conn))
-         return false;
-   }
+   if (!wsi_device->sw && !wsi_conn->has_dri3 && !wsi_conn->has_present)
+      return false;

    if (!visual_supported(connection_get_visualtype(connection, visual_id)))
       return false;
@@ -718,11 +704,9 @@
    if (!wsi_conn)
       return VK_ERROR_OUT_OF_HOST_MEMORY;

-   if (!wsi_device->sw) {
-      if (!wsi_x11_check_for_dri3(wsi_conn)) {
-         *pSupported = false;
-         return VK_SUCCESS;
-      }
+   if (!wsi_device->sw && !wsi_conn->has_dri3 && !wsi_conn->has_present) {
+      *pSupported = false;
+      return VK_SUCCESS;
    }

    if (!visual_supported(get_visualtype_for_window(conn, window, NULL, NULL))) {
@@ -1240,6 +1224,8 @@

 struct x11_swapchain {
    struct wsi_swapchain                        base;
+   bool                                       cpu_present;
+   bool                                       has_present;

    bool                                         has_dri3_modifiers;
    bool                                         has_mit_shm;
@@ -1775,7 +1761,8 @@
        !wsi_device->x11.ignore_suboptimal)
       options |= XCB_PRESENT_OPTION_SUBOPTIMAL;

-   xshmfence_reset(image->shm_fence);
+   if (image->shm_fence)
+      xshmfence_reset(image->shm_fence);

    if (!chain->base.image_info.explicit_sync) {
       ++chain->sent_image_count;
@@ -1841,17 +1828,21 @@
 }
 #endif
 /**
- * Send image to X server unaccelerated (software drivers).
+ * Upload a CPU-readable image without a DRI3 buffer import.
  */
 static VkResult
-x11_present_to_x11_sw(struct x11_swapchain *chain, uint32_t image_index)
+x11_present_to_x11_sw(struct x11_swapchain *chain, uint32_t image_index,
+                      uint64_t target_msc, VkPresentModeKHR present_mode)
 {
    assert(!chain->base.image_info.explicit_sync);
    struct x11_image *image = &chain->images[image_index];
+   xcb_drawable_t drawable = image->pixmap ? image->pixmap : chain->window;

    /* Begin querying this before submitting the frame for improved async performance.
     * In this _sw() mode we're expecting network round-trip delay, not just UNIX socket delay. */
-   xcb_get_geometry_cookie_t geom_cookie = xcb_get_geometry(chain->conn, chain->window);
+   xcb_get_geometry_cookie_t geom_cookie = {0};
+   if (!image->pixmap)
+      geom_cookie = xcb_get_geometry(chain->conn, chain->window);

    xcb_void_cookie_t cookie;
    void *myptr = image->base.cpu_map;
@@ -1866,7 +1857,7 @@
          const uint8_t *data = (const uint8_t*)myptr + (rect.y * stride_b) + (rect.x * 4);
          for (int j = 0; j < rect.height; j++) {
             cookie = xcb_put_image(chain->conn, XCB_IMAGE_FORMAT_Z_PIXMAP,
-                                   chain->window, chain->gc,
+                                   drawable, chain->gc,
                                    rect.width,
                                    1,
                                    rect.x, rect.y + j,
@@ -1879,7 +1870,7 @@
       }
    } else if (size < max_req_len) {
       cookie = xcb_put_image(chain->conn, XCB_IMAGE_FORMAT_Z_PIXMAP,
-                             chain->window,
+                             drawable,
                              chain->gc,
                              image->base.row_pitches[0] / 4,
                              chain->extent.height,
@@ -1894,7 +1885,7 @@
       while (y_todo) {
          int this_lines = MIN2(num_lines, y_todo);
          cookie = xcb_put_image(chain->conn, XCB_IMAGE_FORMAT_Z_PIXMAP,
-                                chain->window,
+                                drawable,
                                 chain->gc,
                                 image->base.row_pitches[0] / 4,
                                 this_lines,
@@ -1905,6 +1896,12 @@
          y_start += this_lines;
          y_todo -= this_lines;
       }
+   }
+
+   if (image->pixmap) {
+#ifdef HAVE_X11_DRM
+      return x11_present_to_x11_dri3(chain, image_index, target_msc, present_mode);
+#endif
    }

    xcb_flush(chain->conn);
@@ -2074,8 +2071,8 @@
    x11_capture_trace(chain);

    VkResult result;
-   if (chain->base.wsi->sw && !chain->has_mit_shm)
-      result = x11_present_to_x11_sw(chain, image_index);
+   if (chain->cpu_present && !chain->has_mit_shm)
+      result = x11_present_to_x11_sw(chain, image_index, target_msc, present_mode);
    else
 #ifdef HAVE_X11_DRM
       result = x11_present_to_x11_dri3(chain, image_index, target_msc, present_mode);
@@ -2522,7 +2519,7 @@

       /* Not necessary to block when we have explicit sync */
       bool need_fence_wait =
-            x11_needs_wait_for_fences(chain->base.wsi, wsi_conn, present_mode) &&
+            (chain->cpu_present || x11_needs_wait_for_fences(chain->base.wsi, wsi_conn, present_mode)) &&
             !chain->base.image_info.explicit_sync;

       bool need_timing_fence_wait = x11_swapchain_present_timing_is_out_of_order_completion(
@@ -2627,7 +2624,7 @@
       return result;

    image->update_region = None;
-   if (chain->base.wsi->sw && !chain->has_mit_shm)
+   if (chain->cpu_present && !chain->has_mit_shm && !chain->has_present)
       return VK_SUCCESS;

 #ifdef HAVE_X11_DRM
@@ -2638,7 +2635,22 @@
    image->update_region = xcb_generate_id(chain->conn);
    xcb_xfixes_create_region(chain->conn, image->update_region, 0, NULL);

-   if (chain->base.wsi->sw) {
+   if (chain->cpu_present && !chain->has_mit_shm) {
+      image->pixmap = xcb_generate_id(chain->conn);
+      cookie = xcb_create_pixmap_checked(chain->conn, chain->depth,
+                                          image->pixmap, chain->window,
+                                          image->base.row_pitches[0] / 4,
+                                          pCreateInfo->imageExtent.height);
+      error = xcb_request_check(chain->conn, cookie);
+      if (error) {
+         free(error);
+         goto fail_image;
+      }
+      /* Present's IDLE event releases this ordinary pixmap; no DRI3 fence. */
+      return VK_SUCCESS;
+   }
+
+   if (chain->cpu_present) {
       image->shmseg = xcb_generate_id(chain->conn);

       xcb_shm_attach(chain->conn,
@@ -2766,6 +2778,7 @@
    xcb_discard_reply(chain->conn, cookie.sequence);

 fail_image:
+   xcb_xfixes_destroy_region(chain->conn, image->update_region);
    wsi_destroy_image(&chain->base, &image->base);

 #else
@@ -2780,11 +2793,14 @@
                  struct x11_image *image)
 {
    xcb_void_cookie_t cookie;
-   if (!chain->base.wsi->sw || chain->has_mit_shm) {
+   if (image->pixmap) {
 #ifdef HAVE_X11_DRM
-      cookie = xcb_sync_destroy_fence(chain->conn, image->sync_fence);
-      xcb_discard_reply(chain->conn, cookie.sequence);
-      xshmfence_unmap_shm(image->shm_fence);
+      if (image->sync_fence) {
+         cookie = xcb_sync_destroy_fence(chain->conn, image->sync_fence);
+         xcb_discard_reply(chain->conn, cookie.sequence);
+      }
+      if (image->shm_fence)
+         xshmfence_unmap_shm(image->shm_fence);
 #endif

       cookie = xcb_free_pixmap(chain->conn, image->pixmap);
@@ -2913,7 +2929,7 @@
 {
    const struct wsi_device *wsi_device = chain->base.wsi;

-   if (wsi_device->sw || !use_modifiers(wsi_device))
+   if (chain->cpu_present || !use_modifiers(wsi_device))
       return false;

    struct wsi_drm_image_params drm_image_params;
@@ -3201,7 +3217,9 @@
    struct wsi_base_image_params *image_params = NULL;
    struct wsi_cpu_image_params cpu_image_params;
    uint64_t *modifiers[2] = {NULL, NULL};
-   if (wsi_device->sw) {
+   /* CPU presentation does not change the rendering device or other surfaces. */
+   chain->cpu_present = wsi_device->sw || !wsi_conn->has_dri3;
+   if (chain->cpu_present) {
       cpu_image_params = (struct wsi_cpu_image_params) {
          .base.image_type = WSI_IMAGE_TYPE_CPU,
          .alloc_shm = wsi_conn->has_mit_shm ? &alloc_shm : NULL,
@@ -3272,6 +3290,9 @@
    chain->status = VK_SUCCESS;
    chain->has_dri3_modifiers = wsi_conn->has_dri3_modifiers;
    chain->has_mit_shm = wsi_conn->has_mit_shm;
+#ifdef HAVE_X11_DRM
+   chain->has_present = wsi_conn->has_present;
+#endif
    chain->has_async_may_tear = present_caps & XCB_PRESENT_CAPABILITY_ASYNC_MAY_TEAR;
    chain->has_reliable_msc = !wsi_conn->is_xwayland;

--- a/src/vulkan/wsi/wsi_common_drm.c
+++ b/src/vulkan/wsi/wsi_common_drm.c
@@ -367,6 +367,9 @@
 VkResult
 wsi_drm_init_swapchain_implicit_sync(struct wsi_swapchain *chain)
 {
+   if (chain->image_info.image_type != WSI_IMAGE_TYPE_DRM)
+      return VK_SUCCESS;
+
    /* We don't need implicit sync if explicit sync is available through the
     * window system protocol.
     */
PATCH
}

kwin_patch_6_6() {
    cat <<'PATCH'
--- a/src/backends/wayland/CMakeLists.txt
+++ b/src/backends/wayland/CMakeLists.txt
@@ -1,4 +1,5 @@
 target_sources(kwin PRIVATE
+    dmaheapgraphicsbufferallocator.cpp
     color_manager.cpp
     wayland_backend.cpp
     wayland_display.cpp
--- a/src/backends/wayland/dmaheapgraphicsbufferallocator.cpp
+++ b/src/backends/wayland/dmaheapgraphicsbufferallocator.cpp
@@ -0,0 +1,78 @@
+/* SPDX-License-Identifier: GPL-2.0-or-later */
+#include "dmaheapgraphicsbufferallocator.h"
+#include "core/graphicsbuffer.h"
+#include "utils/drm_format_helper.h"
+
+#include <drm_fourcc.h>
+#include <fcntl.h>
+#include <limits>
+#include <linux/dma-heap.h>
+#include <sys/ioctl.h>
+
+namespace KWin::Wayland
+{
+
+class DmaHeapGraphicsBuffer : public GraphicsBuffer
+{
+public:
+    explicit DmaHeapGraphicsBuffer(DmaBufAttributes &&attributes)
+        : m_attributes(std::move(attributes))
+    {
+    }
+
+    QSize size() const override { return {m_attributes.width, m_attributes.height}; }
+    bool hasAlphaChannel() const override { return alphaChannelFromDrmFormat(m_attributes.format); }
+    const DmaBufAttributes *dmabufAttributes() const override { return &m_attributes; }
+
+private:
+    DmaBufAttributes m_attributes;
+};
+
+DmaHeapGraphicsBufferAllocator::DmaHeapGraphicsBufferAllocator()
+    : m_heap(open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC))
+{
+}
+
+bool DmaHeapGraphicsBufferAllocator::isValid() const
+{
+    return m_heap.isValid();
+}
+
+GraphicsBuffer *DmaHeapGraphicsBufferAllocator::allocate(const GraphicsBufferOptions &options)
+{
+    if (!isValid() || options.software || options.size.isEmpty()
+        || !options.modifiers.contains(DRM_FORMAT_MOD_LINEAR)) {
+        return nullptr;
+    }
+    switch (options.format) {
+    case DRM_FORMAT_XRGB8888:
+    case DRM_FORMAT_ARGB8888:
+    case DRM_FORMAT_XBGR8888:
+    case DRM_FORMAT_ABGR8888:
+        break;
+    default:
+        return nullptr;
+    }
+    const uint64_t stride = (uint64_t(options.size.width()) * 4 + 255) & ~uint64_t(255);
+    const uint64_t length = stride * options.size.height();
+    if (stride > std::numeric_limits<uint32_t>::max() || length > std::numeric_limits<int32_t>::max()) {
+        return nullptr;
+    }
+    dma_heap_allocation_data allocation{};
+    allocation.len = length;
+    allocation.fd_flags = O_RDWR | O_CLOEXEC;
+    if (ioctl(m_heap.get(), DMA_HEAP_IOCTL_ALLOC, &allocation) < 0) {
+        return nullptr;
+    }
+    DmaBufAttributes attributes;
+    attributes.width = options.size.width();
+    attributes.height = options.size.height();
+    attributes.format = options.format;
+    attributes.modifier = DRM_FORMAT_MOD_LINEAR;
+    attributes.planeCount = 1;
+    attributes.fd[0] = FileDescriptor(allocation.fd);
+    attributes.pitch[0] = stride;
+    return new DmaHeapGraphicsBuffer(std::move(attributes));
+}
+
+}
--- a/src/backends/wayland/dmaheapgraphicsbufferallocator.h
+++ b/src/backends/wayland/dmaheapgraphicsbufferallocator.h
@@ -0,0 +1,21 @@
+/* SPDX-License-Identifier: GPL-2.0-or-later */
+#pragma once
+
+#include "core/graphicsbufferallocator.h"
+#include "utils/filedescriptor.h"
+
+namespace KWin::Wayland
+{
+
+class DmaHeapGraphicsBufferAllocator : public GraphicsBufferAllocator
+{
+public:
+    DmaHeapGraphicsBufferAllocator();
+    bool isValid() const;
+    GraphicsBuffer *allocate(const GraphicsBufferOptions &options) override;
+
+private:
+    FileDescriptor m_heap;
+};
+
+}
--- a/src/backends/wayland/wayland_backend.cpp
+++ b/src/backends/wayland/wayland_backend.cpp
@@ -437,7 +437,7 @@
         return false;
     }

-    if (WaylandClient::LinuxDmabufV1 *dmabuf = m_display->linuxDmabuf()) {
+    if (WaylandClient::LinuxDmabufV1 *dmabuf = m_display->linuxDmabuf(); dmabuf && !dmabuf->mainDevice().isEmpty()) {
         m_drmDevice = DrmDevice::open(dmabuf->mainDevice());
         if (!m_drmDevice) {
             qCWarning(KWIN_WAYLAND_BACKEND) << "Failed to open drm render node" << dmabuf->mainDevice();
@@ -586,7 +586,7 @@
 QList<CompositingType> WaylandBackend::supportedCompositors() const
 {
     QList<CompositingType> ret;
-    if (m_display->linuxDmabuf() && m_drmDevice) {
+    if (m_display->linuxDmabuf() && !m_display->linuxDmabuf()->formats().isEmpty()) {
         ret.append(OpenGLCompositing);
     }
     ret.append(QPainterCompositing);
--- a/src/backends/wayland/wayland_display.cpp
+++ b/src/backends/wayland/wayland_display.cpp
@@ -406,8 +406,8 @@
         display->m_xdgDecorationManager = std::make_unique<KWayland::Client::XdgDecorationManager>();
         display->m_xdgDecorationManager->setup(static_cast<zxdg_decoration_manager_v1 *>(wl_registry_bind(registry, name, &zxdg_decoration_manager_v1_interface, std::min(version, 1u))));
     } else if (strcmp(interface, zwp_linux_dmabuf_v1_interface.name) == 0) {
-        if (version < 4) {
-            qWarning("zwp_linux_dmabuf_v1 v4 or newer is needed");
+        if (version < 3) {
+            qWarning("zwp_linux_dmabuf_v1 v3 or newer is needed");
             return;
         }
         display->m_linuxDmabuf = std::make_unique<WaylandClient::LinuxDmabufV1>(registry, name, std::min(version, 4u));
--- a/src/backends/wayland/wayland_egl_backend.cpp
+++ b/src/backends/wayland/wayland_egl_backend.cpp
@@ -9,6 +9,7 @@
 */

 #include "wayland_egl_backend.h"
+#include "dmaheapgraphicsbufferallocator.h"
 #include "core/drmdevice.h"
 #include "core/gbmgraphicsbufferallocator.h"
 #include "opengl/eglswapchain.h"
@@ -73,7 +74,7 @@
             if (it == formatTable.constEnd()) {
                 continue;
             }
-            m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());
+            m_swapchain = EglSwapchain::create(m_backend->graphicsBufferAllocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());
             if (m_swapchain) {
                 break;
             }
@@ -85,7 +86,7 @@
     }

     m_buffer = m_swapchain->acquire();
-    if (!m_buffer) {
+    if (!m_buffer || !m_backend->acquireBuffer(m_buffer->buffer())) {
         return std::nullopt;
     }

@@ -105,6 +106,9 @@
     // Flush rendering commands to the dmabuf.
     glFlush();
     EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
+    if (!m_backend->releaseBuffer(m_buffer->buffer(), releaseFence.fileDescriptor())) {
+        return false;
+    }

     setBuffer(m_backend->backend()->importBuffer(m_buffer->buffer()), damagedDeviceRegion);
     m_swapchain->release(m_buffer, releaseFence.takeFileDescriptor());
@@ -169,7 +173,7 @@
             if (it == formatTable.constEnd()) {
                 continue;
             }
-            m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), bufferSize, it.key(), it.value());
+            m_swapchain = EglSwapchain::create(m_backend->graphicsBufferAllocator(), m_backend->openglContext(), bufferSize, it.key(), it.value());
             if (m_swapchain) {
                 break;
             }
@@ -180,7 +184,7 @@
     }

     m_buffer = m_swapchain->acquire();
-    if (!m_buffer) {
+    if (!m_buffer || !m_backend->acquireBuffer(m_buffer->buffer())) {
         return std::nullopt;
     }

@@ -201,12 +205,15 @@
     // Flush rendering commands to the dmabuf.
     glFlush();

+    EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
+    if (!m_backend->releaseBuffer(m_buffer->buffer(), releaseFence.fileDescriptor())) {
+        return false;
+    }
     wl_buffer *buffer = m_backend->backend()->importBuffer(m_buffer->buffer());
     Q_ASSERT(buffer);

     static_cast<WaylandOutput *>(m_output.get())->cursor()->update(buffer, m_buffer->buffer()->size() / m_output->scale(), (hotspot() / m_output->scale()).toPoint());

-    EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
     m_swapchain->release(m_buffer, releaseFence.takeFileDescriptor());
     return true;
 }
@@ -248,6 +255,21 @@
 DrmDevice *WaylandEglBackend::drmDevice() const
 {
     return m_backend->drmDevice();
+}
+
+GraphicsBufferAllocator *WaylandEglBackend::graphicsBufferAllocator() const
+{
+    return m_allocator ? m_allocator.get() : EglBackend::graphicsBufferAllocator();
+}
+
+bool WaylandEglBackend::acquireBuffer(GraphicsBuffer *buffer)
+{
+    return eglDisplayObject()->acquireDmaBuf(buffer, true);
+}
+
+bool WaylandEglBackend::releaseBuffer(GraphicsBuffer *buffer, const FileDescriptor &fence)
+{
+    return eglDisplayObject()->releaseDmaBuf(buffer, fence, true);
 }

 void WaylandEglBackend::cleanupSurfaces()
@@ -279,14 +301,19 @@
     initClientExtensions();

     if (!m_backend->sceneEglDisplayObject()) {
-        for (const QByteArray &extension : {QByteArrayLiteral("EGL_EXT_platform_base"), QByteArrayLiteral("EGL_KHR_platform_gbm")}) {
+        const auto platform = drmDevice() ? QByteArrayLiteral("EGL_KHR_platform_gbm") : QByteArrayLiteral("EGL_KHR_platform_wayland");
+        for (const QByteArray &extension : {QByteArrayLiteral("EGL_EXT_platform_base"), platform}) {
             if (!hasClientExtension(extension)) {
                 qCWarning(KWIN_WAYLAND_BACKEND) << extension << "client extension is not supported by the platform";
                 return false;
             }
         }

-        m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, m_backend->drmDevice()->gbmDevice(), nullptr)));
+        if (drmDevice()) {
+            m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, drmDevice()->gbmDevice(), nullptr)));
+        } else {
+            m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_WAYLAND_KHR, m_backend->display()->nativeDisplay(), nullptr)));
+        }
     }

     const auto display = m_backend->sceneEglDisplayObject();
@@ -294,6 +321,22 @@
         return false;
     }
     setEglDisplay(display);
+    if (!drmDevice()) {
+        for (const auto &extension : {QByteArrayLiteral("EGL_EXT_image_dma_buf_import_modifiers"),
+                                     QByteArrayLiteral("EGL_ANDROID_native_fence_sync"),
+                                     QByteArrayLiteral("EGL_KHR_wait_sync")}) {
+            if (!display->hasExtension(extension)) {
+                qCWarning(KWIN_WAYLAND_BACKEND) << "DMA heap rendering requires" << extension;
+                return false;
+            }
+        }
+        auto allocator = std::make_unique<DmaHeapGraphicsBufferAllocator>();
+        if (!allocator->isValid()) {
+            return false;
+        }
+        m_allocator = std::move(allocator);
+        display->setDmaBufSyncRequired(true);
+    }
     return true;
 }

@@ -308,6 +351,20 @@
         return;
     }

+    if (m_allocator) {
+        // Validate allocation, EGL import and both synchronization directions before
+        // advertising GPU composition or DMA-BUF to nested clients.
+        const auto formats = m_backend->display()->linuxDmabuf()->formats();
+        auto probe = EglSwapchain::create(m_allocator.get(), openglContext(), QSize(64, 64),
+                                          DRM_FORMAT_XRGB8888, formats.value(DRM_FORMAT_XRGB8888));
+        auto slot = probe ? probe->acquire() : nullptr;
+        EGLNativeFence fence{eglDisplayObject()};
+        if (!slot || !acquireBuffer(slot->buffer()) || !releaseBuffer(slot->buffer(), fence.fileDescriptor())) {
+            setFailed("DMA heap buffer import or synchronization is unavailable");
+            return;
+        }
+    }
+
     initWayland();
 }

--- a/src/backends/wayland/wayland_egl_backend.h
+++ b/src/backends/wayland/wayland_egl_backend.h
@@ -98,6 +98,9 @@

     WaylandBackend *backend() const;
     DrmDevice *drmDevice() const override;
+    GraphicsBufferAllocator *graphicsBufferAllocator() const override;
+    bool acquireBuffer(GraphicsBuffer *buffer);
+    bool releaseBuffer(GraphicsBuffer *buffer, const FileDescriptor &fence);

     void init() override;
     QList<OutputLayer *> compatibleOutputLayers(BackendOutput *output) override;
@@ -109,6 +112,7 @@
     void cleanupSurfaces() override;

     WaylandBackend *m_backend;
+    std::unique_ptr<GraphicsBufferAllocator> m_allocator;
 };

 } // namespace Wayland
--- a/src/compositor.cpp
+++ b/src/compositor.cpp
@@ -131,9 +131,11 @@
     QVariantHash gpuInformation;
     gpuInformation[QStringLiteral("api_type")] = QStringLiteral("OpenGL");
     gpuInformation[QStringLiteral("name")] = QString::fromUtf8(glPlatform->glRendererString());
-    if (const auto pciInfo = backend->drmDevice()->pciDeviceInfo()) {
-        gpuInformation[QStringLiteral("id")] = QString::number(pciInfo->device_id, 16);
-        gpuInformation[QStringLiteral("vendor_id")] = QString::number(pciInfo->vendor_id, 16);
+    if (const auto device = backend->drmDevice()) {
+        if (const auto pciInfo = device->pciDeviceInfo()) {
+            gpuInformation[QStringLiteral("id")] = QString::number(pciInfo->device_id, 16);
+            gpuInformation[QStringLiteral("vendor_id")] = QString::number(pciInfo->vendor_id, 16);
+        }
     }
     if (glPlatform->driverVersion().isValid()) {
         gpuInformation[QStringLiteral("version")] = glPlatform->driverVersion().toString();
@@ -193,7 +195,7 @@
     return true;
 }

-void Compositor::createRenderer()
+bool Compositor::createRenderer()
 {
     // If compositing has been restarted, try to use the last used compositing type.
     const QList<CompositingType> availableCompositors = kwinApp()->outputBackend()->supportedCompositors();
@@ -234,9 +236,16 @@
             break;
         } else if (qEnvironmentVariableIsSet("KWIN_COMPOSE")) {
             qCCritical(KWIN_CORE) << "Could not fulfill the requested compositing mode in KWIN_COMPOSE:" << type << ". Exiting.";
-            qApp->quit();
-        }
-    }
+            break;
+        }
+    }
+    if (m_backend) {
+        return true;
+    }
+    qCCritical(KWIN_CORE) << "The used windowing system requires compositing";
+    // Startup may run before exec(); queue the failure for the event loop.
+    QMetaObject::invokeMethod(qApp, [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
+    return false;
 }

 void Compositor::createScene()
@@ -261,16 +270,8 @@
     Q_EMIT aboutToToggleCompositing();
     m_state = State::Starting;

-    if (!m_backend) {
-        createRenderer();
-    }
-
-    if (!m_backend) {
+    if (!m_backend && !createRenderer()) {
         m_state = State::Off;
-
-        qCCritical(KWIN_CORE) << "The used windowing system requires compositing";
-        qCCritical(KWIN_CORE) << "We are going to quit KWin now as it is broken";
-        qApp->quit();
         return;
     }

--- a/src/compositor.h
+++ b/src/compositor.h
@@ -75,7 +75,7 @@
         return m_backend.get();
     }

-    void createRenderer();
+    bool createRenderer();

 Q_SIGNALS:
     void compositingToggled(bool active);
--- a/src/main_wayland.cpp
+++ b/src/main_wayland.cpp
@@ -146,7 +146,9 @@
     createTabletModeManager();

     auto compositor = Compositor::create();
-    compositor->createRenderer();
+    if (!compositor->createRenderer()) {
+        return;
+    }
     createWorkspace();
     createPlugins();

--- a/src/opengl/eglbackend.cpp
+++ b/src/opengl/eglbackend.cpp
@@ -129,6 +129,12 @@
 {
     m_display = display;
     m_extensions = m_display->extensions();
+}
+
+GraphicsBufferAllocator *EglBackend::graphicsBufferAllocator() const
+{
+    const auto device = drmDevice();
+    return device ? device->allocator() : nullptr;
 }

 void EglBackend::initWayland()
@@ -231,6 +237,19 @@
         LinuxDmaBufV1ClientBufferIntegration *dmabuf = waylandServer()->linuxDmabuf();
         dmabuf->setRenderBackend(this);
         dmabuf->setSupportedFormatsWithModifiers(m_tranches);
+    } else if (m_display->dmaBufSyncRequired()) {
+        QHash<uint32_t, QList<uint64_t>> formats;
+        const auto supported = m_display->allSupportedDrmFormats();
+        for (auto it = supported.constBegin(); it != supported.constEnd(); ++it) {
+            if (!it->nonExternalOnlyModifiers.isEmpty()) {
+                formats.insert(it.key(), it->nonExternalOnlyModifiers);
+            }
+        }
+        if (!formats.isEmpty()) {
+            auto *dmabuf = waylandServer()->linuxDmabuf(false);
+            dmabuf->setRenderBackend(this);
+            dmabuf->setSupportedFormatsWithModifiers(formats);
+        }
     }
     waylandServer()->setRenderBackend(this);
 }
--- a/src/opengl/eglbackend.h
+++ b/src/opengl/eglbackend.h
@@ -24,6 +24,7 @@
 class GLTexture;
 class EglContext;
 class EglDisplay;
+class GraphicsBufferAllocator;

 struct DmaBufAttributes;

@@ -39,6 +40,7 @@
     EglContext *openglContext() const;
     std::shared_ptr<EglContext> openglContextRef() const;
     EglDisplay *eglDisplayObject() const;
+    virtual GraphicsBufferAllocator *graphicsBufferAllocator() const;

     /**
      * @brief Whether the creation of the Backend failed.
--- a/src/opengl/egldisplay.cpp
+++ b/src/opengl/egldisplay.cpp
@@ -7,6 +7,9 @@
     SPDX-License-Identifier: GPL-2.0-or-later
 */
 #include "egldisplay.h"
+#include "eglnativefence.h"
+#include <linux/dma-buf.h>
+#include <sys/ioctl.h>
 #include "core/drmdevice.h"
 #include "core/graphicsbuffer.h"
 #include "opengl/eglutils_p.h"
@@ -23,6 +26,54 @@

 namespace KWin
 {
+
+void EglDisplay::setDmaBufSyncRequired(bool required)
+{
+    m_dmaBufSyncRequired = required;
+}
+
+bool EglDisplay::dmaBufSyncRequired() const
+{
+    return m_dmaBufSyncRequired;
+}
+
+bool EglDisplay::acquireDmaBuf(GraphicsBuffer *buffer, bool write)
+{
+    const auto attributes = buffer->dmabufAttributes();
+    if (!m_dmaBufSyncRequired || !attributes) {
+        return true;
+    }
+    for (int plane = 0; plane < attributes->planeCount; ++plane) {
+        dma_buf_export_sync_file sync{.flags = uint32_t(write ? DMA_BUF_SYNC_RW : DMA_BUF_SYNC_READ), .fd = -1};
+        if (ioctl(attributes->fd[plane].get(), DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &sync) < 0) {
+            return false;
+        }
+        // This is a GPU dependency; it does not block the compositor thread.
+        auto fence = EGLNativeFence::importFence(this, FileDescriptor(sync.fd));
+        if (!fence.waitSync()) {
+            return false;
+        }
+    }
+    return true;
+}
+
+bool EglDisplay::releaseDmaBuf(GraphicsBuffer *buffer, const FileDescriptor &fence, bool write)
+{
+    const auto attributes = buffer->dmabufAttributes();
+    if (!m_dmaBufSyncRequired || !attributes) {
+        return true;
+    }
+    if (!fence.isValid()) {
+        return false;
+    }
+    for (int plane = 0; plane < attributes->planeCount; ++plane) {
+        dma_buf_import_sync_file sync{.flags = uint32_t(write ? DMA_BUF_SYNC_WRITE : DMA_BUF_SYNC_READ), .fd = fence.get()};
+        if (ioctl(attributes->fd[plane].get(), DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &sync) < 0) {
+            return false;
+        }
+    }
+    return true;
+}

 bool EglDisplay::shouldUseOpenGLES()
 {
--- a/src/opengl/egldisplay.h
+++ b/src/opengl/egldisplay.h
@@ -22,6 +22,8 @@

 struct DmaBufAttributes;
 class GLTexture;
+class GraphicsBuffer;
+class FileDescriptor;

 class KWIN_EXPORT EglDisplay
 {
@@ -45,6 +47,10 @@

     bool supportsBufferAge() const;
     bool supportsNativeFence() const;
+    void setDmaBufSyncRequired(bool required);
+    bool dmaBufSyncRequired() const;
+    bool acquireDmaBuf(GraphicsBuffer *buffer, bool write);
+    bool releaseDmaBuf(GraphicsBuffer *buffer, const FileDescriptor &fence, bool write);

     QHash<uint32_t, QList<uint64_t>> nonExternalOnlySupportedDrmFormats() const;
     QHash<uint32_t, DrmFormatInfo> allSupportedDrmFormats() const;
@@ -72,6 +78,7 @@
     const bool m_supportsBufferAge;
     const bool m_supportsNativeFence;
     QHash<uint32_t, DrmFormatInfo> m_importFormats;
+    bool m_dmaBufSyncRequired = false;

     struct
     {
--- a/src/plugins/qpa/eglplatformcontext.cpp
+++ b/src/plugins/qpa/eglplatformcontext.cpp
@@ -15,6 +15,7 @@
 #include "offscreensurface.h"
 #include "opengl/eglcontext.h"
 #include "opengl/egldisplay.h"
+#include "opengl/eglnativefence.h"
 #include "opengl/eglutils_p.h"
 #include "opengl/glutils.h"
 #include "swapchain.h"
@@ -85,7 +86,7 @@
         }

         GraphicsBuffer *buffer = swapchain->acquire();
-        if (!buffer) {
+        if (!buffer || !m_eglDisplay->acquireDmaBuf(buffer, true)) {
             return false;
         }

@@ -159,6 +160,13 @@

         glFlush(); // We need to flush pending rendering commands manually

+        if (m_eglDisplay->dmaBufSyncRequired()) {
+            EGLNativeFence fence{m_eglDisplay};
+            if (!m_eglDisplay->releaseDmaBuf(m_current->buffer, fence.fileDescriptor(), true)) {
+                qFatal("Could not publish internal window DMA-BUF completion");
+            }
+        }
+
         internalWindow->present(InternalWindowFrame{
             .buffer = m_current->buffer,
             .bufferDamage = Rect(QPoint(0, 0), m_current->buffer->size()),
--- a/src/plugins/qpa/window.cpp
+++ b/src/plugins/qpa/window.cpp
@@ -12,10 +12,10 @@
 #include "utils/drm_format_helper.h"

 #include "compositor.h"
-#include "core/drmdevice.h"
 #include "core/renderbackend.h"
 #include "core/shmgraphicsbufferallocator.h"
 #include "internalwindow.h"
+#include "opengl/eglbackend.h"
 #include "swapchain.h"
 #include "window.h"

@@ -57,7 +57,11 @@
             static ShmGraphicsBufferAllocator shmAllocator;
             allocator = &shmAllocator;
         } else {
-            allocator = Compositor::self()->backend()->drmDevice()->allocator();
+            const auto backend = qobject_cast<EglBackend *>(Compositor::self()->backend());
+            allocator = backend ? backend->graphicsBufferAllocator() : nullptr;
+        }
+        if (!allocator) {
+            return nullptr;
         }

         for (auto it = formats.begin(); it != formats.end(); it++) {
--- a/src/plugins/screencast/screencaststream.cpp
+++ b/src/plugins/screencast/screencaststream.cpp
@@ -874,7 +874,7 @@
 std::optional<ScreenCastDmaBufTextureParams> ScreenCastStream::testCreateDmaBuf(const QSize &size, quint32 format, const QList<uint64_t> &modifiers)
 {
     EglBackend *backend = qobject_cast<EglBackend *>(Compositor::self()->backend());
-    if (!backend) {
+    if (!backend || !backend->drmDevice()) {
         return std::nullopt;
     }

--- a/src/scene/itemrenderer_opengl.cpp
+++ b/src/scene/itemrenderer_opengl.cpp
@@ -11,6 +11,7 @@
 #include "core/renderviewport.h"
 #include "core/syncobjtimeline.h"
 #include "effect/effect.h"
+#include "opengl/egldisplay.h"
 #include "opengl/eglnativefence.h"
 #include "scene/decorationitem.h"
 #include "scene/imageitem.h"
@@ -53,6 +54,13 @@

     if (m_eglDisplay) {
         EGLNativeFence fence(m_eglDisplay);
+        for (auto *buffer : m_dmaBufReads) {
+            if (!m_eglDisplay->releaseDmaBuf(buffer, fence.fileDescriptor(), false)) {
+                qFatal("Could not publish DMA-BUF read completion");
+            }
+            buffer->unref();
+        }
+        m_dmaBufReads.clear();
         if (fence.isValid()) {
             for (const auto &releasePoint : m_releasePoints) {
                 releasePoint->addReleaseFence(fence.fileDescriptor());
@@ -229,6 +237,7 @@
                     .colorDescription = item->colorDescription(),
                     .renderingIntent = item->renderingIntent(),
                     .bufferReleasePoint = surfaceItem->bufferReleasePoint(),
+                    .buffer = surfaceItem->buffer(),
                     .paintHole = hole,
                     .hasFloatingPointColor = texture->isFloatingPoint(),
                 });
@@ -423,6 +432,15 @@
             setBlendEnabled(true);
         } else {
             glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
+            if (m_eglDisplay && m_eglDisplay->dmaBufSyncRequired() && renderNode.buffer
+                && renderNode.buffer->dmabufAttributes() && !m_dmaBufReads.contains(renderNode.buffer)) {
+                if (!m_eglDisplay->acquireDmaBuf(renderNode.buffer, false)) {
+                    qCWarning(KWIN_OPENGL) << "Could not acquire client DMA-BUF for reading";
+                    continue;
+                }
+                renderNode.buffer->ref();
+                m_dmaBufReads.insert(renderNode.buffer);
+            }
             setBlendEnabled(renderNode.hasAlpha || renderNode.opacity < 1.0);
         }

--- a/src/scene/itemrenderer_opengl.h
+++ b/src/scene/itemrenderer_opengl.h
@@ -33,6 +33,7 @@
         std::shared_ptr<ColorDescription> colorDescription;
         RenderingIntent renderingIntent;
         std::shared_ptr<SyncReleasePoint> bufferReleasePoint;
+        GraphicsBuffer *buffer = nullptr;
         QVector4D box;
         QVector4D borderRadius;
         int borderThickness = 0;
@@ -81,6 +82,7 @@
     bool m_blendingEnabled = false;
     EglDisplay *const m_eglDisplay;
     std::unordered_set<std::shared_ptr<SyncReleasePoint>> m_releasePoints;
+    std::unordered_set<GraphicsBuffer *> m_dmaBufReads;

     struct
     {
--- a/src/wayland-client/linuxdmabuf.cpp
+++ b/src/wayland-client/linuxdmabuf.cpp
@@ -172,7 +172,9 @@
     };
     zwp_linux_dmabuf_v1_add_listener(m_dmabuf, &dmabufListener, this);

-    m_defaultFeedback = std::make_unique<LinuxDmabufFeedbackV1>(zwp_linux_dmabuf_v1_get_default_feedback(m_dmabuf));
+    if (version >= 4) {
+        m_defaultFeedback = std::make_unique<LinuxDmabufFeedbackV1>(zwp_linux_dmabuf_v1_get_default_feedback(m_dmabuf));
+    }
 }

 LinuxDmabufV1::~LinuxDmabufV1()
@@ -187,12 +189,12 @@

 QByteArray LinuxDmabufV1::mainDevice() const
 {
-    return m_defaultFeedback->devicePath();
+    return m_defaultFeedback ? m_defaultFeedback->devicePath() : QByteArray();
 }

 QHash<uint32_t, QList<uint64_t>> LinuxDmabufV1::formats() const
 {
-    return m_defaultFeedback->formats();
+    return m_defaultFeedback ? m_defaultFeedback->formats() : m_formats;
 }

 void LinuxDmabufV1::format(void *data, struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1, uint32_t format)
@@ -202,7 +204,12 @@

 void LinuxDmabufV1::modifier(void *data, struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1, uint32_t format, uint32_t modifier_hi, uint32_t modifier_lo)
 {
-    // Not sent in v4 and onward.
+    auto *self = static_cast<LinuxDmabufV1 *>(data);
+    const uint64_t modifier = (uint64_t(modifier_hi) << 32) | modifier_lo;
+    auto &modifiers = self->m_formats[format];
+    if (!modifiers.contains(modifier)) {
+        modifiers.append(modifier);
+    }
 }

 wl_buffer *LinuxDmabufV1::importBuffer(GraphicsBuffer *graphicsBuffer) const
@@ -229,6 +236,9 @@

 std::unique_ptr<LinuxDmabufFeedbackV1> LinuxDmabufV1::getSurfaceFeedback(wl_surface *surface) const
 {
+    if (!m_defaultFeedback) {
+        return nullptr;
+    }
     return std::make_unique<LinuxDmabufFeedbackV1>(zwp_linux_dmabuf_v1_get_surface_feedback(m_dmabuf, surface));
 }
 }
--- a/src/wayland-client/linuxdmabuf.h
+++ b/src/wayland-client/linuxdmabuf.h
@@ -93,6 +93,7 @@

     zwp_linux_dmabuf_v1 *m_dmabuf;
     std::unique_ptr<LinuxDmabufFeedbackV1> m_defaultFeedback;
+    QHash<uint32_t, QList<uint64_t>> m_formats;
 };

 }
--- a/src/wayland/linuxdmabufv1clientbuffer.cpp
+++ b/src/wayland/linuxdmabufv1clientbuffer.cpp
@@ -27,9 +27,10 @@
 {
 static const int s_version = 5;

-LinuxDmaBufV1ClientBufferIntegrationPrivate::LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display)
-    : QtWaylandServer::zwp_linux_dmabuf_v1(*display, s_version)
+LinuxDmaBufV1ClientBufferIntegrationPrivate::LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display, bool deviceFeedback)
+    : QtWaylandServer::zwp_linux_dmabuf_v1(*display, deviceFeedback ? s_version : 3)
     , q(q)
+    , deviceFeedback(deviceFeedback)
     , defaultFeedback(new LinuxDmaBufV1Feedback(this))
 {
 }
@@ -295,9 +296,9 @@
     return true;
 }

-LinuxDmaBufV1ClientBufferIntegration::LinuxDmaBufV1ClientBufferIntegration(Display *display)
+LinuxDmaBufV1ClientBufferIntegration::LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback)
     : QObject(display)
-    , d(new LinuxDmaBufV1ClientBufferIntegrationPrivate(this, display))
+    , d(new LinuxDmaBufV1ClientBufferIntegrationPrivate(this, display, deviceFeedback))
 {
 }

@@ -332,6 +333,12 @@
         d->table = std::make_unique<LinuxDmaBufV1FormatTable>(set);
         d->defaultFeedback->setTranches(tranches);
     }
+}
+
+void LinuxDmaBufV1ClientBufferIntegration::setSupportedFormatsWithModifiers(const QHash<uint32_t, QList<uint64_t>> &formats)
+{
+    Q_ASSERT(!d->deviceFeedback);
+    d->supportedModifiers = formats;
 }

 void LinuxDmaBufV1ClientBuffer::buffer_destroy_resource(wl_resource *resource)
--- a/src/wayland/linuxdmabufv1clientbuffer.h
+++ b/src/wayland/linuxdmabufv1clientbuffer.h
@@ -66,13 +66,14 @@
     Q_OBJECT

 public:
-    explicit LinuxDmaBufV1ClientBufferIntegration(Display *display);
+    explicit LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback = true);
     ~LinuxDmaBufV1ClientBufferIntegration() override;

     RenderBackend *renderBackend() const;
     void setRenderBackend(RenderBackend *renderBackend);

     void setSupportedFormatsWithModifiers(const QList<LinuxDmaBufV1Feedback::Tranche> &tranches);
+    void setSupportedFormatsWithModifiers(const QHash<uint32_t, QList<uint64_t>> &formats);

 private:
     friend class LinuxDmaBufV1ClientBufferIntegrationPrivate;
--- a/src/wayland/linuxdmabufv1clientbuffer_p.h
+++ b/src/wayland/linuxdmabufv1clientbuffer_p.h
@@ -31,9 +31,10 @@
 class LinuxDmaBufV1ClientBufferIntegrationPrivate : public QtWaylandServer::zwp_linux_dmabuf_v1
 {
 public:
-    LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display);
+    LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display, bool deviceFeedback);

     LinuxDmaBufV1ClientBufferIntegration *q;
+    const bool deviceFeedback;
     std::unique_ptr<LinuxDmaBufV1Feedback> defaultFeedback;
     std::unique_ptr<LinuxDmaBufV1FormatTable> table;
     dev_t mainDevice;
--- a/src/wayland_server.cpp
+++ b/src/wayland_server.cpp
@@ -547,10 +547,10 @@
     return m_drm;
 }

-LinuxDmaBufV1ClientBufferIntegration *WaylandServer::linuxDmabuf()
+LinuxDmaBufV1ClientBufferIntegration *WaylandServer::linuxDmabuf(bool deviceFeedback)
 {
     if (!m_linuxDmabuf) {
-        m_linuxDmabuf = new LinuxDmaBufV1ClientBufferIntegration(m_display);
+        m_linuxDmabuf = new LinuxDmaBufV1ClientBufferIntegration(m_display, deviceFeedback);
     }
     return m_linuxDmabuf;
 }
@@ -857,7 +857,7 @@

 void WaylandServer::setRenderBackend(RenderBackend *backend)
 {
-    if (backend->drmDevice()->supportsSyncObjTimelines()) {
+    if (const auto device = backend->drmDevice(); device && device->supportsSyncObjTimelines()) {
         // ensure the DRM_IOCTL_SYNCOBJ_EVENTFD ioctl is supported
         const auto linuxVersion = linuxKernelVersion();
         if (linuxVersion.majorVersion() < 6 && linuxVersion.minorVersion() < 6) {
--- a/src/wayland_server.h
+++ b/src/wayland_server.h
@@ -137,7 +137,7 @@
     bool isKeyboardShortcutsInhibited() const;

     DrmClientBufferIntegration *drm();
-    LinuxDmaBufV1ClientBufferIntegration *linuxDmabuf();
+    LinuxDmaBufV1ClientBufferIntegration *linuxDmabuf(bool deviceFeedback = true);

     InputMethodV1Interface *inputMethod() const
     {
PATCH
}

kwin_pointer_patch() {
    cat <<'PATCH'
--- a/src/backends/wayland/wayland_backend.cpp
+++ b/src/backends/wayland/wayland_backend.cpp
@@ -17,2 +17,7 @@
 #include "wayland_qpainter_backend.h"
+#include "wayland/pointer.h"
+#include "wayland/pointerconstraints_v1.h"
+#include "wayland/seat.h"
+#include "wayland/surface.h"
+#include "wayland_server.h"

PATCH
    if [ "${1:-}" = 6.6.6 ]; then
        cat <<'PATCH'
@@ -89,8 +94,15 @@
     connect(pointer, &Pointer::entered, this, [this](quint32 serial, const QPointF &relativeToSurface) {
         WaylandOutput *output = m_seat->backend()->findOutput(m_pointer->enteredSurface());
         Q_ASSERT(output);
         output->cursor()->setPointer(m_pointer.get());
+        // Enter and motion use the same output/subsurface coordinate space.
+        const auto subsurface = m_seat->backend()->findSubSurface(m_pointer->enteredSurface());
+        const QPointF absolutePos = output->position() + relativeToSurface
+            + (subsurface ? subsurface->position() : QPoint());
+        const auto timestamp = std::chrono::duration_cast<std::chrono::microseconds>(
+            std::chrono::steady_clock::now().time_since_epoch());
+        Q_EMIT pointerMotionAbsolute(absolutePos, timestamp, this);
     });
     connect(pointer, &Pointer::left, this, [this]() {
         // wl_pointer.leave carries the wl_surface, but KWayland::Client::Pointer::left does not.
         const auto outputs = m_seat->backend()->outputs();
PATCH
    else
        cat <<'PATCH'
@@ -85,8 +90,13 @@
     connect(pointer, &Pointer::entered, this, [this](quint32 serial, const QPointF &relativeToSurface) {
         WaylandOutput *output = m_seat->backend()->findOutput(m_pointer->enteredSurface());
         Q_ASSERT(output);
         output->cursor()->setPointer(m_pointer.get());
+        // Enter carries the position even when no motion follows before a click.
+        const QPointF absolutePos = output->geometry().topLeft() + relativeToSurface;
+        const auto timestamp = std::chrono::duration_cast<std::chrono::microseconds>(
+            std::chrono::steady_clock::now().time_since_epoch());
+        Q_EMIT pointerMotionAbsolute(absolutePos, timestamp, this);
     });
     connect(pointer, &Pointer::left, this, [this]() {
         // wl_pointer.leave carries the wl_surface, but KWayland::Client::Pointer::left does not.
         const auto outputs = m_seat->backend()->outputs();
PATCH
    fi
    cat <<'PATCH'
@@ -190,3 +195,3 @@
     });
-    connect(touch, &Touch::sequenceStarted, this, [this](TouchPoint *tp) {
+    connect(touch, &Touch::sequenceStarted, this, [this](KWayland::Client::TouchPoint *tp) {
         auto o = m_seat->backend()->findOutput(tp->surface());
@@ -196,3 +201,3 @@
     });
-    connect(touch, &Touch::pointAdded, this, [this](TouchPoint *tp) {
+    connect(touch, &Touch::pointAdded, this, [this](KWayland::Client::TouchPoint *tp) {
         auto o = m_seat->backend()->findOutput(tp->surface());
@@ -202,6 +207,6 @@
     });
-    connect(touch, &Touch::pointRemoved, this, [this](TouchPoint *tp) {
+    connect(touch, &Touch::pointRemoved, this, [this](KWayland::Client::TouchPoint *tp) {
         Q_EMIT touchUp(tp->id(), std::chrono::milliseconds(tp->time()), this);
     });
-    connect(touch, &Touch::pointMoved, this, [this](TouchPoint *tp) {
+    connect(touch, &Touch::pointMoved, this, [this](KWayland::Client::TouchPoint *tp) {
         auto o = m_seat->backend()->findOutput(tp->surface());
@@ -314,2 +319,5 @@
     connect(seat, &WaylandSeat::deviceRemoved, this, &InputBackend::deviceRemoved);
+    connect(waylandServer()->seat()->pointer(), &PointerInterface::focusedSurfaceChanged,
+            m_backend, &WaylandBackend::watchPointerConstraints);
+    m_backend->watchPointerConstraints();
 }
@@ -543,6 +551,46 @@
 {
-    if (!supportsPointerLock()) {
+    m_automaticPointerLock = false;
+    setPointerLock(!m_pointerLockRequested);
+}
+
+void WaylandBackend::watchPointerConstraints()
+{
+    disconnect(m_surfaceConstraintsConnection);
+    disconnect(m_clientLockConnection);
+    if (auto surface = waylandServer()->seat()->focusedPointerSurface()) {
+        m_surfaceConstraintsConnection = connect(surface, &SurfaceInterface::pointerConstraintsChanged,
+                                                 this, &WaylandBackend::watchPointerConstraints);
+        if (auto lock = surface->lockedPointer()) {
+            m_clientLockConnection = connect(lock, &LockedPointerV1Interface::lockedChanged,
+                                             this, &WaylandBackend::updateClientPointerLock);
+        }
+    }
+    updateClientPointerLock();
+}
+
+void WaylandBackend::updateClientPointerLock()
+{
+    auto surface = waylandServer()->seat()->focusedPointerSurface();
+    auto lock = surface ? surface->lockedPointer() : nullptr;
+    const bool locked = lock && lock->isLocked();
+    if (m_clientPointerLocked == locked) {
+        return;
+    }
+    m_clientPointerLocked = locked;
+    // A manual grab outlives inner-client focus; an automatic grab does not.
+    if (locked && !m_pointerLockRequested) {
+        setPointerLock(true);
+        m_automaticPointerLock = m_pointerLockRequested;
+    } else if (!locked && m_automaticPointerLock) {
+        m_automaticPointerLock = false;
+        setPointerLock(false);
+    }
+}
+
+void WaylandBackend::setPointerLock(bool requested)
+{
+    if (m_pointerLockRequested == requested || !supportsPointerLock()) {
         return;
     }
-    if (!m_seat) {
+    if (!m_seat || !m_seat->pointerDevice()) {
         return;
@@ -558,5 +606,5 @@
     for (auto output : std::as_const(m_outputs)) {
-        output->lockPointer(m_seat->pointerDevice()->nativePointer(), !m_pointerLockRequested);
+        output->lockPointer(pointer, requested);
     }
-    m_pointerLockRequested = !m_pointerLockRequested;
+    m_pointerLockRequested = requested;
 }
--- a/src/backends/wayland/wayland_backend.h
+++ b/src/backends/wayland/wayland_backend.h
@@ -228,2 +228,3 @@
     void togglePointerLock();
+    void watchPointerConstraints();

@@ -257,2 +258,4 @@
     void createOutputs();
+    void setPointerLock(bool requested);
+    void updateClientPointerLock();
     void destroyOutputs();
@@ -266,2 +269,6 @@
     bool m_pointerLockRequested = false;
+    bool m_clientPointerLocked = false;
+    bool m_automaticPointerLock = false;
+    QMetaObject::Connection m_surfaceConstraintsConnection;
+    QMetaObject::Connection m_clientLockConnection;
     std::unique_ptr<DrmDevice> m_drmDevice;
--- a/src/backends/wayland/wayland_output.cpp
+++ b/src/backends/wayland/wayland_output.cpp
@@ -307,3 +307,3 @@
     Q_ASSERT(!m_pointerLock);
-    m_pointerLock.reset(m_backend->display()->pointerConstraints()->lockPointer(surface(), pointer, nullptr, PointerConstraints::LifeTime::OneShot));
+    m_pointerLock.reset(m_backend->display()->pointerConstraints()->lockPointer(surface(), pointer, nullptr, PointerConstraints::LifeTime::Persistent));
     if (!m_pointerLock->isValid()) {
PATCH
    if [ "${1:-}" = 6.6.6 ]; then
        cat <<'PATCH'
@@ -526,3 +526,3 @@
     connect(m_pointerLock.get(), &LockedPointer::unlocked, this, [this]() {
-        m_pointerLock.reset();
+        // Keep the request across host focus loss; manual release destroys it.
         inhibitShortcuts(false);
PATCH
    else
        cat <<'PATCH'
@@ -319,3 +319,3 @@
     connect(m_pointerLock.get(), &LockedPointer::unlocked, this, [this]() {
-        m_pointerLock.reset();
+        // Keep the request across host focus loss; manual release destroys it.
         m_hasPointerLock = false;
PATCH
    fi
}

kwin_patch() {
    if [ "${1:-}" = 6.6.6 ]; then kwin_patch_6_6; kwin_pointer_patch "$1"; return; fi
    case "${1:-6.3.6}" in 6.2.5|6.3.6) ;; *) return 1 ;; esac
    cat <<'PATCH'
KWin: nested DMA-BUF v3/EGL rendering without a DRM device.

--- a/src/backends/wayland/CMakeLists.txt
+++ b/src/backends/wayland/CMakeLists.txt
@@ -1,4 +1,5 @@
 target_sources(kwin PRIVATE
+    dmaheapgraphicsbufferallocator.cpp
     wayland_backend.cpp
     wayland_display.cpp
     wayland_egl_backend.cpp
--- a/src/backends/wayland/dmaheapgraphicsbufferallocator.cpp
+++ b/src/backends/wayland/dmaheapgraphicsbufferallocator.cpp
@@ -0,0 +1,78 @@
+/* SPDX-License-Identifier: GPL-2.0-or-later */
+#include "dmaheapgraphicsbufferallocator.h"
+#include "core/graphicsbuffer.h"
+#include "utils/drm_format_helper.h"
+
+#include <drm_fourcc.h>
+#include <fcntl.h>
+#include <limits>
+#include <linux/dma-heap.h>
+#include <sys/ioctl.h>
+
+namespace KWin::Wayland
+{
+
+class DmaHeapGraphicsBuffer : public GraphicsBuffer
+{
+public:
+    explicit DmaHeapGraphicsBuffer(DmaBufAttributes &&attributes)
+        : m_attributes(std::move(attributes))
+    {
+    }
+
+    QSize size() const override { return {m_attributes.width, m_attributes.height}; }
+    bool hasAlphaChannel() const override { return alphaChannelFromDrmFormat(m_attributes.format); }
+    const DmaBufAttributes *dmabufAttributes() const override { return &m_attributes; }
+
+private:
+    DmaBufAttributes m_attributes;
+};
+
+DmaHeapGraphicsBufferAllocator::DmaHeapGraphicsBufferAllocator()
+    : m_heap(open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC))
+{
+}
+
+bool DmaHeapGraphicsBufferAllocator::isValid() const
+{
+    return m_heap.isValid();
+}
+
+GraphicsBuffer *DmaHeapGraphicsBufferAllocator::allocate(const GraphicsBufferOptions &options)
+{
+    if (!isValid() || options.software || options.size.isEmpty()
+        || !options.modifiers.contains(DRM_FORMAT_MOD_LINEAR)) {
+        return nullptr;
+    }
+    switch (options.format) {
+    case DRM_FORMAT_XRGB8888:
+    case DRM_FORMAT_ARGB8888:
+    case DRM_FORMAT_XBGR8888:
+    case DRM_FORMAT_ABGR8888:
+        break;
+    default:
+        return nullptr;
+    }
+    const uint64_t stride = (uint64_t(options.size.width()) * 4 + 255) & ~uint64_t(255);
+    const uint64_t length = stride * options.size.height();
+    if (stride > std::numeric_limits<uint32_t>::max() || length > std::numeric_limits<int32_t>::max()) {
+        return nullptr;
+    }
+    dma_heap_allocation_data allocation{};
+    allocation.len = length;
+    allocation.fd_flags = O_RDWR | O_CLOEXEC;
+    if (ioctl(m_heap.get(), DMA_HEAP_IOCTL_ALLOC, &allocation) < 0) {
+        return nullptr;
+    }
+    DmaBufAttributes attributes;
+    attributes.width = options.size.width();
+    attributes.height = options.size.height();
+    attributes.format = options.format;
+    attributes.modifier = DRM_FORMAT_MOD_LINEAR;
+    attributes.planeCount = 1;
+    attributes.fd[0] = FileDescriptor(allocation.fd);
+    attributes.pitch[0] = stride;
+    return new DmaHeapGraphicsBuffer(std::move(attributes));
+}
+
+}
--- a/src/backends/wayland/dmaheapgraphicsbufferallocator.h
+++ b/src/backends/wayland/dmaheapgraphicsbufferallocator.h
@@ -0,0 +1,21 @@
+/* SPDX-License-Identifier: GPL-2.0-or-later */
+#pragma once
+
+#include "core/graphicsbufferallocator.h"
+#include "utils/filedescriptor.h"
+
+namespace KWin::Wayland
+{
+
+class DmaHeapGraphicsBufferAllocator : public GraphicsBufferAllocator
+{
+public:
+    DmaHeapGraphicsBufferAllocator();
+    bool isValid() const;
+    GraphicsBuffer *allocate(const GraphicsBufferOptions &options) override;
+
+private:
+    FileDescriptor m_heap;
+};
+
+}
--- a/src/backends/wayland/wayland_backend.cpp
+++ b/src/backends/wayland/wayland_backend.cpp
@@ -431,7 +431,7 @@
         return false;
     }

-    if (WaylandLinuxDmabufV1 *dmabuf = m_display->linuxDmabuf()) {
+    if (WaylandLinuxDmabufV1 *dmabuf = m_display->linuxDmabuf(); dmabuf && !dmabuf->mainDevice().isEmpty()) {
         m_drmDevice = DrmDevice::open(dmabuf->mainDevice());
         if (!m_drmDevice) {
             qCWarning(KWIN_WAYLAND_BACKEND) << "Failed to open drm render node" << dmabuf->mainDevice();
@@ -550,7 +550,7 @@
 QList<CompositingType> WaylandBackend::supportedCompositors() const
 {
     QList<CompositingType> ret;
-    if (m_display->linuxDmabuf() && m_drmDevice) {
+    if (m_display->linuxDmabuf() && !m_display->linuxDmabuf()->formats().isEmpty()) {
         ret.append(OpenGLCompositing);
     }
     ret.append(QPainterCompositing);
--- a/src/backends/wayland/wayland_display.cpp
+++ b/src/backends/wayland/wayland_display.cpp
@@ -281,7 +281,9 @@
     };
     zwp_linux_dmabuf_v1_add_listener(m_dmabuf, &dmabufListener, this);

-    m_defaultFeedback = std::make_unique<WaylandLinuxDmabufFeedbackV1>(zwp_linux_dmabuf_v1_get_default_feedback(m_dmabuf));
+    if (version >= 4) {
+        m_defaultFeedback = std::make_unique<WaylandLinuxDmabufFeedbackV1>(zwp_linux_dmabuf_v1_get_default_feedback(m_dmabuf));
+    }
 }

 WaylandLinuxDmabufV1::~WaylandLinuxDmabufV1()
@@ -296,12 +298,12 @@

 QByteArray WaylandLinuxDmabufV1::mainDevice() const
 {
-    return m_defaultFeedback->mainDevice;
+    return m_defaultFeedback ? m_defaultFeedback->mainDevice : QByteArray();
 }

 QHash<uint32_t, QList<uint64_t>> WaylandLinuxDmabufV1::formats() const
 {
-    return m_defaultFeedback->formats;
+    return m_defaultFeedback ? m_defaultFeedback->formats : m_formats;
 }

 void WaylandLinuxDmabufV1::format(void *data, struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1, uint32_t format)
@@ -311,7 +313,12 @@

 void WaylandLinuxDmabufV1::modifier(void *data, struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1, uint32_t format, uint32_t modifier_hi, uint32_t modifier_lo)
 {
-    // Not sent in v4 and onward.
+    auto *self = static_cast<WaylandLinuxDmabufV1 *>(data);
+    const uint64_t modifier = (uint64_t(modifier_hi) << 32) | modifier_lo;
+    auto &modifiers = self->m_formats[format];
+    if (!modifiers.contains(modifier)) {
+        modifiers.append(modifier);
+    }
 }

 WaylandDisplay::WaylandDisplay()
@@ -460,8 +467,8 @@
         display->m_xdgDecorationManager = std::make_unique<KWayland::Client::XdgDecorationManager>();
         display->m_xdgDecorationManager->setup(static_cast<zxdg_decoration_manager_v1 *>(wl_registry_bind(registry, name, &zxdg_decoration_manager_v1_interface, std::min(version, 1u))));
     } else if (strcmp(interface, zwp_linux_dmabuf_v1_interface.name) == 0) {
-        if (version < 4) {
-            qWarning("zwp_linux_dmabuf_v1 v4 or newer is needed");
+        if (version < 3) {
+            qWarning("zwp_linux_dmabuf_v1 v3 or newer is needed");
             return;
         }
         display->m_linuxDmabuf = std::make_unique<WaylandLinuxDmabufV1>(registry, name, std::min(version, 4u));
--- a/src/backends/wayland/wayland_display.h
+++ b/src/backends/wayland/wayland_display.h
@@ -55,6 +55,7 @@

     zwp_linux_dmabuf_v1 *m_dmabuf;
     std::unique_ptr<WaylandLinuxDmabufFeedbackV1> m_defaultFeedback;
+    QHash<uint32_t, QList<uint64_t>> m_formats;
 };

 class WaylandDisplay : public QObject
--- a/src/backends/wayland/wayland_egl_backend.h
+++ b/src/backends/wayland/wayland_egl_backend.h
@@ -98,6 +98,9 @@

     WaylandBackend *backend() const;
     DrmDevice *drmDevice() const override;
+    GraphicsBufferAllocator *graphicsBufferAllocator() const override;
+    bool acquireBuffer(GraphicsBuffer *buffer);
+    bool releaseBuffer(GraphicsBuffer *buffer, const FileDescriptor &fence);

     std::unique_ptr<SurfaceTexture> createSurfaceTextureWayland(SurfacePixmap *pixmap) override;

@@ -121,6 +124,7 @@
     };

     WaylandBackend *m_backend;
+    std::unique_ptr<GraphicsBufferAllocator> m_allocator;
     std::map<Output *, Layers> m_outputs;
 };

--- a/src/opengl/egldisplay.cpp
+++ b/src/opengl/egldisplay.cpp
@@ -7,6 +7,9 @@
     SPDX-License-Identifier: GPL-2.0-or-later
 */
 #include "egldisplay.h"
+#include "eglnativefence.h"
+#include <linux/dma-buf.h>
+#include <sys/ioctl.h>
 #include "core/drmdevice.h"
 #include "core/graphicsbuffer.h"
 #include "opengl/eglutils_p.h"
@@ -24,6 +27,54 @@
 namespace KWin
 {

+void EglDisplay::setDmaBufSyncRequired(bool required)
+{
+    m_dmaBufSyncRequired = required;
+}
+
+bool EglDisplay::dmaBufSyncRequired() const
+{
+    return m_dmaBufSyncRequired;
+}
+
+bool EglDisplay::acquireDmaBuf(GraphicsBuffer *buffer, bool write)
+{
+    const auto attributes = buffer->dmabufAttributes();
+    if (!m_dmaBufSyncRequired || !attributes) {
+        return true;
+    }
+    for (int plane = 0; plane < attributes->planeCount; ++plane) {
+        dma_buf_export_sync_file sync{.flags = uint32_t(write ? DMA_BUF_SYNC_RW : DMA_BUF_SYNC_READ), .fd = -1};
+        if (ioctl(attributes->fd[plane].get(), DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &sync) < 0) {
+            return false;
+        }
+        // This is a GPU dependency; it does not block the compositor thread.
+        auto fence = EGLNativeFence::importFence(this, FileDescriptor(sync.fd));
+        if (!fence.waitSync()) {
+            return false;
+        }
+    }
+    return true;
+}
+
+bool EglDisplay::releaseDmaBuf(GraphicsBuffer *buffer, const FileDescriptor &fence, bool write)
+{
+    const auto attributes = buffer->dmabufAttributes();
+    if (!m_dmaBufSyncRequired || !attributes) {
+        return true;
+    }
+    if (!fence.isValid()) {
+        return false;
+    }
+    for (int plane = 0; plane < attributes->planeCount; ++plane) {
+        dma_buf_import_sync_file sync{.flags = uint32_t(write ? DMA_BUF_SYNC_WRITE : DMA_BUF_SYNC_READ), .fd = fence.get()};
+        if (ioctl(attributes->fd[plane].get(), DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &sync) < 0) {
+            return false;
+        }
+    }
+    return true;
+}
+
 bool EglDisplay::shouldUseOpenGLES()
 {
     if (qstrcmp(qgetenv("KWIN_COMPOSE"), "O2ES") == 0) {
--- a/src/opengl/egldisplay.h
+++ b/src/opengl/egldisplay.h
@@ -22,6 +22,8 @@

 struct DmaBufAttributes;
 class GLTexture;
+class GraphicsBuffer;
+class FileDescriptor;

 class KWIN_EXPORT EglDisplay
 {
@@ -45,6 +47,10 @@

     bool supportsBufferAge() const;
     bool supportsNativeFence() const;
+    void setDmaBufSyncRequired(bool required);
+    bool dmaBufSyncRequired() const;
+    bool acquireDmaBuf(GraphicsBuffer *buffer, bool write);
+    bool releaseDmaBuf(GraphicsBuffer *buffer, const FileDescriptor &fence, bool write);

     QHash<uint32_t, QList<uint64_t>> nonExternalOnlySupportedDrmFormats() const;
     QHash<uint32_t, DrmFormatInfo> allSupportedDrmFormats() const;
@@ -72,6 +78,7 @@
     const bool m_supportsBufferAge;
     const bool m_supportsNativeFence;
     QHash<uint32_t, DrmFormatInfo> m_importFormats;
+    bool m_dmaBufSyncRequired = false;

     struct
     {
--- a/src/platformsupport/scenes/opengl/abstract_egl_backend.cpp
+++ b/src/platformsupport/scenes/opengl/abstract_egl_backend.cpp
@@ -94,6 +94,12 @@
     setSupportsBufferAge(m_display->supportsBufferAge());
 }

+GraphicsBufferAllocator *AbstractEglBackend::graphicsBufferAllocator() const
+{
+    const auto device = drmDevice();
+    return device ? device->allocator() : nullptr;
+}
+
 void AbstractEglBackend::initWayland()
 {
     if (!WaylandServer::self()) {
@@ -192,6 +198,19 @@
         LinuxDmaBufV1ClientBufferIntegration *dmabuf = waylandServer()->linuxDmabuf();
         dmabuf->setRenderBackend(this);
         dmabuf->setSupportedFormatsWithModifiers(m_tranches);
+    } else if (m_display->dmaBufSyncRequired()) {
+        QHash<uint32_t, QList<uint64_t>> formats;
+        const auto supported = m_display->allSupportedDrmFormats();
+        for (auto it = supported.constBegin(); it != supported.constEnd(); ++it) {
+            if (!it->nonExternalOnlyModifiers.isEmpty()) {
+                formats.insert(it.key(), it->nonExternalOnlyModifiers);
+            }
+        }
+        if (!formats.isEmpty()) {
+            auto *dmabuf = waylandServer()->linuxDmabuf(false);
+            dmabuf->setRenderBackend(this);
+            dmabuf->setSupportedFormatsWithModifiers(formats);
+        }
     }
     waylandServer()->setRenderBackend(this);
 }
--- a/src/platformsupport/scenes/opengl/abstract_egl_backend.h
+++ b/src/platformsupport/scenes/opengl/abstract_egl_backend.h
@@ -23,6 +23,7 @@

 struct DmaBufAttributes;
 class Output;
+class GraphicsBufferAllocator;

 class KWIN_EXPORT AbstractEglBackend : public OpenGLBackend
 {
@@ -36,6 +37,7 @@
     EglDisplay *eglDisplayObject() const override;
     EglContext *openglContext() const override;
     std::shared_ptr<EglContext> openglContextRef() const;
+    virtual GraphicsBufferAllocator *graphicsBufferAllocator() const;

     bool testImportBuffer(GraphicsBuffer *buffer) override;
     QHash<uint32_t, QList<uint64_t>> supportedFormats() const override;
--- a/src/plugins/qpa/eglplatformcontext.cpp
+++ b/src/plugins/qpa/eglplatformcontext.cpp
@@ -15,6 +15,7 @@
 #include "offscreensurface.h"
 #include "opengl/eglcontext.h"
 #include "opengl/egldisplay.h"
+#include "opengl/eglnativefence.h"
 #include "opengl/glutils.h"
 #include "swapchain.h"
 #include "window.h"
@@ -84,7 +85,7 @@
         }

         GraphicsBuffer *buffer = swapchain->acquire();
-        if (!buffer) {
+        if (!buffer || !m_eglDisplay->acquireDmaBuf(buffer, true)) {
             return false;
         }

@@ -158,6 +159,13 @@

         glFlush(); // We need to flush pending rendering commands manually

+        if (m_eglDisplay->dmaBufSyncRequired()) {
+            EGLNativeFence fence{m_eglDisplay};
+            if (!m_eglDisplay->releaseDmaBuf(m_current->buffer, fence.fileDescriptor(), true)) {
+                qFatal("Could not publish internal window DMA-BUF completion");
+            }
+        }
+
         internalWindow->present(InternalWindowFrame{
             .buffer = m_current->buffer,
             .bufferDamage = QRect(QPoint(0, 0), m_current->buffer->size()),
--- a/src/plugins/qpa/window.cpp
+++ b/src/plugins/qpa/window.cpp
@@ -12,10 +12,10 @@
 #include "utils/drm_format_helper.h"

 #include "compositor.h"
-#include "core/drmdevice.h"
 #include "core/renderbackend.h"
 #include "core/shmgraphicsbufferallocator.h"
 #include "internalwindow.h"
+#include "platformsupport/scenes/opengl/abstract_egl_backend.h"
 #include "swapchain.h"
 #include "window.h"

@@ -57,7 +57,11 @@
             static ShmGraphicsBufferAllocator shmAllocator;
             allocator = &shmAllocator;
         } else {
-            allocator = Compositor::self()->backend()->drmDevice()->allocator();
+            const auto backend = qobject_cast<AbstractEglBackend *>(Compositor::self()->backend());
+            allocator = backend ? backend->graphicsBufferAllocator() : nullptr;
+        }
+        if (!allocator) {
+            return nullptr;
         }

         for (auto it = formats.begin(); it != formats.end(); it++) {
--- a/src/plugins/screencast/screencaststream.cpp
+++ b/src/plugins/screencast/screencaststream.cpp
@@ -920,7 +920,7 @@
 std::optional<ScreenCastDmaBufTextureParams> ScreenCastStream::testCreateDmaBuf(const QSize &size, quint32 format, const QList<uint64_t> &modifiers)
 {
     AbstractEglBackend *backend = qobject_cast<AbstractEglBackend *>(Compositor::self()->backend());
-    if (!backend) {
+    if (!backend || !backend->drmDevice()) {
         return std::nullopt;
     }

--- a/src/scene/itemrenderer_opengl.cpp
+++ b/src/scene/itemrenderer_opengl.cpp
@@ -11,6 +11,7 @@
 #include "core/renderviewport.h"
 #include "core/syncobjtimeline.h"
 #include "effect/effect.h"
+#include "opengl/egldisplay.h"
 #include "opengl/eglnativefence.h"
 #include "platformsupport/scenes/opengl/openglsurfacetexture.h"
 #include "scene/decorationitem.h"
@@ -53,6 +54,13 @@

     if (m_eglDisplay) {
         EGLNativeFence fence(m_eglDisplay);
+        for (auto *buffer : m_dmaBufReads) {
+            if (!m_eglDisplay->releaseDmaBuf(buffer, fence.fileDescriptor(), false)) {
+                qFatal("Could not publish DMA-BUF read completion");
+            }
+            buffer->unref();
+        }
+        m_dmaBufReads.clear();
         if (fence.isValid()) {
             for (const auto &releasePoint : m_releasePoints) {
                 releasePoint->addReleaseFence(fence.fileDescriptor());
@@ -197,6 +205,7 @@
                     .colorDescription = item->colorDescription(),
                     .renderingIntent = item->renderingIntent(),
                     .bufferReleasePoint = surfaceItem->bufferReleasePoint(),
+                    .buffer = pixmap->buffer(),
                 });
             }
         }
@@ -339,6 +348,16 @@
             continue;
         }

+        if (m_eglDisplay && m_eglDisplay->dmaBufSyncRequired() && renderNode.buffer
+            && renderNode.buffer->dmabufAttributes() && !m_dmaBufReads.contains(renderNode.buffer)) {
+            if (!m_eglDisplay->acquireDmaBuf(renderNode.buffer, false)) {
+                qCWarning(KWIN_OPENGL) << "Could not acquire client DMA-BUF for reading";
+                continue;
+            }
+            renderNode.buffer->ref();
+            m_dmaBufReads.insert(renderNode.buffer);
+        }
+
         setBlendEnabled(renderNode.hasAlpha || renderNode.opacity < 1.0);

         ShaderTraits traits = baseShaderTraits;
--- a/src/scene/itemrenderer_opengl.h
+++ b/src/scene/itemrenderer_opengl.h
@@ -32,6 +32,7 @@
         ColorDescription colorDescription;
         RenderingIntent renderingIntent;
         std::shared_ptr<SyncReleasePoint> bufferReleasePoint;
+        GraphicsBuffer *buffer = nullptr;
     };

     struct RenderContext
@@ -65,6 +66,7 @@
     bool m_blendingEnabled = false;
     EglDisplay *const m_eglDisplay;
     std::unordered_set<std::shared_ptr<SyncReleasePoint>> m_releasePoints;
+    std::unordered_set<GraphicsBuffer *> m_dmaBufReads;

     struct
     {
--- a/src/wayland/linuxdmabufv1clientbuffer.cpp
+++ b/src/wayland/linuxdmabufv1clientbuffer.cpp
@@ -23,11 +23,10 @@

 namespace KWin
 {
-static const int s_version = 4;
-
-LinuxDmaBufV1ClientBufferIntegrationPrivate::LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display)
-    : QtWaylandServer::zwp_linux_dmabuf_v1(*display, s_version)
+LinuxDmaBufV1ClientBufferIntegrationPrivate::LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display, bool deviceFeedback)
+    : QtWaylandServer::zwp_linux_dmabuf_v1(*display, deviceFeedback ? 4 : 3)
     , q(q)
+    , deviceFeedback(deviceFeedback)
     , defaultFeedback(new LinuxDmaBufV1Feedback(this))
 {
 }
@@ -289,9 +288,9 @@
     return true;
 }

-LinuxDmaBufV1ClientBufferIntegration::LinuxDmaBufV1ClientBufferIntegration(Display *display)
+LinuxDmaBufV1ClientBufferIntegration::LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback)
     : QObject(display)
-    , d(new LinuxDmaBufV1ClientBufferIntegrationPrivate(this, display))
+    , d(new LinuxDmaBufV1ClientBufferIntegrationPrivate(this, display, deviceFeedback))
 {
 }

@@ -328,6 +327,12 @@
     }
 }

+void LinuxDmaBufV1ClientBufferIntegration::setSupportedFormatsWithModifiers(const QHash<uint32_t, QList<uint64_t>> &formats)
+{
+    Q_ASSERT(!d->deviceFeedback);
+    d->supportedModifiers = formats;
+}
+
 void LinuxDmaBufV1ClientBuffer::buffer_destroy_resource(wl_resource *resource)
 {
     if (LinuxDmaBufV1ClientBuffer *buffer = LinuxDmaBufV1ClientBuffer::get(resource)) {
--- a/src/wayland/linuxdmabufv1clientbuffer.h
+++ b/src/wayland/linuxdmabufv1clientbuffer.h
@@ -66,13 +66,14 @@
     Q_OBJECT

 public:
-    explicit LinuxDmaBufV1ClientBufferIntegration(Display *display);
+    explicit LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback = true);
     ~LinuxDmaBufV1ClientBufferIntegration() override;

     RenderBackend *renderBackend() const;
     void setRenderBackend(RenderBackend *renderBackend);

     void setSupportedFormatsWithModifiers(const QList<LinuxDmaBufV1Feedback::Tranche> &tranches);
+    void setSupportedFormatsWithModifiers(const QHash<uint32_t, QList<uint64_t>> &formats);

 private:
     friend class LinuxDmaBufV1ClientBufferIntegrationPrivate;
--- a/src/wayland/linuxdmabufv1clientbuffer_p.h
+++ b/src/wayland/linuxdmabufv1clientbuffer_p.h
@@ -31,9 +31,10 @@
 class LinuxDmaBufV1ClientBufferIntegrationPrivate : public QtWaylandServer::zwp_linux_dmabuf_v1
 {
 public:
-    LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display);
+    LinuxDmaBufV1ClientBufferIntegrationPrivate(LinuxDmaBufV1ClientBufferIntegration *q, Display *display, bool deviceFeedback);

     LinuxDmaBufV1ClientBufferIntegration *q;
+    const bool deviceFeedback;
     std::unique_ptr<LinuxDmaBufV1Feedback> defaultFeedback;
     std::unique_ptr<LinuxDmaBufV1FormatTable> table;
     dev_t mainDevice;
--- a/src/wayland_server.cpp
+++ b/src/wayland_server.cpp
@@ -521,10 +521,10 @@
     return m_drm;
 }

-LinuxDmaBufV1ClientBufferIntegration *WaylandServer::linuxDmabuf()
+LinuxDmaBufV1ClientBufferIntegration *WaylandServer::linuxDmabuf(bool deviceFeedback)
 {
     if (!m_linuxDmabuf) {
-        m_linuxDmabuf = new LinuxDmaBufV1ClientBufferIntegration(m_display);
+        m_linuxDmabuf = new LinuxDmaBufV1ClientBufferIntegration(m_display, deviceFeedback);
     }
     return m_linuxDmabuf;
 }
@@ -823,7 +823,7 @@

 void WaylandServer::setRenderBackend(RenderBackend *backend)
 {
-    if (backend->drmDevice()->supportsSyncObjTimelines()) {
+    if (const auto device = backend->drmDevice(); device && device->supportsSyncObjTimelines()) {
         // ensure the DRM_IOCTL_SYNCOBJ_EVENTFD ioctl is supported
         const auto linuxVersion = linuxKernelVersion();
         if (linuxVersion.majorVersion() < 6 && linuxVersion.minorVersion() < 6) {
--- a/src/wayland_server.h
+++ b/src/wayland_server.h
@@ -131,7 +131,7 @@
     bool isKeyboardShortcutsInhibited() const;

     DrmClientBufferIntegration *drm();
-    LinuxDmaBufV1ClientBufferIntegration *linuxDmabuf();
+    LinuxDmaBufV1ClientBufferIntegration *linuxDmabuf(bool deviceFeedback = true);

     InputMethodV1Interface *inputMethod() const
     {
PATCH
    case "${1:-6.3.6}" in
        6.2.5) cat <<'PATCH'
--- a/src/backends/wayland/wayland_egl_backend.cpp
+++ b/src/backends/wayland/wayland_egl_backend.cpp
@@ -9,6 +9,7 @@
 */

 #include "wayland_egl_backend.h"
+#include "dmaheapgraphicsbufferallocator.h"
 #include "core/drmdevice.h"
 #include "core/gbmgraphicsbufferallocator.h"
 #include "opengl/eglswapchain.h"
@@ -71,7 +72,7 @@
             if (it == formatTable.constEnd()) {
                 continue;
             }
-            m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());
+            m_swapchain = EglSwapchain::create(m_backend->graphicsBufferAllocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());
             if (m_swapchain) {
                 break;
             }
@@ -83,7 +84,7 @@
     }

     m_buffer = m_swapchain->acquire();
-    if (!m_buffer) {
+    if (!m_buffer || !m_backend->acquireBuffer(m_buffer->buffer())) {
         return std::nullopt;
     }

@@ -103,6 +104,9 @@
     // Flush rendering commands to the dmabuf.
     glFlush();
     EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
+    if (!m_backend->releaseBuffer(m_buffer->buffer(), releaseFence.fileDescriptor())) {
+        return false;
+    }

     m_presentationBuffer = m_backend->backend()->importBuffer(m_buffer->buffer());
     m_swapchain->release(m_buffer, releaseFence.takeFileDescriptor());
@@ -170,28 +174,24 @@
     const QSize bufferSize(std::ceil(tmp.width()), std::ceil(tmp.height()));
     if (!m_swapchain || m_swapchain->size() != bufferSize) {
         const QHash<uint32_t, QList<uint64_t>> formatTable = m_backend->backend()->display()->linuxDmabuf()->formats();
-        uint32_t format = DRM_FORMAT_INVALID;
-        QList<uint64_t> modifiers;
         for (const uint32_t &candidateFormat : {DRM_FORMAT_ARGB2101010, DRM_FORMAT_ARGB8888}) {
             auto it = formatTable.constFind(candidateFormat);
-            if (it != formatTable.constEnd()) {
-                format = it.key();
-                modifiers = it.value();
+            if (it == formatTable.constEnd()) {
+                continue;
+            }
+            m_swapchain = EglSwapchain::create(m_backend->graphicsBufferAllocator(), m_backend->openglContext(), bufferSize, it.key(), it.value());
+            if (m_swapchain) {
                 break;
             }
         }
-        if (format == DRM_FORMAT_INVALID) {
-            qCWarning(KWIN_WAYLAND_BACKEND) << "Could not find a suitable render format";
-            return std::nullopt;
-        }
-        m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), bufferSize, format, modifiers);
         if (!m_swapchain) {
+            qCWarning(KWIN_WAYLAND_BACKEND) << "Could not find a suitable cursor format";
             return std::nullopt;
         }
     }

     m_buffer = m_swapchain->acquire();
-    if (!m_buffer) {
+    if (!m_buffer || !m_backend->acquireBuffer(m_buffer->buffer())) {
         return std::nullopt;
     }

@@ -212,12 +212,16 @@
     // Flush rendering commands to the dmabuf.
     glFlush();

+    EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
+    if (!m_backend->releaseBuffer(m_buffer->buffer(), releaseFence.fileDescriptor())) {
+        return false;
+    }
+
     wl_buffer *buffer = m_backend->backend()->importBuffer(m_buffer->buffer());
     Q_ASSERT(buffer);

     static_cast<WaylandOutput *>(m_output)->cursor()->update(buffer, scale(), hotspot().toPoint());

-    EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
     m_swapchain->release(m_buffer, releaseFence.takeFileDescriptor());
     return true;
 }
@@ -259,6 +263,21 @@
     return m_backend->drmDevice();
 }

+GraphicsBufferAllocator *WaylandEglBackend::graphicsBufferAllocator() const
+{
+    return m_allocator ? m_allocator.get() : AbstractEglBackend::graphicsBufferAllocator();
+}
+
+bool WaylandEglBackend::acquireBuffer(GraphicsBuffer *buffer)
+{
+    return eglDisplayObject()->acquireDmaBuf(buffer, true);
+}
+
+bool WaylandEglBackend::releaseBuffer(GraphicsBuffer *buffer, const FileDescriptor &fence)
+{
+    return eglDisplayObject()->releaseDmaBuf(buffer, fence, true);
+}
+
 void WaylandEglBackend::cleanupSurfaces()
 {
     m_outputs.clear();
@@ -278,14 +297,19 @@
     initClientExtensions();

     if (!m_backend->sceneEglDisplayObject()) {
-        for (const QByteArray &extension : {QByteArrayLiteral("EGL_EXT_platform_base"), QByteArrayLiteral("EGL_KHR_platform_gbm")}) {
+        const auto platform = drmDevice() ? QByteArrayLiteral("EGL_KHR_platform_gbm") : QByteArrayLiteral("EGL_KHR_platform_wayland");
+        for (const QByteArray &extension : {QByteArrayLiteral("EGL_EXT_platform_base"), platform}) {
             if (!hasClientExtension(extension)) {
                 qCWarning(KWIN_WAYLAND_BACKEND) << extension << "client extension is not supported by the platform";
                 return false;
             }
         }

-        m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, m_backend->drmDevice()->gbmDevice(), nullptr)));
+        if (drmDevice()) {
+            m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, drmDevice()->gbmDevice(), nullptr)));
+        } else {
+            m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_WAYLAND_KHR, m_backend->display()->nativeDisplay(), nullptr)));
+        }
     }

     const auto display = m_backend->sceneEglDisplayObject();
@@ -293,6 +317,22 @@
         return false;
     }
     setEglDisplay(display);
+    if (!drmDevice()) {
+        for (const auto &extension : {QByteArrayLiteral("EGL_EXT_image_dma_buf_import_modifiers"),
+                                     QByteArrayLiteral("EGL_ANDROID_native_fence_sync"),
+                                     QByteArrayLiteral("EGL_KHR_wait_sync")}) {
+            if (!display->hasExtension(extension)) {
+                qCWarning(KWIN_WAYLAND_BACKEND) << "DMA heap rendering requires" << extension;
+                return false;
+            }
+        }
+        auto allocator = std::make_unique<DmaHeapGraphicsBufferAllocator>();
+        if (!allocator->isValid()) {
+            return false;
+        }
+        m_allocator = std::move(allocator);
+        display->setDmaBufSyncRequired(true);
+    }
     return true;
 }

@@ -307,6 +347,20 @@
         return;
     }

+    if (m_allocator) {
+        // Validate allocation, EGL import and both synchronization directions before
+        // advertising GPU composition or DMA-BUF to nested clients.
+        const auto formats = m_backend->display()->linuxDmabuf()->formats();
+        auto probe = EglSwapchain::create(m_allocator.get(), openglContext(), QSize(64, 64),
+                                          DRM_FORMAT_XRGB8888, formats.value(DRM_FORMAT_XRGB8888));
+        auto slot = probe ? probe->acquire() : nullptr;
+        EGLNativeFence fence{eglDisplayObject()};
+        if (!slot || !acquireBuffer(slot->buffer()) || !releaseBuffer(slot->buffer(), fence.fileDescriptor())) {
+            setFailed("DMA heap buffer import or synchronization is unavailable");
+            return;
+        }
+    }
+
     initWayland();
 }

--- a/src/compositor_wayland.cpp
+++ b/src/compositor_wayland.cpp
@@ -167,7 +167,7 @@
             break;
         } else if (qEnvironmentVariableIsSet("KWIN_COMPOSE")) {
             qCCritical(KWIN_CORE) << "Could not fulfill the requested compositing mode in KWIN_COMPOSE:" << type << ". Exiting.";
-            qApp->quit();
+            break;
         }
     }

@@ -176,7 +176,8 @@

         qCCritical(KWIN_CORE) << "The used windowing system requires compositing";
         qCCritical(KWIN_CORE) << "We are going to quit KWin now as it is broken";
-        qApp->quit();
+        // Startup may run before exec(); queue the failure for the event loop.
+        QMetaObject::invokeMethod(qApp, [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
         return;
     }

PATCH
            ;;
        6.3.6) cat <<'PATCH'
--- a/CMakeLists.txt
+++ b/CMakeLists.txt
@@ -7,7 +7,7 @@
 set(CMAKE_CXX_STANDARD 23)
 set(CMAKE_CXX_STANDARD_REQUIRED ON)

-set(PROJECT_DEP_VERSION "6.3.6")
+set(PROJECT_DEP_VERSION "6.3.4")
 set(QT_MIN_VERSION "6.7.0")
 set(KF6_MIN_VERSION "6.10.0")
 set(KDE_COMPILERSETTINGS_LEVEL "5.82")
--- a/src/backends/wayland/wayland_egl_backend.cpp
+++ b/src/backends/wayland/wayland_egl_backend.cpp
@@ -9,6 +9,7 @@
 */

 #include "wayland_egl_backend.h"
+#include "dmaheapgraphicsbufferallocator.h"
 #include "core/drmdevice.h"
 #include "core/gbmgraphicsbufferallocator.h"
 #include "opengl/eglswapchain.h"
@@ -71,7 +72,7 @@
             if (it == formatTable.constEnd()) {
                 continue;
             }
-            m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());
+            m_swapchain = EglSwapchain::create(m_backend->graphicsBufferAllocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());
             if (m_swapchain) {
                 break;
             }
@@ -83,7 +84,7 @@
     }

     m_buffer = m_swapchain->acquire();
-    if (!m_buffer) {
+    if (!m_buffer || !m_backend->acquireBuffer(m_buffer->buffer())) {
         return std::nullopt;
     }

@@ -103,6 +104,9 @@
     // Flush rendering commands to the dmabuf.
     glFlush();
     EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
+    if (!m_backend->releaseBuffer(m_buffer->buffer(), releaseFence.fileDescriptor())) {
+        return false;
+    }

     static_cast<WaylandOutput *>(m_output)->setPrimaryBuffer(m_backend->backend()->importBuffer(m_buffer->buffer()));
     m_swapchain->release(m_buffer, releaseFence.takeFileDescriptor());
@@ -159,28 +163,24 @@
     const QSize bufferSize(std::ceil(tmp.width()), std::ceil(tmp.height()));
     if (!m_swapchain || m_swapchain->size() != bufferSize) {
         const QHash<uint32_t, QList<uint64_t>> formatTable = m_backend->backend()->display()->linuxDmabuf()->formats();
-        uint32_t format = DRM_FORMAT_INVALID;
-        QList<uint64_t> modifiers;
         for (const uint32_t &candidateFormat : {DRM_FORMAT_ARGB2101010, DRM_FORMAT_ARGB8888}) {
             auto it = formatTable.constFind(candidateFormat);
-            if (it != formatTable.constEnd()) {
-                format = it.key();
-                modifiers = it.value();
+            if (it == formatTable.constEnd()) {
+                continue;
+            }
+            m_swapchain = EglSwapchain::create(m_backend->graphicsBufferAllocator(), m_backend->openglContext(), bufferSize, it.key(), it.value());
+            if (m_swapchain) {
                 break;
             }
         }
-        if (format == DRM_FORMAT_INVALID) {
-            qCWarning(KWIN_WAYLAND_BACKEND) << "Could not find a suitable render format";
-            return std::nullopt;
-        }
-        m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), bufferSize, format, modifiers);
         if (!m_swapchain) {
+            qCWarning(KWIN_WAYLAND_BACKEND) << "Could not find a suitable cursor format";
             return std::nullopt;
         }
     }

     m_buffer = m_swapchain->acquire();
-    if (!m_buffer) {
+    if (!m_buffer || !m_backend->acquireBuffer(m_buffer->buffer())) {
         return std::nullopt;
     }

@@ -201,12 +201,16 @@
     // Flush rendering commands to the dmabuf.
     glFlush();

+    EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
+    if (!m_backend->releaseBuffer(m_buffer->buffer(), releaseFence.fileDescriptor())) {
+        return false;
+    }
+
     wl_buffer *buffer = m_backend->backend()->importBuffer(m_buffer->buffer());
     Q_ASSERT(buffer);

     static_cast<WaylandOutput *>(m_output)->cursor()->update(buffer, scale(), hotspot().toPoint());

-    EGLNativeFence releaseFence{m_backend->eglDisplayObject()};
     m_swapchain->release(m_buffer, releaseFence.takeFileDescriptor());
     return true;
 }
@@ -248,6 +252,21 @@
     return m_backend->drmDevice();
 }

+GraphicsBufferAllocator *WaylandEglBackend::graphicsBufferAllocator() const
+{
+    return m_allocator ? m_allocator.get() : AbstractEglBackend::graphicsBufferAllocator();
+}
+
+bool WaylandEglBackend::acquireBuffer(GraphicsBuffer *buffer)
+{
+    return eglDisplayObject()->acquireDmaBuf(buffer, true);
+}
+
+bool WaylandEglBackend::releaseBuffer(GraphicsBuffer *buffer, const FileDescriptor &fence)
+{
+    return eglDisplayObject()->releaseDmaBuf(buffer, fence, true);
+}
+
 void WaylandEglBackend::cleanupSurfaces()
 {
     m_outputs.clear();
@@ -267,14 +286,19 @@
     initClientExtensions();

     if (!m_backend->sceneEglDisplayObject()) {
-        for (const QByteArray &extension : {QByteArrayLiteral("EGL_EXT_platform_base"), QByteArrayLiteral("EGL_KHR_platform_gbm")}) {
+        const auto platform = drmDevice() ? QByteArrayLiteral("EGL_KHR_platform_gbm") : QByteArrayLiteral("EGL_KHR_platform_wayland");
+        for (const QByteArray &extension : {QByteArrayLiteral("EGL_EXT_platform_base"), platform}) {
             if (!hasClientExtension(extension)) {
                 qCWarning(KWIN_WAYLAND_BACKEND) << extension << "client extension is not supported by the platform";
                 return false;
             }
         }

-        m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, m_backend->drmDevice()->gbmDevice(), nullptr)));
+        if (drmDevice()) {
+            m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, drmDevice()->gbmDevice(), nullptr)));
+        } else {
+            m_backend->setEglDisplay(EglDisplay::create(eglGetPlatformDisplayEXT(EGL_PLATFORM_WAYLAND_KHR, m_backend->display()->nativeDisplay(), nullptr)));
+        }
     }

     const auto display = m_backend->sceneEglDisplayObject();
@@ -282,6 +306,22 @@
         return false;
     }
     setEglDisplay(display);
+    if (!drmDevice()) {
+        for (const auto &extension : {QByteArrayLiteral("EGL_EXT_image_dma_buf_import_modifiers"),
+                                     QByteArrayLiteral("EGL_ANDROID_native_fence_sync"),
+                                     QByteArrayLiteral("EGL_KHR_wait_sync")}) {
+            if (!display->hasExtension(extension)) {
+                qCWarning(KWIN_WAYLAND_BACKEND) << "DMA heap rendering requires" << extension;
+                return false;
+            }
+        }
+        auto allocator = std::make_unique<DmaHeapGraphicsBufferAllocator>();
+        if (!allocator->isValid()) {
+            return false;
+        }
+        m_allocator = std::move(allocator);
+        display->setDmaBufSyncRequired(true);
+    }
     return true;
 }

@@ -296,6 +336,20 @@
         return;
     }

+    if (m_allocator) {
+        // Validate allocation, EGL import and both synchronization directions before
+        // advertising GPU composition or DMA-BUF to nested clients.
+        const auto formats = m_backend->display()->linuxDmabuf()->formats();
+        auto probe = EglSwapchain::create(m_allocator.get(), openglContext(), QSize(64, 64),
+                                          DRM_FORMAT_XRGB8888, formats.value(DRM_FORMAT_XRGB8888));
+        auto slot = probe ? probe->acquire() : nullptr;
+        EGLNativeFence fence{eglDisplayObject()};
+        if (!slot || !acquireBuffer(slot->buffer()) || !releaseBuffer(slot->buffer(), fence.fileDescriptor())) {
+            setFailed("DMA heap buffer import or synchronization is unavailable");
+            return;
+        }
+    }
+
     initWayland();
 }

--- a/src/compositor_wayland.cpp
+++ b/src/compositor_wayland.cpp
@@ -120,7 +120,7 @@
     return true;
 }

-void WaylandCompositor::createRenderer()
+bool WaylandCompositor::createRenderer()
 {
     // If compositing has been restarted, try to use the last used compositing type.
     const QList<CompositingType> availableCompositors = kwinApp()->outputBackend()->supportedCompositors();
@@ -161,9 +161,16 @@
             break;
         } else if (qEnvironmentVariableIsSet("KWIN_COMPOSE")) {
             qCCritical(KWIN_CORE) << "Could not fulfill the requested compositing mode in KWIN_COMPOSE:" << type << ". Exiting.";
-            qApp->quit();
+            break;
         }
     }
+    if (m_backend) {
+        return true;
+    }
+    qCCritical(KWIN_CORE) << "The used windowing system requires compositing";
+    // Startup may run before exec(); queue the failure for the event loop.
+    QMetaObject::invokeMethod(qApp, [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
+    return false;
 }

 void WaylandCompositor::createScene()
@@ -191,16 +198,8 @@
     Q_EMIT aboutToToggleCompositing();
     m_state = State::Starting;

-    if (!m_backend) {
-        createRenderer();
-    }
-
-    if (!m_backend) {
+    if (!m_backend && !createRenderer()) {
         m_state = State::Off;
-
-        qCCritical(KWIN_CORE) << "The used windowing system requires compositing";
-        qCCritical(KWIN_CORE) << "We are going to quit KWin now as it is broken";
-        qApp->quit();
         return;
     }

--- a/src/compositor_wayland.h
+++ b/src/compositor_wayland.h
@@ -23,7 +23,7 @@
     static WaylandCompositor *create(QObject *parent = nullptr);
     ~WaylandCompositor() override;

-    void createRenderer();
+    bool createRenderer();

     void start() override;
     void stop() override;
--- a/src/main_wayland.cpp
+++ b/src/main_wayland.cpp
@@ -142,7 +142,9 @@
     createTabletModeManager();

     auto compositor = WaylandCompositor::create();
-    compositor->createRenderer();
+    if (!compositor->createRenderer()) {
+        return;
+    }
     createWorkspace();
     createColorManager();
     createPlugins();
PATCH
            ;;
    esac
    kwin_pointer_patch
}

build_support() {
    cat <<'BUILD_SUPPORT'
build_phase=${MAGICDESK_BUILD_PHASE:-all}
case "$build_phase" in
    all|dependencies|build) ;;
    *) echo 'Invalid source-build phase.' >&2; exit 1 ;;
esac
BUILD_SUPPORT
}

weston_setup() {
    distribution_support
    build_support
    cat <<'WESTON'
jobs=$1
recipe=$2
[ -f /usr/local/share/applications/magicdesk-weston.desktop ] || exit 0
# 13.0.0 dereferences popup shell data during focus handoff. Use the official
# maintenance release, not a compositor fork or a browser-specific workaround.
case "$(/usr/bin/weston --version)" in
    'weston 13.0.'[0-3]) ;;
    *) rm -f /etc/magicdesk/weston.sh; exit 0 ;;
esac
version=13.0.4
archive_sha=66252c9c5bbc45ab402e2518b76e6d824f09ebc1d0040ee2f51b096a1bc585b1
prefix=/opt/magicdesk/weston/$version-$recipe
work=/var/cache/magicdesk/weston/$version-$recipe
mkdir -p /var/lib/magicdesk /etc/magicdesk "$work"
exec 9>/var/lib/magicdesk/installer.lock
flock -n 9 || { echo 'Another graphics setup is running in this environment.' >&2; exit 1; }
candidate=/etc/magicdesk/weston.sh.new
trap 'status=$?; rm -f "$candidate"; package_cleanup; exit "$status"' 0
trap 'exit 130' INT
trap 'exit 143' TERM
if [ ! -f "$prefix/.complete" ]; then
    if [ "$build_phase" != build ]; then
        case "$family" in
            apt) packages build-essential meson ninja-build pkg-config curl xz-utils \
                libwayland-dev wayland-protocols libxkbcommon-dev libpixman-1-dev \
                libinput-dev libevdev-dev libudev-dev libdrm-dev libcairo2-dev \
                libpango1.0-dev libpng-dev libegl-dev libgles-dev libxcb-composite0-dev \
                libxcb-shape0-dev libxcb-xfixes0-dev libxcb-cursor-dev libxcursor-dev ;;
            apk) packages build-base meson ninja pkgconf curl xz wayland-dev wayland-protocols \
                libxkbcommon-dev pixman-dev libinput-dev libevdev-dev eudev-dev libdrm-dev \
                cairo-dev pango-dev libpng-dev mesa-dev libxcb-dev xcb-util-cursor-dev libxcursor-dev ;;
            dnf) packages gcc gcc-c++ meson ninja-build pkgconf-pkg-config curl xz \
                wayland-devel wayland-protocols-devel libxkbcommon-devel pixman-devel \
                libinput-devel libevdev-devel systemd-devel libdrm-devel cairo-devel \
                pango-devel libpng-devel libglvnd-devel libxcb-devel xcb-util-cursor-devel libXcursor-devel ;;
            pacman|recovery) packages base-devel meson ninja pkgconf curl xz wayland wayland-protocols \
                libxkbcommon pixman libinput libevdev libdrm cairo pango libpng libglvnd libxcb xcb-util-cursor libxcursor ;;
            zypper) packages gcc gcc-c++ make meson ninja pkgconf-pkg-config curl xz \
                wayland-devel wayland-protocols-devel libxkbcommon-devel pixman-devel \
                libinput-devel libevdev-devel libudev-devel libdrm-devel cairo-devel \
                pango-devel libpng-devel libglvnd-devel libxcb-devel xcb-util-cursor-devel libXcursor-devel ;;
            xbps) packages base-devel meson ninja pkg-config curl xz wayland-devel wayland-protocols \
                libxkbcommon-devel pixman-devel libinput-devel libevdev-devel eudev-libudev-devel \
                libdrm-devel cairo-devel pango-devel libpng-devel libglvnd-devel libxcb-devel xcb-util-cursor-devel libXcursor-devel ;;
        esac
    fi
    [ "$build_phase" != dependencies ] || exit 0
    mkdir -p /var/cache/magicdesk/weston/downloads
    archive=/var/cache/magicdesk/weston/downloads/weston-$version.tar.xz
    if ! printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum -c - >/dev/null 2>&1; then
        # EVENT_WAIT: download progress; a stalled connection fails and curl retries it.
        curl --fail --location --proto '=https' --proto-redir '=https' \
            --connect-timeout 30 --speed-limit 1 --speed-time 60 --retry 3 --output "$archive.part" \
            "https://gitlab.freedesktop.org/wayland/weston/-/releases/$version/downloads/weston-$version.tar.xz"
        printf '%s  %s\n' "$archive_sha" "$archive.part" | sha256sum -c -
        mv "$archive.part" "$archive"
    fi
    source=$work/weston-$version
    if [ ! -f "$source/.extracted" ]; then
        rm -rf "$source" "$work/build" "$work/unpack"
        mkdir "$work/unpack"
        tar --no-same-owner -xJf "$archive" -C "$work/unpack"
        touch "$work/unpack/weston-$version/.extracted"
        mv "$work/unpack/weston-$version" "$source"
        rmdir "$work/unpack"
    fi
    set -- --prefix="$prefix" --libdir=lib --buildtype=release --wrap-mode=nodownload \
        -Dbackend-default=wayland -Dbackend-drm=false -Dbackend-drm-screencast-vaapi=false \
        -Dbackend-pipewire=false -Dbackend-rdp=false -Dbackend-vnc=false -Dbackend-x11=false \
        -Dbackend-wayland=true -Dbackend-headless=true -Drenderer-gl=true -Dxwayland=true \
        -Dsystemd=false -Dscreenshare=false -Dremoting=false -Dpipewire=false \
        -Dshell-fullscreen=false -Dshell-ivi=false -Dshell-kiosk=false \
        -Dcolor-management-lcms=false -Dimage-jpeg=false -Dimage-webp=false \
        -Ddemo-clients=false -Dsimple-clients= -Dtools=terminal -Dwcap-decode=false -Dtest-junit-xml=false
    if [ -f "$work/build/build.ninja" ]; then
        meson setup --reconfigure "$work/build" "$source" "$@" </dev/null
    else
        meson setup "$work/build" "$source" "$@" </dev/null
    fi
    ninja -C "$work/build" -j "$jobs" </dev/null
    rm -rf "$work/stage"
    meson install -C "$work/build" --no-rebuild --destdir "$work/stage" </dev/null
    [ -x "$work/stage$prefix/bin/weston" ] || exit 1
    mkdir -p /opt/magicdesk/weston
    [ ! -e "$prefix" ] || { echo "Incomplete prefix exists: $prefix" >&2; exit 1; }
    printf '%s\n' "$recipe" > "$work/stage$prefix/.complete"
    mv "$work/stage$prefix" "$prefix"
fi
[ "$build_phase" != dependencies ] || exit 0
[ "$(cat "$prefix/.complete")" = "$recipe" ] || { echo 'Weston build identity mismatch.' >&2; exit 1; }
printf "weston_prefix='%s'\n" "$prefix" > "$candidate"
mv "$candidate" /etc/magicdesk/weston.sh
WESTON
}

plasma_setup() {
    case "$distro" in steamos) kwin_version=6.2.5 ;; *) kwin_version=6.3.6 ;; esac
    distribution_support
    build_support
    cat <<'KWIN_HEAD'
jobs=$1
recipe=$2
[ -f /usr/local/share/applications/magicdesk-plasma-wayland.desktop ] || exit 0
[ ! -r /etc/profile.d/magicdesk-graphics.sh ] || . /etc/profile.d/magicdesk-graphics.sh
[ -n "${MAGICDESK_MESA_PREFIX:-}" ] || exit 0
case "$ID:$VERSION_ID" in
    debian:13)
        version=6.3.6
        archive_sha=27f2205f06d58f1d1f480d2a94ae24022c2f95b9c1fdc5a549f8e143713fce12 ;;
    steamos:0.3.0)
        version=6.2.5
        archive_sha=5cc450a6e41105c8c49929b72550b331237f96aafb294690f4707bdc5f776848 ;;
    *) echo 'The pinned KWin recipe requires Debian 13 or SteamOS 0.3.0.' >&2; exit 1 ;;
esac
prefix=/opt/magicdesk/kwin/$version-$recipe
work=/var/cache/magicdesk/kwin/$version-$recipe
mkdir -p /var/lib/magicdesk /etc/magicdesk "$work"
exec 9>/var/lib/magicdesk/installer.lock
flock -n 9 || { echo 'Another graphics setup is running in this environment.' >&2; exit 1; }
candidate=/etc/magicdesk/plasma-kwin.sh.new
trap 'status=$?; rm -f "$candidate"; package_cleanup; exit "$status"' 0
trap 'exit 130' INT
trap 'exit 143' TERM
cat > "$work/dmaheap.patch" <<'KWIN_PATCH'
KWIN_HEAD
    kwin_patch "$kwin_version"
    cat <<'KWIN_BODY'
KWIN_PATCH
if [ ! -f "$prefix/.complete" ]; then
    if [ "$build_phase" != build ]; then
    case "$family" in
    apt)
    packages build-essential cmake ninja-build extra-cmake-modules pkgconf patch curl xz-utils \
        gettext hwdata breeze-dev kscreenlocker-dev kwayland-dev \
        libcanberra-dev libcap-dev libdisplay-info-dev libdrm-dev libegl-dev libeis-dev \
        libepoxy-dev libfontconfig-dev libfreetype-dev libgbm-dev libice-dev libinput-dev \
        libkdecorations3-dev libkf6auth-dev libkf6colorscheme-dev libkf6config-dev \
        libkf6configwidgets-dev libkf6coreaddons-dev libkf6crash-dev libkf6dbusaddons-dev \
        libkf6declarative-dev libkf6doctools-dev libkf6globalaccel-dev libkf6guiaddons-dev \
        libkf6i18n-dev libkf6idletime-dev libkf6itemviews-dev libkf6kcmutils-dev \
        libkf6newstuff-dev libkf6notifications-dev libkf6package-dev libkf6runner-dev \
        libkf6service-dev libkf6svg-dev libkf6widgetsaddons-dev libkf6windowsystem-dev \
        libkf6xmlgui-dev libkglobalacceld-dev libkirigami-dev liblcms2-dev libpipewire-0.3-dev \
        libplasma-dev libplasmaactivities-dev libqaccessibilityclient-qt6-dev libsm-dev \
        libsystemd-dev libudev-dev libwayland-dev libx11-xcb-dev libxcb-composite0-dev \
        libxcb-cursor-dev libxcb-damage0-dev libxcb-dri3-dev libxcb-glx0-dev \
        libxcb-icccm4-dev libxcb-image0-dev libxcb-keysyms1-dev libxcb-present-dev \
        libxcb-randr0-dev libxcb-render0-dev libxcb-shape0-dev libxcb-shm0-dev \
        libxcb-sync-dev libxcb-util-dev libxcb-xfixes0-dev libxcb-xinerama0-dev \
        libxcb-xinput-dev libxcb-xkb-dev libxcb-xtest0-dev libxcb1-dev libxcursor-dev \
        libxcvt-dev libxkbcommon-dev libxkbcommon-x11-dev plasma-wayland-protocols \
        qt6-5compat-dev qt6-base-dev qt6-base-private-dev qt6-declarative-dev \
        qt6-declarative-private-dev qt6-sensors-dev qt6-svg-dev qt6-tools-dev \
        qt6-wayland-dev qt6-wayland-dev-tools wayland-protocols xwayland ;;
    recovery)
        # Arch-style packages already include headers; retain the image's Plasma ABI.
        packages base-devel cmake ninja extra-cmake-modules pkgconf patch curl xz \
            gettext wayland-protocols plasma-wayland-protocols ;;
    esac
    fi
    [ "$build_phase" != dependencies ] || exit 0
    mkdir -p /var/cache/magicdesk/kwin/downloads
    archive=/var/cache/magicdesk/kwin/downloads/kwin-$version.tar.xz
    if ! printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum -c - >/dev/null 2>&1; then
        # EVENT_WAIT: download progress; a stalled connection fails and curl retries it.
        curl --fail --location --proto '=https' --proto-redir '=https' \
            --connect-timeout 30 --speed-limit 1 --speed-time 60 --retry 3 --output "$archive.part" \
            "https://download.kde.org/stable/plasma/$version/kwin-$version.tar.xz"
        printf '%s  %s\n' "$archive_sha" "$archive.part" | sha256sum -c -
        mv "$archive.part" "$archive"
    fi
    source=$work/source
    if [ ! -f "$source/.patched" ]; then
        rm -rf "$source" "$work/build" "$work/unpack"
        mkdir "$work/unpack"
        tar --no-same-owner -xJf "$archive" -C "$work/unpack"
        patch --batch --forward --fuzz=0 -p1 -d "$work/unpack/kwin-$version" < "$work/dmaheap.patch"
        touch "$work/unpack/kwin-$version/.patched"
        mv "$work/unpack/kwin-$version" "$source"
        rmdir "$work/unpack"
    fi
    cmake -S "$source" -B "$work/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_AUTOGEN_PARALLEL="$jobs" \
        -DCMAKE_INSTALL_PREFIX="$prefix" -DKDE_INSTALL_USE_QT_SYS_PATHS=OFF \
        -DKDE_INSTALL_LIBDIR=lib -DKDE_INSTALL_QTPLUGINDIR=lib/qt6/plugins \
        -DKDE_INSTALL_QMLDIR=lib/qt6/qml -DCMAKE_INSTALL_RPATH="$prefix/lib" \
        -DBUILD_TESTING=OFF -DKWIN_BUILD_KCMS=OFF -DKWIN_BUILD_X11_BACKEND=OFF </dev/null
    cmake --build "$work/build" --parallel "$jobs" </dev/null
    rm -rf "$work/stage"
    DESTDIR="$work/stage" cmake --install "$work/build" </dev/null
    [ -x "$work/stage$prefix/bin/kwin_wayland" ] || exit 1
    mkdir -p /opt/magicdesk/kwin
    [ ! -e "$prefix" ] || { echo "Incomplete prefix exists: $prefix" >&2; exit 1; }
    printf '%s\n' "$recipe" > "$work/stage$prefix/.complete"
    mv "$work/stage$prefix" "$prefix"
fi
[ "$build_phase" != dependencies ] || exit 0
[ "$(cat "$prefix/.complete")" = "$recipe" ] || { echo 'KWin build identity mismatch.' >&2; exit 1; }
printf "kwin_prefix='%s'\n" "$prefix" > "$candidate"
mv "$candidate" /etc/magicdesk/plasma-kwin.sh
KWIN_BODY
}

mesa_options() {
    cat <<'MESA_OPTIONS'
-Dauto_features=disabled
-Dplatforms=x11,wayland
-Dllvm=disabled
-Dxmlconfig=disabled
-Dbuild-tests=false
-Dgallium-rusticl=false
-Dvulkan-drivers=freedreno
-Dfreedreno-kmds=msm,kgsl
-Dgallium-drivers=zink,softpipe
-Dopengl=true
-Dgles1=enabled
-Dgles2=enabled
-Dglx=dri
-Degl=enabled
-Dgbm=enabled
-Dglvnd=enabled
-Dgallium-va=disabled
MESA_OPTIONS
}

mesa_source() {
    printf '%s\n' 'version=26.2.3' 'archive_sha=1628058a8d2c0615975de5a15ab7bbb9638c50000b5bed9456ff423ea034a81f'
}

mesa_probe_source() {
    cat <<'MESA_PROBE'
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
/* Resolve only stable C symbols from the target runtime. Linking the probe
 * against the host libc would reject glibc runtimes when built on musl. */
__attribute__((constructor)) static void probe(void) {
    char *paths = getenv("MD_MESA_PROBE_LIBRARIES");
    if (!paths || !*paths) _exit(2);
    while (*paths) {
        char *end = strchr(paths, '\n');
        if (end) *end = 0;
        void *library = dlopen(paths, RTLD_NOW | RTLD_LOCAL);
        if (!library) { dprintf(2, "%s: %s\n", paths, dlerror()); _exit(1); }
        dlclose(library);
        if (!end) break;
        paths = end + 1;
    }
    static const char ok[] = "MD_MESA_PROBE_OK\n";
    if (write(1, ok, sizeof(ok) - 1) != sizeof(ok) - 1) _exit(2);
    _exit(0);
}
MESA_PROBE
}

flatpak_gl_helper() {
    cat <<'FLATPAK_GL_PY'
#!/usr/bin/env python3
"""Publish the selected Mesa build through Flatpak's local GL.host extension."""
import argparse
import configparser
import ctypes
import errno
import fcntl
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile

CONFIG = Path('/etc/magicdesk/mesa-prefix')
BUILD_CONFIG = Path('/etc/magicdesk/mesa-build.json')
PROBE = Path('/usr/local/libexec/magicdesk-mesa-probe')
EXTENSION = 'org.freedesktop.Platform.GL.host'
FORMAT = '5'


class MesaABIError(ValueError):
    pass


def build_identity(prefix):
    marker = prefix / '.complete'
    if marker.is_file():
        identity = marker.read_text().strip()
        if identity:
            return identity
    # Nix owns publication and immutability; do not modify its store to add a stamp.
    resolved = prefix.resolve()
    if resolved.parent == Path('/nix/store') and re.fullmatch(r'[0-9a-z]{32}-.+', resolved.name):
        return str(resolved)
    raise ValueError('Incomplete Mesa build: ' + str(prefix))


def public_directory(path):
    try:
        path.mkdir()
    except FileExistsError:
        if not path.is_dir() or path.is_symlink():
            raise ValueError('Not a local Flatpak directory: ' + str(path))
    else:
        path.chmod(0o755)


def prepare_environment(base):
    # Host absolute driver paths are not valid inside a runtime. Use Flatpak's
    # normal loader discovery, without overriding explicit per-app settings.
    path = base / 'overrides/global'
    data = configparser.ConfigParser(interpolation=None, strict=False)
    data.optionxform = str
    original = path.read_text() if path.exists() else ''
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644
    data.read_string(original)
    unset = data.get('Context', 'unset-environment', fallback='').split(';')
    unset = [key for key in unset if key]
    changed = False
    for key in ('VK_DRIVER_FILES', 'VK_ICD_FILENAMES',
                '__EGL_VENDOR_LIBRARY_FILENAMES', 'LIBGL_DRIVERS_PATH', 'GBM_BACKENDS_PATH'):
        if key not in unset and not data.has_option('Environment', key):
            unset.append(key)
            changed = True
    if not changed:
        return
    if not data.has_section('Context'):
        data.add_section('Context')
    data.set('Context', 'unset-environment', ';'.join(unset) + ';')
    output = io.StringIO()
    data.write(output, space_around_delimiters=False)
    public_directory(path.parent)
    fd, name = tempfile.mkstemp(prefix='.magicdesk-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(output.getvalue())
            os.fchmod(stream.fileno(), mode)
        if (path.read_text() if path.exists() else '') != original:
            raise ValueError('Flatpak overrides changed during GPU preparation; retry')
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def runtime_groups(base):
    groups = {}
    for metadata in sorted((base / 'runtime').glob('*/aarch64/*/active/metadata')):
        data = configparser.ConfigParser(interpolation=None, strict=False)
        data.read(metadata)
        section = 'Extension org.freedesktop.Platform.GL'
        if not data.has_section(section):
            continue
        layout = data.get(section, 'directory', fallback='')
        if not layout.startswith('lib/') or '..' in layout.split('/'):
            raise ValueError('Invalid GL extension directory: ' + layout)
        versions = data.get(section, 'versions', fallback=data.get(section, 'version',
                           fallback=metadata.parents[1].name)).split(';')
        # 1.4 is the unversioned NVIDIA ABI, not a Mesa compatibility promise.
        branch = next((v for v in versions if re.fullmatch(r'\d{2}\.\d{2}', v)), None)
        if branch and data.getboolean(section, 'subdirectories', fallback=False):
            groups.setdefault(branch, []).append((metadata.resolve(), layout))
    return groups


def relocate_json(source, target, root):
    data = json.loads(source.read_text())
    name = Path(data['ICD']['library_path']).name
    if not (target.parents[2] / 'lib' / name).is_file():
        raise ValueError('Missing Mesa ICD library: ' + name)
    data['ICD']['library_path'] = root + '/lib/' + name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data) + '\n')


def stage_bundle(prefix, directory, layout):
    shutil.copytree(prefix / 'lib', directory / 'lib', symlinks=True)
    # Store outputs can be read-only. The unpublished copy must remain removable
    # on admission failure; never change permissions in the source prefix.
    for path in [directory / 'lib', *(directory / 'lib').rglob('*')]:
        if path.is_dir() and not path.is_symlink():
            path.chmod(0o755)
    # Development files are not part of a runtime extension.
    shutil.rmtree(directory / 'lib/pkgconfig', ignore_errors=True)
    root = '/usr/' + layout + '/host'
    relocate_json(prefix / 'share/vulkan/icd.d/freedreno_icd.aarch64.json',
                  directory / 'vulkan/icd.d/magicdesk.json', root)
    relocate_json(prefix / 'share/glvnd/egl_vendor.d/50_mesa.json',
                  directory / 'glvnd/egl_vendor.d/50_magicdesk.json', root)


def verify_runtime(metadata, bundle):
    files = metadata.parent / 'files'
    loaders = list(files.glob('lib/ld-linux-aarch64.so.1'))
    loaders += list(files.glob('lib/*/ld-linux-aarch64.so.1'))
    if not loaders:
        raise ValueError('No ARM64 glibc loader in ' + str(metadata))
    loader = '/usr/' + str(loaders[0].relative_to(files))
    libraries = [str(p.relative_to(bundle)) for p in sorted((bundle / 'lib').rglob('*.so*'))
                 if p.is_file() and not p.is_symlink()]
    if not libraries:
        raise ValueError('Empty Mesa library set')
    command = ['bwrap', '--unshare-pid', '--unshare-net', '--unshare-ipc',
               '--ro-bind', str(files), '/usr', '--symlink', 'usr/lib', '/lib',
               '--symlink', 'usr/bin', '/bin', '--dev', '/dev', '--proc', '/proc',
               '--ro-bind', str(bundle), '/mdgl', '--ro-bind', str(PROBE), '/probe',
               '--chdir', '/', '--', loader, '--library-path',
               '/mdgl/lib:/usr/lib/aarch64-linux-gnu:/usr/lib', '--preload', '/probe', '/usr/bin/true']
    environment = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C',
                   'MD_MESA_PROBE_LIBRARIES': '\n'.join('/mdgl/' + name for name in libraries)}
    # EVENT_WAIT: the runtime loader/dlopen probe exits; timeout rejects admission.
    result = subprocess.run(command, env=environment,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=30)
    if result.returncode:
        # Only a completed dlopen probe can request an SDK rebuild. A broken
        # sandbox/loader or timed-out process is not evidence of a driver ABI mismatch.
        error = MesaABIError if result.returncode == 1 and result.stdout.startswith('/mdgl/') else ValueError
        raise error('Mesa cannot load in ' + str(metadata) + ':\n' + result.stdout)
    if not result.stdout.endswith('MD_MESA_PROBE_OK\n'):
        raise ValueError('Runtime loader did not execute the Mesa ABI probe:\n' + result.stdout)


def sdk_build(base, prefix, metadata, cache):
    if not BUILD_CONFIG.is_file():
        raise ValueError('This Mesa installation has no retained SDK build sources; '
                         'select a Mesa build compatible with the installed Flatpak runtime')
    config = json.loads(BUILD_CONFIG.read_text())
    if config['identity'] != build_identity(prefix):
        raise ValueError('Mesa source selection does not match its build configuration')
    source = Path(config['source'])
    if not source.is_absolute() or not (source / '.patched').is_file():
        raise ValueError('Mesa sources are missing; resume the Turnip recipe first')
    data = configparser.ConfigParser(interpolation=None, strict=False)
    data.read(metadata)
    runtime = data.get('Runtime', 'runtime')
    sdk = data.get('Runtime', 'sdk')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/aarch64/[A-Za-z0-9_.-]+', sdk):
        raise ValueError('Invalid Flatpak SDK reference: ' + sdk)
    user_base = Path(os.environ.get('XDG_DATA_HOME', str(Path.home() / '.local/share'))) / 'flatpak'
    if base == user_base.resolve():
        installation = '--user'
    elif base == Path('/var/lib/flatpak') and os.geteuid() == 0:
        installation = '--system'
    else:
        raise ValueError('Run Mesa preparation as the owner of this Flatpak installation: ' + str(base))

    def info(ref, field):
        return subprocess.run(['flatpak', installation, 'info', field, ref], text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)

    location = info(sdk, '--show-location')
    if location.returncode:
        origin = info(runtime, '--show-origin')
        origin.check_returncode()
        # This helper also runs as an installation trigger. Starting another
        # Flatpak transaction here would recurse into the publisher/installer locks.
        raise ValueError('Matching Mesa SDK is required. Run: flatpak ' + installation +
                         ' install ' + origin.stdout.strip() + ' ' + sdk +
                         '; then magicdesk-flatpak-gl --build-sdk ' + shlex.quote(str(base)))
    sdk_path = Path(location.stdout.strip()).resolve()
    if not (sdk_path / 'metadata').is_file():
        raise ValueError('Missing SDK deployment: ' + str(sdk_path))
    build_config = {key: value for key, value in config.items() if key != 'jobs'}
    identity = hashlib.sha256(json.dumps([build_config, str(sdk_path)], sort_keys=True).encode()).hexdigest()
    work = cache / ('sdk-' + identity)
    work.mkdir(exist_ok=True)
    output = work / 'prefix'
    if (output / '.complete').is_file():
        if (output / '.complete').read_text().strip() != identity:
            raise ValueError('Mesa SDK build identity mismatch')
        return output
    app = work / 'app'
    if not (app / 'metadata').is_file():
        name, arch, branch = sdk.split('/')
        subprocess.run(['flatpak', 'build-init', '--arch=' + arch, str(app),
                        'io.github.mekhontsev.MesaBuild', name, runtime.split('/')[0], branch], check=True)
    # The recipe already installs these build tools. Only their portable Python
    # modules cross the SDK boundary, never host extension modules or libc.
    modules = work / 'python'
    modules.mkdir(exist_ok=True)
    for name in ('yaml', 'mako', 'markupsafe', 'packaging', 'ply'):
        module = importlib.import_module(name)
        root = Path(module.__file__).parent
        for path in root.rglob('*.py'):
            target = modules / name / path.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    command = ['flatpak', 'build', '--filesystem=' + str(source) + ':ro',
               '--filesystem=' + str(work), '--env=PYTHONPATH=' + str(modules), str(app)]
    build = work / 'build'
    configure = ['meson', 'setup'] + (['--reconfigure'] if (build / 'build.ninja').is_file() else [])
    configure += [str(build), str(source), '--prefix=' + str(output), '--libdir=lib',
                  '--buildtype=release', '--wrap-mode=nodownload',
                  '-Dc_link_args=-Wl,-rpath,$ORIGIN:$ORIGIN/..',
                  '-Dcpp_link_args=-Wl,-rpath,$ORIGIN:$ORIGIN/..', '-Dgbm-backends-path=$ORIGIN/gbm']
    print('Building Mesa for Flatpak SDK ' + sdk + '; cached objects are retained on failure', flush=True)
    subprocess.run(command + configure + config['options'], check=True)
    subprocess.run(command + ['ninja', '-C', str(build), '-j', str(config['jobs'])], check=True)
    stage = work / 'stage'
    shutil.rmtree(stage, ignore_errors=True)
    subprocess.run(command + ['meson', 'install', '-C', str(build), '--no-rebuild',
                              '--destdir', str(stage)], check=True)
    built = stage / str(output).lstrip('/')
    (built / '.complete').write_text(identity + '\n')
    os.rename(built, output)
    return output


def owned(link):
    return not link.is_symlink() and (link / '.magicdesk-gl').is_file()


def publish(candidate, destination):
    if not destination.exists():
        os.rename(candidate, destination)
        return False
    # Exchange whole directories: relative symlinks inside an extension remain
    # valid after Flatpak mounts it; live sandboxes retain the old directory.
    rename = getattr(ctypes.CDLL(None, use_errno=True), 'renameat2', None)
    if rename is None:
        raise OSError(errno.ENOSYS, 'libc does not provide renameat2 for atomic GL replacement')
    rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(-100, os.fsencode(candidate), -100, os.fsencode(destination), 2):
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))
    return True


def retire(link, bundles):
    if owned(link):
        unused = Path(tempfile.mkdtemp(prefix='retired-', dir=bundles))
        os.replace(link, unused)


def write_receipt(directory, receipt):
    fd, name = tempfile.mkstemp(prefix='.receipt-', dir=directory)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(json.dumps(receipt) + '\n')
            os.fchmod(stream.fileno(), 0o644)
        os.replace(name, directory / '.magicdesk-gl')
    finally:
        Path(name).unlink(missing_ok=True)


def extension_directory(base):
    destination = base
    for name in ('extension', EXTENSION, 'aarch64'):
        destination = destination / name
        public_directory(destination)
    return destination


def prepare(base, prefix, build_sdk=False):
    base = base.resolve()
    if not (base / 'runtime').is_dir():
        return
    bundles = base / '.magicdesk-gl'
    bundles.mkdir(exist_ok=True)
    with (bundles / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        destination = base / 'extension' / EXTENSION / 'aarch64'
        groups = runtime_groups(base) if prefix else {}
        failed = []
        if prefix:
            identity = build_identity(prefix)
        for branch, runtimes in groups.items():
            link = destination / branch
            if os.path.lexists(link) and not owned(link):
                failed.append('Not replacing an independently installed GL.host: ' + str(link))
                continue
            layouts = {layout for _, layout in runtimes}
            if len(layouts) != 1:
                failed.append('Conflicting GL paths for runtime branch ' + branch)
                retire(link, bundles)
                continue
            layout = next(iter(layouts))
            receipt = [FORMAT, str(prefix), identity, layout,
                       [str(path) for path, _ in runtimes]]
            temporary = None
            try:
                previous = json.loads((link / '.magicdesk-gl').read_text()) if owned(link) else None
                if previous == receipt:
                    continue
                if isinstance(previous, list) and previous[:4] == receipt[:4]:
                    try:
                        for metadata, _ in runtimes:
                            verify_runtime(metadata, link)
                    except MesaABIError:
                        pass
                    else:
                        write_receipt(link, receipt)
                        continue
                temporary = Path(tempfile.mkdtemp(prefix='.prepare-', dir=bundles))
                stage_bundle(prefix, temporary, layout)
                try:
                    for metadata, _ in runtimes:
                        verify_runtime(metadata, temporary)
                except MesaABIError as error:
                    if not build_sdk:
                        raise MesaABIError(str(error) + '\nNo Mesa compilation was started. '
                                           'To explicitly build a separate Flatpak driver, run: '
                                           'magicdesk-flatpak-gl --build-sdk ' + shlex.quote(str(base))) from error
                    built = sdk_build(base, prefix, runtimes[0][0], bundles)
                    shutil.rmtree(temporary)
                    temporary = Path(tempfile.mkdtemp(prefix='.prepare-', dir=bundles))
                    stage_bundle(built, temporary, layout)
                    for metadata, _ in runtimes:
                        verify_runtime(metadata, temporary)
                    (temporary / '.magicdesk-build').write_text(json.dumps({
                        'prefix': str(built), 'identity': (built / '.complete').read_text().strip()}) + '\n')
                write_receipt(temporary, receipt)
                temporary.chmod(0o755)
                extension_directory(base)
                if publish(temporary, link):
                    previous_bundle, temporary = temporary, None
                    os.rename(previous_bundle, previous_bundle.with_name(
                        'retired-' + previous_bundle.name.removeprefix('.prepare-')))
                temporary = None
                print('Flatpak Mesa ready: ' + branch)
            except (OSError, ValueError, KeyError, configparser.Error, subprocess.SubprocessError) as error:
                # An old build is not silently reused after ABI admission failed.
                retire(link, bundles)
                failed.append(str(error))
            finally:
                if temporary:
                    shutil.rmtree(temporary)
        for link in destination.glob('*'):
            if link.name not in groups and owned(link):
                retire(link, bundles)
        if any(owned(destination / branch) for branch in groups):
            prepare_environment(base)
        # Old immutable bundles can still be mounted by running applications.
        # Keep them; never mutate a library beneath an existing sandbox.
        if failed:
            raise ValueError('\n'.join(failed))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-sdk', action='store_true',
                        help='allow a separate Mesa build if the host driver fails runtime ABI admission')
    parser.add_argument('installations', type=Path, nargs='*', help='Flatpak installation directories')
    args = parser.parse_args()
    prefix = Path(CONFIG.read_text().strip()) if CONFIG.is_file() else None
    if prefix and not prefix.is_absolute():
        raise ValueError('Mesa prefix must be absolute')
    if args.installations:
        bases = args.installations
    else:
        bases = [Path(os.environ.get('XDG_DATA_HOME', str(Path.home() / '.local/share'))) / 'flatpak']
        if os.geteuid() == 0:
            bases.append(Path('/var/lib/flatpak'))
    for base in bases:
        prepare(base, prefix, build_sdk=args.build_sdk)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, configparser.Error, subprocess.SubprocessError) as error:
        print('Flatpak Mesa not activated: ' + str(error), file=sys.stderr)
        sys.exit(1)
FLATPAK_GL_PY
}

snap_gpu_helper() {
    cat <<'SNAP_GPU_PY'
#!/usr/bin/env python3
"""Publish prepared Mesa through mesa-2404's kernel-gpu-2404 content plug."""
import argparse
import ctypes
import errno
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import select
import shutil
import socket
import subprocess
import sys
import tempfile
import time

CONFIG = Path('/etc/magicdesk/mesa-prefix')
PROBE = Path('/usr/local/libexec/magicdesk-mesa-probe')
CACHE = Path('/var/cache/magicdesk/snap-gpu')
SNAP_DATA = Path('/var/snap/mesa-2404')
NAME = 'magicdesk-gpu-2404'
FORMAT = '3'
MANGLER = '''# Sourced by the official gpu-2404 provider, not an app wrapper.
md_gpu=$COMPONENT_PATH
export LD_LIBRARY_PATH="$md_gpu/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LIBGL_DRIVERS_PATH="$md_gpu/lib/dri" GBM_BACKENDS_PATH="$md_gpu/lib/gbm"
export __EGL_VENDOR_LIBRARY_FILENAMES="$md_gpu/share/glvnd/egl_vendor.d/50_mesa.json"
export VK_DRIVER_FILES="$md_gpu/share/vulkan/icd.d/freedreno_icd.aarch64.json"
export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_KOPPER_DRI2=true
unset LIBGL_ALWAYS_SOFTWARE md_gpu
'''


def run(argv, **kwargs):
    # EVENT_WAIT: the bounded preparation command exits; expiry rejects it.
    return subprocess.run(argv, check=True, timeout=300, **kwargs)


def check_connection():
    connection = http.client.HTTPConnection('localhost', timeout=30)
    connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.sock.settimeout(30)
    try:
        connection.sock.connect('/run/snapd.socket')
        connection.request('GET', '/v2/connections?select=all')
        response = connection.getresponse()
        data = json.load(response)
        if response.status != 200:
            raise ValueError('Cannot inspect Snap GPU connections: ' + str(data))
    finally:
        connection.close()
    for entry in data['result']['established']:
        if entry['plug'] == {'snap': 'mesa-2404', 'plug': 'kernel-gpu-2404'}:
            if entry['slot'] != {'snap': NAME, 'slot': 'kernel-gpu-2404'}:
                raise ValueError('mesa-2404 already uses another GPU provider; disconnect it explicitly')


def publish_component(provider, identity):
    run(['snap', 'start', '--enable', 'mesa-2404.component-monitor'])
    marker = SNAP_DATA / provider.name / 'kernel-gpu-2404/kernel-gpu-2404-sentinel'
    try:
        ready = marker.read_text().strip() == identity
    except FileNotFoundError:
        ready = False
    if not ready:
        # An existing monitor retains its old content mount across a connection
        # update. Restart through snapd to enter the newly prepared namespace.
        run(['snap', 'restart', 'mesa-2404.component-monitor'])
    wait_for_component(provider, identity)


def wait_for_component(provider, identity):
    directory = SNAP_DATA / provider.name / 'kernel-gpu-2404'
    marker = directory / 'kernel-gpu-2404-sentinel'
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_init1.argtypes = [ctypes.c_int]
    libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
    fd = libc.inotify_init1(os.O_CLOEXEC | os.O_NONBLOCK)
    if fd < 0:
        raise OSError(ctypes.get_errno(), 'Cannot observe GPU component publication')
    try:
        events = 0x8 | 0x80 | 0x100 | 0x200 | 0x400 | 0x800
        if libc.inotify_add_watch(fd, os.fsencode(directory.parent), events) < 0:
            raise OSError(ctypes.get_errno(), 'Cannot watch GPU provider data directory')
        deadline = time.monotonic() + 60
        while True:
            # The monitor can create or replace its publication directory. Keep
            # its parent watched and arm the current directory before reading.
            if libc.inotify_add_watch(fd, os.fsencode(directory), events) < 0:
                error = ctypes.get_errno()
                if error != errno.ENOENT:
                    raise OSError(error, 'Cannot watch GPU component directory')
            try:
                if marker.read_text().strip() == identity:
                    return
            except FileNotFoundError:
                pass
            remaining = deadline - time.monotonic()
            # EVENT_WAIT: component-monitor publishes the sentinel last. Timeout
            # is failure, not proof that the new driver is ready for applications.
            if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
                raise ValueError('Timed out waiting for mesa-2404.component-monitor')
            os.read(fd, 65536)
    finally:
        os.close(fd)


def stage_bundle(prefix, root, identity):
    shutil.copytree(prefix / 'lib', root / 'lib', symlinks=True)
    shutil.rmtree(root / 'lib/pkgconfig', ignore_errors=True)
    for path in (root / 'lib').rglob('*'):
        if path.is_symlink() and not path.resolve().is_relative_to((root / 'lib').resolve()):
            raise ValueError('Mesa library link escapes its bundle: ' + str(path))
    for name in ('vulkan/icd.d/freedreno_icd.aarch64.json', 'glvnd/egl_vendor.d/50_mesa.json'):
        data = json.loads((prefix / 'share' / name).read_text())
        library = Path(data['ICD']['library_path']).name
        if not (root / 'lib' / library).is_file():
            raise ValueError('Missing Mesa library: ' + library)
        # The provider prepends its library directory; no host absolute paths.
        data['ICD']['library_path'] = library
        target = root / 'share' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data) + '\n')
    (root / 'kernel-gpu-2404-provider-mangler').write_text(MANGLER)
    (root / 'kernel-gpu-2404-sentinel').write_text(identity + '\n')
    (root / 'meta').mkdir()
    (root / 'meta/snap.yaml').write_text('''name: ''' + NAME + '''
version: 'md-''' + identity[:24] + ''''
summary: Locally prepared MagicDesk Mesa
description: Turnip and Zink for the mesa-2404 GPU content provider.
architectures: [arm64]
base: core24
confinement: strict
grade: devel
slots:
  kernel-gpu-2404:
    interface: content
    content: kernel-gpu-2404
    read: [$SNAP]
''')
    # The caller's private umask must not make a content snap root-only.
    root.chmod(0o755)
    for path in root.rglob('*'):
        if not path.is_symlink():
            path.chmod(0o755 if path.is_dir() else 0o644)


def provider_libraries(provider):
    # Both published mesa-2404 layouts retain the same content contract.
    for path in ('mesa-2404/usr/lib/aarch64-linux-gnu', 'usr/lib/aarch64-linux-gnu'):
        if (provider / path / 'libvulkan.so.1').is_file():
            return path
    raise ValueError('Unsupported mesa-2404 library layout')


def verify_runtime(root, base, provider):
    libraries = provider_libraries(provider)
    targets = [str(p.relative_to(root)) for p in sorted((root / 'lib').rglob('*.so*'))
               if p.is_file() and not p.is_symlink()]
    if not targets:
        raise ValueError('Empty Mesa library bundle')
    argv = ['bwrap', '--unshare-pid', '--unshare-net', '--unshare-ipc',
            '--ro-bind', str(base / 'usr'), '/usr', '--symlink', 'usr/lib', '/lib',
            '--symlink', 'usr/bin', '/bin', '--dev', '/dev', '--proc', '/proc',
            '--ro-bind', str(root), '/mdgl', '--ro-bind', str(provider), '/mdprovider',
            '--ro-bind', str(PROBE), '/probe', '--chdir', '/', '--',
            '/lib/ld-linux-aarch64.so.1', '--library-path',
            '/mdgl/lib:/mdprovider/' + libraries + ':/usr/lib/aarch64-linux-gnu',
            '--preload', '/probe', '/usr/bin/true']
    result = run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C',
             'MD_MESA_PROBE_LIBRARIES': '\n'.join('/mdgl/' + name for name in targets)})
    if not result.stdout.endswith('MD_MESA_PROBE_OK\n'):
        raise ValueError('Runtime loader did not execute the Mesa ABI probe:\n' + result.stdout)


def prepare(prefix, base, provider, jobs):
    marker = (prefix / '.complete').read_text().strip()
    if not marker:
        raise ValueError('Incomplete Mesa build')
    identity = hashlib.sha256((FORMAT + '\n' + str(prefix) + '\n' + marker + '\n' +
                               MANGLER + str(base) + '\n' + str(provider)).encode()).hexdigest()
    CACHE.mkdir(parents=True, exist_ok=True)
    image = CACHE / (NAME + '-' + identity + '.snap')
    if not image.is_file():
        with tempfile.TemporaryDirectory(prefix='.prepare-', dir=CACHE) as temporary:
            root = Path(temporary) / 'root'
            root.mkdir()
            stage_bundle(prefix, root, identity)
            verify_runtime(root, base, provider)
            candidate = Path(temporary) / 'gpu.snap'
            run(['mksquashfs', str(root), str(candidate), '-noappend', '-all-root',
                 '-comp', 'xz', '-processors', str(jobs), '-no-progress'])
            os.replace(candidate, image)
    return image, identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', type=Path, help='Prepared Mesa prefix; defaults to recipe selection')
    parser.add_argument('--jobs', type=int, default=2, help='Compression workers')
    parser.add_argument('--build-only', action='store_true', help='Build and verify without changing Snap connections')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('Run as root inside the selected Linux environment')
    if args.jobs < 1:
        raise ValueError('--jobs must be positive')
    prefix = (args.prefix or Path(CONFIG.read_text().strip())).resolve(strict=True)
    base = Path('/snap/core24/current').resolve(strict=True)
    provider = Path('/snap/mesa-2404/current').resolve(strict=True)
    wrapper = (provider / 'bin/gpu-2404-provider-wrapper').read_text()
    if 'kernel-gpu-2404-provider-mangler' not in wrapper:
        raise ValueError('mesa-2404 does not support the kernel-gpu-2404 provider contract')
    for name in ('bwrap', 'mksquashfs', 'snap'):
        if not shutil.which(name):
            raise ValueError('Required command is not installed: ' + name)
    if not PROBE.is_file():
        raise ValueError('Reapply the graphics recipe to install the Mesa ABI probe')
    CACHE.mkdir(parents=True, exist_ok=True)
    with (CACHE / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if not args.build_only:
            check_connection()
        image, identity = prepare(prefix, base, provider, args.jobs)
        print('Snap GPU bundle: ' + str(image), flush=True)
        if args.build_only:
            return
        marker = Path('/snap') / NAME / 'current/kernel-gpu-2404-sentinel'
        if marker.parent.exists() and not marker.is_file():
            raise ValueError('Not replacing an independently installed ' + NAME)
        if not marker.is_file() or marker.read_text().strip() != identity:
            # Locally built content is unsigned; this does not disable confinement.
            run(['snap', 'install', '--dangerous', str(image)])
        run(['snap', 'connect', 'mesa-2404:kernel-gpu-2404', NAME + ':kernel-gpu-2404'])
        publish_component(provider, identity)
        print('Snap GPU connected. Applications keep their mesa-2404:gpu-2404 connection.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print('Snap GPU preparation failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
SNAP_GPU_PY
}

graphics_profile_reset() {
    cat <<'PROFILE'
if [ -n "${MAGICDESK_FEX_THUNKCONFIG:-}" ] && [ "${FEX_THUNKCONFIG:-}" = "$MAGICDESK_FEX_THUNKCONFIG" ]; then
    unset FEX_THUNKCONFIG
fi
unset MAGICDESK_FEX_THUNKCONFIG
_md_owned=${MAGICDESK_GRAPHICS_LIBRARY_PATH:-${MAGICDESK_MESA_PREFIX:+$MAGICDESK_MESA_PREFIX/lib}}
if [ -n "$_md_owned" ]; then
    _md_paths=${LD_LIBRARY_PATH:-}
    _md_kept=
    while [ -n "$_md_paths" ]; do
        _md_item=${_md_paths%%:*}
        case $_md_paths in *:*) _md_paths=${_md_paths#*:} ;; *) _md_paths= ;; esac
        case :$_md_owned: in
            *:"$_md_item":*) ;;
            *) [ -z "$_md_item" ] || _md_kept=${_md_kept:+$_md_kept:}$_md_item ;;
        esac
    done
    if [ -n "$_md_kept" ]; then export LD_LIBRARY_PATH=$_md_kept; else unset LD_LIBRARY_PATH; fi
fi
if [ -n "${MAGICDESK_MESA_PREFIX:-}" ]; then
    _md_paths=${PRESSURE_VESSEL_FILESYSTEMS_RO:-}
    _md_kept=
    while [ -n "$_md_paths" ]; do
        _md_item=${_md_paths%%:*}
        case $_md_paths in *:*) _md_paths=${_md_paths#*:} ;; *) _md_paths= ;; esac
        if [ -n "$_md_item" ] && [ "$_md_item" != "$MAGICDESK_MESA_PREFIX" ] && [ "$_md_item" != /opt/magicdesk/fex-thunks.json ]; then
            _md_kept=${_md_kept:+$_md_kept:}$_md_item
        fi
    done
    if [ -n "$_md_kept" ]; then export PRESSURE_VESSEL_FILESYSTEMS_RO=$_md_kept; else unset PRESSURE_VESSEL_FILESYSTEMS_RO; fi
fi
unset _md_owned _md_paths _md_kept _md_item MAGICDESK_GRAPHICS_LIBRARY_PATH
unset MAGICDESK_MESA_PREFIX LIBGL_ALWAYS_SOFTWARE GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE
unset LIBGL_DRIVERS_PATH GBM_BACKENDS_PATH LIBGL_KOPPER_DRI2 __EGL_VENDOR_LIBRARY_FILENAMES VK_DRIVER_FILES VK_ICD_FILENAMES
unset MESA_VK_WSI_DEBUG
PROFILE
}

# One payload is both fingerprinted and executed; the lab exports the same patch.
graphics_setup() {
    distribution_support
    build_support
    cat <<'GPU_HEAD'
set -eu
umask 022
gpu=$1
jobs=$2
recipe=$3
GPU_HEAD
    mesa_source
    cat <<'GPU_HEAD'
mkdir -p /var/lib/magicdesk /etc/profile.d
exec 9>/var/lib/magicdesk/installer.lock
flock -n 9 || { echo 'Another graphics setup is running in this environment.' >&2; exit 1; }
profile=/etc/profile.d/magicdesk-graphics.sh
candidate=$profile.new
trap 'status=$?; rm -f "$candidate"; package_cleanup; exit "$status"' 0
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p /usr/local/libexec /usr/local/bin /etc/magicdesk
cat > /usr/local/bin/magicdesk-flatpak-gl <<'FLATPAK_GL_HELPER'
GPU_HEAD
    flatpak_gl_helper
    cat <<'GPU_HEAD'
FLATPAK_GL_HELPER
chmod 755 /usr/local/bin/magicdesk-flatpak-gl
cat > /usr/local/bin/magicdesk-snap-gpu <<'SNAP_GPU_HELPER'
GPU_HEAD
    snap_gpu_helper
    cat <<'GPU_HEAD'
SNAP_GPU_HELPER
chmod 755 /usr/local/bin/magicdesk-snap-gpu
cat > /etc/magicdesk/mesa-options <<'MESA_BUILD_OPTIONS'
GPU_HEAD
    mesa_options
    cat <<'GPU_HEAD'
MESA_BUILD_OPTIONS

prepare_meson() {
    meson_tool=$(command -v meson || :)
    if [ -n "$meson_tool" ] && python3 -c '
import re, sys
match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[.-].*)?", sys.argv[1])
sys.exit(0 if match and tuple(map(int, match.groups())) >= (1, 4, 0) else 1)
' "$("$meson_tool" --version)"; then return; fi
    meson_version=1.7.2
    meson_sha=4d40d63aa748a9c139cc41ab9bffe43edd113c5639d78bde81544ca955aea890
    meson_root=$work/tools/meson-$meson_version
    meson_tool=$meson_root/meson.py
    if [ ! -f "$meson_root/.complete" ]; then
        mkdir -p "$work/tools"
        meson_archive=$work/tools/meson-$meson_version.tar.gz
        if ! printf '%s  %s\n' "$meson_sha" "$meson_archive" | sha256sum -c - >/dev/null 2>&1; then
            # EVENT_WAIT: download progress; stalled transport fails before extraction.
            curl --fail --location --proto '=https' --proto-redir '=https' \
                --connect-timeout 30 --speed-limit 1 --speed-time 60 --retry 3 \
                --output "$meson_archive.part" \
                "https://github.com/mesonbuild/meson/releases/download/$meson_version/meson-$meson_version.tar.gz"
            printf '%s  %s\n' "$meson_sha" "$meson_archive.part" | sha256sum -c -
            mv "$meson_archive.part" "$meson_archive"
        fi
        rm -rf "$meson_root"
        tar --no-same-owner -xzf "$meson_archive" -C "$work/tools"
        [ "$("$meson_tool" --version)" = "$meson_version" ]
        touch "$meson_root/.complete"
    fi
}

prepare_wayland_protocols() {
    pkg-config --atleast-version=1.41 wayland-protocols && return
    # XML build inputs only: do not replace the distribution's Wayland runtime.
    protocols_version=1.41
    protocols_sha=2786b6b1b79965e313f2c289c12075b9ed700d41844810c51afda10ee329576b
    protocols_prefix=$work/tools/wayland-protocols-$protocols_version
    if [ ! -f "$protocols_prefix/.complete" ]; then
        protocols_archive=/var/cache/magicdesk/mesa/downloads/wayland-protocols-$protocols_version.tar.xz
        if ! printf '%s  %s\n' "$protocols_sha" "$protocols_archive" | sha256sum -c - >/dev/null 2>&1; then
            # EVENT_WAIT: download progress; a stalled transport fails before extraction.
            curl --fail --location --proto '=https' --proto-redir '=https' \
                --connect-timeout 30 --speed-limit 1 --speed-time 60 --retry 3 \
                --output "$protocols_archive.part" \
                "https://gitlab.freedesktop.org/wayland/wayland-protocols/-/releases/$protocols_version/downloads/wayland-protocols-$protocols_version.tar.xz"
            printf '%s  %s\n' "$protocols_sha" "$protocols_archive.part" | sha256sum -c -
            mv "$protocols_archive.part" "$protocols_archive"
        fi
        rm -rf "$work/protocols-source" "$work/protocols-build" "$protocols_prefix"
        mkdir -p "$work/protocols-source"
        tar --no-same-owner -xJf "$protocols_archive" --strip-components=1 -C "$work/protocols-source"
        "$meson_tool" setup "$work/protocols-build" "$work/protocols-source" \
            --prefix="$protocols_prefix" --wrap-mode=nodownload -Dtests=false </dev/null
        "$meson_tool" install -C "$work/protocols-build" </dev/null
        touch "$protocols_prefix/.complete"
    fi
    export PKG_CONFIG_PATH="$protocols_prefix/share/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
    pkg-config --atleast-version="$protocols_version" wayland-protocols
}

prepare_glvnd() {
    private_glvnd=
    pkg-config --exists libglvnd && return
    # Some distributions ship Mesa's direct loaders instead of GLVND. Keep
    # their packages intact and provide dispatch with our private Mesa stack.
    glvnd_version=1.7.0
    glvnd_sha=073e7292788d4d3eeb45ea6c7bdcce9bfdb3b3eef8d7dbd47f2f30dce046ef98
    private_glvnd=/opt/magicdesk/glvnd/$glvnd_version-$recipe
    if [ ! -f "$private_glvnd/.complete" ]; then
        glvnd_archive=/var/cache/magicdesk/mesa/downloads/libglvnd-$glvnd_version.tar.gz
        if ! printf '%s  %s\n' "$glvnd_sha" "$glvnd_archive" | sha256sum -c - >/dev/null 2>&1; then
            # EVENT_WAIT: download progress; a stalled transport fails before extraction.
            curl --fail --location --proto '=https' --proto-redir '=https' \
                --connect-timeout 30 --speed-limit 1 --speed-time 60 --retry 3 \
                --output "$glvnd_archive.part" \
                "https://codeload.github.com/NVIDIA/libglvnd/tar.gz/refs/tags/v$glvnd_version"
            printf '%s  %s\n' "$glvnd_sha" "$glvnd_archive.part" | sha256sum -c -
            mv "$glvnd_archive.part" "$glvnd_archive"
        fi
        if [ ! -f "$work/libglvnd-$glvnd_version/.extracted" ]; then
            rm -rf "$work/libglvnd-$glvnd_version"
            tar --no-same-owner -xzf "$glvnd_archive" -C "$work"
            touch "$work/libglvnd-$glvnd_version/.extracted"
        fi
        set -- --prefix="$private_glvnd" --libdir=lib --sysconfdir=/etc \
            --buildtype=release --wrap-mode=nodownload '-Dc_link_args=-Wl,-rpath,$ORIGIN' \
            -Dglx=enabled -Dx11=enabled -Degl=true -Dgles1=true -Dgles2=true
        if [ -f "$work/glvnd-build/build.ninja" ]; then
            "$meson_tool" setup --reconfigure "$work/glvnd-build" "$work/libglvnd-$glvnd_version" "$@" </dev/null
        else
            "$meson_tool" setup "$work/glvnd-build" "$work/libglvnd-$glvnd_version" "$@" </dev/null
        fi
        ninja -C "$work/glvnd-build" -j "$jobs" </dev/null
        rm -rf "$work/glvnd-stage"
        "$meson_tool" install -C "$work/glvnd-build" --no-rebuild --destdir "$work/glvnd-stage" </dev/null
        [ -f "$work/glvnd-stage$private_glvnd/lib/libEGL.so.1" ] || exit 1
        [ -f "$work/glvnd-stage$private_glvnd/lib/libGLX.so.0" ] || exit 1
        mkdir -p /opt/magicdesk/glvnd
        [ ! -e "$private_glvnd" ] || { echo "Incomplete prefix exists: $private_glvnd" >&2; exit 1; }
        printf '%s\n' "$recipe" > "$work/glvnd-stage$private_glvnd/.complete"
        mv "$work/glvnd-stage$private_glvnd" "$private_glvnd"
    fi
    [ "$(cat "$private_glvnd/.complete")" = "$recipe" ] || exit 1
    export PKG_CONFIG_PATH="$private_glvnd/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
}

automatic=no
if [ "$gpu" = auto ]; then
    automatic=yes
    gpu=software
    if [ -c /dev/kgsl-3d0 ] && [ -r /dev/kgsl-3d0 ] && [ -w /dev/kgsl-3d0 ]; then
        gpu=turnip
    else
        printf 'KGSL is unavailable; selecting software rendering.\n'
    fi
fi
if [ "$gpu" != turnip ] && [ "$build_phase" = dependencies ]; then exit 0; fi
if [ "$gpu" = turnip ]; then
    [ -c /dev/kgsl-3d0 ] && [ -r /dev/kgsl-3d0 ] && [ -w /dev/kgsl-3d0 ] || {
        echo 'Turnip requires access to /dev/kgsl-3d0; the graphics profile is unchanged.' >&2
        exit 1
    }
    prefix=/opt/magicdesk/mesa/$version-$recipe
    work=/var/cache/magicdesk/mesa/$version-$recipe
    mkdir -p "$work"
    cat > "$work/wayland-zink.patch" <<'MESA_PATCH'
GPU_HEAD
    mesa_patch
    cat <<'GPU_BODY'
MESA_PATCH
    if [ "$build_phase" != build ]; then
    case "$family" in
    apt) packages build-essential meson ninja-build pkg-config \
        python3-mako python3-yaml python3-ply python3-packaging bison flex patch curl xz-utils glslang-tools \
        libdrm-dev libexpat1-dev libzstd-dev zlib1g-dev libelf-dev \
        libwayland-dev libwayland-egl-backend-dev wayland-protocols libglvnd-dev libvulkan-dev vulkan-tools mesa-utils \
        libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxxf86vm-dev \
        libxrandr-dev libxshmfence-dev libxcb-glx0-dev libxcb-randr0-dev \
        libxcb-shm0-dev libxcb-dri3-dev libxcb-present-dev libxcb-sync-dev \
        libxcb-xfixes0-dev ;;
    apk) packages build-base meson ninja pkgconf python3 py3-mako py3-yaml py3-ply py3-packaging bison flex \
        patch curl xz glslang-dev linux-headers libdrm-dev expat-dev zstd-dev zlib-dev elfutils-dev \
        wayland-dev wayland-protocols mesa-dev vulkan-headers vulkan-tools \
        libx11-dev libxext-dev libxfixes-dev libxxf86vm-dev libxrandr-dev libxshmfence-dev libxcb-dev ;;
    dnf) packages gcc gcc-c++ meson ninja-build pkgconf-pkg-config python3-mako python3-pyyaml \
        python3-ply python3-packaging bison flex patch curl xz glslang libdrm-devel expat-devel libzstd-devel zlib-ng-compat-devel \
        elfutils-libelf-devel wayland-devel wayland-protocols-devel libglvnd-devel vulkan-headers vulkan-tools \
        libX11-devel libXext-devel libXfixes-devel libXxf86vm-devel libXrandr-devel libxshmfence-devel libxcb-devel ;;
    pacman|recovery) packages base-devel meson ninja pkgconf python-mako python-yaml python-ply python-packaging bison flex \
        patch curl xz glslang libdrm expat zstd zlib libelf wayland wayland-protocols libglvnd vulkan-headers \
        vulkan-tools libx11 libxext libxfixes libxxf86vm libxrandr libxshmfence libxcb ;;
    zypper) packages gcc gcc-c++ make meson ninja pkgconf-pkg-config python3-Mako python3-PyYAML \
        python3-ply python3-packaging bison flex patch curl xz glslang-devel libdrm-devel libexpat-devel libzstd-devel zlib-devel \
        libelf-devel wayland-devel wayland-protocols-devel libglvnd-devel vulkan-headers vulkan-tools \
        libX11-devel libXext-devel libXfixes-devel libXxf86vm-devel libXrandr-devel libxshmfence-devel libxcb-devel ;;
    xbps) packages base-devel meson ninja pkg-config python3-Mako python3-yaml python3-ply python3-packaging bison flex \
        patch curl xz glslang libdrm-devel expat-devel libzstd-devel zlib-devel elfutils-devel \
        wayland-devel wayland-protocols libglvnd-devel Vulkan-Headers Vulkan-Tools \
        libX11-devel libXext-devel libXfixes-devel libXxf86vm-devel libXrandr-devel libxshmfence-devel libxcb-devel ;;
    esac
    fi
    [ "$build_phase" != dependencies ] || exit 0
    if [ ! -f "$prefix/.complete" ]; then
        mkdir -p /var/cache/magicdesk/mesa/downloads
        archive=/var/cache/magicdesk/mesa/downloads/mesa-$version.tar.xz
        if ! printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum -c - >/dev/null 2>&1; then
            # EVENT_WAIT: download progress; a stalled connection fails and curl retries it.
            curl --fail --location --proto '=https' --proto-redir '=https' \
                --connect-timeout 30 --speed-limit 1 --speed-time 60 --retry 3 --output "$archive.part" \
                "https://archive.mesa3d.org/mesa-$version.tar.xz"
            printf '%s  %s\n' "$archive_sha" "$archive.part" | sha256sum -c -
            mv "$archive.part" "$archive"
        fi
        source=$work/source
        if [ ! -f "$source/.patched" ]; then
            rm -rf "$source" "$work/build" "$work/unpack"
            mkdir "$work/unpack"
            tar --no-same-owner -xJf "$archive" -C "$work/unpack"
            source_tmp=$work/unpack/mesa-$version
            patch --batch --forward --fuzz=0 -p1 -d "$source_tmp" < "$work/wayland-zink.patch"
            touch "$source_tmp/.patched"
            mv "$source_tmp" "$source"
            rmdir "$work/unpack"
        fi
        prepare_meson
        prepare_wayland_protocols
        prepare_glvnd
        # Both KMDs are needed: Vulkan WSI also uses the DRM image allocator.
        set -- --prefix="$prefix" --libdir=lib --buildtype=release --wrap-mode=nodownload \
            "-Dc_link_args=-Wl,-rpath,\$ORIGIN:\$ORIGIN/..:$prefix/lib" \
            "-Dcpp_link_args=-Wl,-rpath,\$ORIGIN:\$ORIGIN/..:$prefix/lib" \
            '-Dgbm-backends-path=$ORIGIN/gbm'
        while IFS= read -r option; do set -- "$@" "$option"; done < /etc/magicdesk/mesa-options
        if [ -f "$work/build/build.ninja" ]; then
            "$meson_tool" setup --reconfigure "$work/build" "$source" "$@" </dev/null
        else
            "$meson_tool" setup "$work/build" "$source" "$@" </dev/null
        fi
        ninja -C "$work/build" -j "$jobs" </dev/null
        # Install off to the side; a resumed/failed installation cannot alter active libraries.
        rm -rf "$work/stage"
        "$meson_tool" install -C "$work/build" --no-rebuild --destdir "$work/stage" </dev/null
        if [ -n "$private_glvnd" ]; then
            cp -a "$private_glvnd/lib/." "$work/stage$prefix/lib/"
        fi
        # GLVND also accepts this profile override for capability-bearing tasks,
        # where LD_LIBRARY_PATH has already been removed by the ELF loader.
        python3 - "$work/stage$prefix/share/glvnd/egl_vendor.d/50_mesa.json" \
            "$prefix/lib/libEGL_mesa.so.0" <<'EGL_MANIFEST'
import json
from pathlib import Path
import sys
path = Path(sys.argv[1])
manifest = json.loads(path.read_text())
manifest['ICD']['library_path'] = sys.argv[2]
path.write_text(json.dumps(manifest, indent=2) + '\n')
EGL_MANIFEST
        mkdir -p /opt/magicdesk/mesa
        [ ! -e "$prefix" ] || { echo "Incomplete prefix exists: $prefix" >&2; exit 1; }
        printf '%s\n' "$recipe" > "$work/stage$prefix/.complete"
        mv "$work/stage$prefix" "$prefix"
    fi
    [ "$(cat "$prefix/.complete")" = "$recipe" ] || { echo 'Mesa build identity mismatch.' >&2; exit 1; }
    python3 - "$work/source" "$recipe" "$jobs" <<'MESA_BUILD_CONFIG'
import json
from pathlib import Path
import sys
path = Path('/etc/magicdesk/mesa-build.json')
temporary = path.with_suffix('.new')
temporary.write_text(json.dumps(dict(source=sys.argv[1], identity=sys.argv[2], jobs=int(sys.argv[3]),
    options=Path('/etc/magicdesk/mesa-options').read_text().splitlines())) + '\n')
temporary.replace(path)
MESA_BUILD_CONFIG
    cat > "$work/flatpak-gl-probe.c" <<'PROBE'
GPU_BODY
    mesa_probe_source
    cat <<'GPU_BODY'
PROBE
    cc -O2 -shared -fPIC -nostdlib "$work/flatpak-gl-probe.c" -o "$work/flatpak-gl-probe"
    install -m 755 "$work/flatpak-gl-probe" /usr/local/libexec/magicdesk-mesa-probe
fi

reset_profile() {
cat <<'PROFILE'
GPU_BODY
    graphics_profile_reset
    cat <<'GPU_BODY'
PROFILE
}
reset_profile > "$candidate"
if [ "$gpu" = turnip ]; then
    printf 'export MAGICDESK_MESA_PREFIX=%s\n' "$prefix" >> "$candidate"
    cat >> "$candidate" <<'PROFILE'
export LD_LIBRARY_PATH="$MAGICDESK_MESA_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LIBGL_DRIVERS_PATH="$MAGICDESK_MESA_PREFIX/lib/dri"
export GBM_BACKENDS_PATH="$MAGICDESK_MESA_PREFIX/lib/gbm"
export __EGL_VENDOR_LIBRARY_FILENAMES="$MAGICDESK_MESA_PREFIX/share/glvnd/egl_vendor.d/50_mesa.json"
export VK_DRIVER_FILES="$MAGICDESK_MESA_PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json"
# Zink can also present through the frontend when Kopper/DRI3 is unavailable.
export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_KOPPER_DRI2=true
# Thunk defaults are loaded before FEX's per-game overrides; never replace those.
if [ -r /opt/magicdesk/fex-thunks.json ]; then
    if [ "${FEX_THUNKCONFIG+x}" != x ]; then
        export FEX_THUNKCONFIG=/opt/magicdesk/fex-thunks.json
        export MAGICDESK_FEX_THUNKCONFIG=$FEX_THUNKCONFIG
    fi
    export PRESSURE_VESSEL_FILESYSTEMS_RO="$MAGICDESK_MESA_PREFIX:/opt/magicdesk/fex-thunks.json${PRESSURE_VESSEL_FILESYSTEMS_RO:+:$PRESSURE_VESSEL_FILESYSTEMS_RO}"
fi
PROFILE
    # EVENT_WAIT: Vulkan enumeration exits; timeout rejects a hung driver, never activates it.
    probe_status=0
    /bin/sh -c '. "$1"; unset DISPLAY WAYLAND_DISPLAY; exec timeout 30 vulkaninfo --summary' sh "$candidate" \
        > "$work/vulkan-probe.log" 2>&1 || probe_status=$?
    cat "$work/vulkan-probe.log"
    if [ "$probe_status" -ne 0 ] || ! grep -Eq 'driverID[[:space:]]*=[[:space:]]*DRIVER_ID_MESA_TURNIP' "$work/vulkan-probe.log"; then
        [ "$automatic" = yes ] || {
            echo 'No working Turnip GPU found; the graphics profile is unchanged.' >&2; exit 1;
        }
        printf 'Turnip could not initialize this GPU (status %s); selecting software rendering.\n' "$probe_status"
        gpu=software
        reset_profile > "$candidate"
    fi
fi
if [ "$gpu" = software ]; then
    if [ "$ID" = steamos ]; then
        # Valve's Mesa supplies Zink, not a swrast Gallium driver. Its older
        # Lavapipe package needs LLVM 17 alongside the image's newer LLVM.
        packages vulkan-swrast llvm17-libs vulkan-tools mesa-utils
        cat >> "$candidate" <<'PROFILE'
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink
export LIBGL_KOPPER_DRI2=true
export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.aarch64.json
PROFILE
        work=/var/cache/magicdesk/software
        mkdir -p "$work"
        # EVENT_WAIT: Vulkan/EGL probes exit; expiry rejects the candidate profile.
        probe_status=0
        /bin/sh -c '. "$1"; unset DISPLAY WAYLAND_DISPLAY; exec timeout 30 /bin/sh -ec \
            "vulkaninfo --summary; eglinfo -B -p surfaceless"' sh "$candidate" \
            > "$work/probe.log" 2>&1 || probe_status=$?
        cat "$work/probe.log"
        if [ "$probe_status" -ne 0 ] \
                || ! grep -q 'DRIVER_ID_MESA_LLVMPIPE' "$work/probe.log" \
                || ! grep -Eq 'OpenGL core profile renderer:.*zink.*llvmpipe' "$work/probe.log"; then
            echo 'Lavapipe/Zink could not initialize; the graphics profile is unchanged.' >&2
            exit 1
        fi
    else
        printf 'export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe\n' >> "$candidate"
    fi
fi
chmod 644 "$candidate"
# Protected executables cannot rely on driver/library environment overrides.
# Publish the same private stack through standard system loader discovery.
(
    vulkan_manifest=/etc/vulkan/icd.d/magicdesk-turnip.aarch64.json
    egl_manifest=/etc/glvnd/egl_vendor.d/10_magicdesk.json
    trap 'rm -f "$vulkan_manifest.new" "$egl_manifest.new"' 0
    if [ "$gpu" = turnip ]; then
        mkdir -p "${vulkan_manifest%/*}" "${egl_manifest%/*}"
        python3 - "$prefix" "$vulkan_manifest.new" "$egl_manifest.new" <<'DRIVER_MANIFESTS'
import json
from pathlib import Path
import sys

prefix = Path(sys.argv[1])
for source, destination in zip(('vulkan/icd.d/freedreno_icd.aarch64.json',
                                'glvnd/egl_vendor.d/50_mesa.json'), sys.argv[2:]):
    manifest = json.loads((prefix / 'share' / source).read_text())
    library = prefix / 'lib' / Path(manifest['ICD']['library_path']).name
    if not library.is_file():
        raise SystemExit('Missing private graphics library: ' + str(library))
    manifest['ICD']['library_path'] = str(library)
    Path(destination).write_text(json.dumps(manifest, indent=2) + '\n')
DRIVER_MANIFESTS
        chmod 644 "$vulkan_manifest.new" "$egl_manifest.new"
        mv -f "$vulkan_manifest.new" "$vulkan_manifest"
        mv -f "$egl_manifest.new" "$egl_manifest"
    else
        rm -f "$vulkan_manifest" "$egl_manifest"
    fi
    if [ -e /lib/ld-musl-aarch64.so.1 ] &&
            { [ "$gpu" = turnip ] || [ -r /etc/magicdesk/mesa-prefix ]; }; then
        # musl retains pointers into the initial environment. Programs which
        # rewrite argv/environ (including Chromium's zygote) need system lookup
        # too, as do protected programs which cannot use LD_LIBRARY_PATH.
        python3 - "$gpu" "${prefix:-}" <<'MUSL_GRAPHICS_PATH'
from pathlib import Path
import sys

path = Path('/etc/ld-musl-aarch64.path')
marker = Path('/etc/magicdesk/mesa-prefix')
previous_prefix = marker.read_text().strip() if marker.is_file() else ''
previous = previous_prefix + '/lib' if previous_prefix else None
selected = sys.argv[2] + '/lib' if sys.argv[1] == 'turnip' else None
contents = path.read_text() if path.exists() else '/lib:/usr/local/lib:/usr/lib'
paths = [item for item in contents.replace(':', '\n').split('\n')
         if item and item not in (previous, selected)]
if selected:
    paths.insert(0, selected)
temporary = path.with_name(path.name + '.new')
try:
    temporary.write_text('\n'.join(paths) + '\n')
    temporary.chmod(0o644)
    temporary.replace(path)
finally:
    temporary.unlink(missing_ok=True)
MUSL_GRAPHICS_PATH
    fi
)
# Disable old discovery before replacing its profile. A failed activation may
# leave discovery off, but must never advertise it for a software profile.
rm -f /etc/shroot/render-node
mv -f "$candidate" "$profile"
if [ "$gpu" = turnip ]; then
    mkdir -p /etc/shroot
    printf 'turnip\n' > /etc/shroot/render-node.new
    mv /etc/shroot/render-node.new /etc/shroot/render-node
    printf '%s\n' "$prefix" > /etc/magicdesk/mesa-prefix.new
    mv /etc/magicdesk/mesa-prefix.new /etc/magicdesk/mesa-prefix
    mkdir -p /usr/share/flatpak/triggers
    cat > /usr/share/flatpak/triggers/magicdesk-gl.trigger <<'TRIGGER'
#!/bin/sh
exec /usr/local/bin/magicdesk-flatpak-gl "$1"
TRIGGER
    chmod 755 /usr/share/flatpak/triggers/magicdesk-gl.trigger
else
    rm -f /etc/magicdesk/mesa-prefix
fi
if command -v python3 >/dev/null 2>&1; then
    magicdesk-flatpak-gl || echo 'Flatpak GPU preparation failed; see the diagnostic above.' >&2
fi
printf 'Graphics profile activated for new launches: %s\n' "$gpu"
GPU_BODY
}

desktop_session() {
    cat <<'DESKTOP'
#!/bin/sh
set -eu
session_manager() {
    [ -n "${XDG_RUNTIME_DIR:-}" ] &&
        [ "${DBUS_SESSION_BUS_ADDRESS:-}" = "unix:path=$XDG_RUNTIME_DIR/bus" ] &&
        command -v systemd-run >/dev/null 2>&1
}
if [ "${1:-}" = --ready ]; then
    # Called by the compositor after it publishes its child display endpoints.
    set --
    if session_manager; then set -- --systemd; fi
    for variable in DISPLAY WAYLAND_DISPLAY XDG_CURRENT_DESKTOP XDG_SESSION_TYPE PULSE_SERVER PIPEWIRE_REMOTE PIPEWIRE_RUNTIME_DIR; do
        if printenv "$variable" >/dev/null 2>&1; then set -- "$@" "$variable"; fi
    done
    exec dbus-update-activation-environment "$@"
fi
desktop=${1:?Expected xfce, weston, gnome, plasma-x11 or plasma-wayland}
shift
[ ! -r /etc/profile.d/magicdesk-graphics.sh ] || . /etc/profile.d/magicdesk-graphics.sh
[ ! -r /etc/profile.d/magicdesk-applications.sh ] || . /etc/profile.d/magicdesk-applications.sh
if command -v magicdesk-flatpak-gl >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    magicdesk-flatpak-gl || echo 'Flatpak GPU preparation failed; see the diagnostic above.' >&2
fi
# Root needs no authentication agent. Keep packaged and user autostart files
# intact; this profile applies only to explicitly launched root desktops.
if [ "$(id -u)" = 0 ]; then
    export XDG_CONFIG_DIRS="/etc/magicdesk/root-session:${XDG_CONFIG_DIRS:-/etc/xdg}"
fi
case "$desktop" in
    xfce)
        unset WAYLAND_DISPLAY WAYLAND_SOCKET
        export XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=XFCE XDG_SESSION_DESKTOP=xfce
        set -- xfce4-session "$@" ;;
    weston)
        export XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Weston XDG_SESSION_DESKTOP=weston
        export XDG_CONFIG_DIRS="${XDG_CONFIG_DIRS:-/etc/xdg}:/etc/magicdesk/session"
        # Package upgrades can retire the compatibility build without rebuilding.
        case "$(weston --version)" in
            'weston 13.0.'[0-3])
                [ -r /etc/magicdesk/weston.sh ] || {
                    echo 'Complete Weston compatibility setup before launching this desktop.' >&2; exit 1;
                }
                . /etc/magicdesk/weston.sh
                [ -x "$weston_prefix/bin/weston" ] || exit 1
                export PATH="$weston_prefix/bin:$PATH"
                export LD_LIBRARY_PATH="$weston_prefix/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" ;;
        esac
        renderer=pixman
        [ -z "${MAGICDESK_MESA_PREFIX:-}" ] || renderer=gl
        mkdir -p -m 1777 /tmp/.X11-unix
        set -- weston --backend=wayland --renderer="$renderer" --shell=desktop-shell.so --xwayland "$@" ;;
    gnome)
        export XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=GNOME
        set -- gnome-shell --devkit "$@" ;;
    plasma-x11|plasma-wayland)
        export XDG_CURRENT_DESKTOP=KDE XDG_SESSION_DESKTOP=KDE KDE_FULL_SESSION=true KDE_SESSION_VERSION=6
        unset WAYLAND_SOCKET
        mkdir -p -m 1777 /tmp/.X11-unix
        mkdir -p "$HOME/.config"
        kwriteconfig6 --file startkderc --group General --key systemdBoot false
        if [ "$desktop" = plasma-x11 ]; then
            unset WAYLAND_DISPLAY
            export XDG_SESSION_TYPE=x11 QT_QPA_PLATFORM=xcb
            if [ "${LIBGL_ALWAYS_SOFTWARE:-}" = 1 ]; then
                export QT_QUICK_BACKEND=software
            fi
            set -- startplasma-x11 "$@"
        else
            unset DISPLAY
            export XDG_SESSION_TYPE=wayland QT_QPA_PLATFORM=wayland
            if [ -n "${MAGICDESK_MESA_PREFIX:-}" ]; then
                [ -r /etc/magicdesk/plasma-kwin.sh ] || {
                    echo 'Complete Plasma Turnip setup before launching the Wayland desktop.' >&2; exit 1;
                }
                . /etc/magicdesk/plasma-kwin.sh
                [ -x "$kwin_prefix/bin/kwin_wayland" ] || exit 1
                export PATH="$kwin_prefix/bin:$PATH"
                export LD_LIBRARY_PATH="$kwin_prefix/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
                export QT_PLUGIN_PATH="$kwin_prefix/lib/qt6/plugins${QT_PLUGIN_PATH:+:$QT_PLUGIN_PATH}"
                export QML_IMPORT_PATH="$kwin_prefix/lib/qt6/qml${QML_IMPORT_PATH:+:$QML_IMPORT_PATH}"
                export XDG_DATA_DIRS="$kwin_prefix/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
            else
                # The stock nested compositor has no DRM output in this session.
                export KWIN_COMPOSE=Q QT_QUICK_BACKEND=software
            fi
            set -- startplasma-wayland "$@"
        fi ;;
    *) echo "Unknown desktop: $desktop" >&2; exit 2 ;;
esac
# Reuse the launcher's bus. Direct guest-console launches get the same contract.
if session_manager; then
    # The scope retains caller identity, environment and FDs. Its last process
    # releases the graphical target; other sessions keep their own references.
    exec systemd-run --user --scope --collect --quiet \
        --property=BindsTo=graphical-session.target \
        -- magicdesk-audio-session "$@"
fi
if [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
    exec magicdesk-audio-session "$@"
fi
exec dbus-run-session -- magicdesk-audio-session "$@"
DESKTOP
}

audio_session() {
    cat <<'AUDIO'
#!/bin/bash
set -euo pipefail
loop_config() {
    # PI futex owner TIDs cannot represent Shroot's logical PID namespace.
    # Keep a dedicated data loop, using ordinary mutexes and native scheduling.
    cat <<'LOOP_CONFIG'
context.properties = {
    context.data-loops = [
        { thread.name = data-loop.0 loop.prio-inherit = false }
    ]
}
LOOP_CONFIG
}
if [[ ${1:-} == --print-loop-config ]]; then
    loop_config
    exit
fi
if [[ ${1:-} == --ready ]]; then
    printf '%s\n' "$2" >&3
    exit
fi
[[ $# != 0 ]] || { echo 'Expected desktop command.' >&2; exit 2; }
[[ -n ${MAGICDESK_AUDIO_PULSE:-} ]] || exec "$@"
[[ ${MAGICDESK_AUDIO_SESSION:-} != active ]] || exec "$@"
# Native scheduling remains available; this session does not provide RTKit.
export DISABLE_RTKIT=1
host=$MAGICDESK_AUDIO_PULSE
[[ $host == /* && $host != *$'\n'* && $host != *$'\r'* ]] || {
    echo 'Invalid Android audio endpoint.' >&2; exit 2;
}
work=$(mktemp -d "${XDG_RUNTIME_DIR:-/tmp}/magicdesk-audio.XXXXXXXX")
children=()
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    # EVENT_WAIT: release clients before their server; wait owns only our child processes.
    for (( i=${#children[@]}-1; i>=0; i-- )); do
        kill "${children[i]}" 2>/dev/null || :
        wait "${children[i]}" 2>/dev/null || :
    done
    rm -rf -- "$work"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$work/config/pipewire.conf.d" "$work/config/pipewire-pulse.conf.d" "$work/wp/wireplumber.conf.d"
cp /usr/share/pipewire/pipewire.conf /usr/share/pipewire/pipewire-pulse.conf "$work/config/"
cp /usr/share/wireplumber/wireplumber.conf "$work/wp/"
for config in pipewire.conf pipewire-pulse.conf; do
    loop_config > "$work/config/$config.d/90-magicdesk-loop.conf"
done
loop_config > "$work/wp/wireplumber.conf.d/90-magicdesk-loop.conf"
if [[ -d /usr/share/wireplumber/main.lua.d ]]; then
    # WirePlumber 0.4 (Ubuntu 24.04) uses Lua configuration, not feature profiles.
    for part in main.lua.d policy.lua.d bluetooth.lua.d common; do
        # Private editable configuration needs links/content, not package owners.
        cp -R -P "/usr/share/wireplumber/$part" "$work/wp/"
    done
    cat > "$work/wp/main.lua.d/80-magicdesk.lua" <<'LUA'
alsa_monitor.enabled = false
v4l2_monitor.enabled = false
libcamera_monitor.enabled = false
LUA
    cat > "$work/wp/bluetooth.lua.d/80-magicdesk.lua" <<'LUA'
bluez_monitor.enabled = false
bluez_midi_monitor.enabled = false
LUA
fi
host=unix:${host//\\/\\\\}
host=${host//\"/\\\"}
cat > "$work/config/pipewire.conf.d/90-magicdesk.conf" <<CONFIG
# The guest graph has no hardware clock or guaranteed real-time scheduling.
# Keep a bounded scheduling budget even when a client requests a tiny quantum.
context.properties = { default.clock.rate = 48000 default.clock.min-quantum = 1024 mem.allow-mlock = false }
context.exec = [ { path = /usr/local/bin/magicdesk-audio-session args = "--ready core" } ]
CONFIG
# Keep the remote tunnel in a client context, not a core-to-itself connection.
cat > "$work/config/pipewire-pulse.conf.d/90-magicdesk.conf" <<CONFIG
pulse.properties = { pulse.min.req = 1024/48000 pulse.min.quantum = 1024/48000 }
context.modules = [
  { name = libpipewire-module-pulse-tunnel args = {
      tunnel.mode = sink
      pulse.server.address = "$host"
      pulse.latency = 60
      audio.rate = 48000
      audio.channels = 2
      audio.position = [ FL FR ]
      stream.props = {
        node.name = magicdesk.android
        node.description = "Android audio"
        # This is the guest's output device, not an internal routing node.
        node.virtual = false
      }
  } }
]
context.exec = [ { path = /usr/local/bin/magicdesk-audio-session args = "--ready pulse" } ]
CONFIG
cat > "$work/wp/wireplumber.conf.d/90-magicdesk.conf" <<'CONFIG'
wireplumber.profiles = {
  main = {
    monitor.alsa = disabled monitor.alsa-midi = disabled
    monitor.bluez = disabled monitor.bluez-midi = disabled
    monitor.v4l2 = disabled monitor.libcamera = disabled
  }
}
CONFIG
export PIPEWIRE_RUNTIME_DIR=$work PULSE_RUNTIME_PATH=$work/pulse
export PIPEWIRE_REMOTE=pipewire-0 PULSE_SERVER=unix:$work/pulse/native
export MAGICDESK_AUDIO_SESSION=active
mkfifo "$work/ready"
exec 3<>"$work/ready"
PIPEWIRE_CONFIG_DIR=$work/config pipewire &
children+=("$!")
# EVENT_WAIT: PipeWire context.exec startup receipt; expiry tears down this session.
read -r -t 15 receipt <&3 && [[ $receipt == core ]] || {
    echo 'Guest PipeWire startup failed.' >&2; exit 1;
}
WIREPLUMBER_CONFIG_DIR=$work/wp wireplumber 3>&- &
children+=("$!")
PIPEWIRE_CONFIG_DIR=$work/config pipewire-pulse &
children+=("$!")
# EVENT_WAIT: Pulse protocol server initialization, not a fixed startup delay.
read -r -t 15 receipt <&3 && [[ $receipt == pulse ]] || {
    echo 'Guest Pulse compatibility startup failed.' >&2; exit 1;
}
# EVENT_WAIT: the policy manager publishes both output ports; expiry rejects startup.
# A listening server alone does not mean that its sink has been configured.
exec 4< <(pw-link --input --monitor 3>&-)
monitor=$!
children+=("$monitor")
left=0 right=0 deadline=$((SECONDS + 15))
while (( !left || !right )); do
    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) && IFS= read -r -t "$remaining" port <&4 || {
        echo 'Guest Android audio sink was not configured.' >&2; exit 1;
    }
    case $port in
        *magicdesk.android:*_FL) left=1 ;;
        *magicdesk.android:*_FR) right=1 ;;
    esac
done
exec 4<&-
kill "$monitor" 2>/dev/null || :
wait "$monitor" 2>/dev/null || :
unset 'children[-1]'
"$@" <&0 3>&- &
children+=("$!")
# EVENT_WAIT: desktop command lifetime; signal traps stop all owned audio services.
status=0
wait "${children[-1]}" || status=$?
unset 'children[-1]'
exit "$status"
AUDIO
}

steam_game() {
    cat <<'STEAM_GAME'
#!/usr/bin/env python3
"""Steam Launch Options: magicdesk-steam-game %command%."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile


CONTAINER = r'''
set -eu
# FEX's empty-root lookup is relative to cwd; the container root is explicit.
if [ "${FEX_ROOTFS-unset}" = "" ]; then export FEX_ROOTFS=/; fi
if [ -n "${MAGICDESK_MESA_PREFIX:-}" ]; then
    export LD_LIBRARY_PATH="$MAGICDESK_MESA_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export LIBGL_DRIVERS_PATH="$MAGICDESK_MESA_PREFIX/lib/dri${LIBGL_DRIVERS_PATH:+:$LIBGL_DRIVERS_PATH}"
fi
exec "$@"
'''


def launch(command):
    if not command:
        raise ValueError('Use magicdesk-steam-game %command% in Steam Launch Options')
    if 'PRESSURE_VESSEL_EMULATOR' not in os.environ:
        # Steam declares its selected tools. argv also covers launchers that omit
        # that variable; never guess the user's Steam library location.
        directories = [Path(p) for p in os.environ.get('STEAM_COMPAT_TOOL_PATHS', '').split(':') if p]
        directories += [Path(p).parent for p in command if os.path.isabs(p)]
        for directory in dict.fromkeys(directories):
            source = directory / 'emulator.json'
            if not source.is_file():
                continue
            manifest = json.loads(source.read_text())
            emulator = manifest.get('emulator_v0', {})
            if 'FEX_ROOTFS' not in emulator.get('container_environment', {}):
                continue
            for key in ('argv', 'container_argv', 'main_argv', 'server_argv'):
                value = emulator.get(key)
                if value is None:
                    continue
                if isinstance(value, str):
                    value = [value]
                if not isinstance(value, list) or not value or not all(isinstance(p, str) for p in value):
                    raise ValueError('Invalid emulator command: ' + key)
                if not os.path.isabs(value[0]):
                    value[0] = str((directory / value[0]).absolute())
                emulator[key] = value
            for key in ('container_argv', 'main_argv'):
                # pressure-vessel maps this native host executable into /run/host.
                emulator[key] = ['/usr/bin/sh', '-c', CONTAINER,
                                 'magicdesk-steam-container', *emulator[key]]
            # SteamOS /bin/sh is Bash; its dependency must be captured with FEX's.
            emulator['required_libraries'] = list(dict.fromkeys([
                *emulator.get('required_libraries', []), 'libreadline.so.8']))
            data = json.dumps(manifest, sort_keys=True, indent=2).encode() + b'\n'
            cache = Path(os.environ.get('XDG_CACHE_HOME') or Path.home() / '.cache') / 'magicdesk/steam-fex'
            cache.mkdir(parents=True, exist_ok=True, mode=0o700)
            target = cache / (hashlib.sha256(data).hexdigest() + '.json')
            if not target.is_file():
                fd, temporary = tempfile.mkstemp(dir=cache)
                try:
                    with os.fdopen(fd, 'wb') as output:
                        output.write(data)
                    os.replace(temporary, target)
                finally:
                    if os.path.exists(temporary):
                        os.unlink(temporary)
            os.environ['PRESSURE_VESSEL_EMULATOR'] = str(target)
            break
    os.execvp(command[0], command)


try:
    launch(sys.argv[1:])
except (OSError, ValueError, KeyError, TypeError) as error:
    sys.exit('magicdesk-steam-game: ' + str(error))
STEAM_GAME
}

nixos_module() {
    cat <<'NIXOS_MODULE'
{ config, lib, pkgs, ... }:
let
  settings = builtins.fromJSON (builtins.readFile ./magicdesk-settings.json);
  selected = name: builtins.elem name settings.desktops;
  graphical = settings.desktops != [];
  turnip = settings.gpu == "turnip";
  tools = pkgs.runCommand "magicdesk-session-tools" {
    nativeBuildInputs = [ pkgs.bash pkgs.python3 ] ++ lib.optional turnip pkgs.stdenv.cc;
  } ''
    mkdir -p $out/bin $out/libexec
    cp ${./magicdesk-files/magicdesk-desktop-session} $out/bin/magicdesk-desktop-session
    cp ${./magicdesk-files/magicdesk-audio-session} $out/bin/magicdesk-audio-session
    cp ${./magicdesk-files/magicdesk-flatpak-gl} $out/bin/magicdesk-flatpak-gl
    ${lib.optionalString turnip ''
      cc -O2 -shared -fPIC -nostdlib ${./magicdesk-files/mesa-probe.c} -o $out/libexec/magicdesk-mesa-probe
    ''}
    chmod +x $out/bin/*
    substituteInPlace $out/bin/magicdesk-audio-session \
      --replace-fail /usr/share/pipewire ${pkgs.pipewire}/share/pipewire \
      --replace-fail /usr/share/wireplumber ${pkgs.wireplumber}/share/wireplumber
    substituteInPlace $out/bin/magicdesk-flatpak-gl \
      --replace-fail /usr/local/libexec/magicdesk-mesa-probe $out/libexec/magicdesk-mesa-probe \
      --replace-fail "['bwrap'," "['${pkgs.bubblewrap}/bin/bwrap',"
    patchShebangs $out/bin
    mkdir -p $out/share
    $out/bin/magicdesk-audio-session --print-loop-config > $out/share/pipewire-loop.conf
    for name in client.conf client-rt.conf pipewire.conf pipewire-pulse.conf; do
      mkdir -p "$out/share/pipewire/$name.d"
      ln -s ../../pipewire-loop.conf "$out/share/pipewire/$name.d/90-magicdesk-loop.conf"
    done
  '';
  flatpakTriggers = pkgs.runCommand "magicdesk-flatpak-triggers" {} ''
    mkdir -p $out
    cp -r ${config.services.flatpak.package}/share/flatpak/triggers/. $out/
    chmod u+w $out
    rm $out/gtk-icon-cache.trigger
    cp ${pkgs.writeShellScript "magicdesk-icon-cache.trigger" ''
      set -eu
      index=$1/exports/share/icons/hicolor/index.theme
      # A copy from the immutable store retains mode 0444. Only the local
      # regular file belongs to this installation; never chmod a symlink target.
      if test -f "$index" && ! test -L "$index"; then
        ${pkgs.coreutils}/bin/chmod u+w -- "$index"
      fi
      exec ${config.services.flatpak.package}/share/flatpak/triggers/gtk-icon-cache.trigger "$@"
    ''} $out/gtk-icon-cache.trigger
    cp ${pkgs.writeShellScript "magicdesk-gl.trigger" ''
      exec ${tools}/bin/magicdesk-flatpak-gl "$@"
    ''} $out/magicdesk-gl.trigger
  '';
  mesa = if turnip then pkgs.callPackage ./magicdesk-mesa.nix {} else pkgs.mesa;
  kwin = assert pkgs.kdePackages.kwin.version == "6.6.6";
    pkgs.kdePackages.kwin.overrideAttrs (old: {
      postPatch = (old.postPatch or "") + ''
        patch --batch --forward --fuzz=0 -p1 < ${./magicdesk-files/kwin.patch}
      '';
      preConfigure = (old.preConfigure or "") + ''
        cmakeFlagsArray+=("-DCMAKE_AUTOGEN_PARALLEL=$NIX_BUILD_CORES")
      '';
    });
  app = backend: name: title: command: icon: pkgs.makeDesktopItem {
    name = "magicdesk-${name}-${backend}";
    desktopName = "${title} (${backend})";
    exec = "env GDK_BACKEND=${backend} ${command}";
    inherit icon;
    categories = [ "Utility" ];
    extraConfig."X-MagicDesk-Graphics" = backend;
  };
  desktop = name: title: backend: icon: pkgs.makeDesktopItem {
    name = "magicdesk-${name}";
    desktopName = "NixOS - ${title}";
    exec = "${tools}/bin/magicdesk-desktop-session ${name}";
    inherit icon;
    categories = [ "System" ];
    extraConfig = {
      X-MagicDesk-Graphics = backend;
      X-MagicDesk-GraphicsMode = "desktop";
    };
  };
  backends = if settings.protocol == "both" then [ "x11" "wayland" ] else [ settings.protocol ];
  applications = lib.concatMap (backend: [
    (app backend "mousepad" "Mousepad" "${pkgs.mousepad}/bin/mousepad --disable-server %F" "org.xfce.mousepad")
    (app backend "thunar" "Thunar" "${pkgs.thunar}/bin/thunar %F" "org.xfce.thunar")
    (app backend "terminal" "Xfce Terminal" "${pkgs.xfce4-terminal}/bin/xfce4-terminal --disable-server" "org.xfce.terminal")
  ]) backends;
in {
  # The launcher supplies the network. No DHCP server exists on that uplink.
  boot.isContainer = true;
  networking.useDHCP = lib.mkOverride 900 false;
  networking.resolvconf.enable = lib.mkOverride 900 false;
  services.openssh.enable = lib.mkOverride 900 false;
  system.stateVersion = lib.mkDefault "26.05";
  nix.settings = { max-jobs = 1; cores = settings.jobs; sandbox = true; };
  environment.systemPackages = with pkgs; [ bashInteractive coreutils util-linux curl cacert git python3 iproute2 ]
    ++ map (name: lib.attrByPath (lib.splitString "." name)
      (throw "Unknown Nix package attribute: ${name}") pkgs) settings.packages
    ++ lib.optionals graphical ([ tools mousepad thunar xfce4-terminal dbus
      pipewire wireplumber pulseaudio pavucontrol alsa-plugins SDL2 systemd libdecor xkeyboard-config
      hicolor-icon-theme adwaita-icon-theme vulkan-tools mesa-demos ] ++ applications)
    ++ lib.optionals (selected "xfce") [ xfce4-session xfce4-panel xfce4-settings xfce4-appfinder xfdesktop xfwm4 xfce4-pulseaudio-plugin
      (desktop "xfce" "Xfce Desktop" "x11" "org.xfce.xfdesktop") ]
    ++ lib.optionals (selected "weston") [ weston xwayland
      (desktop "weston" "Weston Desktop" "wayland" "computer") ]
    ++ lib.optionals (selected "plasma") [ kdePackages.plasma-pa
      (desktop "plasma-x11" "Plasma Desktop (x11)" "x11" "kde")
      (desktop "plasma-wayland" "Plasma Desktop (wayland)" "wayland" "kde") ];
  services.desktopManager.plasma6.enable = selected "plasma";
  # The minimal LXC profile disables desktop integration by default.
  xdg.autostart.enable = lib.mkOverride 900 graphical;
  xdg.icons.enable = lib.mkOverride 900 graphical;
  xdg.mime.enable = lib.mkOverride 900 graphical;
  xdg.sounds.enable = lib.mkOverride 900 graphical;
  # Android owns firmware and CPU power management, not this container.
  services.fwupd.enable = lib.mkOverride 900 false;
  services.power-profiles-daemon.enable = lib.mkOverride 900 false;
  users.users = lib.genAttrs settings.users (_: {
    isNormalUser = lib.mkDefault true;
    initialHashedPassword = lib.mkDefault "!";
  });
  i18n.defaultLocale = lib.mkIf (settings.locale != "keep") settings.locale;
  time.timeZone = lib.mkIf (settings.timezone != "keep") settings.timezone;
  fonts.packages = lib.optionals (settings.fonts != "keep") [ pkgs.dejavu_fonts ]
    ++ lib.optionals (settings.fonts == "cjk") [ pkgs.noto-fonts-cjk-sans ];
  programs.dconf.enable = lib.mkIf graphical true;
  services.dbus.enable = lib.mkIf graphical true;
  hardware.graphics = lib.mkIf graphical { enable = true; package = mesa; };
  environment.extraInit = lib.mkIf graphical ''
    . /etc/profile.d/magicdesk-applications.sh
    . /etc/profile.d/magicdesk-graphics.sh
  '';
  services.pipewire.configPackages = lib.mkIf graphical [ tools ];
  environment.sessionVariables = lib.mkIf (graphical && config.services.flatpak.enable) {
    FLATPAK_TRIGGERSDIR = "${flatpakTriggers}";
  };
  systemd.services.flatpak-system-helper.environment = lib.mkIf (graphical && config.services.flatpak.enable) {
    FLATPAK_TRIGGERSDIR = "${flatpakTriggers}";
  };
  # The service module owns /etc/pipewire when enabled (for example by Plasma).
  # Other profiles still need client configuration, without starting a daemon.
  environment.etc = lib.mkIf graphical ((lib.optionalAttrs (!config.services.pipewire.enable) {
    "pipewire".source = "${tools}/share/pipewire";
  }) // {
    "wireplumber/wireplumber.conf.d/90-magicdesk-loop.conf".source = "${tools}/share/pipewire-loop.conf";
    "profile.d/magicdesk-applications.sh".text = ''
      export DISABLE_RTKIT=1
      export ALSA_PLUGIN_DIR=${pkgs.alsa-plugins}/lib/alsa-lib
    '' + lib.optionalString config.services.flatpak.enable ''
      export FLATPAK_TRIGGERSDIR=${flatpakTriggers}
    '';
    "asound.conf".text = ''
      pcm.!default { type pulse }
      ctl.!default { type pulse }
    '';
    "profile.d/magicdesk-graphics.sh".text = builtins.readFile ./magicdesk-files/graphics-profile-reset.sh + ''
      export LIBGL_DRIVERS_PATH=${mesa}/lib/dri
      export __EGL_VENDOR_LIBRARY_FILENAMES=${mesa}/share/glvnd/egl_vendor.d/50_mesa.json
      export MAGICDESK_GRAPHICS_LIBRARY_PATH=${lib.makeLibraryPath [ pkgs.libglvnd pkgs.vulkan-loader mesa ]}
      export LD_LIBRARY_PATH=$MAGICDESK_GRAPHICS_LIBRARY_PATH''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
    '' + (if turnip then ''
      unset LIBGL_ALWAYS_SOFTWARE
      export MAGICDESK_MESA_PREFIX=${mesa} GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink LIBGL_KOPPER_DRI2=true
      export VK_DRIVER_FILES=${mesa}/share/vulkan/icd.d/freedreno_icd.aarch64.json
    '' else ''
      export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
    '');
    "magicdesk/root-session/autostart/xfce-polkit.desktop".text = ''
      [Desktop Entry]
      Type=Application
      Name=XFCE PolKit
      Hidden=true
    '';
    "magicdesk/session/weston/weston.ini".text = ''
      [autolaunch]
      path=${pkgs.writeShellScript "magicdesk-desktop-ready" "exec ${tools}/bin/magicdesk-desktop-session --ready"}
    '';
  } // lib.optionalAttrs (turnip && selected "plasma") {
    "magicdesk/plasma-kwin.sh".text = ''
      kwin_prefix=${kwin}
    '';
  } // lib.optionalAttrs turnip {
    "shroot/render-node".text = "turnip\n";
    "magicdesk/mesa-prefix".text = "${mesa}\n";
  });
  # Stable XDG entry points for discovery without evaluating a login shell.
  system.activationScripts.magicdesk = lib.mkIf graphical {
    deps = [ "etc" ];
    text = ''
      mkdir -p /usr/share/X11 /usr/local/bin /usr/local/libexec
      for name in applications icons pixmaps; do
        ln -sfn /nix/var/nix/profiles/system/sw/share/$name /usr/share/$name
      done
      ln -sfn ${pkgs.xkeyboard-config}/share/X11/xkb /usr/share/X11/xkb
      ln -sfn ${tools}/bin/magicdesk-desktop-session /usr/local/bin/magicdesk-desktop-session
      ln -sfn ${tools}/bin/magicdesk-audio-session /usr/local/bin/magicdesk-audio-session
      ln -sfn ${tools}/bin/magicdesk-flatpak-gl /usr/local/bin/magicdesk-flatpak-gl
      if test -f ${tools}/libexec/magicdesk-mesa-probe; then
        ln -sfn ${tools}/libexec/magicdesk-mesa-probe /usr/local/libexec/magicdesk-mesa-probe
      else
        rm -f /usr/local/libexec/magicdesk-mesa-probe
      fi
      install -d -o root -g root -m 1777 /tmp/.X11-unix /tmp/.ICE-unix
    '';
  };
}
NIXOS_MODULE
}

nixos_environment() {
    cat <<'NIXOS_INITIAL'
set -eu
umask 022
export HOME=/root NIX_REMOTE=local
# A fresh LXC image has no active system/profile yet. Select its one closure;
# subsequent runs always use the selected system generation.
system=/nix/var/nix/profiles/system
if [ ! -x "$system/sw/bin/nix-store" ]; then
    system=
    for candidate in /nix/store/*-nixos-system-*; do
        [ -x "$candidate/sw/bin/nix-store" ] || continue
        [ -z "$system" ] || { echo 'Ambiguous initial NixOS system closure.' >&2; exit 1; }
        system=$candidate
    done
    [ -n "$system" ] || { echo 'NixOS system closure is missing.' >&2; exit 1; }
fi
export PATH="$system/sw/bin:/usr/local/bin:/bin"
export NIX_PATH=nixpkgs=/nix/var/nix/profiles/per-user/root/channels/nixos:nixos-config=/etc/nixos/configuration.nix
# Activation and builders share guest-owned API mounts for this setup. Keep
# Android device endpoints, but never change the host devpts permissions.
unshare --mount "$system/sw/bin/bash" -s -- "$system" "$@" <<'NIXOS_ENVIRONMENT'
set -eu
system=$1
shift
mkdir -p /run /tmp /var/tmp
devices=$(mktemp -d /tmp/magicdesk-dev.XXXXXXXX)
mount --rbind /dev "$devices"
mount -t tmpfs -o mode=755 tmpfs /dev
for node in null zero full random urandom tty fuse kgsl-3d0 dma_heap dri net; do
    if [ -d "$devices/$node" ]; then mkdir -p "/dev/$node"
    elif [ -e "$devices/$node" ]; then touch "/dev/$node"
    else continue
    fi
    mount --bind "$devices/$node" "/dev/$node"
done
umount -l "$devices"
rmdir "$devices"
mkdir -p /dev/shm /dev/pts
mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts /dev/pts
ln -s pts/ptmx /dev/ptmx
ln -s /proc/self/fd /dev/fd
ln -s /proc/self/fd/0 /dev/stdin
ln -s /proc/self/fd/1 /dev/stdout
ln -s /proc/self/fd/2 /dev/stderr
mount -t tmpfs -o mode=1777 tmpfs /dev/shm
mount -t proc proc /proc
NIXOS_INITIAL
}

nixos_environment_end() {
    cat <<'NIXOS_END'
NIXOS_ENVIRONMENT
# Activation's /run is temporary. Later non-booted launches must resolve the
# successfully activated generation, not the initial image's runtime link.
current=/nix/var/nix/profiles/system
[ -e "$current" ] || current=$system
ln -sfn "$(readlink -f "$current")" /run/current-system
NIXOS_END
}

nixos_bootstrap() {
    # The official LXC archive has neither /bin/sh nor /etc/passwd until its
    # first activation. A configured generation never needs the pinned shell.
    if admin_exec "$name" -- /bin/sh -c ':' >/dev/null 2>&1; then return; fi
    {
        printf '%s\n' 'set -eu' '. /etc/os-release' '[ "$ID" = nixos ] && [ "$VERSION_ID" = 26.05 ]'
        nixos_environment
        printf '%s\n' 'exec "$system/activate"'
        nixos_environment_end
    } | shroot exec "$name" --user 0:0 --env HOME=/root -- \
        /nix/store/0m1nl6fyb2zn779if6aaswmbwmmdnlwr-bash-interactive-5.3p9/bin/bash -s
}

nixos_setup() {
    nixos_environment
    cat <<'NIXOS_SETUP'
mkdir -p /var/lib/magicdesk /etc/nixos /run /tmp /var/tmp
exec 9>/var/lib/magicdesk/installer.lock
flock -n 9 || { echo 'Another environment setup is running.' >&2; exit 1; }
if [ -f /nix-path-registration ]; then
    echo 'Registering the Nix store...'
    nix-store --load-db < /nix-path-registration
    rm /nix-path-registration
fi
if [ ! -e /nix/var/nix/profiles/system ]; then
    nix-env -p /nix/var/nix/profiles/system --set "$system"
fi
ln -sfn "$(readlink -f /nix/var/nix/profiles/system)" /run/current-system
echo 'Activating the initial NixOS configuration...'
/run/current-system/activate
if [ ! -e /nix/var/nix/profiles/per-user/root/channels/nixos ]; then
    # Use the source shipped with the verified image, not today's channel head.
    nix-env -p /nix/var/nix/profiles/per-user/root/channels --set \
        /nix/store/vbxnw5yg270kybxiv79n39ad1p7bm74c-nixos-26.05.12530.c1ff116587e6
    nix-channel --add https://channels.nixos.org/nixos-26.05 nixos
fi
gui=$1; protocol=$2; locale_name=$3; zone=$4; new_user=$5; fonts=$6; cache=$7; extras=$8; gpu=$9
shift 9
jobs=$1
if [ "$gpu" = auto ]; then
    gpu=software
    if [ -c /dev/kgsl-3d0 ] && [ -r /dev/kgsl-3d0 ] && [ -w /dev/kgsl-3d0 ]; then gpu=turnip; fi
fi
if [ "$gpu" = turnip ]; then
    [ -c /dev/kgsl-3d0 ] && [ -r /dev/kgsl-3d0 ] && [ -w /dev/kgsl-3d0 ] || {
        echo 'Turnip requires access to /dev/kgsl-3d0; the system generation is unchanged.' >&2; exit 1;
    }
fi
cd /etc/nixos
# CLI values were validated before import; package names are attributes, not code.
package_list=
for package in $extras; do package_list="$package_list \"$package\""; done
user_list=
[ -z "$new_user" ] || user_list="\"$new_user\""
desktop_list=
case "$gui" in none|keep) ;; *) desktop_list="\"$gui\"" ;; esac
nix-instantiate --eval --strict --json --expr "
let defaults = { desktops = []; users = []; packages = []; locale = \"keep\"; timezone = \"keep\"; fonts = \"keep\"; gpu = \"software\"; };
    old = defaults // (if builtins.pathExists ./magicdesk-settings.json then builtins.fromJSON (builtins.readFile ./magicdesk-settings.json) else {});
    unique = items: builtins.foldl' (result: item: if builtins.elem item result then result else result ++ [item]) [] items;
in old // {
    desktops = unique (old.desktops ++ [$desktop_list]);
    users = unique (old.users ++ [$user_list]);
    packages = unique (old.packages ++ [$package_list]);
    locale = if \"$locale_name\" == \"keep\" then old.locale else \"$locale_name\";
    timezone = if \"$zone\" == \"keep\" then old.timezone else \"$zone\";
    fonts = if \"$fonts\" == \"keep\" then old.fonts else \"$fonts\";
    gpu = if \"$gpu\" == \"keep\" then old.gpu else \"$gpu\";
    protocol = \"$protocol\"; jobs = $jobs;
}" > magicdesk-settings.json.new
mv magicdesk-settings.json.new magicdesk-settings.json
mkdir -p magicdesk-files
cat > magicdesk.nix.new <<'NIXOS_MODULE'
NIXOS_SETUP
    nixos_module
    cat <<'NIXOS_SETUP'
NIXOS_MODULE
mv magicdesk.nix.new magicdesk.nix
cat > magicdesk-files/magicdesk-desktop-session <<'NIXOS_DESKTOP'
NIXOS_SETUP
    desktop_session
    cat <<'NIXOS_SETUP'
NIXOS_DESKTOP
cat > magicdesk-files/magicdesk-audio-session <<'NIXOS_AUDIO'
NIXOS_SETUP
    audio_session
    cat <<'NIXOS_SETUP'
NIXOS_AUDIO
cat > magicdesk-files/magicdesk-flatpak-gl <<'NIXOS_FLATPAK'
NIXOS_SETUP
    flatpak_gl_helper
    cat <<'NIXOS_SETUP'
NIXOS_FLATPAK
cat > magicdesk-files/graphics-profile-reset.sh <<'NIXOS_GRAPHICS_RESET'
NIXOS_SETUP
    graphics_profile_reset
    cat <<'NIXOS_SETUP'
NIXOS_GRAPHICS_RESET
cat > magicdesk-files/mesa-probe.c <<'NIXOS_MESA_PROBE'
NIXOS_SETUP
    mesa_probe_source
    cat <<'NIXOS_SETUP'
NIXOS_MESA_PROBE
cat > magicdesk-files/mesa-options <<'NIXOS_MESA_OPTIONS'
NIXOS_SETUP
    mesa_options
    cat <<'NIXOS_SETUP'
NIXOS_MESA_OPTIONS
cat > magicdesk-files/mesa.patch <<'NIXOS_MESA_PATCH'
NIXOS_SETUP
    mesa_patch
    cat <<'NIXOS_SETUP'
NIXOS_MESA_PATCH
NIXOS_SETUP
    cat <<'NIXOS_SETUP'
cat > magicdesk-files/kwin.patch <<'NIXOS_KWIN_PATCH'
NIXOS_SETUP
    kwin_patch 6.6.6
    cat <<'NIXOS_SETUP'
NIXOS_KWIN_PATCH
NIXOS_SETUP
    mesa_source
    cat <<'NIXOS_SETUP'
printf '{ version = "%s"; sha256 = "%s"; }\n' "$version" "$archive_sha" > magicdesk-files/mesa-source.nix
cat > magicdesk-mesa.nix <<'NIXOS_MESA'
{ lib, stdenv, fetchurl, meson, ninja, pkg-config, bison, flex, python3,
  libdrm, expat, zstd, zlib, elfutils, wayland, wayland-protocols, wayland-scanner,
  libglvnd, vulkan-headers, glslang, libx11, libxext, libxfixes, libxxf86vm,
  libxrandr, libxshmfence, libxcb }:
let source = import ./magicdesk-files/mesa-source.nix;
in stdenv.mkDerivation {
  pname = "magicdesk-mesa";
  inherit (source) version;
  src = fetchurl { url = "https://archive.mesa3d.org/mesa-${source.version}.tar.xz"; inherit (source) sha256; };
  patches = [ ./magicdesk-files/mesa.patch ];
  patchFlags = [ "-p1" "--fuzz=0" ];
  postPatch = ''
    patchShebangs --build .
  '';
  nativeBuildInputs = [ meson ninja pkg-config bison flex wayland-scanner
    (python3.withPackages (p: [ p.mako p.pyyaml p.ply p.packaging ])) ];
  buildInputs = [ libdrm expat zstd zlib elfutils wayland wayland-protocols libglvnd
    vulkan-headers glslang libx11 libxext libxfixes libxxf86vm libxrandr libxshmfence libxcb ];
  mesonFlags = lib.filter (value: value != "")
    (lib.splitString "\n" (builtins.readFile ./magicdesk-files/mesa-options)) ++ [
      "-Dc_link_args=-Wl,-rpath,$ORIGIN:$ORIGIN/.."
      "-Dcpp_link_args=-Wl,-rpath,$ORIGIN:$ORIGIN/.."
      "-Dgbm-backends-path=$ORIGIN/gbm"
    ];
  postInstall = ''
    python3 - "$out" <<'PY'
import json
from pathlib import Path
import sys
prefix = Path(sys.argv[1])
path = prefix / 'share/glvnd/egl_vendor.d/50_mesa.json'
data = json.loads(path.read_text())
data['ICD']['library_path'] = str(prefix / 'lib/libEGL_mesa.so.0')
path.write_text(json.dumps(data) + '\n')
PY
  '';
}
NIXOS_MESA
if [ ! -e configuration.nix ] && [ ! -L configuration.nix ]; then
    # LXC exports contain a system closure, not a user's rebuild configuration.
    printf '%s\n' '{ ... }: {' \
        '  imports = [ <nixpkgs/nixos/modules/virtualisation/lxc-container.nix> ./magicdesk.nix ];' \
        '}' > configuration.nix
fi
if ! grep -Fq './magicdesk.nix' configuration.nix; then
    # Also accept the minimal upstream LXC configuration. Never guess at an
    # arbitrary user's Nix expression or replace their configuration wholesale.
    stock='  imports = [ <nixpkgs/nixos/modules/virtualisation/lxc-container.nix> ];'
    grep -Fxq "$stock" configuration.nix || {
        echo 'Add ./magicdesk.nix to the imports in /etc/nixos/configuration.nix, then resume.' >&2; exit 1;
    }
    sed '\|^  imports = \[ <nixpkgs/nixos/modules/virtualisation/lxc-container.nix> \];$|c\  imports = [ <nixpkgs/nixos/modules/virtualisation/lxc-container.nix> ./magicdesk.nix ];' \
        configuration.nix > configuration.nix.new
    mv configuration.nix.new configuration.nix
fi
echo 'Building the NixOS system generation (packages and selected graphics)...'
# Installers do not own PID1. boot selects the next generation without trying
# to switch services in an unrelated Shroot launch; activation prepares files.
# Only this trusted installer build opts out. The installed Nix configuration
# retains sandboxing; activation and later user builds do not inherit overrides.
nixos-rebuild boot --no-reexec --max-jobs 1 --cores "$jobs" \
    --option sandbox false --option filter-syscalls false
echo 'Activating the installed NixOS generation...'
/nix/var/nix/profiles/system/activate
ln -sfn "$(readlink -f /nix/var/nix/profiles/system)" /run/current-system
export PATH=/run/current-system/sw/bin:/usr/local/bin:/bin
test -x /sbin/init
nix-store --check-validity "$(readlink -f /nix/var/nix/profiles/system)"
if [ "$cache" = clean ]; then
    # Nix outputs and generations are not a disposable package download cache.
    echo 'Nix retains its store and generations; use nix-collect-garbage explicitly when appropriate.'
fi
echo 'NixOS generation prepared. Configuration: /etc/nixos/configuration.nix'
NIXOS_SETUP
    nixos_environment_end
}

system_setup() {
    distribution_support
    printf "install_desktop_session() {\ncat > /usr/local/bin/magicdesk-desktop-session <<'MD_DESKTOP_SESSION'\n"
    desktop_session
    cat <<'MD_DESKTOP_INSTALL'
MD_DESKTOP_SESSION
chmod 755 /usr/local/bin/magicdesk-desktop-session
mkdir -p /etc/magicdesk/session/weston /usr/local/libexec
cat > /usr/local/libexec/magicdesk-desktop-ready <<'MD_DESKTOP_READY'
#!/bin/sh
exec magicdesk-desktop-session --ready
MD_DESKTOP_READY
chmod 755 /usr/local/libexec/magicdesk-desktop-ready
cat > /etc/magicdesk/session/weston/weston.ini <<'MD_WESTON_SESSION'
[autolaunch]
path=/usr/local/libexec/magicdesk-desktop-ready
MD_WESTON_SESSION
# Normally created by tmpfiles at boot; direct non-root desktop launches need
# the same root-owned shared socket directories without booting the OS.
install -d -o root -g root -m 1777 /tmp/.X11-unix /tmp/.ICE-unix
mkdir -p /etc/magicdesk/root-session/autostart
cat > /etc/magicdesk/root-session/autostart/xfce-polkit.desktop <<'MD_ROOT_AGENT'
[Desktop Entry]
Type=Application
Name=XFCE PolKit
Hidden=true
MD_ROOT_AGENT
}
MD_DESKTOP_INSTALL
    printf "install_audio_session() {\ncat > /usr/local/bin/magicdesk-audio-session <<'MD_AUDIO_SESSION'\n"
    audio_session
    cat <<'MD_AUDIO_INSTALL'
MD_AUDIO_SESSION
chmod 755 /usr/local/bin/magicdesk-audio-session
for config in client.conf client-rt.conf pipewire.conf pipewire-pulse.conf; do
    mkdir -p "/etc/pipewire/$config.d"
    /usr/local/bin/magicdesk-audio-session --print-loop-config > "/etc/pipewire/$config.d/90-magicdesk-loop.conf"
done
mkdir -p /etc/wireplumber/wireplumber.conf.d
/usr/local/bin/magicdesk-audio-session --print-loop-config > /etc/wireplumber/wireplumber.conf.d/90-magicdesk-loop.conf
}
MD_AUDIO_INSTALL
    printf "install_steam_game() {\ncat > /usr/local/bin/magicdesk-steam-game <<'MD_STEAM_GAME'\n"
    steam_game
    printf 'MD_STEAM_GAME\nchmod 755 /usr/local/bin/magicdesk-steam-game\n}\n'
    cat <<'SETUP'
arch_mirror_fallback() {
    [ -f "$1" ] || return 0
    # The ARM rootfs ships only a geo-redirector. Retain it after the official
    # HTTPS mirror used for the rootfs; do not replace a user's mirror selection.
    servers=$(awk '/^[[:space:]]*Server[[:space:]]*=/ {
        sub(/^[[:space:]]*Server[[:space:]]*=[[:space:]]*/, ""); print
    }' "$1")
    [ "$servers" = 'http://mirror.archlinuxarm.org/$arch/$repo' ] || return 0
    awk '{ if ($0 ~ /^[[:space:]]*Server[[:space:]]*=/) print "Server = https://de3.mirror.archlinuxarm.org/$arch/$repo"; print }' \
        "$1" > "$1.magicdesk-new"
    mv "$1.magicdesk-new" "$1"
}
gui=$1
protocol=$2
locale_name=$3
zone=$4
new_user=$5
fonts=$6
cache=$7
extras=$8
# Minimal images may need util-linux first; package managers own their bootstrap locks.
setup_locked=no
if command -v flock >/dev/null 2>&1; then
    mkdir -p /var/lib/magicdesk
    exec 9>/var/lib/magicdesk/installer.lock
    flock -n 9 || { echo 'Another environment setup is running.' >&2; exit 1; }
    setup_locked=yes
fi
trap 'status=$?; package_cleanup; exit "$status"' 0
trap 'exit 130' INT
trap 'exit 143' TERM
case "$family" in
    apt)
        if [ "$locale_name" != keep ] && [ "$locale_name" != C.UTF-8 ]; then
            # Slim images exclude translations from newly installed GUI packages.
            printf 'path-include /usr/share/locale/*\n' > /etc/dpkg/dpkg.cfg.d/zz-magicdesk-locales
        fi
        # Package services belong to Linux, not Android's service manager.
        if [ ! -e /usr/sbin/policy-rc.d ]; then
            printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
            chmod 755 /usr/sbin/policy-rc.d
        fi
        dpkg --configure -a </dev/null
        refresh_packages
        packages ca-certificates bash coreutils util-linux tzdata locales passwd ;;
    apk)
        refresh_packages
        packages ca-certificates bash coreutils util-linux tzdata musl-locales musl-locales-lang lang shadow ;;
    dnf) packages ca-certificates bash coreutils util-linux tzdata glibc-langpack-en shadow-utils iptables-nft ;;
    zypper)
        refresh_packages
        packages ca-certificates bash coreutils util-linux timezone glibc-locale glibc-i18ndata shadow iptables-backend-nft ;;
    xbps)
        refresh_packages
        packages ca-certificates bash coreutils util-linux tzdata glibc-locales shadow iptables-nft ;;
    pacman)
        arch_mirror_fallback /etc/pacman.d/mirrorlist
        refresh_packages
        packages ca-certificates bash coreutils util-linux tzdata gzip ;;
    recovery)
        refresh_packages
        dbus-uuidgen --ensure ;;
esac
mkdir -p /var/lib/magicdesk /etc/profile.d /usr/local/bin /usr/local/share/applications
if [ "$family" = recovery ]; then
    mkdir -p /opt/magicdesk
    cat > /opt/magicdesk/fex-thunks.json <<'FEX_DEFAULTS'
{"ThunksDB":{"GL":"1","Vulkan":"1"}}
FEX_DEFAULTS
    install_steam_game
fi
if [ "$setup_locked" = no ]; then
    exec 9>/var/lib/magicdesk/installer.lock
    flock -n 9 || { echo 'Another environment setup is running.' >&2; exit 1; }
fi

if [ "$zone" != keep ]; then
    [ -f "/usr/share/zoneinfo/$zone" ] || { echo "Unknown timezone: $zone" >&2; exit 1; }
    ln -sfn "/usr/share/zoneinfo/$zone" /etc/localtime
    printf '%s\n' "$zone" > /etc/timezone
fi
if [ "$locale_name" != keep ]; then
    if [ "$locale_name" != C.UTF-8 ]; then
        language=${locale_name%%_*}
        case "$family" in
            apt|pacman|recovery|zypper|xbps) localedef -i "${locale_name%.UTF-8}" -f UTF-8 "$locale_name" ;;
            dnf) packages "glibc-langpack-$language" ;;
            apk)
                MUSL_LOCPATH=/usr/share/i18n/locales/musl locale -a | grep -Fqx "${locale_name%.UTF-8}" || {
                    echo "Locale not supplied by musl-locales: $locale_name" >&2; exit 1;
                } ;;
        esac
    fi
    # The selected guest locale must not be masked by Android Shell's LC_ALL.
    printf 'unset LC_ALL\nexport LANG=%s\n' "$locale_name" > /etc/profile.d/magicdesk-locale.sh
    printf 'LANG=%s\n' "$locale_name" > /etc/locale.conf
fi
if [ -n "$new_user" ]; then
    if getent passwd "$new_user" >/dev/null; then
        [ "$(id -u "$new_user")" -ge 1000 ] || {
            echo "Refusing to reuse system account: $new_user" >&2; exit 1;
        }
        printf 'Retaining existing account and home: %s\n' "$new_user"
    else
        useradd -m -U -s /bin/bash "$new_user"
    fi
fi

# Keep the selected desktops on resume, but refresh their shared launch services.
gui_services=no
if { [ "$gui" != none ] && [ "$gui" != keep ]; } || [ -x /usr/local/bin/magicdesk-audio-session ]; then
    gui_services=yes
    case "$family" in
        apt) packages pipewire pipewire-bin pipewire-pulse wireplumber pipewire-alsa pulseaudio-utils \
            libsdl2-2.0-0 libudev1 libdecor-0-plugin-1-cairo ;;
        apk) packages pipewire pipewire-pulse pipewire-tools wireplumber pipewire-alsa pulseaudio-utils \
            sdl2 eudev-libs libdecor ;;
        dnf) packages pipewire pipewire-pulseaudio pipewire-utils wireplumber pipewire-alsa pulseaudio-utils \
            sdl2-compat systemd-libs systemd-pam libdecor ;;
        pacman|recovery)
            udev_package=systemd-libs
            sdl_package=sdl2
            if [ "$ID" = artix ]; then udev_package=libudev; sdl_package=sdl2-compat; fi
            packages pipewire pipewire-pulse wireplumber pipewire-alsa libpulse \
                "$sdl_package" "$udev_package" libdecor ;;
        zypper) packages pipewire pipewire-pulseaudio pipewire-tools wireplumber pipewire-alsa pulseaudio-utils \
            libSDL2-2_0-0 libudev1 libdecor ;;
        xbps) packages pipewire wireplumber alsa-pipewire pulseaudio-utils SDL2 eudev-libudev libdecor ;;
    esac
    install_audio_session
    install_desktop_session
    case "$ID" in
        steamos) ;;
        openkylin) packages pavucontrol-qt ;;
        *) packages pavucontrol ;;
    esac
    if [ "$gui" = xfce ] || [ -f /usr/local/share/applications/magicdesk-xfce.desktop ]; then
        packages xfce4-pulseaudio-plugin
    fi
    if [ "$gui" = plasma ] || [ -f /usr/local/share/applications/magicdesk-plasma-wayland.desktop ]; then
        packages plasma-pa
    fi
fi
if [ "$gui" != none ] && [ "$gui" != keep ]; then
    case "$family" in
        apt) packages dbus-x11 xkb-data adwaita-icon-theme mousepad thunar xfce4-terminal \
            libgl1-mesa-dri libegl-mesa0 libglx-mesa0 libegl1 libgl1 libgles2 ;;
        apk) packages dbus dbus-x11 xkeyboard-config adwaita-icon-theme mousepad thunar xfce4-terminal \
            mesa-dri-gallium mesa-egl mesa-gl ;;
        dnf) packages dbus-daemon dbus-x11 xkeyboard-config adwaita-icon-theme mousepad Thunar xfce4-terminal \
            mesa-dri-drivers mesa-libEGL mesa-libGL ;;
        pacman) packages dbus xkeyboard-config adwaita-icon-theme mousepad thunar xfce4-terminal mesa ;;
        zypper) packages dbus-1-daemon dbus-1-tools xkeyboard-config adwaita-icon-theme mousepad thunar xfce4-terminal \
            Mesa-dri Mesa-libEGL1 Mesa-libGL1 ;;
        xbps) packages dbus xkeyboard-config adwaita-icon-theme mousepad Thunar xfce4-terminal \
            mesa-dri mesa ;;
    esac
    dbus-uuidgen --ensure
    case "$gui:$family" in
        xfce:apt|xfce:apk|xfce:pacman) packages xfce4 ;;
        xfce:dnf|xfce:zypper|xfce:xbps) packages xfce4-session xfce4-panel xfce4-settings xfce4-appfinder xfdesktop xfwm4 ;;
        weston:apk) packages weston weston-shell-desktop weston-backend-wayland weston-clients weston-xwayland xwayland ;;
        weston:apt) packages weston xwayland ;;
        weston:dnf) packages weston xorg-x11-server-Xwayland ;;
        weston:pacman) packages weston xorg-xwayland ;;
        weston:zypper) packages weston xwayland ;;
        weston:xbps) packages weston xorg-server-xwayland ;;
        plasma:apt) packages plasma-desktop plasma-workspace kwin-wayland kwin-x11 \
            dolphin konsole kdialog breeze xwayland qml6-module-qtquick-controls \
            qml6-module-qtquick-layouts qml6-module-qtquick-window ;;
        gnome:dnf) packages gnome-shell mutter-devkit gnome-terminal nautilus gnome-text-editor ;;
    esac
fi
if [ "$gui_services" = yes ]; then
    # Recovery images can ship Flatpak without an initialized system repo.
    # Prepare it as root before desktop notifiers try to monitor it as a user.
    if command -v flatpak >/dev/null 2>&1 && [ ! -f /var/lib/flatpak/repo/config ]; then
        echo 'Initializing the system Flatpak repository...'
        flatpak repair --system
    fi

    # Scheduling policy is launch-scoped, not a replacement for distro config.
    for config in client.conf client-rt.conf; do
        rm -f "/etc/pipewire/$config.d/90-magicdesk-scheduling.conf"
    done

    # VLC's relocatable Linux build guesses its plugin directory from native
    # /proc/self/maps. Object-backed stores need its explicit search path.
    mkdir -p /etc/profile.d
    cat > /etc/profile.d/magicdesk-applications.sh <<'APPLICATIONS'
# No realtime portal/RTKit service; PipeWire may still use native scheduling.
export DISABLE_RTKIT=1
if [ "${VLC_PLUGIN_PATH+x}" != x ]; then
    for _md_lib in /usr/lib /usr/lib64 /usr/lib/*-linux-gnu; do
        if [ -d "$_md_lib/vlc/plugins" ]; then
            export VLC_PLUGIN_PATH=$_md_lib/vlc/plugins
            break
        fi
    done
    unset _md_lib
fi
APPLICATIONS

fi
if [ "$gui" != none ] && [ "$gui" != keep ]; then
    # Protocol-specific entries are ours; distribution/user launchers are never rewritten.
    for backend in x11 wayland; do
        apps='mousepad thunar terminal'
        [ "$family" != recovery ] || apps=
        for app in $apps; do
            entry=/usr/local/share/applications/magicdesk-$app-$backend.desktop
            if [ "$protocol" != both ] && [ "$protocol" != "$backend" ]; then
                rm -f "$entry"
                continue
            fi
            case "$app" in
                mousepad) title=Mousepad; cmd='mousepad --disable-server %F'; icon=org.xfce.mousepad ;;
                thunar) title=Thunar; cmd='thunar %F'; icon=org.xfce.thunar ;;
                terminal) title='Xfce Terminal'; cmd='xfce4-terminal --disable-server'; icon=org.xfce.terminal ;;
            esac
            printf '[Desktop Entry]\nType=Application\nName=%s (%s)\nExec=env GDK_BACKEND=%s %s\nIcon=%s\nTerminal=false\nCategories=Utility;\nX-MagicDesk-Graphics=%s\n' \
                "$title" "$backend" "$backend" "$cmd" "$icon" "$backend" > "$entry"
        done
    done
    if [ ! -e /etc/profile.d/magicdesk-graphics.sh ]; then
        printf 'export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe\n' > /etc/profile.d/magicdesk-graphics.sh
    fi
    if [ "$family" != recovery ]; then
        command -v mousepad
        command -v thunar
        command -v xfce4-terminal
    fi
fi

if [ "$gui_services" = yes ]; then
    # Refresh only installer-owned entries that exist or were explicitly selected.
    for desktop in xfce weston gnome plasma-x11 plasma-wayland; do
        entry=/usr/local/share/applications/magicdesk-$desktop.desktop
        [ "$gui" = "${desktop%%-*}" ] || [ -f "$entry" ] || continue
        case "$desktop" in
            xfce) title='Xfce Desktop'; backend=x11; icon=org.xfce.xfdesktop; program=xfce4-session ;;
            weston) title='Weston Desktop'; backend=wayland; icon=computer; program=weston ;;
            gnome) title='GNOME Shell (development kit)'; backend=wayland; icon=computer; program=gnome-shell ;;
            plasma-*) backend=${desktop#plasma-}; title="Plasma Desktop ($backend)"; icon=preferences-desktop; program=startplasma-$backend ;;
        esac
        command -v "$program"
        printf '[Desktop Entry]\nType=Application\nName=%s - %s\nExec=magicdesk-desktop-session %s\nIcon=%s\nTerminal=false\nCategories=System;\nX-MagicDesk-Graphics=%s\nX-MagicDesk-GraphicsMode=desktop\n' \
            "${NAME:-$ID}" "$title" "$desktop" "$icon" "$backend" > "$entry"
    done
    rm -f /usr/local/bin/magicdesk-plasma /usr/local/bin/magicdesk-weston
fi

if [ "$fonts" != keep ]; then
    case "$family" in
        apt) packages fonts-dejavu-core; [ "$fonts" != cjk ] || packages fonts-noto-cjk ;;
        apk) packages font-dejavu; [ "$fonts" != cjk ] || packages font-noto-cjk ;;
        dnf) packages dejavu-sans-fonts; [ "$fonts" != cjk ] || packages google-noto-sans-cjk-fonts ;;
        pacman) packages ttf-dejavu; [ "$fonts" != cjk ] || packages noto-fonts-cjk ;;
        zypper) packages dejavu-fonts; [ "$fonts" != cjk ] || packages google-noto-sans-cjk-fonts ;;
        xbps) packages dejavu-fonts-ttf; [ "$fonts" != cjk ] || packages noto-fonts-cjk ;;
    esac
fi
# Names were validated before any image mutation; no evaluation of package text.
if [ -n "$extras" ]; then
    set -f
    packages $extras
    set +f
fi
# Module loading belongs to the host, including when this rootfs is exported
# to another container runtime. Keep the units usable for a native OS boot.
if [ -d /usr/lib/systemd/system ] || [ -d /lib/systemd/system ]; then
    for unit in systemd-modules-load.service modprobe@.service; do
        mkdir -p "/etc/systemd/system/$unit.d"
        printf '[Unit]\nConditionVirtualization=!container\n' > "/etc/systemd/system/$unit.d/50-magicdesk-container.conf"
    done
    # A runtime mask also excludes dependencies of physical mount units. A
    # condition on home.mount alone would still pull in its disk/fsck jobs.
    mkdir -p /usr/local/lib/systemd/system-generators
    cat > /usr/local/lib/systemd/system-generators/magicdesk-container-hardware <<'CONTAINER_HARDWARE'
#!/bin/sh
set -eu
systemd-detect-virt --container --quiet || exit 0
# Containers use an eager binfmt mount, independently of host autofs support.
for units in /usr/lib/systemd/system /lib/systemd/system; do
    [ -f "$units/proc-sys-fs-binfmt_misc.mount" ] || continue
    mkdir -p "$1/sysinit.target.wants"
    ln -sf "$units/proc-sys-fs-binfmt_misc.mount" "$1/sysinit.target.wants/"
    ln -sf /dev/null "$1/proc-sys-fs-binfmt_misc.automount"
    break
done
for unit in deckard-audio-setup.service deckard-boot-images.service deckard-fpga.service \
    deckard-led-control.service deckard-typec-logger.service deckard-power-monitor.service \
    deckard-charger.service deckard-fan-control.service gpu-trace.service steamos-boot.service \
    steamvr-program-ble.service steamvr-set-kernel-thread-priorities.service \
    steamvr-v4l2loopback.service; do
    if [ -f "/usr/lib/systemd/system/$unit" ] || [ -f "/lib/systemd/system/$unit" ]; then
        ln -sf /dev/null "$1/$unit"
    fi
done
# Only vendor partition-set mounts. Administrator units in /etc take priority
# over this normal-priority generator output and are never rewritten.
for path in /usr/lib/systemd/system/*.mount /lib/systemd/system/*.mount; do
    if [ -f "$path" ] && grep -q '^What=/dev/disk/by-partsets/' "$path"; then
        ln -sf /dev/null "$1/${path##*/}"
    fi
done
CONTAINER_HARDWARE
    chmod 755 /usr/local/lib/systemd/system-generators/magicdesk-container-hardware
fi
if [ -d /etc/runit ]; then
    mkdir -p /usr/local/libexec
    cat > /usr/local/libexec/magicdesk-container-services <<'CONTAINER_SERVICES'
#!/bin/sh
set -eu
publish() {
    IFS= read -r interpreter < "$1"
    case "$interpreter" in *bash*) bash -n "$1.magicdesk-new" ;; *) sh -n "$1.magicdesk-new" ;; esac
    if ! cmp -s "$1" "$1.magicdesk-new"; then
        chmod --reference="$1" "$1.magicdesk-new"
        chown --reference="$1" "$1.magicdesk-new"
        mv "$1.magicdesk-new" "$1"
    else
        rm "$1.magicdesk-new"
    fi
}
    for service in /etc/sv/*/run /etc/runit/sv/*/run; do
        [ -f "$service" ] || continue
        # Preserve the packaged daemon and its options. Only standalone host
        # module preparation becomes conditional, as with systemd above.
        # runit may clear its services' environment; PID 1 retains the contract.
        awk '
            function guard() {
                print "if [ -z \"${container:-}\" ] && ! tr \"\\000\" \"\\n\" 2>/dev/null < /proc/1/environ | grep -q \"^container=.\"; then"
            }
            /^# MagicDesk container module policy$/ { print; getline; guard(); guarded=1; next }
            guarded { print; guarded=0; next }
            /^(env[ \t]+)?modprobe[ \t]+[-a-zA-Z0-9_ \t]+([ \t]*\|\|[ \t]*exit[ \t]+[0-9]+)?$/ {
                print "# MagicDesk container module policy"
                guard()
                print; print "fi"; next
            }
            { print }
        ' "$service" > "$service.magicdesk-new"
        publish "$service"
    done
    # These stages manage the host RTC, kernel modules, virtual consoles or
    # kernel log. Keep them on native boot, as systemd's container conditions do.
    for name in hwclock console-setup modules kmod-static-nodes dmesg; do
        service=/usr/lib/rc/sv.d/$name
        [ -f "$service" ] || continue
        awk '
            NR==1 {
                print
                print "# MagicDesk host hardware policy"
                print "if [ -n \"${container:-}\" ] || tr \"\\000\" \"\\n\" 2>/dev/null < /proc/1/environ | grep -q \"^container=.\"; then"
                print "    exit 0"
                print "fi"
                next
            }
            /^# MagicDesk host hardware policy$/ { skip=3; next }
            skip { --skip; next }
            { print }
        ' "$service" > "$service.magicdesk-new"
        publish "$service"
    done
CONTAINER_SERVICES
    chmod 755 /usr/local/libexec/magicdesk-container-services
    /usr/local/libexec/magicdesk-container-services
    if [ "$family" = pacman ]; then
        mkdir -p /etc/pacman.d/hooks
        cat > /etc/pacman.d/hooks/95-magicdesk-container-services.hook <<'CONTAINER_HOOK'
[Trigger]
Operation = Install
Operation = Upgrade
Type = Path
Target = etc/sv/*/run
Target = etc/runit/sv/*/run
Target = usr/lib/rc/sv.d/*

[Action]
Description = Preserve container-aware hardware preparation in runit services
When = PostTransaction
Exec = /usr/local/libexec/magicdesk-container-services
CONTAINER_HOOK
    fi
fi
if [ "$family" = dnf ]; then
    # Fedora's legacy alternative targets host xtables, not the guest nftables model.
    alternatives --set iptables /usr/bin/iptables-nft
elif [ "$family" = pacman ]; then
    # The nft package provides iptables too. Accept only its package-conflict
    # replacement question when resuming a rootfs with the legacy provider.
    pacman -S --needed --noconfirm --ask=4 iptables-nft </dev/null
elif [ "$family" = xbps ]; then
    xbps-alternatives -s iptables-nft -g iptables
fi
if [ "$cache" = clean ]; then
    case "$family" in
        apt) apt-get clean ;;
        apk) rm -f /var/cache/apk/*.apk ;;
        dnf) dnf clean packages ;;
        pacman) pacman -Sc --noconfirm </dev/null ;;
        zypper) zypper --non-interactive clean ;;
        xbps) xbps-remove -Oy ;;
    esac
fi
SETUP
}

recovery_reader() {
    cat <<'READER'
import base64
import bz2
import hashlib
import io
import json
import mmap
from pathlib import Path
import stat
import struct
import sys
import tarfile
import zlib


def exact(stream, size):
    data = stream.read(size)
    if len(data) != size:
        raise ValueError('Truncated recovery image')
    return data


def partition_table(stream):
    header = bytearray(exact(stream, 1024)[512:])
    if header[:8] != b'EFI PART':
        raise ValueError('Expected a GPT recovery image')
    size, checksum = struct.unpack_from('<II', header, 12)
    if not 92 <= size <= 512:
        raise ValueError('Invalid GPT header length')
    struct.pack_into('<I', header, 16, 0)
    if zlib.crc32(header[:size]) != checksum:
        raise ValueError('GPT header checksum mismatch')
    first, last = struct.unpack_from('<QQ', header, 40)
    lba, count, stride, crc = struct.unpack_from('<QIII', header, 72)
    if lba != 2 or not 1 <= count <= 4096 or stride != 128:
        raise ValueError('Unsupported GPT entry layout')
    table = exact(stream, count * stride)
    if zlib.crc32(table) != crc:
        raise ValueError('GPT table checksum mismatch')
    parts = {}
    ranges = []
    for i in range(count):
        entry = table[i * stride:(i + 1) * stride]
        if entry[:16] == bytes(16):
            continue
        start, end = struct.unpack_from('<QQ', entry, 32)
        name = entry[56:128].decode('utf-16-le').rstrip('\0')
        if not first <= start <= end <= last or name in parts:
            raise ValueError('Invalid or duplicate GPT partition')
        parts[name] = (start * 512, (end - start + 1) * 512)
        ranges.append((start, end))
    ranges.sort()
    if any(a[1] >= b[0] for a, b in zip(ranges, ranges[1:])):
        raise ValueError('Overlapping GPT partitions')
    return parts, 1024 + len(table)


def extract_root(archive, target):
    with bz2.open(archive, 'rb') as source:
        parts, offset = partition_table(source)
        start, size = parts['rootfs-A']
        if start < offset or size > 16 * 1024**3:
            raise ValueError('Unsupported recovery root partition')
        print(f'Reading rootfs-A: {size // 1024**2} MiB', flush=True)
        while offset < start:
            offset += len(exact(source, min(start - offset, 4 * 1024**2)))
        with target.open('xb') as output:
            remaining = size
            while remaining:
                block = exact(source, min(remaining, 4 * 1024**2))
                output.write(block)
                remaining -= len(block)
                if (size - remaining) % (256 * 1024**2) == 0 or not remaining:
                    print(f'Read {(size - remaining) // 1024**2}/{size // 1024**2} MiB', flush=True)
        # Consume the tail: a complete bzip2 stream, not just a readable partition.
        while source.read(4 * 1024**2):
            pass


def acl_text(value):
    if len(value) < 4 or struct.unpack_from('<I', value)[0] != 2 or (len(value) - 4) % 8:
        raise ValueError('Invalid POSIX ACL')
    names = {1: 'user', 2: 'user', 4: 'group', 8: 'group', 16: 'mask', 32: 'other'}
    entries = []
    for tag, perm, identifier in struct.iter_unpack('<HHI', value[4:]):
        if tag not in names or perm > 7:
            raise ValueError('Invalid POSIX ACL entry')
        who = str(identifier) if tag in (2, 8) else ''
        mode = ''.join(c if perm & bit else '-' for c, bit in [('r', 4), ('w', 2), ('x', 1)])
        entries.append(f'{names[tag]}:{who}:{mode}')
    return ','.join(entries)


def xattrs(node):
    from dissect.btrfs.c_btrfs import c_btrfs
    for _, data in node.subvolume.tree.cursor().iter(
            node.inum, c_btrfs.BTRFS_XATTR_ITEM_KEY, 0, ignore_offset=True):
        stream = io.BytesIO(data)
        while stream.tell() < len(data):
            item = c_btrfs.btrfs_dir_item(stream)
            yield item.name.decode('utf-8'), item.data


def metadata(node, name):
    info = tarfile.TarInfo(name)
    info.mode = stat.S_IMODE(node.mode)
    info.uid, info.gid = node.uid, node.gid
    info.mtime = node.mtime_ns // 10**9
    for field in ('mtime', 'atime'):
        value = getattr(node, field + '_ns')
        seconds, nanos = divmod(abs(value), 10**9)
        info.pax_headers[field] = f'{"-" if value < 0 else ""}{seconds}.{nanos:09d}'
    for key, value in xattrs(node):
        if key == 'btrfs.compression':
            continue  # Filesystem storage policy, not a userspace file attribute.
        if key in ('system.posix_acl_access', 'system.posix_acl_default'):
            info.pax_headers['SCHILY.acl.' + key.rsplit('_', 1)[1]] = acl_text(value)
        elif key == 'security.capability':
            info.pax_headers['LIBARCHIVE.xattr.' + key] = base64.b64encode(value).decode()
        else:
            raise ValueError(f'Unsupported image attribute {key} on {name}')
    return info


def children(node):
    return {name: child for name, child in node.iterdir() if name not in ('.', '..')}


def export_tree(archive, node, path='', fallback=None, links=None, factory=False):
    links = {} if links is None else links
    name = path or '.'
    info = metadata(node, name)
    kind = stat.S_IFMT(node.mode)
    if kind == stat.S_IFDIR:
        info.type = tarfile.DIRTYPE
    elif kind == stat.S_IFLNK:
        info.type, info.linkname = tarfile.SYMTYPE, node.link
    elif kind == stat.S_IFIFO:
        info.type = tarfile.FIFOTYPE
    elif kind == stat.S_IFREG:
        key = (factory, node.subvolume.objectid, node.inum)
        if key in links:
            info.type, info.linkname = tarfile.LNKTYPE, links[key]
        else:
            info.size = node.size
            links[key] = name
    else:
        raise ValueError(f'Unsupported file type {oct(kind)} at {name}')
    if info.isreg():
        with node.open() as source:
            archive.addfile(info, source)
    else:
        archive.addfile(info)
    if len(archive.members) % 5000 == 0:
        print(f'Exported {len(archive.members)} entries: /{name}', flush=True)
    if kind == stat.S_IFDIR:
        entries = children(node)
        defaults = children(fallback) if fallback else {}
        for child in sorted(entries.keys() | defaults.keys()):
            if not child or '/' in child or '\0' in child or child.startswith('.wh.'):
                raise ValueError('Invalid filesystem name')
            primary = entries.get(child)
            secondary = defaults.get(child)
            if primary is None:
                # Factory contents are copies, not aliases of /usr/share/factory.
                export_tree(archive, secondary, f'{path}/{child}'.lstrip('/'), links=links, factory=True)
            else:
                if not (primary.is_dir() and secondary and secondary.is_dir()):
                    secondary = None
                export_tree(archive, primary, f'{path}/{child}'.lstrip('/'), secondary, links, factory)


class HashWriter:
    def __init__(self, stream):
        self.stream, self.digest = stream, hashlib.sha256()

    def write(self, data):
        self.digest.update(data)
        return self.stream.write(data)


def json_blob(directory, value, media):
    data = json.dumps(value, separators=(',', ':')).encode()
    digest = hashlib.sha256(data).hexdigest()
    (directory / digest).write_bytes(data)
    return {'mediaType': media, 'digest': 'sha256:' + digest, 'size': len(data)}


def convert(image, destination):
    from compression import zstd
    from dissect.btrfs import Btrfs
    blobs = destination / 'blobs/sha256'
    blobs.mkdir(parents=True)
    layer = destination / 'layer.tmp'
    with image.open('rb') as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as mapped:
        fs = Btrfs(mapped)
        release = fs.get('/etc/os-release').open().read().decode()
        if '\nID=steamos\n' not in release or '\nVERSION_ID=0.3.0\n' not in release:
            raise ValueError('Unexpected SteamOS release')
        with zstd.open(layer, 'wb', level=3) as compressed:
            writer = HashWriter(compressed)
            with tarfile.open(fileobj=writer, mode='w|', format=tarfile.PAX_FORMAT) as tar:
                # Recovery /var contains reimaging tools. Populate the installed
                # userspace from rootfs and its factory defaults instead.
                root = fs.get('/')
                info = metadata(root, '.')
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
                links = {}
                for name, node in sorted(children(root).items()):
                    print(f'Exporting /{name}', flush=True)
                    default = fs.get('/usr/share/factory/var') if name == 'var' else None
                    export_tree(tar, node, name, default, links)
        diff_id = writer.digest.hexdigest()
    with layer.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    length = layer.stat().st_size
    layer.rename(blobs / digest)
    config = json_blob(blobs, {
        'architecture': 'arm64', 'os': 'linux',
        'config': {'User': 'steamos', 'WorkingDir': '/home/steamos', 'Cmd': ['/bin/bash'],
                   'Env': ['PATH=/usr/local/sbin:/usr/local/bin:/usr/bin', 'HOME=/home/steamos'],
                   'Labels': {'org.opencontainers.image.title': 'SteamOS ARM64',
                              'org.opencontainers.image.version': '0.3.0',
                              'io.github.mekhontsev.magicdesk.recovery.sha256':
                              '3a4a077f1b1f40688ab3279affcb56776bd97c54db1573e7c65fc52a97106676'}},
        'rootfs': {'type': 'layers', 'diff_ids': ['sha256:' + diff_id]}},
        'application/vnd.oci.image.config.v1+json')
    manifest = json_blob(blobs, {'schemaVersion': 2, 'config': config, 'layers': [{
        'mediaType': 'application/vnd.oci.image.layer.v1.tar+zstd',
        'digest': 'sha256:' + digest, 'size': length}]},
        'application/vnd.oci.image.manifest.v1+json')
    (destination / 'oci-layout').write_text('{"imageLayoutVersion":"1.0.0"}')
    (destination / 'index.json').write_text(json.dumps({'schemaVersion': 2, 'manifests': [manifest]}))
    print(f'Local SteamOS image ready: {length // 1024**2} MiB', flush=True)


if __name__ == '__main__':
    archive, work = Path(sys.argv[1]), Path(sys.argv[2])
    image = work / 'rootfs.img'
    extract_root(archive, image)
    convert(image, work / 'oci')
    image.unlink()
READER
}

download_verified() {
    file="$downloads/${1##*/}"
    if [ -f "$file" ] && [ ! -L "$file" ]; then
        actual=$(sha256sum "$file"); actual=${actual%% *}
        if [ "$actual" = "$2" ]; then printf 'Using verified %s\n' "$file"; return; fi
    fi
    magicdesk download "$1" "$file" --sha256 "$2"
}

import_helper() { MAGICDESK_GUEST_HOME="$import_work/library" shroot "$@"; }

prepare_downloads() {
    environments=$(shroot list)
    if printf '%s\n' "$environments" | cut -f1 | grep -Fxq "$name"; then
        fail "Environment $name already exists; use --resume to continue setup."
    fi
    downloads=${downloads:-${HOME:?}/downloads}
    mkdir -p "$downloads"
    downloads=$(cd "$downloads" && pwd -P)
}

prepare_import_helper() {
    work_parent=${TMPDIR:-$HOME/.cache}
    mkdir -p "$work_parent"
    work_parent=$(cd "$work_parent" && pwd -P)
    case "$work_parent/" in "$downloads/"*) fail 'Temporary work must be outside the read-only download cache.' ;; esac
    import_work=$(mktemp -d "$work_parent/magicdesk-import-XXXXXX")
    mkdir "$import_work/exchange"
    # Validate the selected DNS policy before fetching the multi-GB recovery archive.
    stage='preparing the temporary Python environment'
    import_helper install 'python@sha256:fe368aeff9df1995b57f0aa9bbced949a2a98fb3e6dfd92568e5f6acea6220f7' --name unpack --dns "$dns"
}

prepare_rootfs() {
    prepare_downloads
    if [ -n "$source_checksum" ]; then
        stage="reading the official $distro rootfs checksum"
        checksum_file="$downloads/${source##*/}.sha256"
        magicdesk download "$source_checksum" "$checksum_file"
        source_sha=$(awk -v name="${source##*/}" '
            NF == 2 && ($2 == name || $2 == "*" name) {
                if (length($1) != 64 || $1 ~ /[^0-9A-Fa-f]/) exit 1
                digest = tolower($1); count++
            }
            END { if (count != 1) exit 1; print digest }
        ' "$checksum_file") || fail "Invalid official checksum for ${source##*/}."
    fi
    stage="downloading the official $distro rootfs"
    download_verified "$source" "$source_sha"
    image="$downloads/${source##*/}"
    case "$source" in
        *.xz)
            prepare_import_helper
            stage='decompressing the rootfs archive'
            # Only change compression. Native import owns tar validation and metadata.
            import_helper exec unpack --bind "$import_work/exchange" /mnt --bind-ro "$downloads" /media \
                -- /usr/local/bin/python3 -c '
import gzip, lzma, shutil, sys
with lzma.open(sys.argv[1], "rb") as source, gzip.open(sys.argv[2], "wb", compresslevel=1) as target:
    shutil.copyfileobj(source, target, 1024 * 1024)
' "/media/${source##*/}" /mnt/rootfs.tar.gz
            image="$import_work/exchange/rootfs.tar.gz" ;;
    esac
}

prepare_recovery() {
    prepare_downloads
    prepare_import_helper
    stage='downloading the official SteamOS recovery image'
    download_verified "$source" '3a4a077f1b1f40688ab3279affcb56776bd97c54db1573e7c65fc52a97106676'
    stage='downloading pinned filesystem readers'
    download_verified 'https://files.pythonhosted.org/packages/a8/24/717afae59efd76fb85d882af52eeea827207776dd8bfdcd24916a0de5288/dissect_btrfs-1.10-py3-none-any.whl' '22569fba43c1740aa998544998beee09b599ff95f45d4fe3e05a47fac689d938'
    download_verified 'https://files.pythonhosted.org/packages/37/1c/dc626b1b0e477546c8e80d8cb028442ef4602af276c405f7a5ccc85b0b5a/dissect_cstruct-4.7-py3-none-any.whl' '0427621ce67baa3106df2dc63a320d0a7f5c2da88ba3faf2ef5886a1a6b458fd'
    download_verified 'https://files.pythonhosted.org/packages/cd/09/926cbb3fed29f762e676fb6a8d62b68c6bf536f817892cd35e89145e30e7/dissect_util-3.24-cp310-abi3-musllinux_1_2_aarch64.whl' 'df4130e896201c44e6b3d8ec1c022b586915abaedd1552f9c1b878bd343ae696'
    stage='installing the verified filesystem readers'
    import_helper exec unpack --bind-ro "$downloads" /mnt -- /usr/local/bin/python3 -m pip install \
        --no-index --no-deps --only-binary=:all: --no-cache-dir --root-user-action=ignore \
        /mnt/dissect_btrfs-1.10-py3-none-any.whl /mnt/dissect_cstruct-4.7-py3-none-any.whl \
        /mnt/dissect_util-3.24-cp310-abi3-musllinux_1_2_aarch64.whl
    stage='converting SteamOS to a local Linux image'
    recovery_reader | import_helper exec unpack --bind "$import_work/exchange" /mnt \
        --bind-ro "$downloads" /media -- /usr/local/bin/python3 - "/media/${source##*/}" /mnt
    image="$import_work/exchange/oci"
}

fail() { printf '%s\n' "$*" >&2; exit 1; }
require_magicdesk_version() {
    command -v magicdesk >/dev/null 2>&1 || fail 'magicdesk is unavailable. Open a MagicDesk Shell console first.'
    installed_magicdesk_version_code=$(magicdesk get_state --field data.app.versionCode) || \
        fail "Cannot determine MagicDesk version. Use MagicDesk $MIN_MAGICDESK_VERSION_NAME or newer and open a new Shell console."
    case "$installed_magicdesk_version_code" in
        ''|*[!0-9]*) fail 'Invalid MagicDesk versionCode; expected a non-negative Android version code.' ;;
    esac
    if [ "${#installed_magicdesk_version_code}" -gt 10 ] || [ "$installed_magicdesk_version_code" -gt 2147483647 ]; then
        fail 'Invalid MagicDesk versionCode; expected a non-negative Android version code.'
    fi
    [ "$installed_magicdesk_version_code" -ge "$MIN_MAGICDESK_VERSION_CODE" ] || \
        fail "This installer requires MagicDesk $MIN_MAGICDESK_VERSION_NAME or newer (versionCode $MIN_MAGICDESK_VERSION_CODE; installed $installed_magicdesk_version_code). Update MagicDesk, open a new Shell console and try again."
}
ask() {
    printf '%s [%s]: ' "$1" "$2"
    IFS= read -r answer || fail 'Input closed.'
    answer=${answer:-$2}
}
android_locale() (
    # Read the active user's preference, not the app language or shell's LANG.
    locales=
    if command -v settings >/dev/null 2>&1; then
        locales=$(settings --user current get system system_locales 2>/dev/null) || locales=
    fi
    case "$locales" in
        ''|null)
            if command -v getprop >/dev/null 2>&1; then
                locales=$(getprop persist.sys.locale 2>/dev/null) || locales=
            fi ;;
    esac
    tag=${locales%%,*}
    # Formatting extensions do not select another language/region. Do not guess
    # POSIX modifiers for script/variant tags or a region for language-only tags.
    base=${tag%%-u-*}
    if printf '%s\n' "$base" | LC_ALL=C grep -Eq '^[a-z]{2,3}-[A-Z]{2}$'; then
        printf '%s_%s.UTF-8\n' "${base%-*}" "${base#*-}"
    else
        printf 'Android locale has no unambiguous Linux mapping; retaining the guest locale. Use --locale to override.\n' >&2
        printf 'keep\n'
    fi
)
ask_dns() {
    printf '\n%s\n' \
        '  auto      Current network (recommended)' \
        '  preserve  Keep the Linux image/current Linux DNS settings' \
        '  IP[,IP...]  Custom DNS server addresses (1-3)'
    [ "$1" != cancel ] || printf '  cancel    Cancel installation\n'
    dns_default=$1
    [ "$dns_default" != system ] || dns_default=auto
    ask 'Network' "$dns_default"
    dns=$answer
}
check_dns() {
    while :; do
        [ "$dns" != auto ] || dns=system
        if dns_error=$(shroot dns --check "$dns" 2>&1); then return; fi
        printf '\nCannot prepare guest DNS.\n%s\n' "$dns_error" >&2
        printf '%s\n' \
            'Check the current connection, then choose auto to try again, or choose another DNS option.' \
            'No public DNS server will be selected automatically.' >&2
        [ "$yes" = no ] || fail 'Retry with --dns auto, --dns IP[,IP...] or --dns preserve.'
        ask_dns cancel
        case "$dns" in
            cancel) printf 'Cancelled.\n'; exit 0 ;;
        esac
    done
}
dns_description() {
    case "$dns" in
        auto|system) printf 'Current network (DNS saved once; editable in Linux)' ;;
        preserve) printf 'Keep Linux DNS settings' ;;
        *) printf 'Custom DNS: %s' "$dns" ;;
    esac
}
recipe_defaults() {
    source_kind=oci
    source_sha=
    source_checksum=
    case "$distro" in
        debian)
            source=https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/slim/oci/blobs/rootfs.tar.gz
            source_sha=bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba
            source_kind=rootfs; version=13 ;;
        ubuntu)
            source=https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz
            source_sha=a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
            source_kind=rootfs; version=24.04 ;;
        alpine)
            source=https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.6-aarch64.tar.gz
            source_sha=b17a57958e29735ff0e6e64254d958e65903687a70ca30cc430b33a965ad49d7
            source_kind=rootfs; version=3.23 ;;
        nixos)
            source=https://hydra.nixos.org/build/350465271/download/1/nixos-image-lxc-26.05.12530.c1ff116587e6-aarch64-linux.tar.xz
            source_sha=333b1d718db3d99147ca2b5fb6d533b4aabaac98420d48e9a5369799433352ae
            source_kind=rootfs; version=26.05 ;;
        fedora) source=registry.fedoraproject.org/fedora:44; version=44 ;;
        arch)
            # Official mirror with working TLS; archive authenticated with upstream's build key.
            source=https://de3.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz
            source_sha=42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319
            source_kind=rootfs; version=rolling ;;
        opensuse)
            # Rolling snapshots are removed upstream; verify the current alias
            # against its HTTPS-published checksum before native import.
            source=https://download.opensuse.org/ports/aarch64/tumbleweed/appliances/opensuse-tumbleweed-image.aarch64-lxc.tar.xz
            source_checksum=$source.sha256
            source_kind=rootfs; version=Tumbleweed ;;
        openkylin) source=openkylin/openkylin:latest; version=3.0 ;;
        deepin) source=linuxdeepin/deepin:crimson-arm64; version=25 ;;
        manjaro)
            source=https://github.com/manjaro-arm/rootfs/releases/download/20261012/Manjaro-ARM-aarch64-latest.tar.gz
            source_sha=1ae540a80e21e1ca55e0576df9618fd2865cf4596b8204d20780f225aca5164a
            source_kind=rootfs; version=rolling ;;
        void)
            source=https://repo-default.voidlinux.org/live/20250202/void-aarch64-ROOTFS-20250202.tar.xz
            source_sha=01a30f17ae06d4d5b322cd579ca971bc479e02cc284ec1e5a4255bea6bac3ce6
            source_kind=rootfs; version=rolling ;;
        armtix)
            source=https://armtix.artixlinux.org/images/armtix-runit-20260921.tar.xz
            source_sha=8e1de041c64cb8bd8c1766d8f9048e75544de1c162769c02a626ce484aad4465
            source_kind=rootfs; version=rolling ;;
        steamos) source=https://steamdeck-images.steamos.cloud/recovery/steamframe-oobe-repair-20260922.5153644-0.3.0.img.bz2; version=0.3.0; source_kind=recovery ;;
        *) fail 'Unsupported distribution; use --list for available recipes.' ;;
    esac
}
distribution_id() {
    case "$1" in
        archarm) printf 'arch\n' ;;
        opensuse-tumbleweed) printf 'opensuse\n' ;;
        manjaro-arm) printf 'manjaro\n' ;;
        artix) printf 'armtix\n' ;;
        *) printf '%s\n' "$1" ;;
    esac
}
gui_notice() {
    [ "$gui" != gnome ] || printf 'GNOME devkit also needs a Linux system bus; this recipe does not boot one.\n'
}
admin_exec() (
    admin_target=$1
    shift
    # exec retains image HOME; setup must use the selected account's home instead.
    exec shroot login "$admin_target" --user root "$@"
)
package_exec() (
    package_target=$1
    shift
    # These hints apply only to setup, never to later logins or systemd boots.
    [ "${FORCE_IPV4:-}" != 1 ] || set -- --env FORCE_IPV4=1 "$@"
    admin_exec "$package_target" --env DEBIAN_FRONTEND=noninteractive --env SYSTEMD_IN_CHROOT=1 --env LC_ALL=C "$@"
)
if [ "$#" = 1 ] && [ "$1" = --print-mesa-patch ]; then mesa_patch; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-flatpak-gl-helper ]; then flatpak_gl_helper; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-snap-gpu-helper ]; then snap_gpu_helper; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-system-setup ]; then system_setup; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-nixos-setup ]; then nixos_setup; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-nixos-module ]; then nixos_module; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-graphics-setup ]; then graphics_setup; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-weston-setup ]; then weston_setup; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-desktop-session ]; then desktop_session; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-audio-session ]; then audio_session; exit 0; fi
if [ "$#" = 1 ] && [ "$1" = --print-steam-game ]; then steam_game; exit 0; fi
if [ "$#" -ge 1 ] && [ "$1" = --print-kwin-patch ]; then
    [ "$#" -le 2 ] || fail 'Expected one KWin version.'
    kwin_patch "${2:-6.3.6}" || fail 'KWin version must be 6.6.6, 6.3.6 or 6.2.5.'
    exit 0
fi
if [ "$#" = 1 ] && [ "$1" = --print-recovery-reader ]; then recovery_reader; exit 0; fi
distro=
name=
image=
gui=
protocol=both
gpu=
jobs=4
jobs_selected=no
locale_name=
zone=
new_user=
fonts=
cache=keep
extras=
yes=no
resume=no
dns=
downloads=
import_work=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --distro|--name|--image|--downloads|--gui|--protocol|--gpu|--jobs|--locale|--timezone|--create-user|--fonts|--cache|--package|--dns)
            [ "$#" -ge 2 ] && [ -n "$2" ] || fail "Missing value for $1"
            case "$1" in
                --distro) distro=$2 ;; --name) name=$2 ;; --image) image=$2 ;; --gui) gui=$2 ;;
                --protocol) protocol=$2 ;; --gpu) gpu=$2 ;; --jobs) jobs=$2; jobs_selected=yes ;; --locale) locale_name=$2 ;;
                --timezone) zone=$2 ;; --create-user) new_user=$2 ;; --fonts) fonts=$2 ;;
                --cache) cache=$2 ;;
                --package) extras="${extras:+$extras }$2" ;; --dns) dns=$2 ;;
                --downloads) downloads=$2 ;;
            esac
            shift 2 ;;
        --yes) yes=yes; shift ;;
        --resume) resume=yes; shift ;;
        --list) configurations; exit 0 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "Unknown argument: $1 (see --help)" ;;
    esac
done
if [ "$resume" = yes ]; then
    [ -n "$name" ] || fail '--resume requires --name NAME.'
    [ -z "$dns" ] && [ -z "$image" ] || fail '--resume retains image and DNS; use shroot dns to change DNS.'
fi

command -v shroot >/dev/null 2>&1 || fail 'Open a MagicDesk Shell console with shell access first.'
case "$(id -u)" in 2000|0) ;; *) fail 'Shroot requires the selected Android shell (UID 2000) or root executor.' ;; esac
require_magicdesk_version
if [ "$yes" = no ]; then
    [ -t 0 ] || fail 'Interactive input is unavailable. Save the script before running it, or use --yes.'
    if [ "$resume" = no ]; then
        configurations
        if [ -z "$distro" ]; then ask 'Distribution' debian; distro=$answer; fi
    fi
    if [ -z "$name" ]; then ask 'Independent environment name' "${distro:-debian}"; name=$answer; fi
    if [ "$distro" = steamos ]; then
        fonts=${fonts:-keep}
        printf 'SteamOS retains its supplied applications and system drivers.\n'
    fi
    if [ -z "$gui" ]; then
        default_gui=apps; [ "$resume" = no ] || default_gui=keep
        case "$distro" in steamos) default_gui=plasma ;; nixos) [ "$resume" = yes ] || default_gui=plasma ;; esac
        ask 'GUI: none/apps/xfce/weston/plasma (Debian/SteamOS/NixOS)/gnome (Fedora devkit)/keep' "$default_gui"; gui=$answer
    fi
    if [ -z "$gpu" ]; then
        default_gpu=keep
        if [ "$distro" = steamos ] && [ "$resume" = no ]; then default_gpu=auto; fi
        ask 'Graphics: keep/software/auto/turnip (Adreno KGSL; builds Mesa)' "$default_gpu"; gpu=$answer
    fi
    if { [ "$gpu" = turnip ] || [ "$gpu" = auto ] || [ "$gui" = plasma ] || [ "$gui" = weston ]; } && [ "$jobs_selected" = no ]; then
        ask 'Compiler jobs for Mesa/KWin/Weston (1-8)' "$jobs"; jobs=$answer
    fi
    if [ -z "$locale_name" ]; then
        default_locale=system; [ "$resume" = no ] || default_locale=keep
        ask 'Locale: system (Android), keep, or UTF-8 locale' "$default_locale"; locale_name=$answer
    fi
    if [ -z "$zone" ]; then ask 'Timezone: system, IANA zone, or keep' keep; zone=$answer; fi
    if [ -z "$fonts" ]; then
        default_fonts=basic; [ "$resume" = no ] || default_fonts=keep
        [ "$distro" != steamos ] || default_fonts=keep
        ask 'Fonts: basic/cjk/keep' "$default_fonts"; fonts=$answer
    fi
    ask 'Additional settings (guest user, protocol, packages, DNS, cache)? y/N' N
    case "$answer" in
        y|Y|yes|YES)
            if [ -z "$new_user" ]; then ask 'Create guest user (empty = no new account)' ''; new_user=$answer; fi
            if [ "$distro" != steamos ]; then
                ask 'Installer application entries: both/x11/wayland' "$protocol"; protocol=$answer
                ask 'Additional repository packages (space-separated)' "$extras"; extras=$answer
            fi
            if [ "$resume" = no ] && [ -z "$dns" ]; then
                ask_dns auto
            fi
            if [ "$distro" != steamos ]; then ask 'Downloaded package cache: keep/clean' "$cache"; cache=$answer; fi
            ;;
    esac
fi
name=${name:-${distro:-debian}}
if [ "$resume" = yes ]; then
    gui=${gui:-keep}; fonts=${fonts:-keep}
elif [ "$distro" = steamos ]; then
    gui=${gui:-plasma}; gpu=${gpu:-auto}; fonts=${fonts:-keep}
elif [ "$distro" = nixos ]; then
    gui=${gui:-plasma}; fonts=${fonts:-basic}
else gui=${gui:-apps}; fonts=${fonts:-basic}; fi
gpu=${gpu:-keep}
if [ "$resume" = yes ]; then locale_name=${locale_name:-keep}
else locale_name=${locale_name:-system}; fi
[ "$locale_name" != system ] || locale_name=$(android_locale)
zone=${zone:-keep}
dns=${dns:-system}
case "$name" in ''|[!A-Za-z0-9]*|*[!A-Za-z0-9_.-]*) fail 'Invalid environment name.' ;; esac
[ "${#name}" -le 64 ] || fail 'Name must be at most 64 characters.'
case "$gui" in none|apps|xfce|weston|plasma|gnome|keep) ;; *) fail 'Invalid GUI profile.' ;; esac
case "$protocol" in both|x11|wayland) ;; *) fail 'Protocol must be both, x11 or wayland.' ;; esac
case "$gpu" in keep|software|turnip|auto) ;; *) fail 'GPU must be keep, software, auto or turnip.' ;; esac
case "$jobs" in [1-8]) ;; *) fail 'Jobs must be between 1 and 8.' ;; esac
case "$locale_name" in
    keep|C.UTF-8) ;;
    *) printf '%s\n' "$locale_name" | grep -Eq '^[a-z]{2,3}_[A-Z]{2}\.UTF-8$' || fail 'Expected a UTF-8 locale such as en_US.UTF-8.' ;;
esac
if [ "$zone" = system ]; then
    command -v getprop >/dev/null 2>&1 || fail 'Android timezone is unavailable; supply an IANA timezone.'
    zone=$(getprop persist.sys.timezone)
    [ -n "$zone" ] || fail 'Android timezone is empty; supply an IANA timezone.'
fi
case "$zone" in ''|/*|*..*|*[!A-Za-z0-9_+/-]*) fail 'Invalid IANA timezone.' ;; esac
if [ -n "$new_user" ]; then
    printf '%s\n' "$new_user" | grep -Eq '^[a-z_][a-z0-9_-]{0,30}$' || fail 'Invalid guest account name.'
    [ "$new_user" != root ] || fail 'The root account already exists; --create-user creates an ordinary account.'
fi
case "$fonts" in basic|cjk|keep) ;; *) fail 'Fonts must be basic, cjk or keep.' ;; esac
case "$cache" in keep|clean) ;; *) fail 'Cache must be keep or clean.' ;; esac
if [ -n "$extras" ]; then
    printf '%s\n' "$extras" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9+_.-]*( [A-Za-z0-9][A-Za-z0-9+_.-]*)*$' || fail 'Expected repository package names, not options or shell commands.'
fi

# Read-only validation precedes every existing-environment mutation.
if [ "$resume" = yes ]; then
    [ "$distro" != nixos ] || nixos_bootstrap
    detected=$(admin_exec "$name" -- /bin/sh -c '. /etc/os-release; printf "%s\n" "$ID"')
    detected=$(distribution_id "$detected")
    [ -z "$distro" ] || [ "$distro" = "$detected" ] || fail 'Selected distribution does not match the existing environment.'
    distro=$detected
else
    distro=${distro:-debian}
fi
recipe_defaults
if [ "$distro" = steamos ]; then
    [ -z "$image" ] || fail 'SteamOS uses its pinned official recovery archive, not --image.'
    { [ "$gui" = keep ] || [ "$gui" = plasma ]; } && [ "$fonts" = keep ] && [ -z "$extras" ] \
        && [ "$cache" = keep ] || fail 'SteamOS supports plasma/keep with supplied applications and fonts.'
fi
if [ -n "$image" ]; then source_kind=oci; else image=$source; fi
[ "$gui" != gnome ] || [ "$distro" = fedora ] || fail 'The GNOME devkit recipe currently requires Fedora 44. Use apps, Xfce or Weston on other distributions.'
[ "$gui" != plasma ] || [ "$distro" = debian ] || [ "$distro" = steamos ] || [ "$distro" = nixos ] || fail 'The Plasma recipe requires Debian 13, SteamOS 0.3.0 or NixOS 26.05.'
[ "$gui" != none ] || [ "$gpu" = keep ] || fail 'Choose a GUI profile before installing graphics; use --gui keep for an existing GUI.'
[ "$resume" = yes ] || check_dns

printf '\n%s %s ARM64 | %s | GUI=%s | graphics=%s\n' "$distro" "$version" "$name" "$gui" "$gpu"
printf 'Locale=%s | timezone=%s | fonts=%s | application entries=%s\n' "$locale_name" "$zone" "$fonts" "$protocol"
printf 'Guest account=%s | extra packages=%s | package cache=%s\n' "${new_user:-unchanged}" "${extras:-none}" "$cache"
if [ "$gpu" = turnip ] || [ "$gpu" = auto ] || [ "$gui" = plasma ] || [ "$gui" = weston ]; then
    printf 'Compiler jobs for Mesa/KWin/Weston: %s\n' "$jobs"
fi
if [ "$resume" = yes ]; then printf 'Continue the existing environment without replacing user data.\n'
else printf 'Source: %s\nNetwork: %s\n' "$image" "$(dns_description)"; fi
[ "$distro" != armtix ] || printf 'Armtix retains its upstream repository signature policy (the supplied rootfs disables package signatures).\n'
[ "$gui" != gnome ] || printf 'GNOME development kit is experimental; it is not a booted systemd/GDM desktop.\n'
gui_notice
if [ "$yes" = no ]; then
    ask 'Continue? y/N' N
    case "$answer" in y|Y|yes|YES) ;; *) printf 'Cancelled.\n'; exit 0 ;; esac
fi

stage='checking Shroot'
ready=no
finish() {
    status=$?
    if [ -n "$import_work" ]; then
        if [ "$status" -eq 0 ] || [ "$ready" = yes ]; then
            if import_helper remove unpack && import_helper prune; then
                rm -rf "$import_work"
            else
                printf 'Temporary converter retained: %s\n' "$import_work" >&2
            fi
        else
            printf 'Image conversion work retained for inspection: %s\n' "$import_work" >&2
        fi
    fi
    if [ "$status" -ne 0 ]; then
        printf '\nInstallation stopped while %s (exit %s).\n' "$stage" "$status" >&2
        if [ "$ready" = yes ]; then
            printf 'Environment retained. Repeat your options with --name %s --resume (do not reinstall).\n' "$name" >&2
            printf 'GUI=%s GPU=%s jobs=%s locale=%s timezone=%s fonts=%s\n' \
                "$gui" "$gpu" "$jobs" "$locale_name" "$zone" "$fonts" >&2
        else
            printf 'Inspect shroot list before retrying an interrupted installation.\n' >&2
        fi
    fi
}
trap finish 0
trap 'exit 130' INT
trap 'exit 143' TERM
runtime_features=$(shroot --probe)
printf '%s\n' "$runtime_features"
if [ "$resume" = no ]; then
    case "$source_kind" in
        recovery) prepare_recovery ;;
        rootfs) prepare_rootfs ;;
    esac
    stage='installing the Linux image'
    case "$source_kind" in
        recovery) shroot install "$image" --oci --name "$name" --dns "$dns" ;;
        rootfs) shroot install "$image" --rootfs --name "$name" --dns "$dns" ;;
        oci) shroot install "$image" --name "$name" --dns "$dns" ;;
    esac
    if [ "$dns" != preserve ]; then
        stage='applying selected DNS to the new environment'
        shroot dns "$name" "$dns" --replace
    fi
    if [ "$distro" = nixos ]; then
        stage='activating the official NixOS image'
        nixos_bootstrap
    fi
fi
stage='validating the selected environment'
admin_exec "$name" -- /bin/sh -c '
    set -eu
    . /etc/os-release
    case "$ID" in archarm) ID=arch ;; opensuse-tumbleweed) ID=opensuse ;; manjaro-arm) ID=manjaro ;; artix) ID=armtix ;; esac
    [ "$ID" = "$1" ] || exit 1
    case "$ID" in
        debian|ubuntu|openkylin|deepin) [ "$VERSION_ID" = "$2" ] && [ "$(dpkg --print-architecture)" = arm64 ] ;;
        alpine) case "$VERSION_ID" in "$2".*) ;; *) exit 1 ;; esac; [ "$(apk --print-arch)" = aarch64 ] ;;
        fedora) [ "$VERSION_ID" = "$2" ] && [ "$(rpm --eval "%{_arch}")" = aarch64 ] ;;
        opensuse) [ "$(rpm --eval "%{_arch}")" = aarch64 ] ;;
        arch|manjaro|armtix) [ "$(uname -m)" = aarch64 ] ;;
        void) [ "$(xbps-uhelper arch)" = aarch64 ] ;;
        steamos) [ "$VERSION_ID" = "$2" ] && [ "$(uname -m)" = aarch64 ] ;;
        nixos) [ "$VERSION_ID" = "$2" ] && [ -d /nix/store ] ;;
    esac || { echo "Unexpected distribution version or architecture; setup was not applied." >&2; exit 1; }
' sh "$distro" "$version"
ready=yes
setup_exec() {
    set -- -- /bin/sh -s -- "$@"
    [ -z "${setup_phase:-}" ] || set -- --env MAGICDESK_BUILD_PHASE="$setup_phase" "$@"
    package_exec "$name" "$@"
}
setup_build() {
    build_label=$1
    build_payload=$2
    shift 2
    unset setup_phase
    build_recipe=$("$build_payload" | sha256sum)
    build_recipe=${build_recipe%% *}
    stage="installing build dependencies for $build_label"
    setup_phase=dependencies
    "$build_payload" | setup_exec "$@" "$build_recipe"
    setup_phase=build
    stage="preparing the $build_label"
    "$build_payload" | setup_exec "$@" "$build_recipe"
    unset setup_phase
}
stage='installing packages and guest settings'
install_guest_settings() {
    if [ "$distro" = nixos ]; then
        nixos_setup | setup_exec "$gui" "$protocol" "$locale_name" "$zone" "$new_user" "$fonts" "$cache" "$extras" "$gpu" "$jobs"
    else
        system_setup | setup_exec "$gui" "$protocol" "$locale_name" "$zone" "$new_user" "$fonts" "$cache" "$extras"
    fi
}
while :; do
    if install_guest_settings; then
        break
    else
        setup_status=$?
    fi
    [ "$yes" = no ] || exit "$setup_status"
    printf '\nPackage setup failed (exit %s). The environment and your choices are retained.\n' "$setup_status" >&2
    printf 'Review the error above. Changing DNS only helps resolver problems.\n' >&2
    while :; do
        ask 'Package setup: retry, dns (change resolver and retry), or cancel' cancel
        case "$answer" in
            retry) break ;;
            dns)
                ask_dns "$dns"
                check_dns
                [ "$dns" = preserve ] || shroot dns "$name" "$dns" --replace
                break ;;
            cancel) exit "$setup_status" ;;
            *) printf 'Choose retry, dns, or cancel.\n' >&2 ;;
        esac
    done
done

if [ "$distro" = steamos ]; then
    stage='preparing the supplied Steam client'
    printf 'Preparing the supplied Steam client for the steamos account...\n'
    shroot exec "$name" -- /bin/sh -c '
        set -eu
        [ "$(id -un)" = steamos ]
        mkdir -p "$HOME/.steam" "$HOME/.local/share"
        exec 9>"$HOME/.steam/installer.lock"
        flock -n 9 || { echo "Another Steam setup is running." >&2; exit 1; }
        if [ ! -d "$HOME/.local/share/Steam" ]; then
            work=$(mktemp -d "$HOME/.local/share/.steam-XXXXXX")
            trap '\''rm -rf "$work"'\'' 0
            tar --no-same-owner --zstd --checkpoint=10000 -xf /usr/lib/steam/steam.tar.zst -C "$work"
            [ -x "$work/steamrtarm64/steam" ]
            mv "$work" "$HOME/.local/share/Steam"
        fi
        if [ ! -e "$HOME/.steam/steam" ] && [ ! -L "$HOME/.steam/steam" ]; then
            ln -s ../.local/share/Steam "$HOME/.steam/steam"
        fi
        # Desktop launches need the SDK paths too, without RUNSTEAM session policy.
        for link in root sdk32 sdk64 sdkarm64 bin32 bin64 binarm64; do
            case "$link" in
                root) target=. ;;
                sdk32) target=linux32 ;; sdk64) target=linux64 ;; sdkarm64) target=linuxarm64 ;;
                bin32) target=ubuntu12_32 ;; bin64) target=ubuntu12_64 ;; binarm64) target=steamrtarm64 ;;
            esac
            if [ ! -e "$HOME/.steam/$link" ] && [ ! -L "$HOME/.steam/$link" ]; then
                ln -s "steam/$target" "$HOME/.steam/$link"
            fi
        done
    '
fi

if [ "$distro" != nixos ] && [ "$gpu" != keep ]; then
    setup_build "$gpu graphics profile" graphics_setup "$gpu" "$jobs"
fi

if [ "$distro" != nixos ] && { [ "$gui" = plasma ] || [ "$gpu" != keep ]; }; then
    setup_build 'nested Plasma compositor' plasma_setup "$jobs"
fi

if [ "$distro" != nixos ] && { [ "$gui" = weston ] || [ "$gui" = keep ]; }; then
    setup_build 'Weston compatibility' weston_setup "$jobs"
fi

stage=complete
printf '\nLinux is ready: %s (%s %s)\n' "$name" "$distro" "$version"
gui_notice
printf '\nNext steps:\n'
if [ "$gui" != none ]; then
    printf 'In MagicDesk Control Panel, select a display and open Apps (Start).\n'
    printf 'Press Refresh beside the search field to load installed Linux entries.\n'
    printf 'Audio connects to Android automatically. Desktop volume controls use their private PipeWire graph.\n'
    printf 'Android gamepads are available in the focused Shroot window, on X11 and Wayland.\n'
fi
case "$gui" in
    none) printf 'No GUI was installed by this run. Use a terminal below.\n' ;;
    keep) printf 'Existing GUI entries were preserved; choose an installed application or desktop.\n' ;;
    *)
        app_backends='x11 wayland'
        [ "$distro" != steamos ] || app_backends=
        for backend in $app_backends; do
            if [ "$protocol" = both ] || [ "$protocol" = "$backend" ]; then
                printf 'Individual apps: Mousepad (%s), Thunar (%s), Xfce Terminal (%s).\n' "$backend" "$backend" "$backend"
            fi
        done
        case "$gui" in
            apps) printf 'The apps profile adds individual applications, not a whole Linux desktop.\n' ;;
            xfce) printf 'Whole desktop: search for Xfce Desktop (X11), environment %s.\n' "$name" ;;
            weston) printf 'Whole desktop: search for Weston Desktop (Wayland), environment %s.\n' "$name" ;;
            plasma) printf 'Whole desktop: search for Plasma Desktop (x11) or Plasma Desktop (wayland), environment %s.\n' "$name" ;;
            gnome) printf 'Experimental desktop: search for GNOME Shell (development kit), environment %s; requires a Linux system bus.\n' "$name" ;;
        esac ;;
esac
printf 'Terminal: Terminal sessions > New session > Shroot environments > %s > New session.\n' "$name"
printf 'Keep privileged access available; no MagicDesk Desktop session or installer rerun is needed.\n'
printf 'Console: shroot login %s\n' "$name"
[ "$distro" != steamos ] || printf '%s\n' 'SteamOS uses the steamos guest account. Open Steam from Apps; its own updater may download client components.' 'For x86 Linux games using FEX, set Steam Launch Options: magicdesk-steam-game %command%' 'This installs Linux userspace, not SteamOS boot, recovery or VR services. GPU/game compatibility is a separate check.'
if [ -n "$new_user" ]; then printf 'User console: shroot login %s --user %s\n' "$name" "$new_user"; fi
printf 'Folder attachment: shroot login %s --bind /sdcard/Download /mnt\n' "$name"
printf 'Optional Android command access: shroot login %s --magicdesk\n' "$name"
printf 'Backup (close its applications first): shroot backup %s /sdcard/Download/%s.tar.zst\n' "$name" "$name"
if [ "$gpu" = turnip ]; then
    printf 'Software fallback: sh install_linux.sh --name %s --resume --gpu software\n' "$name"
fi
