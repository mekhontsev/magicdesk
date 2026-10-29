#!/usr/bin/env python3
"""Exact-build ELF adaptation experiment, never an installer hook."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

import lief

root = Path(__file__).resolve().parent
(root / 'result.json').unlink(missing_ok=True)
original = Path('/usr/lib/libkwin.so.6.2.5')
expected = '986879c1435901c27655a23dc36bf7543649390b76d44871aee9e312b2056a0f'
assert hashlib.sha256(original.read_bytes()).hexdigest() == expected, 'Unreviewed KWin binary'
target = root / 'patched'
target.mkdir(exist_ok=True)
report = {'source': str(original), 'sha256': expected, 'commands': []}


def run(argv, env=None):
    start = time.monotonic()
    result = subprocess.run(argv, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    report['commands'].append(dict(argv=argv, seconds=time.monotonic() - start,
                                   returncode=result.returncode, output=result.stdout))
    print(result.stdout, end='', flush=True)
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    assert result.returncode == 0, argv
    return result.stdout


flags = shlex.split(run(['pkg-config', '--cflags', '--libs', 'Qt6Core', 'Qt6Gui', 'epoxy']))
compile_flags = ['c++', '-std=c++20', '-O2', '-fPIC', '-I/usr/include/kwin', '-I/usr/include/libdrm']
run([*compile_flags, '-shared', '-Wl,-soname,libmd-kwin-fragment.so',
     str(root / 'kwin-binary-allocator.cpp'), '-o', str(target / 'libmd-kwin-fragment.so'), *flags])
run([*compile_flags, str(root / 'kwin-binary-probe.cpp'), '-o', str(root / 'probe'),
     '-L/usr/lib', '-lkwin', '-ldl', *flags])
binary = lief.parse(str(original))
assert binary.header.machine_type == lief.ELF.ARCH.AARCH64
original_text = bytes(binary.get_section('.text').content)
text_address = binary.get_section('.text').virtual_address
old_name = '_ZN4KWin26GbmGraphicsBufferAllocator8allocateERKNS_21GraphicsBufferOptionsE'
vtable = binary.get_dynamic_symbol('_ZTVN4KWin26GbmGraphicsBufferAllocatorE')
assert vtable and vtable.size == 40
relocations = [r for r in binary.dynamic_relocations if r.address == vtable.value + 32]
assert len(relocations) == 1
relocation = relocations[0]
assert relocation.type == lief.ELF.Relocation.TYPE.AARCH64_ABS64
assert relocation.symbol.name == old_name and relocation.addend == 0
symbol = lief.ELF.Symbol()
symbol.name = 'md_kwin_allocate'
symbol.binding = lief.ELF.Symbol.BINDING.GLOBAL
symbol.type = lief.ELF.Symbol.TYPE.FUNC
symbol.shndx = 0
symbol = binary.add_dynamic_symbol(symbol)
relocation.symbol = symbol
binary.add_library('libmd-kwin-fragment.so')
binary.write(str(target / 'libkwin.so.6'))
patched = lief.parse(str(target / 'libkwin.so.6'))
assert bytes(patched.get_section('.text').content) == original_text
# Finalize the ELF layout before linking the address-dependent entry stub.
entry = patched.get_dynamic_symbol(old_name).value
slot = patched.get_dynamic_symbol('_ZTVN4KWin26GbmGraphicsBufferAllocatorE').value + 32
run(['cc', '-c', str(root / 'kwin-binary-entry.S'), '-o', str(root / 'entry.o')])
run(['ld', '--section-start=.text=' + hex(entry),
     '--defsym=md_allocate_slot=' + hex(slot), '-e', 'md_allocate_entry',
     str(root / 'entry.o'), '-o', str(root / 'entry.elf')])
run(['objcopy', '-O', 'binary', '--only-section=.text', str(root / 'entry.elf'),
     str(root / 'entry.bin')])
entry_code = (root / 'entry.bin').read_bytes()
assert len(entry_code) == 12
assert bytes(patched.get_content_from_virtual_address(entry, 12)).hex() == 'ffc303d1c2280090429840f9'
content = bytearray((target / 'libkwin.so.6').read_bytes())
file_offset = patched.virtual_address_to_offset(entry)
content[file_offset:file_offset + len(entry_code)] = entry_code
(target / 'libkwin.so.6').write_bytes(content)
final = lief.parse(str(target / 'libkwin.so.6'))
assert final.get_dynamic_symbol(old_name).value == entry
assert not any(int(segment.flags) & 3 == 3 for segment in final.segments)
offset = entry - patched.get_section('.text').virtual_address
expected_text = original_text[:offset] + entry_code + original_text[offset + len(entry_code):]
assert bytes(final.get_section('.text').content) == expected_text
report['textPatchBytes'] = len(entry_code)
report['textOtherwiseUnchanged'] = True
report['entryAddress'] = entry
report['dispatchSlotAddress'] = slot
report['textAddressShift'] = patched.get_section('.text').virtual_address - text_address
report['entryDisassembly'] = run(['objdump', '-d', str(root / 'entry.elf')])
report['fragmentBytes'] = (target / 'libmd-kwin-fragment.so').stat().st_size
report['originalBytes'] = original.stat().st_size
report['patchedBytes'] = (target / 'libkwin.so.6').stat().st_size
print(json.dumps({k: v for k, v in report.items() if k != 'commands'}), flush=True)
environment = os.environ.copy()
environment.pop('LD_PRELOAD', None)
run([str(root / 'probe'), '--control'], environment)
environment['LD_LIBRARY_PATH'] = str(target) + ':' + environment.get('LD_LIBRARY_PATH', '')
for _ in range(3):
    run([str(root / 'probe')], environment)
assert hashlib.sha256(original.read_bytes()).hexdigest() == expected
report['passed'] = True
(root / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
