# Shiguang for Android

[简体中文](README.md) · **English**

Shiguang is a native Android client for video, anime, comics and books. Version **0.11.0** adds an English interface alongside Simplified Chinese. Android 8.0 or later is required.

[Download the APK](https://github.com/xiaolong610/shiguang-android/releases/latest) · [Screenshots](docs/screenshots/0.11.0/README.md) · [Build instructions](docs/BUILD.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

Install `shiguang-0.11.0.apk`. It uses the existing package ID and signing certificate, so it can update an earlier Shiguang installation without uninstalling it. Private GitHub release assets require an account with repository access.

## Choose English

Open **Discover → + → Language → English**. The same menu offers Simplified Chinese and Follow system. Android 13 and later also expose these languages in the system app language settings. Older Android versions use an app preference and recreate the screen. The initial choice follows the system; languages other than Chinese fall back to English.

Interface language does not translate website titles, source names, chapter names or content. Imported books and existing favorites remain in their original language. Built-in sample stories and comic artwork are Chinese originals. Previously stored source status messages can remain in the language in which they were generated until refreshed.

## Features

- Video, anime, comics and books have separate categories. Search uses enabled sources, shows results progressively, and supports local partial and approximate title matching. Remote results depend on what each website returns.
- Favorites, recent activity and update checks share the library. Open a title to favorite it. Other-source selection retains the old progress record and asks you to confirm a starting chapter or episode.
- The native video player supports background playback, picture-in-picture, playback speed, double-tap play/pause, temporary hold-to-speed-up and episode sorting.
- Download the current episode from playback settings. Common MP4 and on-demand HLS streams support persistent tasks, pause/resume, retry and app-private offline playback. Downloads use Wi-Fi by default. HLS is stored as playable segments, not exported as a merged MP4. Website verification, expired URLs and protected media may prevent downloading.
- Comics support vertical strips, left-to-right pages and right-to-left pages. Books support scrolling, swiping and tap-to-turn, bookmarks, chapter search, current-chapter text search, volume-key navigation, custom TTF/OTF fonts and automatic scrolling. Full-book search is available for locally imported books.
- Book import supports TXT, EPUB, MOBI/AZW3, FB2, PDF, DOCX, RTF, HTML, Markdown and single-book ZIP archives, up to 100 MiB. DRM-protected files and scanned-image OCR are not supported.
- Device narration selects an installed offline Chinese or English voice based on book text, independently of interface language. Install the appropriate offline voice pack in system text-to-speech settings. AI narration requires a separately paired service and its language support depends on that service.
- **Discover → + → Downloads & cache** manages video downloads and temporary caches. Clearing temporary caches keeps favorites, progress, imported books, fonts and downloaded videos.

## Import website rules

Download `rules-tested-11.json` or `rules-all-263.json` from the release, then open **Sources → + → File → Choose file**. The first file is the previously tested shortlist; the full catalog is not a guarantee that all websites currently work. Existing version 0.6 rule bundles remain compatible. This release does not add or re-certify English-language content websites.

**Subscription URL** accepts rule JSON or Kazumi `index.json`. Automatic refresh runs approximately every 12 hours when system conditions permit. Imported rules remain available when an update fails. Source update and rollback actions are in the subscription menu.

The app includes original offline samples, without preloaded third-party aggregation websites or private service credentials.
