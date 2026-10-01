"""Maç sonuçlarını çekip açık kuponları sonuçlandırma."""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timedelta

from .ledger import Ledger
from .models import LOST, WON, ScoreResult

ScoreFetcher = Callable[[set[str]], dict[str, ScoreResult]]

# Maç başladıktan bu kadar sonra sonucu sorgula (90 dk + devre + uzatmalar).
RESULT_DELAY = timedelta(hours=2, minutes=15)
# Sonuç servisi en fazla 3 gün geriye bakabiliyor; sonrasında elle girilmeli.
MANUAL_AFTER = timedelta(days=3)


def leg_result(market: str, outcome: str, home: int, away: int) -> str:
    if market == "MS":
        actual = "1" if home > away else "2" if away > home else "X"
    elif market == "AU25":
        actual = "UST" if home + away >= 3 else "ALT"
    else:
        raise ValueError(f"bilinmeyen pazar: {market}")
    return WON if outcome == actual else LOST


def settle_open(ledger: Ledger, fetch_scores: ScoreFetcher, now: datetime) -> list[str]:
    """Açık kuponları günceller; kullanıcıya gösterilecek mesajları döndürür."""
    messages: list[str] = []
    coupons = ledger.open_coupons()
    due_legs = []
    for c in coupons:
        for leg in ledger.legs(c["id"]):
            if leg["result"] is None and datetime.fromisoformat(leg["kickoff"]) + RESULT_DELAY <= now:
                due_legs.append(leg)
    if not due_legs:
        return messages

    sports = {leg["sport_key"] for leg in due_legs if leg["sport_key"]}
    scores = fetch_scores(sports) if sports else {}

    for leg in due_legs:
        score = scores.get(leg["sharp_ref"])
        label = f"{leg['home']} - {leg['away']}"
        if score and score.completed and score.home_goals is not None:
            result = leg_result(leg["market"], leg["outcome"], score.home_goals, score.away_goals)
            ledger.set_leg_result(leg["id"], result, f"{score.home_goals}-{score.away_goals}")
        elif datetime.fromisoformat(leg["kickoff"]) + MANUAL_AFTER <= now:
            messages.append(f"#{leg['coupon_id']} {label}: sonuç bulunamadı (ertelenmiş olabilir). "
                            f"Elle gir: bilincli sonuc {leg['coupon_id']} {leg['position']} "
                            f"kazandi|kaybetti|iade")

    for c in coupons:
        outcome = ledger.settle_coupon(c["id"])
        if outcome is None:
            continue
        c = ledger.coupon(c["id"])
        kind = "Oynanan" if c["played"] else "Oynanmayan öneri"
        if outcome == WON:
            extra = f", ödeme {c['payout'] / 100:.2f} TL" if c["played"] else ""
            messages.append(f"{kind} kupon #{c['id']} TUTTU (oran {c['total_odds']:.2f}{extra}).")
        elif outcome == LOST:
            messages.append(f"{kind} kupon #{c['id']} yattı.")
        else:
            messages.append(f"{kind} kupon #{c['id']} iade oldu.")
    return messages
