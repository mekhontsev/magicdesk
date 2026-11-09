"""Offline borrowing preserves inode identity and rejects unsafe shared access."""
from pathlib import Path
from contextlib import ExitStack
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

lab = Path(__file__).resolve().parents[2] / 'native/guest-exec-lab'
if os.name == 'posix':
    import fcntl
    with patch.object(sys, 'path', [str(lab), *sys.path]):
        import borrowed_store as borrow


@unittest.skipUnless(os.name == 'posix', 'Host access metadata requires POSIX')
class BorrowedStoreTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.labels = Mock()
        self.labels.get.side_effect = lambda path, name='security.selinux': b'shell' if name == 'security.selinux' else b''

    def test_inventory_does_not_follow_guest_symlinks(self):
        store = self.root / 'store'
        store.mkdir()
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'private').write_text('untouched')
        (store / 'link').symlink_to(outside, target_is_directory=True)
        records = borrow.inventory([store], self.labels)
        self.assertEqual(2, records[0]['entries'])
        self.assertFalse(any(call.args[0] == outside / 'private' for call in self.labels.get.call_args_list))

    def test_hardlinks_must_remain_inside_locked_closure(self):
        store = self.root / 'store'
        store.mkdir()
        (store / 'a').write_text('data')
        info = list(os.stat(store / 'a'))
        info[3] = 2
        linked = os.stat_result(info)
        scanned = [(store, os.stat(store)), (store / 'a', linked)]
        with patch.object(borrow, 'entries', return_value=scanned):
            with self.assertRaisesRegex(RuntimeError, 'hardlink'):
                borrow.inventory([store], self.labels)
        with patch.object(borrow, 'entries', return_value=[*scanned, (store / 'b', linked)]):
            self.assertEqual(3, borrow.inventory([store], self.labels)[0]['entries'])

    def test_mixed_host_access_is_not_silently_normalized(self):
        path = self.root / 'file'
        path.touch()
        self.labels.get.side_effect = lambda item, name='security.selinux': (
            b'' if name != 'security.selinux' else b'other' if item == path else b'shell')
        with self.assertRaisesRegex(RuntimeError, 'Mixed host'):
            borrow.inventory([self.root], self.labels)

    def test_host_file_capabilities_are_not_silently_lost(self):
        path = self.root / 'file'
        path.touch()
        self.labels.get.side_effect = lambda item, name='security.selinux': (
            b'capability' if item == path and name == 'security.capability' else b'shell')
        with self.assertRaisesRegex(RuntimeError, 'capabilities'):
            borrow.inventory([self.root], self.labels)

    def test_chown_preserves_physical_set_id_mode(self):
        path = self.root / 'file'
        path.touch()
        original = os.stat_result((stat.S_IFREG | 0o4755, 1, 2, 1, 2000, 2000, 0, 0, 0, 0))
        with patch.object(borrow.os, 'chown') as chown, patch.object(borrow.os, 'chmod') as chmod:
            borrow.set_access(path, original, [10647, 10647, b'shell'.hex()], self.labels)
        chown.assert_called_once_with(path, 10647, 10647, follow_symlinks=False)
        chmod.assert_called_once_with(path, 0o4755, follow_symlinks=False)
        self.labels.set.assert_not_called()

    def test_journal_is_replaceable_and_keeps_recovery_identity(self):
        journal = self.root / 'journal.json'
        data = dict(restored=False, identity=[1, 2])
        borrow.write_journal(journal, data)
        self.assertEqual(data, json.loads(journal.read_text()))
        data['restored'] = True
        borrow.write_journal(journal, data)
        self.assertEqual(data, json.loads(journal.read_text()))
        self.assertEqual(0o600, stat.S_IMODE(journal.stat().st_mode))

    def test_closure_reuses_native_sources_and_deduplicates(self):
        store = self.root / 'instances/test'
        layer = self.root / 'layers/layer'
        store.mkdir(parents=True)
        (store / 'namespace.db').touch()
        (layer / 'objects').mkdir(parents=True)
        sources = json.dumps([dict(path='../../layers/layer/objects')] * 2)
        with patch.object(borrow.subprocess, 'check_output', return_value=sources) as inspect:
            self.assertEqual(sorted([store, layer]), borrow.closure(self.root, store, Path('/image')))
        inspect.assert_called_once_with(['/image', 'sources', str(store)], text=True)

    def test_closure_rejects_absolute_and_external_sources(self):
        store = self.root / 'instances/test'
        store.mkdir(parents=True)
        (store / 'namespace.db').touch()
        for source in ('/external/objects', '../../../external/objects'):
            with self.subTest(source=source), patch.object(borrow.subprocess, 'check_output',
                    return_value=json.dumps([dict(path=source)])):
                with self.assertRaises((ValueError, FileNotFoundError)):
                    borrow.closure(self.root, store, Path('/image'))

    def test_live_native_lease_blocks_preparation_and_restoration(self):
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
            records = [dict(path=str(self.root), identity=borrow.identity(self.root))]
            with ExitStack() as stack, self.assertRaises(BlockingIOError):
                borrow.lock_stores(stack, records)
            with patch.object(borrow, 'set_access') as change, self.assertRaises(BlockingIOError):
                borrow.restore(self.root / 'journal.json', dict(roots=records), self.labels)
            change.assert_not_called()
        finally:
            os.close(fd)

    def test_restore_rejects_a_replaced_store(self):
        records = [dict(path=str(self.root), identity=[0, 0])]
        with ExitStack() as stack, self.assertRaisesRegex(RuntimeError, 'identity'):
            borrow.lock_stores(stack, records)

    def test_partial_preparation_restores_original_and_new_files(self):
        source = self.root / 'source'
        source.mkdir()
        (source / 'old').write_text('same bytes')
        (source / 'new').touch()
        target = self.root / 'borrowed'
        (target / 'instances/store').mkdir(parents=True)
        original = borrow.identity(source / 'old')
        record = dict(path=str(source), target=str(target / 'instances/store'),
                      identity=borrow.identity(source), default=[2000, 2000, b'shell'.hex()])
        data = dict(roots=[record], target=str(target), restored=False)
        journal = self.root / 'journal.json'
        with patch.object(borrow, 'mounts', return_value=set()), patch.object(borrow, 'set_access') as change:
            borrow.restore(journal, data, self.labels)
        self.assertEqual({source, source / 'old', source / 'new'}, {call.args[0] for call in change.call_args_list})
        self.assertTrue(all(call.args[2] == record['default'] for call in change.call_args_list))
        self.assertEqual(original, borrow.identity(source / 'old'))
        self.assertEqual('same bytes', (source / 'old').read_text())
        self.assertFalse(target.exists())
        self.assertTrue(json.loads(journal.read_text())['restored'])

    def test_retention_requires_successful_exposure(self):
        data = dict(store='/private/store', roots=[dict(entries=2)])
        argv = ['borrowed_store.py', 'prepare', '--journal', str(self.root / 'journal.json'),
                '--library', '/library', '--store', '/library/store', '--image', '/image',
                '--target', '/private/store', '--uid', '10647']
        for retain, fail in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(retain=retain, fail=fail), ExitStack() as stack:
                stack.enter_context(patch.object(sys, 'argv', argv + (['--retain'] if retain else [])))
                stack.enter_context(patch.object(sys, 'stdin', io.StringIO('')))
                output = stack.enter_context(patch.object(sys, 'stdout', io.StringIO()))
                stack.enter_context(patch.object(borrow.os, 'getuid', return_value=0))
                stack.enter_context(patch.object(borrow.os, 'readlink', return_value='mnt:[1]'))
                stack.enter_context(patch.object(borrow.signal, 'signal'))
                stack.enter_context(patch.object(borrow, 'Labels', return_value=self.labels))
                stack.enter_context(patch.object(borrow, 'prepare', return_value=(data, [])))
                stack.enter_context(patch.object(borrow, 'expose',
                                                side_effect=OSError('bind failed') if fail else None))
                restore = stack.enter_context(patch.object(borrow, 'restore'))
                if fail:
                    with self.assertRaisesRegex(OSError, 'bind failed'):
                        borrow.main()
                    self.assertEqual('', output.getvalue())
                else:
                    borrow.main()
                    self.assertEqual(retain, json.loads(output.getvalue())['retained'])
                if fail or not retain:
                    restore.assert_called_once_with(self.root / 'journal.json', data, self.labels)
                else:
                    restore.assert_not_called()

    def test_preparation_failure_keeps_its_diagnostic_and_closes_helper(self):
        suite = Mock()
        suite.executor.mode = 'app'
        suite.executor.home = '/private/app'
        suite.executor.uid = 10647
        suite.output = self.root / 'output/report.json'
        suite.output.parent.mkdir()
        suite.report = dict(id='preparation')
        suite.image = '/image'
        process = Mock(stdin=io.StringIO(), stdout=io.StringIO(''))
        process.wait.return_value = 1
        with patch.object(borrow.subprocess, 'Popen', return_value=process) as spawn:
            with self.assertRaisesRegex(RuntimeError, 'Borrow preparation failed; see .*\\.log'):
                borrow.BorrowedStore(suite, self.root, self.root / 'store')
        self.assertTrue(process.stdin.closed)
        self.assertTrue(process.stdout.closed)
        self.assertTrue(spawn.call_args.kwargs['stderr'].closed)
        process.wait.assert_called_once_with()

    def test_helper_failure_without_a_primary_error_requires_recovery(self):
        helper = borrow.BorrowedStore.__new__(borrow.BorrowedStore)
        helper.journal = self.root / 'journal.json'
        helper.process = Mock(stdin=io.StringIO(), stdout=io.StringIO(''))
        helper.process.wait.return_value = 1
        helper.log = io.StringIO()
        with self.assertRaisesRegex(RuntimeError, 'Borrow helper failed; retain/recover journal'):
            helper.close()


if __name__ == '__main__':
    unittest.main()
