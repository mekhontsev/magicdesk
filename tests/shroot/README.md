# Shroot Integration Tests

MagicDesk owns Android execution transports, APK packaging, installer recipes,
command delegation and graphical workflows. Native engine assertions, guest
payloads and the local executor live in the pinned [`Shroot tests`](../../native/shroot/tests/README.md).
These drivers import them rather than maintaining a second copy.

Recipe-specific probes live here with their drivers. See [installer and environment workflows](recipe-workflows.md)
for APK acceptance, and [compositor package adaptation](compositor-adaptation.md)
for the KWin and Weston experiments.

Run application drivers from this repository:

```sh
python tests/shroot/test_command_access.py --help
python tests/shroot/test_k3s_boot.py --help
PYTHONPATH=tests/shroot python -m unittest discover -s tests/shroot/unit
PYTHONPATH=tests/shroot python tests/shroot/test_boot_helpers.py
python scripts/tests/test_command_shroot.py
```

`SHROOT_SOURCE` optionally selects a separate native checkout; otherwise the
pinned `native/shroot` is used. Build and package that exact runtime when testing
the app executor. The debug fixture APK verifies helper hashes before execution.
The tests read the existing MCP configuration; they do not create a new server.

Shared engine drivers can use the same adapter:

```sh
export PYTHONPATH="$PWD/tests/shroot"
export SHROOT_TEST_ADAPTER=magicdesk_executor
export SHROOT_TEST_EXECUTOR=app  # or shell
python native/shroot/tests/test_resources.py app/build/shroot --case supervisor-state
```

`app` executes in MagicDesk's actual Zygote identity. `shell` verifies UID 2000
and the shell SELinux domain. Neither falls back to the other. Local native
tests keep the caller's identity and need no MCP or APK.

Keep test stores disposable and retain exact launch IDs through completion and
cleanup. A transport timeout does not authorize replay. Close an active Desktop
through its normal cleanup before replacing an APK. Graphical tests require
the selected graphics services; a headless browser pass does not verify Android
presentation, input or audio.
