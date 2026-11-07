"""Stage the installed PRoot under the same shell identity as Shroot fixtures."""
import hashlib

ASSETS = ('bin/proot', 'libexec/proot/loader', 'libexec/proot/loader32',
          'lib/libtalloc.so.2', 'lib/libandroid-shmem.so')


def stage(suite, source_prefix):
    prefix = suite.base + '/proot-tools'
    suite.command(['mkdir', '-p', prefix + '/tmp'])
    hashes = {}
    for name in ASSETS:
        source = source_prefix / name
        target = prefix + '/' + source.name
        with source.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        suite.upload(source, target)
        assert suite.command(['sha256sum', target], quiet=True).split()[0] == digest
        hashes[name] = digest
        suite.chmod('700', target)
    invocation = ['env', '-i', 'PATH=/system/bin', 'LD_LIBRARY_PATH=' + prefix,
                  'PROOT_TMP_DIR=' + prefix + '/tmp', 'PROOT_LOADER=' + prefix + '/loader',
                  'PROOT_LOADER_32=' + prefix + '/loader32', prefix + '/proot']
    suite.report['proot'] = {
        'version': suite.command(invocation + ['--version'], quiet=True).strip(),
        'hashes': hashes, 'uid': 2000, 'guestIdentity': 'fake-root',
        'environment': 'clean; no PROOT_NO_SECCOMP or preload',
    }
    return invocation
