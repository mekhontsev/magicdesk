"""Bounded system-manager readiness shared by nested boot fixtures."""
import threading
import time


def wait_systemd(probe, check_alive, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        assert remaining > 0, 'Nested system bus did not become ready'
        result = probe(remaining)
        if result.returncode == 0:
            assert result.stdout.strip() == 'running', result.stdout
            return
        # Process startup precedes the bus listener. An available manager may
        # still be starting; degraded/failed/offline must fail immediately.
        starting = result.returncode == 1 and result.stdout.strip() in ('initializing', 'starting')
        connecting = 'Failed to connect' in result.stderr and any(
            text in result.stderr for text in ('No such file or directory', 'Connection refused'))
        assert starting or connecting, result
        remaining = deadline - time.monotonic()
        assert remaining > 0, 'Nested system bus did not become ready'
        check_alive(remaining)
        # EVENT_WAIT: bounded bus/state reconciliation, never evidence of readiness.
        threading.Event().wait(min(0.1, max(0, deadline - time.monotonic())))
