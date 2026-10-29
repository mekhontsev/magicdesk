#!/usr/bin/env python3
"""Check a public-API guard against the installed, unmodified Weston 13 library."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

root = Path(__file__).resolve().parent
report = {'passed': False, 'commands': []}

def run(args, env=None):
    before = time.monotonic()
    result = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    report['commands'].append(dict(args=args, seconds=time.monotonic() - before,
                                   returncode=result.returncode, output=result.stdout))
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(result.stdout, end='', flush=True)
    assert result.returncode == 0, args

libraries = [p for p in Path('/usr/lib').glob('**/libweston-13.so.0') if p.is_file()]
assert len(libraries) == 1, libraries
stock = libraries[0].resolve()
report.update(stock=str(stock), stockSha256=hashlib.sha256(stock.read_bytes()).hexdigest())
configs = sorted(Path('/var/cache/magicdesk/weston').glob('13.0.4-*/build/compile_commands.json'))
assert configs, 'The probe needs the cached release headers and compiler configuration'
rows = json.loads(configs[-1].read_text())
row = next(r for r in rows if r['file'].endswith('/desktop/surface.c'))
source = (Path(row['directory']) / row['file']).resolve()
declarations = source.read_text().split('static void\nweston_desktop_surface_update_view_position')[0]
(root / 'probe.c').write_text(declarations + (root / 'weston-child-probe.c').read_text())
args = shlex.split(row['command'])
flags = []
index = 1
while index < len(args):
    arg = args[index]
    index += 1
    if arg in ('-o', '-MF', '-MT', '-MQ'):
        index += 1
    elif arg in ('-c', '-MD', '-DNDEBUG') or arg == row['file']:
        continue
    elif arg.startswith('-I') and not Path(arg[2:]).is_absolute():
        flags.append('-I' + str((Path(row['directory']) / arg[2:]).resolve()))
    else:
        flags.append(arg)
flags.append('-I' + str(source.parent))
run(['cc', '-O2', '-fPIC', '-shared', '-Wall', '-Wextra', '-Werror',
     str(root / 'weston-child-guard.c'), '-o', str(root / 'libmd-weston-child.so'), '-ldl'])
run(['cc', *flags, str(root / 'probe.c'), '-o', str(root / 'probe'),
     str(stock), '-lwayland-server', '-lwayland-client'])
run([str(root / 'probe'), 'stock'])
run([str(root / 'probe'), 'guarded'], {**os.environ, 'LD_PRELOAD': str(root / 'libmd-weston-child.so')})
assert hashlib.sha256(stock.read_bytes()).hexdigest() == report['stockSha256']
report.update(passed=True, moduleBytes=(root / 'libmd-weston-child.so').stat().st_size)
(root / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
