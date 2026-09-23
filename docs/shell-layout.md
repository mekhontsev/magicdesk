# Shell Layout

## Ownership

`ShellLayout` is a protocol-neutral geometry owner for one Desktop workspace or
one nested graphical desktop. It has no Android, Binder, X11 or wlroots dependency.
The owner submits a complete committed set of surface intents and viewport bounds;
readers receive an immutable snapshot. Equal commits reuse that snapshot. Layout
runs on geometry/state changes, not on rendering, pointer motion or a polling timer.

A `ShellLayoutScope` defines the coordinate and lifetime scope. Its bindings own
complete surface sets, namespace client-local identities and release only their
own reservations. Viewport and owner-state changes can commit atomically. Scope
release revokes all bindings; delayed commits through a revoked owner are rejected.
Notifications are event-driven and do not deliver superseded snapshots after a
reentrant owner release. Surface IDs are not Android task IDs or persistent
display identities. A nested
Linux desktop must have its own layout: its panels cannot reserve space on the
Android desktop containing its viewer. Output attachment does not merge scopes.
The adapter converts its protocol coordinates and density into the scope's units
before committing them. The Android Desktop scope uses display pixels, including
the density-resolved sizes of its Views. All rectangles and edge intervals are
half-open.

The surface owner submits `mapped=false` on unmap and omits destroyed/disconnected
surfaces. Unmapped clients still receive resolved geometry for configuration, but
have no input or reservations. Releasing a scope clears all of its reservations.
Pending wire-protocol state and configure acknowledgements stay in the protocol
adapter; they are not additional mutable fields shared with layout readers.

## Model

`ShellSurface` describes identity, mapping, semantic layer, keyboard intent,
placement, paint extension, input bounds and edge reservations. Placement includes
anchors, requested size, margins and one of four reference frames:

- `OUTPUT`: the complete output rectangle;
- `CONTENT`: the stable viewport after system insets;
- `AVAILABLE`: space remaining after window reservations.
- `PANEL`: space remaining after window and shell-only reservations.

Zero size requests stretch between opposite anchors. Fixed size requests align
with one anchor or center between opposite anchors; only applicable margins affect
placement. This layout policy constrains geometry to the chosen reference frame.
It is not a complete implementation of any wire protocol's configure semantics.

Resolved content, painted and interactive bounds are separate. For example, phone
taskbar controls stay above navigation while their painted/input window continues
through that inset. Layer and keyboard intent are metadata, not grants: Android
host/focus policy decides what it can present. A theme cannot acquire exclusive
input or create a privileged Android layer by choosing an enum value.

## Reservations

`ShellReservation` preserves two distinct meanings:

- An absolute output-edge distance with a partial interval. Overlapping absolute
  reservations form a union; their depths are not added.
- A placement-relative exclusive zone including its edge margin. Panels placed
  against `AVAILABLE` can stack; panels against `CONTENT` can overlap.

Mapped reserving surfaces are arranged first, then other surfaces. Higher
semantic layers take layout precedence; ties retain the owner's commit order.
Absolute regions are collected before arrangement, independently of that order.
This is layout policy, not a command to reorder Android tasks or scene nodes.

Snapshots retain each precise exclusion, its owning surface and edge. Existing
window consumers receive a conservative rectangular `workArea`, bounded to at
least one pixel even when reservations exhaust it. Region-aware consumers can use
the exclusions rather than pretending every partial strut occupies the whole edge.
`panelArea` additionally avoids shell-only reservations: an auto-hiding taskbar
does not reduce maximized windows, but Start and shell panels still avoid it.

## Android Adapter

`DesktopViewport` reads stable system-bar geometry. `DesktopShellLayout` expresses
the current bottom taskbar policy. `DesktopLayoutController` converts the resolved
bounds to Android rectangles and updates existing desktop views and taskbar hosts.
Window placement, shell panels, popup limits and the icon-grid viewport consume the
same result rather than independently subtracting a taskbar height.

Start, overview, notifications and quick controls submit measured sizes and
`ShellPanelPlacement` intents. Popup placement resolves anchors, edge flipping and
clamping centrally. An owned popup follows its parent's resolved position; it
cannot resolve against an absent or unmapped parent. The Android controller closes
children before releasing their parent.

`DesktopPanelWindowController` materializes the resolved geometry in application
panel windows. Geometry changes update existing windows rather than detaching
them or reacquiring focus. Its host-token lifecycle, focus-acknowledgement gate,
IME targeting and modal-child input behavior remain platform responsibilities.
Neither keyboard intent nor a geometry binding grants focus by itself.

Taskbar concealment/reveal and IME visibility keep their existing presentation
policy. They do not discard the taskbar's layout intent or move its stable viewport.
`DesktopTaskbarHost` and `DesktopChromeActivity` still own the actual bounded child
window, including its temporary reveal-edge input region. Wallpaper fills the
physical display independently of shell content padding.

Android DisplayArea ownership, stable fullscreen planes and the task activation
gateway remain separate. No layer enum maps directly to a DisplayArea or raw
SurfaceControl z-order. See [fullscreen transitions](fullscreen-transitions.md).

## Protocol Adapters

The native Wayland runtime implements layer-shell admission, committed state,
configure/ack and borrowed transparent outputs. It publishes shell surfaces
separately from application windows. It does not yet connect them to Android
workspace hosts. X11 strut adaptation is also pending. These adapters must
preserve protocol lifetimes and coordinate conversion:

- Wayland consumes committed layer-surface state and reports geometry through
  configure/ack; mapping controls reservations. The native compositor retains
  scene, subsurface, popup and hit-testing ownership.
- X11 consumes `_NET_WM_STRUT_PARTIAL` (or the older strut when partial is absent),
  converts root-relative distances and inclusive intervals, and follows property,
  map/unmap and destruction events. DOCK/DESKTOP roles are shell surfaces, not
  ordinary application tasks. Guest-WM reservations stay inside the guest scope.

The wlroots separation of protocol state, `full_area`/`usable_area` arrangement,
scene nodes and seat focus is the reference, not an Android dependency. The
current pinned source is wlroots 0.18.2, particularly
`types/scene/layer_shell_v1.c` and `include/wlr/types/wlr_layer_shell_v1.h`.
Protocol references: [layer-shell](https://wayland.app/protocols/wlr-layer-shell-unstable-v1)
and [EWMH](https://specifications.freedesktop.org/wm/latest-single/).

## Integration Work

1. Connect native shell state to an explicitly selected live workspace binding.
   Keep nested-desktop scopes separate; output presentation alone is not admission.
   Translate geometry and density once at this boundary.
2. Host backgrounds and bottom surfaces in existing HOME infrastructure, and top
   surfaces in the existing chrome host. Complete popup paint extents and precise
   input regions before admitting external panels. Apply workspace fullscreen and
   focus policy rather than mapping layer numbers directly to Android z-order.
3. Adapt X11 DOCK/DESKTOP properties and struts to the same bindings. Preserve
   guest-WM ownership for whole-desktop sessions.
4. Expose the shared application catalog and semantic actions to external panels,
   using the existing task gateway. Add explicit session/workspace selection and
   cleanup on workspace loss or client failure.

## Verification

`ShellLayoutTest`, `ShellLayoutScopeTest`, `ShellPanelPlacementTest`,
`DesktopShellLayoutTest` and `DesktopViewportTest` cover pure
layout, partial/overlapping exclusions, mapped lifetime, isolated scopes,
placement margins, viewport origins, DPI-dependent taskbar height, autohide,
navigation paint extension, owner-relative popups, atomic viewport commits and
immutable snapshots. Quick-controls fixtures retain their placement assertions.
`DesktopPanelArchitectureTest` guards Android host and focus boundaries. The
Wayland native shell fixture verifies transparent pixels, configure deduplication,
mapping lifetime, independent pointer/keyboard ownership and output revocation.
Desktop self-tests cover existing Android geometry and fullscreen transitions;
they do not validate external Linux panel protocols or integrated-shell UX.
