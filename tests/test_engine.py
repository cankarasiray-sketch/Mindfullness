from datetime import timedelta

import pytest

from bilincli.config import StrategyConfig
from bilincli.engine import best_proposals, build_candidates, decide

from .helpers import NOW, make_pair

FAIR = (0.50, 0.28, 0.22)


def _cfg(**kw):
    return StrategyConfig(**kw)


def test_no_value_means_pass():
    # iddaa oranları adil orandan düşük (marjlı) -> pas
    pairs = [make_pair(i, (1.80, 3.20, 4.00), FAIR) for i in range(5)]
    d = decide([b for b, _ in pairs], [s for _, s in pairs], NOW, _cfg())
    assert d.is_pass
    assert "pas" in d.reason.lower()
    assert d.stats["eslesen"] == 5


def test_value_selection_found_and_staked():
    pairs = [make_pair(1, (2.30, 3.20, 4.00), FAIR)]  # 0.5 * 2.3 = 1.15 -> +%15
    d = decide([pairs[0][0]], [pairs[0][1]], NOW, _cfg())
    assert not d.is_pass
    leg = d.proposal.legs[0]
    assert leg.outcome == "1"
    assert leg.ev == pytest.approx(0.15)
    assert 0 < d.proposal.stake_fraction <= 0.03


def test_mbs_forces_combo_size():
    cfg = _cfg(min_win_prob=0.05)
    only = [make_pair(1, (2.30, 3.2, 4.0), FAIR, mbs=3)]
    cands, _ = build_candidates([(b, s, 1.0) for b, s in only], NOW, cfg)
    assert best_proposals(cands, cfg) == []  # MBS 3'lük maçla tek başına kupon olmaz

    pairs = only + [make_pair(2, (2.25, 3.2, 4.0), FAIR), make_pair(3, (2.20, 3.2, 4.0), FAIR)]
    cands, _ = build_candidates([(b, s, 1.0) for b, s in pairs], NOW, cfg)
    for p in best_proposals(cands, cfg, top=10):
        assert len(p.legs) >= max(leg.mbs for leg in p.legs)


def test_one_selection_per_match():
    b, s = make_pair(1, (2.30, 3.90, 5.00), FAIR)  # hem 1 hem X hem 2 avantajlı
    cfg = _cfg(min_win_prob=0.0, max_leg_odds=10, min_coupon_odds=1.0)
    cands, _ = build_candidates([(b, s, 1.0)], NOW, cfg)
    assert len(cands) == 3
    for p in best_proposals(cands, cfg, top=10):
        assert len({leg.book.ref for leg in p.legs}) == len(p.legs)


def test_time_window_filters():
    cfg = _cfg()
    soon = make_pair(1, (2.3, 3.2, 4.0), FAIR, hours=0.25)  # 30 dk'dan az kaldı
    late = make_pair(2, (2.3, 3.2, 4.0), FAIR, hours=30)  # 24 saat dışı
    cands, allc = build_candidates([(b, s, 1.0) for b, s in (soon, late)], NOW, cfg)
    assert cands == [] and allc == []


def test_lottery_coupons_rejected():
    # Avantajlı ama hepsi yüksek oranlı: toplam tutma şansı %20'nin altında kalır.
    fair = (0.30, 0.30, 0.40)
    pairs = [make_pair(i, (3.45, 3.0, 2.2), fair, mbs=3) for i in range(4)]
    d = decide([b for b, _ in pairs], [s for _, s in pairs], NOW, _cfg())
    assert d.is_pass


def test_prefers_growth_over_raw_odds():
    pairs = [make_pair(1, (2.30, 3.2, 4.0), FAIR), make_pair(2, (2.20, 3.2, 4.0), FAIR,
                                                               hours=11)]
    d = decide([b for b, _ in pairs], [s for _, s in pairs], NOW, _cfg())
    p = d.proposal
    assert p.growth > 0
    assert p.ev >= 0.05
    assert NOW + timedelta(minutes=30) <= min(leg.book.kickoff for leg in p.legs)
