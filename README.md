<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" alt="Transdroid app icon">
</p>

<h1 align="center">Transdroid</h1>

<p align="center">
  Manage your torrents from your Android device.
</p>

<p align="center">
  <a href="../../actions"><img src="../../actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
  <a href="../../releases"><img src="https://img.shields.io/github/v/release/Gourab0002/transdroid?label=latest%20release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License: GPLv3">
</p>

<p align="center">
  <a href="https://www.transdroid.org/">www.transdroid.org</a> ·
  <a href="mailto:transdroid@2312.nl">transdroid@2312.nl</a>
</p>

## Download

Get the signed APK from the [**Releases**](../../releases) page:

- **Transdroid `full`** (`Transdroid-v*.apk`) — the complete app: search, RSS, widgets and all clients. Distributed via transdroid.org and (soon) F-Droid.
- **Transdroid `lite`** (`Transdroid-lite-v*.apk`, a.k.a. Transdrone) — the Google Play variant with in-app search and RSS disabled.

Requires **Android 10 (API 29) or newer**. Every push is also built by CI — a debug APK is attached to each run on the [Actions tab](../../actions) (artifact `transdroid-full-debug`).

> Upgrading from Transdroid 2? This is a ground-up rewrite (see [transdroid3_plan.md](transdroid3_plan.md)). The final v2 code is preserved at the [`transdroid2-final`](../../tree/transdroid2-final) tag and receives no further development.

## Features

**Core**

- Torrent list with live auto-refresh, status filters, label chips, name filter, sorting and pull-to-refresh
- Torrent details: start/pause, recheck, reannounce, force start, queue position, remove (optionally with data), per-file progress and priorities, trackers, peers, speed limits
- Add torrents via magnet link, URL, `.torrent` file picker, file-manager intents or share intents from other apps
- Server settings with connection test, per-server download directory, labels and global/alternative speed limits
- Adaptive two-pane layout on tablets and foldables; Material 3 in the classic Transdroid grey-green

**Search & RSS** (`full` flavor only)

- In-app torrent search via the Torznab API — one Jackett or Prowlarr endpoint unlocks hundreds of indexers; results sort by seeders and send straight to the active server
- RSS/Atom feed subscriptions with new-item highlighting and optional auto-download with title filters

**Background & widgets**

- Opt-in "torrent finished" notifications (WorkManager, ~15 min, Android 13+ permission aware)
- Home screen widgets: server overview and most-active torrent list, refreshed by foreground use and background checks

**Connectivity**

- Local network discovery: adding a server scans your Wi-Fi/Ethernet subnet and offers what it finds
- Self-signed HTTPS done securely: inspect the server's certificate fingerprint once, then pin exactly that certificate — no "trust everything" toggle
- Custom HTTP headers per server (e.g. Cloudflare Access service tokens), access-portal detection with actionable errors

## Supported clients

| Client | Protocol | Notes |
| --- | --- | --- |
| Transmission | JSON-RPC | Session-id handshake, basic auth |
| qBittorrent | Web API v2 | Works with 4.1+ and 5.x (`pause`/`resume` and `stop`/`start`) |
| rTorrent | XML-RPC | e.g. `/RPC2` behind a web server or ruTorrent |
| Deluge | Web UI JSON-RPC | 1.3 and 2.x, session re-authentication |

More clients can return as community contributions — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Building

```
./gradlew :protocol:test                 # protocol unit tests (pure JVM, no emulator needed)
./gradlew :app:testFullDebugUnitTest     # app unit tests
./gradlew :app:lintFullDebug             # Android lint
./gradlew :app:assembleFullDebug         # installable debug APK
```

Release builds (`assembleFullRelease` / `assembleRelease`) are minified with R8. To sign them, copy `keystore.properties.example` to `keystore.properties` (git-ignored) and fill in your keystore — or set the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` repository secrets and cut a `v*` tag to publish a GitHub Release.

## Project structure

| Module | Contents |
| --- | --- |
| `:protocol` | Pure-JVM library: one normalized `DaemonAdapter` interface, torrent/file models, Transmission / qBittorrent / rTorrent / Deluge adapters, Torznab search, RSS parsing, LAN probing — all unit-tested against recorded fixtures |
| `:app` | The Android app: Compose UI, ViewModels, encrypted settings storage, workers, widgets |

See [CONTRIBUTING.md](CONTRIBUTING.md) for the module layout and how to add a torrent-client adapter.

## Security & privacy

- Server credentials, feed URLs (often containing private passkeys) and indexer API keys are stored **AES-256-GCM encrypted** with a hardware-backed Android Keystore key, and **excluded from cloud backup and device transfer** — passwords never leave the device.
- Passphrase-encrypted settings backup export/import (PBKDF2 + AES-GCM) for moving between devices.
- Self-signed certificates are pinned per server after explicit user approval; XML parsing is XXE-hardened.
- Plain HTTP is allowed because many home daemons speak HTTP on the LAN — prefer HTTPS (or a reverse proxy) whenever your server leaves your network.

## Roadmap

- F-Droid inclusion (metadata is ready; store screenshots and the fdroiddata merge request are next)
- Translations
- More Transdroid 2 client adapters via community contributions

## Credits

Designed and developed by [Eric Kok](mailto:eric@2312.nl) of [2312 development](https://2312.nl/). Contributions by various others (see commit log). Code and design contributions are very welcome — note that all code is licensed under the GNU GPLv3.

## License

    Copyright 2010-2026 Eric Kok et al.

    Transdroid is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Transdroid is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Transdroid.  If not, see <https://www.gnu.org/licenses/>.

Libraries used in the project:
*  [Android Jetpack (AndroidX)](https://developer.android.com/jetpack), including Compose —
   The Android Open Source Project, Apache License 2.0
*  [Kotlin and kotlinx libraries](https://kotlinlang.org/) —
   JetBrains and contributors, Apache License 2.0
*  [OkHttp](https://square.github.io/okhttp/) —
   Square, Inc., Apache License 2.0
