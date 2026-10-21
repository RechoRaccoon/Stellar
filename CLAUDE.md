# Stellar — notes for working on this repo

Kotlin Multiplatform + Compose Multiplatform app (Android, iOS, Desktop JVM, Web wasm).
Shared UI lives in `shared/src/commonMain/kotlin/com/mediaviewer/ui/`.

## Popups (read before adding any popup, confirmation or dialog)

Every popup must look like the app's others: the app page behind it is
**blurred**, and the popup's own glass **blurs what's under it**. It must not
dim the whole screen on its own.

- Use `ConfirmPopup` (ui/ConfirmPopup.kt) for any "are you sure?" and pass it a
  real `backdrop` (the page's `GlassBackdrop`), never `backdrop = null`.
- Don't wrap popups in `androidx.compose.ui.window.Dialog`: a Dialog is a
  separate window, so it dims everything and its glass can't blur the page.
- From somewhere that can't draw over the whole app itself (a panel, a
  sheet, the emoji menu), set `LocalOverlays.confirm = ConfirmRequest(...)`;
  `LocalOverlayHost` shows it over the blurred app with the app backdrop.
- Bigger popups (sheets, pickers) go through `LocalOverlays` +
  `LocalOverlayHost` (see `FadingPopupHost`, `PopupSheetSurface`) and count
  toward `LocalOverlays.popupOpen`, which turns the app blur on.

## Conventions

- User-visible strings are English keys translated from
  `shared/src/commonMain/composeResources/files/i18n/*.json`; add every new
  string to all of them and run `python3 tools/i18n/extract.py --check`.
- Shapes: use `com.mediaviewer.ui.theme.RoundedCornerShape` / `CircleShape`
  (they follow Squared-Off Corners); `AvatarShape` / `TrueCircleShape` stay round.
- Settings are device-local; only the lists in `PrivateSyncSections` sync.
- Comments explain *why*, in plain words, matching the surrounding code.
