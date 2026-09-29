import {spawnSync} from 'node:child_process';

// Debian 12 archive primary key: https://ftp-master.debian.org/keys.html
export const archiveFingerprint = 'B8B80B5B623EAB6AD8775C45B7C5D7D6350947F8';
export function verifyRelease(keyring, home, signature, release) {
  const result = spawnSync('gpgv', ['--status-fd', '1', '--homedir', home,
    '--keyring', keyring, signature, release], {maxBuffer: 1024 * 1024});
  const status = result.stdout?.toString() || '';
  const signed = status.split('\n').some(line => {
    const fields = line.split(' ');
    return fields[0] === '[GNUPG:]' && fields[1] === 'VALIDSIG'
      && ['8', '9', '10'].includes(fields[9])
      && (fields[2] === archiveFingerprint || fields[fields.length - 1] === archiveFingerprint);
  });
  // Release.gpg can contain additional Debian signatures for keys not in this keyring.
  // Unknown additional keys are not failures, but an expired/revoked/bad signature is.
  if (result.error || result.signal || !signed || /\[GNUPG:\] (BADSIG|EXPSIG|EXPKEYSIG|REVKEYSIG|KEYEXPIRED|SIGEXPIRED)\b/.test(status))
    throw new Error(`Pinned Debian archive signature not verified: ${result.stderr || result.error}`);
}
