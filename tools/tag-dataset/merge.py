#!/usr/bin/env python3
"""Builds Stellar's shared tag dataset from dataset exports.

    python3 tools/tag-dataset/merge.py <exports folder> <output folder>

Every file in the exports folder (Stellar's "Export Dataset" files, .json or
.json.gz) is merged into one dataset: a post is in it if any file has it, a
tag is on a post if any file gives the post that tag, and each post and tag
appears once (a tag keeps its highest score). The output is rebuilt from
the files present every time, so removing a file removes exactly what only
that file contributed.

Output (what the app downloads, from the repo's `tag-dataset` branch):
  manifest.json   the version (a hash of the content), counts, and the parts
  part-000.json … the posts, split into parts of about PART_BYTES each:
                  [["at://…/post/…", "cid", "media url", [["tag", 0.93], …]], …]
"""
import gzip, hashlib, json, os, sys, time

PART_BYTES = 8_000_000


def read_export(path):
    raw = open(path, 'rb').read()
    if path.endswith('.gz') or raw[:2] == b'\x1f\x8b':
        raw = gzip.decompress(raw)
    data = json.loads(raw.decode('utf-8'))
    posts = data.get('posts') if isinstance(data, dict) else None
    if not isinstance(posts, list):
        raise ValueError('not a Stellar dataset export (no "posts" list)')
    return data.get('name') or os.path.basename(path), posts


def main(src, out):
    files = sorted(f for f in os.listdir(src) if f.endswith('.json') or f.endswith('.json.gz')) if os.path.isdir(src) else []
    merged = {}   # post uri -> [cid, media url, {tag: score}]
    sources = []
    for f in files:
        try:
            name, posts = read_export(os.path.join(src, f))
        except Exception as e:  # a broken file is reported and left out, never fatal
            print(f'::warning::{f} was left out: {e}')
            continue
        count = 0
        for p in posts:
            uri = (p.get('postUri') or '').strip()
            if not uri:
                continue
            entry = merged.setdefault(uri, ['', '', {}])
            entry[0] = entry[0] or (p.get('cid') or '')
            entry[1] = entry[1] or (p.get('mediaUrl') or '')
            for t in p.get('tags') or []:
                tag = (t.get('name') or '').strip()
                if not tag:
                    continue
                score = float(t.get('confidence') or 0)
                if score > entry[2].get(tag, -1.0):
                    entry[2][tag] = round(score, 4)
            count += 1
        sources.append({'file': f, 'name': name, 'posts': count})
        print(f'{f}: {count} posts')

    rows = []
    for uri in sorted(merged):
        cid, url, tags = merged[uri]
        rows.append([uri, cid, url, sorted(([k, v] for k, v in tags.items()), key=lambda kv: (-kv[1], kv[0]))])

    os.makedirs(out, exist_ok=True)
    parts, current, size = [], [], 0
    def flush():
        nonlocal current, size
        if not current:
            return
        name = 'part-%03d.json' % len(parts)
        body = json.dumps(current, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        open(os.path.join(out, name), 'wb').write(body)
        parts.append({'file': name, 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()})
        current, size = [], 0
    for row in rows:
        line = len(json.dumps(row, ensure_ascii=False, separators=(',', ':')).encode('utf-8')) + 1
        if current and size + line > PART_BYTES:
            flush()
        current.append(row)
        size += line
    flush()

    version = hashlib.sha256(''.join(p['sha256'] for p in parts).encode()).hexdigest()[:16]
    manifest = {
        'format': 1,
        'version': version,
        'updatedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'posts': len(rows),
        'tags': sum(len(r[3]) for r in rows),
        'bytes': sum(p['bytes'] for p in parts),
        'parts': parts,
        'sources': sources,
    }
    json.dump(manifest, open(os.path.join(out, 'manifest.json'), 'w'), indent=1)
    print(f'{len(rows)} posts, {manifest["tags"]} tags, {len(parts)} parts, version {version}')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
