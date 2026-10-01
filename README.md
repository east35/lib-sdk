# lib-sdk

Local-first Android WebView shells for two self-hosted reader apps. Each app
loads its existing web UI through an embedded localhost proxy, so the same UI
works online and offline. Reading progress is queued locally and merged with
the cloud when reachable. HonLib's highlights and journals are kept in full on
the device the same way, so they can be made and reviewed with no connection
and are exchanged with the cloud when it is reachable.

Built for [BOOX](https://shop.boox.com/) e-ink readers that are sometimes
offline. Backends are unchanged: the shells just sit in front of them.

## Modules

| Module     | App     | Backend                                                  |
|------------|---------|----------------------------------------------------------|
| `core/`    | shared  | embedded HTTP proxy, local index, progress queue, auth   |
| `honlib/`  | HonLib  | <https://github.com/east35/HonLib> (ebooks, `.epub`)     |
| `galib/`   | GaLib   | GaLib (manga, `.cbz`)                                    |

The two app modules are thin shells — config + bundled web assets — sharing
`core/`.

## Build

Requires the Android SDK (set its location in `local.properties` as
`sdk.dir=...`) and the two web-app repos checked out as siblings of this
repo. At build time each app module copies `static/` and `fonts/` from its
sibling into the APK's `assets/web/`.

Build and install to a connected device:

```sh
./gradlew :honlib:installDebug
./gradlew :galib:installDebug
```

The HonLib build path is set in `honlib/build.gradle.kts`; the GaLib path in
`galib/build.gradle.kts`. Adjust if your sibling layout differs. HonLib is also
found when this repo is checked out as its `android/` submodule.

### Release builds

A release is signed with a key kept outside the repo and passed in as Gradle
properties. Environment variables keep the password out of the process list:

```sh
ORG_GRADLE_PROJECT_honlibStoreFile=/path/to/honlib-release.jks \
ORG_GRADLE_PROJECT_honlibStorePassword="$(cat /path/to/honlib-release.pass)" \
ORG_GRADLE_PROJECT_honlibKeyPassword="$(cat /path/to/honlib-release.pass)" \
ORG_GRADLE_PROJECT_honlibCloudUrl=https://your.honlib.server \
./gradlew :honlib:assembleRelease
```

The key alias defaults to `honlib` (`honlibKeyAlias` overrides it). Without
`honlibStoreFile` the release APK is left unsigned. Android only installs an
update signed with the same key as the app it replaces, so keep the key and
its password backed up. Bump `versionCode` and `versionName` in
`honlib/build.gradle.kts` for each release.

### Test builds beside an installed app

A locally signed build cannot update an app that was signed elsewhere, and
uninstalling that app to make room discards its settings and unsynced
progress. To try a build on a device that already has HonLib:

```sh
./gradlew :honlib:installDebug -PidSuffix=.dev -PcloudUrl=http://127.0.0.1:8765
adb reverse tcp:8765 tcp:8765
```

`-PidSuffix` installs it as a separate app, "HonLib Test", with its own data
and its own proxy port. `-PcloudUrl` pre-fills the setup screen; with
`adb reverse` the device reaches a HonLib server running on the build machine
over USB (cleartext is permitted to 127.0.0.1 only). Pass the same `-PidSuffix`
to `:honlib:connectedDebugAndroidTest`, which otherwise uninstalls
`com.readershell.ebook` when it finishes.

## Settings UI

Both web UIs ship a hidden `#app-settings` gear link pointing at
`shell://settings`. The shell reveals it inside the WebView and routes the
scheme to the in-app `SetupActivity`. The gear hides itself while the reader
overlay is open.
