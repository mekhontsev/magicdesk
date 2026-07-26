#!/usr/bin/env bash
set -euo pipefail

EXPECTED_COMMIT=f1bdb13583da85a47fcf1632a78ef52d6e6da651

if [[ $# -ne 2 ]]; then
    printf 'Usage: %s KERNEL_SOURCE GKI_ARTIFACT_DIRECTORY\n' "$0" >&2
    exit 2
fi

kernel_source=$(realpath "$1")
artifact_dir=$(realpath "$2")
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
module_dir="$project_dir/kernel/xr-resolution-fix"
output_dir=${KERNEL_OUTPUT:-"$project_dir/.kernel-build/android16-6.12"}
config="$artifact_dir/kernel_aarch64_dot_config"
symvers="$artifact_dir/kernel_aarch64_Module.symvers"

for file in "$config" "$symvers"; do
    if [[ ! -f "$file" ]]; then
        printf 'Missing GKI artifact: %s\n' "$file" >&2
        exit 1
    fi
done

actual_commit=$(git -C "$kernel_source" rev-parse HEAD)
if [[ "$actual_commit" != "$EXPECTED_COMMIT" ]]; then
    printf 'Wrong kernel source commit: %s (expected %s)\n' \
        "$actual_commit" "$EXPECTED_COMMIT" >&2
    exit 1
fi

mkdir -p "$output_dir"
cp "$config" "$output_dir/.config"

protected_list="$kernel_source/protected_module_names_list"
remove_protected_list=false
if [[ ! -e "$protected_list" ]]; then
    : > "$protected_list"
    remove_protected_list=true
fi
cleanup() {
    if [[ "$remove_protected_list" == true ]]; then
        rm -f "$protected_list"
    fi
}
trap cleanup EXIT

make -C "$kernel_source" O="$output_dir" ARCH=arm64 LLVM=1 \
    olddefconfig modules_prepare
cp "$symvers" "$output_dir/Module.symvers"
make -C "$kernel_source" O="$output_dir" M="$module_dir" \
    ARCH=arm64 LLVM=1 modules

printf '\nBuilt: %s\n' "$module_dir/dp_mode_reset.ko"
sha256sum "$module_dir/dp_mode_reset.ko"
printf '%s\n' \
    'Test the module before replacing kernel-fixes/src/main/res/raw/dp_mode_reset.ko.' \
    'After replacement, update EXPECTED_MODULE_SHA256 in the add-on XrResolutionFix.java.'
