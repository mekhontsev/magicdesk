#!/usr/bin/env python3
"""Partial-build research fixture. Does not replace the installed KWin."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
from test_oci_services import Suite

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory')
parser.add_argument('--name', choices=('steamos', 'nixos-recipe', 'debian-turnip'), default='steamos')
parser.add_argument('--adapt', action='store_true')
parser.add_argument('--rebuild', action='store_true', help='Discard only this fixture\'s compiled objects')
parser.add_argument('--keep', action='store_true', help='Retain artifacts for test_kwin_binary_desktop.py')
args = parser.parse_args()
if args.directory and (Path(args.directory).parent != Path('/data/local/tmp')
                       or not Path(args.directory).name.startswith('md-oci-services-')):
    parser.error('Expected this fixture\'s retained work directory')
output = Path('build/kwin-binary-module') / args.name
suite = Suite(output)
work = args.directory or suite.base
owned = not args.directory
try:
    if args.directory:
        suite.command(['test', '-f', work + '/kwin-module-build.py'])
        owned = True
    suite.report['workDirectory'] = work
    info = suite.installed_environment(args.name)
    assert not info['launches'], 'Do not rebuild fragments while the environment is running'
    suite.report['environment'] = info
    build_user = '0:0' if args.name == 'nixos-recipe' else '1000:1000'
    if args.name == 'steamos':
        cache = '/var/cache/magicdesk/kwin/6.2.5-5c6a9f974ae6dca9919fa1e32aab0de069e8990d252ccd9981ed7898fbaf1e06'
        profile = dict(version='6.2.5', source=cache + '/source', build=cache + '/build',
            stock='/usr/lib/libkwin.so.6.2.5',
            sha256='986879c1435901c27655a23dc36bf7543649390b76d44871aee9e312b2056a0f',
            archive='/var/cache/magicdesk/kwin/downloads/kwin-6.2.5.tar.xz',
            moc='/usr/lib/qt6/moc', executable='/usr/bin/kwin_wayland')
        setup = ''
    elif args.name == 'nixos-recipe':
        cache = '/var/cache/magicdesk/kwin-interrupted-20261024/kwin-6.6.6'
        prefix = '/nix/store/xna1l2m6kfvygdf5p6kza70fkbsqsja3-kwin-6.6.6'
        profile = dict(version='6.6.6', source=cache, build=cache + '/build',
            recordedSource='/build/kwin-6.6.6', recordedBuild='/build/kwin-6.6.6/build',
            stock=prefix + '/lib/libkwin.so.6.6.6',
            sha256='06af978267cb31ab7d59970bff452fcf62cd261c7130b7c65564250b0ec708e5',
            archive='/nix/store/n6j2ngpw0hk1fyrw8bnyvnyihz68v86a-kwin-6.6.6.tar.xz',
            moc='/nix/store/x2w1r6hn5f2r21953pcp5vckqd8smzc4-qtbase-6.11.2/libexec/moc',
            launcher=prefix + '/bin/kwin_wayland',
            executable=prefix + '/bin/.kwin_wayland-wrapped')
        setup = ('. /var/cache/magicdesk/kwin-interrupted-20261024/env-vars\n'
                 'export TMPDIR=/tmp/md-kwin-module NIX_BUILD_TOP=/tmp/md-kwin-module NIX_ENFORCE_PURITY=0\n')
    else:
        cache = '/tmp/md-kwin-module/cache'
        profile = dict(version='6.3.6', source=cache + '/kwin-6.3.6', build=cache + '/build',
            stock='/usr/lib/aarch64-linux-gnu/libkwin.so.6.3.6',
            sha256='0ed90624ef19f41e19bc1068c9a1ca3d0e1e7360de3d765ac7787c72701cfb3d',
            archive='/tmp/md-kwin-module/kwin-6.3.6.tar.xz',
            moc='/usr/lib/qt6/libexec/moc', executable='/usr/bin/kwin_wayland')
        setup = ''
    profile_path = output / 'module-profile.json'
    profile_path.write_text(json.dumps(profile, indent=2) + '\n')
    suite.transport.upload(suite.client, profile_path, work + '/module-profile.json', overwrite=True)
    patch = subprocess.check_output(['sh', 'scripts/install_linux.sh', '--print-kwin-patch', profile['version']], text=True)
    pointer_patch = output / 'pointer.patch'
    pointer_patch.write_text(patch[patch.rindex('--- a/src/backends/wayland/wayland_backend.cpp'):])
    suite.transport.upload(suite.client, pointer_patch, work + '/pointer.patch', overwrite=True)
    if args.name == 'debian-turnip':
        archive = Path('build/kwin-6.3.6.tar.xz')
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == '27f2205f06d58f1d1f480d2a94ae24022c2f95b9c1fdc5a549f8e143713fce12'
        suite.transport.upload(suite.client, archive, work + '/' + archive.name, overwrite=True)
        full_patch = output / 'dmaheap.patch'
        full_patch.write_text(patch)
        suite.transport.upload(suite.client, full_patch, work + '/dmaheap.patch', overwrite=True)
    if args.rebuild:
        suite.command(['rm', '-rf', work + '/objects'])
    if args.adapt:
        suite.command(['rm', '-f', work + '/adapt-result.json'])
    for name in ('kwin-module-build.py', 'kwin-module-state.h', 'kwin-module-state.cpp',
                 'kwin-module-layout.cpp', 'kwin-module-adapt.py'):
        suite.transport.upload(suite.client, Path(__file__).with_name('fixtures') / name,
                               work + '/' + name, overwrite=True)
    suite.command(['chmod', '-R', 'a+rX', work])
    suite.command(['chmod', '777', work])
    suite.command([suite.runner, '--store', info['store'], '--user', '0:0', '--',
                   '/bin/sh', '-c', 'PATH=/run/current-system/sw/bin:/usr/bin:/bin mkdir -p /tmp/md-kwin-module'])
    if args.name == 'debian-turnip':
        prefix = [suite.runner, '--store', info['store'], '--user', build_user,
                  '--bind', work, '/tmp/md-kwin-module', '--']
        suite.command([*prefix, '/bin/sh', '-ec', '''
cd /tmp/md-kwin-module
mkdir -p cache
if [ ! -f cache/kwin-6.3.6/.patched ]; then
    rm -rf cache/kwin-6.3.6 cache/build
    tar --no-same-owner -xJf kwin-6.3.6.tar.xz -C cache
    patch --batch --fuzz=0 -p1 -d cache/kwin-6.3.6 -i /tmp/md-kwin-module/dmaheap.patch
    touch cache/kwin-6.3.6/.patched
fi
cmake -S cache/kwin-6.3.6 -B cache/build -G Ninja -DCMAKE_BUILD_TYPE=Release \\
    -DCMAKE_AUTOGEN_PARALLEL=4 -DBUILD_TESTING=OFF -DKWIN_BUILD_KCMS=OFF -DKWIN_BUILD_X11_BACKEND=OFF
'''], timeout=240, quiet=True)
        targets = suite.command([*prefix, '/usr/bin/ninja', '-C', profile['build'], '-t', 'targets', 'all'], quiet=True)
        generated = [line.rsplit(': ', 1)[0] for line in targets.splitlines()
                     if line.endswith(': CUSTOM_COMMAND')]
        generated = [name for name in generated if name.startswith('src/wayland/')
                     and name.endswith(('.h', '.c', '.cpp'))]
        assert generated, 'No generated protocol sources'
        plan = suite.command([*prefix, '/usr/bin/ninja', '-C', profile['build'], '-n', *generated], quiet=True)
        # A small protocol code generator is needed, not the compositor/library.
        compilations = [line for line in plan.splitlines()
                        if 'Building CXX object' in line or 'Building C object' in line]
        assert all('src/wayland/tools/' in line for line in compilations), plan
        suite.command([*prefix, '/usr/bin/ninja', '-C', profile['build'], '-j', '4', *generated], timeout=240, quiet=True)
    try:
        suite.command([suite.runner, '--store', info['store'], '--user', build_user,
                       '--bind', work, '/tmp/md-kwin-module', '--',
                       '/bin/sh', '-ec', setup + 'python3 /tmp/md-kwin-module/kwin-module-build.py'], timeout=900)
    except AssertionError:
        raise RuntimeError('Module build failed; see module-result.json') from None
    if args.adapt:
        suite.command([suite.runner, '--store', info['store'], '--user', build_user,
                       '--bind', work, '/tmp/md-kwin-module', '--', '/bin/sh', '-ec',
                       setup + 'cd /tmp/md-kwin-module\n'
                       'export LD_LIBRARY_PATH="$(dirname "$(c++ -print-file-name=libstdc++.so.6)")${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"\n'
                       'test -x venv/bin/python || python3 -m venv venv\n'
                       'venv/bin/python -m pip --disable-pip-version-check --no-cache-dir install --only-binary=:all: lief==0.17.1\n'
                       'venv/bin/python kwin-module-adapt.py'], timeout=180)
    suite.report['passed'] = True
finally:
    try:
        for name in ('module-result.json', 'adapt-result.json', 'patched/libkwin.so.6',
                     'patched/.kwin_wayland-wrapped',
                     'patched/libmd-kwin-module.so', 'patched/kwin_wayland',
                     'patched/plugins/platforms/libmd-kwin-qpa.so'):
            exists = suite.command(['/system/bin/sh', '-c',
                'test ! -f ' + shlex.quote(work + '/' + name) + ' || printf present'], quiet=True)
            if exists:
                destination = output / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                suite.transport.download(suite.client, work + '/' + name, destination, overwrite=True)
    finally:
        try:
            if owned and not args.keep:
                suite.command(['rm', '-rf', work])
            if work != suite.base:
                suite.command(['rmdir', suite.base])
        finally:
            suite.finish()
        if args.keep:
            print('Retained work directory: ' + work)
