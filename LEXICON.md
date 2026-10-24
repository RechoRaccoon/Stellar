# Stellar Custom Lexicons

Stellar extends AT Protocol with four custom record collections for profile
customization: profile style, Home tab rows, public folders, and custom
emoji. This document describes those collections exactly, so that other
apps can read and render them, and so that agents acting for a user can
read and write them directly through the user's PDS.

## Who this is for

- **App developers** integrating Stellar features: read these collections
  from any account's PDS and render what they describe. No Stellar code,
  account, or permission is required to read.
- **Agents** acting for a user: authenticate as the user and read, write,
  or delete these records through the standard `com.atproto.repo.*`
  endpoints. No Stellar client is required.

## Principles

- **Public records.** Every collection here is a plain, unencrypted
  record on the account's own PDS. Anyone can read them without signing
  in, using `com.atproto.repo.getRecord` or `com.atproto.repo.listRecords`.
  Only the account owner can write or delete them.
- **Custom lexicons, no published schema.** A PDS validates records
  against known lexicons by default, so writes use
  `com.atproto.repo.putRecord` with `"validate": false`. The one
  exception is emoji creation, which uses `com.atproto.repo.createRecord`
  (the app sends no validate flag there) and lets the PDS assign the key.
- **Every record carries `"$type"` set to its collection name.**
- **Absence means default.** There is no "off" flag. Deleting a record
  removes that customization; a profile with no style record is a default
  profile.
- **Stellar-only rendering.** Other Bluesky apps ignore these records.
  Nothing here changes the account's Bluesky posts, profile, or
  appearance outside Stellar.
- **Timestamps are ISO 8601 strings in UTC**, for example
  `2026-10-22T16:00:00.000Z`.

## Collections at a glance

| Collection | Purpose | One record per |
|---|---|---|
| `com.rechoraccoon.stellar.profile` | Profile style: icon shape, effect, color, video banner/icon, status | Account (single record, key `self`) |
| `com.rechoraccoon.stellar.home` | Profile Home tab rows and header style | Row (plus one `settings` record) |
| `com.rechoraccoon.stellar.folder` | Public bookmark folders | Folder |
| `com.rechoraccoon.stellar.emoji` | Custom emoji: name, folder, picture | Emoji |

---

## 1. Profile style — `com.rechoraccoon.stellar.profile`

One record per account, always at record key `self`. It controls how the
account's profile page looks in Stellar: the icon shape, an animated
effect when the profile opens, a profile color, a video in place of the
banner, a video in place of the profile picture, and a temporary status
bubble.

### Fields

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.profile"`. |
| `iconShape` | string | `"circle"` (default) or `"square"` (a slightly rounded square icon instead of the round one). |
| `effect` | string | The effect that plays when the profile opens. One of the ids below; `"none"` (default) means no effect. Unknown ids read as `"none"`. |
| `styleVersion` | number | Always written as `2`. (Legacy: in version 1 records, `effect: "confetti"` meant "nothing picked" and still reads as `"none"`.) |
| `colors` | array of 2 strings | The account's **one** profile color, `"#RRGGBB"`. The format carries it twice, `["#RRGGBB", "#RRGGBB"]`, both entries the same, because the record originally held a pair; **only the first entry is used** and the second is legacy. The app writes the array only when a color is picked, always with both entries; omitted means the color is taken from the banner and profile picture as usual. Writers should write both entries with the same value. Entries must be exactly 6 hex digits; 8-digit/alpha forms are ignored on read. |
| `bannerVideo` | blob | A short looping video shown **in place of the banner picture**. See "Video blobs" below. |
| `iconVideo` | blob | A short looping video shown **in place of the profile picture** on the profile page. Same blob shape. |
| `status` | object | A temporary status, shown in a bubble by the profile picture. See "Status" below. |
| `updatedAt` | string | ISO 8601, set on every save. |

Stellar writes `iconShape`, `effect`, `styleVersion`, and `updatedAt` on
every save; `colors`, `bannerVideo`, `iconVideo`, and `status` only when
set. **When everything is back to default, the record is deleted
entirely.** No record at all is the default state.

### Profile effect options

| Id | Shown as | What plays |
|---|---|---|
| `none` | None | Nothing (default) |
| `confetti` | Confetti | Falling confetti |
| `balloons` | Balloons | Birthday balloons |
| `snow` | Snow | Falling snow |
| `fireworks` | Fireworks | Fireworks |
| `bats` | Bats | Bats |
| `hearts` | Hearts | Floating hearts |
| `rain` | Rain | Rain |
| `bubbles` | Bubbles | Bubbles |
| `paws` | Paw Prints | Paw prints |
| `starshower` | Shooting Stars | Shooting stars |

### Video blobs (banner and icon)

The video is uploaded to the account's own PDS first
(`com.atproto.repo.uploadBlob`), then referenced from the record as a
standard AT Protocol blob object:

```json
"bannerVideo": {
  "$type": "blob",
  "ref": { "$link": "bafkrei…" },
  "mimeType": "video/mp4",
  "size": 1234567
}
```

- `ref.$link` is the blob's CID from the upload response; `mimeType` and
  `size` are copied from it too.
- Maximum size: **50 MB** (the app refuses larger). The app also resizes
  to at most 1080p and strips personal metadata before uploading;
  writers should do the same.
- Playback is muted and looping, straight from the blob. No video
  service is involved.

### Status

```json
"status": {
  "text": "Streaming tonight!",
  "createdAt": "2026-10-22T16:00:00.000Z",
  "expiresAt": "2026-10-23T16:00:00.000Z",
  "emoji": [
    { "at": 10, "did": "did:plc:…", "cid": "bafkrei…", "name": "wave", "animated": true }
  ]
}
```

- `text`: required, **max 300 characters** (longer is cut off on read).
- `createdAt`: ISO 8601 (set to now if left blank when the app saves).
- `expiresAt`: optional ISO 8601. Once the time passes, every Stellar
  client hides the status even if the record still contains it. Omitted
  means the status stays until changed.
- `emoji`: optional list of custom emoji inside the text. Each entry:
  `at` (the character index in `text` where the emoji sits; in the app
  it stands on an invisible marker character, see §4), `did` (the
  account whose emoji library the picture belongs to), `cid` (the blob
  CID of that emoji's picture, from its `com.rechoraccoon.stellar.emoji`
  record), `name` (display name, may be empty), `animated` (written
  only when true, meaning the emoji's picture is a GIF).

### Example: a complete profile style record

```json
{
  "$type": "com.rechoraccoon.stellar.profile",
  "iconShape": "square",
  "effect": "paws",
  "styleVersion": 2,
  "colors": ["#AB351E", "#AB351E"],
  "bannerVideo": {
    "$type": "blob",
    "ref": { "$link": "bafkreiexamplebanner…" },
    "mimeType": "video/mp4",
    "size": 4211330
  },
  "status": {
    "text": "Live in five!",
    "createdAt": "2026-10-22T16:00:00.000Z",
    "expiresAt": "2026-10-22T20:00:00.000Z"
  },
  "updatedAt": "2026-10-22T16:00:00.000Z"
}
```

Written to: `at://<did>/com.rechoraccoon.stellar.profile/self`

---

## 2. Profile Home tab — `com.rechoraccoon.stellar.home`

A profile's **Home tab**: rows the account picked to show first on their
profile. A row can hold posts (from lists, accounts, hashtags, tags, or
public folders), recommended accounts, or blogs and reviews.

- The tab appears on someone's profile **as soon as they have at least
  one row record**. (The owner always sees their own tab.)
- Each row is **one record**; the record key **is the row's id** (the
  app generates keys like `h` plus a base-36 timestamp, for example
  `hm4k2x9ab`, but any unique key works).
- One extra record at key **`settings`** holds the header style.
- Rows display in ascending `position` order.

### Row record

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.home"`. |
| `name` | string | The row's title (a copy of the name inside `spec`). |
| `position` | number | Where the row sits (0 = first). Rows without one go last. |
| `spec` | string | **A JSON string** (JSON encoded inside JSON; note the double encoding) describing the row's feed. See "The spec object" below. |
| `createdAt` | string | ISO 8601. |

### The spec object (the feed inside `spec`)

The `spec` string parses into a "built feed" object:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | `""` | The feed's id, same as the record key. |
| `name` | string | `""` | The row's title. |
| `sources` | array | `[]` | What the row is made of. See "Sources" below. |
| `textPosts` | boolean | `true` | Include text-only posts. |
| `images` | boolean | `true` | Include picture posts. |
| `horizontalVideos` | boolean | `true` | Include wide (16:9) videos. |
| `verticalVideos` | boolean | `true` | Include tall (9:16) videos. |
| `polls` | boolean | `true` | Include polls. |
| `sort` | string | `"recent"` | Order: `"recent"`, `"oldest"`, `"likes"`, `"reposts"`, `"replies"` (likes/reposts/replies = most first). |
| `style` | string | `"natural"` | How the row looks: `"natural"` (each post its own shape), `"square"`, `"tiktok"` (tall 9:16 cards), `"youtube"` (wide 16:9 cards with their text under them), `"avatars"` (just the accounts' pictures, for a recommended-accounts row). |
| `rows` | number | `1` | How many rows deep the strip is, 1 to 3. |
| `blogs` | boolean | `false` | Also show these accounts' blogs (Leaflet) as a row of their own under the posts. |
| `reviews` | boolean | `false` | Also show these accounts' reviews (Popfeed) as a row of their own under the posts. |

Fields left out of the JSON take the defaults shown.

### Sources

Each source is `{ "kind": …, "value": …, "label": … }` (`label` is an
optional display name, may be `""`):

| `kind` | `value` is | Shows |
|---|---|---|
| `list` | A Bluesky list's `at://` URI | Posts from that list |
| `account` | An account's DID (`did:plc:…`) | That account's posts, or, with `style: "avatars"`, the account itself |
| `hashtag` | A hashtag without the `#` | Posts with that hashtag |
| `tag` | A tag name | Posts carrying that tag in the on-device/shared tag datasets |
| `folder` | One of the **owner's own** bookmark folder ids | That folder's posts. **Only works in the owner's own tab**: these sources are dropped when anyone else views the profile. Use `publicFolder` for rows everyone should see. |
| `publicFolder` | `"<owner did>/<folder id>"`: the folder owner's DID, a slash, and the folder's record key | That public folder's posts (see §3) |

### Header settings record (key `settings`)

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.home"`. |
| `headerAlign` | number | Row titles: `0` = left, `1` = center, `2` = right. |
| `dividerHeaders` | boolean | A small divider line between the title and its row. |
| `headerLines` | boolean | Decorative lines beside a big title. |
| `createdAt` | string | ISO 8601. |

### Examples

A row of one of your public folders (a "Shorts" row). First make the
folder public (§3), then write a row whose only source points at it:

```json
{
  "$type": "com.rechoraccoon.stellar.home",
  "name": "Shorts",
  "position": 0,
  "spec": "{\"id\":\"hshorts1\",\"name\":\"Shorts\",\"sources\":[{\"kind\":\"publicFolder\",\"value\":\"did:plc:your…/folderid1\",\"label\":\"Shorts\"}],\"style\":\"tiktok\"}",
  "createdAt": "2026-10-22T16:00:00.000Z"
}
```

A recommended-accounts row ("Follow These Guys"). `style` is
`"avatars"` and each source is one account:

```json
{
  "$type": "com.rechoraccoon.stellar.home",
  "name": "Follow These Guys",
  "position": 1,
  "spec": "{\"id\":\"hrecs001\",\"name\":\"Follow These Guys\",\"sources\":[{\"kind\":\"account\",\"value\":\"did:plc:aaa…\",\"label\":\"\"},{\"kind\":\"account\",\"value\":\"did:plc:bbb…\",\"label\":\"\"}],\"style\":\"avatars\"}",
  "createdAt": "2026-10-22T16:00:00.000Z"
}
```

Header style (written at record key `settings`):

```json
{
  "$type": "com.rechoraccoon.stellar.home",
  "headerAlign": 1,
  "dividerHeaders": true,
  "headerLines": false,
  "createdAt": "2026-10-22T16:00:00.000Z"
}
```

---

## 3. Public folders — `com.rechoraccoon.stellar.folder`

Bookmark folders are private by default and kept only on the device. A
folder switched to **Public** is also written, unencrypted, as one
record on the owner's PDS, so it shows on their profile (Collections →
Folders) and can be used as a Home tab row (§2).

- One record per folder; the record key **is the folder's id**.
- Making the folder private again (or deleting it) deletes the record.

### Fields

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.folder"`. |
| `name` | string | The folder's name (read as "Folder" if blank). |
| `posts` | array of strings | The posts in the folder, as `at://` URIs, newest first. **Max 1000** (the app writes at most that many; entries that are not `at://` URIs are ignored on read). |
| `cover` | string | Optional. The folder's cover picture as an `https://` URL (a post's public thumbnail). Only `https://` URLs are written or read, never a file from a device. Omitted when there is none. |
| `layout` | string | How the folder opens: `"grid"` (default), `"vertical"` (tall 9:16 tiles), `"horizontal"` (wide 16:9 videos with their text). Only written when it is not `"grid"`. |
| `createdAt` | string | ISO 8601. |
| `updatedAt` | string | ISO 8601. Folders list newest-updated first. |

### Example

```json
{
  "$type": "com.rechoraccoon.stellar.folder",
  "name": "VR Pictures",
  "posts": [
    "at://did:plc:…/app.bsky.feed.post/3l…",
    "at://did:plc:…/app.bsky.feed.post/3l…"
  ],
  "cover": "https://cdn.bsky.app/img/feed_thumbnail/plain/did:plc:…/bafkrei…@jpeg",
  "layout": "vertical",
  "createdAt": "2026-10-01T12:00:00.000Z",
  "updatedAt": "2026-10-22T16:00:00.000Z"
}
```

Written to: `at://<did>/com.rechoraccoon.stellar.folder/<folder id>`

---

## 4. Custom emoji — `com.rechoraccoon.stellar.emoji`

Custom emoji live in the account's own emoji library: one record per
emoji on the owner's PDS, so the emoji follow the account to every
device and can be shown to whoever they message. Like folders, these
are **plain public records**. Anyone can read the library, and the
records are not encrypted.

- One record per emoji; the record key is **assigned by the PDS at
  creation** (`createRecord`, below). Keys are timestamps, so listing
  the collection in key order gives the order the emoji were added.
- The same picture is the same emoji everywhere: the record carries a
  hash of the picture, and the app matches on it. Uploading the same
  picture twice does not make a second emoji, and deleting the local
  copy deletes the record.

### Fields

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.emoji"`. |
| `name` | string | The emoji's display name (read as `"emoji"` if blank). |
| `image` | blob | The emoji's picture, as a standard blob object (see §1 "Video blobs" for the shape). `image/png`, or `image/gif` for an animated emoji. |
| `hash` | string | FNV-1a 64-bit hash of the picture bytes, as 16 lowercase hex characters, zero-padded on the left. This is the emoji's identity for syncing (see below). |
| `folder` | string | Optional. The folder the emoji sits in, by the folder's **name** (not an id). Omitted when the emoji is only in All. |
| `createdAt` | string | ISO 8601. |

There is **no `animated` field on the record**. Animation is read from
the picture: `image.mimeType == "image/gif"`. The `animated: true` flag
appears only in the places that *point at* the emoji (a status's `emoji`
list, a post or DM facet), and only when the picture is a GIF.

### Creating one

1. Upload the picture with `POST /xrpc/com.atproto.repo.uploadBlob`:
   `image/png`, or `image/gif` for an animated emoji. Keep the returned
   blob object.
2. Compute the hash: FNV-1a 64-bit over the picture bytes (offset basis
   `0xcbf29ce484222325`, prime `0x100000001b3`), rendered as 16
   lowercase hex characters, zero-padded on the left.
3. Create the record with `POST /xrpc/com.atproto.repo.createRecord`,
   body: `{ "repo": "<did>", "collection":
   "com.rechoraccoon.stellar.emoji", "record": { … } }`. The PDS
   assigns the key. The response URI's last segment is the record key,
   and the blob CID from step 1 is the picture's CID.
4. Folder: pass the folder's name as `folder` to put the emoji in a
   folder, or leave it out for All only.

### Example

```json
{
  "$type": "com.rechoraccoon.stellar.emoji",
  "name": "wave",
  "image": {
    "$type": "blob",
    "ref": { "$link": "bafkrei…" },
    "mimeType": "image/png",
    "size": 12345
  },
  "hash": "0123456789abcdef",
  "folder": "Reactions",
  "createdAt": "2026-10-24T12:00:00.000Z"
}
```

Written to: `at://<did>/com.rechoraccoon.stellar.emoji/<key assigned by the PDS>`

### Using one

Emoji appear inside text through an invisible marker character (U+2060
WORD JOINER) placed where the emoji sits, plus a pointer that says
which emoji it is:

- **In a status**: the status text (§1) carries the marker, and the
  status's `emoji` list points at it: `at` (the character index of the
  marker in the text), `did` (the library owner), `cid` (the picture's
  blob CID), `name`, `animated` (only when the picture is a GIF).
- **In a post or comment**: the post text carries the marker, and the
  post's facets carry one facet of type
  `com.rechoraccoon.stellar.richtext.facet#emoji` over it (UTF-8 byte
  offsets), with `did`, `rkey`, `cid`, `name` (and `animated` when a
  GIF). The `app.bsky.richtext.facet` features union is open, so a PDS
  accepts a post facet with this custom `$type`. Stellar draws the
  picture there; other apps show the post without the emoji pictures.
- **In a DM**: the same marker in the message text, plus a facet of the
  same type with the same fields. If the chat service refuses the
  facets, the message goes as `":name:"` text instead.
- **Reading anyone's library**: `listRecords` on their PDS, no sign-in
  needed. Records come back newest first; sort by key for the order
  they were added.
- **Deleting**: `deleteRecord` on the collection and key. (The app's
  sync also deletes the record when the emoji disappears from the
  owner's device library.)

### Common mistakes

- **Re-uploading the same picture to "make" an emoji that already
  exists.** The hash matches, so the app treats it as the same emoji.
  Check the library first.
- **Using a folder id for `folder`.** It is the folder's name.
- **Expecting an `animated` field on the record.** There isn't one;
  animation is read from the picture's mimeType.
- **Writing the record with a hand-picked key via `putRecord`.** The
  app creates with `createRecord` and lets the PDS assign a timestamp
  key; keys double as the library order.

---

## 5. Integrating into your app

To render Stellar customization for an account:

1. **Profile style.** Fetch `com.rechoraccoon.stellar.profile/self`.
   If there is no record, render defaults (round icon, no effect, color
   from the banner and picture). Otherwise apply `iconShape`,
   `effect`, and `colors[0]`; substitute `bannerVideo` for the banner
   and `iconVideo` for the profile picture where present; show `status`
   while `expiresAt` is in the future (or unset). Unknown effect ids
   mean no effect.
2. **Home tab.** List `com.rechoraccoon.stellar.home`, sort row records
   by `position` (records without one go last), and render each row's
   parsed `spec`. Read the `settings` record for header style. Drop
   `kind: "folder"` sources when viewing someone else's profile; keep
   `publicFolder` sources for everyone.
3. **Public folders.** List `com.rechoraccoon.stellar.folder`, newest
   `updatedAt` first. Resolve each folder's `posts` URIs through the
   usual post endpoints. Open a folder in its `layout` where you have
   an equivalent, or in a plain grid otherwise.
4. **Custom emoji.** List `com.rechoraccoon.stellar.emoji` to build the
   account's library (key order is addition order; `hash` deduplicates
   identical pictures). To render emoji in text, replace each U+2060
   marker with the picture from the facet or status entry that points
   at it, resolved by `did` + `cid` (or `did` + `rkey`). Where you
   cannot resolve a pointer, leave the marker out and show nothing, or
   fall back to `":name:"` text.
5. **Fallbacks.** Treat every unknown field, unknown effect id, and
   unknown style or layout value as "use the default". Never fail a
   whole profile, row, or folder over one field you do not recognize.

## 6. Writing these records (for agents)

1. Authenticate as the account (an app password session is fine) and
   use its PDS's XRPC endpoints.
2. **Blobs first.** Upload any video or picture with
   `POST /xrpc/com.atproto.repo.uploadBlob`, then put the returned
   blob object (`{"$type":"blob","ref":{"$link":…},"mimeType":…,"size":…}`)
   into `bannerVideo`, `iconVideo`, or `image`.
3. **Emoji via `createRecord`.** Follow §4's four steps. The PDS
   assigns the key.
4. **Everything else via `putRecord` with `"validate": false`**, body:
   `{ "repo": "<did>", "collection": "<collection>", "rkey": "<key>", "record": { … } }`.
5. **Remove a customization by deleting its record**
   (`com.atproto.repo.deleteRecord`). For the profile style, deleting
   the `self` record resets everything to default at once.
6. **To change one thing in the profile style, read the current record
   first** (`com.atproto.repo.getRecord`), modify it, and write the
   whole record back. `putRecord` replaces the record completely.

### Common mistakes

- **Forgetting `validate: false`.** The write fails; these are custom
  lexicons with no published schema on the PDS.
- **Double-encoding the Home `spec`.** It is a *string containing JSON*,
  not a nested object. A nested object there is ignored and the row is
  skipped.
- **Using `kind: "folder"` for a public row.** Those sources are
  dropped for everyone but the owner. Public rows need `publicFolder`
  with `"<folder owner's did>/<folder id>"`.
- **8-digit colors.** `colors` entries must be exactly `#RRGGBB`
  (6 hex digits) or they are ignored.
- **A `cover` that is not `https://`.** Ignored on read, and the app
  never writes one (covers come from posts' public thumbnails).
- **Expecting other apps to show any of this.** Only Stellar reads
  these collections.

---

*Source of truth: Stellar's code — `util/ProfileStyles.kt`,
`util/ProfileHome.kt`, `util/PublicFolders.kt`, `util/LocalData.kt`
(`LocalFeed`), `util/EmojiCloud.kt`, and
`repository/BlueskyRepository.kt` (`saveProfileStyle` /
`parseProfileStyle`, `createEmojiRecord` / `listEmojiRecords`).*
