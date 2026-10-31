#!/usr/bin/python3
"""Disposable VPN fixture's Linux resolver integration, not an application hook."""
import os
from pathlib import Path

work = Path(os.environ['MD_VPN_WORK'])
resolver = Path('/etc/resolv.conf')
event = os.environ['script_type']
if event == 'up':
    servers = []
    for key, value in os.environ.items():
        words = value.split()
        if key.startswith('foreign_option_') and words[:2] == ['dhcp-option', 'DNS']:
            assert len(words) == 3
            servers.append(words[2])
    assert servers == ['198.51.100.1'], servers
    (work / 'resolver.before').write_bytes(resolver.read_bytes())
    resolver.write_text(''.join('nameserver ' + address + '\n' for address in servers))
elif event == 'route-up':
    (work / 'ready').touch()
elif event == 'down':
    resolver.write_bytes((work / 'resolver.before').read_bytes())
    (work / 'restored').touch()
