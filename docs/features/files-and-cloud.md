---
layout: default
title: Files & cloud storage
---

# Files & cloud storage

A unified file browser across SFTP/SCP, SMB, 60+ cloud providers, and Reticulum
mesh, plus on-device media transcoding and streaming.

## Files

Unified file browser with SFTP, SMB, and cloud storage tabs. Browse remote directories, upload files or entire folders, download, delete, rename, create directories, copy path, toggle hidden files, sort by name/size/date. **Multi-select** — long-press or tap **Select** to enter selection mode with a contextual action bar (copy, cut, permissions, delete). **Permissions editor** — octal field plus a 3×3 rwx checkbox grid, supported on SFTP/SCP/local (not SMB/rclone). **Built-in text editor** with syntax highlighting, find/replace, and terminal-matched theme. **Markdown preview** — tapping a `.md` file renders it (GitHub-flavoured: tables, task lists, alerts, strikethrough, autolinks) with an outline for jumping between headings, copyable code blocks, and images that sit beside the document on the same backend. Relative links to other Markdown files open in place, with Back returning to where you were; Edit opens the file in the editor. Network images are not fetched — they show as a chip that opens in the browser. **Image tools** — view, crop, rotate, perspective-correct. **Cross-filesystem copy/move** — copy files between any backends (e.g. Google Drive → SFTP server) with clipboard model: long-press → Copy/Cut, switch tab, Paste. Conflict resolution (skip/replace) for existing files. Path preserved when switching between tabs.

## SMB

Browse Windows/Samba file shares with optional SSH tunneling for secure access over the internet.

## Cloud Storage

Browse, upload, download, and manage files on 60+ cloud providers via [rclone](https://rclone.org) — Google Drive, Dropbox, OneDrive, Amazon S3, Backblaze B2, and more. OAuth authentication with automatic browser flow. Server-side copy between cloud remotes (no temp file needed). **Share link** — generate public URLs for files on supported backends. **Folder size** — fast recursive size calculation. **Folder sync** — copy, mirror, or move between remotes with include/exclude filters, size limits, bandwidth throttling, and dry-run preview. **Media streaming** — stream audio/video to VLC or any player via local HTTP server with M3U playlists and seeking. **DLNA server** — stream cloud media to smart TVs and Chromecast on the local network.

## Media Convert

Convert media files between formats directly on-device using a custom [FFmpeg 8.0](https://ffmpeg.org) build with the full codec/format/filter set. Long-press any media file and tap Convert. Separate dropdown selectors for container format (MP4, MKV, WebM, MOV, AVI, MPEG-TS, MP3, WAV, OGG, Opus, FLAC, M4A), video encoder (H.264, H.265, VP9, VP8, MPEG-4, stream copy), and audio encoder (AAC, MP3, Opus, Vorbis, FLAC, PCM, FLAC, stream copy). **Copy-remux by default** — container auto-matches the source extension so tapping Convert on most files gives an instant lossless remux. **Frame preview** — see filter effects on a single frame before committing, with seek slider and tap-to-fullscreen. **Audio preview** — play a 5-second clip with current filters applied. Video filters: brightness, contrast, saturation, gamma, sharpen, denoise, stabilize (deshake), auto color correction, speed, rotation. Audio filters: volume, loudness normalization (EBU R128). One-tap presets (Stabilize, Fix Colors, Enhance, Normalize Audio). Live CLI preview shows the exact ffmpeg command being built. Audio-only files auto-detected — video UI hidden, only audio formats shown. **Save to** picker: Downloads folder or back to the source folder (uploads to cloud/SFTP/SMB with live progress). **Works on cloud files without downloading** — for rclone profiles, ffmpeg streams the source over HTTP via the rclone VFS so transcode starts in seconds regardless of file size (falls back to full download for offline/reliability via a toggle). **HLS streaming** — stream any local or rclone media file to other devices on the network via an HTML5 player; URL auto-copied to clipboard and opened at the device's LAN IP for easy sharing.

---

[← All features](../FEATURES.md)
