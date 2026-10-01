import pytest

from bilincli.ledger import LedgerError, fmt_tl, parse_tl
from bilincli.models import LOST, VOID, WON, Candidate, Proposal

from .helpers import make_pair


def _proposal(n=2, odds=2.0):
    legs = []
    for i in range(n):
        b, s = make_pair(i, (odds, 3.4, 4.0), (0.55, 0.25, 0.20))
        legs.append(Candidate(b, s, "MS", "1", odds, 0.55))
    total = odds**n
    prob = 0.55**n
    return Proposal(legs=legs, odds=total, prob=prob, stake_fraction=0.02, growth=0.001)


@pytest.mark.parametrize("text,kurus", [
    ("1500", 150000), ("1500.50", 150050), ("1.500,50", 150050), ("1500,5", 150050),
    ("250 TL", 25000), ("₺75", 7500), ("5.000", 500000), ("1.250.000", 125000000),
    ("2.50", 250),
])
def test_parse_tl(text, kurus):
    assert parse_tl(text) == kurus


@pytest.mark.parametrize("bad", ["", "abc", "-5", "0", "nan", "inf"])
def test_parse_tl_rejects(bad):
    with pytest.raises(LedgerError):
        parse_tl(bad)


def test_fmt_tl():
    assert fmt_tl(123456789) == "1.234.567,89 TL"
    assert fmt_tl(-5000) == "-50,00 TL"


def test_deposit_withdraw_balance(ledger):
    ledger.deposit(100000)
    ledger.withdraw(30000)
    assert ledger.balance() == 70000
    with pytest.raises(LedgerError):
        ledger.withdraw(80000)


def test_played_coupon_won(ledger):
    ledger.deposit(100000)
    cid = ledger.add_coupon(_proposal(), "2026-10-01", 2000)
    ledger.mark_played(cid, 2000)
    assert ledger.balance() == 98000
    for leg in ledger.legs(cid):
        ledger.set_leg_result(leg["id"], WON, "2-0")
    assert ledger.settle_coupon(cid) == WON
    assert ledger.balance() == 98000 + 8000  # 20 TL x 4.00
    s = ledger.stats()
    assert s.played == 1 and s.won == 1
    assert s.betting_pl == 6000
    assert s.roi == pytest.approx(3.0)


def test_played_coupon_lost_settles_on_first_loss(ledger):
    ledger.deposit(100000)
    cid = ledger.add_coupon(_proposal(), "2026-10-01", 2000)
    ledger.mark_played(cid, 2000)
    first = ledger.legs(cid)[0]
    ledger.set_leg_result(first["id"], LOST, "0-1")
    assert ledger.settle_coupon(cid) == LOST  # diğer maç beklenmeden yattı
    assert ledger.balance() == 98000
    assert ledger.stats().betting_pl == -2000


def test_void_leg_counts_as_one(ledger):
    ledger.deposit(100000)
    cid = ledger.add_coupon(_proposal(), "2026-10-01", 2000)
    ledger.mark_played(cid, 2000)
    legs = ledger.legs(cid)
    ledger.set_leg_result(legs[0]["id"], VOID)
    assert ledger.settle_coupon(cid) is None  # ikinci maç bekleniyor
    ledger.set_leg_result(legs[1]["id"], WON, "1-0")
    assert ledger.settle_coupon(cid) == WON
    assert ledger.balance() == 98000 + 4000  # yalnızca 2.00


def test_unplayed_suggestion_tracked_without_money(ledger):
    ledger.deposit(100000)
    cid = ledger.add_coupon(_proposal(), "2026-10-01", 2000)
    for leg in ledger.legs(cid):
        ledger.set_leg_result(leg["id"], WON)
    assert ledger.settle_coupon(cid) == WON
    assert ledger.balance() == 100000
    s = ledger.stats()
    assert s.played == 0 and s.suggested_won == 1


def test_mark_played_validations(ledger):
    ledger.deposit(1000)
    cid = ledger.add_coupon(_proposal(), "2026-10-01", 2000)
    with pytest.raises(LedgerError):
        ledger.mark_played(cid, 2000)  # kasa yetmiyor
    with pytest.raises(LedgerError):
        ledger.mark_played(cid, 500, leg_odds=[1.9])  # eksik oran
    assert ledger.balance() == 1000
    ledger.mark_played(cid, 500, leg_odds=[1.9, 2.1])
    assert ledger.coupon(cid)["total_odds"] == pytest.approx(3.99)
    with pytest.raises(LedgerError):
        ledger.mark_played(cid, 500)  # iki kez oynanamaz
