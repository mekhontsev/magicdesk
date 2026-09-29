import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {verifyRelease} from './verify-release.mjs';

const work = path.resolve(process.argv[2]);
const key = path.join(work, 'archive-key-12.gpg'), home = path.join(work, 'gnupg');
const signature = path.join(work, 'Release.gpg'), release = path.join(work, 'Release');
verifyRelease(key, home, signature, release);
const temp = fs.mkdtempSync(path.join(work, 'signature-test-'));
try {
  const changedRelease = path.join(temp, 'Release');
  const bytes = fs.readFileSync(release);
  bytes[0] ^= 1;
  fs.writeFileSync(changedRelease, bytes);
  assert.throws(() => verifyRelease(key, home, signature, changedRelease));
  const changedSignature = path.join(temp, 'Release.gpg');
  fs.writeFileSync(changedSignature, fs.readFileSync(signature).subarray(0, 32));
  assert.throws(() => verifyRelease(key, home, changedSignature, release));
  const wrongKey = path.join(temp, 'empty.gpg');
  fs.writeFileSync(wrongKey, '');
  assert.throws(() => verifyRelease(wrongKey, home, signature, release));
  console.log('PASS pinned signature; modified Release, truncated signature and missing key rejected');
} finally {
  fs.rmSync(temp, {recursive: true});
}
