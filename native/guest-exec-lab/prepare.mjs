#!/usr/bin/env node
// Build-only sysroot preparation, never package installation or maintainer scripts.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {parseArgs} from 'node:util';
import {archiveSuites, verifyRelease} from './verify-release.mjs';
import {packagePlan} from './package-plan.mjs';

const {values, positionals} = parseArgs({allowPositionals: true, options: {
  graphics: {type: 'boolean', default: false}, gtk: {type: 'boolean', default: false},
  applications: {type: 'boolean', default: false}, cache: {type: 'string'},
  qt: {type: 'boolean', default: false}, suite: {type: 'string', default: 'bookworm'},
}});
const [output] = positionals;
if (!output || positionals.length !== 1)
  throw new Error('Usage: node prepare.mjs OUTPUT_DIRECTORY [--graphics|--gtk|--applications|--qt] [--suite bookworm|trixie] [--cache PREPARED_DIRECTORY]');
const suite = values.suite;
const signingKey = archiveSuites[suite];
if (!signingKey) throw new Error('Expected bookworm or trixie');
if (values.qt) values.applications = true;
if (values.applications) values.gtk = true;
if (fs.existsSync(path.join(output, 'sysroot')))
  throw new Error('Use a fresh output directory; do not mix sysroot package versions');
const base = 'https://deb.debian.org/debian/';
const packages = ['libc6', 'libc6-dev', 'linux-libc-dev', 'dash', 'coreutils',
  'libselinux1', 'libpcre2-8-0', 'libgmp10', 'dpkg', 'tar', 'gzip',
  'libmd0', 'libbz2-1.0', 'liblzma5', 'libzstd1', 'zlib1g', 'libacl1', 'libattr1', 'libc-bin', 'diffutils',
  'libsqlite3-0', 'libsqlite3-dev'];
if (values.graphics || values.gtk) packages.push('libwayland-client0', 'libwayland-dev', 'libffi8',
  'wayland-protocols', 'xkb-data');
// Host extraction cannot preserve these packages' hard links. Keep their real
// archives, never substitute copies/symlinks. The sysroot is not an installed OS.
const archiveOnly = new Set(['gzip', 'perl', 'perl-base']);
fs.mkdirSync(output, {recursive: true});
function run(command, args) {
  const result = spawnSync(command, args, {maxBuffer: 80 * 1024 * 1024});
  if (result.status !== 0) throw new Error(`${command}: ${result.stderr || result.error}`);
  return result.stdout;
}
async function download(relative) {
  const cached = values.cache && path.join(values.cache, path.basename(relative));
  // Cached bytes pass exactly the same signature/hash checks as downloaded bytes.
  if (cached && fs.existsSync(cached)) return fs.readFileSync(cached);
  const response = await fetch(new URL(relative, base), {signal: AbortSignal.timeout(120000)});
  if (!response.ok) throw new Error(`${relative}: HTTP ${response.status}`);
  return Buffer.from(await response.arrayBuffer());
}
function hash(bytes) { return crypto.createHash('sha256').update(bytes).digest('hex'); }
const release = await download(`dists/${suite}/Release`);
const releaseFile = path.resolve(output, 'Release');
fs.writeFileSync(releaseFile, release);
// The archive key fingerprint is pinned, not trusted merely because HTTPS served a key.
// https://ftp-master.debian.org/keys.html
const keyFile = path.resolve(output, `archive-key-${signingKey.version}.asc`);
fs.writeFileSync(keyFile, await download(`https://ftp-master.debian.org/keys/archive-key-${signingKey.version}.asc`));
const keyring = path.resolve(output, `archive-key-${signingKey.version}.gpg`);
const keyHome = path.resolve(output, 'gnupg');
fs.mkdirSync(keyHome, {recursive: true, mode: 0o700});
run('gpg', ['--no-options', '--batch', '--yes', '--homedir', keyHome, '--dearmor', '--output', keyring, keyFile]);
const signatureFile = path.resolve(output, 'Release.gpg');
fs.writeFileSync(signatureFile, await download(`dists/${suite}/Release.gpg`));
verifyRelease(keyring, keyHome, signatureFile, releaseFile, suite);
const indexName = 'main/binary-arm64/Packages.xz';
const hashes = release.toString().split('SHA256:\n')[1]?.split('\nSHA')[0];
const indexRecord = hashes?.split('\n').map(line => line.trim().split(/\s+/))
  .find(fields => fields[2] === indexName);
if (!indexRecord) throw new Error('Release has no SHA256 for ARM64 package index');
const index = await download(`dists/${suite}/${indexName}`);
if (hash(index) !== indexRecord[0] || index.length !== Number(indexRecord[1]))
  throw new Error('Package index integrity mismatch');
const archive = path.join(output, 'Packages.xz');
fs.writeFileSync(archive, index);
const indexText = run('xz', ['-dc', archive]);
const records = indexText.toString().split('\n\n').map(record => {
  const fields = new Map();
  let key;
  for (const line of record.split('\n')) {
    if (/^[ \t]/.test(line) && key) fields.set(key, fields.get(key) + '\n' + line.slice(1));
    else {
      const colon = line.indexOf(':');
      if (colon < 1) continue;
      key = line.slice(0, colon);
      fields.set(key, line.slice(colon + 1).trim());
    }
  }
  return fields;
});
if (values.gtk) {
  const selected = packagePlan(output, indexText, records,
    ['gtk-3-examples', 'fontconfig', 'fonts-dejavu-core', 'libglib2.0-bin', 'dbus-x11',
      ...(values.applications ? ['mousepad', 'galculator', 'curl', 'ca-certificates'] : []),
      ...(values.qt ? ['qml-qt6', 'qt6-wayland', 'qml6-module-qtquick-controls',
        'qml6-module-qtquick-layouts', 'qml6-module-qtquick-window',
        'qml6-module-qtqml-workerscript', 'qml6-module-qtquick-templates'] : [])], suite);
  for (const name of selected) if (!packages.includes(name)) packages.push(name);
}
const root = path.join(output, 'sysroot');
fs.mkdirSync(root, {recursive: true});
const manifest = {suite, architecture: 'arm64', profile: values.applications ? 'applications' : values.gtk ? 'gtk' : values.graphics ? 'graphics' : 'base', base,
  trust: 'Pinned Debian archive OpenPGP signature and Release -> Packages -> deb SHA256 chain',
  signingKeyFingerprint: signingKey.fingerprint,
  releaseSha256: hash(release), indexSha256: hash(index), packages: []};
for (const name of packages) {
  const p = records.find(p => p.get('Package') === name && ['arm64', 'all'].includes(p.get('Architecture')));
  if (!p) throw new Error(`Package not found: ${name}`);
  const bytes = await download(p.get('Filename'));
  if (hash(bytes) !== p.get('SHA256') || bytes.length !== Number(p.get('Size')))
    throw new Error(`Package integrity mismatch: ${name}`);
  const file = path.join(output, path.basename(p.get('Filename')));
  fs.writeFileSync(file, bytes);
  if (!archiveOnly.has(name)) run('dpkg-deb', ['-x', file, root]);
  manifest.packages.push({name, version: p.get('Version'), file: p.get('Filename'),
    sha256: p.get('SHA256'), size: bytes.length, extracted: !archiveOnly.has(name)});
  process.stdout.write(`${name} ${p.get('Version')} verified${archiveOnly.has(name) ? ' (archive only)' : ' and extracted'}\n`);
}
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
// Debian's merged-/usr packages rely on these distribution aliases.
for (const directory of ['bin', 'sbin', 'lib']) {
  if (!fs.existsSync(path.join(root, directory))) fs.symlinkSync('usr/' + directory, path.join(root, directory));
}
