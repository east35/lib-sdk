# lib-sdk

Local-first Android WebView shells for two self-hosted reader apps. Each app
loads its existing web UI through an embedded localhost proxy, so the same UI
works online and offline. Reading progress is queued locally and merged with
the cloud when reachable.

Built for [BOOX](https://shop.boox.com/) e-ink readers that are sometimes
offline. Backends are unchanged: the shells just sit in front of them.

## Modules

| Module     | App     | Backend                                                  |
|------------|---------|----------------------------------------------------------|
| `core/`    | shared  | embedded HTTP proxy, local index, progress queue, auth   |
| `honlib/`  | HonLib  | <https://github.com/east35/HonLib> (ebooks, `.epub`)     |
| `galib/`   | GaLib   | manga-dl (manga, `.cbz`)                                 |

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

The HonLib build path is set in `honlib/build.gradle.kts`; the manga path in
`galib/build.gradle.kts`. Adjust if your sibling layout differs.

## Architecture

See [`spec.md`](./spec.md) for the full design — localhost proxy rationale,
content-id ↔ local-file mapping, routing tables, offline progress queue, and
the phase plan.

## Settings UI

Both web UIs ship a hidden `#app-settings` gear link pointing at
`shell://settings`. The shell reveals it inside the WebView and routes the
scheme to the in-app `SetupActivity`. The gear hides itself while the reader
overlay is open.
