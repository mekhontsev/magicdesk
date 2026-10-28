"""Run a nested boot fixture through an installed environment's root controller."""
import hashlib
import json
from pathlib import Path
import shlex
import urllib.request
import uuid

from test_oci_services import Suite


def run_fixture(session, kind, arguments, uploads=(), prepare=None):
    state = json.loads(session.read_text())
    assert not state.get('closed')
    suite = Suite(session.parent)
    tag = uuid.uuid4().hex
    suite.report.update(session=state['id'], environment=state['environment'],
                        nativeHashes=state.get('nativeHashes'), entryMode=state.get('entryMode', 'lazy'),
                        workload=kind, inputs={})
    try:
        fixtures = Path(__file__).with_name('fixtures')
        files = [fixtures / name for name in (kind + '-boot.py', 'systemd_ready.py', 'network_exchange.py')]
        for fixture in [*files, *uploads]:
            with fixture.open('rb') as source:
                suite.report['inputs'][fixture.name] = hashlib.file_digest(source, 'sha256').hexdigest()
            suite.transport.upload(suite.client, fixture, state['directory'] + '/' + fixture.name, overwrite=True)
            suite.command(['chmod', '644', state['directory'] + '/' + fixture.name])
        result_name = kind + '-' + tag + '.json'
        argv = [state['target'] + '/' + arg.name if isinstance(arg, Path) else arg for arg in arguments]
        command = shlex.join(['python3', state['target'] + '/' + kind + '-boot.py', *argv,
                             state['target'] + '/' + result_name])
        if prepare is not None:
            suite.report['preparation'] = prepare(state)
            command = '(' + suite.report['preparation'] + ') && ' + command
        request = urllib.request.Request('http://127.0.0.1:' + str(state['rootPort']),
            data=json.dumps(dict(id=tag, command=command, wait=True)).encode(),
            headers={'Authorization': 'Bearer ' + state['token']})
        print(kind + ' log: ' + state['directory'] + '/' + tag + '.log', flush=True)
        # EVENT_WAIT: exact root-controller job; a timeout never replays the request.
        response = json.loads(urllib.request.urlopen(request, timeout=900).read())
        log = session.parent / (kind + '-' + tag + '.log')
        result = session.parent / result_name
        suite.transport.download(suite.client, state['directory'] + '/' + tag + '.log', log)
        suite.report.update(log=str(log), exitCode=response['exitCode'])
        try:
            suite.transport.download(suite.client, state['directory'] + '/' + result_name, result)
        except Exception:
            if response['exitCode']:
                raise AssertionError(log.read_text()[-6000:]) from None
            raise
        suite.report['probe'] = json.loads(result.read_text())
        assert response['exitCode'] == 0 and suite.report['probe']['passed'], log.read_text()[-6000:]
        suite.report['passed'] = True
        print('PASS ' + kind + ' fixture in ' + state['name'], flush=True)
    finally:
        suite.finish()
