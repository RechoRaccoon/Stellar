#!/usr/bin/env python3
"""Lists every user-facing string in Stellar's shared code (the text the
Language setting translates).

    python3 tools/i18n/extract.py           # JSON: {"strings": [...], "templates": [...]}
    python3 tools/i18n/extract.py --check   # what each language file is missing

Templates are strings with $-interpolations, written with {0}, {1}, …
Strings that look like text but aren't shown to anyone (keys, formats,
log lines) are listed in not_ui.txt and left out."""
import os, re, json, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', '..', 'shared', 'src')
ROOTS = [os.path.join(SRC, s, 'kotlin') for s in ('commonMain', 'nonWebMain', 'androidMain', 'iosMain', 'desktopMain', 'wasmJsMain')]
I18N = os.path.join(SRC, 'commonMain', 'composeResources', 'files', 'i18n')

def kotlin_strings(src):
    """Yields (literal, has_template) for each ordinary "..." string (not raw)."""
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3; continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1; continue
        if c == '"':
            j = i + 1; parts = []; tmpl = False; idx = 0
            while j < n and src[j] != '"':
                if src[j] == '\\':
                    esc = src[j + 1]
                    parts.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$', "'": "'"}.get(esc, ''))
                    if esc == 'u':
                        parts[-1] = chr(int(src[j + 2:j + 6], 16)); j += 6; continue
                    j += 2; continue
                if src[j] == '$' and j + 1 < n and src[j + 1] == '{':
                    depth = 1; k = j + 2
                    while k < n and depth:
                        if src[k] == '{': depth += 1
                        elif src[k] == '}': depth -= 1
                        elif src[k] == '"':  # nested string inside the expression
                            k += 1
                            while k < n and src[k] != '"':
                                k += 2 if src[k] == '\\' else 1
                        k += 1
                    parts.append('{%d}' % idx); idx += 1; tmpl = True; j = k; continue
                if src[j] == '$' and j + 1 < n and (src[j + 1].isalpha() or src[j + 1] == '_'):
                    k = j + 1
                    while k < n and (src[k].isalnum() or src[k] == '_'): k += 1
                    # $a.b.c property chains count as one value
                    while k + 1 < n and src[k] == '.' and (src[k + 1].isalpha()):
                        k += 1
                        while k < n and (src[k].isalnum() or src[k] == '_'): k += 1
                    parts.append('{%d}' % idx); idx += 1; tmpl = True; j = k; continue
                parts.append(src[j]); j += 1
            yield ''.join(parts), tmpl, src[max(0, i - 120):i]
            i = j + 1; continue
        i += 1

NOT_UI = re.compile(r'^(https?:|at://|did:|app\.bsky|com\.atproto|chat\.bsky|[a-z0-9_.\-/]+$|[A-Z0-9_]+$|#?[0-9A-Fa-f]{6,8}$|.*\.(png|jpg|gif|mp4|json|ttf|otf|m3u8)$)')
CODE_CONTEXT = re.compile(r'(Key\(|PreferencesKey\(|getString\(|putString\(|getBoolean\(|putBoolean\(|getInt\(|putInt\(|getLong\(|putLong\(|Regex\(|\.startsWith\(|\.endsWith\(|\.contains\(|split\(|Log\.[dewiv]\(|optString\(|optInt\(|optJSONArray\(|optJSONObject\(|optBoolean\(|optLong\(|getJSONObject\(|getJSONArray\(|\.put\(|header\(|SerialName\(|\.equals\(|== |!= |jformat|replace\()\s*$')

TECHNICAL = re.compile(
    r'^(SELECT|INSERT|CREATE|UPDATE|DELETE FROM|ALTER|DROP|FROM|WHERE|PRAGMA)\b'   # SQL
    r'|^[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+$'                                    # CamelCase tags / class names
    r'|^[A-Za-z_]+(?:\.[A-Za-z_0-9]+)+$'                                          # dotted keys
    r'|^[A-Za-z]+_[A-Za-z_]+$'                                                    # snake_case keys
    r'|^[a-z][\w\-]*[:@]$'                                                        # "dm:", "launchpad@"
    r'|^(Mozilla|Stellar|MediaViewer)/'                                           # user agents
    r'|[{};]\s*$|^\(?function|^return '                                           # code
    r'|^(format|transpose|scale)=|^[a-z]+,[a-z]+$'                                # FFmpeg filters
    r'|^(AppData|Library)/|^Stellar/Cache$'                                       # folders
    r'|^(attributes|extensions|extras|humanoid|indices|textureProperties|vectorProperties)\.'
)

def ui_like(s):
    t = s.strip()
    if TECHNICAL.search(t): return False
    if len(t) < 2 or not re.search(r'[A-Za-z]{2}', t): return False
    if NOT_UI.match(t): return False
    if re.fullmatch(r'[a-z][A-Za-z0-9]*', t): return False   # identifiers
    if re.fullmatch(r'[\w.\-]+:[\w.\-:/]+', t): return False # ns:id
    if '://' in t or t.startswith('/') or t.startswith('xrpc'): return False
    return True

NOT_UI_FILE = os.path.join(HERE, 'not_ui.txt')
not_ui = set()
if os.path.exists(NOT_UI_FILE):
    not_ui = {l.rstrip('\n').replace('\\n', '\n') for l in open(NOT_UI_FILE, encoding='utf8') if l.strip() and not l.startswith('# ')}

strings, templates = set(), set()
for root in ROOTS:
    for r, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith('.kt'): continue
            src = open(os.path.join(r, f), encoding='utf8').read()
            for lit, tmpl, before in kotlin_strings(src):
                if CODE_CONTEXT.search(before.replace('\n', ' ')): continue
                if not ui_like(re.sub(r'\{\d+\}', '', lit)): continue
                if lit in not_ui: continue
                if tmpl:
                    letters = re.sub(r'\{\d+\}', '', lit)
                    if len(re.findall(r'[A-Za-z]', letters)) >= 3: templates.add(lit)
                else:
                    strings.add(lit)

if '--check' in sys.argv:
    want = strings | templates
    for f in sorted(os.listdir(I18N)):
        have = json.load(open(os.path.join(I18N, f), encoding='utf8'))
        missing = sorted(want - set(have))
        stale = sorted(set(have) - want)
        print('%s: %d translated, %d missing, %d no longer used' % (f, len(want) - len(missing), len(missing), len(stale)))
        for m in missing[:20]: print('   missing:', repr(m))
    sys.exit(0)
json.dump({'strings': sorted(strings), 'templates': sorted(templates)}, sys.stdout, ensure_ascii=False, indent=0)
