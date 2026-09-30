# Guest Browser Fixtures

These fixtures build and test the production native runtime in
`native/guest-runtime`. They do not contain a second syscall implementation.
Use a prepared disposable Debian store with matching browsers, libraries, fonts
and helper fixtures, never a personal browser profile.

The runtime supports application filters but is **not a complete security
sandbox**. Real executor credentials remain UID 2000 in these tests. The
[interception contract](../../guest-runtime/interception.md) defines ownership,
logical image admission and remaining isolation limits.

## Build And Run

`build.sh` builds the same four executables as the APK. SQLite uses the pinned
production dependency, with `MD_SQLITE_BUILD` optionally selecting its existing
build directory.

```sh
sh native/guest-exec-lab/browser/build.sh build/guest-browser
python native/guest-exec-lab/browser/run.py --build build/guest-browser --store DEVICE_GUEST_STORE --expect-artifact /tmp/NEW_FIREFOX_PROFILE/page.png -- /bin/sh -ec 'mkdir /tmp/NEW_FIREFOX_PROFILE; exec /usr/bin/firefox-esr --headless --no-remote --profile /tmp/NEW_FIREFOX_PROFILE --window-size 800,600 --screenshot /tmp/NEW_FIREFOX_PROFILE/page.png "data:text/html,<h1>MagicDesk guest browser</h1>"'
python native/guest-exec-lab/browser/test_browser.py --build build/guest-browser --store DEVICE_GUEST_STORE
```

The Chromium store contains an ordinary mode-0755 copy of Debian's sandbox
helper at `/tmp/md-chrome-helper-ordinary`. The test explicitly selects it with
`--admit-elf`; no actual host set-ID executable or privilege elevation is used.
The unadmitted-helper negative control must fail.

Each run stages an immutable helper bundle and records device/build identity,
actual UID, hashes, command, output, exit status and console cleanup. The
supervisor owns only its newly created process tree. An event deadline fails and
cancels it after `--deadline-seconds` (5 to 120); an outer bound covers cleanup.
No Android task is traced, no display acquired and no kernel policy changed.

Exit zero alone is not rendering success. `--expect-artifact` rejects an existing
path and checks the resulting PNG signature; visual content needs separate
inspection. `--expect-output` requires an explicit marker. `workloadCompleted`
requires a successful exit and expectations. `sandboxSupportEstablished` and
`layerOneEstablished` remain false: no test here certifies full isolation.

## Regression Groups

- `test_browser.py`: three stock Chromium launches with fresh profiles, computed
  JavaScript/DOM values, real installed filters, PNG output and zero exit. No
  sandbox-disabling flags. Child signal exits and diagnostics remain in reports.
- `test_credentials.py`: explicit sealed-image catalogue, first-entry and exec
  logical credentials, secure auxv/loader, irreversible drops, no_new_privs,
  denied real credentials and retained descriptors.
- `test_retained.py`: protected metadata and exec, nonleader exec, descriptor
  offsets, signals, group death, job control, sockets, three stock zygote/renderer
  starts and rejection of a helper not explicitly admitted.
- `test_renderer.py`: a separately labelled layer-two experiment. Its parent
  uses `--no-sandbox`, while `renderer-prefix.py` removes that flag from
  renderer children. This does not satisfy full-browser acceptance and must not
  be used for browsing.

```sh
python native/guest-exec-lab/browser/test_credentials.py --build BUILD --store DEVICE_GUEST_STORE
python native/guest-exec-lab/browser/test_retained.py --build BUILD --store DEVICE_GUEST_STORE
```

`build-controls.sh` builds `control.c` for the prepared guest libc.
`build-identity.sh` tests the production logical-credential model.
`zygote_stage.py` exercises helper IPC, renderer fork, real filter installation
and nonempty Mojo startup independently of a displayed page. Prepared stores
supply these explicitly; they are not downloaded by the runtime.

## Coverage And Limits

Device coverage is NX809J, Android 16, Linux 6.12.23, 4 KiB pages, actual
UID 2000 in the shell SELinux domain. Firefox's headless screenshot and
Chromium's three-profile JavaScript/PNG workflows establish execution, not
arbitrary browsing, network/GPU compatibility or confinement.

Protected metadata uses task-affine descriptor export, notification ADDFD and
original-site replay without relaxing dumpability. A second tracer cannot attach.
The logical proc-root restriction is not general chroot. Interpreter/library
admission, mapped-code protection, full descriptor/root confinement and
domain-authenticated filesystem authorization remain unverified or incomplete.
The [native boundary controls](../sandbox-research.md) record separate kernel
experiments; their results are not substituted for these production workflows.
