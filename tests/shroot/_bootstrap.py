"""MagicDesk integration tests reuse the selected Shroot checkout's fixtures."""
import os
from pathlib import Path
import sys

SOURCE = Path(__file__).resolve().parents[2]
SHROOT = Path(os.environ.get('SHROOT_SOURCE', SOURCE / 'native/shroot')).resolve(strict=True)
SHROOT_TESTS = SHROOT / 'tests'
sys.path.insert(1, str(SHROOT_TESTS))
os.environ.setdefault('SHROOT_TEST_ADAPTER', 'magicdesk_executor')


def fixture(name):
    """Application integration payloads stay here; engine payloads stay upstream."""
    local = Path(__file__).parent / 'fixtures' / name
    return (local if local.is_file() else SHROOT_TESTS / 'fixtures' / name).resolve(strict=True)
