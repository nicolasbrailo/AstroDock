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
- `SettingsActivity.kt` + `res/xml/preferences.xml`: settings screen. The
  preference keys in the XML must match `Settings.KEY_*`.
- `SlideshowActivity.kt` + `res/layout/activity_slideshow.xml`: the slideshow,
  launcher entry point.
- `AppListActivity.kt` + `res/layout/activity_app_list.xml`, `item_app.xml`:
  grid of launchable apps, and the settings button.

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
- `PictureHistory` keeps the last `HISTORY_SIZE` (20) pictures. Going back stops
  at the oldest kept one. Going forward first walks the kept pictures again.
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

**Settings**: server URL, API key (needs `album.read`, `asset.read` and
`asset.view`), max pictures per album (default 20), percent of each album
(default 0 = all) and seconds per picture (default 30).

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
