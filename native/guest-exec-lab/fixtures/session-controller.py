#!/usr/bin/env python3
"""Authenticated loopback command channel for a retained init/desktop fixture."""
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import threading

directory, port, token, *address = sys.argv[1:]
directory = Path(directory)


def execute(ident, command):
    with (directory / (ident + '.log')).open('w') as log:
        result = subprocess.run(['bash', '-lc', command], stdout=log, stderr=subprocess.STDOUT)
    (directory / (ident + '.exit')).write_text(str(result.returncode) + '\n')
    return result.returncode


class Handler(BaseHTTPRequestHandler):
    def authorized(self):
        if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
            self.send_error(403)
            return False
        return True

    def do_GET(self):
        if not self.authorized():
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps({'uid': os.getuid()}).encode())

    def do_POST(self):
        if not self.authorized():
            return
        size = int(self.headers.get('Content-Length', '0'))
        if not 0 < size <= 65536:
            self.send_error(400)
            return
        request = json.loads(self.rfile.read(size))
        ident = request['id']
        if not ident.isalnum() or (directory / (ident + '.log')).exists():
            self.send_error(409)
            return
        result = {'id': ident, 'uid': os.getuid()}
        if request.get('wait'):
            result['exitCode'] = execute(ident, request['command'])
        else:
            threading.Thread(target=execute, args=(ident, request['command'])).start()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps(result).encode())


ThreadingHTTPServer((address[0] if address else '127.0.0.1', int(port)), Handler).serve_forever()
