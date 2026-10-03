# Native Appearance

**Settings > Appearance** configures MagicDesk's native panels, Start and built-in
tools without starting Desktop or acquiring display input. Choose **Global
defaults**, or **Current workspace** when the host supplies a stable workspace
identity. **Use global defaults** removes that workspace's override.
**Appearance mode** selects Follow Android, Light or Dark for either palette
source. It changes colors without resetting typography, controls, backdrops,
geometry, composition, resources or motion. **High contrast** selects the
high-contrast fixed base. **Custom colors** edits the currently displayed tone;
the other tone remains unchanged.

**Choose theme** previews a complete appearance for the selected scope, including
panel composition and Start layout. **Keep changes** applies it; cancellation
restores the previous appearance. Built-in themes use the same JSON schema and
publication path as imported documents:

| Theme | Layout |
| --- | --- |
| [Workbench](../app/src/main/assets/themes/workbench.json) | Compact full-width bottom panel and list-style Start; initially light. |
| [Glass Dock](../app/src/main/assets/themes/glass-dock.json) | Translucent floating dock and grid-style Start; initially dark. |
| [Two Panels](../app/src/main/assets/themes/two-panels.json) | Top status panel with Start, plus a separate bottom task dock. |
| [Contours](../app/src/main/assets/themes/contours.json) | Animated AGSL contour wallpaper and translucent bottom panel. |

Secondary controls adapt to available width. Start, tasks, open tasks and quick
controls remain available. These bundled themes contain no external assets or
service requirements; blur follows system availability. The linked files are the actual
bundled documents and can be edited and imported as JSON. All include light and
dark presentation, using either explicit color variants or the fixed base.

**Common background** sets background opacity (15-100%) and blur radius (0-64 dp)
for native shell panels, popup backgrounds and appearance-bound dialogs in the
selected scope. Select a panel to edit its edge, length, alignment, gaps,
thickness, padding, rounding and space reservation. **Use common background**
inherits the common opacity and blur;
uncheck it to edit both panel-specific sliders, initially set to the common
values. Checking it again removes the panel override. Add or remove panels,
reorder components, or move a component to another panel. Moving the last
component leaves a spacer in its source panel. Removing a panel removes its
components from the composition, not their underlying services or workspace tasks.

JSON and theme ZIP import/export use Android's document picker. Imports,
**Edit configuration** and **Panel components** offer a live preview with **Keep
changes** or cancellation. Dismissing confirmation restores committed state.
Closing Settings cancels its preview and pending work; process restart discards
unconfirmed previews. Edits and imports capture scope and revision so a late
result cannot overwrite a newer configuration or a different workspace.

MagicDesk windows publish their scoped palette's background and panel colors in
Android's task description, retaining each window's title and icon. Native
captions remain rendered by Android; the system may use these colors to select
light or dark decorations. Live theme edits and workspace changes update the
metadata without recreating the window. Linux client contents and their themes
are independent of the host's task description.

Individual Linux application sessions receive Android's current light/dark
preference through [XSettings and the Settings portal](linux-appearance.md).
This follows the Android system theme, including its temporary Desktop override,
not the shell's independent palette. Whole Linux desktops retain their own settings.

Appearance does not restyle third-party applications, their captions, arbitrary
Android dialogs or terminal protocol colors. Widget bindings, Android
application identities and permissions are independent. The **Android** section's
**System theme during Desktop** is a separate, temporary system-wide preference.
It is shared by all Desktop workspaces, independently of the appearance scope,
and is not part of theme JSON or bundles.
The phone touchpad keeps a black input surface and high-contrast toolbar,
independently of shell themes; its help panel uses the normal dialog palette.

## Ownership And Scope

`ShellAppearance` is an immutable density-independent model: semantic palette,
typography, shape, semantic control styles, backdrop, composition, motion, feedback and resources.
`AppearanceStore` owns global defaults and workspace patches in app-private preferences.
`WorkspaceAppearance` resolves patches over global defaults. Stable profile or
workspace keys survive output changes; transient display IDs and live workspace
residency IDs are not persistence keys. Ordinary tools use global defaults unless
their host explicitly supplies a workspace binding.

Global documents inherit omitted fields from the named built-in `preset`
(default `dark`). A workspace patch inherits omitted fields from the global
document, not from its previous patch, and does not accept `preset`. Objects merge
recursively; arrays replace whole lists. A `null` patch value removes a known field,
restoring its built-in default rather than its global override. Omission inherits.
Overriding `composition.panels` owns the
complete panel list, while overriding `typography.scale` still inherits the global
font. Likewise, a workspace edit to `backdrop.opacity` leaves `blurRadiusDp`
inherited, and a blur-radius edit leaves opacity inherited. Panel backdrop edits
replace the panel list without overriding the root common backdrop in the
workspace patch. Empty `{}` restores inheritance. There are at most 32 saved/preview scopes,
keys of at most 512 UTF-8 bytes and 256 KiB total override/preview JSON.

Native bindings use already-prepared immutable assets. File access and decoding
run on workers, never in draw callbacks. Style-only changes retain native Views,
focus, text selection, tool state and PTYs. Composition changes reconcile native
panel contents. Listeners and context bindings are released by their owners.
Themes cannot execute commands, scripts or general-purpose application code.
Optional AGSL wallpaper programs run in Android's graphics pipeline, not a Linux
runtime or a separate graphics engine.

Panels contribute geometry and edge reservations to the existing shared
`ShellLayout`. The native hosts consume that layout without acquiring new task,
focus or input authority. Application task-area topology remains unchanged.

## Document

Documents and workspace patches are at most 32 KiB with bounded nesting. The
current document version is **6**. Unknown fields, invalid types, duplicate
identities and out-of-range values are rejected before the appearance changes.
The authoritative schema is available through **Export JSON Schema**,
`appearance.schema`, and `magicdesk://appearance/schema`. Errors identify
JSON-pointer paths; typed model checks also enforce cross-panel uniqueness.

```json
{
  "version": 6,
  "preset": "dark",
  "colors": { "accent": "#22D3EE" },
  "backdrop": { "opacity": 0.9, "blurRadiusDp": 16 },
  "composition": {
    "panels": [
      {
        "id": "dock",
        "edge": "bottom",
        "style": {
          "length": "content",
          "alignment": "center",
          "maxLengthDp": 1100,
          "sideGapDp": 12,
          "edgeGapDp": 12,
          "thicknessDp": 0,
          "paddingDp": 8,
          "radiusDp": 8,
          "backdrop": { "opacity": 0.88, "blurRadiusDp": 24 },
          "reserveSpace": true
        },
        "components": [ { "type": "start" }, { "type": "tasks" } ]
      },
      {
        "id": "status",
        "edge": "top",
        "components": [ { "type": "quick_controls" }, { "type": "spacer" }, { "type": "clock" } ]
      }
    ],
    "start": { "sections": ["apps", "recent", "tools"], "presentation": "grid" }
  },
  "motion": { "panels": "fade", "taskbar": "fade", "durationMs": 160 }
}
```

Color roles are `background`, `panel`, `surface`, `text`, `muted`, `accent`,
`danger`, `attention`, `hover` and `desktop_text`, written as opaque `#RRGGBB`.
Desktop label color is independent of panel text. Background opacity does
not fade icons or labels. Application artwork stays owned by the application
catalog. Fonts are `sans`, `serif` or `mono`, with scale 0.8-1.3. Control radius
scale is 0-2 and border width 0-3 dp.

## Backdrops

The root `backdrop` supplies the common background for native shell panels and
popup backgrounds through their Window bindings. Themed modals created through
`UiDialogs.themedBuilder` and panel-owned dialogs use the same backdrop;
other app dialogs retain the Android theme. Appearance settings itself is a
full-width Settings page with a bounded content column. The backdrop contains
`opacity` (0.15-1, default 1) and `blurRadiusDp` (integer
0-64, default 0). A panel's optional `style.backdrop` object overrides that
background; omitting it inherits the common backdrop, including later changes.
In the typed model, an inherited `PanelStyle.backdrop` is `null`, not a copied
default. The JSON field is omitted for inheritance rather than written as `null`.

Blur is compositor background blur clipped to the surface's visible rounded
region. It blurs what is behind the background, not the panel, popup or dialog's
own icons, text or other content. Opacity likewise affects only the background.
Radius 0 turns blur off. The requested dp radius is converted using the host's
density and capped at 150 physical pixels.

System blur support is optional and can change while a surface is open. When it
is unavailable or disabled, the background retains the chosen opacity without
blur; the stored appearance does not change. Standard system blur capability
signals control the effect. MagicDesk neither forces blur on nor substitutes
captured screenshots. Availability changes update the presentation without
restarting Desktop or acquiring new services. Blur does
not add a platform, privilege, HOME or input prerequisite to Appearance.

## Palette Sources

`palette.source` is `fixed` (default) or `system`. Fixed palettes use MagicDesk's
light/dark base, with optional `palette.highContrast` (default false). System
palettes use public Android 14+ semantic color resources; high contrast applies
only to the fixed base.
`palette.mode` selects `system`, `light`, or `dark` for either source and never
changes Android's theme. When omitted, fixed colors use the root `preset`'s tone
(dark by default); system colors follow Android. A root `preset` of `contrast`
selects a dark, high-contrast fixed base.

`colors` overrides roles for both tones. `palette.light` and `palette.dark` are
optional role maps applied after the common overrides for the selected tone.
Missing or null colors fall back to the preceding layer. Mode changes preserve
all overrides; source changes also retain explicit colors. No automatic color
inversion is performed. Export writes definitions, not currently resolved Android
colors. Complete exports explicitly reset unspecified overrides in all three
maps, so importing a theme cannot retain colors from the previous definition.

Roles include `background`, `panel`, `surface`, `surface_low`, `surface_high`,
`text`, `muted`, `accent`, `on_accent`, `accent_container`, `on_accent_container`,
`outline`, `danger`, `attention`, `hover`, and `desktop_text`. `transparent` is a
paint role with constant zero alpha, not an editable color. Paired content roles
allow filled buttons without assuming that the ordinary text color contrasts
with the accent. Fixed colors use `#RRGGBB`; paint state layers provide alpha.

`SystemAppearancePalette` resolves and caches presentation snapshots separately
from `AppearanceStore` definitions. Android configuration callbacks invalidate
that cache for either source; no palette polling, privilege request, theme
persistence or preview revision change is involved. Fixed palettes do not read
Android color resources. The same
resolved palette reaches Views, drawables and MagicDesk task descriptions.

## Control Styles

Unavailable actions retain their layout slots and use the shared disabled
state for text, icons and switch labels. Its default content is `muted` at
38% opacity, distinct from ordinary secondary labels. Explicit control-state
styles can override that presentation without changing action availability.

`controls` styles semantic roles: `action_button`, `panel_button`, `search_field`,
`tab`, `switch`, `settings_row`, `app_tile`. These are not widget IDs, launch
commands or panel components. They change presentation without replacing actions,
Views, text selections, application icons, focus or scroll position.

Each role supports `shape` (`rounded` or `capsule`), `radiusDp` (0-48), `borderDp`
(0-4), `paddingHorizontalDp` (0-32), `paddingVerticalDp` (0-24), `minHeightDp`
(0-96), `textSizeSp` (8-32), and `textWeight` (100-900). Missing/null metrics
retain the host baseline. Global typography scale and rounding scale still apply;
capsules follow control bounds. Layout constraints remain owned by the host.
Container labels receive the same role explicitly; application artwork is not
tinted. Autosized labels retain their fit-to-container policy.
Without an explicit paint, content keeps the host's semantic color, including
live status indicators such as charging. Explicit paints define their own paired
content color.

`normal` and `states` (`hover`, `pressed`, `selected`, `focused`, `disabled`)
contain paints: `fill`, `content`, `outline`, `layer` are palette roles;
`layerOpacity` and `opacity` are 0-1. A paint defaults to transparent fill/outline,
ordinary text content/layer, no state layer, full opacity. States are complete
paints, not partial changes to `normal`. Omitted states use shared feedback;
when `normal` is customized they derive hover/press/selection layers from its
content color and reduce disabled opacity. Focus retains a visible outline.
Switches keep the native Android widget and behavior: fill tints its track,
content tints its thumb; `selected` maps to checked. Radius/border do not replace
the platform switch's track and thumb geometry.

`UiControlStyle` owns retained state drawables and captures baseline metrics once.
`UiAppearance` applies scoped paints and typography to existing Views on publication
and attachment. Pointer state changes select a prepared drawable; they do not
parse JSON or rebuild the palette. Geometry is identical across visual states.

Examples: [System controls](themes/system-controls.json) uses Android colors,
filled action buttons, capsule search/tabs and state layers;
[Compact controls](themes/compact-controls.json) uses square, compact native controls.
Both use the same framework and default shell composition.

## Panels And Components

`composition.panels` contains 1-4 panels with unique stable `id` values of 1-32
ASCII lowercase letters, digits, `_` or `-`. Each declares `edge` (`top`,
`bottom`, `left`, `right`), `style`, and 1-24 ordered `components`. A component
kind may occur only once across all panels, except `spacer`.

Panel style uses `length` (`fill`, `content`), `alignment` (`start`, `center`,
`end`), `maxLengthDp` (64-4096), `sideGapDp` and `edgeGapDp` (0-96),
`thicknessDp` (0 for automatic, otherwise 40-160), `paddingDp` (0-16),
`radiusDp` (0-32), optional `backdrop`, `hover`, and `reserveSpace`. Automatic thickness
uses the native host's normal sizing. All lengths are density-independent and
constrained to the available viewport. Thickness and padding determine automatic
square button extents and icon sizes on either axis. Explicit component widths
and control padding override those defaults. Control paints do not own panel
geometry. Start/end alignment follows the panel's
long axis. Reserving panels contribute edge intervals; rectangular window
consumers conservatively avoid an edge band. Non-reserving panels overlay the
workspace. These reservations do not alter Android task-area ownership.

Components are `start`, `tasks`, `show_desktop`, `open_tasks`, `notifications`,
`keyboard_layout`, `phone_screen`, `quick_controls`, `battery`, `clock`, `spacer`.
They retain production action controllers and semantic automation identities.
Each accepts `widthDp` (0 for automatic, otherwise 32-240), `minViewportDp`
(0-4096), `visibility` (`always`, `expanded`, `external`), and `group` (`start`,
`center`, `end`; default `start`). Groups anchor independently; the center group
is centered when space permits and shifts to avoid side groups. Components keep
their order within each group. Overflow scrolls rather than overlapping. The Start component
accepts `label` (at most 32 printable characters; empty shows the Start symbol); Clock accepts `clock` (`time`,
`date`, `date_time`). Battery accepts `battery` (`percent`, `icon`, `both`), and
Tasks accepts `indicator` (`line`, `dot`, `none`). These presentations reuse
existing battery observations and task state. Unavailable controls do not start
their services. [Grouped Dock](themes/grouped-dock.json) combines these options.

`composition.start` declares ordered `sections` (`recent`, `apps`, `running`,
`tools`), `presentation` (`grid`, `list`), `navigation` (`scroll`, `pages`; default
`scroll`), `tileWidthDp` (80-200; preferred column width), `iconSizeDp` (24-64), and
`gapDp` (0-24). `apps` is required and sections cannot repeat. Grid and list use
recycled visible entries. Columns adapt to width and icon bounds; page capacity
uses the measured viewport and cell height, including themed text. There is no
fixed row count. Both retain the shared catalog, profile identity, search,
context actions and launch destinations.

## Resources

`resources.icons` maps semantic symbols to built-in symbols: `desktop`, `windows`,
`notifications`, `keyboard`, `controls`, `files`, `terminal`, `settings`, `search`,
`camera`, `video`. These are single-step substitutions, not recursive aliases or
Android resource IDs.

A theme ZIP contains `theme.json`, optional images under `icons/`, media under
`wallpapers/`, and `.ttf` or `.otf` fonts under `fonts/`. `resources.iconAssets`
maps semantic symbols to bundle-relative image paths; `resources.font` and
`resources.wallpaper` select a font and wallpaper. Icons accept static PNG,
JPEG and WebP. Wallpapers also accept animated WebP/GIF and MP4/WebM video,
using Android's installed decoders. The portable document omits `resources.bundle` or leaves it
empty. Import attaches the verified content digest; a JSON-only document with
assets therefore references an already-installed bundle.

```json
{
  "version": 6,
  "resources": {
    "iconAssets": { "files": "icons/files.png" },
    "font": "fonts/interface.ttf",
    "wallpaper": "wallpapers/workspace.jpg"
  }
}
```

ZIP import validates schema and all referenced asset kinds before atomic
publication. It rejects traversal, ambiguous paths, duplicate entries, invalid
media, scripts and non-allowlisted formats. Limits include 32 MiB archive size,
64 MiB expanded size, 256 entries, 16 MiB per entry, 4 MiB per font, 8192-pixel
image dimensions and 24 million aggregate decoded pixels. Installed storage is
bounded to 16 bundles and 256 MiB. Imported resources are immutable and
content-addressed; validation never follows external paths or fetches URLs.
**Remove unused theme bundles** explicitly reclaims unused storage, retaining
global/workspace current, committed, preview and staged resources. Cleanup and
appearance publication are coordinated by the shared store.

Animated sources are limited to 4 megapixels. Videos must contain one unencrypted
video track, have a duration of at most five minutes, and declare no more than
60 fps when frame-rate metadata is present. Import validates a decodable poster;
playback errors retain that poster. Compressed animation/video data and posters
count toward the aggregate 96 MiB live appearance-media budget across workspaces.

Wallpaper uses a fixed physical-display center crop, independent of system-bar
insets and process density. Each workspace owns its playback instance. Video
loops silently without requesting audio focus. Hidden or off-screen hosts,
power saving, reduced motion and disabled Android animations release playback
and display the static poster. Losing HOME focus alone does not stop playback.
MagicDesk Files also offers **Set as desktop wallpaper** for local images and
MP4/WebM files, with the same decoder and a 64 MiB source-file limit.

ZIP export captures the effective edited document and its verified resource bundle,
removing the installed digest from portable `theme.json`. It does not export an
outdated original document. JSON export captures the global document, or the
selected workspace's sparse patch; it carries no binary assets.

### Wallpaper Selection

The effective workspace appearance selects the wallpaper in this order:

1. `resources.shader`, using `resources.wallpaper` as its static poster when set.
2. `resources.wallpaper` from the selected resource bundle.
3. The shared Desktop media file, `/storage/emulated/0/Desktop/.magicdesk/wallpaper`.
4. The bundled MagicDesk image when no custom source is selected.

**Set as desktop wallpaper** in Files updates the shared media file, not an
appearance override. It remains behind any effective theme wallpaper or shader.
**Use MagicDesk wallpaper** in the desktop context menu clears wallpaper/shader
selection for that workspace's appearance scope and removes the shared media
file. Other workspaces retain their own theme resources; workspaces using the
shared file return to the bundled image. Neither action changes Android's system
wallpaper. Use scoped Appearance configuration for independent backgrounds.

### AGSL Wallpapers

`resources.shader` selects a single-pass Android `RuntimeShader` wallpaper.
It works throughout the supported API range (Android 14+); rendering needs a
hardware-accelerated Android host, not shell access, Termux or a Linux session.
`resources.wallpaper`, when provided alongside a shader, must be a static image
and becomes its poster. Otherwise `fallbackColor` supplies a solid poster
(default `#202428`). Set `shader` to `null` to disable it, including in a
workspace that inherits a global shader. Omitting it preserves inheritance.

```json
{
  "version": 6,
  "resources": {
    "shader": {
      "fps": 30,
      "fallbackColor": "#202428",
      "floats": [{ "name": "speed", "value": [0.2] }],
      "colors": [{ "name": "ink", "value": "#557A70" }],
      "source": "half4 main(float2 p) { float v = 0.7 + 0.3 * sin(p.x / md_resolution.x * 6.28 + md_time * speed); return half4(ink.rgb * v, 1); }"
    }
  }
}
```

The host supplies declarations and bindings for:

- `float2 md_resolution`: output width and height in physical pixels.
- `float md_time`: elapsed animation seconds, restarting when playback resumes.
- `floats`: up to 16 named parameters, each with 1-4 finite values in
  `[-10000,10000]`; these become `float`, `float2`, `float3` or `float4` uniforms.
- `colors`: up to 16 named `#RRGGBB` values, bound as color-managed
  `layout(color) uniform half4` parameters.
- `textures`: up to four `{ "name": "paper", "path": "wallpapers/paper.png" }`
  inputs from the verified bundle. These become `uniform shader` inputs;
  `paper.eval(position)` samples image-pixel coordinates with linear filtering
  and clamped edges. Only static PNG/JPEG/WebP images, up to 4 megapixels each,
  are accepted. Texture dimensions also count toward bundle pixel limits.
- `signals`: up to 16 named device-data bindings, described below. These become
  `float2` uniforms: `.x` is the value, `.y` is availability (0 or 1).

Uniform names are unique across all lists, start with an ASCII letter and
contain at most 32 letters, digits or underscores. `md_`, `sk_` and `gl_` prefixes
are reserved. Do not redeclare generated uniforms in `source`. The source is at
most 16 KiB UTF-8, inside the ordinary 32 KiB document limit. Return premultiplied
color from `half4 main(float2 position)` as required by
[AGSL](https://developer.android.com/reference/android/graphics/RuntimeShader).

All configuration uses the normal schema, workspace patches, preview/cancel,
JSON/ZIP import/export and automation paths. A shader without textures or an
image poster needs no bundle. Schema validation checks the typed description;
asset preparation compiles and binds the program on a worker before applying or
previewing it. Invalid programs leave the current appearance intact. Each
output gets its own mutable shader; immutable textures may be shared. Source,
textures and poster count toward the live appearance-media budget.

`fps` is 1-60 (default 30), paced by Android vsync. Drawing performs no resource
reads or recompilation. The ordinary visibility, display-power, power-saving,
reduced-motion and **Animate wallpaper** policies stop callbacks and retain the
poster. There is no touch/sensor capture, network access, multipass pipeline or
application-window effect. Programs should be kept inexpensive: source and
frame-rate limits do not guarantee GPU execution time for arbitrary AGSL code.

### Device Signals

Shaders may explicitly subscribe to normalized device measurements. For example,
this wallpaper brightens as CPU usage rises, using a neutral level when counters
are unavailable:

```json
{
  "resources": {
    "shader": {
      "fps": 30,
      "fallbackColor": "#202428",
      "signals": [
        { "name": "activity", "source": "system.cpu.usage", "fallback": 0.2, "smoothingMillis": 800 }
      ],
      "source": "half4 main(float2 p) { return half4(mix(float3(0.08, 0.12, 0.16), float3(0.25, 0.65, 0.5), activity.x), 1); }"
    }
  }
}
```

| Source | Value | Availability |
| --- | --- | --- |
| `system.cpu.usage` | Aggregate busy CPU fraction, 0-1; idle includes I/O wait. Not load average. | Requires the already-authorized shell service, readable aggregate counters and two samples. No process enumeration. |
| `system.memory.usage` | `1 - available / total` system RAM, 0-1. | Public Android `ActivityManager.MemoryInfo`; no shell required. |
| `battery.level` | Charge fraction, 0-1. | Android battery broadcasts; missing battery/data remains unknown. |
| `battery.charging` | 1 while charging, 0 while discharging, not charging or full. | Android battery status; unknown status is not reported as zero. |

Each binding has `name`, `source`, optional `fallback` (0-1, default 0) and
`smoothingMillis` (0-10000, default 0). Smoothing is an exponential response with
that time constant, independent of FPS. Unknown or expired data immediately uses
the fallback and sets `.y` to 0. Both declaration and initial fallback binding
are validated during preparation without acquiring any data source.

CPU and RAM are sampled at most once a second per active source; battery uses
events and has no polling timer. CPU sampling stops when shell access disappears
and restarts with a fresh baseline on reconnection. CPU/RAM observations expire
after three seconds, so a stalled source cannot look like current data. Theme
code cannot request permissions, execute commands or choose arbitrary sources.

Sources are shared across active outputs and subscribed only during playback.
Hiding or stopping the wallpaper releases its subscriptions; the last subscriber
stops the source and releases its receiver/worker. Themes without `signals`
create no telemetry subscription, timer or worker. A battery-only theme does not
read CPU/RAM or contact the shell service. Frames consume cached samples through
preallocated uniform arrays, with no service calls, I/O or per-frame allocations.
The bundled Contours theme has no device subscriptions.

`appearance.get` includes process-wide `signalSources` with active subscriber
counts, availability and normalized values. Reading this diagnostic snapshot does
not initialize or sample sources. Workspace preview/cancel, patches and JSON/ZIP
import/export use the same binding schema.

## Feedback And Motion

Each panel's `style.hover` configures task-icon feedback: `scale` (1-2, default
1), `liftDp` (0-32, default 0), and `radius` (0.5-3 stable item widths, default
1.5). Influence falls smoothly with distance from each slot; lift points inward
from the panel edge. `motion.feedbackMs` and `curve` govern interpolation.
Reduced motion and disabled Android animators disable these transforms.

The host allocates a bounded overflow frame without changing the panel's paint,
content slots or workspace reservation. Its touch region contains the base panel
and visible transformed items, not the surrounding transparent rectangle.
Pointer actions map back into the original controls, retaining their task,
context-menu and accessibility identities. The public Android root-surface API
publishes that region only when it changes. Finger scrolling remains owned by
the normal View hierarchy. No animation runs while idle. A panel with visual
overflow retains translucency but disables window-wide background blur: Android's
public blur API cannot restrict it to the panel's smaller paint bounds. Menus and
panels whose paint fills their window retain normal background blur.

`feedback` maps `normal`, `hover`, `pressed`, `selected`, `focused`, `disabled`
and `outline` to palette roles, including `transparent`. Native controls retain
geometry as their state changes; defaults have no permanent idle backplate.

`motion.panels` and `motion.taskbar` accept `none`, `fade`, `slide`, `scale` or
`slide_scale`. Enabled effects fade in the native content; slide and scale add
their respective transforms. `distanceDp` is 0-32 (default 12), and `scaleFrom`
is 0.85-1 (default 0.96). Taskbar content enters from its panel edge; popup
content enters from below. Translation is bounded to half the content dimension.
`durationMs` is 0-400, `feedbackMs` is 0-250, and `curve` is `linear`, `ease_out`
or `smooth`. `reduced` disables effects, as does Android's disabled animator
setting.

`motion.wallpaper` (default `true`) controls animated wallpaper independently of
panel effects. Settings exposes **Animate wallpaper** for the global appearance
or the selected workspace. Disabling it keeps the source and shows its poster;
re-enabling starts playback again. Neither media nor AGSL wallpaper requires
Termux or a Linux graphics session.

Window frames, background fills, blur and shell reservations stay at their final
geometry. Transformed content remains a child of an Android ViewGroup, which
maps pointer events through the same matrix used for drawing. Child-menu input
hosts and outside-dismissal regions do not move. Effects begin on layout's
pre-draw callback, and detach, dismissal or theme replacement releases the
animation and restores the original properties. Focus and close never wait for
an animation; application-task transitions are separate.

## Tool Layouts

`UiToolLayout` supplies the shared native page, system/IME insets, bounded content
column, scrollable action row and settings-row composition. Settings, Appearance,
Files, Control Panel, Device Setup, Diagnostics, task/application inspectors,
graphics-session management and prompts use these primitives without changing
their service or window ownership. `UiContentColumn` resolves its cap against the
current host width and density on measurement.

Files uses `UiAdaptivePane`: navigation occupies a sidebar in wide windows and a
horizontal strip in narrow ones. Resizing retains the same browser, selection,
navigation and scroll views. The console shares only the outer page and inset
policy; its terminal field stays specialized. X11, Wayland and display-viewer
surfaces retain their own edge-to-edge content and scaling contracts.

## Automation

All scoped operations accept optional `workspaceKey`. Omit it for global defaults;
confirm/cancel must use the same scope as the exact returned `previewId`.

- `appearance.schema`: schema; observation.
- `appearance.validate`: resolve `document` without mutation; observation.
- `appearance.get`: effective and committed documents, sparse patches, known
  override keys, revision, preview ID and process-wide cached `signalSources`;
  observation.
- `appearance.themes`: bundled theme IDs, localized names and complete validated
  documents for the same preview path; observation.
- `appearance.apply`: replace `document` or selected patch; control.
- `appearance.preview`: temporary `document` or patch; control.
- `appearance.confirm` / `appearance.cancel`: commit/discard exact preview; control.
- `appearance.preset`: apply `name` while retaining geometry/resources/motion; control.
- `appearance.reset`: reset global appearance or remove selected override; control.
- `appearance.import`: `path`, optional `format` (`zip` default, `json`), returning
  an unconfirmed preview; requires **control and files_read**.
- `appearance.export`: existing `directory`, optional `name` and `format`,
  returning an actual new file `path`; requires **files_write**. Existing files
  are not overwritten. Use `files.download_begin` on the returned path.
- `appearance.prune`: explicitly remove unused app-private bundles; control.
  Returns removed digests and count, without changing external files.

MCP file operations reuse verified shared Files descriptors and require file
service availability; SAF operations use the user's document grant. Neither path
provisions Desktop. One preview may be active per scope. Stale IDs cannot revert
later changes. A changed revision rejects a delayed import rather than silently
replacing a newer edit. Results identify accepted configuration, not completed
pixel presentation or durable disk I/O.

Examples: [multiple panels](themes/multi-panel.json), [compact dock](themes/compact-dock.json),
[light workspace](themes/light-workspace.json), [quiet controls](themes/quiet-controls.json).
