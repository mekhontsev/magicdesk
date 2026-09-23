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

Sparse external panels additionally request trusted input-occlusion handling from
the existing chrome host. `FrameworkSurfaceInputApi` checks the current service's
`ACCESS_SURFACE_FLINGER` permission and commits `setTrustedOverlay` on the host's
owned organizer surface. Child windows inherit that state. The grant lives with
that host, does not alter task order/focus, and is not requested by ordinary native
panels. Missing permission/API rejects the optional request without disabling
Desktop. No global touch-security setting is changed. This SurfaceFlinger commit
does not acknowledge child-window input regions or displayed pixels; those remain
separate admission conditions for an external panel.

`DesktopPanelWindowController.ShellWindow` lends persistent, keyboard-inert child
windows in that chrome host. Ordinary popup dismissal does not release them;
explicit closure or host loss does. They borrow graphical outputs, not sessions.
`HostedShellWindows` reconciles a committed surface set against these leases.
Unmap removes a window; remap borrows a new output. Changed geometry updates the
existing window, while equal state does not restart presentation. A replacement
invalidates its previous receipt before relayout. Host loss or presentation failure
revokes the contribution rather than retaining an invisible reservation. The host
validates semantic roles, including unmapped clients; the current chrome adapter
admits only `TOP` with keyboard `NONE`.

`HostedShellSurfaceView` joins the window-layout frame and matching graphical
viewport receipt before publishing the exact touchable region. `HostedShellFrame`
translates family-local input to window pixels, clips it to the viewport and
rounds inward so fractional scaling cannot capture a transparent hole.

Input readiness additionally requires an exact window-token/display/region
observation from `FrameworkInputWindowObservationSource`, followed by the
InputDispatcher receipt in `FrameworkSurfaceInputApi`. `ShellInputRegionReceipt`
owns this one-shot subscription, callback and deadline. Replacement, detachment,
owner death and closure cancel it; stale callbacks cannot re-enable input. The
deadline reports failure, never successful readiness. These event-driven receipts
allocate no worker thread, poll no state and are not requested for pointer motion
or pixel-only repaints. Ordinary application hosts retain their existing behavior.

Taskbar concealment/reveal and IME visibility keep their existing presentation
policy. They do not discard the taskbar's layout intent or move its stable viewport.
`DesktopTaskbarHost` and `DesktopChromeActivity` still own the actual bounded child
window, including its temporary reveal-edge input region. Wallpaper fills the
physical display independently of shell content padding.

Android DisplayArea ownership, stable fullscreen planes and the task activation
gateway remain separate. No layer enum maps directly to a DisplayArea or raw
SurfaceControl z-order. See [fullscreen transitions](fullscreen-transitions.md).

## Protocol Adapters

The Wayland runtime implements layer-shell admission, committed state,
configure/ack and borrowed transparent outputs. `WaylandShellBinding` connects
its typed Binder catalog to an explicitly supplied `ShellLayoutScope`. It never
starts Desktop, selects a display or merges nested scopes. An explicit binding can
borrow a host from the selected Desktop's panel controller; catalog and family
geometry events then reconcile its windows automatically. Public workspace
selection, complete layer/focus policy and X11 strut adaptation are pending.
The adapters preserve protocol lifetimes
and coordinate conversion:

- Wayland consumes committed layer-surface state and reports geometry through
  configure/ack; mapping controls reservations. The native compositor retains
  scene, subsurface, popup and hit-testing ownership.
- X11 consumes `_NET_WM_STRUT_PARTIAL` (or the older strut when partial is absent),
  converts root-relative distances and inclusive intervals, and follows property,
  map/unmap and destruction events. DOCK/DESKTOP roles are shell surfaces, not
  ordinary application tasks. Guest-WM reservations stay inside the guest scope.

`WaylandShellLayout` translates surface units and signed margins to display
pixels using the scope owner's density, and resolved geometry back to logical
output coordinates. Positive zones reserve an unambiguous anchored edge;
zero zones avoid existing reservations, and negative zones use the complete
output. Configure requests carry the exact committed revision. The initial
configure handshake is distinct from an unmapped surface that needs no reply.

Each binding has a monotonically increasing runtime identity. Revocation releases
its surface outputs and reservations, but not application windows or the graphics
session. Both ends reject obsolete owners and geometry replies. Scope release
also notifies an empty binding, so admission cannot outlive its workspace merely
because no panel has mapped yet. A retained graphics session admits at most one
shell scope; application-recipe sessions cannot contribute shell components.
Viewport/density changes update the same binding. Pixel commits and pointer
motion do not recalculate layout or publish catalogs.
The Java bridge publishes each immutable shell catalog on the consumer's event
thread together with its callback. A fast unmap/remap cannot replace an unobserved
lifecycle state. Released owners discard queued publications.

Rendered-family geometry is a separate protocol observation. Wayland publishes
paint extents and precise input rectangles, including popups/subsurfaces, through
`WaylandViewGeometry`; it does not enlarge a panel's reservation when its menu opens.
Shell geometry follows the same revocable owner as the surface catalog.
`HostedShellPlacement` translates family-local paint extents, including negative
popup origins, relative to the panel's resolved content position at the workspace
density. Painted bounds round outward; exact input regions still round inward.
The paint extension does not enlarge the panel's reservation. An incomplete region
is not permission to capture its bounding
box. The output API provides a generation-qualified viewport/Surface receipt:
matching pixels have been queued to Android, not necessarily displayed. Hosts must
coordinate placement and input with this receipt. Android's separate cross-UID
touch-occlusion checks must also be satisfied; a region with holes is not by itself
proof that underlying application windows can receive input.

The wlroots separation of protocol state, `full_area`/`usable_area` arrangement,
scene nodes and seat focus is the reference, not an Android dependency. The
current pinned source is wlroots 0.18.2, particularly
`types/scene/layer_shell_v1.c` and `include/wlr/types/wlr_layer_shell_v1.h`.
Protocol references: [layer-shell](https://wayland.app/protocols/wlr-layer-shell-unstable-v1)
and [EWMH](https://specifications.freedesktop.org/wm/latest-single/).

## Integration Work

1. Extend host admission beyond keyboard-inert top panels: backgrounds and bottom
   surfaces use existing HOME infrastructure. Apply workspace fullscreen and focus
   policy through existing hosts rather than mapping layer numbers directly to
   Android z-order. Keep pointer admission and keyboard ownership separate.
2. Adapt X11 DOCK/DESKTOP properties and struts to the same bindings. Preserve
   guest-WM ownership for whole-desktop sessions.
3. Expose the shared application catalog and semantic actions to external panels,
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
`WaylandShellLayoutTest` covers protocol-zone conversion, density, remapping,
oversized requests and nested-scope isolation. `ShellSurfaceCatalogTest` covers
lease revocation, obsolete configurations and catalog bounds. The native
geometry fixture covers constrained popups, synchronized subsurfaces,
input holes, publication limits and pixel-only metadata stability;
`WaylandViewGeometryTest` covers immutable snapshots and unavailable input.
`FramePresentationTest` covers generation replacement, stale acknowledgements,
release, failure, reentrant completions and viewport limits.
The Android shell runtime fixture uses the Binder bridge and an isolated layout scope,
checking viewport-aligned pixels/input, Surface replacement and frame receipts;
it does not test external panels hosted over Android application tasks.
`FrameworkSurfaceInputApiTest` and `ShellDesktopChromeHostTest` cover permission
denial, invalid ownership, commit failure/interruption, optional initialization
and grant reset on host replacement. `ShellPanelInstrumentation` is a separate
debug fixture on an explicitly selected active Desktop: it borrows the normal
panel host and sets an exact two-part touchable region with the public
`AttachedSurfaceControl` API. On RM11/API 36 with service UID 2000, the input
dispatcher reports inherited `TRUSTED_OVERLAY`; mouse and touch reach both active
strips while holes pass through to a different-UID Android application without
closing the panel or stealing its keyboard focus. This verifies the Android
host capability, not Wayland frame/region synchronization or other firmware.
`HostedShellFrameTest`, `ShellFrameAdmissionTest`, `DesktopShellWindowTest`,
`FrameworkInputRegionReceiptTest` and `ShellInputRegionReceiptTest` cover geometry,
receipt ordering, replacement, permission rejection, cancellation, deadline failure
and host/owner loss. The `chrome_display` variant of `WaylandRuntimeInstrumentation`
drives a real Wayland family through the production chrome lease, checks alpha
pixels and exact input holes after movement/resizing, and sends input immediately after admission. It also
checks replacement receipts and releases borrowed hosts independently of the session.
`HostedShellWindowsTest`, `HostedShellPlacementTest` and
`WaylandShellPublicationTest` cover automatic reconciliation, pending-window
replacement, stale receipts, host failure, policy concealment, remapping, family
origins, fractional DPI and ordered lifecycle publication. The
`workspace_display` runtime fixture binds to the selected Desktop's actual layout
scope. It checks automatic placement, exact input holes, client-requested movement,
unmap/remap, unsupported-role rejection, reservation cleanup and application
survival after shell revocation. It does not select a workspace on the user's behalf.
Desktop self-tests cover existing Android geometry and fullscreen transitions;
they do not validate external Linux panel protocols or integrated-shell UX.
