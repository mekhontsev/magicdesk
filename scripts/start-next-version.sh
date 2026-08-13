#!/usr/bin/env sh
set -eu

if [ "$#" -ne 2 ]; then
    printf 'Usage: %s VERSION VERSION_CODE\n' "$0" >&2
    exit 2
fi

version=$1
version_code=$2
project_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
properties="$project_dir/gradle.properties"

if ! printf '%s\n' "$version" \
        | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    printf 'Version must use major.minor.patch format: %s\n' "$version" >&2
    exit 2
fi
if ! printf '%s\n' "$version_code" | grep -Eq '^[1-9][0-9]*$'; then
    printf 'Version code must be a positive integer: %s\n' \
        "$version_code" >&2
    exit 2
fi

current_version=$(awk -F= \
    '$1 == "magicDeskVersionName" {print $2; exit}' "$properties")
current_code=$(awk -F= \
    '$1 == "magicDeskVersionCode" {print $2; exit}' "$properties")
highest_version=$(printf '%s\n%s\n' "$current_version" "$version" \
    | sort -V | tail -n 1)

if [ "$version" = "$current_version" ] \
        || [ "$highest_version" != "$version" ]; then
    printf 'Version must be newer than %s\n' "$current_version" >&2
    exit 1
fi
if [ "$version_code" -le "$current_code" ]; then
    printf 'Version code must be greater than %s\n' "$current_code" >&2
    exit 1
fi

temporary=$(mktemp "$properties.XXXXXX")
trap 'rm -f "$temporary"' EXIT HUP INT TERM
awk -v version="$version" -v code="$version_code" '
    $1 ~ /^magicDeskVersionName=/ {
        print "magicDeskVersionName=" version
        next
    }
    $1 ~ /^magicDeskVersionCode=/ {
        print "magicDeskVersionCode=" code
        next
    }
    { print }
' "$properties" > "$temporary"
mv "$temporary" "$properties"
trap - EXIT HUP INT TERM

printf 'Started MagicDesk %s development cycle (versionCode %s).\n' \
    "$version" "$version_code"
