# Website background execution

> One section of Twig's hard-won lessons. See [AGENTS.md](../../AGENTS.md).

## Music stops in the background and resumes when the page returns (2026-10-18)

The page stayed loaded, but music stopped after some time with the screen locked or
another app open. Merely skipping `WebView.onPause()` for opted-in websites did not
resolve it. That change only removes an explicit WebView pause; it does not establish
an Android background execution session.

Read-only system event logs on the Android 16 test device contained repeated
`am_freeze [pid,com.twig.app]` / `am_unfreeze` pairs. This supports cached-process
freezing as a cause, rather than proving that WebView was destroyed. Android's
[cached-app freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)
keeps processes in RAM while suspending their threads; returning to the Activity
unfreezes them. Do not disable the device-wide freezer to fix an app lifecycle bug.

An explicitly opted-in, live website session now holds `WebsiteBackgroundService`, a
`specialUse` foreground service for general website tasks with its own notification/channel. Start it from
`DefaultLifecycleObserver.onResume`, when Lifecycle is actually RESUMED: checking
that state from inside Activity.onResume can be too early, and silently skips start.
Backgrounding does not release the session. Closing the page, disabling the option,
removing its document task, or closing websites from the notification releases it.
Multiple website pages share the service; closing one must not stop the others.
Detaching a stopped service before requesting its shutdown avoids losing a new
session opened while the old service is being destroyed.

This is session-based and applies to general page tasks as well as media. The
notification remains until the page/session is closed, even without audio playing.
Use the general special-use type and declare its website-session purpose in the
manifest; a media-only type would misrepresent non-media page tasks. Store the option
as allowBackgroundRunning, defaulting to false when absent. Keep the notification
channel ID stable so users retain their notification settings. No page scripts,
MediaSession emulation, extra wake lock, or automatic page restoration are involved.
Use START_NOT_STICKY: a restarted process has no live authenticated WebView to retain.
Notifications carry names and bookmark IDs, never URLs, headers or credentials.

Tests cover real Activity lifecycle transitions, foreground type/notification,
multi-page cleanup, task removal, stale shutdown, orphan starts, and API 24. Those
checks establish lifecycle behavior; they do not measure continuous playback or
freezing on a real device. Verify the affected site's lock-screen playback separately.

## Capture permission is granted but the microphone cannot start (2026-10-22)

The user reported that capture worked in Firefox but failed in Twig despite the permission
prompt. The first implementation declared CAMERA and RECORD_AUDIO, and callback tests
passed, but it omitted MODIFY_AUDIO_SETTINGS. Chromium's
[AudioManagerAndroid](https://chromium.googlesource.com/chromium/src/media/+/refs/heads/main/base/android/java/src/org/chromium/media/AudioManagerAndroid.java) requires
both audio permissions to enumerate and select communication capture devices; without
them it reports that no audio device is available for recording. This is a normal install-time
permission, not an additional runtime dialog. Combined audio/video requests can fail when
the audio source cannot start. This omission is a concrete defect; the camera-only result
still needs a device check.

Android runtime permission results arrive before Activity.onResume. Keep the originating
request while that system prompt pauses the Activity, avoid pausing the WebView for this
prompt, and defer granting capture until resumeWebsite has resumed the WebView. Navigation,
WebView cancellation, stop and destruction still invalidate pending consent. Unit tests must
assert that a permission result while paused does not immediately grant capture.

Bookmarks store independent microphonePermission/cameraPermission ask/allow/deny choices.
The prompt offers an unchecked remember checkbox only for the opened saved bookmark when
both the top-level page and requesting origin match its saved scheme/host/port. Temporary
browser intents always ask, even if that URL also exists as a bookmark. Dismissing a prompt
saves nothing; explicitly blocking can be remembered. New/default settings do not inherit
capture grants. Editing a bookmark to another origin resets both policies to ask. Backup
JSON preserves the per-bookmark policies, and unknown/absent values default to ask.
Revoking a permission in the editor refreshes the matching live page to release existing
streams; remembering an allow decision must not reload and cancel its own request.

Robolectric covers permissions, lifecycle and persistence, but cannot validate real Chromium
capture or camera hardware. Retest microphone-only, camera-only and combined recording on
the device after installing the rebuilt APK.

## Camera connects but the website preview stays black (2026-10-22)

The user saw a black preview while Android's camera-in-use indicator remained on.
CameraService showed streaming from front camera device 1. Read-only inspection of the
actual WebRTC sample established: a live, enabled, unmuted video track, readyState=4,
videoWidth=480, videoHeight=640, but paused=true and currentTime=0. Capture succeeded;
playback had not started.

The first attempted fix changed mediaPlaybackRequiresUserGesture to false in the native
capture grant callback and reset it on navigation. The user confirmed this did not work.
Chromium's [AutoplayPolicy](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/html/media/autoplay_policy.cc)
latches locked_pending_user_gesture_ in the media element constructor. Updating the
WebSettings property after the preview video exists does not clear that element's lock.
A test that merely checks the setting after grant misses this browser behavior.

Configure mediaPlaybackRequiresUserGesture=false before any page load or restore, and
retain it across navigation. Remove the late grant callback and navigation reset. This
also permits ordinary website media autoplay; Android/WebView's public setting does not
provide the browser's document-activation policy. Microphone and camera access still
require the independent origin-specific consent and Android runtime permissions.
Do not recreate the site's video elements or inject recurring play calls: those approaches
can break event listeners, page logic and deliberate pauses.

Tests check initial playback configuration and independent capture consent. After installing
the build with the setting moved before page loading, the user confirmed on 2026-10-23
that the camera preview worked. Keep this device result separate from callback-only tests.

See Android's [WebSettings media playback setting](https://developer.android.com/reference/android/webkit/WebSettings#setMediaPlaybackRequiresUserGesture(boolean))
and the [WebRTC camera sample](https://github.com/webrtc/samples/blob/gh-pages/src/content/getusermedia/gum/index.html).

## Website geolocation follows bookmark consent (2026-10-23)

Handle WebChromeClient.onGeolocationPermissionsShowPrompt separately from media capture.
The callback always uses retain=false: persistent decisions belong to a saved bookmark,
not WebView's shared origin store. Check the requesting origin and current top-level URL
against that bookmark's scheme/host/port. Temporary pages and foreign frames cannot use
or save bookmark decisions. Store independent locationPermission ask/allow/deny values;
old or unknown values default to ask, backups retain them, and defaults do not.

Request ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION together, accepting either
runtime grant. Approximate-only approval is valid and must not cause repeated precision
requests. Both manifest permissions are foreground-only; location/GPS/network hardware
features are optional. Request no background location or location service. Navigation,
request withdrawal and leaving the foreground invalidate pending callbacks, except that
Android's own permission UI may pause the Activity; its result waits until page resume.

Reference: [Android runtime location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
and [WebView geolocation callback retention](https://developer.android.com/reference/android/webkit/GeolocationPermissions.Callback).

## Location consent succeeds but Google Maps and the simple demo keep waiting (2026-10-23)

The user reported that Firefox returned a location while Twig's Google Maps and the
W3Schools geolocation example did not. Package inspection confirmed both coarse and
fine runtime grants. LocationManager recorded Twig's fused-provider subscriptions but
zero delivered locations. Firefox's provider history also recorded zero new deliveries.
The system location switch was on; available providers were passive, fused and GPS,
with no network provider. The newest GPS cache was more than two days old, based on
its elapsed-realtime timestamp compared with device uptime. Do not log coordinates
when collecting this diagnostic; provider names, update counts and cache age suffice.

The browser implementations explain why these observations can coexist. Firefox's
[GeckoAppShell](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/geckoview/src/main/java/org/mozilla/gecko/GeckoAppShell.java)
reads last-known locations from all providers and immediately delivers the chosen one
before requesting updates. Chromium's
[LocationProviderAndroid](https://github.com/chromium/chromium/blob/main/services/device/geolocation/android/java/src/org/chromium/device/geolocation/LocationProviderAndroid.java)
normally waits for updates; its direct last-known-location path applies only when the
passive provider is the only enabled provider. On this device, cached position is a
plausible explanation for Firefox's result despite zero new system deliveries. It is
not evidence that Twig's consent failed or that the cached point is the current location.

This observation is not a completed root-cause diagnosis. The user subsequently noted
that the phone had been screen-off during inspection and that both Firefox and WeChat
could locate successfully. Screen state, foreground page state, location delivery history
and browser-specific providers must be compared during the same live reproduction.
Do not infer a device-wide location failure from a background snapshot, or treat Firefox's
cache behavior as proof that its result is stale. The earlier guidance to fix the system's
network provider was premature. No application patch has yet been justified by these logs.
Do not silently return a days-old position as current or add a third-party location service
merely to make a demo stop waiting.

A subsequent synchronized reproduction kept Twig's W3Schools page visible and focused,
with the screen awake and location AppOps in foreground mode. LocationManager showed
an active Twig fused subscription and the underlying GPS subscription. A one-shot
getCurrentPosition diagnostic with enableHighAccuracy=true, maximumAge=0 and a 20-second
timeout switched both system subscriptions to HIGH_ACCURACY, then returned JavaScript
error code 3 (Timeout expired) after 20 seconds. The probe logged success/error, duration,
accuracy and timestamp age only, never coordinates. This rules out screen-off and missing
foreground permission for that particular reproduction. It does not establish how Firefox
or WeChat obtained their successful results, nor prove a universal WebView defect. No
production code was changed based only on this provider comparison. An independent
network-location fallback would introduce a new recipient of Wi-Fi/cell observations and
requires an explicit user preference before choosing that approach.

## Withdrawn network location experiment (2026-10-23)

An optional BeaconDB fallback was implemented and tested after the user initially
accepted a separate provider. It did not resolve the reported device behavior. The user
then explicitly rejected that approach; the client, WebView injection, setting, radio
scan permission, AndroidX WebKit dependency and associated tests were removed. Existing
saved configuration keys are ignored. Do not restore the service as part of a system
geolocation fix.

The user repeatedly observed successful Firefox/WeChat results and an updated Android
location access record, while Twig showed neither. An active LocationManager subscription
alone does not establish a working request/result path. Compare actual delivered results,
last-location access and AppOps during a synchronized reproduction. Do not treat lack of
access history as proof that a permission is missing, or subscription presence as proof
that all application behavior is correct.

AOSP's LocationProviderManager notes the fine/coarse AppOp when it delivers a location
or returns a non-null last-known location, rather than merely registering a listener.
GeckoAppShell reads last-known locations before subscribing; Chromium's active-provider
path waits for updates. This is an explanation to test against the reproduction, not
proof that Firefox's observed result came from a stale cache. Source references:
[Android location delivery](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/location/provider/LocationProviderManager.java)
and [Gecko system location](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/geckoview/src/main/java/org/mozilla/gecko/GeckoAppShell.java).
Do not manufacture a successful location or an AppOps record just to match another
browser, or return an old fix with a new timestamp.

In the follow-up synchronized comparison, the user confirmed that Firefox had just
returned a result. Its FINE_LOCATION AppOp was updated 18 seconds before inspection,
but its aggregate fused-provider delivered-location count remained zero, as did Twig's.
All three system last-location monotonic timestamps were unchanged between the Twig
and Firefox snapshots. The newest system fix (GPS/passive) was approximately three days
old; the fused cache was older. Together with Gecko's explicit last-known reads, this
supports a cache-read explanation for that reproduction's access-history difference.
GeckoAppShell's native callback also omits the original Location timestamp; do not copy
that behavior into Twig or imply that a newly updated AppOp proves a fresh GPS fix.
BeaconDB removal passed 185 website JVM tests, full release assembly and libre Kotlin
compilation. The version and signing identity were preserved for the installed test APK.
The system-provider positioning issue remains unresolved; removal is not a location fix.
