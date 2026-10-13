#!/usr/bin/env python3
"""Compile-checks Stellar's shared Kotlin for the desktop (Windows / macOS) build.

    sh tools/ios-typecheck/setup.sh                # once: compiler + library sources
    sh tools/desktop-typecheck/setup.sh            # once: a few JVM jars
    python3 tools/desktop-typecheck/check.py       # from the repo root

The desktop twin of tools/ios-typecheck: the Kotlin/JVM compiler gets the
source code of every library the shared module uses together with
shared/src/commonMain and shared/src/desktopMain, as one module, and only
the errors in Stellar's own files are printed. Plain Java libraries
(OkHttp, org.json, JavaCV/FFmpeg) are given to it as jars instead.

What it catches: everything the compiler's front end reports. What it
can't see: anything that only fails when linking or running, and Gradle
itself. CI (.github/workflows/desktop.yml) is still the final word.
"""
import os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(HERE))
TC = os.environ.get('STELLAR_TC_HOME', os.path.expanduser('~/.stellar-typecheck'))
SRC, KC, JARS = TC + '/src', TC + '/kotlinc', TC + '/jars'
OUT = TC + '/out-desktop'
GEN = OUT + '/gen'
if not os.path.isdir(KC) or not os.path.isdir(SRC + '/cmc'):
    sys.exit('Run tools/ios-typecheck/setup.sh first (nothing found in %s).' % TC)
if not os.path.isdir(JARS):
    sys.exit('Run tools/desktop-typecheck/setup.sh first (no %s).' % JARS)
os.makedirs(GEN, exist_ok=True)

# ── library modules ──────────────────────────────────────────────────
C = SRC + '/cmc/'
mods = [C + m for m in '''annotation/annotation collection/collection compose/runtime/runtime compose/runtime/runtime-saveable
compose/ui/ui compose/ui/ui-geometry compose/ui/ui-graphics compose/ui/ui-text compose/ui/ui-unit compose/ui/ui-util
compose/ui/ui-backhandler compose/animation/animation compose/animation/animation-core
compose/foundation/foundation compose/foundation/foundation-layout compose/material/material-ripple compose/material3/material3
lifecycle/lifecycle-common lifecycle/lifecycle-runtime lifecycle/lifecycle-runtime-compose lifecycle/lifecycle-viewmodel
lifecycle/lifecycle-viewmodel-compose lifecycle/lifecycle-viewmodel-savedstate savedstate/savedstate
datastore/datastore-core datastore/datastore-core-okio datastore/datastore-preferences-core'''.split()]
mods += [SRC + '/atomicfu/atomicfu', SRC + '/skiko/skiko', SRC + '/cmp/components/resources/library',
         SRC + '/serialization/core', SRC + '/serialization/formats/json']
mods += [SRC + '/coil/' + m for m in 'coil-core coil-compose-core coil-compose coil-network-core coil-network-okhttp coil'.split()]
# Source sets that ARE part of a desktop (JVM) build.
ALLOW = re.compile(r'^(jvm|desktop|skiko|skikoExcludingWeb|jb|nonAndroid|notMobile|awt|jvmAndAndroid|commonJvm|jvmCommon|'
                   r'nonJsCommon|nonNative|nonJs|blocking)Main$')

common, plat = [], []
def walk(d, acc):
    for r, _, fs in os.walk(d):
        acc.extend(os.path.join(r, f) for f in fs if f.endswith('.kt') or (f.endswith('.java') and f != 'module-info.java'))

for m in mods:
    s = m + '/src' if os.path.isdir(m + '/src') else m
    if not os.path.isdir(s):
        print('missing library source:', m); continue
    for ss in sorted(os.listdir(s)):
        d = os.path.join(s, ss)
        if os.path.isdir(d):
            if ss == 'commonMain': walk(d, common)
            elif ALLOW.match(ss): walk(d, plat)
co = SRC + '/coroutines/kotlinx-coroutines-core/'
walk(co + 'common/src', common)
for x in ('concurrent', 'jvm'): walk(co + x + '/src', plat)

# ── generated stand-ins: Res accessors and the material icons in use ──
for f in os.listdir(GEN): os.remove(os.path.join(GEN, f))
rd = APP + '/shared/src/commonMain/composeResources'
g = ['@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)', 'package com.mediaviewer.resources',
     'import org.jetbrains.compose.resources.*', 'object Res { object drawable; object font; object string }']
for kind, typ in (('drawable', 'DrawableResource'), ('font', 'FontResource')):
    for f in sorted(os.listdir(rd + '/' + kind)) if os.path.isdir(rd + '/' + kind) else []:
        g.append('val Res.%s.%s: %s get() = TODO()' % (kind, f.rsplit('.', 1)[0], typ))
open(GEN + '/Res.kt', 'w').write('\n'.join(g))

appc, appd = [], []
walk(APP + '/shared/src/commonMain/kotlin', appc)
walk(APP + '/shared/src/desktopMain/kotlin', appd)
walk(APP + '/desktopApp/src', appd)
alltext = '\n'.join(open(f, encoding='utf8').read() for f in appc + appd)
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
frs = [('libCommon', common), ('libPlatform', plat), ('appCommon', appc), ('appDesktop', appd)]
jars = [os.path.join(JARS, f) for f in sorted(os.listdir(JARS)) if f.endswith('.jar')]
args = ['-d', OUT + '/classes', '-Xmulti-platform', '-nowarn', '-Xsuppress-version-warnings', '-jvm-target', '17',
        '-classpath', ':'.join(jars), '-no-reflect',
        '-Xfragments=' + ','.join(n for n, _ in frs),
        '-Xfragment-refines=libPlatform:libCommon,appCommon:libPlatform,appDesktop:appCommon',
        '-Xplugin=' + KC + '/lib/compose-compiler-plugin.jar', '-Xplugin=' + KC + '/lib/kotlinx-serialization-compiler-plugin.jar',
        '-Xexpect-actual-classes', '-Xcontext-receivers', '-Xallow-kotlin-package', '-opt-in=kotlin.RequiresOptIn', '-Xskip-prerelease-check']
for o in ('androidx.compose.material3.ExperimentalMaterial3Api', 'kotlinx.serialization.ExperimentalSerializationApi',
          'kotlin.io.encoding.ExperimentalEncodingApi'):
    args.append('-opt-in=' + o)
for n, fl in frs:
    args += ['-Xfragment-sources=%s:%s' % (n, f) for f in fl if f.endswith('.kt')]
for n, fl in frs: args += fl
open(OUT + '/args', 'w').write('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"' for a in args))
print('files:', {n: len(fl) for n, fl in frs}, flush=True)

t = time.time()
env = dict(os.environ, JAVA_OPTS='-Xmx6g -Xss64m -Dfile.encoding=UTF-8')
p = subprocess.run(['bash', KC + '/bin/kotlinc', '@' + OUT + '/args'], capture_output=True, text=True, cwd='/', env=env)
log = p.stdout + p.stderr
open(OUT + '/log.txt', 'w').write(log)
lines = log.split('\n')
errs = [i for i, l in enumerate(lines) if l.startswith('error:') or ': error:' in l]
if not errs:
    print(log[-3000:]); sys.exit('The compiler did not run to the end — see %s/log.txt' % OUT)
rel = APP.lstrip('/') + '/'
mine = [i for i in errs if rel in lines[i].split(': error:')[0]]
print('%d seconds; %d errors in Stellar\'s files (%d in all)' % (time.time() - t, len(mine), len(errs)))
for i in mine:
    print(lines[i].replace(rel, ''))
    if i + 1 < len(lines) and ': error:' not in lines[i + 1]: print('    ' + lines[i + 1].strip())
sys.exit(1 if mine else 0)
