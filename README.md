<div align="center">
  <img style="display: block" src="https://github.com/user-attachments/assets/e8fb14f5-ec8e-440e-a2ac-8065322b0e28" alt="">
  <h1>Paperize (Fork)</h1>
  <p><strong>A dynamic wallpaper changer that keeps your device's aesthetic fresh and exciting</strong></p>

  [![GitHub Downloads](https://img.shields.io/github/downloads/Anthonyy232/Paperize/total?style=flat&logo=github&label=Downloads)](https://github.com/Anthonyy232/Paperize/releases)
  [![GitHub Release](https://img.shields.io/github/v/release/Anthonyy232/Paperize?style=flat&logo=github)](https://github.com/Anthonyy232/Paperize/releases/latest)
  [![License](https://img.shields.io/github/license/Anthonyy232/Paperize?style=flat)](LICENSE)
  [![F-Droid](https://img.shields.io/f-droid/v/com.anthonyla.paperize?style=flat&logo=fdroid)](https://f-droid.org/en/packages/com.anthonyla.paperize/)
  
  [![Crowdin](https://badges.crowdin.net/paperize/localized.svg)](https://crowdin.com/project/paperize)
</div>

---

## Changes in This Fork

- **Static Mode Screen-Lock Trigger** — Added support for changing the wallpaper when the screen turns off (`ACTION_SCREEN_OFF`) in Static Wallpaper mode, matching the interactive trigger previously limited to Live Wallpaper mode.
- **Instant Screen-Off Preloading** — Pre-decodes and renders visual effects for the next static wallpaper in the background (`WallpaperController.preloadNext`) so screen-off transitions apply immediately without waiting on image decoding or GPU pipelines.
- **Native Cloud Media Provider (Android Photo Picker)** — Switched image selection from Android's Storage Access Framework (`OpenMultipleDocuments`) to the native Android Photo Picker (`PickMultipleVisualMedia`), allowing direct imports from cloud libraries like Google Photos.
- **Google Photos Shared Album Sync** — Added the option to link a shared Google Photos album URL (`photos.app.goo.gl` / `photos.google.com/share/...`) as a dynamic folder.
- **6-Hour Periodic Folder & Cloud Sync** — Increased background folder refresh frequency to every 6 hours and updated `RefreshFolderUseCase` to automatically add newly uploaded photos and prune removed ones.
- **Streamlined Scheduling Controls** — Renamed the rotation toggle to **"Use time interval"**, enabled it by default when albums are configured, and prioritized its placement above individual screen scheduling.

---

## Features

- **Dynamic Wallpaper Changer** — Set your wallpaper to change at specific time intervals or automatically whenever you lock your device
- **Static & Live Wallpapers** — Choose between traditional static wallpapers or smooth live wallpaper transitions
- **Cloud & Local Media Support** — Import directly from the Android Photo Picker (Cloud Media Provider) or link shared Google Photos album URLs for automatic background syncing
- **Multiple Image Formats** — Supports JPG, PNG, WEBP, AVIF, HEIC/HEIF, BMP, GIF, TIFF, and SVG
- **Folder Support** — Organize wallpapers into local or cloud-synced folders for auto-updating
- **Dual Screen Support** — Choose the same or separate albums for home and lock screen
- **Wallpaper Effects** — Apply various effects including brightness, blur, scaling, vignette, and more

---

## Download

[<img src="https://github.com/Anthonyy232/Paperize/assets/60626873/1c034414-21cd-4a0a-838d-89fe7bd56910" alt="Download from GitHub" height="60">](https://github.com/Anthonyy232/Paperize/releases)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="60">](https://f-droid.org/en/packages/com.anthonyla.paperize/)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="60">](https://apt.izzysoft.de/fdroid/index/apk/com.anthonyla.paperize)

---

## Localization

Help translate Paperize into your language! Currently, most translations are provided using machine translation — contributions from native speakers are greatly appreciated.

**[Contribute on Crowdin →](https://crowdin.com/project/paperize/invite?h=d8d7a7513d2beb0c96ba9b2a5f85473e2084922)**

---

## Tech Stack

| Category | Technology |
|----------|------------|
| **Language** | [Kotlin](https://kotlinlang.org/) |
| **UI Framework** | [Jetpack Compose](https://developer.android.com/develop/ui/compose) |
| **Design System** | [Material 3](https://m3.material.io/) |
| **Dependency Injection** | [Dagger Hilt](https://dagger.dev/hilt/) |
| **Database** | [Room](https://developer.android.com/training/data-storage/room) |
| **Image Loading** | [Coil 3](https://coil-kt.github.io/coil/) (with OkHttp network fetcher) |

<details>
<summary><b>View all dependencies</b></summary>

- [Zoomable](https://github.com/usuiat/Zoomable) — Zoomable and pannable views

</details>

---

## Architecture

- `AlbumRepository` owns library mutations. Imports, folder synchronization (`syncFolderWallpapers`), reordering, removal, covers, and queue invalidation use Room transactions; provider scans and remote album scrapes run outside them.
- `DocumentSource` and `WallpaperRenderer` (domain interfaces, Android implementations in `data/source`) keep use cases free of document-provider, `CloudMediaProvider`, remote HTTP stream, and bitmap-decoding details.
- `GooglePhotosAlbumScraper` resolves shared Google Photos album links and extracts high-resolution media URLs for periodic synchronization via `AlbumRefreshWorker`.
- `WallpaperRepository` owns rotation queues and current-wallpaper records. Queue creation checks and writes in one transaction, including synchronized screens. Static changes and the live wallpaper engine both dequeue through `ChangeWallpaperUseCase.dequeueNext`.
- `WallpaperController` shares static wallpaper application between services and workers and maintains a pre-rendered bitmap cache (`preloadNext`) for instant screen-off wallpaper swaps. Callers hold `WallpaperChangeLock` through application and schedule updates. The controller recycles applied bitmaps.
- `ScreenOffListenerService` runs as a lightweight foreground service when Static Mode's screen-off trigger is enabled, listening for `Intent.ACTION_SCREEN_OFF` and applying preloaded wallpapers under `WallpaperChangeLock`.
- `SettingsCoordinator` persists wallpaper settings edits and applies their side effects (schedules, queues, immediate changes) one at a time; ViewModels only hold UI state. `WallpaperChangeService` helpers are the single way to start a manual change.
- Repositories and use cases throw on failure. ViewModels, services, and workers catch at the edge, using `runCatchingCancellable` where they need a `Result`, so coroutine cancellation always propagates. Progress belongs to UI state.
- Room migrations preserve versions 1–3 when upgrading to version 4. New schema changes must include a migration; destructive fallback is disabled.

## Building from Source

### Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 17 |
| Android Gradle Plugin | 9.3.2 |
| Gradle | 9.7.1 |
| Compile SDK | 37 (Android 17) |
| Minimum SDK | 31 (Android 12) |
| Target SDK | 36 |

### Build Steps

1. **Clone the repository**
   ```bash
   git clone [https://github.com/Anthonyy232/Paperize.git](https://github.com/Anthonyy232/Paperize.git)
   cd Paperize
