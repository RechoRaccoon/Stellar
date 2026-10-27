# Stellar Custom Lexicons

Stellar adds four custom record collections on top of AT Protocol:
profile style, Home tab rows, public folders, and custom emoji. This doc
lists them exactly: what each record holds, and how to read and write
it through the account's PDS.

- **App developers:** read these collections from any account's PDS
  and render them. No Stellar code, account, or permission is needed
  to read.
- **Agents acting for a user:** sign in as the user and read, write,
  or delete them through the standard `com.atproto.repo.*`
  endpoints. No Stellar client is needed.

## Principles

- **Public records.** Every collection here is plain, unencrypted, on
  the account's own PDS. Anyone can read them with
  `com.atproto.repo.getRecord` or `com.atproto.repo.listRecords`, no
  sign-in. Only the owner can write or delete.
- **Custom lexicons, no published schema.** Writes use
  `com.atproto.repo.putRecord` with `"validate": false`. The one
  exception is emoji creation, which uses
  `com.atproto.repo.createRecord` (no validate flag sent) and lets
  the PDS assign the key.
- **Every record carries `"$type"` set to its collection name.**
- **Absence means default.** No "off" flag. Delete a record to remove
  that customization. No style record means a default profile.
- **Stellar-only rendering.** Other Bluesky apps ignore these
  records. Nothing here changes the account's posts, profile, or
  appearance outside Stellar.
- **Timestamps are ISO 8601 UTC strings**, for example
  `2026-10-22T16:00:00.000Z`.

## Collections at a glance

| Collection | Purpose | One record per |
|---|---|---|
| `com.rechoraccoon.stellar.profile` | Profile style: icon shape, effect, color, video banner/icon, status | Account (single record, key `self`) |
| `com.rechoraccoon.stellar.home` | Profile Home tab rows and header style | Row (plus one `settings` record) |
| `com.rechoraccoon.stellar.folder` | Public bookmark folders | Folder |
| `com.rechoraccoon.stellar.emoji` | Custom emoji: name, folder, picture | Emoji |
| `com.rechoraccoon.stellar.emojiLayout` | Emoji library arrangement: All order, folder order, per-folder order | Account (single record, key `self`) |

---

## 1. Profile style — `com.rechoraccoon.stellar.profile`

One record per account, always at record key `self`. It sets the
profile page look in Stellar: icon shape, an effect when the profile
opens, a profile color, a video instead of the banner, a video
instead of the profile picture, and a temporary status bubble.

### Fields

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.profile"`. |
| `iconShape` | string | `"circle"` (default) or `"square"`. |
| `effect` | string | Effect on profile open. One of the ids below; `"none"` (default) means no effect. Unknown ids read as `"none"`. |
| `styleVersion` | number | Always written as `2`. (Version 1 records: `effect: "confetti"` meant nothing picked, and still reads as `"none"`.) |
| `colors` | array of 2 strings | The account's **one** profile color, `"#RRGGBB"`, written twice: `["#RRGGBB", "#RRGGBB"]`. **Only the first entry is used**; the second is legacy. Write both with the same value. Entries must be exactly 6 hex digits; 8-digit/alpha forms are ignored on read. Omitted means the color comes from the banner and profile picture. |
| `bannerVideo` | blob | Video shown in place of the banner picture. See "Video blobs" below. |
| `iconVideo` | blob | Video shown in place of the profile picture on the profile page. Same blob shape. |
| `status` | object | Temporary status, shown in a bubble by the profile picture. See "Status" below. |
| `updatedAt` | string | ISO 8601, set on every save. |

Stellar writes `iconShape`, `effect`, `styleVersion`, and `updatedAt`
on every save; `colors`, `bannerVideo`, `iconVideo`, and `status`
only when set. **When everything is back to default, the record is
deleted entirely.** No record is the default state.

### Profile effect options

| Id | Shown as |
|---|---|
| `none` | None (default) |
| `confetti` | Confetti |
| `balloons` | Balloons |
| `snow` | Snow |
| `fireworks` | Fireworks |
| `bats` | Bats |
| `hearts` | Hearts |
| `rain` | Rain |
| `bubbles` | Bubbles |
| `paws` | Paw Prints |
| `starshower` | Shooting Stars |

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

- `ref.$link` is the blob's CID from the upload response; `mimeType`
  and `size` are copied from it too.
- Maximum size: **50 MB** (the app refuses larger). The app resizes
  to at most 1080p and strips personal metadata before uploading;
  writers should do the same.
- Playback is muted and looping, straight from the blob.

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
- `expiresAt`: optional ISO 8601. Past expiry hides the status in
  every Stellar client, even if the record still holds it. Omitted
  means it stays until changed.
- `emoji`: optional list of custom emoji in the text. Each entry:
  `at` (character index in `text` where the emoji sits; it stands on
  an invisible marker, see §4), `did` (library owner), `cid`
  (picture blob CID, from its `com.rechoraccoon.stellar.emoji`
  record), `name` (may be empty), `animated` (only when true,
  meaning the picture is a GIF).

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

A profile's **Home tab**: rows the account picked to show first on
their profile. A row holds posts (from lists, accounts, hashtags,
tags, or public folders), recommended accounts, or blogs and reviews.

- The tab shows on a profile once the account has at least one row
  record. The owner always sees their own tab.
- Each row is **one record**; the record key **is the row's id** (the
  app generates keys like `h` plus a base-36 timestamp, for example
  `hm4k2x9ab`; any unique key works).
- One extra record at key **`settings`** holds the header style.
- Rows display in ascending `position` order.

### Row record

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.home"`. |
| `name` | string | The row's title (a copy of the name inside `spec`). |
| `position` | number | Where the row sits (0 = first). Rows without one go last. |
| `spec` | string | **A JSON string** (JSON encoded inside JSON: double-encoded) describing the row's feed. See "The spec object" below. |
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
| `sort` | string | `"recent"` | Order: `"recent"`, `"oldest"`, `"likes"`, `"reposts"`, `"replies"` (last three = most first). |
| `style` | string | `"natural"` | How the row looks: `"natural"` (each post its own shape), `"square"`, `"tiktok"` (tall 9:16 cards), `"youtube"` (wide 16:9 cards with text under them), `"avatars"` (just the accounts' pictures, for a recommended-accounts row). |
| `rows` | number | `1` | How many rows deep the strip is, 1 to 3. |
| `blogs` | boolean | `false` | Also show these accounts' blogs (Leaflet) as their own row under the posts. |
| `reviews` | boolean | `false` | Also show these accounts' reviews (Popfeed) as their own row under the posts. |

Fields left out take the defaults shown.

### Sources

Each source is `{ "kind": …, "value": …, "label": … }` (`label` is an
optional display name, may be `""`):

| `kind` | `value` is | Shows |
|---|---|---|
| `list` | A Bluesky list's `at://` URI | Posts from that list |
| `account` | An account's DID (`did:plc:…`) | That account's posts, or, with `style: "avatars"`, the account itself |
| `hashtag` | A hashtag without the `#` | Posts with that hashtag |
| `tag` | A tag name | Posts carrying that tag in the on-device/shared tag datasets |
| `folder` | One of the **owner's own** bookmark folder ids | That folder's posts. **Owner's tab only**: dropped for anyone else viewing the profile. Use `publicFolder` for rows everyone should see. |
| `publicFolder` | `"<owner did>/<folder id>"`: the folder owner's DID, a slash, and the folder's record key | That public folder's posts (see §3) |

### Header settings record (key `settings`)

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.home"`. |
| `headerAlign` | number | Row titles: `0` = left, `1` = center, `2` = right. |
| `dividerHeaders` | boolean | Divider line between the title and its row. |
| `headerLines` | boolean | Decorative lines beside a big title. |
| `createdAt` | string | ISO 8601. |

### Examples

A row pointing at one of your public folders. Make the folder public
first (§3):

```json
{
  "$type": "com.rechoraccoon.stellar.home",
  "name": "Shorts",
  "position": 0,
  "spec": "{\"id\":\"hshorts1\",\"name\":\"Shorts\",\"sources\":[{\"kind\":\"publicFolder\",\"value\":\"did:plc:your…/folderid1\",\"label\":\"Shorts\"}],\"style\":\"tiktok\"}",
  "createdAt": "2026-10-22T16:00:00.000Z"
}
```

A recommended-accounts row. `style` is `"avatars"`, each source one
account:

```json
{
  "$type": "com.rechoraccoon.stellar.home",
  "name": "Follow These Guys",
  "position": 1,
  "spec": "{\"id\":\"hrecs001\",\"name\":\"Follow These Guys\",\"sources\":[{\"kind\":\"account\",\"value\":\"did:plc:aaa…\",\"label\":\"\"},{\"kind\":\"account\",\"value\":\"did:plc:bbb…\",\"label\":\"\"}],\"style\":\"avatars\"}",
  "createdAt": "2026-10-22T16:00:00.000Z"
}
```

Header style, at record key `settings`:

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

Folders are private by default, device-only. Flipping one to Public
also writes it, unencrypted, as one record on the owner's PDS. It
then shows on their profile (Collections → Folders) and works as a
Home tab row (§2).

- One record per folder; the record key **is the folder's id**.
- Making it private again (or deleting it) deletes the record.

### Fields

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.folder"`. |
| `name` | string | The folder's name (read as "Folder" if blank). |
| `posts` | array of strings | Posts in the folder, as `at://` URIs, newest first. **Max 1000** (the app writes at most that many; non-`at://` entries are ignored on read). |
| `cover` | string | Optional. Cover picture as an `https://` URL (a post's public thumbnail). Only `https://` URLs are written or read, never a device file. Omitted when there is none. |
| `layout` | string | How the folder opens: `"grid"` (default), `"vertical"` (tall 9:16 tiles), `"horizontal"` (wide 16:9 videos with text). Only written when not `"grid"`. |
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

One record per emoji in the account's library, on the owner's PDS.
The library is public: anyone can read it, and the emoji work on
every device. Records are not encrypted.

- The record key is **assigned by the PDS at creation**
  (`createRecord`, below). Keys are timestamps, so key order is the
  order emoji were added.
- The same picture is the same emoji everywhere: the record carries
  a hash of the picture, and the app matches on it. Uploading the
  same picture twice makes no second emoji. Deleting the local copy
  deletes the record.

### Fields

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.emoji"`. |
| `name` | string | Display name (read as `"emoji"` if blank). |
| `image` | blob | Picture, as a standard blob object (see §1 "Video blobs" for the shape). `image/png`, or `image/gif` or animated `image/webp` for an animated emoji. |
| `hash` | string | FNV-1a 64-bit hash of the picture bytes, 16 lowercase hex characters, zero-padded left. This is the emoji's identity for syncing. |
| `folder` | string | Optional. Folder the emoji sits in, by the folder's **name** (not an id). Omitted when only in All. |
| `createdAt` | string | ISO 8601. |

There is **no `animated` field on the record**. Animation is read
from the picture: `image.mimeType == "image/gif"` or animated
`image/webp`. `animated: true` appears only where something
*points at* the emoji (a status's `emoji` list, a post or DM
facet), and only when the picture is animated.


### How the library is arranged — `com.rechoraccoon.stellar.emojiLayout`

One record per account, key `self`, on the owner's PDS. It says how
the library is arranged: the order of All, the order of the folders,
and the order inside each folder, plus which emoji sit in which
folders. The pictures themselves are still the per-emoji records
above (the "All" database); this record only says where each one
sits. It is keyed by picture hash, not emoji id, because ids differ
between devices and hashes don't.

| Field | Type | Meaning |
|---|---|---|
| `$type` | string | Always `"com.rechoraccoon.stellar.emojiLayout"`. |
| `data` | string | The arrangement as JSON text (not a nested object): `{"all": ["<hash>", …], "folders": [{"name": "<folder>", "emoji": ["<hash>", …]}, …]}`. An emoji can appear in any number of folders. |
| `updatedAt` | string | ISO 8601. |

Written to: `at://<did>/com.rechoraccoon.stellar.emojiLayout/self`

How sync treats it:

- No layout saved yet: this device's arrangement becomes the saved one.
- First time a device meets a saved layout: the two are merged, so
  nothing is lost (folders and folder membership from both sides).
- After that, whichever side changed since the last sync wins: a
  layout changed on another device is taken; a layout changed here
  is uploaded.
- When a layout exists, it decides the folders. A per-emoji record's
  `folder` field is only read for libraries that have no layout yet.
- Folders match by name. A local folder with no match is reused for
  a layout folder with no match (a rename); any folder still left
  over is deleted, and its emoji stay in All.
- Deleting an emoji from a folder only takes it out of that folder.
  Deleting a folder keeps its emoji in All. Only deleting from All
  deletes the emoji everywhere, including other devices (its record
  goes, and sync removes it from devices that still have it).

### Creating one

1. Upload the picture with `POST /xrpc/com.atproto.repo.uploadBlob`:
   `image/png`, or `image/gif` or animated `image/webp` for an
   animated emoji. Keep the returned blob object.
2. Compute the hash: FNV-1a 64-bit over the picture bytes (offset
   basis `0xcbf29ce484222325`, prime `0x100000001b3`), rendered as
   16 lowercase hex characters, zero-padded left.
3. Create the record with `POST /xrpc/com.atproto.repo.createRecord`,
   body: `{ "repo": "<did>", "collection":
   "com.rechoraccoon.stellar.emoji", "record": { … } }`. The PDS
   assigns the key. The response URI's last segment is the record
   key, and the blob CID from step 1 is the picture's CID.
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

Emoji sit in text as an invisible marker (U+2060 WORD JOINER) where
the emoji goes, plus a pointer saying which emoji it is:

- **In a status**: the text carries the marker, and the status's
  `emoji` list points at it: `at` (character index of the marker),
  `did` (library owner), `cid` (picture blob CID), `name`,
  `animated` (only when the picture is animated: GIF or animated WebP).
- **In a post or comment**: the text carries the marker, and the
  post's facets carry one facet of type
  `com.rechoraccoon.stellar.richtext.facet#emoji` over it (UTF-8 byte
  offsets), with `did`, `rkey`, `cid`, `name` (and `animated` when the picture is animated). The `app.bsky.richtext.facet` features union is open, so a
  PDS accepts a post facet with this custom `$type`. Stellar draws
  the picture there; other apps show the post without the emoji
  pictures.
- **In a DM**: the same marker, same facet type and fields. If the
  chat service refuses the facets, the message goes as `":name:"`
  text instead.
- **Reading anyone's library**: `listRecords` on their PDS, no
  sign-in needed. Records come back newest first; sort by key for
  add order.
- **Deleting**: `deleteRecord` on the collection and key. (The app's
  sync also deletes the record when the emoji leaves the owner's
  device library.)

### Common mistakes

- **Re-uploading the same picture to "make" an emoji that already
  exists.** The hash matches, so the app treats it as the same
  emoji. Check the library first.
- **Using a folder id for `folder`.** It is the folder's name.
- **Expecting an `animated` field on the record.** There isn't one;
  animation is read from the picture's mimeType (`image/gif` or
  animated `image/webp`).
- **Writing the record with a hand-picked key via `putRecord`.** The
  app creates with `createRecord` and lets the PDS assign a
  timestamp key; keys double as the library order.

---

## 5. Integrating into your app

To render Stellar customization for an account:

1. **Profile style.** Fetch `com.rechoraccoon.stellar.profile/self`.
   No record means defaults (round icon, no effect, color from the
   banner and picture). Otherwise apply `iconShape`, `effect`, and
   `colors[0]`; substitute `bannerVideo` for the banner and
   `iconVideo` for the profile picture where present; show `status`
   while `expiresAt` is in the future (or unset). Unknown effect ids
   mean no effect.
2. **Home tab.** List `com.rechoraccoon.stellar.home`, sort rows by
   `position` (no position goes last), render each row's parsed
   `spec`. Read `settings` for header style. Drop `kind: "folder"`
   sources on anyone else's profile; keep `publicFolder` for
   everyone.
3. **Public folders.** List `com.rechoraccoon.stellar.folder`,
   newest `updatedAt` first. Resolve each folder's `posts` URIs
   through the usual post endpoints. Open a folder in its `layout`
   where you have an equivalent, or a plain grid otherwise.
4. **Custom emoji.** List `com.rechoraccoon.stellar.emoji` to build
   the library (key order is add order; `hash` dedupes identical
   pictures). To render emoji in text, replace each U+2060 marker
   with the picture from the facet or status entry pointing at it,
   resolved by `did` + `cid` (or `did` + `rkey`). Where a pointer
   can't resolve, drop the marker and show nothing, or fall back to
   `":name:"` text.
5. **Fallbacks.** Treat every unknown field, effect id, style, or
   layout value as default. Never fail a whole profile, row, or
   folder over one field you don't recognize.

## 6. Writing these records (for agents)

1. Authenticate as the account (an app password session works) and
   use its PDS's XRPC endpoints.
2. **Blobs first.** Upload any video or picture with
   `POST /xrpc/com.atproto.repo.uploadBlob`, then put the returned
   blob object (`{"$type":"blob","ref":{"$link":…},"mimeType":…,"size":…}`)
   into `bannerVideo`, `iconVideo`, or `image`.
3. **Emoji via `createRecord`.** Follow §4's four steps. The PDS
   assigns the key.
4. **Everything else via `putRecord` with `"validate": false`**,
   body: `{ "repo": "<did>", "collection": "<collection>", "rkey": "<key>", "record": { … } }`.
5. **Remove a customization by deleting its record**
   (`com.atproto.repo.deleteRecord`). Deleting the profile `self`
   record resets everything to default at once.
6. **To change one thing in the profile style, read the current
   record first** (`com.atproto.repo.getRecord`), modify it, and
   write the whole record back. `putRecord` replaces the record
   completely.

### Common mistakes

- **Forgetting `validate: false`.** The write fails; these are
  custom lexicons with no published schema on the PDS.
- **Double-encoding the Home `spec`.** It is a *string containing
  JSON*, not a nested object. A nested object is ignored and the
  row is skipped.
- **Using `kind: "folder"` for a public row.** Those sources drop
  for everyone but the owner. Public rows need `publicFolder` with
  `"<folder owner's did>/<folder id>"`.
- **8-digit colors.** `colors` entries must be exactly `#RRGGBB`
  (6 hex digits) or they are ignored.
- **A `cover` that is not `https://`.** Ignored on read; the app
  never writes one (covers come from posts' public thumbnails).
- **Expecting other apps to show any of this.** Only Stellar reads
  these collections.

---

*Source of truth: Stellar's code — `util/ProfileStyles.kt`,
`util/ProfileHome.kt`, `util/PublicFolders.kt`, `util/LocalData.kt`
(`LocalFeed`), `util/EmojiCloud.kt`, and
`repository/BlueskyRepository.kt` (`saveProfileStyle` /
`parseProfileStyle`, `createEmojiRecord` / `listEmojiRecords`).*
