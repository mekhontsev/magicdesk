#!/usr/bin/env python3
"""Device fixture: temporarily restart debug MagicDesk App-only and restore its limits.

Root is used only for fixture setup/recovery while the app deliberately has no shell.
The product commands under test never use it. No privilege-manager restart or reboot.
"""
import importlib.util
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'io.github.mekhontsev.magicdesk'
PREFERENCES = 'shared_prefs/runtime_limits.xml'


def host(command, data=None):
    return subprocess.run(['su','-c',command],input=data,capture_output=True,check=True,timeout=30).stdout


def main():
    spec = importlib.util.spec_from_file_location('checks',Path(__file__).with_name('test_command_access.py'))
    checks = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checks)
    c = checks.client()
    original_state = c.call('get_state')
    assert not original_state['workspaces'] and not original_state['graphics']
    original = host('/system/bin/run-as '+PACKAGE+' cat '+PREFERENCES)
    tree = ET.fromstring(original)
    node = next(n for n in tree if n.attrib.get('name') == 'maximum_access')
    node.text = 'APP_ONLY'
    trial = ET.tostring(tree,encoding='utf-8',xml_declaration=True)
    def restart(contents):
        host('/system/bin/am force-stop '+PACKAGE)
        host('/system/bin/run-as '+PACKAGE+' sh -c "cat > '+PREFERENCES+'"',contents)
        host('/system/bin/am start -n '+PACKAGE+'/.ControlActivity')
        return c.call('get_state',retry=True)
    try:
        state = restart(trial)
        assert state['limits']['active']['maximumAccess'] == 'app_only' and not state['shell']['ready']
        subprocess.run([sys.executable,str(Path(__file__).with_name('test_command_access.py')),
                        '--access','app_only','--fixture-activity'],check=True,timeout=300)
    finally:
        restored = restart(original)
        assert restored['limits']['active'] == original_state['limits']['active']
        print('RESTORED original access limits',flush=True)


if __name__ == '__main__':
    main()
