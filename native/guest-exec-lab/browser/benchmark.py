#!/usr/bin/env python3
"""Stock Chromium video/input checks, not an isolation certificate.

Uses an owned profile, a local video and the shared fixture executor. Optional
retained-desktop mode exercises stock Flatpak without disabling its GPU.
Root only observes host CPU counters; the browser retains its selected identity.
"""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_cpu import observe_cpu
from fixture_executor import FixtureExecutor
from installed_desktop import controller_command, wait_controller
from test_oci_services import Server, Suite


PAGE = b'''<!doctype html><title>Shroot video and input</title>
<style>body{background:white;color:black;font:24px sans-serif}video{width:960px}
input{font:24px sans-serif;width:600px}</style>
<video id="video" src="/video.webm" muted autoplay loop></video><br>
<input id="input" autofocus><span id="echo"></span>
<script>
const video=document.getElementById('video'), input=document.getElementById('input');
window.framesSeen=0; window.inputTimes=[];
function frame(){window.framesSeen++;video.requestVideoFrameCallback(frame)}
video.requestVideoFrameCallback(frame);
input.addEventListener('input',()=>{const start=performance.now();
 document.getElementById('echo').textContent=input.value;
 requestAnimationFrame(()=>window.inputTimes.push(performance.now()-start));});
window.snapshot=()=>{const q=video.getVideoPlaybackQuality();return {
 frames:q.totalVideoFrames,dropped:q.droppedVideoFrames,presented:window.framesSeen,
 width:video.videoWidth,height:video.videoHeight,paused:video.paused,
 ready:video.readyState,error:video.error?.message||'',inputs:input.value.length,
 inputTimes:[...window.inputTimes],time:video.currentTime}};
</script>'''


class Protocol:
    def __init__(self, url):
        import websocket
        self.socket = websocket.create_connection(url, suppress_origin=True, timeout=30)
        self.serial = 0

    def call(self, method, params=None, session=None):
        self.serial += 1
        request = dict(id=self.serial, method=method, params=params or {})
        if session:
            request['sessionId'] = session
        self.socket.send(json.dumps(request))
        # EVENT_WAIT: exact DevTools reply; socket expiry fails the operation.
        deadline = time.monotonic() + 30
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('DevTools reply: ' + method)
            self.socket.settimeout(remaining)
            reply = json.loads(self.socket.recv())
            if reply.get('id') == self.serial:
                assert 'error' not in reply, reply
                return reply.get('result', {})

    def evaluate(self, expression, session):
        reply = self.call('Runtime.evaluate', dict(expression=expression,
                          returnByValue=True, awaitPromise=True), session)
        assert 'exceptionDetails' not in reply, reply
        return reply['result'].get('value')

    def close(self):
        self.socket.close()


def host(command):
    return subprocess.check_output(['su', '-c', command], text=True, timeout=15)


def threads(pid):
    rows = host(f'for f in /proc/{pid}/task/*/stat; do '
                'if { IFS= read -r row < "$f"; } 2>/dev/null; then printf "%s\\n" "$row"; fi; done')
    return thread_snapshot(rows, pid)


def thread_snapshot(rows, pid):
    result = {}
    for row in rows.splitlines():
        tid = int(row.split()[0])
        fields = row[row.rfind(')')+2:].split()
        result[tid] = dict(ticks=int(fields[11])+int(fields[12]), start=int(fields[19]), cpu=int(fields[36]))
    assert pid in result, 'Supervisor exited during observation'
    return result


def instrumentation(args, state=None):
    result = {name: getattr(args, name) if state is None else state.get(name)
              for name in ('statistics', 'diagnostics')}
    if state is not None:
        for name, active in result.items():
            if getattr(args, name) and active is not True:
                raise ValueError('--' + name + ' requires that option in the retained desktop session')
    return result


@contextmanager
def sample_profile(args, row, pid, ident):
    if not args.profile:
        yield
        return
    target = (args.output / ('profile-' + ident)).resolve()
    command = ['/system/bin/simpleperf', 'record', '-p', str(pid), '--no-inherit',
               '-e', 'cpu-clock', '-f', '499', '--call-graph', 'fp',
               '--duration', str(args.seconds), '--exit-with-parent', '-o', str(target) + '.data']
    row['samplingProfile'] = str(target) + '.data'
    with Path(str(target) + '.log').open('w') as log:
        process = subprocess.Popen(['su', '-c', shlex.join(command)], stdout=log, stderr=subprocess.STDOUT)
        try:
            yield
        finally:
            try:
                result = process.wait(timeout=args.seconds + 20)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
                raise
            assert result == 0, 'Profiler failed: ' + str(target) + '.log'
    with Path(str(target) + '.txt').open('w') as report:
        subprocess.run(['su', '-c', shlex.join(['/system/bin/simpleperf', 'report',
            '-i', str(target) + '.data', '--sort', 'tid,dso,symbol'])],
            stdout=report, stderr=subprocess.STDOUT, timeout=30, check=True)


class DesktopBrowser:
    def __init__(self, suite, state, command, seconds):
        self.suite, self.state = suite, state
        self.ident = suite.report['id']
        self.log = []
        self.deadline = time.monotonic() + seconds + 130
        self.result = None
        controller_command(state, shlex.join(['timeout', '-k', '5', str(seconds + 120), *command]),
                           ident=self.ident)

    def read(self, suffix):
        path = self.state['directory'] + '/' + self.ident + suffix
        return self.suite.command(['/system/bin/sh', '-c',
            'if test -f "$1"; then cat "$1"; fi', 'fixture', path], quiet=True)

    def ready(self, text, timeout=90):
        deadline = min(self.deadline, time.monotonic() + timeout)
        while True:
            self.log = [self.read('.log')]
            if text in self.log[0]:
                return
            result = self.read('.exit')
            if result:
                self.log = [self.read('.log')]
                raise AssertionError('Browser exited with ' + result.strip() + ': ' + self.log[0])
            assert time.monotonic() < deadline, self.log[0]
            # Bounded state wait: exact browser endpoint, not a warmup delay.
            threading.Event().wait(.1)

    def completed(self, expected=0, timeout=30):
        deadline = min(self.deadline, time.monotonic() + timeout)
        while self.result is None:
            value = self.read('.exit')
            if value:
                self.result = int(value)
                break
            assert time.monotonic() < deadline, 'Owned desktop browser did not exit'
            # EVENT_WAIT: controller's completion receipt for this command.
            threading.Event().wait(.1)
        self.log = [self.read('.log')]
        if expected is not None:
            assert self.result == expected, self.log[0]

    def close(self):
        self.completed(expected=None, timeout=max(1, self.deadline-time.monotonic()))
        self.suite.report['servers'].append(dict(commandId=self.ident, log=''.join(self.log),
                                                result=self.result))


def display_clocks(client):
    snapshot = client.call('list_displays')
    return dict(generatedAtMillis=snapshot.get('generatedAtMillis'), error=snapshot.get('catalogError'),
                displays=[{key: display.get(key) for key in ('id', 'state', 'mode')}
                          for display in snapshot.get('displays', [])])


def run(args, server_url, mode, index):
    state = json.loads(args.desktop_session.read_text()) if args.desktop_session else None
    suite = Suite(args.output, None if state else args.build, executor=args.executor, session=state)
    profile = ('/home/' + state['user'] + '/.var/app/' + args.flatpak + '/cache/' if state
               else '/tmp/') + 'md-video-' + suite.report['id']
    worker = protocol = None
    row = dict(mode=mode, round=index, passed=False, profile=profile, browserArguments=args.browser_arg,
               **instrumentation(args, state))
    suite.report['browserVideo'] = row
    try:
        options = ['--no-first-run', '--user-data-dir=' + profile,
                   '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
                   '--window-size=1280,900', *args.browser_arg, 'about:blank']
        if state:
            wait_controller(state, user=True)
            worker = DesktopBrowser(suite, state, ['flatpak', 'run',
                '--env=SHROOT_MACHINE_ENTRY=' + str(int(mode == 'lazy')), args.flatpak, *options], args.seconds)
        else:
            suite.command(suite.guest(args.store, '--user', '0:0') + ['--', '/bin/mkdir', '-m', '0777', profile], quiet=True)
            launch = suite.guest(args.store, '--user', '2000:2000', '--env',
                'SHROOT_MACHINE_ENTRY=' + str(int(mode == 'lazy')), '--deadline-seconds', str(args.seconds+150))
            if args.statistics:
                launch += ['--statistics']
            if args.diagnostics:
                launch += ['--diagnostics']
            launch += ['--', args.chromium, '--headless', '--disable-gpu', *options]
            worker = Server(suite, launch, timeout=args.seconds+170)
        worker.ready('DevTools listening on ', timeout=90)
        endpoint = re.search(r'DevTools listening on (ws://\S+)', ''.join(worker.log))[1]
        protocol = Protocol(endpoint)
        row['version'] = protocol.call('Browser.getVersion')
        row['graphics'] = protocol.call('SystemInfo.getInfo')
        row['initialTargets'] = protocol.call('Target.getTargets')['targetInfos']
        target = protocol.call('Target.createTarget', dict(url='about:blank'))['targetId']
        row['target'] = target
        session = protocol.call('Target.attachToTarget', dict(targetId=target, flatten=True))['sessionId']
        protocol.call('Page.enable', session=session)
        protocol.call('Page.navigate', dict(url=server_url), session)
        # Bounded state wait: document navigation creates the fixture API.
        deadline = time.monotonic()+30
        while not protocol.evaluate('typeof window.snapshot === "function"', session):
            assert time.monotonic() < deadline, 'Fixture document did not load'
            threading.Event().wait(.05)
        # EVENT_WAIT: decoded/presented video frames, not an arbitrary warmup delay.
        protocol.evaluate('new Promise((resolve,reject)=>{const v=document.getElementById("video");'
            'const t=setTimeout(()=>reject(Error("video startup")),20000);'
            'v.addEventListener("error",()=>reject(Error(v.error.message)),{once:true});'
            'let n=0;function frame(){if(++n===30){clearTimeout(t);resolve(true)}'
            'else v.requestVideoFrameCallback(frame)}v.requestVideoFrameCallback(frame);v.play().catch(reject)})', session)
        records = [json.loads(line) for line in suite.command(
            suite.image_command('launches', args.store), quiet=True).splitlines() if line]
        owned = [record for record in records if record.get('label') == state['id']] if state else [
            record for record in records if record['program'] == args.chromium]
        assert len(owned) == 1, records
        pid = owned[0]['pid']
        row['launch'] = owned[0]
        protocol.evaluate('document.getElementById("input").focus();true', session)
        row['hostDisplaysBefore'] = display_clocks(suite.client)
        with sample_profile(args, row, pid, suite.report['id']), observe_cpu(host) as cpu:
            before = threads(pid)
            first = protocol.evaluate('snapshot()', session)
            start = time.monotonic()
            latencies = []
            for count in range(args.seconds):
                # Measurement pacing only, never a readiness assertion.
                threading.Event().wait(max(0, start+count-time.monotonic()))
                sent = time.monotonic()
                protocol.call('Input.insertText', dict(text='x'), session)
                value = protocol.evaluate('new Promise(resolve=>requestAnimationFrame(()=>resolve(snapshot())))', session)
                assert value['inputs'] == count+1, value
                latencies.append((time.monotonic()-sent)*1000)
            threading.Event().wait(max(0, start+args.seconds-time.monotonic()))
            last = protocol.evaluate('snapshot()', session)
            elapsed = time.monotonic()-start
            after = threads(pid)
        row['hostDisplaysAfter'] = display_clocks(suite.client)
        ticks = os.sysconf('SC_CLK_TCK')
        delta = {str(tid): (state['ticks']-before[tid]['ticks'])/ticks for tid,state in after.items()
                 if tid in before and state['start'] == before[tid]['start']}
        assert str(pid) in delta and all(value >= 0 for value in delta.values()), delta
        row.update(seconds=elapsed, before=first, after=last, cpu=cpu, threadCpuSeconds=delta,
                   supervisorPercent=100*delta[str(pid)]/elapsed,
                   processPercent=100*sum(delta.values())/elapsed,
                   frames=last['frames']-first['frames'], dropped=last['dropped']-first['dropped'],
                   inputRoundTripMs=latencies, inputMedianMs=statistics.median(latencies))
        assert not last['paused'] and not last['error'] and last['ready'] >= 3, last
        assert last['presented']-first['presented'] > elapsed*10, (first,last,elapsed)
        screenshot = protocol.call('Page.captureScreenshot', dict(format='png'), session)
        image = args.output / ('video-' + suite.report['id'] + '.png')
        image.write_bytes(base64.b64decode(screenshot['data'], validate=True))
        row['screenshot'] = str(image)
        protocol.call('Browser.close')
        worker.completed(timeout=30)
        output = ''.join(worker.log)
        counters = [json.loads(value) for value in re.findall(r'^SVC_RUNTIME_STATS (.+)$', output, re.MULTILINE)]
        row['entryCounters'] = counters
        if args.statistics and mode == 'lazy' and not state:
            assert any(value['filteredCompleted'] for value in counters), 'No post-filter acceleration'
        if mode == 'base':
            assert not any(value['completed'] for value in counters), counters
        row['passed'] = suite.report['passed'] = True
        print('VIDEO_RESULT', json.dumps({k:v for k,v in row.items() if k not in
              ('cpu','before','after','entryCounters','inputRoundTripMs','graphics')}), flush=True)
        return row
    finally:
        if protocol:
            if state and worker and worker.result is None:
                try:
                    protocol.call('Browser.close')
                except Exception:
                    pass
            protocol.close()
        try:
            if worker:
                worker.close()
            if state:
                result = controller_command(state, shlex.join(['rm', '-rf', '--', profile]), wait=True)
                assert result['exitCode'] == 0, result
            else:
                suite.command(suite.guest(args.store, '--user', '0:0') + ['--', '/bin/rm', '-rf', '--', profile], quiet=True)
        finally:
            suite.finish()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--store')
    parser.add_argument('--desktop-session', type=Path, help='Retained installed_desktop session.json; uses its runtime')
    parser.add_argument('--flatpak', default='org.chromium.Chromium')
    parser.add_argument('--browser-arg', action='append', default=[],
                        help='Explicit diagnostic browser option, retained in the report')
    parser.add_argument('--video', type=Path, required=True)
    parser.add_argument('--chromium', default='/usr/lib/chromium/chromium')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=45)
    parser.add_argument('--rounds', type=int, default=2)
    parser.add_argument('--statistics', action='store_true', help='Instrumented admission proof, not timing baseline')
    parser.add_argument('--diagnostics', action='store_true', help='Detailed runtime transitions, not timing baseline')
    parser.add_argument('--profile', action='store_true', help='Root simpleperf samples during playback, not a timing baseline')
    parser.add_argument('--modes', nargs='+', choices=('base', 'lazy'), default=['base', 'lazy'])
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    state = None
    if args.desktop_session:
        if args.build or args.store:
            parser.error('A retained session supplies its own build and store')
        state = json.loads(args.desktop_session.read_text())
        assert not state.get('closed') and not state.get('headless'), 'A running GUI session is required'
        args.store = state['environment']['store']
    elif not args.build or not args.store:
        parser.error('Headless mode requires --build and --store')
    try:
        runtime = instrumentation(args, state)
    except ValueError as error:
        parser.error(str(error))
    assert 10 <= args.seconds <= 120 and 1 <= args.rounds <= 5
    video = args.video.read_bytes()
    args.output.mkdir(parents=True, exist_ok=True)
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body, mime = (video, 'video/webm') if self.path == '/video.webm' else (PAGE, 'text/html')
            self.send_response(200)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *args):
            pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    report = dict(videoSha256=hashlib.sha256(video).hexdigest(), executor=args.executor,
                  **runtime, profile=args.profile,
                  samples=[], passed=False)
    try:
        for index in range(args.rounds):
            for mode in (args.modes if index%2 == 0 else reversed(args.modes)):
                report['samples'].append(run(args, f'http://127.0.0.1:{server.server_port}/', mode, index))
        report['passed'] = True
    finally:
        server.shutdown()
        server.server_close()
        thread.join(10)
        assert not thread.is_alive()
        (args.output/'summary.json').write_text(json.dumps(report, indent=2)+'\n')


if __name__ == '__main__':
    main()
