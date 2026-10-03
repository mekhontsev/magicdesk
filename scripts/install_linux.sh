#!/bin/sh
# Run from a saved file in MagicDesk's Shell console; stdin is used for questions.
set -eu

usage() {
    cat <<'HELP'
Usage: sh install_linux.sh [--distro debian|ubuntu|alpine|fedora|arch] [OPTIONS]

Install an ARM64 Linux userspace in Shroot. Choose applications or a desktop
independently of the distribution. Installation output stays in this console.
New environments use software rendering unless Turnip is explicitly selected.

  --distro ID   Debian 13 (default), Ubuntu 24.04, Alpine 3.23, Fedora 44,
                or rolling Arch Linux ARM (community menci/archlinuxarm image)
  --name NAME   Independent environment name (default: distribution name)
  --image REF   Alternative OCI reference for the SAME distribution/version
  --gui MODE    none, apps (default), xfce, weston, gnome (Fedora devkit), keep
                apps: Mousepad, Thunar, Xfce Terminal; xfce: X11 desktop;
                weston: Wayland desktop; gnome: experimental nested shell
  --protocol P  x11, wayland, both (default); installer application entries
  --gpu keep    Retain the graphics profile (default)
  --gpu software  Use the distribution's software renderer
  --gpu turnip  Build private Mesa/Zink/Turnip for a supported Adreno KGSL GPU
  --jobs N      Mesa compiler jobs, 1-8 (default: 2)
  --locale L    UTF-8 locale, e.g. ru_RU.UTF-8, or keep (default)
  --timezone Z  IANA zone, system (Android zone), or keep (default)
  --create-user USER  Create an optional guest account, with a locked password
                Select it later with login --user or the shortcut User field;
                this does not change the image account or grant Android root
  --fonts F    basic (default), cjk (also Chinese/Japanese/Korean), keep
  --package P   Additional repository package (repeatable)
  --cache C     keep (default), clean (package downloads only, not Mesa work)
  --arch-sandbox P  keep (default), disable-filesystem (explicit pacman opt-out)
  --dns POLICY  New image DNS: system (default), preserve, or IP[,IP...]
  --yes         Accept the selected configuration without questions
  --resume      Continue in --name NAME; detect distribution, preserve GUI by default
  --list        Show available distribution/profile combinations
  --print-mesa-patch  Print the embedded patch without running the installer

Turnip builds inside Linux, needs several GB of free space and can take a while.
The source version and SHA-256 are pinned; no Android or distribution drivers are replaced.
Failed builds retain the previous profile and can be resumed. New launches use
the selected profile; already running programs are not restarted.

No Termux or Android root is required. Use trusted images: Shroot is not a
security sandbox. The current shell/root identity is never changed.
Fedora/Arch GUI recipes are experimental: Glycin/Bubblewrap can fail on Android.
GNOME devkit additionally needs a working Linux system bus.
HELP
}

configurations() {
    printf '%s\n' 'Distribution  Version     Source                                   GUI' \
        'debian        13          debian:trixie-slim                        none/apps/xfce/weston' \
        'ubuntu        24.04       ubuntu:24.04                              none/apps/xfce/weston' \
        'alpine        3.23        alpine:3.23                               none/apps/xfce/weston' \
        'fedora        44          registry.fedoraproject.org/fedora:44       none/apps/xfce/weston/gnome' \
        'arch          rolling     menci/archlinuxarm:base (community)        none/apps/xfce/weston'
    printf '%s\n' 'Fedora/Arch GUI: experimental (Glycin/Bubblewrap); GNOME also requires a Linux system bus.'
}

# Package-manager differences stay here; setup and the Mesa recipe share them.
distribution_support() {
    cat <<'DISTRO'
set -eu
umask 022
. /etc/os-release
case "$ID" in
    debian|ubuntu) family=apt ;;
    alpine) family=apk ;;
    fedora) family=dnf ;;
    arch|archarm) family=pacman ;;
    *) echo "Unsupported distribution: $ID" >&2; exit 1 ;;
esac
pacman_command() {
    if [ "${arch_sandbox:-keep}" = disable-filesystem ]; then
        pacman --disable-sandbox-filesystem "$@" </dev/null
    else
        pacman "$@" </dev/null
    fi
}
packages() {
    case "$family" in
        apt) apt-get install -y --no-install-recommends "$@" </dev/null ;;
        apk) apk add "$@" </dev/null ;;
        dnf) dnf -y --setopt=install_weak_deps=False install "$@" </dev/null ;;
        pacman) pacman_command -S --needed --noconfirm "$@" ;;
    esac
}
package_cleanup() {
    # pacman-key's service is scoped to the package keyring, not a user's GPG home.
    if [ "$family" = pacman ] && command -v gpgconf >/dev/null 2>&1; then
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
The EGLDevice convention matches platform_x11_finalize(force_zink).

--- a/src/egl/drivers/dri2/platform_wayland.c
+++ b/src/egl/drivers/dri2/platform_wayland.c
@@ -3262,7 +3262,7 @@ dri2_initialize_wayland_swrast(_EGLDisplay *disp)
    if (!dri2_create_screen(disp))
       goto cleanup;

-   if (!dri2_setup_device(disp, disp->Options.ForceSoftware)) {
+   if (!dri2_setup_device(disp, disp->Options.ForceSoftware || disp->Options.Zink)) {
       _eglError(EGL_NOT_INITIALIZED, "DRI2: failed to setup EGLDevice");
       goto cleanup;
    }
@@ -3297,7 +3297,7 @@ dri2_initialize_wayland_swrast(_EGLDisplay *disp)
 EGLBoolean
 dri2_initialize_wayland(_EGLDisplay *disp)
 {
-   if (disp->Options.ForceSoftware)
+   if (disp->Options.ForceSoftware || disp->Options.Zink)
       return dri2_initialize_wayland_swrast(disp);
    else
       return dri2_initialize_wayland_drm(disp);
PATCH
}

# One payload is both fingerprinted and executed; the lab exports the same patch.
graphics_setup() {
    distribution_support
    cat <<'GPU_HEAD'
set -eu
umask 022
gpu=$1
jobs=$2
recipe=$3
arch_sandbox=$4
version=26.2.3
archive_sha=1628058a8d2c0615975de5a15ab7bbb9638c50000b5bed9456ff423ea034a81f
mkdir -p /var/lib/magicdesk /etc/profile.d
exec 9>/var/lib/magicdesk/installer.lock
flock -n 9 || { echo 'Another graphics setup is running in this environment.' >&2; exit 1; }
profile=/etc/profile.d/magicdesk-graphics.sh
candidate=$profile.new
trap 'status=$?; rm -f "$candidate"; package_cleanup; exit "$status"' 0
trap 'exit 130' INT
trap 'exit 143' TERM

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
    case "$family" in
    apt) packages build-essential meson ninja-build pkg-config \
        python3-mako python3-yaml python3-ply bison flex patch curl xz-utils glslang-tools \
        libdrm-dev libexpat1-dev libzstd-dev zlib1g-dev libelf-dev \
        libwayland-dev libwayland-egl-backend-dev wayland-protocols libglvnd-dev libvulkan-dev vulkan-tools mesa-utils \
        libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxxf86vm-dev \
        libxrandr-dev libxshmfence-dev libxcb-glx0-dev libxcb-randr0-dev \
        libxcb-shm0-dev libxcb-dri3-dev libxcb-present-dev libxcb-sync-dev \
        libxcb-xfixes0-dev ;;
    apk) packages build-base meson ninja pkgconf python3 py3-mako py3-yaml py3-ply bison flex \
        patch curl xz glslang-dev linux-headers libdrm-dev expat-dev zstd-dev zlib-dev elfutils-dev \
        wayland-dev wayland-protocols libglvnd-dev vulkan-headers vulkan-tools \
        libx11-dev libxext-dev libxfixes-dev libxxf86vm-dev libxrandr-dev libxshmfence-dev libxcb-dev ;;
    dnf) packages gcc gcc-c++ meson ninja-build pkgconf-pkg-config python3-mako python3-pyyaml \
        python3-ply bison flex patch curl xz glslang libdrm-devel expat-devel libzstd-devel zlib-ng-compat-devel \
        elfutils-libelf-devel wayland-devel wayland-protocols-devel libglvnd-devel vulkan-headers vulkan-tools \
        libX11-devel libXext-devel libXfixes-devel libXxf86vm-devel libXrandr-devel libxshmfence-devel libxcb-devel ;;
    pacman) packages base-devel meson ninja pkgconf python-mako python-yaml python-ply bison flex \
        patch curl xz glslang libdrm expat zstd zlib libelf wayland wayland-protocols libglvnd vulkan-headers \
        vulkan-tools libx11 libxext libxfixes libxxf86vm libxrandr libxshmfence libxcb ;;
    esac
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
            tar -xJf "$archive" -C "$work/unpack"
            source_tmp=$work/unpack/mesa-$version
            patch --batch --forward --fuzz=0 -p1 -d "$source_tmp" < "$work/wayland-zink.patch"
            touch "$source_tmp/.patched"
            mv "$source_tmp" "$source"
            rmdir "$work/unpack"
        fi
        # Both KMDs are needed: Vulkan WSI also uses the DRM image allocator.
        set -- --prefix="$prefix" --libdir=lib --buildtype=release --wrap-mode=nodownload \
            -Dauto_features=disabled -Dplatforms=x11,wayland -Dllvm=disabled \
            -Dxmlconfig=disabled -Dbuild-tests=false -Dgallium-rusticl=false \
            -Dvulkan-drivers=freedreno -Dfreedreno-kmds=msm,kgsl \
            -Dgallium-drivers=zink,softpipe -Dopengl=true -Dgles1=enabled -Dgles2=enabled -Dglx=dri \
            -Degl=enabled -Dgbm=enabled -Dglvnd=enabled -Dgallium-va=disabled
        if [ -f "$work/build/build.ninja" ]; then
            meson setup --reconfigure "$work/build" "$source" "$@" </dev/null
        else
            meson setup "$work/build" "$source" "$@" </dev/null
        fi
        ninja -C "$work/build" -j "$jobs" </dev/null
        # Install off to the side; a resumed/failed installation cannot alter active libraries.
        rm -rf "$work/stage"
        meson install -C "$work/build" --no-rebuild --destdir "$work/stage" </dev/null
        mkdir -p /opt/magicdesk/mesa
        [ ! -e "$prefix" ] || { echo "Incomplete prefix exists: $prefix" >&2; exit 1; }
        printf '%s\n' "$recipe" > "$work/stage$prefix/.complete"
        mv "$work/stage$prefix" "$prefix"
    fi
    [ "$(cat "$prefix/.complete")" = "$recipe" ] || { echo 'Mesa build identity mismatch.' >&2; exit 1; }
fi

# Remove only our previously exported library directory when re-sourcing a login profile.
cat > "$candidate" <<'PROFILE'
if [ -n "${MAGICDESK_MESA_PREFIX:-}" ]; then
    _md_paths=${LD_LIBRARY_PATH:-}
    _md_kept=
    while [ -n "$_md_paths" ]; do
        _md_item=${_md_paths%%:*}
        case $_md_paths in *:*) _md_paths=${_md_paths#*:} ;; *) _md_paths= ;; esac
        if [ -n "$_md_item" ] && [ "$_md_item" != "$MAGICDESK_MESA_PREFIX/lib" ]; then
            _md_kept=${_md_kept:+$_md_kept:}$_md_item
        fi
    done
    if [ -n "$_md_kept" ]; then export LD_LIBRARY_PATH=$_md_kept; else unset LD_LIBRARY_PATH; fi
    unset _md_paths _md_kept _md_item
fi
unset MAGICDESK_MESA_PREFIX LIBGL_ALWAYS_SOFTWARE GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE
unset LIBGL_DRIVERS_PATH LIBGL_KOPPER_DRI2 __EGL_VENDOR_LIBRARY_FILENAMES VK_DRIVER_FILES VK_ICD_FILENAMES
PROFILE
if [ "$gpu" = turnip ]; then
    printf 'export MAGICDESK_MESA_PREFIX=%s\n' "$prefix" >> "$candidate"
    cat >> "$candidate" <<'PROFILE'
export LD_LIBRARY_PATH="$MAGICDESK_MESA_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LIBGL_DRIVERS_PATH="$MAGICDESK_MESA_PREFIX/lib/dri"
export __EGL_VENDOR_LIBRARY_FILENAMES="$MAGICDESK_MESA_PREFIX/share/glvnd/egl_vendor.d/50_mesa.json"
export VK_DRIVER_FILES="$MAGICDESK_MESA_PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json"
export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink LIBGL_KOPPER_DRI2=true
PROFILE
    # EVENT_WAIT: Vulkan enumeration exits; timeout rejects a hung driver, never activates it.
    /bin/sh -c '. "$1"; unset DISPLAY WAYLAND_DISPLAY; exec timeout 30 vulkaninfo --summary' sh "$candidate" \
        > "$work/vulkan-probe.log" 2>&1 || { cat "$work/vulkan-probe.log"; exit 1; }
    cat "$work/vulkan-probe.log"
    grep -Eq 'driverID[[:space:]]*=[[:space:]]*DRIVER_ID_MESA_TURNIP' "$work/vulkan-probe.log" || {
        echo 'No Turnip GPU found; the graphics profile is unchanged.' >&2; exit 1;
    }
else
    printf 'export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe\n' >> "$candidate"
fi
chmod 644 "$candidate"
mv -f "$candidate" "$profile"
printf 'Graphics profile activated for new launches: %s\n' "$gpu"
GPU_BODY
}

system_setup() {
    distribution_support
    cat <<'SETUP'
gui=$1
protocol=$2
locale_name=$3
zone=$4
new_user=$5
fonts=$6
cache=$7
arch_sandbox=$8
extras=$9
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
        apt-get update </dev/null
        packages ca-certificates bash coreutils util-linux tzdata locales passwd ;;
    apk)
        apk update </dev/null
        packages ca-certificates bash coreutils util-linux tzdata musl-locales musl-locales-lang lang shadow ;;
    dnf) packages ca-certificates bash coreutils util-linux tzdata glibc-langpack-en shadow-utils ;;
    pacman)
        pacman-key --init </dev/null
        pacman-key --populate archlinuxarm </dev/null
        # Rolling repositories require a complete userspace upgrade, not pacman -Sy.
        pacman_command -Syu --noconfirm
        packages ca-certificates bash coreutils util-linux tzdata ;;
esac
mkdir -p /var/lib/magicdesk /etc/profile.d /usr/local/bin /usr/local/share/applications
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
            apt|pacman) localedef -i "${locale_name%.UTF-8}" -f UTF-8 "$locale_name" ;;
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

if [ "$gui" != none ] && [ "$gui" != keep ]; then
    case "$family" in
        apt) packages dbus-x11 xkb-data adwaita-icon-theme mousepad thunar xfce4-terminal \
            libgl1-mesa-dri libegl-mesa0 libglx-mesa0 ;;
        apk) packages dbus dbus-x11 xkeyboard-config adwaita-icon-theme mousepad thunar xfce4-terminal \
            mesa-dri-gallium mesa-egl mesa-gl ;;
        dnf) packages dbus-daemon dbus-x11 xkeyboard-config adwaita-icon-theme mousepad Thunar xfce4-terminal \
            mesa-dri-drivers mesa-libEGL mesa-libGL ;;
        pacman) packages dbus xkeyboard-config adwaita-icon-theme mousepad thunar xfce4-terminal mesa ;;
    esac
    dbus-uuidgen --ensure
    case "$gui:$family" in
        xfce:apt|xfce:apk) packages xfce4 ;;
        xfce:dnf) packages xfce4-session xfce4-panel xfce4-settings xfce4-appfinder xfdesktop xfwm4 ;;
        xfce:pacman) packages xfce4 ;;
        weston:apk) packages weston weston-shell-desktop weston-backend-wayland weston-clients weston-xwayland xwayland ;;
        weston:apt) packages weston xwayland ;;
        weston:dnf) packages weston xorg-x11-server-Xwayland ;;
        weston:pacman) packages weston xorg-xwayland ;;
        gnome:dnf) packages gnome-shell mutter-devkit gnome-terminal nautilus gnome-text-editor ;;
    esac

    # Protocol-specific entries are ours; distribution/user launchers are never rewritten.
    for backend in x11 wayland; do
        for app in mousepad thunar terminal; do
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
    if [ "$gui" = xfce ]; then
        cat > /usr/local/share/applications/magicdesk-xfce.desktop <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Xfce Desktop
Exec=env XDG_CURRENT_DESKTOP=XFCE XDG_SESSION_DESKTOP=xfce xfce4-session
Icon=org.xfce.xfdesktop
Terminal=false
Categories=System;
X-MagicDesk-Graphics=x11
X-MagicDesk-GraphicsMode=desktop
ENTRY
        command -v xfce4-session
    fi
    if [ "$gui" = weston ]; then
        cat > /usr/local/bin/magicdesk-weston <<'LAUNCH'
#!/bin/sh
set -eu
renderer=pixman
[ -z "${MAGICDESK_MESA_PREFIX:-}" ] || renderer=gl
mkdir -p -m 1777 /tmp/.X11-unix
exec weston --backend=wayland --renderer="$renderer" --shell=desktop-shell.so --xwayland
LAUNCH
        chmod 755 /usr/local/bin/magicdesk-weston
        cat > /usr/local/share/applications/magicdesk-weston.desktop <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Weston Desktop
Exec=magicdesk-weston
Icon=computer
Terminal=false
Categories=System;
X-MagicDesk-Graphics=wayland
X-MagicDesk-GraphicsMode=desktop
ENTRY
        command -v weston
    fi
    if [ "$gui" = gnome ]; then
        cat > /usr/local/share/applications/magicdesk-gnome.desktop <<'ENTRY'
[Desktop Entry]
Type=Application
Name=GNOME Shell (development kit)
Exec=env XDG_CURRENT_DESKTOP=GNOME gnome-shell --devkit
Icon=computer
Terminal=false
Categories=System;
X-MagicDesk-Graphics=wayland
X-MagicDesk-GraphicsMode=desktop
ENTRY
        command -v gnome-shell
    fi
    if [ ! -e /etc/profile.d/magicdesk-graphics.sh ]; then
        printf 'export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe\n' > /etc/profile.d/magicdesk-graphics.sh
    fi
    command -v mousepad
    command -v thunar
    command -v xfce4-terminal
fi

if [ "$fonts" != keep ]; then
    case "$family" in
        apt) packages fonts-dejavu-core; [ "$fonts" != cjk ] || packages fonts-noto-cjk ;;
        apk) packages font-dejavu; [ "$fonts" != cjk ] || packages font-noto-cjk ;;
        dnf) packages dejavu-sans-fonts; [ "$fonts" != cjk ] || packages google-noto-sans-cjk-fonts ;;
        pacman) packages ttf-dejavu; [ "$fonts" != cjk ] || packages noto-fonts-cjk ;;
    esac
fi
# Names were validated before any image mutation; no evaluation of package text.
if [ -n "$extras" ]; then
    set -f
    packages $extras
    set +f
fi
if [ "$cache" = clean ]; then
    case "$family" in
        apt) apt-get clean ;;
        apk) rm -f /var/cache/apk/*.apk ;;
        dnf) dnf clean packages ;;
        pacman) pacman_command -Sc --noconfirm ;;
    esac
fi
SETUP
}

fail() { printf '%s\n' "$*" >&2; exit 1; }
ask() {
    printf '%s [%s]: ' "$1" "$2"
    IFS= read -r answer || fail 'Input closed.'
    answer=${answer:-$2}
}
recipe_defaults() {
    case "$distro" in
        debian) source=debian:trixie-slim; version=13 ;;
        ubuntu) source=ubuntu:24.04; version=24.04 ;;
        alpine) source=alpine:3.23; version=3.23 ;;
        fedora) source=registry.fedoraproject.org/fedora:44; version=44 ;;
        arch) source=menci/archlinuxarm:base; version=rolling ;;
        *) fail 'Distribution must be debian, ubuntu, alpine, fedora or arch.' ;;
    esac
}
gui_notice() {
    case "$distro:$gui" in
        fedora:none|arch:none) ;;
        fedora:*|arch:*)
            printf 'WARNING: Fedora/Arch GUI may fail in the Glycin/Bubblewrap image loader on Android.\n'
            printf 'No image-loader or browser sandbox is disabled by this recipe.\n' ;;
    esac
    [ "$gui" != gnome ] || printf 'GNOME devkit also needs a Linux system bus; this recipe does not boot one.\n'
}
if [ "$#" = 1 ] && [ "$1" = --print-mesa-patch ]; then mesa_patch; exit 0; fi
distro=
name=
image=
gui=
protocol=both
gpu=
jobs=2
locale_name=
zone=
new_user=
fonts=
cache=keep
arch_sandbox=keep
extras=
yes=no
resume=no
dns=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --distro|--name|--image|--gui|--protocol|--gpu|--jobs|--locale|--timezone|--create-user|--fonts|--cache|--arch-sandbox|--package|--dns)
            [ "$#" -ge 2 ] && [ -n "$2" ] || fail "Missing value for $1"
            case "$1" in
                --distro) distro=$2 ;; --name) name=$2 ;; --image) image=$2 ;; --gui) gui=$2 ;;
                --protocol) protocol=$2 ;; --gpu) gpu=$2 ;; --jobs) jobs=$2 ;; --locale) locale_name=$2 ;;
                --timezone) zone=$2 ;; --create-user) new_user=$2 ;; --fonts) fonts=$2 ;;
                --cache) cache=$2 ;; --arch-sandbox) arch_sandbox=$2 ;;
                --package) extras="${extras:+$extras }$2" ;; --dns) dns=$2 ;;
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
    [ -z "$dns" ] && [ -z "$image" ] || fail '--resume retains image and DNS; use magicdesk-guest dns to change DNS.'
fi

command -v magicdesk-guest >/dev/null 2>&1 || fail 'Open a MagicDesk Shell console with shell access first.'
case "$(id -u)" in 2000|0) ;; *) fail 'Shroot requires the selected Android shell (UID 2000) or root executor.' ;; esac
if [ "$yes" = no ]; then
    [ -t 0 ] || fail 'Interactive input is unavailable. Save the script before running it, or use --yes.'
    if [ "$resume" = no ]; then
        configurations
        if [ -z "$distro" ]; then ask 'Distribution' debian; distro=$answer; fi
    fi
    if [ -z "$name" ]; then ask 'Independent environment name' "${distro:-debian}"; name=$answer; fi
    if [ -z "$gui" ]; then
        default_gui=apps; [ "$resume" = no ] || default_gui=keep
        ask 'GUI: none/apps/xfce/weston/gnome (Fedora devkit)/keep' "$default_gui"; gui=$answer
    fi
    if [ -z "$gpu" ]; then ask 'Graphics: keep/software/turnip (Adreno KGSL; builds Mesa)' keep; gpu=$answer; fi
    if [ "$gpu" = turnip ] && [ "$jobs" = 2 ]; then ask 'Mesa compiler jobs (1-8)' 2; jobs=$answer; fi
    if [ -z "$locale_name" ]; then ask 'Locale, e.g. ru_RU.UTF-8; keep leaves it unchanged' keep; locale_name=$answer; fi
    if [ -z "$zone" ]; then ask 'Timezone: system, IANA zone, or keep' keep; zone=$answer; fi
    if [ -z "$fonts" ]; then
        default_fonts=basic; [ "$resume" = no ] || default_fonts=keep
        ask 'Fonts: basic/cjk/keep' "$default_fonts"; fonts=$answer
    fi
    ask 'Additional settings (guest user, protocol, packages, DNS, cache, Arch sandbox)? y/N' N
    case "$answer" in
        y|Y|yes|YES)
            if [ -z "$new_user" ]; then ask 'Create guest user (empty = no new account)' ''; new_user=$answer; fi
            ask 'Installer application entries: both/x11/wayland' "$protocol"; protocol=$answer
            ask 'Additional repository packages (space-separated)' "$extras"; extras=$answer
            if [ "$resume" = no ] && [ -z "$dns" ]; then
                ask 'DNS: system; Private DNS/VPN needs explicit IPs (plain guest DNS)' system; dns=$answer
            fi
            ask 'Downloaded package cache: keep/clean' "$cache"; cache=$answer
            if [ "$distro" = arch ] || [ "$resume" = yes ]; then
                ask 'Arch pacman filesystem sandbox: keep/disable-filesystem' "$arch_sandbox"; arch_sandbox=$answer
            fi ;;
    esac
fi
name=${name:-${distro:-debian}}
if [ "$resume" = yes ]; then gui=${gui:-keep}; fonts=${fonts:-keep}; else gui=${gui:-apps}; fonts=${fonts:-basic}; fi
gpu=${gpu:-keep}
locale_name=${locale_name:-keep}
zone=${zone:-keep}
dns=${dns:-system}
case "$name" in ''|[!A-Za-z0-9]*|*[!A-Za-z0-9_.-]*) fail 'Invalid environment name.' ;; esac
[ "${#name}" -le 64 ] || fail 'Name must be at most 64 characters.'
case "$gui" in none|apps|xfce|weston|gnome|keep) ;; *) fail 'Invalid GUI profile.' ;; esac
case "$protocol" in both|x11|wayland) ;; *) fail 'Protocol must be both, x11 or wayland.' ;; esac
case "$gpu" in keep|software|turnip) ;; *) fail 'GPU must be keep, software or turnip.' ;; esac
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
case "$arch_sandbox" in keep|disable-filesystem) ;; *) fail 'Invalid Arch sandbox policy.' ;; esac
if [ -n "$extras" ]; then
    printf '%s\n' "$extras" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9+_.-]*( [A-Za-z0-9][A-Za-z0-9+_.-]*)*$' || fail 'Expected repository package names, not options or shell commands.'
fi

# Read-only validation precedes every existing-environment mutation.
if [ "$resume" = yes ]; then
    detected=$(magicdesk-guest exec "$name" --user root -- /bin/sh -c '. /etc/os-release; printf "%s\n" "$ID"')
    case "$detected" in archarm) detected=arch ;; esac
    [ -z "$distro" ] || [ "$distro" = "$detected" ] || fail 'Selected distribution does not match the existing environment.'
    distro=$detected
else
    distro=${distro:-debian}
fi
recipe_defaults
[ -n "$image" ] || image=$source
[ "$distro" = arch ] || [ "$arch_sandbox" = keep ] || fail '--arch-sandbox is only applicable to Arch.'
[ "$gui" != gnome ] || [ "$distro" = fedora ] || fail 'The GNOME devkit recipe currently requires Fedora 44. Use apps, Xfce or Weston on other distributions.'
[ "$gui" != none ] || [ "$gpu" = keep ] || fail 'Choose a GUI profile before installing graphics; use --gui keep for an existing GUI.'

printf '\n%s %s ARM64 | %s | GUI=%s | graphics=%s\n' "$distro" "$version" "$name" "$gui" "$gpu"
printf 'Locale=%s | timezone=%s | fonts=%s | application entries=%s\n' "$locale_name" "$zone" "$fonts" "$protocol"
printf 'Guest account=%s | extra packages=%s | package cache=%s\n' "${new_user:-unchanged}" "${extras:-none}" "$cache"
[ "$gpu" != turnip ] || printf 'Mesa compiler jobs: %s\n' "$jobs"
if [ "$resume" = yes ]; then printf 'Continue the existing environment without replacing user data.\n'
else printf 'Source: %s\nGuest DNS: %s\n' "$image" "$dns"; fi
[ "$distro" != arch ] || printf 'Arch uses a community OCI image. Trust its publisher before continuing.\n'
[ "$arch_sandbox" = keep ] || printf 'WARNING: pacman filesystem sandbox is explicitly disabled for this installation.\n'
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
    if [ "$status" -ne 0 ]; then
        printf '\nInstallation stopped while %s (exit %s).\n' "$stage" "$status" >&2
        if [ "$ready" = yes ]; then
            printf 'Environment retained. Repeat your options with --name %s --resume (do not reinstall).\n' "$name" >&2
            printf 'GUI=%s GPU=%s jobs=%s locale=%s timezone=%s fonts=%s arch-sandbox=%s\n' \
                "$gui" "$gpu" "$jobs" "$locale_name" "$zone" "$fonts" "$arch_sandbox" >&2
            [ "$distro" != arch ] || printf 'If pacman reports Landlock unsupported, an explicit --arch-sandbox disable-filesystem permits package setup without that sandbox.\n' >&2
        else
            printf 'Inspect magicdesk-guest list before retrying an interrupted installation.\n' >&2
        fi
    fi
}
trap finish 0
trap 'exit 130' INT
trap 'exit 143' TERM
magicdesk-guest --probe
if [ "$resume" = no ]; then
    stage='installing the Linux image'
    magicdesk-guest install "$image" --name "$name" --dns "$dns"
    if [ "$dns" != preserve ]; then
        stage='applying selected DNS to the new environment'
        magicdesk-guest dns "$name" "$dns" --replace
    fi
fi
stage='validating the selected environment'
magicdesk-guest exec "$name" --user root -- /bin/sh -c '
    set -eu
    . /etc/os-release
    case "$ID" in archarm) ID=arch ;; esac
    [ "$ID" = "$1" ] || exit 1
    case "$ID" in
        debian|ubuntu) [ "$VERSION_ID" = "$2" ] && [ "$(dpkg --print-architecture)" = arm64 ] ;;
        alpine) case "$VERSION_ID" in "$2".*) ;; *) exit 1 ;; esac; [ "$(apk --print-arch)" = aarch64 ] ;;
        fedora) [ "$VERSION_ID" = "$2" ] && [ "$(rpm --eval "%{_arch}")" = aarch64 ] ;;
        arch) [ "$(uname -m)" = aarch64 ] ;;
    esac || { echo "Unexpected distribution version or architecture; setup was not applied." >&2; exit 1; }
' sh "$distro" "$version"
ready=yes
stage='installing packages and guest settings'
system_setup | magicdesk-guest exec "$name" --user root --env DEBIAN_FRONTEND=noninteractive -- /bin/sh -s -- \
    "$gui" "$protocol" "$locale_name" "$zone" "$new_user" "$fonts" "$cache" "$arch_sandbox" "$extras"

if [ "$gpu" != keep ]; then
    stage="preparing the $gpu graphics profile"
    recipe=$(graphics_setup | sha256sum)
    recipe=${recipe%% *}
    graphics_setup | magicdesk-guest exec "$name" --user root --env DEBIAN_FRONTEND=noninteractive \
        -- /bin/sh -s -- "$gpu" "$jobs" "$recipe" "$arch_sandbox"
fi

stage=complete
printf '\nLinux is ready: %s (%s %s)\n' "$name" "$distro" "$version"
gui_notice
printf '\nNext steps:\n'
if [ "$gui" != none ]; then
    printf 'In MagicDesk Control Panel, select a display and open Apps (Start).\n'
    printf 'Press Refresh beside the search field to load installed Linux entries.\n'
fi
case "$gui" in
    none) printf 'No GUI was installed by this run. Use a terminal below.\n' ;;
    keep) printf 'Existing GUI entries were preserved; choose an installed application or desktop.\n' ;;
    *)
        for backend in x11 wayland; do
            if [ "$protocol" = both ] || [ "$protocol" = "$backend" ]; then
                printf 'Individual apps: Mousepad (%s), Thunar (%s), Xfce Terminal (%s).\n' "$backend" "$backend" "$backend"
            fi
        done
        case "$gui" in
            apps) printf 'The apps profile adds individual applications, not a whole Linux desktop.\n' ;;
            xfce) printf 'Whole desktop: open Xfce Desktop from the same list (X11).\n' ;;
            weston) printf 'Whole desktop: open Weston Desktop from the same list (Wayland).\n' ;;
            gnome) printf 'Experimental desktop: GNOME Shell (development kit); requires a Linux system bus.\n' ;;
        esac ;;
esac
printf 'Terminal: Terminal sessions > New session > Shroot environments > %s > New session.\n' "$name"
printf 'Keep privileged access available; no MagicDesk Desktop session or installer rerun is needed.\n'
printf 'Console: magicdesk-guest login %s\n' "$name"
if [ -n "$new_user" ]; then printf 'User console: magicdesk-guest login %s --user %s\n' "$name" "$new_user"; fi
printf 'Folder attachment: magicdesk-guest login %s --bind /sdcard/Download /mnt\n' "$name"
printf 'Optional Android command access: magicdesk-guest login %s --magicdesk\n' "$name"
printf 'Backup (close its applications first): magicdesk-guest backup %s /sdcard/Download/%s.tar.zst\n' "$name" "$name"
if [ "$gpu" = turnip ]; then
    printf 'Software fallback: sh install_linux.sh --name %s --resume --gpu software\n' "$name"
fi
