import pytest

from bilincli.ledger import Ledger

from .helpers import Clock


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def ledger(tmp_path, clock):
    lg = Ledger(tmp_path / "kasa.sqlite3", clock=clock)
    yield lg
    lg.close()
