# Stellar – iOS Port Handoff (read this first in a new chat)

This picks up from `Stellar-iOS-Port-Guide.md`. It records where the Kotlin Multiplatform / Compose Multiplatform port stands, what was decided, and what comes next.

**Latest code:** `Stellar-updates-36.zip` (update 36 = 35 + Tagged Sort, end of section 27). It contains the full repo plus a `workflow-copy/` folder. Update 35 (section 27) polishes the Tips, adds the like heart, the post stats row, and Activity on the Camera page, on top of 34 (section 26).
**Status:** Android is complete and working. The iOS app builds in CI and runs the real shared UI. **Update 22 built on both workflows** (Android #392, iOS #53 — so `iOSApp.swift`'s edits and the new frameworks are confirmed). **Updates 23 and 24 (sections 15 and 16) pass the local compile check** (section 5a) for all shared and iOS Kotlin. What only CI can confirm for them: `StellarFaceTracker.swift` (never compiled) and one small edit to Android's `VrmModeScreen.kt`. **Nothing since update 21 has run on an iPhone.**

**With update 24 the port is feature-complete:** everything Android has now has an iOS version, except the music visualizer (iOS doesn't let an app listen to other apps' sound). What's left is finding out what a real iPhone makes of it.

**Three local tools exist — use them before sending any update:**
- `tools/ios-typecheck` — compiles the shared + iOS Kotlin (section 5a).
- `tools/vrm-test` — runs the VRM loader, retargeter and spring bones against real .vrm files, draws them to PNGs (section 15), and tries the body/hand tracking maths with made-up poses (`--lift`, section 16).
- `tools/stream-test` — pushes a real stream through the RTMP publisher into ffmpeg (section 15).

---

## 1. Ground rules (from Recho)

- The owner is **Recho**. Never write instructions as "I" on Recho's behalf.
- Recho builds **only in CI** (GitHub Actions). CI errors are usually relayed through a helper named Muse. Every change is delivered as a zip that includes a visible `workflow-copy/` folder holding copies of the workflow files.
- **Android keeps all functionality, look and feel.** Nothing Android-side gets removed or degraded for iOS.
- Package: `com.mediaviewer`. applicationId: `rechoraccoon.stellar`.
- Android workflow (`.github/workflows/build.yml`) stays as is:
  - Builds a signed `Stellar.apk`.
  - Keystore file `stellar-release.jks`, alias `stellar`, password `Stellar2026`.
  - Artifact name `Stellar-APK`.
- The iOS workflow is separate: `.github/workflows/ios.yml`.
- On iOS, Android-only features are **grayed out with an "Android only" tag**, never hidden. Use `PlatformFeatureGate`, `PlatformFeatureInline` or `grayedOut()` from `ui/AndroidOnly.kt`.
- **Live Link** is disabled on both platforms.
- No iOS simulator build. Recho's friend tests on a real iPhone.

## 2. App Store decisions (iOS only)

- **Adult content** follows the Bluesky account setting (`adultContentPref` via `app.bsky.actor.getPreferences`). Users can only enable it on the website.
  - Code: `util/AdultContentPolicy.kt`. `appliesHere` is true on iOS; `hides(item)` filters NSFW-labeled posts in `parseFeedItemSafe`.
- Settings has an **"NSFW Content"** bubble with a "Manage on Bluesky" button that opens `https://bsky.app/moderation`. This is on both platforms.
  - Android also keeps "I Hate Fun" below a divider in the same bubble.
  - iOS has no "I Hate Fun".
- **e621 is removed on iOS** (`FeatureFlags.E621_ENABLED = currentPlatform == ANDROID`). The e621 account bubble and Hub rows are hidden on iOS.
- **Report** feature for posts and accounts uses Bluesky's official `com.atproto.moderation.createReport`, sent with the header `atproto-proxy: did:plc:ar7c4by46qjdydhdevvrndac#atproto_labeler`.
- Target age rating: **16+**.

## 3. Toolchain

| Component | Version |
|---|---|
| Kotlin | 2.1.21 |
| AGP | 8.7.3 |
| Gradle | 8.11.1 |
| Compose Multiplatform | 1.8.2 |
| lifecycle | 2.9.1 |
| Coil | 3.2.0 |
| Ktor (iOS/Darwin) | 3.1.3 |
| kotlinx-serialization | 1.8.1 |
| DataStore | 1.1.7 |

## 4. Architecture

Modules: `androidApp/` (thin), `shared/` (almost everything) and `iosApp/` (XcodeGen project).
Source sets: `shared/src/{commonMain,androidMain,iosMain}/kotlin/com/mediaviewer/`.

### Patterns and gotchas

- **expect/actual everywhere.** Android actuals are usually the original Android code, often an `actual typealias`.
- **Expect classes can't take constructor property params.** Nested sealed result types move to top level; for example, `FontCopied` replaced `FontFiles.Copied`.
- **`actual var` doesn't satisfy `expect val`.** Use `private var _x by mutableStateOf(...)` with `actual val x get() = _x`.
- **Extension properties need explicit imports**, even when written fully qualified. Examples: `WindowInsets.navigationBars` and `navigationBarsIgnoringVisibility`.
- **JVM facade clashes.** An Android file that shares a name with a common file gets renamed `*.android.kt`.
- **`localizedStringForLanguageCode` doesn't exist in Kotlin/Native.** Use `NSLocale(localeIdentifier="en").displayNameForKey(NSLocaleLanguageCode, tag)`.
- **Code that can't be verified locally:** Swift files and Android-only code (`androidMain`). CI is the real compiler for both. Shared and iOS Kotlin — including every UIKit / AVFoundation call — can be checked locally: see section 5a.
- **Obj-C category members are extension functions in Kotlin/Native and need their own import** (for example `import platform.AVFoundation.setVideoComposition`). A member that "doesn't exist" on an Apple class is usually this.
- **An extension function can't be called by its full name without a receiver** (`androidx.compose.ui.graphics.drawscope.clipRect(...) {}` inside a DrawScope doesn't compile — import it).
- **iOS moves the app's data folder** (`…/Application/<UUID>`) on an update or AltStore refresh. Never rely on a saved absolute path surviving; `IosPaths.rehome` fixes saved ones as they're read.

### App shell

- **`ui/AppRoot.kt`** (common): the whole app UI, moved out of MainActivity.
- **`ui/SharedAppHost.kt`** (common):
  - `SharedAppStartup.init` starts the stores: UiToggles, FontStore, ListRecency, HubLayout, TitleCovers, WikipediaRepository, ProfileColorStore, SelfProfileColors, AdultContentPolicy.
  - Also handles ViewModelStore restarts via `AppEvents.restart`, foreground tracking, and `AppMessageToast`.
- **`ui/CrashLogScreen.kt`** (common): the crash screen.
  - iOS installs its crash log in `MainViewController.kt` via `setUnhandledExceptionHook`.
- **Android `MainActivity.kt`:**
  - Hosts AppRoot and CrashLogScreen.
  - Hides both system bars.
  - `navBarSpace` keeps the floating bars where they used to sit.
- **iOS `MainViewController.kt`:** installs the crash log, `IosImageLoading` (Coil with Ktor Darwin) and `IosNativePickers` (PHPicker, UIDocumentPicker, with exports going to the Documents folder).

### Platform abstraction

- **`ui/PlatformWidgets.kt`** expects: `EmojiPanel`, `InlineVideoPlayer`, `EmbeddedWebView`, `renderTextshotPreview`, `CameraNotchButton`, `DebugOverlay`, `VrmModeScreen`, `CameraModeScreen`, `CapturePreviewScreen`.
- **`ui/FeedVideo.kt`:** `FeedVideoPlayer` / `FeedVideos` / `FeedVideoView`.
  - Android implements them with `ExoFeedVideoPlayer`, which wraps `FeedVideoPool`.
  - iOS implements them with `AvFeedVideoPlayer` (AVPlayer, looping) and `PlayerLayerView`.
- **`ui/compat/Compat.kt`:**
  - `PlatformView` provides `captureScreen`, `displayCutoutCenterYPx` and `crunchHaptic`.
  - Expect functions: `vibrateOneShot`, `restartApp`, `appPackageName`, `applyReducedAnimations` (a no-op on iOS).
  - Expect property: `WindowInsets.navBarSpace`.
- **`ui/compat/Images.kt`:** `sampleAverageColor`, `coilContext`, `qrModuleMatrix`.
- **`ui/ShatterTransition.kt`** is common. Android's capture code lives in `ShatterCapture.android.kt`.
- **`util/` expects:**
  - `FontStore` (with `FontFiles`, `audiowideFontFamily`)
  - `EmojiStore`
  - `AppBackup` (throws "not available" on iOS)
  - `AudioVisualizerEngine`
  - `TranslationManager.engine` (ML Kit on Android, `null` on iOS)
  - `DateText`
  - `ImageCache`
- **`util/QrEncoder.kt`:** a pure-Kotlin QR encoder, verified against ZXing.
- **`platform/PlatformFeatures.kt`:** the feature enum used by the gates (for example AUDIO_VISUALIZER, TRANSLATION, OPEN_BY_DEFAULT_LINKS, AI_TAGGING, CUSTOM_FONT).

### iosMain files

- `MainViewController.kt`
- `ui/PlatformWidgets.ios.kt`
- `ui/AndroidOnlyScreens.ios.kt`
- `ui/FeedVideo.ios.kt`
- `ui/compat/{IosScreen, IosNativePickers, Images.ios, Compat.ios}.kt`
- `util/{IosImageLoading, ImageCache.ios, FontStore.ios, TranslationManager.ios, DateText.ios, EmojiStore.ios, AppBackup.ios, AudioVisualizerEngine.ios}.kt`

## 5. CI / iOS project

**`.github/workflows/ios.yml`** runs on macos-15 and takes about 40 minutes (the Kotlin/Native build is slow; `~/.konan` is cached). Steps:

1. Set up Xcode 16, JDK 17 and Gradle 8.11.1.
2. Run `gradle :shared:linkReleaseFrameworkIosArm64`, which builds a static `Shared` framework.
3. Copy `shared/src/commonMain/composeResources/.` into `iosApp/compose-resources/composeResources/com.mediaviewer.resources/`.
4. Run `brew install xcodegen`, then `xcodegen generate`.
5. Build an unsigned iphoneos app with `xcodebuild`, package it as `Stellar-unsigned.ipa`, and upload it as the artifact **`Stellar-iOS-unsigned`**.

**`iosApp/project.yml`:**

- The `compose-resources` folder is added as a resources build phase.
- `OTHER_LDFLAGS` links Shared, AVFoundation, AVKit, CoreMedia, WebKit, Photos, PhotosUI and UniformTypeIdentifiers.

**`iosApp/iosApp/Info.plist`** sets:

- Status bar hidden: `UIStatusBarHidden`, and `UIViewControllerBasedStatusBarAppearance` = false.
- File sharing on: `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace`, so exports show up in the Files app.

**Build status:**

- Every update through 20 has built on both workflows. iOS builds take about an hour (20 minutes when the runner is having a good day).
- `ios.yml` now cancels an older iOS build when a newer push arrives, and can be run by hand on `macos-15-intel` (14 GB of memory instead of 7) to see whether that Mac is faster — Actions › Build Stellar iOS › Run workflow › "Which Mac to build on". If it is, change the default in `runs-on`.
- `iosApp/project.yml` links two more system frameworks: BackgroundTasks and UserNotifications.

## 5a. Local compile check (new)

`tools/ios-typecheck/` compiles `shared/src/commonMain` + `shared/src/iosMain` for `ios_arm64` with the real Kotlin/Native compiler and the real Apple platform libraries, on Linux, using only GitHub (Maven Central is not reachable from these sessions).

```
sh tools/ios-typecheck/setup.sh        # once per session: ~2 GB from GitHub, a few minutes
python3 tools/ios-typecheck/check.py   # ~2 minutes; prints only errors in Stellar's own files
```

- It catches what the compiler's front end reports: unresolved names, wrong types, missing imports, wrong Apple API names and signatures, `@Composable` misuse, missing opt-ins, kotlinx.serialization problems. In this session it caught three real errors before they reached CI.
- It does **not** cover Swift, `androidMain`, link-time problems, or behaviour on a device.
- How it works: the libraries (Compose Multiplatform, coroutines, serialization, Coil, Ktor, okio, DataStore, lifecycle, Skiko) are compiled from their source alongside the app in one compiler run; the ~1000 errors inside library code are expected and ignored. Material icons and the generated `Res` class are stood in for automatically. `check.py`'s header explains the details.
- Library versions are pinned in `setup.sh`; update them there when `build.gradle.kts` changes.
- If the check says "0 errors" make sure the line before it lists the app's files (`appCommon`, `appIos`) — and if in doubt, add a deliberate typo to see it reported.

## 6. NEXT STEPS (in order)

1. **Read the CI results for the latest update on both workflows.** Likeliest trouble on iOS: `StellarFaceTracker.swift` (never compiled) and the new App Intents code in `StellarWidgets/StellarWidgets.swift` (section 17) — a Swift error in either stops the whole iOS build, so fix those first; then the link step. On Android: `widget/StellarWidgets.kt` (section 17) and the one-line VRM fix (section 16) were edited without a compiler.
2. **One device test** — the list in section 16, "The device test". Ask the tester which **Fixes** switches (VRM Settings › Fixes) they had to turn on; each one that's needed should become the default (`VrmSceneOptions`, `VrmStage`, `VisionLift`), and then its switch can go.
3. **Fix whatever the test turns up.** Run all three tools before sending.
4. **Still different from Android** (all deliberate, see section 16): no music visualizer; VRM/Camera buttons are tinted rather than live-blurred; no volume-key shutter; node constraints (twist bones) aren't on either platform.
5. **App Store prep:** signing with a paid account (certificate + two provisioning profiles + the App Group), the age-rating questionnaire (16+), review notes. Done: icon, launch screen, privacy manifest, encryption flag, usage texts (camera, microphone, photos).

## 7. Sideload steps for testers (AltStore, Windows + iPhone)

1. Make a spare free Apple ID. Don't use your main one.
2. On the PC:
   - Install iTunes and iCloud from Apple's website (not the Microsoft Store versions).
   - Install **AltServer** from altstore.io.
3. Plug in the iPhone and tap Trust.
4. Install AltStore onto the phone: AltServer tray icon → Install AltStore → your phone → sign in with the spare Apple ID.
5. On the iPhone:
   - Settings → General → VPN & Device Management → trust the spare Apple ID.
   - Settings → Privacy & Security → turn on Developer Mode, then restart.
6. Put `Stellar-unsigned.ipa` on the phone (AirDrop, iCloud Drive or Files). In AltStore: My Apps → + → pick the ipa.
7. The app lasts 7 days. Refresh it in AltStore while the PC's AltServer is running on the same Wi-Fi.

**Mac variant:** the same flow with AltServer for Mac. No iTunes or iCloud is needed; enable the Mail plug-in when prompted.

## 8. Recent feature work (already done, Android and iOS shared)

**Reporting and content settings**
- Report popup for posts and accounts.
- NSFW Content setting.

**Post screen**
- More menus are animated icon bubbles with haptics.
- Profile-colored active icons.
- Share button sits right of GIF.
- Multi-image and text-only posts are centered.
- Landscape post UI: compact, even side gaps, a fullscreen button and tap to reveal.

**Profiles**
- Share profile, and a More menu with the Bluesky link, Report and Block.
- Profile cards shared in DMs.
- Feeds-row profile bubble fix.

**Search**
- Redesigned page: back button, recent searches, account bubbles, posts pagination and all sub-tabs shown.

**Other screens**
- Hub list rows fix.
- Tags page bubbles.
- QR page closes when Camera or VRM opens.
- Camera landscape rotation fix.

**System bars**
- Android nav bar is hidden, with the bars kept in their original positions.
- Evened bar heights.

---

## 9. Update 17 (compiled, tested on Android)

### iOS fixes from the first device test
- **No sound in videos:** the audio session is now set to Playback at launch (`IosAudioSession` in `MainViewController.kt`), so the silent switch no longer mutes video. `AVFAudio` is linked in `iosApp/project.yml`.
- **No landscape:** `Info.plist` now allows landscape left/right on iPhone.
- **Password field:** on iOS it uses a plain ASCII keyboard with autocorrect off and has a Paste button. Android is unchanged.
- **Download image/video:** file names are now built safely from the media type, Photos add-only permission is requested first, and real errors are reported (`IosDownloads.kt`, `DownloadInfo.kt`).
- **Backup/restore** now works on iOS (`AppBackup.ios.kt`).

### Shared features (Android and iOS)
- DMs: messages move up with the keyboard.
- Hub: Mutuals row hidden with no mutuals; new default row "Stellar's Supporters"; welcome popup then tutorial popup, once per account; Support Stellar popup on the 10th open.
- Comments: replying no longer inserts an @handle and replies to the exact comment.
- Translating a post no longer vibrates.
- Add To: hold a list to delete (confirm popup), double-tap a list cover to change it.
- Profiles: @handles in bios open that profile; blog Back button moved into the blog's interaction bar; new far-right "Lists/Feeds" tab (Add / Pin to Feeds / Follow All / Block All, members popup, feeds open in Explore and return to the profile); pink shining "Supporter" label and confetti for Stellar supporters.
- Search: bar matches the Hub's; Back button splits out of the bar with an animation.
- Customize Hub: collapse arrow; every row removable; "Add" row with Default / Profiles / List; "Accounts" renamed "Profiles"; Profiles rows are local only.
- Lists can be pinned as feeds (stored as Bluesky's own saved-feed type "list"; shows original posts only, newest first).
- Settings → Support Stellar mentions the $4.99 supporter perk. Dev Tools can preview the welcome and support popups.

### Android only
- VRM: hand tracking toggle; orbit / pinch-zoom / two-finger pan when Follow Head is off; IK hands sit 14 cm further forward; solid profile-colored buttons in performance mode; settings popup rebuilt with tabs (Tracking, Avatar, Display, Audio & Web) with every setting kept.

### Decisions and constants
- `util/StellarOfficial.kt` holds the Stellar account, the supporters list, the For You feed and `TUTORIAL_POST_URL`. **`TUTORIAL_POST_URL` is blank**, so the tutorial popup shows a "Placeholder" card; put the tutorial's bsky.app post link there when it exists.
- "Block All" on a moderation list subscribes to the list as a block list (a `listblock` record), so it can be undone with "Unblock All".
- "Follow All" follows accounts one at a time to stay inside rate limits.
- The supporters list is re-read on every app open and cached on the device.
- The welcome popup shows once per account, including for existing users on their first open after this update.
- The DM header stays in place when the keyboard opens; the messages move.

### Still open on iOS
- Reduced Animations (needs a `MotionDurationScale` in the Compose host's coroutine context), custom emoji panel, textshot preview, GIF export, translation engine, custom font import, audio visualizer, notch ring, universal links.
- The whole-screen blur behind the welcome popups uses a Compose render effect: Android 12+ and iOS blur; older Android only dims.

---

## 10. Update 18 (built on both workflows)

### Shared (Android and iOS)
- Hub row renamed "Stellar Supporters".
- Welcome popup: switches keep their color while Continue is working; the two feeds are added to the top of the feeds list (For You, then Stellar Supporters), both pinned.
- "features" → "benefits" in the supporter text.
- Support popup shows on the 10th, 25th, 50th open and every 25 after (`Onboarding.supportMilestone`).
- Supporter confetti waits for the loading screen to finish and plays once per visit.
- Own lists / starter packs / moderation lists: a delete button in the members popup header, with a confirm prompt.
- Customize Hub: Profiles rows have a pen button that reopens the picker to add/remove accounts and rename.
- Search: Feeds and Starter Packs use the same rows as the profile Lists/Feeds tab (Add; Follow All with a confirm; tap a pack to see its accounts).
- Dev Tools → Preview Welcome Popup opens the popup directly.
- Bios: @handles are plain text in the profile color (no bold); `bsky.app/profile/...` links show as `@handle` and open in Stellar.
- Export App Data already carries Customize Hub (order, on/off, lists, Profiles rows, removed defaults) through the `hub_layout` preference file on both platforms; nothing needed changing.

### Android only (VRM)
- Free camera fix: gestures now write straight to the renderer (they were only stored in Compose state that nothing recomposed on).
- Full bright moved to the Avatar tab. Tabs are sized to their labels.
- Background: color wheel + hex popup (`ColorWheelDialog`) and a Reset button.
- Go Live popup has the same close button as VRM Settings.
- Voice pitch can change mid-recording (recordings with a mic now always go through `PitchedAudioRecorder`) and mid-stream.
- Overlays can be switched between shown/hidden in captures mid-recording/stream (the compositor is set up whenever any overlay exists), and pages keep running while a popup is open during a capture.
- Overlay capture now draws each page with the GPU into an `ImageReader` (`BrowserOverlayRegistry.hardwareSnapshot`) at ~10 fps, falling back to the old software snapshot if that throws. **Untested** — this is the fix for the capture going white after navigating, and needs checking on a device.

### Open question
- The bio tag report ("shouldn't display the entire URL link", "unable to open link") was fixed on a best guess (see the Bios line above). If it is still wrong, ask for the exact bio text.
- (18f) The profile "Lists/Feeds" tab only appears when the account has any. It is probed on every profile open like Blogs/Vods, and its rows are kept in the profile tab cache (`CachedProfileTabs.lists`).

---

## 11. Update 19 (built on both workflows)

Written without a compiler again (a Kotlin syntax parse of every file passed; types and imports are unchecked). Expect compile errors on both workflows, most likely in the iOS/Swift and Android-only files listed below. Step 1 of section 6 applies to this update too.

### Fixes (shared)
- iOS keyboard closes on a tap outside the field and on Search (`ui/KeyboardDismiss.kt`; every `BasicTextField` in `ui/` goes through its wrapper).
- Video player glass blur on iOS: `VideoFrameGrabber` (`FeedVideo.ios.kt`) feeds small frames to `NativeVideoBackdrop` in `GlassTheme.kt`.
- Shared posts in DMs open from the data already loaded and swipe between the chat's shared posts (`openDmSharedPost`).
- Feeds tapped in Search open like in profiles/DMs (`openFeedFromSearch`).
- Bookmark folders sort by the most recent post added (`BookmarkFolder.updatedAt`).
- Customize Hub sits above UI Customization in Settings.
- Hub: after switching feed, scrolling down opens the picked feed.
- Video upload: starts when the video is attached (`util/VideoUpload.kt`, `prepareVideoUpload`); tries Bluesky's video service, then falls back to a plain blob upload to the PDS. The root cause of the old failures was never confirmed, so this needs a device test.
- Video post counter counts the real two-character gap between title and description.

### Supporter features (shared unless noted)
- **Archive**: More menu → Archive on own posts. Record + media are stored in app-private files (`util/PostArchive.kt`, `platform/PrivateFiles`), the post is deleted; Launchpad → Archived; "Add to Profile" re-uploads with the original rkey/createdAt. Hold "Add to Profile", then tap again, to delete an archived post for good.
- **Notes**: the page title is the note title; `- [ ]` checklists; Return continues list/checklist markers.
- **Profile customization**: one record, collection `com.rechoraccoon.stellar.profile`, rkey `self` (square icon, effect, two colors). Fetched once per profile per session (`util/ProfileStyles.kt`).
- **Home-screen widgets** (DMs, Upcoming Events, Note): Android `widget/StellarWidgets.kt`; iOS `iosApp/StellarWidgets` (WidgetKit extension, App Group `group.rechoraccoon.stellar`). The iOS widgets only get data on a build signed with that App Group; the unsigned/AltStore build will most likely show placeholders.
- **Hub widget**: Customize Hub → Add → Widgets → Upcoming Events.
- **VRM (Android)**: capture mode cycles photo → video → live; the old Live bubble is "Activity" (`ui/VrmActivity.kt`): scenes (`StellarSceneCard`), soundboard (`stream/SoundboardMixer.kt`, mixed into the recording/stream audio), effects. The stage layer is snapshotted ~20 fps into the existing `OverlayCompositor` as a full-screen `CaptureOverlay`. Background image/looping video: `ui/VrmBackground.kt` (an unlit glTF quad inside the Filament scene), Settings → Display. All untested on a device.

### Other
- Hub blog cards: fixed-size portrait cards (`HubBlogCard`).
- Android notifications: alarm every 5 min + expedited work + battery-optimization prompt, new HIGH channels, tap opens the chat/inbox (`worker/StellarNotificationWorker.kt`). In-app banner for DMs/inbox activity (`ui/InAppNoticeBanner.kt`) on both platforms.
- Login: any AT Protocol handle (handle → DID → PDS), app or main password, email sign-in code. Create Account: bsky.social account, Bluesky's own human-check page embedded (cannot be restyled), then email code before entering the app. A birthday field was added because Bluesky requires it.
- iOS translation: `StellarTranslator.swift` (Apple Translation, iOS 18+) registered through `IosBridges.kt`; below iOS 18 it stays gated.
- Support page benefits list updated; the Support popup now mirrors the page (logo, benefits panel that scrolls, links).

### Still gated on iOS (unchanged, "Android only" tag)
- VRM / ARKit face tracking, camera, streaming: needs a full renderer + ARKit port; not started.
- AI tagging: the Android model has no iOS runtime here yet.
- Audio visualizer: Bluesky videos are HLS, and iOS gives no audio tap for HLS streams (`MTAudioProcessingTap` does not work on them), so it cannot react to in-app videos.

---

## 12. Update 20 (built on both workflows)

Same caveat as section 11: syntax-parsed only. The Swift files and `IosTaggingRepository.kt` are the likeliest places for compile errors.

### iOS build
- The Kotlin/Native compile ran out of heap. It runs inside the Gradle daemon, so `org.gradle.jvmargs` went from 4 GB to 6 GB (`gradle.properties`). The macOS runner has 7 GB; if it still fails, the next step is splitting `MainViewModel.kt` / `BlueskyRepository.kt`, not more heap.
- `iosApp/project.yml` now pulls ONNX Runtime through Swift Package Manager (`onnxruntime-swift-package-manager`, product `onnxruntime`). If package resolution fails in CI, removing the `packages:` block and the `- package: onnxruntime` dependency makes the app build without the tagger (`StellarTagger.swift` is guarded with `#if canImport(OnnxRuntimeBindings)` and AI Tagging goes back to "Android only").

### iOS features added
- **AI Tagging**: same model file as Android (Z3D-E621-Convnext). `iosApp/iosApp/StellarTagger.swift` downloads, loads and runs it; `shared/src/iosMain/.../tagging/IosTaggingRepository.kt` is the `TaggingService` (dataset kept as one JSON file, same search rules, same thresholds 0.25 / 0.15, import/export compatible with Android). Videos are tagged from their cover picture, not the middle frame. `PlatformFeature.AI_TAGGING` is available when the tagger registered (`IosCapabilities`).
- **Save as GIF**: `StellarMediaTools.swift` (AVFoundation + ImageIO), called from `IosDownloads.saveAsGif`. Up to 25 fps, 540 px longest side, 600 frames.
- **Download**: the file's real type is read from its first bytes before it goes to Photos.

### Shared
- Holidays: `util/Holidays.kt` (rules computed on device, lunar-calendar holidays from a table covering 2026–2030 — extend `TABLE_YEARS` tables before 2031). Calendar dots: white = built-in day, profile color = own event. Supporter Settings: Major Holidays / Minor Holidays. Upcoming lists (Hub + home screen) include major holidays only.
- Calendar: time row removed, every event is all day; tapping an Upcoming row opens the Calendar on that day (`calendar:<yyyymmdd>` link, `LocalOverlays.openCalendarDay`).
- DM effects: `DmEffect` now has BIRTHDAY (confetti + balloons), HEARTS, RAIN, BUBBLES. All eight are offered as profile effects and in the VRM Activity popup.
- Profile style: read through the signed-in server for the own account; a missing record no longer wipes the device copy (it is re-sent); a save that can't be read back reports an error. **Root cause of the reported reset was not found by reading the code** — if it still resets, the red error text in Edit Profile is the thing to capture.
- Archive "Add to Profile": busy state was never reaching the bar (state read inside a `SideEffect`); uploads now send bytes with a known length and time out with a message.
- Create Account: the human check finishes on `bsky.app`, not `bsky.social`; every navigation is checked through `BrowserState.onUrl`.
- Android widgets: background is three tinted shape drawables instead of a stretched bitmap.

---

## 13. Update 21 (built on Android; iOS build was still running — Kotlin passes the local check)

### iOS features added
- **Textshot**: `iosMain/util/TextshotRenderer.ios.kt` (Skia paragraph; same wrapping rules as Android, ink scaled to fill the frame). Used for both the live preview and the posted picture.
- **Custom emoji**: `iosMain/util/EmojiStore.ios.kt` (same library shape as Android, stored under Application Support/Stellar/emoji) and `iosMain/ui/EmojiPanel.ios.kt` (a copy of Android's panel; imports come from the photo library or one file from Files — there is no "import folder" on iOS).
- **Custom fonts**: `FontFiles` on iOS copies a picked .ttf/.otf into Application Support/Stellar/fonts. Fonts are looked up by file name because iOS moves the app's folder on update. `PlatformFeature.CUSTOM_FONT` is now available on iOS.

### Still open on iOS
- Reduced Animations: needs a `MotionDurationScale` in the Compose host's coroutine context, which `ComposeUIViewController` gives no hook for.
- Custom video thumbnails (stitching), audio visualizer, notch ring, universal links, camera, VRM, streaming.

### Shared
- Hearts effect redrawn (no outline, layered for thickness); bubble pops use `rememberHapticTap`.
- `stellarsocial.bsky.social` is hidden from the Stellar Supporters row/feed alongside Recho (`isHiddenSupporter`).
- Support popup: logo removed, benefits list no longer scrolls.

---

## 14. Update 22 (built on both workflows; not yet run on an iPhone)

### Fixes Recho asked for (shared, Android and iOS)
- **DM header vanishing during the bubbles effect.** While bubbles are out, the chat is recorded over the sky for the bubbles to blur; the sky is the whole screen and was drawn unclipped, painting over the header above the messages. It's now clipped to the chat (`DmInboxOverlay.kt`).
- **Edit Profile popup** is as tall as its contents (the 640dp cap is gone). It still scrolls when the screen itself has no room: keyboard open, landscape.
- **Archived posts are in the App Data Export**, on Android and iOS. The list travels as `"archivedPosts"` and the pictures/videos as a second streamed section, `"archiveMedia"`, after `"localMedia"`. Import **adds** them to what's already archived on the device (nothing already there is replaced) and re-points their file paths at the new device (`PostArchive.exportJson` / `importJson`, `PrivateFiles.rootUri`). Older app versions importing a new backup simply ignore both.

### iOS: new
- **Notifications while Stellar is closed** (`platform/IosNotifications.kt`). The same check as Android's worker — unread DMs and new Inbox activity, supporters only — posted as ordinary iPhone notifications; a tap opens the chat / Inbox. It also keeps the DMs widget current and sets the number on the app icon. What wakes it is iOS's background app refresh: Stellar asks for one every 15 minutes, iOS decides when it really happens (often much later, never in Low Power Mode or with Background App Refresh off). There's no way to tighten that without a push server. Settings' two rows say so on iOS. `README.md` still says "(android only)" — left for Recho to reword.
- **Custom video thumbnails** (`platform/IosVideoStitcher.kt`): the picture becomes the video's first second, like Android. Re-encodes the whole video with AVFoundation.
- **Camera page** (`ui/compat/IosCamera.kt`, `ui/CameraScreens.ios.kt`): Apple's own camera (photo and video), then a review page with Save to Device and Create Post. From the posting page a capture goes straight into the post. No crop menu, voice pitch, browser windows or Live on iOS.
- **Notch bubble** (`CameraNotchButton` in `ui/CameraScreens.ios.kt`): a ring around the Dynamic Island that opens to Camera | VRM like Android (VRM opens VRM mode as of update 23). Older-notch iPhones get a slim bubble peeking out under the notch; iPhones with no notch get Android's small top-centre ring. iOS doesn't report the island's position, so it's placed from Apple's published size (126×37 pt, ending 11 pt above the safe area).
- **Open Bluesky links in Stellar**: iOS only lets the owner of bsky.app claim its links, so the way in is `stellar://open?url=<link>` (also `stellar://profile/<actor>[/post/<rkey>]`), meant for a two-step share-sheet Shortcut. Settings › "Open Bluesky Links in Stellar" › Set Up explains it on iOS and copies the prefix (`AppLinks.pendingProfile`, `SharedAppHost`).
- **Saved files survive an app update / AltStore refresh.** iOS renames the app's data folder then, which broke every saved absolute path (draft and note media, folder covers, archived posts). Paths are now corrected as they're read (`IosPaths.rehome`, used by the preference files, and `healMovedAppFolderPaths()` for the settings file at launch).
- **Backup import takes effect straight away**: the stores it writes to re-read their files (`SharedAppStartup.reloadAfterImport`). Before, the rebuilt app kept showing — and could save back — what was in memory.
- **Reduced Animations** row on iOS explains that the app follows the iPhone's Reduce Motion switch (no switch of its own).
- Launch screen is black instead of white. Privacy manifest filled out. Camera / microphone usage texts added (required — iOS closes an app that opens the camera without them).

### Needs a look on a device
- Notch bubble: does the ring sit on the island, and does a tap beside it open it?
- Camera: photo and video both come back; video posts upload (.mov).
- Custom thumbnail: right way up for portrait and landscape videos; sound still in step.
- A notification arrives with the app closed (give iOS time; it's faster for apps opened often), and tapping it opens the right chat.
- After an AltStore refresh: draft pictures, note media and archived posts still show.
- Export on one phone, import on another (and Android ↔ iOS): archived posts appear with their media.

### VRM on iOS
Done in update 23 — see section 15.

### Tooling
- `tools/ios-typecheck/` (section 5a).
- `ios.yml`: newer pushes cancel older iOS builds; optional `macos-15-intel` run by hand.

---

## 15. Update 23 (never pushed on its own — included in update 24; compile-checked and desktop-tested where possible; not yet run on a device)

### VRM mode on iOS (new)
Opened from the notch bubble's VRM half. Files: `shared/src/iosMain/kotlin/com/mediaviewer/vrm/*`, `ui/VrmModeScreen.ios.kt`, `iosApp/iosApp/StellarFaceTracker.swift`.

- **What it does:** choose a .vrm from Files → the avatar is drawn (SceneKit) and follows your face (ARKit, the Face ID camera): head turn, blink, mouth, brows, with spring bones for hair. Drag to turn / raise, pinch to zoom. Photo, video (with mic) and **Go Live** (RTMP/RTMPS; server URL, stream key, 432p/576p/720p). Settings: avatar, spring bones, smoothing, background colour, reset view, Troubleshooting.
- **Same code as Android where it could be:** `AvatarRetargeter.kt`, `VrmSpring.kt`, `Quaternion.kt`, `OneEuroFilter.kt` and `stream/RtmpPublisher.kt` are copies of Android's files with only the renderer / socket calls swapped for small interfaces (`VrmRig`, `RtmpTransport`, `RtmpThreads`). Android's files are untouched. If one side is fixed, port the fix to the other.
- **What's new code:** `GltfDoc.kt` (reads meshes, skins, morph targets, materials out of the .vrm — Android leaves that to Filament), `VrmScene.kt` (builds the SceneKit model), `VrmStage.kt` (view, camera, per-frame loop), `VrmRecorder.kt`, `stream/IosH264Encoder.kt` (VideoToolbox), `stream/IosAacEncoder.kt`, `stream/IosStreamIo.kt` (sockets/TLS), `stream/IosLiveStreamer.kt`.
- **Face tracking crosses from Swift as plain numbers** (`IosFaceTracker` / `IosFaceListener` in `IosBridges.kt`): ARKit's matrices are SIMD types, which Kotlin/Native handles poorly.
- **iPhones without Face ID** can open VRM mode and see / photograph the avatar; it says the avatar can't follow them.

### How it was tested without a phone
- `tools/vrm-test/run.sh a.vrm …` — loads real avatars (VRM 0.x and 1.0), checks every mesh, skin, morph target and texture reads, runs the retargeter and spring bones, and fails on anything non-finite or missing. Passes on the official samples (Seed-san, VRM1 Constraint Twist Sample, UniVRM's Alicia).
- `tools/vrm-test/run.sh --render out/ a.vrm …` — draws the avatar with a small software renderer using the same camera, skinning and morph maths as the stage: at rest, posed (head turned, wink, mouth open, arms relaxed), and after spring bones. **Look at these pictures after any change to the vrm/ files.** They showed: textures and skinning right for both layouts of mesh data, VRM 0.x turned to face the camera, mirror-correct head turn and wink, hair swinging.
- `tools/stream-test/run.sh` — encodes a test clip with ffmpeg, pushes it through `RtmpPublisher` into a listening ffmpeg, and checks what arrived (H.264 + AAC, right length, decodes cleanly). Passes.
- **Not covered by any of that:** SceneKit itself, ARKit, VideoToolbox, the audio engine, Foundation's streams and the screen — everything that only exists on an iPhone.

### Troubleshooting switches (VRM Settings)
Each is a guess that couldn't be checked off-device; the switch flips the guess without a rebuild. Defaults are what the documentation and other SceneKit glTF loaders suggest.
| Switch | What it flips | Default assumption |
|---|---|---|
| Head turns the wrong way | mirrors the head rotation | ARKit's view-space matrix behaves like MediaPipe's face matrix |
| A wink closes the wrong eye | swaps Left/Right blend shapes | ARKit names sides from the person's own point of view |
| Textures look scrambled | flips texture V | SceneKit and glTF both put (0,0) at the picture's top-left |
| Lashes or hair edges show as blocks | blends cut-out materials instead of discarding pixels in a shader modifier | the one-line shader modifier compiles |
| Face explodes when it moves | morph targets as finished shapes instead of differences | SceneKit's "additive" morpher takes differences |

### Needs a look on a device
- VRM: avatar loads and looks right; head/eyes/mouth follow; hair swings; photo; video has picture and voice in step; Go Live reaches a real service (try YouTube `rtmp://a.rtmp.youtube.com/live2` and an `rtmps://` one), survives ~10 minutes, and ends cleanly; battery/heat while live.
- Frame rate with a heavy avatar (the debug line at the bottom of VRM Settings shows vertex / triangle / morph counts).

### Why the rest of VRM stopped here
- **Body and hand tracking:** ARKit face tracking and Apple's Vision body/hand detection can run together, but Vision's results depend on telling it which way up the front camera's picture is, and its "left/right" naming under mirroring — neither can be settled without a phone, and a wrong guess gives arms on the wrong side or no detection at all. Hands are 2-D only in Vision, which isn't enough for finger curl. Worth doing with a tester on hand: the retargeter already takes body and hand points (`TrackingFrame.body` / `.hands`), exactly as on Android.
- **Node constraints** (`VRMC_node_constraint`, twist bones): not implemented on either platform; the Constraint Twist sample shows it as sleeves that don't follow the arms. Ordinary VRoid avatars don't use them.
- **Streaming cost:** each streamed/recorded frame is drawn a second time off screen and copied through memory to the encoder. Fine for a first version; rendering straight into the encoder's pixel buffers (Metal) would be the optimisation.

### Scope
- Everything new in update 23 is iOS-only (`iosMain`, `iosApp`, `tools`). `commonMain` and `androidMain` are as update 22 left them.

---

## 16. Update 24 — the rest of the port (compile-checked and desktop-tested where possible; not yet run on a device)

Recho can't test on an iPhone yet and asked for everything to be finished first, so the test only has to happen once. This update ports every remaining Android-only feature. **A caution that belongs here:** roughly 6,000 lines of iOS code now rest on Apple frameworks that have only ever been compiled against, never run. The compile check and the desktop tests remove whole classes of mistake, and each guess that couldn't be settled has a switch — but a first run on a phone that needs no fixes at all would be surprising. Plan for one round of fixes after the test.

### Fix for Android (and the one Android file touched)
- **VRM mode: the avatar stood in a T-pose, untracked, until Settings was opened.** The tracking pipeline is handed the avatar's data in a `SideEffect` in the page's body. Everything the page draws sits inside a `CompositionLocalProvider` block, which Compose re-runs on its own — so when the avatar file finished loading only that block ran again, the `SideEffect` didn't, and the pipeline kept "no avatar". Opening Settings re-ran the whole page, which is why that "fixed" it. The data is now read in the page's body (`val vrmDataNow = parsedVrmData`), so the page re-runs when it arrives. One line plus a comment in `androidMain/.../ui/VrmModeScreen.kt`; nothing else on Android changed.

### VRM mode on iOS: what was added
Layout and wording follow Android's: bottom bar **mic · photo/video/live · capture · Activity · Settings**, the same four Settings tabs (Tracking, Avatar, Display, Audio & Web) plus a fifth, **Fixes**. Settings are stored in the same `vrm_settings` file under Android's names, so a backup carries them across.

- **Hands, upper body, full body** (`vrm/VisionLift.kt`, `StellarFaceTracker.swift`). Apple's Vision framework looks at the same camera picture ARKit uses and finds body joints and 21 joints per hand. Vision only gives flat (2-D) points; `VisionLift` works out depth from foreshortening — a bone that looks shorter than its real length must be pointing at the camera — and takes the likely direction: arms reach towards the camera, fingers curl towards the palm (which way the palm faces is read from which side the thumb is on). The result goes into the same retargeter as Android's MediaPipe points. Also here: Hand IK, Arms need hands, Head fallback (the body tracker's nose/eyes/ears turn the head while the face is hidden), Fast (30 fps), Smoothing.
  - **Which way up the camera picture is** can't be asked on iOS. `VrmStage.judgeOrientation` finds out by watching Vision's results: a person upside down or on their side, or nobody at all while ARKit can see a face, means the picture is being read the wrong way, and the next way is tried (remembered in `ios_vision_orientation`). Debug info shows which one is in use.
- **Follow my head** (on by default, like Android): the avatar's head sits where yours is in the mirrored camera picture and is as big as yours looks. ARKit gives the head's real distance, so this needs no eye-spacing estimate. With it off: drag to turn/raise, pinch to zoom.
- **Manual eyes**, **Physics**, **Full bright** and, with it off, **lit shading with a Brightness slider** (one light from over the viewer's shoulder that travels with the camera, plus an even fill — the same arrangement as Android's).
- **Avatar parts**: every mesh piece can be hidden; ids match Android's ("node:primitive").
- **Background colour, picture or looping video** (picture/video for supporters). Being part of the 3-D scene, it's in captures too.
- **Frame rate** 30 / 60 / 120, **Tracking preview** (dots for what Vision sees — never the camera picture), **Debug info**.
- **Voice pitch** −8 … +8 semitones, for recordings and streams, adjustable while running.
- **Activity** (supporters): Scene cards (Starting Soon / Be Right Back), Soundboard (add, play, hold-and-drag to reorder / rename / delete) and Effects. All of it appears in photos, recordings and the stream; soundboard sounds are mixed straight into the recording's / stream's sound.
- **Browser overlays**: floating web pages (chat, alerts) you can move, resize and re-address; each can be set to show in captures. Shared with the Camera page.
- **Go Live** gained the optional **Stream link**, which puts Bluesky's Live badge on your profile while streaming (`util/IosStreamBadge.kt` — the same record Android writes; renewed hourly, removed when the stream ends or fails). An "End stream?" confirmation was added.
- Recordings stop by themselves at 10 minutes, like Android.

### Camera page on iOS (replaces Apple's camera sheet)
`ui/CameraModeScreen.ios.kt`, `stream/IosCameraRig.kt`. Stellar's own camera page with Android's bar: mic, photo/video, capture, **Live**, flip. Photos, videos (with mic and VRM's voice pitch), browser windows in captures, and going live — sharing VRM mode's saved stream settings. If this camera can't start, Apple's camera sheet (update 22's) opens instead, so there's always a way to take the picture.

### Review page: crop
`ui/CapturePreviewScreen.ios.kt`, `platform/IosCrop.kt`. The crop menu (Default, 9:16, 2:3, 3:4, 4:5, 1:1 — or the landscape set), drag to position, pinch to zoom, applied to both Save to Device and Create Post, for photos and videos.

### How the pieces are built (for whoever fixes things after the test)
- **Sound** — `stream/IosAudioRig.kt`: one AVAudioEngine wired `mic → pitch → fader ┐ / soundboard players ┴→ mix → (tapped) → silent fader → speaker`. The tap's samples are encoded to AAC (`IosAacEncoder`) for both the stream and recordings. Recordings keep the AAC frames as an `.aac` (ADTS) file and join it to the picture at the end (`IosFrameRecorder.join`: passthrough, re-encode as a fallback). `IosSoundboard` plays a sound aloud and, if a rig is running, into the mix.
- **Recording** — `stream/IosFrameRecorder.kt` takes pixel buffers one at a time from either source. `vrm/VrmRecorder.kt` (stage) and `IosCameraRig` (camera) feed it.
- **Live** — `IosLiveStreamer` now takes a `LiveFeeder` (`VrmLiveFeeder` for the stage, `IosCameraRig` for the camera) instead of knowing about the stage.
- **Things captured over the picture** — `CaptureLayer` (a picture + where it goes). VRM mode's stage layer is drawn twice by Compose: full size (photos) and one-third size (read back ~20 times a second for video); web pages are snapshotted a few times a second. `PixelFrames` draws the layers onto each frame.
- **Swift does as little as possible**: `StellarFaceTracker.swift` runs ARKit and Vision and passes arrays of numbers. Everything that interprets them is Kotlin, where the compile check and the desktop tests can see it.

### How it was tested without a phone
- `tools/ios-typecheck`: 0 errors (and still reports deliberately broken lines).
- `tools/vrm-test/run.sh --lift a.vrm …` (new, `LiftTest.kt`): builds people and hands in 3-D, flattens them the way a camera sees them, lifts them back with `VisionLift` and compares — arms reaching at the camera, fingers half curled on a right and a left hand, hands told apart by position, an upside-down picture noticed — then runs each result through the retargeter on real avatars. Passes. **This proves the maths is self-consistent, not that Vision's real output matches what the test assumes** (see Fixes).
- `tools/vrm-test` and `tools/stream-test` as before: pass.
- Not covered: everything that only exists on an iPhone — SceneKit, ARKit, Vision, the audio engine, the camera, VideoToolbox, WKWebView snapshots, the photo picker.

### Fixes (VRM Settings › Fixes)
Each is a guess that couldn't be checked off-device; the switch flips it without a rebuild.
| Switch | What it flips | Default assumption |
|---|---|---|
| Head turns the wrong way | mirrors the head rotation | ARKit's view-space matrix behaves like MediaPipe's face matrix |
| A wink closes the wrong eye | swaps Left/Right blend shapes | ARKit names sides from the person's own point of view |
| Avatar slides the opposite way to me | Follow my head's left/right | +x in ARKit's view space is the picture's right, unmirrored |
| Arms cross over the chest | Vision's "left"/"right" joint names | Vision names joints from the person's own point of view |
| The wrong arm moves | reads the camera picture mirrored (and swaps the names with it) | ARKit's camera picture is not mirrored |
| Textures look scrambled | flips texture V | SceneKit and glTF both put (0,0) at the picture's top-left |
| Lashes or hair edges show as blocks | blends cut-outs instead of discarding pixels in a shader modifier | the one-line shader modifier compiles |
| Face explodes when it moves | morph targets as finished shapes instead of differences | SceneKit's "additive" morpher takes differences |

### The device test (once, in this order — earlier items block later ones)
1. **The app opens** and the rest of Stellar works as in update 21 (nothing outside VRM/Camera/review changed, but the audio and camera frameworks are linked now).
2. **VRM mode, face only** (turn Hands off): avatar loads and looks right; head, eyes, mouth follow; hair swings; Follow my head keeps the avatar's face on yours. Try the first three Fixes if not.
3. **Photo, video (check the voice is there and in step), Voice pitch.**
4. **Hands on**, then **Upper body**: turn on Tracking preview and Debug info first — dots should appear on your shoulders/hands, and "camera picture read as" should settle within a few seconds. Then the arm Fixes if needed. Expect this to be rougher than Android: depth is estimated, not measured.
5. **Avatar tab:** hide a part; Full bright on/off; Brightness.
6. **Display tab:** background colour; (supporter) picture and video backgrounds, and whether they show in a recording.
7. **Activity** (supporter): a scene card and an effect appear in a recording; a soundboard sound is heard in a recording.
8. **Browser overlay:** add a page, move/resize it, set "In captures", record.
9. **Go Live** to a real service from VRM mode, with a Stream link — the Live badge should appear on the profile and go when the stream ends.
10. **Camera page:** preview, flip, photo, video with sound, Live.
11. **Review page:** each crop, on a photo and on a video; Save to Device; Create Post.
12. **Heat and battery** over ~10 minutes of VRM with hands on, and while live.

### Known limits (deliberate)
- **Music visualizer:** not possible on iOS.
- **Buttons over the avatar/camera are tinted, not live-blurred** (what's behind them isn't Compose's to blur) — the look of Android's Performance mode, so there's no Performance switch on iOS.
- **No volume-key shutter** (iOS doesn't hand those keys to apps without private tricks).
- **Body/hand depth is estimated.** With a tester, the TrueDepth camera's real depth map (`ARFrame.capturedDepthData`) could replace the foreshortening guess for a clear improvement.
- **Camera page captures** are the camera's whole picture, unmirrored; the screen shows a centre crop of it, mirrored for the selfie camera.
- **Streaming/recording VRM** still draws each frame a second time off screen (section 15).
- **Background video in VRM recordings** relies on SceneKit showing an AVPlayer in an off-screen render; if recordings show the colour instead of the video, that's why.

### Scope
- New/changed in update 24: `iosMain` (vrm/, stream/, ui/, util/IosStreamBadge.kt, platform/IosCrop.kt, platform/IosBridges.kt, ui/compat/IosPictures.kt, ui/compat/IosNativePickers.kt), `iosApp/iosApp/StellarFaceTracker.swift`, `iosApp/iosApp/Info.plist` (camera/microphone wording), `tools/vrm-test`, and the one Android fix above. `commonMain` is untouched.

### Open question for Recho: the lexicon name
Stellar's own records are named `com.rechoraccoon.stellar.profile` and `com.rechoraccoon.stellar.blog`. A lexicon name is a domain name written backwards, so these say "defined by whoever owns **rechoraccoon.com**" — which Recho doesn't. It works today (a PDS stores records under any well-formed name), but the convention is to use a domain you control: it's what lets others look up the schema, and it's what stops someone who registers rechoraccoon.com from having the better claim to the name. Options: register rechoraccoon.com (nothing in the app changes), or rename to a domain Recho does own — which means writing new records under the new name and still reading the old ones for existing users. Not changed in this update; Recho's call.

---

## 17. Update 25 — the Note widget chooses its note on the widget (not compiled on either platform's own toolchain; not yet run)

Recho asked for a way to pick which note the Note widget shows: placed, it starts as a list of all notes; tapping one turns the widget into that note.

### Android (`androidMain/.../widget/StellarWidgets.kt`, `res/layout/widget_stellar_list.xml`, new `res/layout/widget_stellar_row_pick.xml`, `res/values/strings.xml`)
- **Each Note widget remembers its own note** (`"note_id:<widget id>"` in the `stellar_widgets` preferences), so several widgets can show different notes.
- **No note chosen yet → "Choose a Note"**: every note, newest first (title, and its first line underneath). A tap is a broadcast to `NoteWidgetProvider` (`ACTION_PICK_NOTE`), which saves the choice and redraws — nothing opens.
- **A note is showing → a small "Change" chip** in the title row (`widget_switch`) brings the list back (`ACTION_CHOOSE_NOTE`). Tapping the note itself still opens it in Stellar.
- A chosen note that's later deleted sends that widget back to the list. Removing a widget forgets its choice (`onDeleted`).
- **Widgets placed before this update keep what they showed** (`syncNoteChoices`): the first time the widget code runs after an app *update* (not a fresh install), each existing Note widget is given the note it was showing — the one sent from the app, or the newest.
- **"Widget" on a note inside the app still works**: it sends that note to every Note widget; switching it off (or deleting the note) sends widgets showing it back to their list. The chip only knows about the last note sent from the app — it doesn't light up for a note chosen on a widget.
- Not compiled here (there's no Android compiler in these sessions): read by eye, brackets and XML checked.

### iOS (`iosApp/StellarWidgets/StellarWidgets.swift`, `IosWidgetBridge` in `platform/IosBridges.kt`)
- **iOS 17 and later** get a new Note widget (`NoteChoiceWidget`, kind `StellarNoteChoice`) that behaves like Android's: a list of notes, tap one and the widget becomes it, "Change" brings the list back. The taps are App Intents (`PickNoteIntent`), which is what lets a widget do something without opening the app.
- **One difference iOS forces:** a widget can only remember something *of its own* through its Edit Widget screen. So a note tapped in the list is shared by every Note widget that hasn't been given its own; for several widgets showing different notes, touch and hold one → Edit Widget → Note (`ChooseNoteIntent`). A widget given its own note that way has no "Change" chip — it's changed in Edit Widget.
- **iOS 16** keeps the old widget (one note: the one sent from the app, or the newest). On iOS 17+ the old one is kept out of the widget gallery by giving it no sizes (`legacyNoteFamilies`).
- The app now publishes every note (up to 60, newest first, bodies cut to 4,000 characters) under `"notes"`, and the tapped choice lives under `"note_pick"` in the app group's shared defaults. The widget extension writes `"note_pick"` itself — the first thing it has ever written.
- **Never compiled.** The App Intents code is the likeliest thing in this update to fail CI. If the widget-bundle line `if #available(iOS 17.0, *) { NoteChoiceWidget() }` is what it objects to, that's the builder not accepting a condition on this Xcode — say so and it can be restructured.

### For the device test
- Android: place a Note widget → list → tap a note → it shows; Change → list; two widgets with different notes; delete a shown note; "Widget" chip in a note.
- iOS 17+: the same, plus Edit Widget → Note. Check the old "Note" widget isn't offered twice in the gallery.

---

## 18. Update 26 — collapsible Settings categories, and "Scrobble Music to Rocksky" (shared code passes the local iOS check; the Android-only code has not been compiled; not yet run)

### Collapsible Settings categories
- Every Settings category (Supporter Settings, Customize Hub, UI Customization, App Functionality, Integrations, Media Tagging, Data and Privacy, Dev Tools) now has the arrow the Customize Hub had. `SectionHeader` is replaced by `CollapsibleSection(title, tint, supporter) { … }` in `commonMain/ui/SettingsPage.kt`.
- A category's bottom gap is part of its body, so a closed category has no gap under it.
- Which categories are closed is remembered in `UiToggles.collapsedSettingsSections` (key `collapsed_settings_sections`); the old Customize Hub setting is carried over.

### Scrobble Music to Rocksky (supporter feature, Android only)
- **Where:** Settings › Integrations, above the e621 bubble: `RockskyScrobbleBubble` with an "Apps" button to the left of a `SupporterSwitch`. "Apps" opens `ScrobbleAppsDialog` ("Which apps should Stellar Scrobble?"): Spotify, YouTube Music, YouTube, Apple Music, SoundCloud, then the rest of Rocksky's list (Tidal, Deezer, Shazam, Pixel Now Playing, Pixel Ambient Services, Ambient Music Mod, Audile), then any other app seen playing music. All off by default.
- **Turning it on:** asks for the notification permission (Android 13+), then opens Stellar's own "notification access" page (Android 11+; the general list before that). The row's sub-text says what is still missing, and tapping it reopens the page.
- **How it listens** (`androidMain/scrobble/`, adapted from Rocksky's `rocksky-scrobbler` module, MPL-2.0 header on each file): `StellarScrobbleListener` (a `NotificationListenerService`) watches the media sessions of the chosen apps, counts real listening time, and queues a listen after half the song or four minutes (songs under 30 seconds don't count). It shows one silent ongoing notification, "Stellar scrobbling". `ScrobbleUploadJob` uploads the queue when there is a connection, and survives reboots (`ScrobbleBootReceiver`).
- **What it writes** (`commonMain/util/RockskyScrobbler.kt`): Rocksky's own app sends listens to Rocksky's server with a Rocksky login, and the server writes the records. Stellar has no Rocksky login, so it writes the same four records straight to the account's PDS: `app.rocksky.song`, `app.rocksky.artist`, `app.rocksky.album` (once each per account) and `app.rocksky.scrobble`, filled in from Rocksky's public `matchSong` lookup. Rocksky's indexer picks up `app.rocksky.scrobble` records from any repo.
- iOS: the row is grayed out with the "Android only" tag (`PlatformFeature.MUSIC_SCROBBLING`); iOS gives apps no way to see what other apps are playing.
- Manifest: permissions `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`; the listener service, the upload job, the boot receiver.
- Supporter benefits list: "Scrobble music to Rocksky" under "Pin DMs".

### Known differences and risks
- Because the records skip Rocksky's server, its duplicate check is skipped too: a song Rocksky already wrote a `song`/`album`/`artist` record for (through Rocksky's app or another scrobbler) gets a second one the first time Stellar scrobbles it. Running both Stellar's and another scrobbler at once would double the scrobbles.
- On Android 13+, an app installed from outside the Play Store has the notification-access switch grayed out until "Allow restricted settings" is chosen in the app's App info (three-dot menu). The row's sub-text explains this.
- Google Play requires a declaration for the `specialUse` foreground service type.

### To check on a phone
1. Android build compiles (the `scrobble/` files, `LocalPlatform.android.kt`, the manifest).
2. Toggle on → permission prompt → notification-access page for Stellar → back in Stellar the row reads "On".
3. Apps popup: all off; turn on one player, play a song past half-way; the scrobble appears on the Rocksky profile and in Stellar's Rocksky views.
4. The "Stellar scrobbling" notification is silent and goes away when the toggle is turned off.
5. Reboot, play a song: still scrobbles.
6. Settings categories: each collapses, stays collapsed after reopening the app, and closed ones sit close together.

---

## 19. Update 27 — scrobbling the official way, live "Listening to", history import, deleting a listen, Note widget checklists (shared code passes the local iOS check; the Android-only code has not been compiled; not yet run)

### How Stellar writes to Rocksky (replaces the description in section 18)
- Rocksky's Android app signs in to **Rocksky's server** with Bluesky OAuth, and the server writes the records. That sign-in belongs to Rocksky's own app and can't be reused by another app.
- Rocksky's **official SDK and `rocksky import` command** (`sdk/typescript/src/agent.ts`, `apps/cli/src/cmd/import.ts`) are the road meant for everyone else: sign in with the Bluesky account and write straight to the PDS. Stellar follows that, using the Bluesky session it already has: artist → album → song (once each) → scrobble.
- **No duplicates:** like the SDK's "dedup index", Stellar reads what the repo already holds (`RockskyScrobbler.syncIndex`, `com.atproto.repo.listRecords`, 100 per request; afterwards only what's new) into a `known` table in `stellar_scrobbles.db`, keyed the way Rocksky keys things (`sdk/typescript/src/hash.ts`). This replaces the old "remember what Stellar itself wrote" memory, so records written by Rocksky or another scrobbler are no longer written again.

### Live "Listening to"
- **Writing:** the moment a song has played for 1.5 s in a chosen app, `StellarScrobbleListener.publishStatus` writes `app.rocksky.actor.status` (rkey `self`) — the record the SDK's `setNowPlaying` and Rocksky's server write — with `startedAt` and `expiresAt` (end of song + 30 s). Ten seconds of nothing playing deletes it. Switching scrobbling off deletes it too.
- **Reading:** `RockskyRepository.getNowPlaying` now reads that record from the profile owner's PDS first. The profile's watcher (`MainViewModel`, `nowPlayingJob`) re-checks every 15 s while a status is showing; the five-minute guess from the latest scrobble is still there and is used only when there is no official status.

### Scrobble Settings popup
- The button is "Settings"; the popup is "Scrobble Settings", in the Blocked Accounts popup's design (solid panel, X button).
- Top row: "Scrobble after [50]% or [4:00] of the track" — both boxes editable; whichever comes first; Rocksky's defaults. Stored as `percent` / `seconds` in the `stellar_scrobbler` prefs.
- Then a divider, "Which apps do you want Stellar to scrobble?", and the app list.
- "YouTube" and "YouTube Music" also cover ReVanced, ReVanced Extended, Morphe, anddea and old Vanced builds (`RockskyScrobbler.APP_ALIASES`).

### Import YouTube/Spotify music history (supporter feature, Android only)
- **Where:** second part of the scrobbling bubble (shown while scrobbling is on or an import exists): "Import YouTube/Spotify music history to Rocksky" + "Import"; one row per file with "(done/total)" and Cancel (tap twice: "Really?"; a finished file shows "Clear"); "Work in Background" at the bottom.
- **What it reads** (`androidMain/scrobble/ScrobbleImporter.kt`): Spotify "Extended streaming history" and "Account data" history, and Google Takeout's `watch-history.json` (YouTube Music entries only) — the .zip or a single .json. Plays under 30 s and podcasts are skipped. Takeout must be requested with History as **JSON** (the default is HTML; the row says so if it finds HTML).
- **How it survives interruption:** every listen in the file is copied into the database when the file is picked (`imports`, `import_plays`), and each is marked as it's sent. A restart carries on with the first unsent one; more files queue behind. `MainActivity.onCreate` and the boot receiver call `ScrobbleImporter.resume`.
- **Pace:** 1,400 PDS writes an hour (the SDK's 1,500 ceiling, less room for live scrobbles). Roughly 350–1,400 listens an hour depending on how many new artists/albums/songs they bring.
- **Work in Background:** `ScrobbleImportService` (foreground, `specialUse`) with a silent progress notification and a wake lock renewed every 30 s. Turning it on asks for the notification permission and then Android's battery-optimisation exemption.
- Titles: YouTube titles are cleaned ("(Official Video)" etc.) and matched with Rocksky's `matchSong`; a match is only used when its title plainly is the same song.

### Deleting a listen
- Press and hold a row in **your own** Music History → the app's `ConfirmPopup` → `RockskyScrobbler.deleteScrobble` deletes the `app.rocksky.scrobble` record from the PDS. Rocksky removes it from its side when it sees the delete.

### Note widget (Android)
- A tapped checklist line ticks/unticks itself and saves the change to the note (`StellarWidgets.toggleNoteLine` → `LocalData.saveNote`).
- All Note-widget rows now report to `NoteWidgetProvider`, which picks a note, ticks a line, or opens the note. The list-of-notes and the note view use separate list adapters, so the widget can't be left half in "Choose a Note" mode after a tap.
- iOS Note widget: checklist ticking is not done there yet.

### To check on a phone
1. Android build compiles (`scrobble/*.kt`, `StellarWidgets.kt`, `LocalPlatform.android.kt`, manifest).
2. Play a song in a chosen app → within a few seconds "Listening to" shows on your Stellar profile and on Rocksky; pause → it clears.
3. Scrobble Settings: change 50% / 4:00, reopen, values kept; a short threshold scrobbles sooner.
4. ReVanced YouTube Music with "YouTube Music" on → scrobbles.
5. Import a small Spotify .json first: count rises, close Stellar mid-way, reopen → carries on; import the same file again → finishes quickly with no duplicates on Rocksky.
6. Work in Background: prompts appear, notification shows progress with Stellar closed.
7. Hold a listen in your Music History → Delete → gone in Stellar and on Rocksky.
8. Note widget: tap a note → title becomes the note's; tap a checklist line → ticks, and the note in Stellar shows it ticked; tap another line → opens the note.

---

## 20. Update 28 — Tagged search fixes and widget headers (shared code passes the local iOS check; Android-only code not compiled; not yet run)

- **Keyboard closing on the Tagged tab (iOS):** the search field was rebuilt inside a different surface each time the suggestions opened or closed, so it was a new field every time and lost focus. The field now stays put and only the background behind it changes (`SearchOverlay.kt`).
- **Tagged results jumping to the top after viewing a post:** opening a post removes the Search page, which lost the list's position and sub-tab. Both are now kept in `TaggedSearchPlace` (`SearchOverlay.kt`) and restored; a new search, another sub-tab, or coming to the tab from another one starts at the top.
- **Tags with symbols:** `TagAliases.toTagGroups` stripped everything but letters, digits and "_", so "male/male" became "malemale". Words are now kept as typed. Android's `LIKE` search escapes "_" and "%" so they mean themselves (`TagDatabase.searchPostUris`); iOS matching ignores case.
- **DM / Upcoming Events widget headers vanishing (Android):** after an app update the launcher keeps each widget's views from the old layout and applies the new build's changes to them by view-id number — and those numbers are reassigned every build. Update 25 added the "Change" chip, so "hide the chip" landed on the old title. `StellarWidgets.update` now shows an empty layout (`widget_stellar_reset.xml`) once per installed version, which makes the launcher rebuild the widget from the current layout. This is probably also why "Choose a Note" stayed after picking a note.

### To check
1. iOS: type in Tagged search; the keyboard stays up as suggestions come and go.
2. Scroll down Tagged results, open a post, come back: same spot, same sub-tab.
3. Search "male/male" and a "name_(artist)" tag: results appear.
4. Android: after installing, all three widgets show their titles; they still do after the next update.

---

## 21. Update 29 — iOS microphone crash and the Camera / VRM bubbles (passes the local iOS check; not yet run)

- **Crash in Camera / VRM mode once the microphone is allowed** (`iosMain/stream/IosAudioRig.kt`). The cause was not reproduced. Apple's audio engine stops the whole app on a wiring it doesn't accept (it can't be caught from Kotlin), and the microphone was the only part wired in the hardware's own format, straight into the pitch effect. It's now wired microphone → fader (a mixer, which accepts any format) → pitch (in a fixed stereo 44.1 kHz format) → mix, and only when the permission is granted and the input's format is valid and agrees with the hardware; otherwise the recording goes ahead without the mic. **If it still crashes**, the crash log is needed: iPhone Settings › Privacy & Security › Analytics & Improvements › Analytics Data › the newest "Stellar-…" entry.
- **Camera / VRM on iOS** (`iosMain/ui/CameraScreens.ios.kt`): no outline around the Dynamic Island any more. Two separate bubbles, "Camera" left of the island and "VRM" right of it, shown only on the Hub and the posting page (`showButtons`, a new parameter of `CameraNotchButton`; Android ignores it and keeps its ring). On older-notch iPhones they sit in the corners beside the notch; on an iPhone SE, side by side at the top centre.

### To check
1. Record and go live in Camera mode and VRM mode with the mic allowed: no crash, and your voice is in the result; pitch slider still works.
2. Hub and posting page: the two bubbles are level with the island and open Camera / VRM. Nowhere else (profiles, feed, DMs, Search).

---

## 22. Update 30 — minor holidays in Upcoming Events, and the "Listening to" status (shared code passes the local iOS check; Android-only code not compiled; not yet run)

- **Upcoming Events** (the Hub's widget, Android's and iOS's home-screen widgets) now lists the minor holidays as well as the major ones, each unless switched off in Supporter Settings (`LocalData.upcomingAgenda`, `StellarWidgets.upcomingEvents`).
- **"Listening to" not showing until the song scrobbled.** The cause was not found by reading the code: the status record is written when a song starts and read back from the PDS, and neither step visibly fails. Three changes:
  1. **Your own profile no longer depends on any server.** The scrobbler records what's playing on the phone the moment it starts (`RockskyScrobbler.localStatus`), and `RockskyRepository.getNowPlaying` uses that first.
  2. **A failed write is no longer silent.** `setNowPlaying` returns the server's reason; it's shown under the "Scrobble Music to Rocksky" switch in Settings (`statusError`).
  3. **The record is put back if it disappears.** Every 20 s while a song plays the scrobbler checks the record is still in the repo (`statusPresent`) and writes it again if not — Rocksky's own server deletes this record when a player connected there stops.
- The profile's watcher now re-checks every 15 s whether or not a status is showing.

### To check
1. Play a song, open your own profile within a few seconds: "Listening to" shows before the scrobble.
2. If other people still don't see it, look at the text under the switch in Settings › Integrations for the server's message.
3. Hub and home-screen Upcoming Events show minor holidays; turning them off in Supporter Settings removes them.
- **Stellar Supporters row and feed:** `rechoraccoonclips.bsky.social` is now left out, alongside Recho's and Stellar's own accounts (`StellarOfficial.RECHO_CLIPS_HANDLE`, `BlueskyRepository.isHiddenSupporter`). It stays on the list, so its supporter features still work.

---

## 23. Update 31 — Create Account fixes (shared code passes the local iOS check; Android-only code not compiled; not yet run)

- **Dragging in Bluesky's human check kept letting go.** The check is a web page inside the sign-in page, which scrolls; once a drag started moving, the page around it took the touch over as a scroll. `BrowserState.holdsTouches` (set for the check only) keeps a touch with the web page until the finger lifts: Android asks its parents not to intercept (`Browser.android.kt`), iOS gives the web view its touches directly (`UIKitInteropInteractionMode.NonCooperative`, `Browser.ios.kt`).
- **"Start again" after entering the email code, yet signed in.** The code had been accepted; the page stayed open (in Dev Tools' preview nothing closed it), and a second press of Verify found nothing left to confirm. Now the page stays busy ("Signing you in…") after a successful Verify or Skip, and the preview closes itself.
- **Creating an account while signed in to another.** The new account used to replace the one in use without keeping it. It's now added as an additional account (Settings › Integrations) and switched to — the same path as "Add" then "Switch To", including the app restart — and the account that was in use stays signed in (`MainViewModel.connectLoginFlow`, `flow.finish`).

### To check
1. Create Account → the human check: drag its piece in one smooth motion.
2. Enter the email code once: "Signing you in…", then the app opens on the new account.
3. Do it while signed in: afterwards both accounts are listed under Settings › Integrations, with the new one active.


---

## 24. Update 32 — everything free, polish and size (shared + iOS Kotlin pass the local iOS check; Android-only code not compiled; not yet run)

### Supporter features are free (`util/FeatureFlags.kt`)
- `ALL_FEATURES_FREE = true`: `Supporter.active` is true for everyone. The pink profile **badge** (and its confetti) still reads `StellarSupporters.isSupporter`. Background notifications/widgets (Android worker, iOS refresh, `StellarWidgets.isSupporter`) no longer check the list. Flip the flag to bring every gate back.
- `SUPPORT_POPUP_ENABLED = false`: the 10th/25th/50th-open popup is off (code kept; Dev Tools preview still works).
- `SUPPORTERS_FEED_ENABLED = false`: the Hub's "Stellar Supporters" row is taken out of layouts and Add → Default, and the welcome popup no longer offers the Supporters feed (code kept).
- Settings: "Support Stellar" tab → **"Support Recho"**, page is the logo, "Help Recho afford food by donating with any of the links below." and the three links. The Supporter Settings bubbles (notifications, holidays, browser search engine) moved to the end of **App Functionality**; the category is gone while the flag is on.
- Profile effects: **"None"** is first and the default for everyone; confetti is just one optional effect (no longer tied to the supporter badge). Nobody's choice is reset. Records now carry `styleVersion: 2`; an older record's `"confetti"` (the old default, always written) reads as "none", other effects are kept. Everyone's style records are looked up now (3 at a time, once per session per account).
- README's supporter section replaced with a short "Support Recho" one.

### Fixes
- **Hub Mutuals row** showed every open DM. `DmConversation.isMutual` (from the follows' `viewer.followedBy`) — the row shows only mutuals. `getMutuals` now pages only the follows (followers only if the server leaves viewer state out). The on-disk DM cache no longer counts as "loaded", so the list really refreshes each session (`dmFreshLoaded`).
- **Liking comment replies**: `updateComment` only looked at top-level comments, and the thread page drew a stale copy. Both fixed (recursive update; thread pages re-read the live list).
- **Tagger indicator**: model loading is its own step (`TaggingService.warmUp`), so it goes "Activating tagger…" → "Tagging 1 post" instead of vanishing.
- **Camera page crash (iOS)**: `setVideoMirrored` while `automaticallyAdjustsVideoMirroring` was on throws an Objective-C exception the moment the page opens. Now turned off first (`IosCameraRig.configure`). **VRM crash (iOS)**: not reproduced. Likely suspect is memory (old avatar kept while the new one was built). `VrmStage.load` now unloads first, and a load-crash guard (`ios_avatar_loading`) stops a crashing avatar from auto-loading next time. If VRM mode still closes the app, the crash log is still needed.
- **Uploads**: pictures are decoded at reduced size (no more OOM on big photos), turned upright from EXIF, EXIF/GPS stripped losslessly (`util/JpegMeta.kt`), and stepped down until they fit; transparent PNGs stay PNG. **5+ pictures failed** because `app.bsky.embed.gallery#image` requires `aspectRatio` — always sent now. Pictures upload 3 at a time (decoding one at a time). **Videos**: over 1080p or 90 MB are re-encoded before upload (Android `util/VideoCompressor.kt` with Media3 Transformer; iOS `platform/IosVideoCompressor.kt` with AVAssetExportSession). iOS video upload bodies now carry their real length.
- **Transparent pictures** in posts showed black: Bluesky's view hands out `@jpeg` CDN links. PNG/WebP blobs (from the post record) are now asked for as `@png` (`BskyRecord.alphaImageCids`).

### New
- **Post tags editing** (Tags page in comments): hold = delete (confirm), double-tap = rename, "Add Tag" at the bottom (spaces → "_"). `TaggingService.editPostTag` (Android SQLite, iOS JSON store). Bluesky posts only, and only while "Tag Post When Liked" is on. `ConfirmPopup` gained an optional text field.
- **Launchpad reordering**: hold a button and drag; hold at the pad's edge to turn the page; a page with 6 shows a third row while dragging (max 9 per page). Saved per account in `hub_layout` (`HubLayout.launchpad`, so backups carry it).
- **Quote reposts** show the quoter's text over the thumbnail in Explore and on profile grids (`SentByTileOverlay`).
- **VRM avatar row** (both platforms): VRM Settings › Avatar starts with thumbnail buttons for every added avatar + a round "+". Tap loads (one at a time), hold deletes (confirm), double-tap picks a new picture (cropped square). Thumbnails come from the .vrm itself (`VrmLibrary.embeddedThumbnail`, VRM 0.x and 1.0 — checked against Seed-san and Alicia), else the file's name. Stored in `vrm_settings` (`vrm_library`). Android keeps content:// links; iOS keeps copies. Existing avatars migrate in as the first entry. Avatar parts open → the popup can grow from under the notch to over the gesture bar (`rememberVrmPopupMaxBody`).
- **Hub blogs**: the card uses the reader's own page background (`blogPageBrush`) and the author's profile color, with the author bubble above it like Reviews.
- **Blogs render Markdown** like Notes (bold, italic, checklists, lists, headings, quotes) via `MarkdownView`; the editor continues lists on Return; Hub card previews strip markers.
- **Haptics switch** (App Functionality, on by default): Compose haptics go through `SwitchableHaptics` in AppRoot; `PlatformView`, `vibrateOneShot`, `AppPlatform.haptic` and the two direct Android calls check it.
- **Double-tap-and-drag zoom** on media posts (drag down = in, up = out, around the tapped point). A double tap that isn't dragged still likes (it now fires on release).

### Speed, battery, size
- Android tagger: only the fast CPU cores (big.LITTLE), ORT's own pool at 1 thread when XNNPACK runs (ORT's documented setup), inter-op 1, faster pixel copy.
- Background: notification checks read one page of chats (was three); Android's alarm goes to 15 minutes in Battery Saver; the profile "Listening to" poll pauses while the app is in the background.
- **APK size**: `abiFilters` arm64-v8a + armeabi-v7a (x86/x86_64 dropped — emulators only); **R8 on** for release with conservative rules (`androidApp/proguard-rules.pro`: no renaming, all `com.mediaviewer.**` and native-backed libraries kept, `-ignorewarnings`, full mode off). If a release build misbehaves, set `isMinifyEnabled = false`. MediaPipe's three tracker models (~21 MB) are **optional**: the first time VRM mode opens it asks "Download trackers?" (Download / Not now). "Not now" closes VRM mode, and it asks again every time VRM mode is opened until they're downloaded; a failed download is retried by tapping the note at the top (`trackerQuestion` / `trackerAttempt` in `VrmModeScreen.kt`, files via `util/TrackingModels.kt`, the same float16 v1 files; falls back to "latest"; bundled assets still win if present). **The three `.task` files must be deleted from the repo's `androidApp/src/main/assets/`** for the APK to shrink.

### Follow-ups in the same update
- Support Recho: "…by donating **through** any of the links below.", logo much bigger.
- Tracker download runs in the app's own scope (`TrackingModels.startDownload`), so leaving VRM mode doesn't stop it; its progress is `util/TrackerDownload` (common) and shows as a status bubble under the author row in Timeline mode ("Downloading VRM trackers… n%"), next to the tagging one. Reopening VRM mode mid-download doesn't ask again.
- Tags page: a single tap opens Search › Tagged searching that tag (`MainViewModel.searchTagInTagged`; e621 posts still search e621). Single taps wait out the double-tap window, so double-tap-to-rename is unaffected.

### To check
1. Android build compiles: `VrmModeScreen.kt`, `MediaBridge.android.kt`, `VideoCompressor.kt`, `TrackingModels.kt`, the landmarker helpers, `ImageTagger.kt`, R8 (missing-rule errors would show here).
2. Release APK size before/after; VRM mode asks before downloading the trackers; after "Download" it shows "Downloading trackers… n%" once, then tracks.
3. Post 5+ photos; post a 4K video; post a transparent PNG and see it isn't black.
4. iOS: Camera page opens; VRM opens; a second avatar can be added and switched.
5. Hub Mutuals shows only mutuals; Launchpad drag between pages; tag hold/double-tap/Add.


---

## 25. Update 33 — bug fixes, pinch tip, Music History covers/counter (shared + iOS Kotlin pass the local iOS check; Android-only code, Swift and Objective-C not compiled; not yet run)

### Fixes
- **Profile customization not seen by others** (`ProfileStyles`, `BlueskyRepository.getProfileStyle`). The records were confirmed present on the PDS; the cause was every feed author being looked up while scrolling, three at a time in arrival order, so an opened profile's lookup waited behind hundreds of others (and that many reads risks PDS rate limits). **Rule now (Recho's requirement — keep it):** a style record is read ONLY when a profile is opened (`ProfileStyles.refresh`, from `ProfileOverlay`), at most once per account per session, and reused for later opens; a restart reads it again on the next open. `ProfileStyles.of()` never touches the network. A failed read is retried only on the next open. Other people's styles are also saved on the phone (`profile_style_others`, up to 400) and shown until the session's read lands. The square icon is profile-page only — feed author bubbles are always round. A failed direct PDS read falls back once to the entryway's `listRecords`.
- **Birthday field cursor** (`LoginScreen.kt`): only digits are stored; slashes are drawn by `BirthdaySlashes` (VisualTransformation with an offset mapping), so the cursor stays at the end.
- **iOS Camera-page record crash** (not reproduced; best guess): the capture session no longer re-configures the app's audio session (`automaticallyConfiguresApplicationAudioSession = false`, `IosCameraRig.configure`), and every AVAudioEngine `connect` and the start now run inside Objective-C `@try` (`iosApp/iosApp/StellarTry.{h,m}` → `StellarAudioGuard.swift` → `IosAudioGuard` in `IosBridges.kt`). A refused wiring records without the mic instead of crashing. New: bridging header `iosApp/Stellar-Bridging-Header.h` (`SWIFT_OBJC_BRIDGING_HEADER` in `project.yml`). If CI rejects the Swift/ObjC, that's the place. If it still crashes, the crash log is needed.
- **Follow button on your own post** is greyed and inert (`FollowButton(clickable = false)`, `AuthorRow(ownPost)`).
- **Widgets free** on both platforms: iOS widget extension no longer reads the `supporter` flag (always true); Android `StellarWidgets.isSupporter` returns true regardless of `ALL_FEATURES_FREE`; DMs widget no longer gated on `Supporter.active`; events/notes are handed to the widgets once at launch.
- **Backlog button hidden on your own review** (`TitleDetailOverlay`, review bar).
- **DM press-and-hold on a shared post / profile card** now opens the reaction picker (cards use `combinedClickable` with the bubble's long-press).

### New
- ~~Pinch tip popup~~ — replaced in update 34 by Tips (section 26).
- **Scrobble covers** (`RockskyScrobbler.upload`): matcher retried with the YouTube-tidied title/artist; cover order is matcher art → the player's own cover link (`artUri` from MediaMetadata) → the player's cover picture uploaded as a blob (`albumArt` blob + `albumArtUrl` = PDS getBlob link). Android listener saves the picture to `files/scrobble_art/` for the upload job, deleted after sending.
- **Manual cover**: own Music History › double-tap a cover → pick a picture → square 600 px JPEG uploaded once; every `app.rocksky.song` / `app.rocksky.scrobble` record of that song (and its album record if it has no real cover) is rewritten (`RockskyScrobbler.setSongCover`). The cover is also remembered on the phone (`rocksky_covers` prefs) and shown at once, since Rocksky's own server may keep its old cover.
- **Music History repeats**: consecutive plays of the same song are one row, with "x2", "x26"… top right and the time bottom right.


---

## 26. Update 34 — first-time Tips (shared code passes the local iOS check; Android-only edit is one modifier; not yet run)

Recho's spec: not popups — the screen dims, the parts being talked about stay lit, plain text sits over the page (not always centred), sometimes a gesture animation, and "Tap to continue." Lines in the profile color run from the text to what it's about, only horizontal/vertical with sharp turns, ending in a slightly bigger dot. Several pieces of text per screen are fine. Every walkthrough shows once, to **everyone** who hasn't seen it (no skipping for existing users). Dev Tools › **Reset Tips** brings them all back. Explain what helps people use the app; leave small obvious buttons alone.

### Engine — `commonMain/ui/Tips.kt`
- `Modifier.tipAnchor("id")` registers an element's on-screen bounds (`TipAnchors`; only published to Compose state while a tour is up). `"base@l,t,r,b"` is a fractional part of an anchor (used for the capture bar's fixed-size buttons).
- `TipTour` → `TipStep`s → `TipNote`s (text, optional title, `**bold accent**`, place `Screen` / `Above(anchor)` / `Below(anchor)` with `pin` 0/0.5/1 = left/centre/right alignment and automatic flip when there's no room) + `TipAnimSpec`s (`PINCH_EXPLORE` big square → 2×2 grid, `DOUBLE_TAP_LIKE`, `HOLD_WHEEL`, `ZOOM`, `THREE_FINGERS`, `SWIPE_SIDEWAYS/VERTICAL`, `TAP`, `DRAG_HOLD`, all Canvas-drawn).
- `TipOverlay` (last in AppRoot's root Box, z 30): dim 78% with rounded cut-outs (`BlendMode.DstOut`, cross-faded between steps) and a thin ring; lines routed by `elbowPath` (straight if the target is under/over the text, else down-across-down; beside → across, then up/down) and drawn in over ~0.5 s; taps in the first 0.45 s of a step are ignored; Back = next. A note whose anchors aren't on screen is left out; a step with nothing left to show is skipped.
- `Tips`: seen set in prefs `stellar_tips`; `request(id)` (ignored while `blocked` — welcome/tutorial/support popups, loading transition, signed out); `reset()`.

### Tours — `commonMain/ui/TipTours.kt` (all wording lives here)
Hub (welcome, feeds, Timeline/Explore, Launchpad, + and Settings) · Timeline (swipes, profile/follow/text bubble, interaction bar: like/save/send/more, gesture grid, pinch → Explore animation) · Explore (feeds tabs, content-type tabs, refresh/layout) · Profile (tabs, Add To) · Search (tabs, Tagged) · Comments · Compose (tools, thread +, Post) · Title page (Summary/reviews, Review/Backlog) · Blog · DMs (hold to react, swipe to reply) · VRM mode · Camera page.
Triggers: AppRoot picks the screen that's showing (`tipScreen`) and requests its tour 0.8 s later (1.5 s for VRM/Camera); title, blog and DM chat request their own from their composables. The Hub tour waits for the Hub's main page (`Tips.hubMainShowing`, set in SettingsSheet).

### Anchors added
SettingsSheet (feeds row, Launchpad, Timeline/Explore pills, + button, Settings button), MainFeedScreen (author pill, follow, text bubble, action bar + like/save/send/more), GridScreen (feeds/content-type rows; `ResultsInteractionBar` gained `refreshAnchor`/`gridAnchor`), ProfileOverlay (profile tabs, Add To, title tabs/bars, profile Follow), SearchOverlay (bar, tabs), CommentsSheet (header, field), ComposePostScreen (tools row, thread +, Post), `CaptureControlsBar` on both platforms (`capture.bar`, one modifier on its button Row — the only Android edit).

### Removed
The update-33 pinch popup (`OnboardingPopups` PINCH_TIP stage, `Onboarding.pinchTip*`, Dev Tools › Reset Pinch Tip).

### To check on a phone
Fresh install (or Reset Tips): each screen's walkthrough appears once; lines land on the right buttons; nothing underneath reacts to taps while a tip is up; text is readable over busy posts; the Explore/Timeline pills note on small screens.


---

## 27. Update 35 — Tips polish, like heart, post stats row, Camera page = VRM bar (shared + iOS Kotlin pass the local check; Android-only edits not compiled; not yet run)

### Tips (Recho's feedback on the first device run)
- **Wording rules** (top of `TipTours.kt`): "This is the X. It does Y." / "You can …" for features; no orders, no "Name: explanation" shorthand, no notes that lean on each other; feeds are custom feeds/algorithms made by people on Bluesky; the text bubble opens on tap (it's one line by default).
- **Highlights** (`TipStep.highlights`): when given, only those are lit; notes still point at their own anchors — so a whole bar is lit while each button gets its own note. `"a+b"` lights one area covering both (Timeline+Explore pills, author+follow, Launchpad label+pad, tools row+thread +). Anything reaching a screen edge is lit as a plain full-width band (no rounded corners cut off).
- **Rows of buttons** get one note per button as a staircase (`stairRight` / `stairLeft`): each note starts beside its own button and reaches away from the buttons still below it, so no line crosses text. Timeline's bar and the profile bar are split over two screens (left half, right half).
- **Tap to continue.** moves (bottom → top → middle …) to wherever nothing of the step is.
- **Keyboard** is closed while a tip is up.
- **Launchpad** text is built from the account's actual first page (`HubLayout.launchpad`), top left to bottom right.
- **DMs** use new animations (`HOLD_REACT`: the reaction row popping over a message; `SWIPE_REPLY`).
- Removed: comments tour, search's Tagged step.
- VRM/Camera: one note per button (tiers: capture lowest, then Activity, mode, Settings/Flip, mic).

### Camera page bar = VRM mode's (both platforms)
`[mic] [photo → video → live] [capture] [Activity] [flip]`. Live moved into the mode button (capture opens the Go Live popup in live mode); Activity (scenes, soundboard, effects) is the 4th button, drawn over the camera and into photos/recordings/streams the same way VRM mode does it (Android: `VrmStageFrames` → `CaptureOverlay`; iOS: `stageSmall`/`stageLayer` → `CaptureLayer`). Mode saved as `camera_page_capture_mode`. Android edit (`CameraModeScreen.kt`) not compiled.

### Like heart
Liking (like button, or double tap) sends up one 3D heart (`drawHeart3D`, the Hearts effect's heart, in `DmEffects.kt`) in the post's color: from the like button, or bigger from the double-tapped spot; pops in, spins, rises slow-then-fast, fades (`LikeHeart` in `MainFeedScreen.kt`). Not with Reduced Animations; not when unliking.

### Post stats row
The opened text bubble ends with likes · reposts · saves · comments (icons in the post's color, same size as the text) and the date on the right (`PostStatsRow`). Saves = Bluesky's `bookmarkCount` (new on `BskyPost`/`MediaItem`; kept in step when you save/unsave). Settings › App Functionality: **Hide Post Stats**, and once on, **Hide Post Date** (both on = no row; `UiToggles.hidePostStats/hidePostDate`).


### Update 36 — Search › Tagged › Sort
- A **Sort** button at the right end of the Tagged results bar (`ResultsInteractionBar(trailing = …)`) opens the same bubble stack as More (`BubbleActionStack`): most liked, most reposted, most saved, most comments, most recently uploaded, most recently tagged — top to bottom, icons only, the chosen one in your color. Default most liked; remembered (`UiToggles.taggedSort`). Sorting happens in the ViewModel (`sortTagged`, stable — ties stay most-recently-tagged first), so posts opened from the list swipe in the sorted order.
- **The whole dataset is sorted, not just the newest 200:** the Tagged search and browse no longer cap results on either platform (`TaggingRepository.search` / `browseAllTagged(Int.MAX_VALUE)`), and every match is fetched for its counts (`getPostsByUris`: 25 a request, 6 requests at a time, one retry on 429/5xx). A dataset of thousands of posts means a few hundred requests and a few seconds of spinner each search.

## 28. Update 37 — Tagged paging, profile Sort, like heart, fixes (shared + iOS Kotlin pass the local iOS check; no Android-only or Swift edits; not yet run)

### Tagged: index-sorted, paged (replaces update 36's load-everything)
- `util/TaggedStats.kt`: an on-phone index of every tagged post's likes / reposts / saves / comments / post date (`tag_stats/stats.tsv` in private files). Like e621's indexed `fav_count`/`score` columns: the sort runs over these numbers, not over downloaded posts.
- `performLikedTagSearch`: all matching uris (cheap) → numbers fetched only for uris never seen (`BlueskyRepository.getPostStats`, 25/request, 6 at a time; progress "Sorting your tagged posts… n/N", first time only) → `TaggedStats.order` → only 30 posts hydrated (`loadTaggedPage`), 30 more per scroll (`loadMoreTagged`, wired via `SearchOverlay.onLoadMoreTagged/taggedLoadingMore/taggedExhausted`). Each page refreshes its posts' numbers; numbers older than 3 days refresh quietly in the background (≤1500 per search). Deleted posts drop out.

### Profile › Sort
- The QR button moved into More (between Profile Note and View on Bluesky); its bar spot is now **Sort** (`profile.sort` anchor; profile tour text updated): most liked / reposted / saved / comments / newest (default, Bluesky's order). Lit in your color when not newest. Session-only, per profile (`ProfileOverlayState.postSort`).
- Bluesky has no like/repost sort for an account's posts (`getAuthorFeed` is newest-only; search `sort=top` isn't a like count), so a sorted Posts tab reads every own post once — `scanProfilePosts`: `getAuthorFeed` 100/request, one at a time, keeping only uri + counts + which sub-tabs it belongs to — sorts on the phone, then hydrates 30 at a time (`loadSortedProfilePosts`). The read is cached for the session (`profileScanCache`, cleared by Refresh), so switching sorts or sub-tabs (All / Images / Horizontal / Vertical / Text — each sub-tab gets its own top posts) is instant. Progress "Sorting posts… n". Sorted pages aren't written to the profile disk cache.

### Like heart / double tap
- `LikeHeart`: rises from the first frame (accelerating), sways and spins, pops with overshoot, ring + sparkles at the spot; no fade — it flies off the top of the screen and is removed then.
- `drawHeart3D` (and the DM Hearts effect, now sharing `drawHeartSlices`): slices ≤ ~1px apart and opaque, fading applied once via a layer — no more fuzzy sides.
- Settings › UI Customization › **2D Like Heart** (`UiToggles.flatLikeHeart`, no description): `FlatLikeHeart` + `drawHeart2D` — springs in, wobbles, holds a beat, then lifts off eased-in with a sway and lean, off the top of the screen.
- Double tap only likes (e621: favorites) when not already; never unlikes. Haptic + heart every time. The like button still bursts only when liking.

### Fixes
- Tips: no blink between steps (`previousHoles`/`holeFade` now set in the same frame as the step); text crossfade overlaps.
- DM tip pictures: both message placeholders are the same size and height.
- First comment of a session failing: `postComment` renews an expired sign-in and retries once, fetches the post's cid if missing, re-reads comments 1.8s later (AppView lag), and says so if it still fails.

### Update 38 — profile Sort: saved + paced
- `util/ProfileSortStore.kt`: a sorted profile's post list (uri, counts, sub-tab bits) is saved per account (`profile_sort/<did>.tsv`, first line = when the whole account was last read). Next sort — even after a restart — reads only posts newer than the saved list (`scanProfilePosts(stopAt = known uris)`) and merges.
- Saved counts older than 3 days: the whole account is re-read in the background with a 700ms pause per page (`refreshProfileSortInBackground`), saved, used from the next sort (no reshuffle on screen).
- Foreground reads pause 250ms between pages. Profile Refresh forces a full re-read (`profileForceFullScan`).
- In memory: the 6 most recently sorted profiles (`profileScanCache`, LinkedHashMap, synchronized).

### Update 39 — refresh by post age (replaces the 3-day recount)
- `statRefreshInterval(createdMs, now)` (TaggedStats.kt): how long numbers stay fresh by post age — <2 days: 2h, <7 days: 12h, <30 days: 2 days, <1 year: 10 days, older: 30 days (unknown date: 3 days). Posts get nearly all their likes early, so new posts are rechecked often and old ones rarely.
- Tagged: `TaggedStats.stale` = due by that schedule, newest posts first (still ≤1500 per search, background).
- Profile Sort: `ProfilePostEntry` now has `createdMs`/`fetchedMs` (store columns 7–8; old files load with 0 = due). On a sort with a saved list, `scanProfilePosts` reads new posts plus the last 7 days again (stops at the first known post older than a week) — usually one request. Then `refreshProfileSortInBackground` rechecks only the due posts (≤600, newest first, `getPostStats` 100 at a time, 500ms pauses); deleted posts are dropped. Posts loaded on screen write their live numbers back (`updateProfileEntries`). No more whole-account recount on a timer; Refresh still forces a full re-read.

### Update 40 — numbers update as posts load (replaces update 39's schedule)
- No background refreshing at all, for Tagged or profiles (`staleRefreshJob`, `refreshProfileSortInBackground`, `statRefreshInterval`, `TaggedStats.stale` removed).
- Every loaded page writes its posts' live numbers back: Tagged (`loadTaggedPage` → `TaggedStats`), sorted profile pages and normal newest-first Posts tab pages (`writeBackProfileNumbers` → `ProfileSortStore`, only if that account has a saved sort list).
- Sorting a profile with a saved list still reads new posts + the last 7 days (usually one request). Known gap: a post that's never loaded can't move up until it is.

### Update 41 — profile index: resumable, caught up on open
- `ProfileIndex` (MainViewModel, "Profile post index"): an account's post list for Sort, kept in memory (6 most recent) and in `ProfileSortStore` (format v2: header `v2 <complete> <cursor>`; older files load as complete).
- Started the first time a profile is sorted (`runProfileIndex(start = true)`); read 100 posts a page (`BlueskyRepository.getOwnPostEntriesPage`, 250ms between pages), saved every 5 pages and when stopped.
- Follows the open profile (an `init` collector on `_profileOverlay`'s did): closing the profile or opening another cancels the read (what's read is saved, with the cursor); reopening carries on from the cursor until the full read is done. Hidden (post opened from the profile) keeps going.
- Each open, once a session, reads only posts newer than the index (stops at the first known post) — usually one request. No re-reads on a timer; the last-week re-read is gone.
- Live numbers from any loaded page of that profile replace indexed ones (`writeBackProfileNumbers`). Refresh on a sorted profile rebuilds the index (`resetProfileIndex`).
- Sorting waits for an unfinished index ("Sorting posts… n"); if a page can't be read, it sorts what it has and finishes on a later open.
- File I/O is kept outside `synchronizedCompat` (iOS uses one app-wide lock); saves use a Mutex.
