# alauncher

Android app for a Facebook Portal Go (Android 10, API 29, arm64, 1280x800, no
Google Play Services). It shows a full screen slideshow of random pictures from
an [Immich](https://immich.app) server. Tapping the slideshow opens a list of
apps to launch. The long-term goal is to use it as the device's home screen; it
isn't registered as one yet (no `CATEGORY_HOME` intent filter), so for now it
is started like any other app.

This file is the shared context for AI assistants (Gemini in Android Studio
reads `AGENTS.md`; Claude Code reads `CLAUDE.md`, which imports this file). Keep
it up to date when the design changes.

## Build, test, run

- `./gradlew assembleDebug`: build. `./gradlew testDebugUnitTest`: unit tests
  (JVM, no device needed).
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` then
  `adb shell am start -n com.nicobrailo.alauncher/.SlideshowActivity`.
- `tools/push-config.sh SERVER_URL API_KEY [MAX_PICTURES [PERCENT [SLIDE_SECONDS]]]`:
  pushes the settings to the device and restarts the app, so they don't have
  to be typed on the touch screen. Only works with debug builds, since it uses
  `run-as`.
- If a screenshot is all white or black, the Portal's screen is probably off:
  `adb shell input keyevent KEYCODE_WAKEUP`.
- Logs: `adb logcat -s SlideshowActivity RandomAlbumPicker` (every swipe logs
  its direction).

Toolchain: AGP 9 with its built-in Kotlin support (there is no separate
`kotlin-android` plugin), Gradle version catalog in `gradle/libs.versions.toml`,
minSdk 29, Java 11. Views and XML layouts, not Compose.

## Code map

All sources are in `app/src/main/java/com/nicobrailo/alauncher/`.

- `immich/ImmichClient.kt`: Immich REST client (OkHttp + `org.json`). Lists
  albums, lists an album's images (paginated `POST /search/metadata`), fetches
  a picture's metadata (`GET /assets/{id}`, including EXIF and people) and
  builds picture URLs. Every failure is an `ImmichException`. `AlbumSource` is the
  interface the picker uses, so tests can fake the server.
- `immich/RandomAlbumPicker.kt`: decides which picture comes next (see below).
  Returns an `AlbumPicture`: the picture ID plus the album it came from.
- `PictureDescription.kt`: turns metadata into the slideshow's overlay text.
- `PictureHistory.kt`: the pictures the user can swipe back through.
- `Settings.kt`: settings stored in SharedPreferences: keys, defaults and valid
  ranges.
- `SettingsActivity.kt` + `res/layout/activity_settings.xml`: settings with two
  tabs. The Slideshow tab is the preferences in `res/xml/preferences.xml`, whose
  keys must match `Settings.KEY_*`.
- `media/NowPlaying.kt` + `media/MediaListenerService.kt`: what another app is
  playing, and the controls for it, through `MediaSessionManager`. Reading it
  needs notification access, which is granted to the (otherwise empty)
  notification listener service; without it the panel stays hidden.
  Apps leave stale sessions behind: Jellyfin keeps one that claims to be playing
  for days, and it ignores even the system's own pause. So a session claiming to
  play is only believed while `AudioManager.isMusicActive` is true (or it plays
  to another device), and such a session is skipped when choosing which one to
  show. A paused session is shown for 15 minutes, so it can still be resumed.
  Sound stopping isn't reported, so the panel is also re-checked every 5s.
- `SystemSettingsFragment.kt`: the System tab. One item per thing the app needs
  from the system, with its state and a button that opens the system dialog.
  These intents must be started **for a result** (`systemDialog.launch`): the
  role dialog identifies the caller that way and closes immediately otherwise.
- `ScreenAdminReceiver.kt` + `res/xml/device_admin.xml`: device admin with the
  force-lock policy only, so the app can turn the screen off.
- `ScreenControl.kt`: the two bits of screen behaviour the app may control. It
  writes `screen_off_timeout` (when the screensaver starts), which needs both
  the `WRITE_SETTINGS` declaration in the manifest **and** the user's grant:
  `Settings.System.canWrite()` is false without either. It also turns the screen
  off with `DevicePolicyManager.lockNow()` for the night rule, and holds the
  night-window arithmetic (which wraps past midnight), unit tested.
- `SlideshowState.kt`: which picture is being shown, as one object for the whole
  process (`SlideshowState.shared`): the settings, client and picker, the
  history, the picture picked ahead, the metadata cache and whether the details
  are expanded. The home screen and the screensaver both bind to it, so the
  slideshow carries on across the switch instead of jumping to an unrelated
  picture. Picking is behind a mutex, so only one of them picks at a time.
- `SlideshowController.kt` + `res/layout/slideshow.xml`: the slideshow itself:
  the views, the timer and the gestures over `SlideshowState`. Used by both the
  home screen and the screensaver; `interactive` is false in the screensaver,
  where a touch wakes the device instead.
- `SlideshowActivity.kt`: the slideshow as the home screen. It declares HOME and
  LAUNCHER, `singleTask` and `stateNotNeeded`. It only becomes the home screen
  once the user picks it.
- `SlideshowDreamService.kt` + `res/xml/dream.xml`: the same slideshow as the
  system screensaver (an Android "dream"). `isInteractive` and the other window
  properties are set in `onCreate()`, before the window is created: setting them
  in `onAttachedToWindow()` leaves the window focusable, and it then swallows
  every touch, so the screensaver can't be dismissed and the device looks
  frozen. It also ends the screensaver itself in `dispatchTouchEvent`.
  A dream has **no AppCompat theme**, so `res/layout/slideshow.xml` may only use
  framework attributes (`?android:attr/...`). An AppCompat one like
  `?attr/selectableItemBackgroundBorderless` inflates fine in the activity but
  crashes the screensaver, and a crashed screensaver leaves whatever was
  underneath on screen — which looks like the Portal launcher stealing the home
  screen back.
- `PortalState.kt`: what the Portal is doing, for the debug overlay. Tapping the
  clock shows it.
- `SystemBars.kt`: hides the status and navigation bars. The system shows them
  again whenever a window loses focus, so activities call `hideSystemBars()`
  from `onWindowFocusChanged`, not only at startup.
- `AppListActivity.kt` + `res/layout/activity_app_list.xml`, `item_app.xml`,
  `item_folder.xml`, `dialog_folder.xml`: grid of apps and folders, the
  long-press menu, drag and drop, and the settings button.
- `apps/LauncherModel.kt`: the installed apps, through `LauncherApps` rather
  than `queryIntentActivities`, so other profiles are included, icons carry the
  profile badge, and the system reports installs and removals while the list is
  open. It also launches apps and opens their app info.
- `apps/Folders.kt`: the folder rules as pure functions (`FolderOps`), so they
  can be unit tested. An app is in at most one folder, and a folder with fewer
  than two apps dissolves.
- `apps/FolderStore.kt`: saves the folders as JSON in their own
  SharedPreferences file.
- `overlay/HomeButtonService.kt` + `overlay/HomeButtonApps.kt`: a home button
  drawn on top of another app, for apps that leave no way back to the launcher.
  WhatsApp (the phone build, `com.whatsapp`) is the reason: its own header
  replaces the Portal's Back/Home bar, and the Portal has no home key. It's
  per app, chosen in the app list's long-press menu, and needs the "display over
  other apps" permission from the System tab. `AppListActivity` starts the
  service when it launches a flagged app and `SlideshowActivity.onStart` stops
  it, so the button is only there while that app is in front. The service runs
  in the foreground (with a quiet notification), because it has to outlive the
  launcher's own screen.

Unit tests are in `app/src/test/`. `android.util.Log` is a no-op there
(`unitTests.isReturnDefaultValues`), and `org.json` is a stub, so code that
parses JSON can't be unit tested on the JVM.

## Behaviour

**Picking pictures** (`RandomAlbumPicker`). This is a port of the C library
[libimmich-random](../libimmich-random) (`random.c` and `browse.c`; `immich.h`
documents the API). Keep the two behaving the same.
- It fetches the album list once and caches it. `refresh()` marks the list
  stale, and it's re-fetched when the next album is picked. If that re-fetch
  fails, the old list is kept.
- Albums are visited in a random order, each once per round, and a new round
  never starts with the album that was just shown. Albums with `assetCount == 0`
  are skipped.
- From each album it takes a random sample of its images, in album order
  (oldest first), using selection sampling (Knuth's Algorithm S). The sample
  size is `percent`% of the album (rounded, at least 1; 0 means 100%), then
  capped at `maxPictures` (0 means no cap).
- When the sample runs out, it moves on to the next album.

**Slideshow** (`SlideshowActivity`).
- It shows the `PREVIEW` size (~1440px) and loads it with Coil, sending the
  `x-api-key` header. Coil caches pictures in memory and on disk.
- Three `ImageView`s hold the picture on screen (`cur`) and its neighbours
  (`prev`, `next`), placed one screen width to either side. As the user moves
  through pictures, the views swap roles instead of reloading.
- The picker's next picture is fetched and loaded ahead of time (`upcoming`), so
  moving forward never waits for the network.
- While swiping, the pictures follow the finger. Letting go past 25% of the
  width, or after a fling, slides to the neighbour; otherwise the picture slides
  back. Dragging towards a neighbour that isn't loaded is resisted and always
  slides back.
- Swipe left (finger moves right to left, like a photo gallery): forward. Swipe
  right: back. Moving forward always works once the next picture has loaded:
  when the newest picture is on screen, the next one is `upcoming`.
- `PictureHistory` keeps the last 20 pictures (`SlideshowState.HISTORY_SIZE`).
  Going back stops at the oldest kept one. Going forward first walks the kept
  pictures again.
- The home screen and the screensaver share all of that, so switching between
  them keeps the picture, the history and the expanded details. Only one of them
  is on screen at a time, so only one runs the timer.
- The timer slides to the next picture every `slideSeconds` (default 30) while
  the activity is visible. If the next picture isn't ready because picking or
  loading it failed, the tick retries instead. Every swipe restarts the timer,
  which then moves forward from wherever the user is, including from inside
  the history.
- Tapping the screen opens `AppListActivity`. There's deliberately no
  long-press action: long-press fires when a swipe starts slowly.
- The bottom left corner shows a clock (`TextClock`, always `HH:mm`) and a
  line of metadata for the picture on screen: "year - place", e.g. "2024 -
  Amsterdam". The place is the city, or the state or country if the city isn't
  known. Tapping the line expands it to labelled details (album, date, place,
  coordinates, camera, lens, exposure, people, description, file name), and
  tapping again collapses it. The choice stays when the picture changes. Each
  slot fetches its picture's metadata alongside the image, so the text is ready
  when the picture slides in; if the metadata can't be fetched, the line is
  hidden.
- The bottom right corner shows what another app is playing (title, artist and
  album, artwork when the app provides one) with previous, play/pause and next.
  The screensaver shows the text but no buttons, since a touch ends it. Grant
  notification access in the System tab, or with
  `adb shell cmd notification allow_listener com.nicobrailo.alauncher/com.nicobrailo.alauncher.media.MediaListenerService`
  (`disallow_listener` to revoke).
- Errors are shown in a text overlay over the picture.
- When the activity starts again, it reloads the settings. If they changed, it
  rebuilds the client and picker and clears the history. If not, it calls
  `picker.refresh()`.
- Everything in the activity runs on the main thread.

**App list** (`AppListActivity`).
- It shows every activity with `ACTION_MAIN` + `CATEGORY_LAUNCHER`, except this
  app, sorted by label. The `<queries>` element in the manifest makes those
  activities visible on Android 11+.
- The list reloads in `onStart()`, so installed and removed apps show up.
- Tapping an app launches it in a new task and closes the list, so Back from
  the app returns to the slideshow.
- The gear button (top right) opens `SettingsActivity`, which is the only way to
  reach the settings on the device.
- Long-pressing an item starts a drag. Dropping an app on another app makes a
  folder; dropping it on a folder adds it. A long-press that never moves shows a
  menu instead: app info and uninstall for an app (hidden for system apps and
  other profiles), rename and ungroup for a folder. Inside an open folder, the
  menu can also take an app out.
- Entries are sorted by name; there's no manual ordering.
- The long-press menu also offers a home button over that app (see
  `HomeButtonService`), which is off for every app until the user asks for it.

**Settings**: server URL, API key (needs `album.read`, `asset.read` and
`asset.view`), max pictures per album (default 20), percent of each album
(default 0 = all) and seconds per picture (default 30). Under "Screen": how long
before the screensaver starts (0 leaves the system's value alone) and an opt-in
"turn the screen off at night" with its hours (default 00:00 to 06:00, off).

**Night screen off**. While the slideshow is on screen it checks every 30s (the
first check 20s after it appears, so someone walking in has time to touch it)
whether the hour is inside the night window. If it is, and nothing was touched
in the last 5 minutes, it calls `lockNow()`. The Portal's presence detection
wakes the screen again when it sees someone, and the next check switches it off
again, so the screen stays dark unless the user actually touches it. Changing
these settings doesn't disturb the pictures: only the server and sampling
settings reset the slideshow.

## Portal platform notes (measured on the device, 2026-09-17)

- Production `user` build, no root. adb runs as `shell`. The Portal launcher
  (`com.facebook.alohaapps.launcher`) holds the HOME role.
- **Presence detection** runs in the Portal's camera process
  (`aloha.CameraServiceController: Notify people presence`, about every 30s
  while it sees someone). `PresenceManager` (package
  `com.facebook.alohaservices.presence`) then reports user activity to the power
  manager without changing the lights, which keeps the device awake. When the
  screen is off, it wakes it (`Full_Wakeup_PresenceManager`). The camera keeps
  watching while the screen is off.
- A third-party app **can't** read presence directly. The broadcasts
  (`RECEIVE_PRESENCE_TRANSITION`), the state content providers
  (`ACCESS_STATESDB`) and `IDLE_SCREEN_*` are all `signature` or `privileged`.
- What an app **can** see: the effects. `SCREEN_ON`/`SCREEN_OFF` and
  `DREAMING_STARTED`/`DREAMING_STOPPED` broadcasts (register them in code, not
  in the manifest), and the light sensor.
- **Timers:** after `screen_off_timeout` (system setting, 300000 ms) without
  activity, the device starts the screensaver (dream). After `sleep_timeout`
  (secure setting, 1200000 ms by default) since the last activity, including
  presence, it goes to sleep. Both can be changed with
  `adb shell settings put`.
- **Portal's ambient mode** is a screensaver:
  `screensaver_components=com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService`,
  a windowless dream that starts the home activity. When presence wakes the
  screen, the system starts dreaming, not the home activity.
- **Dark room clock:** the Portal launcher switches to a full-screen clock when
  the light sensor reads dark (`AmbientLightSensor: luxDark`). Covering the
  camera also covers the light sensor.
- A keep-screen-on flag in our app would block sleep entirely, and with it the
  "screen off when nobody is around" behaviour.
- `tools/capture-presence.sh OUT_DIR` records logcat, power, dream, top
  activity and light-sensor changes, for experiments like these.
- `tools/setup-device.sh` does the parts an app can't: `sleep_timeout` (secure
  setting) and, as a shortcut, the screensaver and home screen. Everything else
  is granted from the System tab.
- `sleep_timeout` does not stick: it was back at the Portal's 1200000 twice
  after the device dreamt and woke again, so something on the Portal resets it.
  Don't rely on it; to control when the screen goes off, use the device admin
  (`ScreenAdminReceiver`) and `DevicePolicyManager.lockNow()`.
- Declaring HOME means that, until the user picks a default home app, pressing
  Home shows a chooser between alauncher and the Portal launcher.
- Some apps leave no way back to the launcher. With `com.whatsapp` in front the
  Portal's SystemUI still reports its Back and Home buttons as visible, but
  nothing is drawn and taps in that area do nothing; Jellyfin and Spotify, also
  sideloaded, are fine. It happens with the Portal's own launcher as home too,
  so it isn't ours. The way out is the home button overlay above, or
  `adb shell input keyevent KEYCODE_HOME`.
- Swiping up from the bottom edge opens the Portal's Control Center (volume,
  brightness, power). It has no Home button.
- The floating bug-report pill is an overlay window (`BugnubPillViewService`)
  drawn by `com.facebook.aloha.system.services`, not by the bug-reporter app
  (disabling that app changes nothing). No setting controls it;
  `tools/setup-device.sh bugnub off` denies that package the overlay app-op and
  restarts it. That stops any other overlay from the same package too.
- Waking the screen (presence, or the power key) starts the **screensaver**, not
  the home activity, so the screensaver has to work for the Portal to show our
  slideshow on wake. If it fails, the system resumes whatever activity was last
  on screen, which can be the Portal's `HomeActivity` even when we hold the HOME
  role. `adb logcat -d | grep -E 'DreamController|AndroidRuntime'` shows which
  dream started and whether it died.

## Conventions and constraints

- Don't add dependencies on Google Play Services or Firebase. The device doesn't
  have them.
- Cleartext HTTP is allowed app-wide (`usesCleartextTraffic`), because Immich
  servers on a home network are often plain `http://`.
- Immich IDs are UUIDs. Check them with `ImmichClient.isValidId` before putting
  them in a URL path.
- Comments explain why, not what. They are full sentences, written in the style
  of the existing code.
- Keep `AGENTS.md` in sync with any change to behaviour or structure.
