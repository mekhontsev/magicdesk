#!/usr/bin/env python3
"""Exact-build offline adaptation of stock KWin to the partial module."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess

import lief

root = Path(__file__).resolve().parent
output = root / 'patched'
output.mkdir(exist_ok=True)
profile = json.loads((root / 'module-profile.json').read_text())
stock = Path(profile['stock'])
stock_hash = profile['sha256']
assert hashlib.sha256(stock.read_bytes()).hexdigest() == stock_hash
build = json.loads((root / 'module-result.json').read_text())
assert build['passed'] and build['layouts']['stock'] == build['layouts']['module']
original = lief.parse(str(stock))
module = lief.parse(str(root / 'libmd-kwin-module.so'))
assert original.header.machine_type == module.header.machine_type == lief.ELF.ARCH.AARCH64
original_text = bytes(original.get_section('.text').content)
properties = original.get_section('.note.gnu.property')
properties = bytes(properties.content) if properties else None
backend_vtable = '_ZTVN4KWin10EglBackendE' if profile['version'] == '6.6.6' else '_ZTVN4KWin18AbstractEglBackendE'
for name in (backend_vtable, '_ZTVN4KWin7Wayland14WaylandBackendE',
             '_ZTVN4KWin18ItemRendererOpenGLE'):
    a, b = original.get_dynamic_symbol(name), module.get_dynamic_symbol(name)
    assert a and b and a.size == b.size, ('Vtable size', name, a, b)
symbols = [s for s in module.dynamic_symbols if s.is_function and s.shndx and s.size]
names = subprocess.check_output(['c++filt', *[s.name for s in symbols]], text=True).splitlines()
owner = re.compile(r'^(?:(?:non-virtual|virtual) thunk to )?KWin::(?:'
                   r'Wayland::WaylandBackend|EglDisplay|AbstractEglBackend|EglBackend|ItemRendererOpenGL|'
                   r'LinuxDmaBufV1ClientBufferIntegration)::')
selected = []
for symbol, demangled in zip(symbols, names):
    if not owner.match(demangled) and not demangled.startswith((
        'KWin::WaylandServer::linuxDmabuf(', 'KWin::WaylandServer::setRenderBackend(',
        'KWin::Compositor::attemptOpenGLCompositing(',
        'KWin::Display::bufferForResource(')):
        continue
    old = original.get_dynamic_symbol(symbol.name)
    if not old or not old.shndx or not old.is_function:
        continue
    assert old.size >= 4 and old.value % 4 == 0
    alias = lief.ELF.Symbol()
    alias.name = 'md_kwin_' + str(len(selected))
    alias.type = lief.ELF.Symbol.TYPE.FUNC
    alias.binding = lief.ELF.Symbol.BINDING.GLOBAL
    alias.visibility = lief.ELF.Symbol.VISIBILITY.DEFAULT
    alias.value, alias.size, alias.shndx = symbol.value, symbol.size, symbol.shndx
    module.add_dynamic_symbol(alias)
    selected.append(dict(name=symbol.name, demangled=demangled, alias=alias.name))
assert len(selected) >= 50, len(selected)
module.write(str(output / 'libmd-kwin-module.so'))

def segment(flags, size):
    item = lief.ELF.Segment()
    item.type = lief.ELF.Segment.TYPE.LOAD
    item.flags = flags
    item.alignment = 0x1000
    item.content = [0] * size
    return original.add(item)

slots = segment(lief.ELF.Segment.FLAGS.R | lief.ELF.Segment.FLAGS.W, 8 * len(selected))
code = segment(lief.ELF.Segment.FLAGS.R | lief.ELF.Segment.FLAGS.X, 16 * len(selected))
# The new sections identify their final addresses after LIEF lays out the ELF.
for name, address in [('_md_slots', slots.virtual_address), ('_md_code', code.virtual_address)]:
    s = lief.ELF.Symbol()
    s.name, s.value, s.shndx = name, address, 1
    s.binding = lief.ELF.Symbol.BINDING.GLOBAL
    original.add_dynamic_symbol(s)
for index, item in enumerate(selected):
    s = lief.ELF.Symbol()
    s.name = item['alias']
    s.type = lief.ELF.Symbol.TYPE.FUNC
    s.binding = lief.ELF.Symbol.BINDING.GLOBAL
    s = original.add_dynamic_symbol(s)
    relocation = lief.ELF.Relocation(slots.virtual_address + 8 * index,
                                    lief.ELF.Relocation.TYPE.AARCH64_ABS64,
                                    lief.ELF.Relocation.ENCODING.RELA)
    relocation.symbol = s
    original.add_dynamic_relocation(relocation)
original.add_library('libmd-kwin-module.so')
target = output / 'libkwin.so.6'
original.write(str(target))
final = lief.parse(str(target))
assert bytes(final.get_section('.text').content) == original_text
slots_address = final.get_dynamic_symbol('_md_slots').value
code_address = final.get_dynamic_symbol('_md_code').value
content = bytearray(target.read_bytes())
expected_text = bytearray(original_text)
entries = {}
protected_entries = 0
patched_bytes = 0
for index, item in enumerate(selected):
    entry = final.get_dynamic_symbol(item['name']).value
    trampoline, slot = code_address + 16 * index, slots_address + 8 * index
    # ADRP x16; LDR x16,[x16,#offset]; BR x16. x16 is the AAPCS veneer scratch.
    pages = (slot >> 12) - (trampoline >> 12)
    assert -(1 << 20) <= pages < (1 << 20) and slot % 8 == 0
    adrp = 0x90000010 | ((pages & 3) << 29) | (((pages >> 2) & 0x7ffff) << 5)
    ldr = 0xf9400210 | (((slot & 4095) // 8) << 10)
    stub = struct.pack('<4I', adrp, ldr, 0xd61f0200, 0xd503201f)
    where = final.virtual_address_to_offset(trampoline)
    content[where:where + 16] = stub
    # Constructor/destructor aliases may share an entry; all must share a target.
    if entry in entries:
        first = entries[entry]
        a = module.get_dynamic_symbol(selected[first]['name'])
        b = module.get_dynamic_symbol(item['name'])
        assert a.value == b.value, ('Conflicting alias', item)
        continue
    entries[entry] = index
    where = final.virtual_address_to_offset(entry)
    instruction = struct.unpack_from('<I', content, where)[0]
    landing = instruction if instruction in (0xd503245f, 0xd503249f, 0xd50324df) else None
    if instruction in (0xd503233f, 0xd503237f):  # PACIASP/PACIBSP are also landing pads.
        # The replacement signs its own return address. Do not sign twice.
        landing = 0xd503245f  # BTI c
    prefix = struct.pack('<I', landing) if landing is not None else b''
    assert final.get_dynamic_symbol(item['name']).size >= len(prefix) + 4
    delta = trampoline - entry - len(prefix)
    assert delta % 4 == 0 and -(1 << 27) <= delta < (1 << 27)
    branch = prefix + struct.pack('<I', 0x14000000 | ((delta // 4) & 0x3ffffff))
    content[where:where + len(branch)] = branch
    relative = entry - final.get_section('.text').virtual_address
    expected_text[relative:relative + len(branch)] = branch
    protected_entries += bool(prefix)
    patched_bytes += len(branch)
target.write_bytes(content)
checked = lief.parse(str(target))
assert bytes(checked.get_section('.text').content) == expected_text
assert (bytes(checked.get_section('.note.gnu.property').content)
        if checked.get_section('.note.gnu.property') else None) == properties
assert not any(int(s.flags) & 3 == 3 for s in checked.segments)
for index, item in enumerate(selected):
    matches = [r for r in checked.dynamic_relocations if r.address == slots_address + 8 * index]
    assert len(matches) == 1 and matches[0].symbol.name == item['alias']
plugins = output / 'plugins/platforms'
plugins.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root / 'libmd-kwin-qpa.so', plugins / 'libmd-kwin-qpa.so')
exe = Path(profile['executable']).read_bytes()
key = b'wayland-org.kde.kwin.qpa\0'
# One literal selects QPA; embedded plugin metadata has a length-prefixed key.
assert exe.count(key) == 1, exe.count(key)
exe = exe.replace(key, b'magicdesk-kwin\0'.ljust(len(key), b'\0'))
if 'launcher' in profile:
    # Preserve Nix's package-generated Qt/QML environment. Only its exec target
    # changes, in a private copy; the original Nix store stays immutable.
    inner = output / '.kwin_wayland-wrapped'
    inner.write_bytes(exe)
    inner.chmod(0o755)
    launcher = Path(profile['launcher']).read_bytes()
    old = profile['executable'].encode() + b'\0'
    new = str(inner).encode() + b'\0'
    assert launcher.startswith(b'\x7fELF') and launcher.count(old) == 1 and len(new) <= len(old)
    exe = launcher.replace(old, new.ljust(len(old), b'\0'))
(output / 'kwin_wayland').write_bytes(exe)
(output / 'kwin_wayland').chmod(0o755)
assert hashlib.sha256(stock.read_bytes()).hexdigest() == stock_hash
report = dict(passed=True, stockSha256=stock_hash, functions=selected,
              branchProtection=build['branchProtection'],
              uniqueEntries=len(entries), patchedTextSpanBytes=patched_bytes,
              changedTextBytes=sum(a != b for a, b in zip(original_text, expected_text)),
              protectedEntries=protected_entries,
              moduleBytes=(output / 'libmd-kwin-module.so').stat().st_size,
              qpaBytes=(plugins / 'libmd-kwin-qpa.so').stat().st_size)
(root / 'adapt-result.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'functions'}))
