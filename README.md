# Auto Lyrics

An Android Auto app that displays synced lyrics for the currently playing song, powered by [LRCLIB](https://lrclib.net/).

## v1.10.0 changes (fork)

- **Android Auto**: the current lyric line is now the large title on the media card / now-playing screen; song name · artist moved to the small subtitle (toggle in settings).
- **Multiple lyrics sources**: NetEase, QQ Music, KuGou, LRCLIB, SyncLRC, Musixmatch, YouTube Music. Enable with checkboxes and drag to reorder (phone → settings → "歌詞來源與進階設定"). First synced result wins.
- **Simplified → Traditional Chinese** conversion for lyrics (Android 10+ ICU).
- **Title-change guard**: players that write lyrics into the title (e.g. YT Music + Morphe "Third-party lyrics") no longer trigger endless re-fetching.
- **v1.10.1 — compact lyrics page**: on the AA lyrics list the song info is a small section title instead of a full row with album art, and 4 lyric lines are shown (1 previous, current, 2 upcoming). Toggle in settings.
- **v1.10.2 — messy video titles**: when nothing is found, retries with brackets / "MV" / "動態歌詞" removed and with titles like "薛之謙-天外之物" or "不遺憾 (…) - 李榮浩『…』" split into artist and song name (both orders). Results still have to pass the normal title/artist/duration check.
- **v1.10.3 — tabs & jump without leaving lyrics**: Android Auto opens on a "Lyrics" tab, "⟳ Sync" is the second tab. Each lyric row has a ▶ button (custom browse action) that jumps to that line without switching to the now-playing screen; on head units without custom browse actions, rows become folders that jump and open the same lyrics page. Settings → "車機點歌詞時": auto / folder mode / original.
- **v1.10.4 — song name in brackets**: titles like "周杰倫 Jay Chou【告白氣球 Love Confession】Official MV" are retried with the 【】「」『』《》 text as the song name, and mixed Chinese/English names also get a Chinese-only try ("告白氣球 / 周杰倫"). At most 4 extra tries per song.
- **v1.11.0 — phone lyrics search**: "🔍 搜尋歌詞 ／ 聽聲辨曲記錄" at the top of the phone screen. Type a song name (artist optional) to see its full lyrics, or tap one of the last 10 songs Pixel "Now Playing" (聽聲辨曲) recognised — read from its notification. Phone only; nothing is played.
- Sources other than LRCLIB use unofficial endpoints and may break at any time.

## Features

- **Synced lyrics on Android Auto** — shows the current lyric line with surrounding context, updated in real-time
- **Works with any music player** — Spotify, YouTube Music, Apple Music, Poweramp, etc.
- **Phone companion view** — see lyrics on your phone screen too
- **Automatic song detection** — picks up whatever is playing via media session APIs
- **Multi-strategy lyrics lookup** — tries exact match first, falls back to keyword search

## Architecture

```
MediaListenerService (NotificationListenerService)
    │
    ▼
MediaTracker (singleton, StateFlow)
    │   ├── detects song changes → LrcLibClient → LrcParser
    │   └── tracks playback position → updates current lyric index
    │
    ├─────────────────┐
    ▼                 ▼
LyricsScreen      MainActivity
(Android Auto)    (Phone UI)
```

## Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- JDK 17
- A physical Android device (Android 8.0+)

### Build

1. Open the project in Android Studio
2. Sync Gradle
3. Build and install on your device:
   ```
   ./gradlew installDebug
   ```

### First-Time Setup

1. **Open Auto Lyrics** on your phone
2. **Grant notification access** — tap the button and enable "Auto Lyrics" in the system settings
3. **Play a song** in any music app
4. Lyrics will appear both on the phone and on Android Auto

### Testing with Android Auto Desktop Head Unit (DHU)

1. Install **Android Auto Desktop Head Unit emulator** from SDK Manager → SDK Tools
2. Enable **Developer mode** in the Android Auto app on your phone (tap version 10 times)
3. In Android Auto developer settings, enable **Unknown sources**
4. Start the DHU:
   ```
   cd $ANDROID_SDK/extras/google/auto/
   ./desktop-head-unit
   ```
5. Connect your phone via USB with the Android Auto companion app running
6. Auto Lyrics will appear in the app launcher on the DHU

### Using on a real car (sideloaded APK)

Since this app is installed outside the Play Store, Android Auto requires **developer mode** to show it:

1. Open the **Android Auto** app on your phone
2. Go to **Settings** → scroll to the bottom → tap **Version** 10 times rapidly
3. You'll see a toast saying "Developer mode enabled"
4. Tap the **⋮** (three-dot) menu at the top right → **Developer settings**
5. Enable **"Unknown sources"** (allows sideloaded apps on Android Auto)
6. Restart Android Auto or disconnect/reconnect to your car
7. **Auto Lyrics** should now appear in the Android Auto app launcher
8. Play music — lyrics appear automatically

## How It Works

1. **Media detection**: A `NotificationListenerService` grants access to `MediaSessionManager`, which provides the currently active media controllers
2. **Song identification**: When the song changes, metadata (title, artist, album, duration) is extracted from the `MediaController`
3. **Lyrics fetch**: The app queries [LRCLIB](https://lrclib.net/api) — first an exact match (`/api/get`), then a keyword search (`/api/search`) as fallback
4. **LRC parsing**: Synced lyrics in `[mm:ss.xx] text` format are parsed into timestamped lines
5. **Position tracking**: Playback position is calculated from the media session's last reported position + elapsed time, checked every 150ms
6. **Display**: On Android Auto, a `PaneTemplate` shows 4 lyric lines centered on the current one, marked with ▶. The screen refreshes only when the active line changes.

## Limitations

- **Android Auto app categories**: The app registers as an IOT-category Car App. For Google Play distribution, it would need to pass Android Auto app review.
- **Lyrics availability**: Not all songs have synced lyrics on LRCLIB. The app will show "No synced lyrics available" for missing tracks.
- **Player compatibility**: Most major music players expose media sessions correctly. Some niche players may not provide full metadata.
- **Streaming quality tags**: Some music apps (YouTube Music, Tidal, etc.) inject quality info like "Lossless" or "Hi-Res" into metadata fields. The app strips these automatically, but unusual formats may slip through.
- **Sideloaded apps on Android Auto**: Developer mode must be enabled in Android Auto settings to see sideloaded apps. See setup instructions above.

## License

MIT
