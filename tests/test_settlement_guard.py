from datetime import timedelta

import pytest

from bilincli.config import LimitConfig
from bilincli.guard import check_limits, week_start
from bilincli.models import LOST, TR, WON, Candidate, Proposal, ScoreResult
from bilincli.settlement import leg_result, settle_open

from .helpers import NOW, make_pair


@pytest.mark.parametrize("market,outcome,h,a,expected", [
    ("MS", "1", 2, 1, WON), ("MS", "X", 1, 1, WON), ("MS", "2", 1, 1, LOST),
    ("MS", "2", 0, 3, WON), ("AU25", "UST", 2, 1, WON), ("AU25", "ALT", 2, 1, LOST),
    ("AU25", "ALT", 1, 1, WON),
])
def test_leg_result(market, outcome, h, a, expected):
    assert leg_result(market, outcome, h, a) == expected


def _coupon(ledger, outcome="1"):
    b, s = make_pair(1, (2.2, 3.4, 4.0), (0.5, 0.28, 0.22), hours=10)
    p = Proposal(legs=[Candidate(b, s, "MS", outcome, 2.2, 0.5)], odds=2.2, prob=0.5,
                 stake_fraction=0.02, growth=0.001)
    return ledger.add_coupon(p, "2026-10-01", 2000)


def test_settle_open_with_scores(ledger, clock):
    ledger.deposit(100000)
    cid = _coupon(ledger)
    ledger.mark_played(cid, 2000)
    calls = []

    def fetch(sports):
        calls.append(sports)
        return {"s1": ScoreResult(True, 2, 0)}

    assert settle_open(ledger, fetch, NOW) == [] and calls == []  # maç başlamadı, sorgu yok
    clock.now = NOW + timedelta(hours=13)
    msgs = settle_open(ledger, fetch, clock.now)
    assert calls == [{"lig"}]
    assert "TUTTU" in msgs[0]
    assert ledger.balance() == 100000 - 2000 + 4400


def test_settle_open_asks_manual_after_three_days(ledger):
    cid = _coupon(ledger)
    msgs = settle_open(ledger, lambda s: {}, NOW + timedelta(days=4))
    assert "Elle gir" in msgs[0] and f"sonuc {cid} 1" in msgs[0]


def test_week_start_is_monday_0600_tr():
    ws = week_start(NOW)  # 1 Ekim 2026 Perşembe 06:00
    assert ws.weekday() == 0 and ws.hour == 6 and ws.tzinfo == TR
    assert ws < NOW.astimezone(TR) and NOW.astimezone(TR) - ws < timedelta(days=7)


def test_weekly_loss_limit(ledger, clock):
    cfg = LimitConfig(weekly_loss_limit=0.15, chase_cooldown_hours=0)
    clock.now = week_start(NOW) - timedelta(days=1)
    ledger.deposit(100000)
    clock.now = NOW
    assert check_limits(ledger, cfg, NOW) is None
    for _ in range(3):
        cid = _coupon(ledger)
        ledger.mark_played(cid, 5000)
        ledger.set_leg_result(ledger.legs(cid)[0]["id"], LOST)
        ledger.settle_coupon(cid)
    assert "Haftalık kayıp limiti" in check_limits(ledger, cfg, NOW)
    # Sonraki hafta limit sıfırlanır.
    assert check_limits(ledger, cfg, NOW + timedelta(days=7)) is None


def test_open_stake_not_counted_as_loss(ledger, clock):
    cfg = LimitConfig(weekly_loss_limit=0.15, chase_cooldown_hours=0)
    clock.now = week_start(NOW) - timedelta(days=1)
    ledger.deposit(100000)
    clock.now = NOW
    cid = _coupon(ledger)
    ledger.mark_played(cid, 3000)
    ledger.mark_played(_coupon(ledger), 3000)
    ledger.mark_played(_coupon(ledger), 3000)
    assert check_limits(ledger, cfg, NOW) is None


def test_chase_cooldown_only_when_losing(ledger, clock):
    cfg = LimitConfig(weekly_loss_limit=0, chase_cooldown_hours=48)
    ledger.deposit(100000)
    assert check_limits(ledger, cfg, NOW) is None  # ilk yatırma kovalama değil
    cid = _coupon(ledger)
    ledger.mark_played(cid, 5000)
    ledger.set_leg_result(ledger.legs(cid)[0]["id"], LOST)
    ledger.settle_coupon(cid)
    clock.now = NOW + timedelta(hours=1)
    ledger.deposit(50000)
    assert "Kovalama" in check_limits(ledger, cfg, clock.now)
    assert check_limits(ledger, cfg, clock.now + timedelta(hours=49)) is None
