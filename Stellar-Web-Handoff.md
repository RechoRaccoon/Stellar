# Stellar – Web Version Handoff (read this first in a new chat)

The goal: Stellar in a web browser, built from the same shared code as the
Android, iOS, Windows and macOS apps. This note records where the project
stands today, the rules to keep, and a plan for the web version. Nothing for
the web exists yet.

The full history of the iOS port is in `Stellar-iOS-Port-Handoff-16.md`.
Read its sections 1 (ground rules) and 4 (architecture) too.

**Branch:** the desktop and language work below is on
`claude/zen-dijkstra-yahycm`. It isn't merged into `main` yet. Start web work
from that branch, or from `main` once it's merged.

---

## 0f. Update: status durations, banner pills, composer Remove bubble, landscape fit (latest)

- **Status popup**: Minutes / Hours / Days / Never. Each unit remembers its
  own number (30 / 24 / 7 by default; Never = 0, greyed); the last unit and
  the numbers live in `ui_toggles` (`UiToggles.statusAmount/statusUnit`),
  so they're in the backup. Your own empty bubble reads "Status" (greyed).
- **Profile banner**: the name pills are one size and together half the
  avatar's height; Follow/Edit (and Back) sit at the top, as far from the
  top edge as the avatar/pills are from the bottom. Profile notes hug their
  text.
- **Composer**: opening an image blurs at once; hold an image and drag it
  onto the "Remove" bubble (centred over the Drafts/counter row) to take it
  off (`ImageDragState`, reusing `HubRemoveBubble`).
- **Landscape**: no top gap on any page (`rememberTopCutoutClearance` is
  ~10dp sideways); Settings gets even left/right room for the camera and
  gesture bar (`rememberLandscapeSideInset`); launchpad labels shrink to fit
  (`OneLineText`); blogs/reviews/VODs and text posts follow the Grid Size
  slider (`Landscape.textCardLanes`, shrunk with `shrinkToWidth`);
  multi-image posts pick the row count that makes the tiles biggest; the
  post UI's base size follows the screen height, and pinching the bar
  resizes the top row and fullscreen button too.

## 0e. Update: side lists shrink to fit, composer viewer, edit-history pictures

- **Side lists** (Hub sidebar, Search, DMs, Settings sidebar) lay out at
  `SIDE_LIST_LAYOUT_WIDTH` (340dp) and scale down evenly with
  `Modifier.shrinkToFit` (`ui/Landscape.kt`) when narrowed, so text is never
  cut. The drag pill is tinted with the page's profile colour.
- **Landscape Settings**: no top gap and no nav-bar gap under the bottom bar.
- **Edit history** keeps the old images: `stellarEditHistory` entries store
  `imageBlobs` (so the PDS keeps the blobs) and the popup shows thumbnails
  (`PostEditVersion.pictures`).
- **Composer images**: a tap opens them fullscreen (`CommentMediaViewer`,
  `showClose = false`, blur behind, tap again to close); press and hold
  without moving to delete (with the existing confirmation).
- **Crop popup**: the hint shrinks to stay on one line, and the video
  autoplays muted and loops (`LoopingVideoPreview`, also used by Edit
  Profile's Animated slots).

## 0d. Update: in-app updates, smaller name pills

- **Version:** one place, `shared/.../AppVersion.kt` (`NAME = "2.8"`).
  - The Android build reads it: `versionName`, and `versionCode` = major × 100 + minor (2.8 → 208).
  - So do the desktop build (`packageVersion` 2.8.0) and the Inno Setup step in `desktop.yml`.
  - iOS `MARKETING_VERSION` in `iosApp/project.yml` is set by hand to match.
  - **Bump it before building a release, to the release tag's number.** Otherwise that build keeps offering itself as an update.
- **Updates** (`util/AppUpdates.kt` + `PlatformUpdater`):
  - Reads GitHub's latest release (tag `vX.Y`) at most every 12 hours. Settings → App Functionality → Check for Updates (on/off, Check Now).
  - The popup shows the release notes, with Update / Later / Skip.
  - Android: downloads `Stellar.apk` into `cache/updates/` (FileProvider path `updates`) and opens the system installer (`REQUEST_INSTALL_PACKAGES`). The first time, it opens "Install unknown apps" for Stellar.
  - Windows: downloads `Stellar-Windows.exe`, opens it, and quits.
  - Mac: opens the matching `.dmg`.
  - iOS: opens the release page.
  - Web: never checks.
- **Profile banner:** the name and handle pills are compact (13sp/11sp, snug padding) at the bottom right. The avatar and follow button keep their old size and place.

## 0c. Update: video icons, crop popup, landscape Hub, status history

- **Status bubble:** plain triangle tail out of its square bottom-left
  corner, tip on the picture's top-right; it grows up to 300 characters as
  far as the banner allows.
- **Grids:**
  - Text bubbles and video cards lay out at a reference width and shrink
    to fit their column, so they keep their shape (`Modifier.shrinkToWidth`
    in `ui/Landscape.kt`).
  - Video cards use the slider's count.
  - The slider runs 1 to 10 (default 5).
- **Edit Profile:**
  - Default / Animated tabs sit on the banner's corner; Animated has slots
    for a video icon and a video banner.
  - A picked video opens `VideoCropDialog` (drag, pinch or corner handle).
  - "Use This" really crops it with `MediaBridge.cropVideo`, and only the
    cropped, silent file is uploaded:
    - Android: Media3 `Crop`.
    - Desktop: FFmpeg `crop`.
    - iOS: `AVMutableVideoComposition`.
    - Web: canvas + `MediaRecorder`; mp4 where the browser can, otherwise
      webm, which iOS can't play.
  - `iconVideo` is stored next to `bannerVideo` in the profile-style
    record.
  - `FeedVideoView` gained `fill` and `cornerPercent`, because native
    video views ignore Compose clipping.
- **Archived → Statuses:** status history, kept on the device
  (`util/StatusHistory.kt`, prefs file `status_history`, included in App
  Data export). Hold one to delete it.
- **Landscape Hub:**
  - A side list on the left: search, the Launchpad (swiping), feeds, Create
    Feed, and the bottom bar. The other rows are on the right.
  - Customize Hub → "Custom Hub Layouts per Orientation" keeps separate
    portrait and landscape layouts (`HubLayout.separateOrientations`;
    keys `rows_json@did#portrait` / `#landscape`, in `hub_layout`).
- **Settings in landscape:** two columns of sections, taking turns; no gaps
  at the top and bottom.
- **Android notch bubble:** in landscape it opens up and down, with stacked
  letters.
- **Launchpad:** Saved Posts uses the bookmark icon.
- **Shared side-list width:** every split page (Hub, Search, profiles,
  Explore, DMs) uses one width, `UiToggles.sideListWidthDp` (default 340dp,
  200–560dp, at most 45% of the window), set by dragging the pill on its
  edge (`SideListEdge` in `ui/SideList.kt`). While dragging, a line in the
  page's color runs the edge's full height.
- **Search in landscape:** the side list has the Hub's search bar at the
  top (no back bubble), then Back and the tabs as buttons, the post-type
  filters, and the grid slider at the bottom.

## 0b. Update: faster profiles, more landscape, video banners, statuses

### Speed
- Hearts effect: each heart color is drawn once into 40 small rotated
  pictures (`HeartSprites` in `ui/DmEffects.kt`); a frame is now ~18 picture
  draws instead of ~1000 path fills. All versions.
- Web pictures: decoding (fetch → createImageBitmap → resize → pixels) runs
  in a Web Worker (`util/WebImageLoading.kt`, `globalThis.__stellarDecoder`),
  and the pixels go into Skia with one bulk copy into the wasm heap
  (`platform/SkiaPixels.kt`) instead of Skiko's byte-by-byte copy. Both fall
  back to the old way if they fail.
- Loading animation defaults to None on the web.

### Landscape
- Hub: Settings/Timeline/Explore/+ on the left and search on the right, in
  one row, each half the width. Search does the same with its tabs.
  Launchpad shows both pages side by side.
- Grid button: only Pinterest-style and square grid in landscape
  (`effectiveGridMode`/`nextGridMode` in `ui/ProfileOverlay.kt`). The column
  count (5 to 10, default 5) is `UiToggles.landscapeColumns`, set with the
  slider at the bottom of the side list.
- Profiles and Explore: tabs, feeds and filters are a list down the left
  (`ui/SideList.kt`: `LandscapeSideList`, `SideListButton`). Back is its first
  row; a line in the profile color splits tabs from filters. The tab and
  filter rows draw themselves as side-list buttons while `LocalSideList` is
  true, so portrait code is unchanged. Explore's feed buttons have their
  icons on every version and orientation.
- Text-post grids are compact. An opened text post is at most 640dp wide.
- Fixed-width pages (Settings, reviews, titles): top and bottom rows match
  the middle column's width. There's no notch gap on the web.

### Video banners and statuses (all versions)
Both live in the existing `com.rechoraccoon.stellar.profile/self` record
(`util/ProfileStyles.kt`), so they cost no extra reads:
- `bannerVideo`: an ordinary blob ref. Edit Profile → Video Banner picks a
  video, which is uploaded to your PDS on Save (resized to 1080p and
  scrubbed, like posts; max 50 MB). It plays muted and looping over the
  banner picture (`ProfileBannerVideo` in `ui/ProfileStatus.kt`), using the
  new `FeedVideoPlayer.setVolumeMuted`.
- `status {text, createdAt, expiresAt?}`: the bubble pointing at the
  profile picture. Tap it on your own profile to set a status; the circle on
  the left sets how long it lasts (minutes, hours or days; default 24 hours;
  0 = never clears).
  Clearing works like this:
  - Every app hides a status once `expiresAt` has passed.
  - Your own app rewrites the record when the time runs out, or on the next
    start if it was closed then.
  - There is no background service; the client-side check makes one
    unnecessary.
  Records are now read again after 10 minutes (it used to be once a
  session), so new statuses show up.

## 0a. Update: web speed, and landscape on every version

### Web speed (web only, `LiteGlass` in `ui/GlassTheme.kt`)
A browser draws the whole app on one canvas on one thread, so the things
the other versions do every frame were what made the web version slow.
`LiteGlass.LIVE` is false on the web only; Android, iOS and desktop are
unchanged.
- **No live glass on the web.** Nothing is recorded into a GraphicsLayer
  (`Modifier.recordBackdrop`, and every `.record {}` site checks
  `LiteGlass.LIVE`) and nothing is blurred (`CAN_BLUR` is false). Glass
  panels that would have blurred a backdrop get a solid "frosted" fill of
  their own color (`LiteGlass.frostedFill`); rims and panel tints are flat
  colors instead of gradients. Page blurs behind popups
  (`Modifier.pageBlur`) become a dim.
- **Still starry sky** on the web (`SpaceSky` doesn't tick).
- **Pictures** (`util/WebImageLoading.kt`): the browser decodes them off the
  page's thread and shrinks them to the size they're shown at
  (`createImageBitmap` → RGBA pixels → Skia bitmap); the old
  download-and-decode stays as the fallback. Small avatars/feed pictures
  ask the CDN for its thumbnail sizes (when the CDN is reachable).
- The 10-a-second video frame copies for blurred video controls are off
  on the web (nothing blurs them now).
- **All versions** (no visual change): off-screen tiles in the profile
  masonry (one non-lazy item) show a same-sized stand-in, so their
  pictures are let go (`MasonryWindowed`); the Hub's Mutuals row is lazy.

### Landscape (all versions, `ui/Landscape.kt`)
Only when the window is wider than tall; portrait is untouched.
- Post grids (Explore, profiles, Search, Hub lists) get more columns as
  the window widens (`Landscape.postLanes`, `resultsLayoutSpec(…, wideDp)`,
  `sharedPostResults(…, wideDp)`); horizontal videos become a video-card
  grid (`VideoCard`); blogs, reviews, VODs, music history, lists and
  Search accounts sit side by side.
- Hub: the launchpad is one row once its buttons fit; search bar and the
  Timeline/Explore bar are centered at a sensible width.
- Timeline: comments open as a side panel and the post moves over.
- DMs (≥ 760 dp): chat list on the left, the open chat on the right.
- Settings, Support, About, the composer, Inbox, blog/review/title pages,
  the Calendar/Notes/Timer/Calculator apps, sign-in, and the Add To /
  Quote popups keep a centered reading width (`Modifier.landscapeReadable`,
  `rememberLandscapeSidePadding`).

### From the update before (not written up then)
- Images: cdn.bsky.app doesn't let web pages read its pictures, so after
  it refuses once, each picture comes from the poster's PDS
  (`com.atproto.sync.getBlob`) — full-size originals, which is why the
  in-browser shrinking above matters.
- Video: frame copies for blurred controls (now off on the web, see above).

### Still open
- "Jumps to Timeline on startup": not reproduced locally — a reload opens
  the Hub; signing in opens Timeline, as on Android.
- Whether e621, Rocksky, Streamplace etc. allow the page to connect (CORS)
  still needs a real browser (the console shows `W/Http: … failed`).

### Local web build (cloud sandbox)
Google's Maven is blocked, so `androidx.annotation` 1.9.1 and
`androidx.collection` 1.5.0 (wasm) were compiled from
compose-multiplatform-core v1.8.2 sources into a local Maven folder, and a
wasm-only Gradle project pointed at the repo's sources built the page; a
Playwright script fed it stand-in Bluesky answers for screenshots.

## 0. Status: the web version exists

Built on `claude/blissful-shannon-dj3lzg` (from the desktop branch, with
`main` merged in). Decisions from Recho: **GitHub Pages**, and the page
**fills the browser window** (the window can be resized to any shape).

**What was done**
- `Dispatchers.IO` → `ioDispatcher` everywhere in commonMain
  (`platform/PlatformFunctions.kt`; `Dispatchers.IO` on Android/iOS/desktop,
  `Dispatchers.Default` on the web).
- Settings: commonMain no longer touches DataStore. `util/settings/Settings.kt`
  is a tiny interface with DataStore's names (`stringPreferencesKey`,
  `edit { }`, …); on Android, iOS and desktop it wraps the very same
  DataStore file (`src/nonWebMain/.../DataStoreSettings.kt`), so nothing moved.
  `PreferencesManager` now reads `context.appSettings`.
- New source set **`nonWebMain`** (Android + desktop + iOS; `shared/build.gradle.kts`
  hierarchy template): DataStore, `Dispatchers.IO`.
- `wasmJs` target + `src/wasmJsMain` (every `actual`), and the **`webApp`**
  module (`Main.kt`, `index.html`).
- `PlatformKind.WEB`; gray-out tag reads **"NOT ON WEB"** (translated).
- `web.yml` (+ copy): builds on every push (artifact `Stellar-Web`); on
  `main` it also publishes to Pages. **One-time:** Settings → Pages →
  Source: "GitHub Actions". Fonts are downloaded by the workflow into
  `fonts/` (Noto Sans, Noto Color Emoji, Noto Sans CJK JP/KR/SC).
- `tools/*-typecheck/check.py` include `nonWebMain`; `extract.py` reads
  `wasmJsMain` too.

**How the web parts work** (all in `shared/src/wasmJsMain`)
- Storage: `WebFiles` — every "file" lives in memory and is saved to
  IndexedDB; it's all loaded before the first frame. SharedPreferences and
  the settings file are JSON files in it. Picked files stay browser `File`s
  (`WebBlobs`); a video upload is sent straight from the `File`.
- Network: `HttpEngine.web.kt` on `fetch()` (no User-Agent — browsers forbid
  it; e621 gets `_client=` instead). Failures are logged to the console.
- Images: Coil with its own fetcher on the same `fetch` (`WebImageLoading`).
- Video: an HTML `<video>` (hls.js for HLS, from jsDelivr) placed **behind**
  the canvas; the post clears a see-through hole there (`WebVideo.kt`), so
  menus and controls still draw over it.
- Back: Esc, mouse back button, browser back (one history entry is kept
  while something can go back) — `webApp/.../Main.kt`.
- Downloads: browser downloads ("Save as…" paths under `/downloads/`).
- Notifications: browser Notification API while the tab is open.
- In-app browser / live-stream embeds: a card that opens a new tab.
- Not on web (grayed out or a message): GIF export, custom video
  thumbnails, re-encoding (videos upload as-is), VRM, Camera, AI tagging,
  Translate, visualizer, scrobbling, widgets, Live Link.

**Tested locally** (headless Chromium, Bluesky's API stood in for): sign-in,
feed, images, video playback behind the canvas with controls on top, emoji
and CJK/Cyrillic text, browser back / Esc, staying signed in after reload.

**Still unknown — check in a real browser:** whether e621, Rocksky,
Streamplace, Last.fm covers etc. allow browser requests (CORS). Anything a
server refuses shows up in the console (F12) as `W/Http: … failed`. If some
refuse, the choices are a small proxy (Recho hosts) or graying them out.

**Local web build in the cloud sandbox:** Google's Maven is blocked there,
and the web build needs `androidx.annotation` 1.9.1 and
`androidx.collection` 1.5.0 for wasm. They can be built from
JetBrains/compose-multiplatform-core sources (tag v1.8.2) into a local
Maven folder; the rest resolves from Google's mirror of Maven Central.

---

## 1. Ground rules (from Recho)

- The owner is **Recho**. Never write instructions as "I" on Recho's behalf.
- **Android keeps all its functionality, look and feel.** Nothing is removed
  or made worse on Android (or iOS, or desktop) for the web.
- Features a platform can't support are **grayed out with a tag, never
  hidden** (see `ui/AndroidOnly.kt`: `PlatformFeatureGate`,
  `PlatformFeatureInline`, `grayedOut()`). The tag reads per platform; the
  web needs its own wording, e.g. "NOT ON WEB".
- Recho builds **only in CI** (GitHub Actions). Every workflow has a copy in
  `workflow-copy/`; keep the two in sync.
- **Every push builds every version.** All workflows run on a push to any
  branch (and by hand from the Actions tab). A web workflow should do the
  same.
- Package `com.mediaviewer`. Don't put model names in commits or files.

---

## 2. Where things stand

| Version | Workflow | Artifact | Status |
|---|---|---|---|
| Android | `build.yml` | `Stellar-APK` | complete |
| iOS | `ios.yml` | unsigned `Stellar.ipa` | builds; 1h40m–3h15m, almost all of it the Kotlin framework step |
| Windows | `desktop.yml` | `Stellar-Windows` (.exe from Inno Setup, .msi from jpackage) | runs on Recho's PC |
| macOS | `desktop.yml` | `Stellar-macOS-AppleSilicon`, `Stellar-macOS-Intel` (.dmg) | builds; not yet tried on a Mac |

Toolchain: Kotlin 2.1.21, Compose Multiplatform 1.8.2, Gradle 8.11.1, JDK 17.

### Code layout
- `shared/src/commonMain` — almost all of the app (about 67,000 lines): UI,
  view model, Bluesky/e621/Rocksky networking, models.
- `shared/src/androidMain`, `iosMain`, `desktopMain` — the platform parts.
  There are **79 `expect` declarations** in 24 commonMain files; each
  platform supplies the `actual`s. The files that have them:
  `platform/` (Platform, PlatformTypes, PlatformFunctions, Preferences,
  PrivateFiles, MediaBridge, LocalPlatform), `network/Http.kt`,
  `json/OrgJson.kt`, `ui/` (FeedVideo, AnimatedGif, Browser,
  PlatformWidgets, compat/Images, compat/Compat, compat/ActivityResult,
  compat/SidewaysScroll) and `util/` (DateText, TitleCovers,
  PreferencesManager, FontStore, EmojiStore, AppBackup,
  AudioVisualizerEngine).
- `desktopApp/` — the desktop window (`Main.kt`), icons, the Inno Setup
  script (`windows/stellar.iss`).
- `PlatformKind` is `ANDROID, IOS, DESKTOP`; the web adds `WEB`.
  `PlatformFeatures` decides what each platform can do.

### Language system (all platforms)
- Ten languages: en, es (Latin American), fr (Canadian), de, it, pt-BR, ru,
  ja, ko, zh-Hans. Chosen under Settings → App Functionality → Language and
  on the login page.
- `i18n/I18n.kt`: translations are keyed by the English text, in
  `composeResources/files/i18n/<code>.json`. `com.mediaviewer.i18n.Text`
  replaces material3's `Text` (import it instead); `tr()` translates any
  other string. Sentences with names or numbers use `{0}`, `{1}` patterns.
- `tools/i18n/extract.py --check` lists any on-screen text a language file
  is missing; `tools/i18n/not_ui.txt` lists strings that aren't UI.
- Any new on-screen text needs translating into all nine files.

### Local check tools (use before pushing)
- `tools/ios-typecheck/check.py` — compiles common + iOS Kotlin.
- `tools/desktop-typecheck/check.py` — compiles common + desktop Kotlin.
- Google's Maven server isn't reachable from the cloud sandbox, so Android
  can't be compiled there; CI is the check for Android.
- A web build can be compiled locally with Gradle if the sandbox can reach
  Maven Central (Google's mirror,
  `maven-central.storage-download.googleapis.com`, works).

---

## 3. Lessons from the desktop port (don't repeat these)

- **Regex on Android.** Android's regex engine rejects a bare `}`:
  `Regex("\\{\\d+}")` crashed the APK on launch. Always escape both braces.
  The JVM, iOS (and probably the web) accept it, so only CI on a phone
  shows it.
- **Case-insensitive file names.** Windows and macOS treat `JSONArray.class`
  and `JsonArray.class` as the same file, so building there silently
  dropped one, which broke profiles, feeds and DMs. Desktop's org.json
  classes are now `OrgJSONObject`/`OrgJSONArray` with type aliases. Never
  give two classes in one package names that differ only by case.
- **JVM-only APIs in common code.** `synchronized()` broke the iOS build;
  use `synchronizedCompat`. Run the typecheck tools before every push.
- **Mouse input.** Compose doesn't let a mouse drag sideways rows, and a
  plain wheel doesn't scroll them. `ui/compat/SidewaysScroll.kt`
  (`LazyRow`, `horizontalScroll`, `mousePager`) fixes this; use those, not
  foundation's. The web needs its own `actual` (the desktop one should work
  almost as is).
- **Errors you can't see.** The desktop app writes warnings, errors and
  failed requests to `stellar.log`. The web version should log to the
  browser console the same way (`Log` actual).

---

## 4. The web plan

### Approach
Add a Compose Multiplatform **`wasmJs`** target to `:shared`, plus a small
`webApp` module (an `index.html` and the entry point), the same way
`desktopApp` sits beside `:shared`. The browser draws the whole UI on a
canvas, exactly as the other versions do, so it looks the same.

Needs a modern browser (Chrome/Edge 119+, Firefox 120+, Safari 18.2+).

### Blockers to clear first (before any feature work)
1. **`Dispatchers.IO` doesn't exist on the web** (browsers have no threads).
   commonMain uses it **231 times**. Add a common `ioDispatcher` (an
   `expect val`, or a top-level `val` in a shared non-web source set). It
   is `Dispatchers.IO` everywhere else and `Dispatchers.Default` on the web.
   Then replace every use; a scripted replacement is fine.
2. **DataStore (`datastore-preferences-core`) is in commonMain** and has no
   web version. Move it to a new intermediate source set used by Android,
   iOS and desktop only (e.g. `nonWebMain`). Give the web its own
   preferences on browser `localStorage` behind the existing `Preferences`
   expect.
3. **Blocking and threading code.** About 71 uses of
   `runBlocking`/`synchronizedCompat`/concurrent maps in commonMain. On
   the web `synchronizedCompat` can just run the block (one thread), but
   any `runBlocking` must go (it can't exist on the web).
4. **Fonts.** The browser canvas can't use the computer's fonts. Emoji,
   Japanese, Korean, Chinese and Russian text only shows if fonts for them
   are bundled (Noto Sans CJK and Noto Color Emoji are large, several MB
   each; load them on demand, only when one of those languages or an emoji
   is shown).

### Networking
- Write an `HttpEngine` actual on the browser's `fetch`, or on Ktor's
  browser engine (Ktor is already what iOS uses).
- **CORS decides what works.** A browser only lets the page call servers
  that allow it:
  - Bluesky (bsky.social, PDSes, api.bsky.app, the chat proxy through the
    PDS): allow it. The official web app calls them directly.
  - Wikipedia/Wikidata: allow it with `origin=*`.
  - **e621, Rocksky, Streamplace, Popfeed, Leaflet and the others:** test
    each one. Any that refuse need either a small proxy server (which
    Recho would host) or graying out on the web.
- Images (Coil 3) work on the web through its Ktor/fetch network layer.

### What maps to what on the web
| Feature | Web plan |
|---|---|
| Feeds, posts, profiles, DMs, Hub, search, blogs, reviews | shared code, should just work |
| Images, GIFs | Coil + a GIF decoder (or the browser's own, drawn into the canvas) |
| Video (MP4, HLS) | the hardest part: Compose can't embed a `<video>` element. Place an HTML `<video>` (with hls.js for HLS) over the canvas, positioned to match the post. Prototype this early. |
| Uploading pictures/videos | browser file picker (`<input type=file>`); no FFmpeg, so no re-encoding or thumbnail stitching: upload as-is, or gray out what needs FFmpeg |
| GIF export, video export | gray out (needs FFmpeg) |
| Saving media | browser download |
| Notifications | the browser's Notification API, only while the tab is open; gray out background ones |
| Sign-in, settings, backups | localStorage; backup as a downloaded/uploaded file |
| In-app browser | open in a new tab |
| VRM mode, Camera page, AI Tagging, Translate, audio visualizer, scrobbling, widgets | gray out ("NOT ON WEB"), same as desktop |
| Back button | the browser's back button (history API) and Esc |
| Language | works as is (needs the fonts above) |

### Layout
Like desktop, open in the phone-shaped layout. On a wide browser window,
center a portrait column (or let it fill; ask Recho).

### Hosting and CI
- A `web.yml` workflow (plus its copy in `workflow-copy/`) that runs on
  every push, builds `:webApp:wasmJsBrowserDistribution`, and uploads the
  site as an artifact.
- **GitHub Pages** can publish it for free (e.g. on pushes to `main` only).
  Ask Recho before turning publishing on and which address to use.
- Note the size: a Compose web app downloads several MB on first visit
  (more with fonts). Later visits are cached.

### Suggested order
1. Blockers 1–3 (no new features). Push and confirm Android, iOS and
   desktop still build and behave exactly the same.
2. Empty `wasmJs` target and `webApp` module that compile, with stub
   `actual`s, and the `web.yml` workflow producing a page.
3. Networking (`fetch`), preferences (localStorage), sign-in. Check CORS
   service by service.
4. Images and GIFs, then the Hub, feeds, profiles, DMs.
5. Fonts for emoji and other scripts.
6. Video (prototype the HTML overlay early, it decides how good the web
   version can be).
7. Uploads, downloads, back button, notifications, graying out the rest.
8. Hosting.

---

## 5. Open questions for Recho
- Publish on GitHub Pages (free, `rechoraccoon.github.io/Stellar`) or a
  custom domain?
- On a wide screen: centered phone-shaped column, or fill the window?
- For services that block browsers (likely e621 and Rocksky): host a small
  proxy, or gray them out on the web?

---

## 6. Other open items (not web)
- **Desktop updates:** there's no in-app update check. Every build is
  version 1.0.0, so the .msi can't upgrade an existing install (the .exe
  can). Offered to Recho: automatic version numbers and an "Update
  available" prompt reading GitHub Releases. Not started.
- **Windows: two installs.** Builds from before the Inno Setup installer
  installed elsewhere; Recho was told to uninstall both and reinstall.
  Offered: make the installer remove the old one automatically. Not
  started.
- **macOS:** builds, but nobody has run it on a Mac yet.
- **iOS build time:** it varies (1h40m–3h15m). If it keeps getting slower,
  try the `macos-15-intel` runner (14 GB of memory vs 7 GB).
- **"Couldn't load this media"** showed on one post on Windows; not
  investigated (unknown which kind of post).
