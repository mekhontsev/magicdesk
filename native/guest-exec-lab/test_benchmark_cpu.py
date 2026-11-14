"""Host power observations do not become CPU-policy evidence."""
import unittest

from benchmark_cpu import observe_cpu, parse_snapshot, snapshot


class HostConditions(unittest.TestCase):
    def test_mixed_snapshot(self):
        output = ('policy0\trelated_cpus\t0 1\n'
                  'policy0\tscaling_cur_freq\t883200\n'
                  'policy0\tstats/time_in_state\t883200 123\n'
                  'battery:battery\ttemp\t390\n'
                  'battery:battery\tstatus\tCharging\n'
                  'battery:battery\tcapacity\t-\n')
        result = snapshot(lambda command: output)
        self.assertEqual(result['status'], 'available')
        self.assertEqual(set(result['policies']), {'policy0'})
        self.assertEqual(result['policies'], parse_snapshot(output))
        self.assertEqual(result['batteries']['battery'],
                         dict(temp='390', status='Charging', capacity=None))

    def test_denied_observation(self):
        def denied(command):
            raise PermissionError('host sysfs denied')
        result = snapshot(denied)
        self.assertEqual(result['status'], 'unavailable')
        self.assertEqual(result['policies'], {})

    def test_only_bracket_reads(self):
        calls = []
        def read(command):
            calls.append(command)
            return ''
        with observe_cpu(read) as result:
            self.assertEqual(len(calls), 1)
            self.assertNotIn('after', result)
        self.assertEqual(len(calls), 2)
        self.assertEqual(result['policies'], {})
        self.assertEqual(result['after']['batteries'], {})


if __name__ == '__main__':
    unittest.main()
