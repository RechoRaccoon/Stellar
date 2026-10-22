# Stellar — notes for working on this repo

Kotlin Multiplatform + Compose Multiplatform app (Android, iOS, Desktop JVM, Web wasm).
Shared UI lives in `shared/src/commonMain/kotlin/com/mediaviewer/ui/`.

## Design language (read before writing ANY new UI — the owner checks every one of these)

Every new screen, row, button or popup must look like it was always part of
Stellar. Before writing new UI, find the nearest existing equivalent and copy
its look; don't invent a new one.

1. **No unnecessary words.** No descriptions, subtitles, hints or helper
   lines under things that explain themselves (a toggle's name says what it
   does; "Visibility: Private / Public" needs no paragraph; a "Remove?"
   prompt needs no body text). Add a line of text only when something would
   genuinely be unclear without it. When unsure, leave it out.
2. **Edges line up.** New UI starts and ends exactly where the UI around it
   does. Use the page's own edge, never a new number:
   - Profiles: `PROFILE_EDGE` (tabs, sub-filters, bio, Home rows).
   - Hub: 16dp (the search bar's edges); its pages too (Customize Hub).
   - Grids / Saved Posts / Explore: the filter chips' and tiles' edge.
   - Something centered in a wider slot (an avatar in a name column):
     offset the row so the *visible* thing lines up, not its slot.
   Check the first and last item of every new row against the rows above
   and below it.
3. **Popups have a background.** Popups are glass only while the Timeline
   is showing; everywhere else they're filled (`PopupStyle`,
   `Modifier.popupPanel`, buttons `Modifier.popupButtonFill`). Use
   `LocalPopup` / `PopupSheetSurface` / `ConfirmPopup` (they do this for
   you) — never a bare Box or a transparent panel. The page behind is
   blurred; a popup never dims the whole screen on its own.
4. **Spacing is consistent.** Gaps between stacked rows match the gaps
   already used on that page (e.g. under a profile's sub-filters: the same
   gap as from the tabs to the sub-filters).
5. **Reuse the app's pieces**: `ProfileBackBubble` (Back, top left, title
   level with it), `ProfileSubFilterRow`/`FacetPill` (sub-filters and
   their Customize/Done style toggles), `LayoutRowsList` (draggable lists),
   `ConnectedChoice` (joined option buttons), `LayoutSectionDivider`
   (section names between lines), `PopupDeleteBubble` + `ReallyPrompt`
   (two-tap deletes that say "Really?", never red, reverting on a tap
   elsewhere), `FeedRowTitle` / `HubRowHeader` (row names, which follow the
   Hub / Home header settings — every row, including the Launchpad's and
   Upcoming Events').
6. **Clear by default.** Buttons on pages are glass/clear like the rest of
   the page (Dev Tools' Gradient Buttons fills them all).
7. **Text posts look the same everywhere**: the opened card's own layout
   (width, line breaks, letter size), scaled as a whole — never re-wrapped.

## Popups (read before adding any popup, confirmation or dialog)

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
  (Exception: the calendar's long tail of fun days in util/HolidaysMore.kt
  is data, shown in English.)
- Shapes: use `com.mediaviewer.ui.theme.RoundedCornerShape` / `CircleShape`
  (they follow Squared-Off Corners); `AvatarShape` / `TrueCircleShape` stay round.
- Settings are device-local; only the lists in `PrivateSyncSections` sync.
- PDS writes are debounced (`later(...)` in ProfileHome / PublicFolders) and
  counted against `PrivateSync.WriteBudget`; never write per keystroke or
  per drag step.
- Comments explain *why*, in plain words, matching the surrounding code.
