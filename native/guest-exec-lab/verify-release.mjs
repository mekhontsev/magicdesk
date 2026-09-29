import {spawnSync} from 'node:child_process';
import fs from 'node:fs';

// Debian 12 archive primary key: https://ftp-master.debian.org/keys.html
export const archiveFingerprint = 'B8B80B5B623EAB6AD8775C45B7C5D7D6350947F8';
export const archiveSuites = {
  bookworm: {version: 12, fingerprint: archiveFingerprint},
  // The selected trixie Release is signed by this still-active archive key.
  trixie: {version: 12, fingerprint: archiveFingerprint},
};
export function verifyRelease(keyring, home, signature, release, suite = 'bookworm') {
  const expected = archiveSuites[suite]?.fingerprint;
  if (!expected) throw new Error('Unsupported Debian suite');
  const result = spawnSync('gpgv', ['--status-fd', '1', '--homedir', home,
    '--keyring', keyring, signature, release], {maxBuffer: 1024 * 1024});
  const status = result.stdout?.toString() || '';
  const signed = status.split('\n').some(line => {
    const fields = line.split(' ');
    return fields[0] === '[GNUPG:]' && fields[1] === 'VALIDSIG'
      && ['8', '9', '10'].includes(fields[9])
      && (fields[2] === expected || fields[fields.length - 1] === expected);
  });
  // Release.gpg can contain additional Debian signatures for keys not in this keyring.
  // Unknown additional keys are not failures, but an expired/revoked/bad signature is.
  if (result.error || result.signal || !signed || /\[GNUPG:\] (BADSIG|EXPSIG|EXPKEYSIG|REVKEYSIG|KEYEXPIRED|SIGEXPIRED)\b/.test(status))
    throw new Error(`Pinned Debian archive signature not verified: ${result.stderr || result.error}`);
  if (!fs.readFileSync(release, 'utf8').split('\n').includes(`Codename: ${suite}`))
    throw new Error('Signed Release does not describe the requested suite');
}
