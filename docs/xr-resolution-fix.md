# VITURE XR resolution fix

## Problem

On the REDMAGIC 11 Pro (`NX809J`), VITURE glasses can change their EDID through
an HPD-low/HPD-high transition when entering side-by-side 3D. The original
failure was reproduced with a `1920x1080` to `3840x1080@60` transition. VITURE
Beast currently exposes `1920x1200` in 2D and can use a corresponding wider
1200-line mode in 3D. Nubia's DisplayPort driver keeps a user-selected mode and
a `mode_override` flag in the DP panel state.

The full USB-C disconnect path clears that flag, but the HPD-low path used by
the glasses does not. There is also a second stale-state path:
`nubia_edid_modes()` reapplies the saved mode even when it no longer exists in
the new EDID. The valid 3D mode is rejected, DRM has no accepted EDID modes, and
Android falls back to `640x480`. In the glasses this normally appears as a
black screen.

The module does not contain hardcoded 1080p or 1200p dimensions. It compares
the complete saved mode against the connector's current EDID mode list, so the
same stale-state correction applies to both panel heights.

## Runtime solution

The source in [`kernel/xr-resolution-fix/dp_mode_reset.c`](../kernel/xr-resolution-fix/dp_mode_reset.c)
registers two temporary kernel probes:

1. A `kprobe` on `dp_display_disconnect_sync` clears `mode_override` on HPD low
   and remembers the active DP panel.
2. A return probe on `nubia_edid_modes` compares the saved width, height,
   refresh rate, and aspect ratio with the connector's newly probed modes. It
   clears the override only when no exact mode still exists.

This preserves a valid manual mode choice. There is no userspace polling,
artificial hotplug, timing delay, boot image patch, or Magisk module.

The main MagicDesk APK does not contain a kernel module. The optional,
separately installed **MagicDesk Kernel Fixes** APK packages the tested module
as
[`kernel-fixes/src/main/res/raw/dp_mode_reset.ko`](../kernel-fixes/src/main/res/raw/dp_mode_reset.ko).
MagicDesk exposes **Tools > Kernel fixes** only when the add-on is installed
with the same signing certificate. The add-on's **Activate** button:

1. Requires an explicit user confirmation.
2. Extracts that resource into the add-on's private no-backup directory.
3. Verifies the embedded module SHA-256 in Java.
4. Uses root to verify the exact kernel release and stock `msm_drm.ko` SHA-256.
5. Copies the module to `/data/local/tmp`, executes `insmod`, and immediately
   removes the temporary copy.
6. Treats a repeated press as an idempotent `already active` operation.

The module lives only in kernel memory. It must be activated once after every
reboot, before switching the glasses into 3D. A reboot unloads it automatically.

## Validated target

- Device: REDMAGIC 11 Pro (`NX809J`)
- Displays: VITURE Pro XR transition validated; VITURE Beast `1920x1200` 2D
  mode observed, with its 3D transition requiring a fresh validation pass
- Kernel release:
  `6.12.23-android16-5-gf1bdb13583da-ab13761046-4k`
- Android common-kernel commit:
  `f1bdb13583da85a47fcf1632a78ef52d6e6da651`
- Android CI build: `13761046`, target `kernel_aarch64`
- Stock `msm_drm.ko` SHA-256:
  `6658f1464f33cc09feefba77f5ed026dfeb6af8bbcd96e20b5a93a33288df577`
- Packaged `dp_mode_reset.ko` SHA-256:
  `f1abf9dfece5b175801194c9a32bba08d6c1d913d16c73e4bc9db332613e043d`

The module uses OEM structure offsets and kernel symbol CRCs. Do not weaken the
checks in `XrResolutionFix.java`. After a firmware update, inspect the new
driver and rebuild the module before adding the new hashes. Loading a module
with incorrect structure offsets can crash the kernel. The current mechanism
does not persist across reboot, so a normal reboot is the recovery path if the
runtime driver becomes unstable.

## Source layout

All MagicDesk-owned files required to reproduce and package the fix are in this
repository:

- `kernel/xr-resolution-fix/dp_mode_reset.c`: module source
- `kernel/xr-resolution-fix/Makefile`: external Kbuild definition
- `kernel/xr-resolution-fix/CHECKSUMS`: validated binary identities
- `scripts/build-xr-resolution-fix.sh`: guarded build command
- `kernel-fixes/src/main/res/raw/dp_mode_reset.ko`: tested add-on prebuilt
- `kernel-fixes/src/main/java/io/github/mekhontsev/magicdesk/kernel/XrResolutionFix.java`:
  validation and one-shot root loader

The upstream Android kernel tree, official `.config`, and official
`Module.symvers` are intentionally not copied into the application repository.
They are large upstream build inputs and must match the identifiers above.

## Rebuild in Termux

The Android kernel build expects a normal glibc Linux host. On this device it
was built inside Ubuntu from `proot-distro`, while the MagicDesk project stays
in the Termux home directory.

Install the container:

```sh
pkg install proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
```

Inside Ubuntu, install the build tools:

```sh
apt-get update
apt-get install -y build-essential clang lld bc bison flex git \
  libelf-dev libdw-dev libssl-dev dwarves rsync python3 pkg-config
```

Fetch the exact common-kernel commit:

```sh
git clone --filter=blob:none --no-checkout \
  https://android.googlesource.com/kernel/common common
git -C common fetch --depth=1 origin \
  f1bdb13583da85a47fcf1632a78ef52d6e6da651
git -C common checkout --detach FETCH_HEAD
```

Open the official Android CI artifact page for build `13761046`, target
`kernel_aarch64`:

```text
https://ci.android.com/builds/submitted/13761046/kernel_aarch64/latest
```

Download these two artifacts into one directory, for example
`gki-13761046/`:

```text
kernel_aarch64_dot_config
kernel_aarch64_Module.symvers
```

Run the guarded build script from the MagicDesk repository:

```sh
./scripts/build-xr-resolution-fix.sh \
  /absolute/path/to/common \
  /absolute/path/to/gki-13761046
```

The script verifies the kernel Git commit, configures the exact GKI output,
copies the official symbol CRC table, and builds
`kernel/xr-resolution-fix/dp_mode_reset.ko`. It creates an empty
`protected_module_names_list` only for the duration of this external-module
build because that generated Bazel input is not stored in the common-kernel
Git tree.

Do not package a fresh build immediately. First load it manually on the exact
target firmware, perform repeated 2D/3D and disconnect/reconnect tests, and
inspect the counters:

```sh
su -c 'insmod /data/local/tmp/dp_mode_reset.ko'
su -c 'cat /sys/module/dp_mode_reset/parameters/disconnect_hits'
su -c 'cat /sys/module/dp_mode_reset/parameters/stale_override_hits'
su -c 'rmmod dp_mode_reset'
```

After validation, replace the APK resource and update the hardcoded module
checksum:

```sh
cp kernel/xr-resolution-fix/dp_mode_reset.ko \
  kernel-fixes/src/main/res/raw/dp_mode_reset.ko
sha256sum kernel-fixes/src/main/res/raw/dp_mode_reset.ko
```

Set that digest as `EXPECTED_MODULE_SHA256` in `XrResolutionFix.java`, rebuild
the `kernel-fixes` APK, and verify that the button both activates the fix and
reports an already loaded module on a second press.
