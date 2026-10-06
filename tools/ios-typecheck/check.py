#!/usr/bin/env python3
"""Compile-checks Stellar's shared Kotlin for iOS without a Mac.

    python3 tools/ios-typecheck/check.py            # from the repo root

What it does: hands the Kotlin/Native compiler (target ios_arm64) the
source code of every library the shared module uses together with
shared/src/commonMain and shared/src/iosMain, as one module, and prints
only the errors that are in Stellar's own files. Takes about two minutes.

What it catches: everything the compiler's front end reports — unresolved
names, wrong types, missing imports, wrong Apple API names/signatures
(the real iOS platform libraries are used), @Composable misuse,
kotlinx.serialization problems.

What it can't see: Swift files, Android-only files (androidMain), anything
that only fails when linking, and how the app behaves when run. CI is
still the final word.

The libraries are compiled from source rather than downloaded as binaries
because only GitHub is reachable from these sessions. Their own errors
(about a thousand: missing platform halves, opt-ins) are expected and
ignored. Material icons and the generated Res class aren't in any of
those sources, so small stand-ins are generated from what the app uses.

Run setup.sh once first.
"""
import os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(HERE))
TC = os.environ.get('STELLAR_TC_HOME', os.path.expanduser('~/.stellar-typecheck'))
SRC, KN, KC = TC + '/src', TC + '/kotlin-native', TC + '/kotlinc'
OUT = TC + '/out'
GEN = OUT + '/gen'
if not os.path.isdir(KN) or not os.path.isdir(SRC + '/cmc'):
    sys.exit('Run tools/ios-typecheck/setup.sh first (nothing found in %s).' % TC)
os.makedirs(GEN, exist_ok=True)

# ── library modules ──────────────────────────────────────────────────
C = SRC + '/cmc/'
mods = [C + m for m in '''annotation/annotation collection/collection compose/runtime/runtime compose/runtime/runtime-saveable
compose/ui/ui compose/ui/ui-geometry compose/ui/ui-graphics compose/ui/ui-text compose/ui/ui-unit compose/ui/ui-util
compose/ui/ui-backhandler compose/ui/ui-uikit compose/animation/animation compose/animation/animation-core
compose/foundation/foundation compose/foundation/foundation-layout compose/material/material-ripple compose/material3/material3
lifecycle/lifecycle-common lifecycle/lifecycle-runtime lifecycle/lifecycle-runtime-compose lifecycle/lifecycle-viewmodel
lifecycle/lifecycle-viewmodel-compose lifecycle/lifecycle-viewmodel-savedstate savedstate/savedstate
datastore/datastore-core datastore/datastore-core-okio datastore/datastore-preferences-core'''.split()]
mods += [SRC + '/atomicfu/atomicfu', SRC + '/skiko/skiko', SRC + '/okio/okio', SRC + '/cmp/components/resources/library',
         SRC + '/serialization/core', SRC + '/serialization/formats/json']
mods += [SRC + '/coil/' + m for m in 'coil-core coil-compose coil-compose-core coil-network-core coil-network-ktor3 coil'.split()]
# Modules whose source sets are <name>/src instead of src/<name>.
flat = [SRC + '/kxio/core', SRC + '/kxio/bytestring'] + [SRC + '/ktor/' + m for m in
        '''ktor-client/ktor-client-core ktor-client/ktor-client-darwin ktor-http ktor-utils ktor-io ktor-shared/ktor-events
        ktor-shared/ktor-websockets ktor-shared/ktor-sse ktor-shared/ktor-serialization'''.split()]
# Source sets that are NOT part of an iOS build.
DENY = re.compile(r'^(android|jvm(?!AndPosix)|nativeNonApple|node|api$|build|desktop|js(?!Native)|wasm|web|linux|mingw|macos|tvos|awt|'
                  r'posix|main$|nativeInterop|notMobile|nonApple|nonNative|jsCommon|.*Tests?$|test)')

common, plat = [], []
def walk(d, acc):
    for r, _, fs in os.walk(d):
        acc.extend(os.path.join(r, f) for f in fs if f.endswith('.kt'))

for m in mods:
    s = m + '/src' if os.path.isdir(m + '/src') else m
    if not os.path.isdir(s):
        print('missing library source:', m); continue
    for ss in sorted(os.listdir(s)):
        d = os.path.join(s, ss)
        if os.path.isdir(d):
            if ss == 'commonMain': walk(d, common)
            elif not DENY.match(ss): walk(d, plat)
for m in flat:
    if not os.path.isdir(m):
        print('missing library source:', m); continue
    for ss in sorted(os.listdir(m)):
        d = os.path.join(m, ss, 'src')
        if os.path.isdir(d):
            if ss == 'common': walk(d, common)
            elif not DENY.match(ss): walk(d, plat)
co = SRC + '/coroutines/kotlinx-coroutines-core/'
walk(co + 'common/src', common)
for x in ('concurrent', 'native', 'nativeDarwin'): walk(co + x + '/src', plat)

# ── generated stand-ins: Res accessors and the material icons in use ──
for f in os.listdir(GEN): os.remove(os.path.join(GEN, f))
rd = APP + '/shared/src/commonMain/composeResources'
g = ['@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)', 'package com.mediaviewer.resources',
     'import org.jetbrains.compose.resources.*', 'object Res { object drawable; object font; object string }']
for kind, typ in (('drawable', 'DrawableResource'), ('font', 'FontResource')):
    for f in sorted(os.listdir(rd + '/' + kind)) if os.path.isdir(rd + '/' + kind) else []:
        g.append('val Res.%s.%s: %s get() = TODO()' % (kind, f.rsplit('.', 1)[0], typ))
open(GEN + '/Res.kt', 'w').write('\n'.join(g))

appc, appi = [], []
walk(APP + '/shared/src/commonMain/kotlin', appc)
walk(APP + '/shared/src/iosMain/kotlin', appi)
alltext = '\n'.join(open(f, encoding='utf8').read() for f in appc + appi)
styles = ['Filled', 'Outlined', 'Rounded', 'Sharp', 'TwoTone']
icons = set()
for m in re.finditer(r'material\.icons\.((?:automirrored\.)?[a-z]+)\.([A-Z]\w*)', alltext): icons.add((m.group(1), m.group(2)))
for m in re.finditer(r'Icons\.(AutoMirrored\.)?(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\.([A-Z]\w*)', alltext):
    st = 'filled' if m.group(2) == 'Default' else m.group(2).lower()
    icons.add((('automirrored.' if m.group(1) else '') + st, m.group(3)))
base = ['package androidx.compose.material.icons', 'object Icons {'] + ['  object %s' % s for s in styles] + \
       ['  val Default = Filled', '  object AutoMirrored {'] + ['    object %s' % s for s in styles] + ['    val Default = Filled', '  }', '}']
open(GEN + '/Icons.kt', 'w').write('\n'.join(base))
by = {}
for p, n in icons: by.setdefault(p, set()).add(n)
for p, ns in by.items():
    st = p.split('.')[-1]; st = {'twotone': 'TwoTone'}.get(st, st.capitalize())
    recv = ('Icons.AutoMirrored.' if p.startswith('automirrored.') else 'Icons.') + st
    open(GEN + '/Icons_%s.kt' % p.replace('.', '_'), 'w').write(
        'package androidx.compose.material.icons.%s\nimport androidx.compose.material.icons.Icons\n'
        'import androidx.compose.ui.graphics.vector.ImageVector\n' % p +
        '\n'.join('val %s.%s: ImageVector get() = TODO()' % (recv, n) for n in sorted(ns)))
walk(GEN, common)

# ── one compiler run ─────────────────────────────────────────────────
# Four layers, each seeing the ones before it — the same expect/actual
# structure Gradle builds, flattened.
frs = [('libCommon', common), ('libPlatform', plat), ('appCommon', appc), ('appIos', appi)]
args = ['-target', 'ios_arm64', '-p', 'library', '-o', OUT + '/check', '-Xmulti-platform', '-nowarn', '-Xsuppress-version-warnings',
        '-Xfragments=' + ','.join(n for n, _ in frs),
        '-Xfragment-refines=libPlatform:libCommon,appCommon:libPlatform,appIos:appCommon',
        '-Xplugin=' + KC + '/lib/compose-compiler-plugin.jar', '-Xplugin=' + KC + '/lib/kotlinx-serialization-compiler-plugin.jar',
        '-Xexpect-actual-classes', '-Xcontext-receivers', '-Xallow-kotlin-package', '-opt-in=kotlin.RequiresOptIn', '-Xskip-prerelease-check']
# The opt-ins shared/build.gradle.kts turns on for every source set.
for o in ('androidx.compose.material3.ExperimentalMaterial3Api', 'kotlinx.serialization.ExperimentalSerializationApi',
          'kotlin.io.encoding.ExperimentalEncodingApi', 'kotlinx.cinterop.ExperimentalForeignApi'):
    args.append('-opt-in=' + o)
for n, fl in frs:
    args += ['-Xfragment-sources=%s:%s' % (n, f) for f in fl]
for n, fl in frs: args += fl
open(OUT + '/args', 'w').write('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"' for a in args))
print('files:', {n: len(fl) for n, fl in frs}, flush=True)

# (The plain compiler jar, not the "embeddable" one konanc uses: the two
# compiler plugins above only load next to the plain one.)
cp = ':'.join([KN + '/konan/lib/kotlin-native.jar', KN + '/konan/lib/trove4j.jar', KC + '/lib/kotlin-compiler.jar'])
t = time.time()
p = subprocess.run(['java', '-Xmx5600m', '-Xss64m', '-Dkonan.home=' + KN, '-Dfile.encoding=UTF-8', '-cp', cp,
                    'org.jetbrains.kotlin.cli.utilities.MainKt', 'konanc', '@' + OUT + '/args'],
                   capture_output=True, text=True, cwd='/')
log = p.stdout + p.stderr
open(OUT + '/log.txt', 'w').write(log)
lines = log.split('\n')
errs = [i for i, l in enumerate(lines) if ': error:' in l]
if not errs:
    # No errors at all would mean the libraries compiled cleanly too, which
    # they never do: the compiler itself didn't get as far as checking.
    print(log[-3000:]); sys.exit('The compiler did not run to the end — see %s/log.txt' % OUT)
# (The compiler prints paths relative to where it runs — "/" here — so
# the app's files show up without their leading slash.)
rel = APP.lstrip('/') + '/'
mine = [i for i in errs if rel in lines[i].split(': error:')[0]]
print('%d seconds; %d errors in Stellar\'s files' % (time.time() - t, len(mine)))
for i in mine:
    print(lines[i].replace(rel, ''))
    if i + 1 < len(lines) and ': error:' not in lines[i + 1]: print('    ' + lines[i + 1].strip())
sys.exit(1 if mine else 0)
