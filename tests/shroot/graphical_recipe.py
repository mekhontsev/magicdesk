"""Compile the production graphical launch contract for native device fixtures."""
import _bootstrap
import hashlib
from pathlib import Path
import subprocess
import shutil
from magicdesk_executor import source_root


def compile_recipe(classes):
    repo = source_root()
    modules = {
        'connection-runtime': ('LinuxConnectionContract', 'GuestNetworkProfile'),
        'app': ('LinuxGraphicalEnvironment', 'GuestGraphicalConnection', 'GuestLaunchPlan',
                'GuestEnvironment', 'GraphicalProtocol', 'ShellCommandLine', 'LinuxAudioEnvironment', 'GamepadWire'),
    }
    sources = [Path(__file__).with_name('GraphicalRecipe.java'),
               *(repo / module / 'src/main/java/io/github/mekhontsev/magicdesk' / (name + '.java')
                 for module, names in modules.items() for name in names)]
    classes.mkdir(parents=True, exist_ok=True)
    session = repo / 'native/magicdesk_guest_session.sh'
    shutil.copyfile(session, classes / session.name)
    # EVENT_WAIT: compiler exit; expiry fails preparation before client launch.
    subprocess.run(['javac', '-d', str(classes), *map(str, sources)], check=True, timeout=30)
    return {str(path.relative_to(repo)) if path.is_relative_to(repo) else path.name:
            hashlib.sha256(path.read_bytes()).hexdigest() for path in [*sources, session]}
