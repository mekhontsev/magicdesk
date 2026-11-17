#!/usr/bin/env python3
"""Lab-only partial KWin build; reuse cached source/configuration, never build KWin."""
import hashlib
import json
import os
import re
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import time

root = Path(__file__).resolve().parent
profile = json.loads((root / 'module-profile.json').read_text())
version = profile['version']
new_backend = version == '6.6.6'
cache_source = Path(profile['source'])
cache_build = Path(profile['build'])
recorded_source = profile.get('recordedSource', str(cache_source))
recorded_build = profile.get('recordedBuild', str(cache_build))
source = root / 'source'
objects = root / 'objects'
generated = root / 'generated'
for directory in (source, objects, generated):
    directory.mkdir(exist_ok=True)
report = {'commands': [], 'passed': False, 'profile': profile}
(root / 'module-result.json').write_text(json.dumps(report) + '\n')
original = Path(profile['stock'])
assert hashlib.sha256(original.read_bytes()).hexdigest() == profile['sha256']
notes_env = {**os.environ, 'LC_ALL': 'C'}
branch_protection = (['-mbranch-protection=standard'] if 'aarch64 feature: bti' in
                     subprocess.check_output(['readelf', '-n', str(original)], text=True,
                                             env=notes_env).lower() else [])
report['branchProtection'] = 'bti-pac' if branch_protection else 'baseline'


def run(args, *, capture=False):
    start = time.monotonic()
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    report['commands'].append({'args': args, 'seconds': time.monotonic() - start,
                               'returncode': result.returncode, 'output': result.stdout})
    (root / 'module-result.json').write_text(json.dumps(report, indent=2) + '\n')
    if result.returncode or not capture:
        print(result.stdout[-5000:], end='', flush=True)
    if result.returncode:
        raise RuntimeError('Command failed: ' + args[0])
    return result.stdout


rows = json.loads(subprocess.check_output(['ninja', '-C', str(cache_build), '-t', 'compdb']))
rows = {row['file']: row for row in rows if row['command'] and row['output'].endswith('.o')}
core_files = ['backends/wayland/' + name + '.cpp' for name in (
    'dmaheapgraphicsbufferallocator', 'wayland_backend', 'wayland_display',
    'wayland_egl_backend', 'wayland_output', 'wayland_qpainter_backend')]
backend_file = 'opengl/eglbackend' if new_backend else 'platformsupport/scenes/opengl/abstract_egl_backend'
core_files += ['opengl/egldisplay.cpp', backend_file + '.cpp',
               'scene/itemrenderer_opengl.cpp', 'scene/imageitem.cpp',
               'wayland/linuxdmabufv1clientbuffer.cpp']
if new_backend:
    core_files += ['wayland-client/linuxdmabuf.cpp', 'backends/wayland/wayland_layer.cpp',
                   'backends/wayland/color_manager.cpp']
qpa_files = ['plugins/qpa/' + name + '.cpp' for name in (
    'backingstore', 'clipboard', 'eglhelpers', 'eglplatformcontext', 'integration',
    'main', 'offscreensurface', 'platformcursor', 'screen', 'swapchain', 'window')]
for path in (cache_source / 'src').rglob('*.h'):
    target = source / path.relative_to(cache_source / 'src')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, target)
for name in core_files + qpa_files + ['plugins/qpa/kwin.json']:
    shutil.copyfile(cache_source / 'src' / name, source / name)
shutil.copyfile(root / 'kwin-module-state.h', source / 'kwin-module-state.h')
shutil.copyfile(root / 'kwin-module-state.cpp', source / 'kwin-module-state.cpp')
if 'm_clientPointerLocked' not in (source / 'backends/wayland/wayland_backend.h').read_text():
    run(['patch', '--batch', '--fuzz=0', '-p2', '-d', str(source), '-i', str(root / 'pointer.patch')])


def replace(name, old, new, count=1):
    path = source / name
    content = path.read_text()
    assert content.count(old) == count, (name, old, content.count(old))
    path.write_text(content.replace(old, new))


replace('opengl/egldisplay.h', '    bool m_dmaBufSyncRequired = false;\n', '')
replace('opengl/egldisplay.h', '    const bool m_supportsNativeFence;\n',
        '    const bool m_supportsNativeFence;\n    bool m_dmaBufSyncRequired = false;\n')
replace(backend_file + '.h',
        'virtual GraphicsBufferAllocator *graphicsBufferAllocator() const;',
        'GraphicsBufferAllocator *graphicsBufferAllocator() const;')
replace('backends/wayland/wayland_egl_backend.h',
        'GraphicsBufferAllocator *graphicsBufferAllocator() const override;',
        'GraphicsBufferAllocator *graphicsBufferAllocator() const;')
backend_include = backend_file + '.h'
replace(backend_file + '.cpp',
        '#include "' + backend_include + '"',
        '#include "' + backend_include + '"\n#include "backends/wayland/dmaheapgraphicsbufferallocator.h"')
replace(backend_file + '.cpp',
        'return device ? device->allocator() : nullptr;',
        'if (device) return device->allocator();\n'
        '    static Wayland::DmaHeapGraphicsBufferAllocator allocator;\n'
        '    return m_display->dmaBufSyncRequired() && allocator.isValid() ? &allocator : nullptr;')
for declaration in ('    bool m_clientPointerLocked = false;\n',
                    '    bool m_automaticPointerLock = false;\n',
                    '    QMetaObject::Connection m_surfaceConstraintsConnection;\n',
                    '    QMetaObject::Connection m_clientLockConnection;\n'):
    replace('backends/wayland/wayland_backend.h', declaration, '')
name = 'backends/wayland/wayland_backend.cpp'
replace(name, '#include "wayland_backend.h"', '#include "wayland_backend.h"\n#include "kwin-module-state.h"')
for old, new in [('m_clientPointerLocked', 'clientLocked'), ('m_automaticPointerLock', 'automaticLock'),
                 ('m_surfaceConstraintsConnection', 'surfaceConnection'), ('m_clientLockConnection', 'clientConnection')]:
    content = (source / name).read_text()
    replace(name, old, 'binaryPointerState(this).' + new, content.count(old))
replace('scene/itemrenderer_opengl.h', '    std::unordered_set<GraphicsBuffer *> m_dmaBufReads;\n', '')
replace('scene/itemrenderer_opengl.h', '    ItemRendererOpenGL(EglDisplay *eglDisplay);',
        '    ItemRendererOpenGL(EglDisplay *eglDisplay);\n    ~ItemRendererOpenGL() override;')
name = 'scene/itemrenderer_opengl.cpp'
replace(name, '#include "scene/itemrenderer_opengl.h"',
        '#include "scene/itemrenderer_opengl.h"\n#include "kwin-module-state.h"')
replace(name, 'm_dmaBufReads', 'binaryRendererReads(this)', (source / name).read_text().count('m_dmaBufReads'))
replace('wayland_server.h', 'LinuxDmaBufV1ClientBufferIntegration *linuxDmabuf(bool deviceFeedback = true);',
        'LinuxDmaBufV1ClientBufferIntegration *linuxDmabuf();\n'
        '    LinuxDmaBufV1ClientBufferIntegration *linuxDmabuf(bool deviceFeedback);')
replace('wayland/linuxdmabufv1clientbuffer.h',
        'explicit LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback = true);',
        'explicit LinuxDmaBufV1ClientBufferIntegration(Display *display);\n'
        '    LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback);')
replace('wayland/linuxdmabufv1clientbuffer.cpp',
        'LinuxDmaBufV1ClientBufferIntegration::LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback)',
        'LinuxDmaBufV1ClientBufferIntegration::LinuxDmaBufV1ClientBufferIntegration(Display *display)\n'
        '    : LinuxDmaBufV1ClientBufferIntegration(display, true) {}\n\n'
        'LinuxDmaBufV1ClientBufferIntegration::LinuxDmaBufV1ClientBufferIntegration(Display *display, bool deviceFeedback)')
for name in ('plugins/qpa/main.cpp', 'plugins/qpa/kwin.json'):
    replace(name, 'wayland-org.kde.kwin.qpa', 'magicdesk-kwin')


def flags(name):
    row = rows[recorded_source + '/src/' + name]
    args = shlex.split(row['command'])[1:]
    result = ['-I' + str(generated)]
    index = 0
    while index < len(args):
        arg = args[index]
        index += 1
        if arg in ('-o', '-MF', '-MT'):
            index += 1
            continue
        if arg in ('-c', '-MD', '-DQT_STATICPLUGIN', '-O3', '-fvisibility=hidden') or arg == row['file']:
            continue
        arg = arg.replace(recorded_source + '/src', str(source))
        arg = arg.replace(recorded_build, str(cache_build)).replace(recorded_source, str(cache_source))
        result.append(arg)
    return [*result, '-O2', '-fPIC', '-fvisibility=default', *branch_protection,
            '-DMD_KWIN66=' + str(int(new_backend))]


core_flags = flags('backends/wayland/wayland_backend.cpp')
qpa_flags = flags('plugins/qpa/window.cpp')


def compile_file(name, args=None):
    path = Path(name) if Path(name).is_absolute() else source / name
    output = objects / (path.name + '.o')
    command = ['c++', *(args or flags(name)), '-c', str(path), '-o', str(output)]
    identity = hashlib.sha256(path.read_bytes() + shlex.join(command).encode()
                              + b''.join(p.read_bytes() for p in sorted(source.rglob('*.h')))).hexdigest()
    stamp = output.with_suffix('.sha256')
    if not output.exists() or not stamp.exists() or stamp.read_text() != identity:
        print('Compiling fragment ' + path.name, flush=True)
        run(command, capture=True)
        stamp.write_text(identity)
    return str(output)


# Regenerate only the metadata used by these translation units.
for name in core_files + qpa_files:
    cpp = source / name
    header = cpp.with_suffix('.h')
    options = flags(name)
    moc_flags = [arg for arg in options if arg.startswith(('-D', '-I'))]
    # Qt include directories are represented by separate -isystem arguments.
    for index, arg in enumerate(options):
        if arg == '-isystem':
            moc_flags.append('-I' + options[index + 1])
    if header.exists() and 'Q_OBJECT' in header.read_text():
        run([profile['moc'], *moc_flags, str(header), '-o', str(generated / ('moc_' + header.stem + '.cpp'))], capture=True)
    if 'Q_OBJECT' in cpp.read_text():
        run([profile['moc'], *moc_flags, str(cpp), '-o', str(generated / (cpp.stem + '.moc'))], capture=True)
        if cpp.stem + '.moc' not in cpp.read_text():
            cpp.write_text(cpp.read_text() + '\n#include "' + cpp.stem + '.moc"\n')
    for include in re.findall(r'#include "(moc_[^"]+\.cpp)"', cpp.read_text()):
        private_header = cpp.parent / (include[4:-4] + '.h')
        if private_header != header and private_header.exists():
            run([profile['moc'], *moc_flags, str(private_header), '-o', str(generated / include)], capture=True)

core = [compile_file(name) for name in core_files]
for name in core_files:
    cpp = source / name
    moc = generated / ('moc_' + cpp.stem + '.cpp')
    if moc.exists() and moc.name not in cpp.read_text():
        core.append(compile_file(str(moc), flags(name)))
core.append(compile_file('kwin-module-state.cpp', core_flags))
if new_backend:
    # Keep the stock compositor lifecycle; replace only GPU initialization and
    # its file-local crash-information helper, which must allow a no-DRM backend.
    compositor = (cache_source / 'src/compositor.cpp').read_text()
    start = compositor.index('static QVariantHash collectCrashInformation(')
    end = compositor.index('bool Compositor::attemptQPainterCompositing()', start)
    fragment = source / 'compositor-fragment.cpp'
    fragment.write_text(compositor[:compositor.index('namespace KWin')]
                        + '\nnamespace KWin {\n' + compositor[start:end] + '\n}\n')
    core.append(compile_file(str(fragment), flags('compositor.cpp')))
protocol = cache_build / 'src/wayland'
core.append(compile_file(str(protocol / 'qwayland-server-linux-dmabuf-unstable-v1.cpp'), core_flags))
for path in protocol.glob('*-protocol.c'):
    output = objects / (path.name + '.o')
    command = ['cc', '-fPIC', '-O2', *branch_protection, '-c', str(path), '-o', str(output)]
    identity = hashlib.sha256(path.read_bytes() + shlex.join(command).encode()).hexdigest()
    stamp = output.with_suffix('.sha256')
    if not output.exists() or not stamp.exists() or stamp.read_text() != identity:
        run(command, capture=True)
        stamp.write_text(identity)
    core.append(str(output))
dynamic = subprocess.check_output(['readelf', '-d', str(original)], text=True)
needed = re.findall(r'\(NEEDED\).*\[(.*?)\]', dynamic)
rpaths = re.findall(r'\((?:RUNPATH|RPATH)\).*\[(.*?)\]', dynamic)
library_paths = [str(original.parent), *[p for row in rpaths for p in row.split(':')]]
libraries = [*['-L' + p for p in library_paths], '-lkwin', *['-l:' + lib for lib in needed]]
if new_backend:
    libraries += ['-Wl,-rpath,' + ':'.join(library_paths)]
# Compare every shared object's storage against the unpatched release headers.
baseline = root / 'original-headers'
baseline.mkdir(exist_ok=True)
with tarfile.open(profile['archive']) as archive:
    for member in archive:
        prefix = 'kwin-' + version + '/src/'
        if member.isfile() and member.name.startswith(prefix) and member.name.endswith('.h'):
            relative = Path(member.name.removeprefix(prefix))
            assert '..' not in relative.parts
            path = baseline / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.extractfile(member).read())
shutil.copyfile(cache_build / 'src/config-kwin.h', baseline / 'config-kwin.h')
layouts = {}
for label, headers in [('stock', baseline), ('module', source)]:
    options = ['-I' + str(headers), *[arg.replace(str(source), str(headers)) for arg in core_flags]]
    executable = root / ('layout-' + label)
    run(['c++', *options, '-fno-access-control',
         str(root / 'kwin-module-layout.cpp'), '-o', str(executable), *libraries], capture=True)
    layouts[label] = run([str(executable)], capture=True)
report['layouts'] = layouts
(root / 'module-result.json').write_text(json.dumps(report, indent=2) + '\n')
assert layouts['stock'] == layouts['module'], 'Shared C++ object layout changed'
# Qt casts require one shared staticMetaObject identity across the stock library
# and module. Bind local function bodies, not their type metadata or other data.
run(['c++', '-shared', '-Wl,-Bsymbolic-functions', '-Wl,-z,defs', '-o', str(root / 'libmd-kwin-module.so'),
     *core, *libraries])
qpa = [compile_file(name) for name in qpa_files]
for name in qpa_files:
    cpp = source / name
    header = cpp.with_suffix('.h')
    moc_name = 'moc_' + cpp.stem + '.cpp'
    if header.exists() and 'Q_OBJECT' in header.read_text() and moc_name not in cpp.read_text():
        qpa.append(compile_file(str(generated / moc_name), qpa_flags))
qpa.append(compile_file(str(cache_build / 'src/plugins/qpa/logging.cpp'), qpa_flags))
run(['c++', '-shared', '-Wl,-Bsymbolic-functions', '-Wl,-z,defs', '-o', str(root / 'libmd-kwin-qpa.so'),
     *qpa, '-L' + str(root), '-lmd-kwin-module', *libraries])
if branch_protection:
    for name in ('libmd-kwin-module.so', 'libmd-kwin-qpa.so'):
        assert 'aarch64 feature: bti, pac' in subprocess.check_output(
            ['readelf', '-n', str(root / name)], text=True, env=notes_env).lower()
report['passed'] = True
report['moduleBytes'] = (root / 'libmd-kwin-module.so').stat().st_size
report['qpaBytes'] = (root / 'libmd-kwin-qpa.so').stat().st_size
report['compilerSeconds'] = sum(row['seconds'] for row in report['commands'] if row['args'][0] in ('cc', 'c++'))
report['compiledTranslationUnits'] = sum('-c' in row['args'] for row in report['commands'] if row['args'][0] in ('cc', 'c++'))
(root / 'module-result.json').write_text(json.dumps(report, indent=2) + '\n')
