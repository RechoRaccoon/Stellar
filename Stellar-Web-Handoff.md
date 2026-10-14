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
