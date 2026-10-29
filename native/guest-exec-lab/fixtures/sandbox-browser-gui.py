#!/usr/bin/env python3
"""Interactive packaged-browser receipts inside a prepared desktop/user session."""
import argparse
import fcntl
import hashlib
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading

DATA = b'Shroot browser file round trip\n'
PAGE = b'''<!doctype html><title>Shroot Browser GUI</title>
<style>body{font:22px sans-serif;margin:30px}input,button,a{font:22px sans-serif;margin:12px}
canvas{display:block;border:1px solid black}</style>
<h1>Shroot Browser GUI</h1>
<input placeholder="Keyboard input" oninput="report('input',this.value)"
 onfocus="report('focus',true)" onblur="report('focus',false)"
 onkeydown="report('keydown',event.key)" onkeyup="report('keyup',event.key)">
<button onclick="report('click','ok')">Click</button><br>
<a href="/download" download="shroot-snap.txt">Download test file</a><br>
<input type="file" onchange="this.files[0].text().then(v=>report('upload',v))">
<canvas width="320" height="160" id="gpu"></canvas><pre id="result"></pre>
<script>
function report(type,value){fetch('/receipt',{method:'POST',body:JSON.stringify({type,value})})}
for(const type of ['pointerdown','pointerup','click']) document.addEventListener(type,event=>report(type,{
 target:event.target.tagName,type:event.target.type||'',x:event.clientX,y:event.clientY,
 screenX:event.screenX,screenY:event.screenY,button:event.button,buttons:event.buttons,
 trusted:event.isTrusted,time:event.timeStamp,rect:event.target.getBoundingClientRect().toJSON()}));
const gl=document.getElementById('gpu').getContext('webgl2');
if(gl){gl.clearColor(0,1,0,1);gl.clear(gl.COLOR_BUFFER_BIT);
const pixel=new Uint8Array(4);gl.readPixels(2,2,1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
const ext=gl.getExtension('WEBGL_debug_renderer_info');
const info={renderer:ext?gl.getParameter(ext.UNMASKED_RENDERER_WEBGL):gl.getParameter(gl.RENDERER),pixel:[...pixel]};
document.getElementById('result').textContent=JSON.stringify(info);report('gpu',info);}
else report('gpu',null);
</script>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--browser', choices=('firefox', 'chromium'), default='firefox')
    parser.add_argument('--package', choices=('snap', 'flatpak', 'native'), default='snap')
    parser.add_argument('--common', type=Path)
    parser.add_argument('--reuse-work', type=Path, help='Relaunch the existing fixture profile without rewriting preferences')
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--require-webgl', action='store_true')
    parser.add_argument('--entry', choices=('default', 'base', 'lazy'), default='default')
    parser.add_argument('--net-log', action='store_true', help='Record Chromium network diagnostics in the fixture directory')
    args = parser.parse_args()
    if args.net_log and args.browser != 'chromium':
        parser.error('--net-log requires Chromium')
    app_id = {'firefox': 'org.mozilla.firefox', 'chromium': 'org.chromium.Chromium'}[args.browser]
    if args.common is None:
        args.common = (Path.home() / 'snap' / args.browser / 'common' if args.package == 'snap'
                       else Path.home() / '.var/app' / app_id / 'cache' if args.package == 'flatpak'
                       else Path.home() / '.cache/shroot-browser' / args.browser)
    args.output.mkdir(parents=True, exist_ok=True)
    args.common.mkdir(parents=True, exist_ok=True)
    work = args.reuse_work or Path(tempfile.mkdtemp(prefix='shroot-gui-', dir=args.common))
    # Retain ownership until receipts are committed; a premature relaunch must
    # not delete the first run's download while its browser is still closing.
    ownership = (work / '.fixture.lock').open('a')
    fcntl.flock(ownership, fcntl.LOCK_EX | fcntl.LOCK_NB)
    profile = work / 'profile'
    if args.reuse_work:
        assert profile.is_dir(), profile
        downloaded = work / 'shroot-snap.txt'
        assert downloaded.read_bytes() == DATA, 'Only reuse a completed fixture directory'
    else:
        profile.mkdir()
    if args.browser == 'firefox':
        if not args.reuse_work:
            (profile / 'user.js').write_text(
                'user_pref("browser.shell.checkDefaultBrowser", false);\n'
                'user_pref("browser.startup.homepage_override.mstone", "ignore");\n'
                'user_pref("browser.download.folderList", 2);\n'
                'user_pref("browser.download.dir", ' + json.dumps(str(work)) + ');\n')
        options = ['--no-remote', '--profile', str(profile)]
    else:
        if not args.reuse_work:
            (profile / 'Default').mkdir()
            (profile / 'Default/Preferences').write_text(json.dumps({
                'download': {'default_directory': str(work), 'prompt_for_download': False}}))
        options = ['--user-data-dir=' + str(profile), '--no-first-run', '--no-default-browser-check',
                   '--ozone-platform=wayland']
        if args.net_log:
            options.append('--log-net-log=' + str(work / 'netlog.json'))
    launcher = (['snap', 'run', args.browser] if args.package == 'snap'
                else ['flatpak', 'run', app_id] if args.package == 'flatpak' else [args.browser])
    environment = os.environ.copy()
    if args.entry != 'default':
        entry = '0' if args.entry == 'base' else '1'
        environment['SHROOT_MACHINE_ENTRY'] = entry
        if args.package == 'flatpak':
            launcher.insert(2, '--env=SHROOT_MACHINE_ENTRY=' + entry)
    receipts = []
    downloads = []
    lock = threading.Lock()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in ('/', '/download'):
                self.send_error(404)
                return
            download = self.path == '/download'
            body = DATA if download else PAGE
            self.send_response(200)
            self.send_header('Content-Type', 'application/octet-stream' if download else 'text/html')
            self.send_header('Content-Length', str(len(body)))
            if download:
                self.send_header('Content-Disposition', 'attachment; filename="shroot-snap.txt"')
            self.end_headers()
            self.wfile.write(body)
            if download:
                with lock:
                    downloads.append(True)

        def do_POST(self):
            length = int(self.headers.get('Content-Length', '0'))
            if self.path != '/receipt' or not 0 < length <= 262144:
                self.send_error(400)
                return
            value = json.loads(self.rfile.read(length))
            with lock:
                receipts.append(value)
                with (args.output / 'receipts.jsonl').open('a') as stream:
                    stream.write(json.dumps(value) + '\n')
            self.send_response(200)
            self.end_headers()

    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    if args.reuse_work:
        downloaded.unlink()
    worker = threading.Thread(target=server.serve_forever)
    worker.start()
    try:
        print('Type shroot, click Click, download, then select: ' + str(work / 'shroot-snap.txt'), flush=True)
        print('Close the browser normally to finish. Profile: ' + str(profile), flush=True)
        command = launcher + options + ['http://127.0.0.1:' + str(server.server_address[1]) + '/']
        print('Command: ' + json.dumps(command), flush=True)
        result = subprocess.run(command, env=environment)
    finally:
        server.shutdown()
        worker.join()
        server.server_close()
    downloaded = work / 'shroot-snap.txt'
    checks = {
        'exit': result.returncode == 0,
        'keyboard': {'type': 'input', 'value': 'shroot'} in receipts,
        'pointer': {'type': 'click', 'value': 'ok'} in receipts,
        'download': bool(downloads) and downloaded.is_file() and downloaded.read_bytes() == DATA,
        'chooser': {'type': 'upload', 'value': DATA.decode()} in receipts,
    }
    gpu = [r['value'] for r in receipts if r.get('type') == 'gpu']
    if args.require_webgl:
        checks['webgl'] = any(v and v.get('pixel') == [0, 255, 0, 255] for v in gpu)
    report = dict(passed=all(checks.values()), browser=args.browser, package=args.package,
                  entry=args.entry,
                  command=command, checks=checks, gpu=gpu, work=str(work), reusedProfile=bool(args.reuse_work),
                  sha256=hashlib.sha256(DATA).hexdigest())
    (args.output / 'gui-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
