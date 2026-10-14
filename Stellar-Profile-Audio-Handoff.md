# Stellar – Profile Audio Handoff (profile music / profile intro)

Status: **on hold.** Recho asked to wait on both ideas below. Nothing for
them is built yet. This note keeps everything decided so far, so a future
chat can pick it up without starting over.

Read `Stellar-Web-Handoff.md` too (latest sections first) for the
codebase, the ground rules and how to build and check things.

---

## 1. What was asked

Recho wants profiles to be able to play audio when someone opens them.
Two versions came up; they are **alternatives** (pick one, or one first),
not two separate features:

- **A. Profile music** — a song plays on the profile.
- **B. Profile intro** — like Discord's voice-channel intro sounds:
  whatever short audio the user uploaded plays once when their profile is
  opened.

Recho's priority: **practical ("in practice") risk, not unlikely
technical/legal hypotheticals.** The decision should weigh what actually
gets apps and users in trouble.

## 2. The advice given (and why)

### Uploading full MP3 songs to the user's PDS — advised against
- **Where liability falls:** with the uploader and their PDS host (usually
  Bluesky). Stellar is only a client playing what someone published, the
  same position it's already in with every image and video.
- **The practical problem:** most people would upload songs they don't
  own. A feature that works as a way to share full songs is what draws
  takedown complaints, and gets an app rejected if it ever goes to
  Apple's App Store. Stellar would be the visible "music player" even
  though the file sits on the user's PDS.

### Option A done safely: official 30-second previews
- Search for a song, then play its **official 30-second preview** from
  Apple's iTunes Search API or Deezer's API. Both are free and need no
  key; these previews are licensed for exactly this kind of use.
- Show the track (cover, title, artist) with a link to listen in full.
- Only a track reference (provider + track id) is stored, in the profile
  style record, not the audio.
- Practically risk-free.

### Option B (intro clip) — workable, low practical risk
- Same storage as video banners: the clip is a blob on the user's own PDS,
  referenced from the profile style record. The uploader and their PDS
  host carry the responsibility, as with Discord intro sounds.
- What keeps it low-risk is the **length**. A few seconds played once is
  a "Discord intro", useless for piracy; full songs would be a music host.
  Recommended limits:
  - **Length:** 5–10 seconds maximum (10 s suggested), trimmed or rejected
    at upload.
  - **Playback:** once per profile open, never looped, only on the
    profile page (never in feeds).
  - **Listener controls:** a setting to turn intros off, a mute/speaker
    button on the profile, and respect for the device's silent mode.
- Not legal advice; for this design the realistic worst case is a
  takedown request to the uploader's PDS host, not to Recho.

## 3. Suggested design (if either is built)

### Storage — same record as everything else
`com.rechoraccoon.stellar.profile` / rkey `self` on the user's PDS
(`util/ProfileStyles.kt`, `ProfileStyle`; parse/save in
`repository/BlueskyRepository.kt` `parseProfileStyle` / `saveProfileStyle`).
One record means no extra PDS reads or rate limits. Field names suggested:

- **Option B, intro clip:**
  `"introAudio": { "$type": "blob", "ref": { "$link": cid }, "mimeType", "size" }`
  plus `"introSeconds"` (its length).
  - Add `introAudio: BannerVideo?` (the same blob-ref class the video
    banner/icon use) to `ProfileStyle`, and include it in `isDefault`.
- **Option A, preview song:**
  `"song": { "provider": "itunes" | "deezer", "id": "...", "title", "artist", "art" }`.
  - Fetch the preview URL at play time from the provider's lookup API
    (preview URLs can expire).

### Uploading (option B)
- **Picker:** in Edit Profile, the "Animated" tab already has video icon
  and banner slots, so an "Intro Sound" slot fits there
  (`ui/EditProfileDialog.kt`).
- **Trimming:**
  - Trim to ≤10 s before upload, the way video banners are cropped before
    upload: a small popup to pick the start point, then cut the file.
  - Platform tools: Android Media3 Transformer (already a dependency; use
    `ClippingConfiguration`); desktop FFmpeg via JavaCV (`DesktopFfmpeg`);
    iOS `AVAssetExportSession` with `timeRange`; web via
    `AudioContext` + `MediaRecorder`, or upload as-is with a length check.
- **Upload:** use the same uploader as video banners
  (`ProfileStyles.bannerUploader` → `BlueskyRepository.uploadBannerVideo`
  is video-specific; add an audio twin or generalize it).
  - Cap the size too (e.g. 1–2 MB).

### Playback
- Play once when the profile opens (`ui/ProfileOverlay.kt`, near
  `ProfileHeaderSection`).
- Fetch from the PDS getBlob URL (`BlueskyBlobResolver.resolveBlobUrl(did, cid)`).
- **Player options:**
  - The feed video pool plays audio-only files on Android (ExoPlayer) and
    iOS (AVPlayer), but a dedicated small audio player is cleaner.
  - Web: an `<audio>` element.
- **Browser autoplay:** browsers block sound before the first click; play
  muted is pointless for audio, so on the web show the speaker button
  instead of auto-playing until the page has had a user tap.
- **Speaker toggle:** a small speaker toggle on the banner (near the
  status bubble / avatar) to replay or mute.
- **Setting:** add "Play Profile Intros" (on by default) to Settings → App
  Functionality.
- **Listen once:** cache "already played this session" per DID so going
  back and forth doesn't replay it.

### Option A playback specifics
- Preview MP3/AAC URLs play in every player Stellar has.
- **Display:** album art + title + artist in a small pill on the banner,
  tap to play/pause, link to Apple Music / Deezer.

## 4. Ground rules to keep (from the main handoff)
- Android keeps all functionality and look; anything a platform can't do
  is grayed out, never hidden.
- Every new on-screen string goes into all 9 language files (es, fr, de,
  it, pt-BR, ru, ja, ko, zh-Hans), and `python3 tools/i18n/extract.py
  --check` reports 0 missing. Non-UI strings go in `tools/i18n/not_ui.txt`.
- Every workflow change is mirrored in `workflow-copy/`.
- No model names in commits or files.
- Before pushing, run `python3 tools/desktop-typecheck/check.py` and
  `python3 tools/ios-typecheck/check.py`.
  - These don't catch JVM-only errors such as a `fun setX()` next to a
    `var x` with a private setter ("platform declaration clash"). The APK
    build on GitHub Actions is the real Android check.
