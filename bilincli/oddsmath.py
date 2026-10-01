"""Oran matematiği: marj, marjdan arındırma, beklenen değer, Kelly."""

from __future__ import annotations

import math
from collections.abc import Sequence


def _check(odds: Sequence[float]) -> None:
    if not odds or any(o <= 1.0 for o in odds):
        raise ValueError(f"geçersiz oran listesi: {list(odds)}")


def implied_sum(odds: Sequence[float]) -> float:
    _check(odds)
    return sum(1.0 / o for o in odds)


def margin(odds: Sequence[float]) -> float:
    """Bahis şirketinin marjı (overround). 0.10 = %10."""
    return implied_sum(odds) - 1.0


def payout_rate(odds: Sequence[float]) -> float:
    """Oynanan her 1 TL'nin uzun vadede geri dönen kısmı."""
    return 1.0 / implied_sum(odds)


def devig_proportional(odds: Sequence[float]) -> list[float]:
    total = implied_sum(odds)
    return [(1.0 / o) / total for o in odds]


def devig_power(odds: Sequence[float]) -> list[float]:
    """Üs yöntemi: sum((1/o)^k) = 1 olacak k bulunur.

    Orantılı yönteme göre favori/sürpriz (favourite-longshot) yanlılığını
    daha iyi düzeltir; marjın daha büyük kısmını yüksek oranlardan düşer.
    """
    _check(odds)
    implied = [1.0 / o for o in odds]
    total = sum(implied)
    if abs(total - 1.0) < 1e-12:
        return implied
    lo, hi = (1.0, 50.0) if total > 1.0 else (0.01, 1.0)
    for _ in range(200):
        mid = (lo + hi) / 2.0
        if sum(p**mid for p in implied) > 1.0:
            lo = mid
        else:
            hi = mid
    k = (lo + hi) / 2.0
    probs = [p**k for p in implied]
    s = sum(probs)
    return [p / s for p in probs]


def expected_value(prob: float, odds: float) -> float:
    """1 TL bahis başına beklenen kâr. 0.05 = +%5."""
    return prob * odds - 1.0


def kelly_fraction(prob: float, odds: float) -> float:
    """Tam Kelly: kasanın yatırılması gereken oranı. Avantaj yoksa 0."""
    b = odds - 1.0
    if b <= 0:
        return 0.0
    return max(0.0, (prob * odds - 1.0) / b)


def log_growth(prob: float, odds: float, fraction: float) -> float:
    """Kasanın kupon başına beklenen logaritmik büyümesi."""
    if fraction <= 0:
        return 0.0
    if fraction >= 1:
        return -math.inf
    return prob * math.log1p(fraction * (odds - 1.0)) + (1.0 - prob) * math.log1p(-fraction)


def combo(probs: Sequence[float], odds: Sequence[float]) -> tuple[float, float]:
    """Bağımsız bacaklardan oluşan kombinenin (olasılık, toplam oran) değeri."""
    p = math.prod(probs)
    o = math.prod(odds)
    return p, o
