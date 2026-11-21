import _bootstrap
"""Keep sub-millisecond workload timing independent of GNU time rounding."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


lab = Path(__file__).resolve().parents[1]
with patch.object(sys, 'path', [str(lab), *sys.path]):
    spec = importlib.util.spec_from_file_location('benchmark_compile', lab / 'benchmark_compile.py')
    benchmark = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(benchmark)


def records(workload='file-stat', times=(0.001, 0.002, 0.003, 0.004)):
    return [dict(workload=workload, block=i, iterations=1024, seconds=value)
            for i, value in enumerate(times)]


def output(rows):
    return 'unrelated output\n' + ''.join('READINESS_BENCH ' + json.dumps(row) + '\n' for row in rows)


class BenchmarkCompileTest(unittest.TestCase):
    def test_target_is_geometric_per_workload(self):
        summary = {mode: {"medianSeconds": value} for mode, value in
                   (("chroot", 1), ("proot", 9), ("guest", 3))}
        result = benchmark.performance_target(summary)
        self.assertEqual(3, result['seconds'])
        self.assertEqual(1, result['guestOverTarget'])
        self.assertTrue(result['met'])
        summary['guest']['medianSeconds'] = 4
        self.assertFalse(benchmark.performance_target(summary)['met'])
        self.assertIsNone(benchmark.performance_target({'guest': summary['guest']}))
        for value in (0, -1, float('nan'), float('inf')):
            summary['guest']['medianSeconds'] = value
            with self.assertRaises(ValueError):
                benchmark.performance_target(summary)

    def test_preserves_all_blocks_and_sub_centisecond_timing(self):
        rows = records()
        measured = benchmark.loop_measurement(output(rows), 'file-stat', 1024, 4)
        self.assertEqual(rows, measured['blocks'])
        self.assertAlmostEqual(0.010, measured['workSeconds'])
        self.assertAlmostEqual(0.001, measured['firstBlockSeconds'])
        self.assertAlmostEqual(0.0035, measured['tailBlockSeconds'])

    def test_readiness_uses_one_block(self):
        for workload in ('poll', 'epoll', 'memory'):
            with self.subTest(workload=workload):
                measured = benchmark.loop_measurement(output(records(workload, (0.123,))), workload, 1024, 8)
                self.assertEqual(0.123, measured['workSeconds'])

    def test_rejects_partial_duplicate_or_mismatched_results(self):
        rows = records()
        for invalid in ([], rows[:-1], [rows[0]] * 4, list(reversed(rows)),
                        [dict(row, workload='file-open') for row in rows],
                        [dict(row, iterations=512) for row in rows],
                        *[[dict(row, seconds=value) for row in rows]
                          for value in (0, -1, float('nan'), float('inf'))]):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    benchmark.loop_measurement(output(invalid), 'file-stat', 1024, 4)

    def test_affinity_checks_both_endpoints(self):
        text = 'MD_AFFINITY before 00000000,0000000f\nMD_AFFINITY after f\n'
        self.assertEqual('f', benchmark.affinity_measurement(text, 15)['after'])
        for invalid in (text.replace('after f', 'after 3'), text.replace('before', 'after'), ''):
            with self.assertRaises(ValueError):
                benchmark.affinity_measurement(invalid, 15)
        with self.assertRaises(ValueError):
            benchmark.affinity_measurement(text, 3)

    def test_priority_is_equal_without_boosting_guests(self):
        text = 'MD_NICE before 0\nMD_NICE after 0\n'
        self.assertEqual({'before': 0, 'after': 0}, benchmark.priority_measurement(text))
        for invalid in ('', text.replace('before', 'after'), text.replace('after 0', 'after -10'),
                        text.replace('before 0', 'before 1')):
            with self.assertRaises(ValueError):
                benchmark.priority_measurement(invalid)


if __name__ == '__main__':
    unittest.main()
