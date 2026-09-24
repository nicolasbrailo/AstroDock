# astrodock

Android app for a Facebook Portal Go (Android 10, API 29, arm64, 1280x800, no
Google Play Services). It shows a full screen slideshow of random pictures from
an [Immich](https://immich.app) server. Tapping the slideshow opens a list of
apps to launch. It is the device's home screen: it declares `CATEGORY_HOME`,
and `tools/setup-device.sh` makes it the default, which the pinned shortcuts
(see `PinShortcutActivity`) depend on.

This file is the shared context for AI assistants (Gemini in Android Studio
reads `AGENTS.md`; Claude Code reads `CLAUDE.md`, which imports this file). Keep
it up to date when the design changes.

## Build, test, run

- `./gradlew assembleDebug`: build. `./gradlew testDebugUnitTest`: unit tests
  (JVM, no device needed).
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` then
  `adb shell am start -n com.nicobrailo.astrodock/.SlideshowActivity`.
- `tools/push-config.sh [OPTION]...`: pushes the settings to the device and
  restarts the app, so they don't have to be typed on the touch screen
  (`--server-url`, `--api-key`, `--max-pictures`, `--percent`,
  `--slide-seconds`, the album filter's `--album-include`,
  `--album-exclude`, `--album-from-year`, `--album-to-year`, the weather's
  `--weather` and `--weather-place`, and the broker's
  `--mqtt-enabled`, `--mqtt-host`, `--mqtt-port` and `--mqtt-audio-announce`;
  `--show` prints what the
  device has, `--help` lists them all). It reads the preferences file off the
  device and only replaces the settings it was given, so the rest are left
  alone, including the ones it knows nothing about (the screen tab and the rest
  of the MQTT one); `--reset` replaces the whole file instead. Android writes
  one setting per line, which is what makes editing it with `sed` and `awk`
  sound enough for this. Only works with debug builds, since it uses `run-as`.
- `tools/build-apks.sh [OUT_DIR]`: runs the unit tests, builds both APKs and
  leaves them in `~/Downloads` as `AstroDock-debug.apk` and
  `AstroDock-release.apk`, the names the Apps tab looks for in a GitHub
  release. Upload the debug one: `run-as` needs it, and so do the two scripts
  below. It prints each APK's sha256, which is what the Apps tab compares with
  the digest GitHub publishes. AGP signs a release build with the debug
  keystore too, since `app/build.gradle.kts` has no signing config; keeping
  that key is what lets an installed AstroDock be updated rather than removed
  first.
- `tools/force-uninstall.sh [PACKAGE]`: uninstalls the app. Needed because
  `adb uninstall` fails with `DELETE_FAILED_DEVICE_POLICY_MANAGER` once the
  user has granted the device admin: an active admin can't be uninstalled, and
  `dpm remove-active-admin` only removes an admin marked `testOnly`. The system
  does drop an admin whose receiver stops existing, so the script installs a
  component-less APK with the same package name over the app, then uninstalls
  that. It signs it with the debug keystore, so, like `push-config.sh`, it only
  works on debug builds. It also takes the predecessor
  (`com.nicobrailo.alauncher`) as an argument.
- If a screenshot is all white or black, the Portal's screen is probably off:
  `adb shell input keyevent KEYCODE_WAKEUP`.
- Logs: `adb logcat -s SlideshowActivity RandomAlbumPicker` (every swipe logs
  its direction).

Toolchain: AGP 9 with its built-in Kotlin support (there is no separate
`kotlin-android` plugin), Gradle version catalog in `gradle/libs.versions.toml`,
minSdk 28, Java 11. Views and XML layouts, not Compose. The Portal is
Android 10 (API 29), but nothing here needs more than API 28, so older
devices can run it too; the one API 29 call, `RoleManager`, is guarded (see
`SystemSettingsFragment.kt`). `./gradlew lintDebug` fails the build on a
`NewApi` error, so it is what says whether that is still true.

## Code map

All sources are in `app/src/main/java/com/nicobrailo/astrodock/`.

- `immich/ImmichClient.kt`: Immich REST client (OkHttp + `org.json`). Lists
  albums, lists an album's images (paginated `POST /search/metadata`), fetches
  a picture's metadata (`GET /assets/{id}`, including EXIF and people) and
  builds picture URLs. Every failure is an `ImmichException`. `AlbumSource` is the
  interface the picker uses, so tests can fake the server.
- `immich/AlbumFilter.kt`: which of the server's albums the slideshow uses, by
  name (comma separated globs, to keep and to leave out) and by the years its
  pictures were taken. It is all done here, on the album list, because Immich
  can't help: `GET /albums` only filters by an exact name or by ownership. The
  list already carries the name and `startDate`/`endDate` of every album, so
  filtering costs no extra request. Pure functions, unit tested.
- `immich/RandomAlbumPicker.kt`: decides which picture comes next (see below).
  Returns an `AlbumPicture`: the picture ID plus the album it came from. Albums
  its `AlbumFilter` doesn't keep are left out of the rotation.
- `PictureDescription.kt`: turns metadata into the slideshow's overlay text.
- `PictureHistory.kt`: the pictures the user can swipe back through.
- `Settings.kt`: settings stored in SharedPreferences: keys, defaults and valid
  ranges. Percent of each album, seconds per picture and the screen-off delay
  are sliders (`SeekBarPreference`), which store an int, so `load()` converts
  the string a text input would have left behind the first time it reads one.
- `SettingsActivity.kt` + `res/layout/activity_settings.xml`: settings with two
  tabs. The Slideshow tab is the preferences in `res/xml/preferences.xml`, whose
  keys must match `Settings.KEY_*`.
- `NightHoursPreference.kt` + `res/layout/preference_night_hours.xml`: the
  night hours as one `RangeSlider` with a knob for each end. It still stores
  them as the strings `night_start_hour` and `night_end_hour` the text inputs
  it replaced left behind, so `Settings.load()` didn't change. A night usually
  wraps past midnight, which two knobs on a 0 to 23 scale can't show, so the
  scale runs from 12:00 to 11:00 the next day (`NightHours`, unit tested), and
  the start knob then can't pass the end one; they stay at least an hour
  apart. The price is that a window including noon can't be set, and a stored
  one (from before, or pushed over adb) is shown as the defaults.
- `media/NowPlaying.kt` + `media/MediaListenerService.kt`: what another app is
  playing, and the controls for it, through `MediaSessionManager`. Reading it
  needs notification access, which is granted to the (otherwise empty)
  notification listener service; without it the panel stays hidden. On the
  Portal only adb can grant it, so `tools/setup-device.sh` does (see the
  platform notes); the System tab's button opens a screen that closes itself.
  The app reads the sessions once when it starts, so it has to be restarted
  after the grant.
  Apps leave stale sessions behind: Jellyfin keeps one that claims to be playing
  for days, and it ignores even the system's own pause. So a session claiming to
  play is only believed while `AudioManager.isMusicActive` is true (or it plays
  to another device), and such a session is skipped when choosing which one to
  show. A paused session is shown for 15 minutes, so it can still be resumed.
  Those 15 minutes are timed by `media/SessionActivity.kt` (unit tested), not
  by the session's own update time: that only says when the app last published
  a state, and Spotify publishes the same paused one again every few minutes,
  which kept the panel up for hours. So the clock only restarts when the state,
  position, queue item or title actually differs, or when the panel switches to
  another app. There is one clock for the process, so the handovers between the
  home screen and the screensaver don't restart it. A remote session (casting,
  or Spotify following another device over Connect) is treated like a local
  one, so the panel controls it too; only the `isMusicActive` check is skipped
  for it, since nothing is audible here.
  Sound stopping isn't reported, so the panel is also re-checked every 5s.
- `InstallAppsFragment.kt` + `apps/Installable.kt`, `apps/ApkInstaller.kt`: the
  Apps tab, which offers apps worth having on a Portal (it has no app store).
  `INSTALLABLE_APPS` is the catalogue; add entries there. An entry with an
  `apkUrl` (a URL that always serves the current release, as F-Droid publishes)
  is downloaded to the cache and handed to the system installer through a
  FileProvider; one without (most projects number their downloads per release)
  opens its download page in a browser instead. AstroDock itself is an entry
  too, and updates itself: an entry with a `githubRepo` reads that repository's
  latest release and offers the APK in it. Whether that APK is the one already
  running is settled by the sha256 GitHub publishes for it against the hash of
  the installed APK (`sameBuild`): a version name is inside the APK, where
  nothing short of downloading it would find it, and a tag only says anything
  as far as it's written like the version name, so the tag is compared with
  `BuildConfig.VERSION_NAME` only for a release old enough to have no digest.
  A locally built APK is never the published one, so on a development device
  this always offers the update. Which file it takes is decided by
  `githubAssets`, the names it prefers, read from the release rather than built
  into `apkUrl`, because `releases/latest/download/<name>` redirects to the
  newest release whether or not that release holds a file by that name, so a
  name that's wrong is only found out when the download 404s. `pickApkAsset`
  and `sameBuild` hold both decisions as pure functions, and are unit tested.
  An entry with a `versionFeed` (Firefox) is for a project that publishes its
  current version rather than a URL that serves it: Mozilla has no "latest"
  URL for Android (`download.mozilla.org` only knows the desktop builds), but
  `product-details.mozilla.org/1.0/mobile_versions.json` says the stable
  version under `version`, and the archive's path is made of it
  (`firefoxApkUrl`, arm64 only). The row reads the feed every time the tab is
  shown, since it is one small request, and compares it with the installed
  `versionName`: equal is up to date, anything else offers the download. The
  version must be digits and dots (`isPlainVersion`) before it goes in a URL,
  which also keeps a beta like `157.0b5` out. If the feed can't be read, the
  row offers what it would without it: open, or the download page.
  Keep a debug build first in the list: `tools/push-config.sh` and
  `tools/force-uninstall.sh` go through `run-as`, which a release build doesn't
  allow. Installing needs
  `REQUEST_INSTALL_PACKAGES` plus the user allowing "install unknown apps",
  which is asked for before downloading and is also listed in the System tab.
  A download and the install that follows hold a screen-bright wake lock
  (`ScreenControl.keepScreenOn`), because the screensaver would otherwise cover
  the installer's confirmation: a keep-screen-on window flag is no use once
  another app is in front. It is released when the fragment is resumed again
  (installed or cancelled), and has a 10 minute timeout as a backstop. The
  downloaded file is thrown away once its app is on the device, but not while
  the installer still has it: AstroDock counts as installed the whole way
  through updating itself, and deleting the file under the installer fails it
  with "There was a problem while parsing the package". Progress is reported
  from the download's own thread, so the fragment hops to the main thread
  before it touches the view.
- `weather/Weather.kt`, `weather/WeatherClient.kt`, `weather/PlaceCache.kt`:
  the current temperature and sky, over the clock. Open-Meteo
  (https://open-meteo.com) needs no account and no API key, and nor does its
  geocoder, so the only settings are the toggle and a place name. The name is
  looked up once and kept in `PlaceCache` (its own preferences file, since it is
  derived from a setting rather than being one), and asked again only when the
  name changes, so the weather carries on while the geocoder is down. Text that
  is two numbers in range (`parseCoordinates`) is used as coordinates without
  asking anyone, for a house outside any town. The geocoder answers a bare
  ambiguous name with the most populous match (a plain "Springfield" is the one
  in Missouri) and understands "Springfield, Illinois" or "Paris, US", but not
  the same without the comma, which finds nothing; a name it doesn't know gets
  a reply with no `results` at all. That is why the Slideshow tab's summary is
  what the name was found as (`placeLabel`), looked up every time the screen
  opens, rather than what was typed.
  `conditionOf` folds the WMO 4677 code the server reports onto the seven
  `res/drawable/ic_weather_*.xml` icons, since hail, freezing drizzle and the
  rest of that vocabulary don't survive being drawn at 34dp; an unknown code is
  a cloud. `millisToNextHour` is what makes it refresh on the hour rather than
  an hour after the slideshow started, and works in the local zone because a
  few zones are offset by half an hour or three quarters of one. Both are pure
  and unit tested; only the fetch and the JSON are not.
- `mqtt/StateReporter.kt`, `mqtt/MqttSettings.kt`, `mqtt/Occupancy.kt` +
  `MqttSettingsFragment.kt`, `res/xml/mqtt_preferences.xml`: publishes what the
  device is doing to an MQTT broker, on the topics of the homeboard bridge
  (`~/src/homeboard/dbus-mqtt-bridge/README.md`): `state/bridge` (online record,
  with the offline one preset as the last will, so a crash still reports),
  `state/occupancy`, `state/slideshow_active` and `state/displayed_photo`. All
  retained, QoS 0. It also subscribes to `<prefix>cmd/#` and carries out the
  commands that mean something here (`mqtt/Command.kt`): `ambience/next`,
  `ambience/prev`, `ambience/set_transition_time_secs` (`{"secs":n}`, saved as
  the slideshow setting), `ambience/announce` (`{"timeout":n,"msg":"..."}`,
  shown over the pictures; timeout 0 stays up, an empty message clears it) and
  `ambience/set_album_filter`
  (`{"name":"holidays-*,Pets","exclude":"Screenshots","from_year":2019,"to_year":2021}`,
  saved as the album settings; every field is optional and the payload replaces
  the whole filter, so `{}` shows every album again) go to
  whichever slideshow is on screen, while `presence/force_on` (a wake lock, 30
  min) and `presence/force_off` (device admin lock, so it needs "Turn the screen
  off" from the System tab) are handled by the reporter itself, because they
  must work with nothing on screen. So is `ambience/announce_audio`
  (`{"uri":"http://.../x.mp3","msg":"Dinner is ready","volume":40}`, only
  `uri` required), through
  `audio/AnnouncementPlayer.kt`: it downloads the file in full (20 MB cap), then
  plays it with transient audio focus, so music pauses rather than ducks. The
  volume is a percentage of the device's media volume, set for the length of
  the announcement and put back afterwards unless someone changed it
  meanwhile. A `volume` that is missing or not a number from 0 to 100 plays at
  40% rather than dropping the announcement.
  The MQTT tab's "Play audio announcements" switch (on by default) can turn
  them off; it is read as each command arrives rather than kept in
  `MqttSettings`, so switching it doesn't reconnect, and an announcement it
  stops is dropped with a log line and nothing on screen.
  Announcements play one at a time, under a partial wake lock, so they work
  with the screen off. When the sound starts, `msg` (or "Audio announcement in
  progress" without one) is shown like a text announcement with no timeout,
  and when it ends, `EndAnnouncement` gives it 10 more seconds. That only
  applies if it is still the announcement on screen, which the `owner` token
  settles: anything announced in the meantime is newer and stays. One that can't be fetched or played says why as a 30s
  text announcement, which only shows if a slideshow is on screen. Its own
  playback makes `AudioManager.isMusicActive` true, which `NowPlaying` takes as
  proof that a session claiming to play really is, so a stale session can show
  in the media panel for as long as an announcement lasts. The homeboard's renderer commands
  (`set_svg_overlay`, `set_render_config`, `set_embed_qr`, `set_target_size`)
  are logged and dropped. Retained commands are ignored: they arrive again on
  every reconnect, and acting on them would replay an old command.
  Nothing on screen depends on MQTT, so a broker that can't be reached would
  only show up in the log: the reporter therefore keeps an `alert` (the last
  thing that went wrong, null while it's fine) and the home screen shows it in
  a corner. Paho reports a connect that fails, but not one that never finishes:
  its `connectionTimeout` only covers opening the socket, so a port that
  accepts the connection and then says nothing (an HTTP server behind the wrong
  port number) hangs for ever with neither `connectComplete` nor `onFailure`.
  `watchConnect` gives it 20s and then drops the stuck client, which is also
  what lets the next `applySettings()` start a fresh one. Paho only reconnects
  by itself once it has connected at least once, so a first connect that fails
  waits for that call, which every `SlideshowController.start()` makes. The photo payload follows the field names the homeboard's photo
  provider publishes (`albumname`, `albumpath`, `filename`, `local_path`,
  `src_url`, `gps`, `reverse_geo`, `EXIF DateTimeOriginal`), so one renderer
  reads either device. Immich has no albums on disk, so `albumname` is the
  Immich album and the paths are the server's copy of the original;
  `reverse_geo` holds the place names already in the picture's EXIF, and
  nothing is looked up. `src_url` needs the API key to fetch.
  Departures from the spec, both because this is a Portal: `distance_cm` is
  never published (no mmWave sensor), and occupancy is a guess from the screen
  (`Occupancy`), with a `source` field saying which. The slideshow runs in two
  places and they hand over in either order, so each reports itself by name and
  `slideshow_active` is true while either is showing, and false while the
  night rule has it covered in black. The topic prefix and the client id default
  to the name the device was given at setup, sanitised for topics (e.g.
  `portaloft-portal/`): "astrodock" is the software, the unit is the Portal.
  The Portal's setup keeps that name only as the Bluetooth name (the secure
  `bluetooth_name`, with " Portal" added to what was typed), and the global
  `device_name` is just the model, `PortalGo` on every one of them, so
  `MqttSettings.systemName` reads the Bluetooth name (`BluetoothAdapter`, then
  the setting) and only falls back to `device_name`. Each device needs its own
  prefix, or they overwrite each other's retained topics, so before connecting
  (`prefixIsFree`) the reporter reads the retained `state/bridge`, on a
  connection of its own with no last will, and if it holds another
  `machine_id` it stays off the broker and shows why in the alert corner. It
  doesn't try those settings again until they change or the app restarts. An
  empty record is free: `mosquitto_pub -r -n -t <prefix>state/bridge` hands a
  prefix over. `machine_id` is `ANDROID_ID` (a generated UUID only without
  one), because it has to survive a reinstall: a device with a new id would
  find its own old record and refuse its own name. When testing it, stop the
  app before publishing a fake record: its last will replaces the fake when
  the process dies.
- `SystemSettingsFragment.kt`: the System tab. At the top, a list of what is
  missing and what doesn't work without it (each `Item`'s `missing`), then a
  note to run `tools/setup-device.sh`, which grants all of it; "Everything is
  set up" when nothing is. Below, one item per thing the app needs from the
  system, with a tick once it's granted, and while it isn't, a button that
  opens the system dialog. Granted items have no button, and nor do `adbOnly`
  ones, whose system screen can't grant them: the secure settings grant
  everywhere, and on a Portal (`Build.MANUFACTURER` "Facebook") the device
  admin and notification access too.
  These intents must be started **for a result** (`systemDialog.launch`): the
  role dialog identifies the caller that way and closes immediately otherwise.
  That dialog is the only thing in the app that needs API 29 (`RoleManager`),
  so `homeRoleIntent` returns null below that and the home screen settings,
  where the user picks the launcher by hand, are opened instead. An `Item` with
  an `action` instead of an `intent` is one the app can set itself, and its
  button runs that and rebuilds the list rather than opening anything; high
  contrast text is the only one.
- `TextContrast.kt`: the `high_text_contrast_enabled` switch behind that item.
  It is a secure setting, so it rides on the same adb grant as `sleep_timeout`
  and does nothing without it, which is why the System tab shows the two next
  to each other.
- `ScreenAdminReceiver.kt` + `res/xml/device_admin.xml`: device admin with the
  force-lock policy only, so the app can turn the screen off. On the Portal the
  System tab's button doesn't work (see the platform notes), so
  `tools/setup-device.sh` grants it with `dpm set-active-admin`.
- `ScreenControl.kt`: the two bits of screen behaviour the app may control. It
  writes `sleep_timeout` (**half** the screen-off delay, because the Portal
  takes two rounds of it to switch the screen off; secure, needs the adb grant)
  and
  `screen_off_timeout` (when the screensaver starts, needs both the
  `WRITE_SETTINGS` declaration in the manifest **and** the user's grant:
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
  whose touches go through `onScreensaverTouch` instead (see below).
- `SlideshowActivity.kt`: the slideshow as the home screen. It declares HOME and
  LAUNCHER, `singleTask` and `stateNotNeeded`. It only becomes the home screen
  once the user picks it.
- `SlideshowDreamService.kt` + `res/xml/dream.xml`: the same slideshow as the
  system screensaver (an Android "dream"). `isInteractive` and the other window
  properties are set in `onCreate()`, before the window is created: setting them
  in `onAttachedToWindow()` leaves the window focusable, and it then swallows
  every touch, so the screensaver can't be dismissed and the device looks
  frozen. It also ends the screensaver itself in `dispatchTouchEvent`, but only
  when the finger lifts: a screensaver that isn't interactive never lets its
  views see a touch, and ending it on the first one left the rest of the
  gesture with nowhere to go (Android doesn't move a gesture to the window
  uncovered underneath), so every action took a second tap. Until then it
  hands the touches to `SlideshowController.onScreensaverTouch`, which does
  what the home screen would have: a tap opens the app list (the player on the
  media panel, the details on the picture's line), and a swipe moves to the
  neighbour at once, without following the finger, since the screensaver is
  about to go. The home screen then shows the result through `SlideshowState`.
  At night, under the cover, a touch only wakes.
  `DreamService` doesn't pass changes to its window's attributes on to the
  window manager once the window is up (an activity does), so
  `onWindowAttributesChanged` does it; without that the night rule's backlight
  override had no effect in the screensaver.
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
  from `onWindowFocusChanged`, not only at startup. It also lays the content out
  at full size regardless of the bars (`setDecorFitsSystemWindows(false)`, and
  the screensaver does the same), so a bar that appears for a moment doesn't
  shrink and shift the picture. `Theme.AstroDock.Fullscreen` asks for a full
  screen window up front and turns the window animation off: hiding the bars in
  code alone made the Portal's status bar flash in every time the screensaver
  handed over to the home screen.
- `AppListActivity.kt` + `res/layout/activity_app_list.xml`, `item_app.xml`,
  `item_folder.xml`, `dialog_folder.xml`: grid of apps and folders, the
  long-press menu, drag and drop, and the settings button.
- `apps/LauncherModel.kt`: the installed apps, through `LauncherApps` rather
  than `queryIntentActivities`, so other profiles are included, icons carry the
  profile badge, and the system reports installs and removals while the list is
  open. It also launches apps and opens their app info.
- `PinShortcutActivity.kt`: accepts the shortcuts other apps pin to the home
  screen, such as a page Firefox adds with "Add to Home screen" (or "Install",
  for a site that can run as an app). Android only offers that in an app while
  the default home app has an activity for `CONFIRM_PIN_SHORTCUT`. It accepts
  without asking, since the user just asked in the other app, and shows a
  toast. Android keeps the shortcuts, not us: `LauncherModel.shortcuts()`
  lists them (`FLAG_MATCH_PINNED`), `launch` starts one and `unpin` pins the
  rest of that app's. Only the default home app may do any of that, so
  anywhere else the list is empty. `LauncherModel`'s callback reports them
  changing (`onShortcutsChanged`) like it does apps.
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
  Which apps get one is worked out automatically: `LauncherModel` reads each
  app's theme for `windowLightStatusBar`, because the Portal draws its Back and
  Home buttons in white and doesn't darken them, so they vanish over such an app
  (F-Droid is one). Apps that ask for it in code instead are missed, since
  their manifest theme says nothing, so `HomeButtonApps.ALWAYS` lists them by
  package name and they get a button regardless of what the theme said;
  `com.whatsapp` is there, being the app the feature was written for. The
  long-press menu can still force the button on or off per app, and that choice
  wins over both the list and the automatic detection.

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
  are skipped, as are the ones the `AlbumFilter` leaves out (not in
  libimmich-random, which shows every album).
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
- The top left corner shows the pinned shortcuts as a column of icons (no
  labels, to keep it small over the pictures), and tapping one opens it, in
  the screensaver too (`onScreensaverTap` finds the icon by its tag). The debug
  text goes beside them. At night they are under the cover like the rest. The
  Slideshow tab can turn the column off (`showShortcuts`, on by default), which
  covers both the home screen and the screensaver; the app list always shows
  them.
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
- Above the clock, when the Slideshow tab turns it on and a place is set, is
  the current temperature and an icon for the sky. It is fetched when the
  slideshow appears and then on every hour, and the place is resolved on each
  round too, which costs nothing once it's cached but means a geocoder that was
  down at the start is tried again. Nothing on screen depends on it, so a fetch
  that fails only goes to the log and leaves the panel as it was, or hidden: the
  pictures are the point. A place the geocoder doesn't know hides the panel,
  and the settings screen says so under the field. The screensaver shows it
  too.
- The bottom right corner shows what another app is playing (title, artist and
  album, artwork when the app provides one) with previous, play/pause and next.
  Tapping the panel anywhere else opens the app that's playing (its session's
  own activity when it has one, else its launcher entry) rather than the app
  list, with the home button over it if the user or `HomeButtonApps.ALWAYS`
  asked for one; the automatic detection isn't applied there.
  The screensaver shows the text but no buttons, since a touch ends it; a
  tap on its panel opens the player like on the home screen.
  `tools/setup-device.sh` grants the notification access this needs; by hand it
  is
  `adb shell cmd notification allow_listener com.nicobrailo.astrodock/com.nicobrailo.astrodock.media.MediaListenerService`
  (`disallow_listener` to revoke). The System tab's button is no use on the
  Portal, which is why the script does it.
- Errors are shown in a text overlay over the picture.
- Whatever is wrong with the MQTT broker is shown in the top right corner (see
  `StateReporter`). Only the home screen shows it: the screensaver is what runs
  all night, with nobody looking, so it isn't the place to complain.
- When the activity starts again, it reloads the settings. If they changed, it
  rebuilds the client and picker and clears the history. If not, it calls
  `picker.refresh()`.
- Everything in the activity runs on the main thread.

**App list** (`AppListActivity`).
- It shows every activity with `ACTION_MAIN` + `CATEGORY_LAUNCHER`, except this
  app, and the pinned shortcuts, sorted by label. The `<queries>` element in
  the manifest makes those activities visible on Android 11+. Shortcuts go in
  folders like apps (by `PinnedShortcut.key`), and their long-press menu
  removes them (unpins) or, inside a folder, takes them out of it. A folder
  only drops a shortcut it can't find while AstroDock can read shortcuts at
  all: when it isn't the default home app the list is empty, which says
  nothing about what was removed.
- The list reloads in `onStart()`, so installed and removed apps show up.
- Tapping an app launches it in a new task and closes the list, so Back from
  the app returns to the slideshow.
- The gear button (top right) opens `SettingsActivity`, which is the only way to
  reach the settings on the device.
- Long-pressing an item starts a drag. Dropping an app on another app makes a
  folder; dropping it on a folder adds it. A long-press that never moves shows a
  menu instead: app info and uninstall for an app (hidden for system apps and
  other profiles), rename and ungroup for a folder. Inside an open folder, the
  menu can also take an app out. Uninstalling needs `REQUEST_DELETE_PACKAGES`
  in the manifest: without it `ACTION_DELETE` still starts the system's
  uninstaller, which closes again in the same instant, so the menu item looks
  like it does nothing and only logcat says why.
- Entries are sorted by name; there's no manual ordering.
- The long-press menu also offers a home button over that app (see
  `HomeButtonService`), which is off for every app except the ones the launcher
  detects and the ones `HomeButtonApps.ALWAYS` names.

**Settings**: server URL, API key (needs `album.read`, `asset.read` and
`asset.view`), max pictures per album (default 20), percent of each album
(default 0 = all), seconds per picture (default 30) and whether to show the
pinned shortcuts over the pictures (default on). Under "Album filter", the
`AlbumFilter`: the album names to show and the ones to leave out (comma
separated, `*` and `?` are wildcards, matching the whole name, case
insensitive), and a year range (0 at either end means no limit; an album the
server gives no dates for is left out as soon as a year is set). All four are
empty by default, which shows every album. Changing any of them restarts the
slideshow, since whatever is on screen may come from an album that is now
filtered out. Under "Weather": whether to show it at all and the
place to show it for: a town or city, with a comma and the region or country
if several share the name, or "latitude, longitude". Empty hides the panel.
Changing any of it leaves the pictures alone. Under "Screen": how long
after the Portal last saw someone the screen switches off (a slider, 0 leaves
the system's value alone) and an opt-in "turn the screen off at night" with its hours,
a slider with two knobs (default 00:00 to 06:00, off). Without the device admin ("Turn the screen
off" in the System tab) the night rule can't do anything, so its switch and
hours are greyed out and the reason is shown in red; this is checked every
time the tab is resumed, so granting it enables them. The red doesn't show on
a Portal set up by `tools/setup-device.sh`, whose high contrast text draws
every string black or white.

**Night screen off** (`SlideshowController.checkNight`). The night rule applies
while the hour is inside the night window and nothing has touched the slideshow
in the last 5 minutes. It has two halves, because the Portal's presence
detection wakes the screen about every 30s while its camera sees someone, and
nothing an app can reach stops that:
- **Dark**: the slideshow is covered in black (`night_cover`) with the window's
  backlight override at 1/255, the panel's lowest (dim, not off), and the timer
  stops moving pictures. MQTT reports it as `slideshow_active` false. This is
  decided the moment a slideshow appears, before it reports anything, so a
  screen the Portal has just woken never shows a picture and never reports a
  moment of `true`. A touch on the cover
  brings the pictures back; in the screensaver a touch wakes to the home
  screen, which then isn't dark because of that touch.
- **Off**: `lockNow()`, from the check that runs every 30s (the first 20s after
  the slideshow appears). It isn't repeated for 10 minutes
  (`NIGHT_RELOCK_MILLIS`, timed from `SlideshowState.lastNightLockAt`, which
  survives the handover to a new screensaver): if the Portal wakes the screen
  in that time, someone is in view and it stays black and dim. With nobody in
  view the screen stays off. Locking again on every wake, as the first version
  did, made the pictures blink on and off all night.
Both need the device admin: without it the rule does nothing, matching the
greyed out setting. Changing these settings doesn't disturb the pictures: only
the server and sampling settings reset the slideshow.

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
- **Timers:** `screen_off_timeout` (system setting) decides when the screensaver
  starts; the secure `sleep_timeout` decides when the screen goes off, counted
  from the last presence report. The Slideshow tab's "turn the screen off after"
  writes both (`ScreenControl.applyScreenOffDelay`), keeping the screensaver
  delay below the screen-off delay. **0 means "leave it to the Portal"**: the
  app then puts the Portal's own values back (1200000 / 300000) instead of
  leaving its last ones behind. Apart from the night rule, which calls
  `lockNow()`, these settings are the only way the app touches the screen: it
  holds no wake lock and sets no keep-screen-on flag.
- **`Notify people presence` in the log does not mean somebody was seen.** The
  camera logs that line every 30s as a periodic update carrying a value. What
  counts is whether the Portal then pokes the power manager, and only
  `dumpsys power` shows that (`mLastUserActivityTimeNoChangeLights`). Measured
  with the device facing a wall: the log line appeared every 30s while the poke
  age climbed 141s -> 300s, i.e. no detection at all. A poke does reset the
  countdown, so the screen stays on while the camera genuinely sees someone.
  Don't read the log line as presence, and don't expect an app to observe the
  pokes: only the *arrival edge* is visible, as a screen-on nothing else caused.
- **The screen going off with somebody in the room** therefore means the camera
  isn't seeing them, not that presence is being ignored. The Portal's own delay
  is 20 minutes, long enough to ride that out; anything much shorter blinks.
- **Screensavers stay enabled** (`tools/setup-device.sh`). Turning them off was
  tried: `screen_off_timeout` then switches the screen off directly, which looked
  worse, though that test ran while the camera was seeing nobody, so it proved
  less than it seemed. Ambient mode is also where the Portal's own slideshow
  lives, so ours belongs there too.
- **From ambient mode it took two rounds of the timeout to switch the screen
  off:** the first round ended the screensaver and woke the device, which reset
  the delay, and the second slept. Measured once, with nobody in view: last
  detection 10:56:22, wake 10:58:22, asleep 11:00:23, for a 2 minute setting.
  `ScreenControl` therefore writes half of what the user asked for. From an
  awake screen one round was enough, so that case switches off sooner than the
  setting says. Worth re-measuring if the delay ever feels wrong.
- **The screensaver timeout must be longer than the screen-off delay.** While
  the screensaver runs, the system ends it once `screen_off_timeout` passes
  without activity; if `sleep_timeout` hasn't elapsed yet it *wakes the device*
  instead of sleeping, and waking resets the delay. With 60s against a 2 minute
  delay the Portal alternated screensaver/awake every 60s all night with nobody
  in the room. `ScreenControl` therefore writes `screen_off_timeout` as the
  screen-off delay plus a minute. Measured after the fix: `Going to sleep due to
  timeout` exactly 2 minutes after the last activity, then
  `Waking up from Dozing ... PresenceManager` when someone came back.
- **`sleep_timeout` needs re-applying.** Writing it needs `WRITE_SECURE_SETTINGS`
  (`tools/setup-device.sh` grants it with `pm grant`), and the Portal puts its
  own 1200000 back: our first write was reverted within the same second, and the
  re-apply 20s later stuck. `SlideshowController` therefore writes it again
  every 30s while the slideshow is on screen.
- **Portal's ambient mode** is a screensaver:
  `screensaver_components=com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService`,
  a windowless dream that starts the home activity. Ours replaces it, and
  ambient mode is where presence keeps the screen on, so it is what runs most of
  the time on the Portal.
- **Dark room clock:** the Portal launcher switches to a full-screen clock when
  the light sensor reads dark (`AmbientLightSensor: luxDark`). Covering the
  camera also covers the light sensor.
- A keep-screen-on flag in our app would block sleep entirely, and with it the
  "screen off when nobody is around" behaviour.
- `tools/capture-presence.sh OUT_DIR` records logcat, power, dream, top
  activity and light-sensor changes, for experiments like these.
- The Portal verifies every install made on the device against a fixed set of
  Facebook signing certificates (`com.facebook.appverifier`, logging
  "App certificate rejected"), so F-Droid and anything else fails with
  "App not installed". It's off after `tools/setup-device.sh`
  (`package_verifier_enable`, a global setting, so adb only). Installs over adb
  are never verified (`verifier_verify_adb_installs=0`), which is why this app
  installs fine.
- **The system's install dialog draws its text in the colour of whatever is
  behind it**, so it looks like a blank white page and the install can only be
  confirmed by tapping where the buttons would be. The culprit is the Portal's
  theme for the framework, the RRO `com.facebook.aloha.rro.niu.android`
  (`/vendor/overlay/NiuDeviceDefaultTheme/NiuDeviceDefaultOverlay.apk`), which
  `com.android.packageinstaller` inherits like every other app. The dialog is
  fine underneath: `uiautomator dump` reads every string and the buttons take
  taps. Measured by counting colours in the button strip: exactly one (pure
  white, so no glyphs) with the RRO on, the stock light dialog with it off.
  Night mode makes no difference, and the uninstall dialog in the same package
  is fine, being an AlertDialog theme. This hits the app's own Apps tab and its
  self-update too, since `ApkInstaller` starts the same activity.
  **Don't disable the RRO**, although `cmd overlay disable` can (its
  `mIsStatic` is false): the keyboard (LatinIME, the only IME) is themed by it
  too, and without it the keyboard's window still covers the screen (from the
  status bar down, above the app) but draws nothing, so it swallows every tap.
  Measured 2026-09-23: any text setting's dialog showed no keyboard, and its OK
  and Cancel did nothing. An earlier `tools/setup-device.sh` disabled it; the
  script now enables it, which repairs that. What fixes the installer instead
  is `high_text_contrast_enabled`, which outlines every string, so nothing can
  go invisible: measured with the RRO on, the dialog's text and its Cancel and
  Install buttons are all readable. It is a secure setting, so the app can set
  it too, and the System tab has a switch for it (`TextContrast`).
- **The system's notification access screen closes itself**, so nothing on the
  device can turn the media panel on. `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`
  (whose value really does repeat the `ACTION_` prefix, unlike its `_DETAIL_`
  sibling, so it looks like a mistake and isn't) resolves to
  `com.android.settings/.Settings$NotificationAccessSettingsActivity` and starts
  it: the log shows it created, its fragment switched in and resumed, and then
  gone about 40ms later, with no exception and no permission denial. Measured
  with astrodock force-stopped and the Portal's own settings in front too, so
  it isn't ours, and `ACTION_NOTIFICATION_SETTINGS` goes the same way; the
  screensaver and overlay screens in the same app stay up fine, and
  `android.settings.SETTINGS` opens the Portal's own settings app
  (`com.facebook.alohaapps.settings`), which has no screen for this. So
  `tools/setup-device.sh` grants it over adb, like the secure settings.
- **The system's device admin dialog refuses the app**: it says "Device
  management policies are not supported" and activates nothing, although the
  device has `android.software.device_admin` and
  `adb shell dpm set-active-admin com.nicobrailo.astrodock/.ScreenAdminReceiver`
  works (measured 2026-09-21). So `tools/setup-device.sh` grants it over adb,
  like notification access. `tools/force-uninstall.sh` exists because an
  active admin blocks `adb uninstall`, and reinstalling that way drops the
  grant, so run the setup script again afterwards.
- **Presence wakes a screen that `lockNow()` switched off.** Measured
  2026-09-21 with someone in view: every lock was followed 8-14s later by
  `Waking up from Dozing ... Full_Wakeup_PresenceManager`, on the camera's 30s
  beat, and the screensaver started again. A window's `screenBrightness` of
  1/255 gives `mActualBacklight=1` (`dumpsys display`), which is as dark as the
  screen goes while it is on. After a lock the system also starts the
  screensaver while the screen is still off, so the one the Portal wakes up to
  is already running.
- `tools/setup-device.sh` takes no arguments: it applies everything an app can't
  set for itself (home screen, screensaver, bug pill, app verifier, high
  contrast text for the install dialog, notification access, the device admin,
  and the app-ops behind the System tab's other permissions: `WRITE_SETTINGS`,
  `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`) and prints the result.
  Its header lists the commands to undo each one. Nothing else is needed.
- `sleep_timeout` does not stick: it was back at the Portal's 1200000 twice
  after the device dreamt and woke again, so something on the Portal resets it.
  Don't rely on it; to control when the screen goes off, use the device admin
  (`ScreenAdminReceiver`) and `DevicePolicyManager.lockNow()`.
- Declaring HOME means that, until the user picks a default home app, pressing
  Home shows a chooser between astrodock and the Portal launcher.
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
  (disabling that app changes nothing). No setting controls it, so
  `tools/setup-device.sh` denies that package the overlay app-op and restarts
  it. That stops any other overlay from the same package too.
- Waking the screen (presence, or the power key) starts the **screensaver**, not
  the home activity, and a screensaver that fails leaves whatever was on screen
  before, which can be the Portal's `HomeActivity` even when we hold the HOME
  role. `adb logcat -d | grep -E 'PowerManagerService|DreamController'` shows
  the whole path.

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
