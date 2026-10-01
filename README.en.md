# Chumo Player · SimpleMusic

A feature-rich Android music player (Java + classic Views, minSdk 21 / targetSdk 34).

## Features

**Playback**
- Local library + online search & streaming (NetEase Cloud Music, Bilibili)
- Play modes: list loop / shuffle / single repeat
- Sleep timer (15/30/60 minutes, or end of current track)
- Progress seeking (with buffered seek queuing; the progress bar is pinned and does not snap back while dragging)
- Word-by-word lyrics (enhanced LRC `<00:12.50>`) + multi-source priority (NetEase Cloud / Kugou, with automatic fallback)
- Desktop floating lyrics, home-screen music widget, media notification and lock-screen controls (MediaStyle)

**Library Management**
- Scan local music, manually add scan directories, block folders
- Delete songs (also removes same-named lyrics/cover art, and drops them from playlists, favorites, and history)
- Tag editing: title / artist / album / lyrics (jaudiotagger; MP3=USLT, FLAC=LYRICS)
- Automatic online album-art fetching (saved as a same-named `.jpg`; only one online lookup per track)

**Playlists & Favorites**
- Playlists: create, rename, delete, multi-select add, move up/down reorder, JSON import/export
- My Favorites (heart), with backup and restore just like playlists and play history
- Current playback queue view (auto-scrolls to the track now playing)

**Remote**
- WebDAV streaming (PROPFIND directory listing + Basic auth + MediaPlayer playback with auth headers)
- SMB streaming (smbj; local HTTP proxy with Range support, stream-while-downloading, seekable)

**Personalization**
- Dark / light / **AMOLED true black**, 6 accent colors (Indigo / Sakura / Mint / Violet / Amber / Emerald)
- Configurable home category tabs, greeting pop-up timing, online lyric source priority, online cover-art toggle

## Build

```powershell
gradle assembleDebug assembleRelease
```

- release enables **R8 code shrinking + resource shrinking**, and uses `-dontobfuscate` in `app/proguard-rules.pro`
  **(strips unused code only, keeps class names for easier debugging)**; APK shrinks from 8.35 MB to about 4.09 MB
- `app/libs/*.jar` are the SMB dependencies (smbj + BouncyCastle, etc.) and must be committed along with the repo

> The signing file `release.jks` and the password file `keystore.properties` are excluded by `.gitignore` and
> **will not enter the repository**; if you actually want to publish, place them in the project root yourself.

## Notes

- Feature list inspired by the open-source project [howshea/ArtisanMusic](https://github.com/howshea/ArtisanMusic) (GPL-3.0),
  but **everything here is an independent implementation; none of its code was copied**.
- Dependencies: androidx, Material, Gson, jaudiotagger, smbj.
