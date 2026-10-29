# Linux Appearance

Individual X11 and Wayland application sessions receive the current Android
light/dark preference without requiring Desktop or privileged access. Android
configuration callbacks update retained sessions; no periodic queries or guest
configuration files are used. **System theme during Desktop** affects this
preference while its temporary system override is active. MagicDesk's independent
shell palette does not override Linux content.

Applications decide whether to honor desktop preferences. A forced application
theme, unsupported toolkit or startup-only setting can take precedence. Whole
Linux desktop sessions keep their own settings manager and receive no appearance
helper. Display density remains a separate contract.

GTK3 must have the named theme installed in its Linux environment. Termux supplies
`Adwaita-dark` through `gnome-themes-extra`; without it, GTK3 can report the dark
theme name while falling back to light styling. MagicDesk does not install themes
or modify the user's GTK configuration.

## Protocols

- X11 publishes `Net/ThemeName` (`Adwaita` or `Adwaita-dark`) together with its
  existing density settings in one bounded XSettings property. If a Linux
  settings manager takes the selection, MagicDesk does not reclaim it.
- Both protocols can use `org.freedesktop.portal.Settings` at
  `/org/freedesktop/portal/desktop`. The read-only provider implements `ReadAll`,
  `ReadOne`, legacy `Read` and `SettingChanged`; `org.freedesktop.appearance`
  exposes `color-scheme` (0 unspecified, 1 dark, 2 light).
- GTK compatibility settings include `org.gnome.desktop.interface/gtk-theme`
  and `text-scaling-factor`. The latter is 1; surface/output density is applied
  separately. Portal-aware clients, including libadwaita, can follow the
  preference without toolkit environment overrides.

MagicDesk does not force `GTK_USE_PORTAL`: it also redirects file choosers and
other services that this Settings-only provider does not implement. GTK3 on
Wayland therefore retains its normal settings backend unless the user has
explicitly configured portal use. GTK3 on X11 follows XSettings directly.

An existing `org.freedesktop.portal.Desktop` owner is never replaced. The helper
does not queue for its name and yields if a replacement takes ownership.
Unsupported portal methods/properties fail immediately; the helper is not a
file chooser, permission broker or implementation of every desktop portal.

## Ownership

`LinuxAppearance` observes Android configuration for an application session.
`LinuxAppearanceLaunch` supplies a private authenticated preference endpoint;
`HostedAppearanceBridge` publishes changed values in the executor's namespace.
X11 and Wayland server adapters forward preferences without owning Android
configuration, changing client privilege or involving frame presentation.

`libmagicdesk_linux_settings.so` is a static executable, invoked by the selected
client executor. Its worker uses libdbus on the client's session bus and ends on
endpoint closure, bus disconnection or loss of provider ownership. Socket writes
and startup admission are bounded; application launch continues without the
provider if admission fails. Without a bus address, the wrapper uses an already
installed `dbus-run-session`; if that command is unavailable, it launches the
application without a portal. No D-Bus daemon is bundled in the APK.

The same executable has a separate automation mode for
[AT-SPI2 and typed D-Bus](automation.md#linux-scope). Appearance ownership does
not grant automation access, and a whole-desktop session can use automation
without receiving this appearance provider.

PRoot application recipes bind the helper at `/tmp/magicdesk-linux-settings` and
forward `MAGICDESK_APPEARANCE_SOCKET` and `MAGICDESK_APPEARANCE_TOKEN` before
invoking it inside the guest's session bus. Prepared chroot entry scripts follow
the same contract; see [chroot-entry.sh](../scripts/examples/chroot-entry.sh).
Neither adapter owns or installs the Linux environment. A namespace or SELinux
policy that prevents reaching the endpoint disables the optional portal, not
the display server. Credentials are removed from the launched application's
environment.

## Build And Checks

`native/linux-settings/CMakeLists.txt` downloads the pinned, hash-verified dbus
1.16.2 source and builds client-library support using Meson. `message_bus`
and `tools` are explicitly disabled; libdbus is statically linked into our helper.
A build-only patch skips unused upstream test fixtures in this configuration,
including their Windows-incompatible bulk copy command. Library sources are unchanged.
Gradle packages that exact executable and `assets/licenses/dbus`, not the whole
dependency prefix. The build uses the Android minimum API and requires `patch` and Meson
(the same `magicDeskMeson` override as the Wayland build).

`python3 native/linux-settings/test_portal.py` exercises the built helper against
an installed test-only session bus: typed replies, namespace filtering, live
changes, unavailable portal interfaces, existing-provider priority, missing-bus
fallback and exclusion of daemon/tool build targets. Override
`MAGICDESK_SETTINGS_HELPER` to select the helper built by Gradle. The test requires
`gdbus` and `dbus-run-session` on the test host; these are not APK dependencies.
