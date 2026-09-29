// Build-only dependency selection. APT never installs, downloads or executes hooks.
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

export function packagePlan(output, index, records, seeds) {
  const root = path.resolve(output, 'apt-plan');
  const lists = path.join(root, 'lists');
  fs.mkdirSync(path.join(lists, 'partial'), {recursive: true});
  fs.mkdirSync(path.join(root, 'archives/partial'), {recursive: true});
  fs.mkdirSync(path.join(root, 'empty'), {recursive: true});
  fs.writeFileSync(path.join(root, 'status'), '');
  fs.writeFileSync(path.join(root, 'sources.list'), 'deb https://deb.debian.org/debian bookworm main\n');
  fs.writeFileSync(path.join(lists, 'deb.debian.org_debian_dists_bookworm_main_binary-arm64_Packages'), index);
  const config = path.join(root, 'apt.conf');
  fs.writeFileSync(config, `Dir "${root}";
Dir::Etc::main "-";
Dir::Etc::parts "${root}/empty";
Dir::Etc::sourcelist "${root}/sources.list";
Dir::Etc::sourceparts "${root}/empty";
Dir::Etc::preferences "-";
Dir::Etc::preferencesparts "${root}/empty";
Dir::State::status "${root}/status";
Dir::State::lists "${lists}";
Dir::Cache "${root}";
Dir::Cache::archives "${root}/archives";
Dir::Cache::pkgcache "";
Dir::Cache::srcpkgcache "";
APT::Architecture "arm64";
APT::Architectures { "arm64"; };
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Debug::NoLocking "true";
`);
  // Only the already authenticated index is available; every chosen archive is
  // subsequently checked against that index, independently of APT's trust state.
  const plan = spawnSync('apt-get', ['--print-uris', '--download-only', '--yes',
    '--allow-unauthenticated', 'install', ...seeds], {encoding: 'utf8',
    env: {...process.env, APT_CONFIG: config, LC_ALL: 'C'}, maxBuffer: 8 * 1024 * 1024});
  fs.writeFileSync(path.join(root, 'plan.txt'), (plan.stdout || '') + (plan.stderr || ''));
  if (plan.status !== 0) throw new Error(`Isolated APT plan failed: ${plan.stderr || plan.error}`);
  const selected = new Set();
  for (const line of plan.stdout.split('\n')) {
    if (!line.startsWith("'")) continue;
    const match = /^'([^']+)'\s+\S+\s+(\d+)\s+\S+$/.exec(line);
    if (!match) throw new Error('Invalid APT URI record');
    const url = new URL(match[1]);
    const record = records.find(p => url.origin === 'https://deb.debian.org'
      && !url.search && !url.hash && decodeURIComponent(url.pathname) === '/debian/' + p.get('Filename')
      && Number(match[2]) === Number(p.get('Size')));
    if (!record) throw new Error('APT selected an archive outside the authenticated index');
    selected.add(record.get('Package'));
  }
  for (const seed of seeds) if (!selected.has(seed)) throw new Error('APT omitted requested package: ' + seed);
  return [...selected].sort();
}
