#!/bin/sh
# Run from a saved file in MagicDesk's Shell console; stdin is used for questions.
set -eu

usage() {
    cat <<'HELP'
Usage: sh install_linux.sh [--name NAME] [--gui apps|xfce] [--dns POLICY] [--yes] [--resume]

Install Debian 13 ARM64 in Shroot with Mousepad, Thunar and Xfce Terminal.
Applications have X11 and Wayland entries. The optional Xfce desktop uses X11.
This profile uses software rendering, not a patched Mesa/Turnip build.

  --name NAME   Independent environment name (default: debian)
  --gui apps    Individual GUI applications (default)
  --gui xfce    Also install the complete Xfce desktop
  --dns POLICY  New image DNS: system (default), preserve, or IP[,IP...]
  --yes         Accept the selected configuration without questions
  --resume      Continue package setup in an existing Debian 13 environment

No Termux or Android root is required. Use trusted images: Shroot is not a
security sandbox. The current shell/root identity is never changed.
HELP
}

fail() { printf '%s\n' "$*" >&2; exit 1; }
name=
gui=
yes=no
resume=no
dns=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --name|--gui|--dns)
            [ "$#" -ge 2 ] || fail "Missing value for $1"
            case "$1" in --name) name=$2 ;; --gui) gui=$2 ;; --dns) dns=$2 ;; esac
            shift 2 ;;
        --yes) yes=yes; shift ;;
        --resume) resume=yes; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "Unknown argument: $1 (see --help)" ;;
    esac
done
[ "$resume" = no ] || [ -z "$dns" ] || fail '--resume preserves DNS; change it explicitly with magicdesk-guest dns.'

command -v magicdesk-guest >/dev/null 2>&1 || fail 'Open a MagicDesk Shell console with shell access first.'
case "$(id -u)" in 2000|0) ;; *) fail 'Shroot requires the selected Android shell (UID 2000) or root executor.' ;; esac

if [ "$yes" = no ]; then
    [ -t 0 ] || fail 'Interactive input is unavailable. Save the script before running it, or use --yes.'
    if [ -z "$name" ]; then
        printf 'Environment name [debian]: '
        read -r name || fail 'Input closed.'
    fi
    if [ -z "$gui" ]; then
        printf 'GUI: apps = applications, xfce = applications + desktop [apps]: '
        read -r gui || fail 'Input closed.'
    fi
    if [ "$resume" = no ] && [ -z "$dns" ]; then
        printf 'DNS [system]; with Private DNS/VPN choose explicit IPs (unencrypted guest DNS): '
        read -r dns || fail 'Input closed.'
    fi
fi
name=${name:-debian}
gui=${gui:-apps}
dns=${dns:-system}
case "$name" in ''|[!A-Za-z0-9]*|*[!A-Za-z0-9_.-]*) fail 'Name must start with a letter or digit and contain only letters, digits, _, . or -.' ;; esac
[ "${#name}" -le 64 ] || fail 'Name must be at most 64 characters.'
case "$gui" in apps|xfce) ;; *) fail 'GUI must be apps or xfce.' ;; esac

printf '\nDebian 13 ARM64 | %s | %s\n' "$name" "$gui"
printf 'New graphics profiles use software rendering; existing profiles are retained.\n'
printf 'Packages and their dependencies will be downloaded and installed in this environment.\n'
if [ "$resume" = yes ]; then
    printf 'Continue setup in an existing environment.\n'
else
    printf 'Create a new environment. Existing names will not be replaced.\n'
    printf 'Guest DNS: %s (system refuses Private DNS/VPN; no public resolver is chosen automatically).\n' "$dns"
fi
if [ "$yes" = no ]; then
    printf 'Continue? [y/N]: '
    read -r answer || fail 'Input closed.'
    case "$answer" in y|Y|yes|YES) ;; *) printf 'Cancelled.\n'; exit 0 ;; esac
fi

stage='checking Shroot'
ready=no
finish() {
    status=$?
    if [ "$status" -ne 0 ]; then
        printf '\nInstallation stopped while %s (exit %s).\n' "$stage" "$status" >&2
        if [ "$ready" = yes ]; then
            printf 'The environment was retained. To retry: sh install_linux.sh --name %s --gui %s --resume\n' "$name" "$gui" >&2
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
    stage='installing the Debian image'
    magicdesk-guest install debian:trixie-slim --name "$name" --dns "$dns"
fi
stage='validating the selected environment'
magicdesk-guest exec "$name" --user root -- /bin/sh -c '
    set -eu
    . /etc/os-release
    [ "$ID" = debian ] && [ "$VERSION_ID" = 13 ] && [ "$(dpkg --print-architecture)" = arm64 ] || {
        echo "Expected Debian 13 ARM64; this environment was not modified." >&2
        exit 1
    }
'
ready=yes
stage='installing GUI packages'
magicdesk-guest exec "$name" --user root --env DEBIAN_FRONTEND=noninteractive -- /bin/sh -s -- "$gui" <<'GUEST_SETUP'
set -eu
umask 022
gui=$1

# Package services belong to the guest, not Android's boot or service manager.
if [ ! -e /usr/sbin/policy-rc.d ]; then
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
    chmod 755 /usr/sbin/policy-rc.d
fi
dpkg --configure -a </dev/null
apt-get update </dev/null
set -- ca-certificates dbus-x11 xkb-data fonts-dejavu-core adwaita-icon-theme mousepad thunar xfce4-terminal
if [ "$gui" = xfce ]; then set -- "$@" xfce4; fi
apt-get install -y --no-install-recommends "$@" </dev/null
dbus-uuidgen --ensure

mkdir -p /etc/profile.d /usr/local/share/applications
# A later GPU profile can replace this file; resuming package setup preserves it.
if [ ! -e /etc/profile.d/magicdesk-graphics.sh ]; then
    cat > /etc/profile.d/magicdesk-graphics.sh <<'GRAPHICS'
export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"
GRAPHICS
fi

cat > /usr/local/share/applications/magicdesk-mousepad-wayland.desktop <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Mousepad (Wayland)
Exec=env GDK_BACKEND=wayland mousepad --disable-server %F
Icon=org.xfce.mousepad
Terminal=false
Categories=Utility;TextEditor;
X-MagicDesk-Graphics=wayland
ENTRY
cat > /usr/local/share/applications/magicdesk-thunar-wayland.desktop <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Thunar (Wayland)
Exec=env GDK_BACKEND=wayland thunar %F
Icon=org.xfce.thunar
Terminal=false
Categories=System;FileTools;FileManager;
X-MagicDesk-Graphics=wayland
ENTRY
cat > /usr/local/share/applications/magicdesk-terminal-wayland.desktop <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Xfce Terminal (Wayland)
Exec=env GDK_BACKEND=wayland xfce4-terminal --disable-server
Icon=org.xfce.terminal
Terminal=false
Categories=System;TerminalEmulator;
X-MagicDesk-Graphics=wayland
ENTRY

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
fi
command -v mousepad
command -v thunar
command -v xfce4-terminal
if [ "$gui" = xfce ]; then command -v xfce4-session; fi
GUEST_SETUP

stage=complete
printf '\nDebian is ready: %s\n' "$name"
printf 'Refresh the Linux application list in MagicDesk to find the new entries.\n'
printf 'Ordinary application entries use X11; entries marked Wayland use Wayland.\n'
if [ "$gui" = xfce ]; then printf 'Xfce Desktop opens a complete desktop in one X11 viewer.\n'; fi
printf 'Console: magicdesk-guest login %s\n' "$name"
printf 'This installer did not build Mesa or enable Turnip.\n'
