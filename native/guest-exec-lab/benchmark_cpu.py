"""Read-only CPU frequency evidence outside benchmark timing intervals."""
from contextlib import contextmanager
import time


# Shell builtins avoid starting a process for every sysfs file. No polling
# worker, governor changes or frequency locks run alongside the workload.
SNAPSHOT_COMMAND = r'''
for p in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -d "$p" ] || continue
    policy=${p##*/}
    for field in related_cpus scaling_cur_freq scaling_min_freq scaling_max_freq cpuinfo_max_freq scaling_governor; do
        if { IFS= read -r value < "$p/$field"; } 2>/dev/null; then
            printf '%s\t%s\t%s\n' "$policy" "$field" "$value"
        else
            printf '%s\t%s\t-\n' "$policy" "$field"
        fi
    done
    if { while read -r frequency ticks; do
        printf '%s\tstats/time_in_state\t%s %s\n' "$policy" "$frequency" "$ticks"
    done < "$p/stats/time_in_state"; } 2>/dev/null; then
        :
    else
        printf '%s\tstats/time_in_state\t-\n' "$policy"
    fi
done
for p in /sys/class/power_supply/*; do
    { IFS= read -r type < "$p/type"; } 2>/dev/null || continue
    [ "$type" = Battery ] || continue
    for field in temp status capacity; do
        if { IFS= read -r value < "$p/$field"; } 2>/dev/null; then
            printf 'battery:%s\t%s\t%s\n' "${p##*/}" "$field" "$value"
        else
            printf 'battery:%s\t%s\t-\n' "${p##*/}" "$field"
        fi
    done
done
'''


def parse_snapshot(output):
    policies = {}
    for line in output.splitlines():
        name, field, value = line.split('\t', 2)
        if name.startswith('battery:'):
            continue
        policy = policies.setdefault(name, {})
        if value == '-':
            policy[field] = None
        elif field == 'stats/time_in_state':
            frequency, ticks = map(int, value.split())
            if frequency <= 0 or ticks < 0:
                raise ValueError('invalid time_in_state counter')
            counters = policy.setdefault(field, {})
            if str(frequency) in counters:
                raise ValueError('duplicate time_in_state frequency')
            counters[str(frequency)] = ticks
        elif field.endswith('_freq'):
            policy[field] = int(value)
            if policy[field] <= 0:
                raise ValueError('invalid CPU frequency')
        else:
            policy[field] = value.strip()
    return policies


def snapshot(run_shell):
    result = {'startedUnixNs': time.time_ns()}
    try:
        output = run_shell(SNAPSHOT_COMMAND)
        result['policies'] = parse_snapshot(output)
        result['status'] = 'available' if result['policies'] else 'unavailable'
        result['batteries'] = {}
        for line in output.splitlines():
            name, field, value = line.split('\t', 2)
            if name.startswith('battery:'):
                result['batteries'].setdefault(name.removeprefix('battery:'), {})[field] = (
                    None if value == '-' else value.strip())
    except Exception as error:
        result.update(status='unavailable', error=str(error), policies={})
    result['finishedUnixNs'] = time.time_ns()
    return result


def policy_intervals(before, after):
    result = {}
    first, last = before['policies'], after['policies']
    for name in sorted(first.keys() | last.keys()):
        start, end = first.get(name, {}), last.get(name, {})
        row = result[name] = {'status': 'unavailable'}
        if not start or not end or start.get('related_cpus') != end.get('related_cpus'):
            row['reason'] = 'policy disappeared or CPU membership changed'
            continue
        row['relatedCpus'] = start.get('related_cpus')
        limits = ('scaling_min_freq', 'scaling_max_freq')
        row['endpointLimitsDiffer'] = (any(start[field] != end[field] for field in limits)
                                      if all(start.get(field) and end.get(field) for field in limits)
                                      else None)
        initial, final = start.get('stats/time_in_state'), end.get('stats/time_in_state')
        if not initial or not final:
            row['reason'] = 'time_in_state unavailable'
            continue
        if initial.keys() != final.keys():
            row['reason'] = 'frequency table changed'
            continue
        delta = {frequency: final[frequency] - ticks for frequency, ticks in initial.items()}
        if any(ticks < 0 for ticks in delta.values()):
            row['reason'] = 'time_in_state counters reset'
            continue
        row['timeInStateDeltaTicks'] = delta
        total = sum(delta.values())
        if not total:
            row['reason'] = 'no time_in_state counter advance'
            continue
        row.update(status='available',
                   residencyWeightedKhz=sum(int(frequency) * ticks for frequency, ticks in delta.items()) / total)
    return result


@contextmanager
def observe_cpu(run_shell):
    """Bracket a command using its host shell, never the guest's virtual sysfs.

    The envelope includes launch, warmup, teardown and transport. Policy-wide
    residency includes idle time and other tasks, not just the benchmark thread.
    """
    result = {
        'scope': 'host cpufreq policies across command envelope, not per-thread execution',
        'frequencyUnit': 'kHz',
        'residencyUnit': 'kernel time_in_state ticks',
        'batteryUnits': {'temp': 'tenths of degrees Celsius', 'capacity': 'percent'},
        'before': snapshot(run_shell),
    }
    try:
        yield result
    finally:
        result['after'] = snapshot(run_shell)
        result['policies'] = policy_intervals(result['before'], result['after'])


def observe_suite_cpu(suite):
    return observe_cpu(lambda command: suite.command(['/system/bin/sh', '-c', command], quiet=True))
