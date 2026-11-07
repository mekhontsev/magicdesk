"""Host-kernel capability profiles shared by native and distribution runners."""
import json
import os
import shlex


class KernelProfile:
    choices = ('native', 'linux-5.15')
    variable = 'MAGICDESK_TEST_KERNEL_PROFILE'
    marker = 'SHROOT_KERNEL_PROFILE '

    def __init__(self, name=None):
        self.name = name if name is not None else os.environ.get('SHROOT_TEST_KERNEL_PROFILE', 'native')
        if self.name not in self.choices:
            raise ValueError('Unknown kernel profile: ' + self.name)
        self.options = [] if self.name == 'native' else ['--env', self.variable + '=' + self.name]
        # Android APK paths contain '='. env would consume the executable as
        # another assignment; pass it as a positional shell argument instead.
        self.environment = [] if self.name == 'native' else [
            'sh', '-c', shlex.join(['export', self.variable + '=' + self.name]) + '\nexec "$@"', 'kernel-profile']
        self.evidence = {'profile': self.name, 'scope': 'host', 'launches': []}

    @classmethod
    def add_argument(cls, parser):
        parser.add_argument('--kernel-profile', choices=cls.choices,
                            default=os.environ.get('SHROOT_TEST_KERNEL_PROFILE', 'native'),
                            help='Restrict native Shroot owners, not the emulated guest ABI; requires fixture helpers')

    def observe(self, output, required=False):
        records = []
        for line in output.splitlines():
            if line.startswith(self.marker):
                record = json.loads(line[len(self.marker):])
                if record.get('profile') != self.name or record.get('scope') != 'host' or not record.get('verified'):
                    raise AssertionError('Invalid kernel profile evidence: ' + repr(record))
                records.append(record)
        if required and self.options and not records:
            raise AssertionError('No verified host kernel profile; a production or unprofiled helper was launched')
        self.evidence['launches'].extend(records)
        return records

    def prepare(self, command, supervisor):
        if self.options:
            output = command([*self.environment, supervisor, '--test-kernel-profile'])
            records = self.observe(output, required=True)
            self.evidence['probe'] = records[0]
            self.evidence['launches'].clear()

    def bind_session(self, command, state):
        assert not state.get('closed') and state.get('ready'), 'Retained session is not ready'
        actual = state.get('kernelProfile', {}).get('profile', 'native')
        assert actual == self.name, (self.name, actual)
        if not self.options:
            return
        assert state.get('nativeBuild'), 'Profiled session requires fixture helpers'
        image = state['directory'] + '/libmagicdesk_guest_image.so'
        launches = [json.loads(line) for line in command(
            [image, 'launches', state['environment']['store']]).splitlines() if line]
        owned = [item for item in launches if item.get('label') == state['id']]
        assert len(owned) == 1, 'Retained session must have one live owning launch'
        output = command(['head', '-c', '65536', state['directory'] + '/boot.log'])
        self.observe(output, required=True)
        self.evidence['owner'] = {'session': state['id'], 'launch': owned[0]}

    def shell(self, command):
        # Fixture runners retain this host-only selector for their supervisor,
        # which consumes it before guest entry. No executable data-file wrapper.
        prefix = (shlex.join(['export', self.variable + '=' + self.name]) if self.options
                  else 'unset ' + self.variable)
        return prefix + '\n' + command

    @classmethod
    def output(cls, output):
        # Keep diagnostics in the run artifact, not in a guest's JSON/text result.
        return ''.join(line for line in output.splitlines(keepends=True) if not line.startswith(cls.marker))
