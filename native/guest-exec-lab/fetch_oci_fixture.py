#!/usr/bin/env python3
"""Fetch a public registry ARM64 image as an OCI layout for runtime fixtures.

Development input only, not an APK registry client. Retains original verified
descriptors and compressed blobs; it neither extracts files nor executes code.
"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

ACCEPT = ', '.join(('application/vnd.oci.image.index.v1+json',
                    'application/vnd.docker.distribution.manifest.list.v2+json',
                    'application/vnd.oci.image.manifest.v1+json',
                    'application/vnd.docker.distribution.manifest.v2+json'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repository', help='For example library/alpine')
    parser.add_argument('reference', help='Explicit tag or sha256 digest')
    parser.add_argument('destination', type=Path)
    parser.add_argument('--registry', default='registry-1.docker.io',
                        choices=('registry-1.docker.io', 'mcr.microsoft.com', 'quay.io'))
    parser.add_argument('--resume', action='store_true', help='Reuse only size/digest-verified downloaded blobs')
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=args.resume)
    blobs = args.destination / 'blobs/sha256'
    blobs.mkdir(parents=True, exist_ok=args.resume)
    query = urllib.parse.urlencode({'service': 'registry.docker.io',
                                   'scope': f'repository:{args.repository}:pull'})
    headers = {'Accept': ACCEPT}
    if args.registry == 'registry-1.docker.io':
        with urllib.request.urlopen('https://auth.docker.io/token?' + query, timeout=60) as response:
            headers['Authorization'] = 'Bearer ' + json.load(response)['token']
    base = 'https://' + args.registry + '/v2/' + args.repository

    def get(path, descriptor=None):
        if descriptor and args.resume:
            cached = blobs / descriptor['digest'].removeprefix('sha256:')
            if cached.is_file():
                data = cached.read_bytes()
                if len(data) == descriptor['size'] and hashlib.sha256(data).hexdigest() == cached.name:
                    return data, descriptor
        print('Fetching ' + path, flush=True)
        request = urllib.request.Request(base + path, headers=headers)
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read(256 * 1024 * 1024 + 1)
            media = response.headers.get_content_type()
        assert len(data) <= 256 * 1024 * 1024
        digest = hashlib.sha256(data).hexdigest()
        if descriptor:
            assert descriptor['digest'] == 'sha256:' + digest and descriptor['size'] == len(data), {
                'expected': descriptor, 'actualDigest': 'sha256:' + digest, 'actualSize': len(data),
                'url': base + path, 'mediaType': media}
        (blobs / digest).write_bytes(data)
        return data, {'mediaType': media, 'digest': 'sha256:' + digest, 'size': len(data)}

    raw, selected = get('/manifests/' + args.reference)
    manifest = json.loads(raw)
    if 'manifests' in manifest:
        matches = [entry for entry in manifest['manifests']
                   if entry.get('platform', {}).get('os') == 'linux'
                   and entry['platform'].get('architecture') == 'arm64'
                   and entry['platform'].get('variant', 'v8') == 'v8']
        assert len(matches) == 1, matches
        selected = matches[0]
        raw, _ = get('/manifests/' + selected['digest'], selected)
        manifest = json.loads(raw)
    for descriptor in [manifest['config'], *manifest['layers']]:
        get('/blobs/' + descriptor['digest'], descriptor)
    selected.setdefault('annotations', {})['org.opencontainers.image.ref.name'] = 'fixture'
    (args.destination / 'oci-layout').write_text(json.dumps({'imageLayoutVersion': '1.0.0'}) + '\n')
    (args.destination / 'index.json').write_text(json.dumps({'schemaVersion': 2, 'manifests': [selected]}) + '\n')
    print(json.dumps({'registry': args.registry, 'repository': args.repository,
                      'reference': args.reference, 'manifest': selected['digest']}))


if __name__ == '__main__':
    main()
