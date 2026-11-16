# Themes, settings and action panels

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

## Private space and root configuration

Private space: `PrivateSpace` uses `applicationInfo.dataDir/private_space` through
the ordinary local filesystem. The main menu's root-item visibility choices are
stored per pane in one dialog with left/right columns. Dragging its handles saves a
shared order for configurable roots, including internal storage and one removable-storage
slot. The latter renders mounted SD/USB volumes directly, without an extra group row;
its per-pane visibility also applies to newly attached volumes. Fixed entries retain their slots. Private space defaults
visible; an explicit hidden choice is retained. Root-item and action-panel dialogs
offer Reset for their draft order and visibility; only OK persists it, and Cancel
retains the saved configuration. Its files are excluded from all
system backup/transfer channels, and
configuration-only `.twigbak` never includes them. Known ancestors of Twig's data
directory get UI-only navigation hints when Android denies enumeration;
filesystem copy/compare listings must still fail.

## Security

Security: `secure/Secrets` (two-layer Keystore/master-password encryption of sensitive
fields) + `secure/Backup` (`.twigbak` config backup) + `ui/SecurityUi` (dialogs).
See [docs/lessons/security.md](../lessons/security.md).

## Themes

Themes: AppCompat screens and transparent relays inherit `TwigActivity`, which selects
the complete theme before `super.onCreate`. `AppTheme` lifecycle callbacks only track
screens for theme changes; do not apply palettes or patch window colors there.
OLED has separate opaque and translucent styles. Keep its window background as a
resource reference: older `PhoneWindow` versions cannot use an inline color there.

## Action panel

Action panel: `StripItems` defines stable action identities; `StripItemsDialog` in
the main menu uses drag handles and shared visibility choices.
Order and visibility live in `twig_prefs` and are included in configuration backups. `StripGrid.applyItems`
reorders existing views while resetting GridLayout specs; retain listeners and
orientation changes. `StripGrid` assigns cells from visible order during measurement:
GridLayout's automatic placement counts GONE children and leaves gaps. Main and picker screens apply the same configuration, with
pickers filtering unsupported actions. Pinned landscape navigation stays fixed.
Duplicate defaults to hidden; tapping confirms editable copy names, while holding
copies selected entries immediately into their own parents with `_1`, `_2`, … suffixes.
Holding Rename applies the same numbering as an immediate rename of the originals.
Existing numeric suffixes advance instead of stacking (file_1.txt → file_2.txt). File
extensions are retained; dotted directory names stay whole. Duplicate copies use ordinary
background transfers and retain their generated names across retries.
Properties defaults to hidden and toggles the current/checked items; long-press
requests the second page after metadata finishes loading, falling back to Basic
for one-page cards. Quit is appended to the default action order and hidden by
default; tapping uses the menu confirmation policy, while holding quits immediately.

## Settings and recreation

Settings: `SettingsActivity` (a hand-written standalone settings page: display / grid
view / thumbnails, no preference library). `Prefs.toolbarAtBottom` defaults off;
`ToolbarPlacement` moves the existing browser toolbar below content and matches
navigation-bar tint to its background. Main and share-picker screens follow it;
the main landscape layout keeps the toolbar hidden and the strip centered.
The preference is included in `uiSignature` so returning from settings recreates
the main layout while retaining pane ViewModels.
`Prefs.backAction` applies only to the main screen after pane-local Back handling:
close the screen (default), exit via `confirmExit`, or use the pane's Up action
until the tree top, then close the screen or confirm exit (two separate choices).
Legacy `exit_on_back=true` maps to Exit;
false/absent maps to Close until an explicit choice is saved. Back-triggered exits
always require confirmation; explicit exit shortcuts ask only with active tasks.
Music keeps its long-press Shuffle exit shortcut.
Layout-affecting preferences (row height,
grid, thumbnails) go through `Prefs.uiSignature` — `MainActivity.onResume` compares the
signature and calls `recreate()` on change; in `PaneFragment.onViewCreated`, **when the
VM already has rows, only call `resortAll()` and do not bootstrap again** (after
recreate the VM survives, and a second bootstrap is either skipped by an internal guard
or breaks position restoration).

## Navigation bar tint

**Navigation bar tinted to match the page** (`ui/NavBarTint`, 2026-08-21): the default
solid black navigation bar is a jarring black strip at the bottom in the light theme,
and even in the dark theme it is a shade off from the list background. Tinting it the
same color makes it look transparent. **We do not make it truly transparent** — that
requires `setDecorFitsSystemWindows(false)` and handling insets in layout ourselves
(the music player page already does exactly that, which is why it does not use this).
Two things matter:

- **Take the actual background of the bottom-most layer, not `@color/bg`**: the file
  pane (`fragment_pane`) and both sides of directory compare use `@color/surface`,
  image compare is pure black, and the terminal uses the accessory key bar's
  `TermColors.keyBarBg()` (terminal colors are their own scheme, unrelated to the app
  theme, so its tinting lives in `applyColors()` and follows the scheme). One shade off
  and you can see the seam.
- **★ A light tint is not allowed below API 26**: `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR`,
  which darkens the navigation icons, only exists from API 26. On older systems the
  icons are always white — a light tint means white icons on a white background, so
  home/back are still there but invisible. In that case keep the system default (black).

## Independent row height and text size

**Row height and text size are two independent preferences** (split 2026-08-26,
`Prefs.density` / `Prefs.textSize`): previously one setting drove row height, icon size
and font size together, so "shorter rows but readable text" was impossible.
★ `textSize` **returns `density(ctx)` when it has never been set** — so the appearance
is pixel-identical before and after the split and upgrading users see no jump.
★★ But "fall back to the default" must be cut off **the moment the user changes row
height**: `setDensity` first writes the current text size out explicitly (using the
density level from *before* the change, so the user sees nothing change), otherwise
someone who never touched text size still gets their font size dragged along by row
height and **the split accomplishes nothing**. The assertion is in `RowSizeSplitTest`,
which measures the bound row height in px and font size in px, not "what the preference
reads back as" — the latter is green even on the version where the adapter still derives
the font size from density.
