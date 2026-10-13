# Collage Player

An Android video player built on **libVLC** (the engine inside VLC for Android), plus **Collage mode**: up to 9 videos playing at once in a grid.

## Install (from your phone)

1. Open this repo's **Releases** page and download `CollagePlayer-arm64-v8a.apk` (fits almost every phone; `universal` works everywhere but is bigger).
2. Tap the downloaded file. If Android asks, allow "Install unknown apps" for your browser or file manager.
3. Each push to GitHub builds a new release on its own (`.github/workflows/build-apk.yml`). New builds install over old ones because they share the signing key in `keystore/`.

## Player (VLC engine)
- Plays everything VLC plays: mkv, mp4, avi, flv, wmv, ts, webm, HEVC, VP9, AV1, AC3, DTS…, plus http/rtsp/udp streams
- Swipe gestures: left side for brightness, right side for volume, sideways to seek. Double-tap the sides to skip, pinch to zoom
- Audio track and subtitle track picker, load external subtitle files, audio and subtitle delay
- Speed 0.25×–4×, 10-band equalizer presets, A-B repeat, repeat one/all, sleep timer, jump to time, chapters
- Aspect modes (best fit, fill, 16:9, 4:3, 2.35:1, original…), rotation lock, screen lock, picture-in-picture
- Resumes where you left off. Opens videos from other apps

## Collage mode
- Layouts: 1×2, 2×1, 1×3, 3×1, 2×2, 2×3, 3×2, 3×3
- Videos come **only** from `Movies/Collage Mode`. Fill it from the app (Collage tab, then **Add from gallery**). The app copies the video and leaves the original alone
- Shuffle picks videos at random. Each video plays once before any repeats, and the same video doesn't show twice at the same time
- Everything starts **muted**
- Tap a panel to open its controls: play/pause, change video (pick one or random), fit (Fit/Fill/Stretch/Original), reset pan, loop or auto-shuffle, mute and volume, open in the full player
- In a panel: drag to pan, pinch to zoom, double-tap to pause

### How it avoids lag
- Hardware (MediaCodec) decoding for every panel. If the hardware decoder is busy, it falls back to another decoder
- Muted panels have their audio track switched off completely, so no audio is decoded for them
- Small buffers per panel, panels start one after another, and no display refresh-rate switching
- Pan, zoom and fit are a GPU matrix, so they cost nothing to decode
- **Optimize on import**: big 1080p/4K videos can be re-encoded to 720p (or 480p) as they're added. This is what makes 3×3 smooth on mid-range phones
- Checks how many hardware decoders the phone has, and shows a lag badge on any panel that drops frames
