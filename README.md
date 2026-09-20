# AstroDock

A lightweight home screen and screensaver for the Facebook Portal Go.

## Features

* **Immich Slideshow**: Full-screen photos with metadata, gesture history, and people/EXIF overlays, from all of an Immich server or a filtered set of its albums.
* **Home & Screensaver**: Integrated launcher and system "Dream" service for a seamless experience.
* **App Launcher**: Customizable grid with folders and drag-and-drop support.
* **App Catalogue**: Built-in installer to download and update Portal-compatible apps.
* **Smart Power**: Scheduled sleep/wake cycles and automated screen management.
* **Media Controls**: "Now Playing" overlay for controlling background music and video apps.
* **MQTT Remote**: Publishes device state (occupancy, current photo) and accepts remote commands (navigation, power, text announcements).

## Installing

Only the first install needs a computer. After that the app updates itself from
this repository's GitHub releases, so the Portal can go back to being a
photo frame with nothing plugged into it.

### 1. Connect over adb

Turn on developer options and USB debugging on the Portal, plug it into the
computer and accept the debugging prompt on the screen. Check that it answers:

```sh
adb devices     # the Portal should be listed as "device", not "unauthorized"
```

### 2. Install the APK

```sh
tools/build-apks.sh                                   # runs the tests, builds both APKs
adb install -r ~/Downloads/AstroDock-debug.apk
```

Or download `AstroDock-debug.apk` from a
[release](https://github.com/nicolasbrailo/AstroDock/releases) and install that.
Prefer the debug build: `tools/push-config.sh` and `tools/force-uninstall.sh`
go through `run-as`, which a release build doesn't allow. Both APKs are signed
with the same debug keystore, so a later build installs over an earlier one
instead of having to remove it first.

The Portal's app verifier only accepts apps signed by Facebook and fails
everything else with "App certificate rejected", but installs made over adb are
never verified, so this one goes through before the next step turns the
verifier off.

### 3. Prepare the device

```sh
tools/setup-device.sh
```

This is everything an app can't do for itself: it makes AstroDock the home
screen and the screensaver, grants `WRITE_SECURE_SETTINGS` (so the app can keep
the screen-off delay where the Slideshow tab wants it, since the Portal resets
it), hides the Portal's floating bug-report pill, and turns off the app
verifier, so the Apps tab can install anything at all. It is safe to run again,
it prints what the device looks like afterwards, and its header lists the
command to undo each change.

It comes after the install because it names the app: granting a permission to a
package that isn't there fails, and so does handing it the home role.

The slideshow starts by itself, or:

```sh
adb shell am start -n com.nicobrailo.astrodock/.SlideshowActivity
```

### 4. Grant the permissions

Tap the slideshow to open the app list, then the gear button, then the
**System** tab. It has one row per thing the app needs, each with its current
state and a button that opens the system dialog for it:

| Setting | What it's for |
| --- | --- |
| Home screen | Show the slideshow instead of the Portal home screen. |
| Screensaver | Show the slideshow when the Portal is idle. |
| Turn the screen off | Device admin, so the app can switch the screen off itself (the night rule and the MQTT `presence/force_off` command). |
| Change system settings | Lets the app decide when the screensaver starts. |
| Media controls | Notification access, for the "now playing" panel. |
| Home button over other apps | Draws a home button over apps that hide the Portal's own, like WhatsApp. |
| Install unknown apps | Lets the Apps tab install and update apps, including AstroDock itself. |

None of these need adb, and the slideshow runs without any of them: each one
only adds what its row describes. The list ends with the screen-off delay,
which `adb` alone can grant and `tools/setup-device.sh` already did — that row
has no button, it is there to show whether the grant stuck.

Last, fill in Settings → **Slideshow** with the Immich server URL and an API key
(it needs `album.read`, `asset.read` and `asset.view`). From a computer,
`tools/push-config.sh --server-url URL --api-key KEY` saves typing it on the
touch screen.

### Updating

The **Apps** tab lists AstroDock itself and offers the APK from this
repository's latest GitHub release whenever it isn't the build already running,
which it settles by comparing the sha256 GitHub publishes with the hash of the
installed APK. So no adb, and no computer, after the first install. A locally
built APK is never the published one, so a development device always offers the
update.

### Removing it

`adb uninstall` fails with `DELETE_FAILED_DEVICE_POLICY_MANAGER` once the device
admin has been granted, because an active admin can't be uninstalled. Use
`tools/force-uninstall.sh` instead.

## Album filter

By default the slideshow draws from every album the API key can see. The album
filter narrows that down, by album name and by when the pictures in an album
were taken — a device in the kitchen can show `kitchen-*`, another one only the
last few years.

Immich itself can't do this: `GET /albums` only matches an exact name or an
owner. The album list is one request that already carries each album's name and
the dates of its oldest and newest picture, so the filter is applied to that
list and costs no extra requests.

### Settings

Settings → Slideshow → **Album filter**:

| Setting | Meaning |
| --- | --- |
| Only these albums | Names to show. Empty shows every album. |
| Except these albums | Names to leave out. Checked after the list above, so it wins. |
| From year | Only albums holding pictures from this year onwards. 0 means no limit. |
| To year | Only albums holding pictures up to this year. 0 means no limit. |

Names are comma separated patterns, matched against the **whole** album name
and ignoring case. `*` stands for any run of characters and `?` for exactly one;
everything else is literal, so `Trip (2019)` can be written as it is. For
example, `holidays *, Pets` keeps `Holidays 2019` and `Pets`, but not
`Pets and dogs`.

The years are an overlap test: an album is kept if any of its pictures fall in
the range, so one running 2010–2026 is kept by "2019 to 2021". Two things follow
from that, both worth knowing:

* A *reversed* range is not empty. "From 2020, to 2019" asks for albums that
  end in 2020 or later **and** start in 2019 or earlier — the albums straddling
  that boundary, which is rarely what anyone means.
* Album names say nothing about dates. An album called `2024 - office` whose
  pictures actually run from 2019 is kept by a 2019 filter.

An album the server gives no dates for is left out as soon as either year is
set. If the filter matches nothing, the slideshow says so over the pictures
instead of going blank.

### From a computer

`tools/push-config.sh` sets the filter over adb, so it doesn't have to be typed
on the touch screen. It only replaces the settings named on the command line:

```sh
tools/push-config.sh --album-include 'kitchen-*' --album-from-year 2019
tools/push-config.sh --album-include ''          # drop that part of the filter
tools/push-config.sh --show                      # what the device has now
```

### Over MQTT

With the MQTT tab set up, publishing to `<prefix>cmd/ambience/set_album_filter`
changes the filter while the slideshow is running. `<prefix>` is the device's
topic prefix, e.g. `portalgo/`.

```sh
mosquitto_pub -h BROKER -t 'portalgo/cmd/ambience/set_album_filter' \
  -m '{"name":"kitchen-*","exclude":"Screenshots","from_year":2019,"to_year":2021}'
```

Every field is optional and the payload replaces the whole filter, so anything
left out is cleared and `{}` shows every album again. The new filter is saved
like any other setting, so it survives a restart and the settings screen agrees
with it. Don't publish the message retained: retained commands are ignored, or
they would be replayed on every reconnect.
