import math

import pytest

from bilincli import oddsmath


def test_margin_and_payout():
    odds = [2.0, 3.2, 3.4]
    assert oddsmath.margin(odds) == pytest.approx(0.5 + 1 / 3.2 + 1 / 3.4 - 1)
    assert oddsmath.payout_rate(odds) == pytest.approx(1 / (1 + oddsmath.margin(odds)))


@pytest.mark.parametrize("fn", [oddsmath.devig_proportional, oddsmath.devig_power])
def test_devig_sums_to_one(fn):
    probs = fn([1.5, 4.2, 6.5])
    assert sum(probs) == pytest.approx(1.0)
    assert probs[0] > probs[1] > probs[2]


def test_power_method_shifts_margin_to_longshots():
    odds = [1.30, 5.50, 10.0]
    prop = oddsmath.devig_proportional(odds)
    power = oddsmath.devig_power(odds)
    assert power[0] > prop[0]  # favori daha olası
    assert power[2] < prop[2]  # sürpriz daha az olası


def test_devig_fair_book_is_identity():
    assert oddsmath.devig_power([2.0, 2.0]) == pytest.approx([0.5, 0.5])


def test_invalid_odds_rejected():
    with pytest.raises(ValueError):
        oddsmath.margin([1.0, 2.0])


def test_expected_value_and_kelly():
    assert oddsmath.expected_value(0.5, 2.2) == pytest.approx(0.1)
    assert oddsmath.kelly_fraction(0.5, 2.2) == pytest.approx(0.1 / 1.2)
    assert oddsmath.kelly_fraction(0.4, 2.0) == 0.0  # avantaj yok -> bahis yok


def test_log_growth_peaks_at_kelly():
    p, o = 0.55, 2.0
    f = oddsmath.kelly_fraction(p, o)
    g = oddsmath.log_growth(p, o, f)
    assert g > oddsmath.log_growth(p, o, f * 0.5)
    assert g > oddsmath.log_growth(p, o, f * 1.5)
    assert oddsmath.log_growth(p, o, 1.0) == -math.inf


def test_combo_multiplies():
    p, o = oddsmath.combo([0.5, 0.6], [2.1, 1.8])
    assert p == pytest.approx(0.3)
    assert o == pytest.approx(3.78)
