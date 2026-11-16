# Website tasks and customization

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

## Bookmarks, search, permissions and page lifecycle

Websites: `WebsiteStore` persists per-site options in `twig_websites`; `WebsiteActivity`
opens each bookmark in its own reusable document task using the shared WebView profile.
`SearchEngine` stores the global search homepage and `{query}` URL template in
`twig_prefs` (included in configuration backups). The website heading menu opens
`WebSearch.configure`; text viewer/editor, PDF and terminal selection actions use
the system `ACTION_WEB_SEARCH` chooser through `WebSearch`. Website and preview WebViews
keep their native selection menus. The exported `WebSearchActivity` makes Twig an
optional system search handler. Long-pressing the action strip's Search opens this
entry explicitly, using Twig's configured homepage without a chooser, in Main and
both pickers. `SearchStripItem` retains that gesture while file search is disabled;
ordinary taps stay disabled and scrolling cancels the pending long press.
After the app lock gate, `WebSearchActivity` uses the configured template
and its own `.search` task affinity so system `NEW_TASK` requests cannot bring
MainActivity forward instead of returning to the calling website, viewer or external app.
Empty queries use the homepage. Only requests handled by Twig share the `twig://search` document task,
carrying the requested URL as an extra; reused intents load the new request only
after the app lock gate. Bookmarks and externally opened browser pages keep their
separate document identities.
`TerminalSelectionMenu` replaces Termux's unused More entry with sharing and
installed `ACTION_PROCESS_TEXT` handlers. It reads the live selection at click time,
passes text as read-only and ignores results; external processing must never write
returned text to a terminal session.
`WebsiteDialogs` shares its editor with website defaults: new bookmarks copy display
options only. UA/JS/CSS/hide/block settings apply globally at runtime; per-site UA overrides
the global value, while scripts/styles/selectors/block rules combine with it. Keep raw
per-site fields separate from effective settings, including debug edits and browser saves,
so global values never become permanent site additions. Open pages observe global changes;
locked pages defer applying them until unlock.
Each bookmark can opt into `applyToDomain`: external links, system searches and navigation
borrow its raw configuration on the exact same hostname, then merge global additions once.
Enabling a second bookmark for that host disables the earlier choice; directly opened saved
bookmarks retain their own configuration on their saved origin. Resolve again for top-level
navigation and history, never for subresources. Keep the document's identity separate from
its current page configuration. Domain sharing does not inherit capture/location grants or
background execution; headers and Basic credentials retain their original scheme/host/port
scope. Temporary and borrowed-page debug edits stay in memory, without rewriting the source
bookmark. Prepare changed UAs before intercepted GET loads; history/POST loads that bypass
interception finish before changing UA, since changing it mid-load restarts the request.
`WebsiteMediaPermissions` handles HTTPS microphone/camera requests with explicit
origin-specific consent followed by Android runtime permissions. Grant only selected,
recognized capture resources, after the WebView resumes. Saved bookmarks have independent
ask/allow/deny policies and may remember prompt decisions for their saved origin only;
temporary pages always ask. Foreign frames and redirected origins cannot inherit them.
Navigation, WebView cancellation and leaving the foreground invalidate pending requests,
except while Android's own permission prompt is active. Camera/microphone hardware stays
optional; Chromium audio capture also requires MODIFY_AUDIO_SETTINGS.
Set mediaPlaybackRequiresUserGesture=false before loading/restoring website pages:
Chromium latches the gesture lock when each media element is created; changing it
during permission callbacks does not unlock existing camera preview elements.
`WebsiteLocationPermissions` applies the same per-bookmark origin checks to geolocation.
Request coarse/fine Android permissions together and accept approximate-only access;
never retain decisions in WebView's shared geolocation store (callback retain=false).
Website defaults do not inherit these grants. Changing a bookmark's origin resets all
three permissions; revoking one refreshes the matching live page.
`WebsiteLinkActivity` accepts external HTTP(S) VIEW intents and forwards only the validated
URL to a temporary `WebsiteActivity` document using those defaults. Temporary pages enter
the bookmark list only through the explicit add action; debug edits stay in memory.
`WebsiteCustomization` scopes JS/CSS, hidden selectors,
navigation headers and Basic auth to the saved origin. Headers apply to same-origin
GET page navigation, not all resources. `WebsiteRequestBlocker` handles manual resource
rules, excluding main-frame navigation. `WebsiteDebugPanel` / `WebsiteElementInspector`
provide page tools; `WebsiteRemoteDebug` owns the session-only computer debugging switch.
`WebsiteDownloads` / `WebsiteDownloadTask` use Twig's picker and transfer service,
defaulting to `Downloads/twig`. WebView file uploads also use `PickerActivity`, with
single/multiple selection matching the page's request and stream URIs returned to WebView.
Opt-in background sessions use `WebsiteBackgroundService`
(see [website background execution](../lessons/website-background.md)). Basic credentials
and header values use `Secrets`; `.twigbak` includes website configuration with those
fields decrypted for export and re-encrypted on import, but excludes WebView cookies
and sessions. Import restores the website list as a whole, rather than merging entries.
Website tasks gate cold starts, reused shortcut intents and resumes with `SecurityUi`;
defer reading credentials and loading/restoring pages until unlocked. An opaque cover
blocks the live page while prompting without pausing opted-in background execution.
While locked, `FLAG_SECURE` prevents screenshots/task snapshots. After unlocking,
each website's "Prevent screenshots" option controls it and defaults off,
regardless of whether a master password is configured.
