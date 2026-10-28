#!/bin/sh
# Run in the guest desktop's process tree; failure is not a skip.
set -eu
: "${DBUS_SESSION_BUS_ADDRESS:?Run from the guest desktop terminal}"
: "${XDG_RUNTIME_DIR:?Run from the guest desktop terminal}"
app=${1:-org.chromium.Chromium}
backend=${2:-flatpak}
case "$backend" in
    flatpak) app_id=$app ;;
    snap) app_id=snap.$app ;;
    *) echo "Unknown sandbox backend: $backend" >&2; exit 2 ;;
esac
work=$(mktemp -d "${TMPDIR:-/tmp}/md-document-portal.XXXXXXXX")
document_id=
sandbox=
cleanup() {
    if [ -n "$sandbox" ]; then
        kill "$sandbox" 2>/dev/null || :
        wait "$sandbox" 2>/dev/null || :
    fi
    if [ -n "$document_id" ]; then
        timeout 30 flatpak document-unexport --doc-id "$document_id" || :
    fi
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'document-before\n' > "$work/source.txt"
# EVENT_WAIT: portal export and sandbox process completion; expiry fails.
document=$(timeout 30 flatpak document-export --unique --transient \
    --app="$app_id" --allow-read --allow-write "$work/source.txt")
case "$document" in
    "$XDG_RUNTIME_DIR"/doc/*/source.txt) ;;
    *) echo "Unexpected document path: $document" >&2; exit 1 ;;
esac
document_id=${document#"$XDG_RUNTIME_DIR/doc/"}
document_id=${document_id%%/*}
test -f "$document"
mkfifo "$work/input" "$work/output"
program='
    document=$1
    # Flatpak maps a nonstandard host runtime directory to its own runtime path.
    if [ "$2" = flatpak ]; then document=$XDG_RUNTIME_DIR/doc/$document; fi
    work=$(mktemp -d /tmp/md-sandbox-cwd.XXXXXXXX)
    trap '\''rm -rf "$work"'\'' EXIT
    cd "$work"
    printf "private-cwd\n" > entered.txt
    test "$(cat "$document")" = document-before
    printf "document-after\n" > "$document"
    test "$(cat "$document")" = document-after
    printf "READY\n"
    read -r command
    test "$command" = REVOKE
    if cat "$document" >/dev/null 2>&1; then
        echo "Revoked document remains readable" >&2
        exit 1
    fi
    printf "REVOKED\n"
'
case "$backend" in
    flatpak) set -- flatpak run --instance-id-fd=5 --command=sh "$app" -ec "$program" sh "$document_id/source.txt" flatpak ;;
    snap) set -- snap run --shell "$app" -ec "$program" sh "$document" snap ;;
esac
timeout 45 "$@" < "$work/input" > "$work/output" 5> "$work/instance" &
sandbox=$!
exec 3> "$work/input" 4< "$work/output"
# EVENT_WAIT: the live sandbox acknowledges IO, then checks host revocation;
# the timeout owns its lifetime and closes the pipe on failure.
read -r state <&4
test "$state" = READY
if [ "$backend" = flatpak ]; then
    instance=$(cat "$work/instance")
    test -n "$instance"
    timeout 15 flatpak enter "$instance" sh -ec '
        case "$PWD" in /tmp/md-sandbox-cwd.*) ;; *) exit 1 ;; esac
        test "$(cat entered.txt)" = private-cwd
    '
    echo 'PASS Flatpak enter retains the live sandbox private working directory'
fi
test "$(cat "$work/source.txt")" = document-after
timeout 30 flatpak document-unexport --doc-id "$document_id"
document_id=
printf 'REVOKE\n' >&3
read -r state <&4
test "$state" = REVOKED
wait "$sandbox"
sandbox=
exec 3>&- 4<&-
test ! -e "$document"
echo 'PASS document export, sandbox read/write and revocation in the live sandbox'
