"""Kupon motoru: avantajlı seçimleri bulur, MBS kuralına uyan en iyi kuponu kurar.

Mantık:
  1. Keskin piyasanın (varsayılan Pinnacle) marjı arındırılmış oranları, bir
     sonucun gerçek olasılığının en iyi tahminidir.
  2. iddaa oranı bu adil orandan yüksekse seçim pozitif beklenen değerlidir
     (avantaj = olasılık x iddaa oranı - 1).
  3. Kombinede olasılıklar ve oranlar çarpılır. MBS, kuponda en az kaç maç
     olması gerektiğini söyler; kupon büyüklüğü bacakların en yüksek MBS'inden
     küçük olamaz.
  4. Adaylar arasından kasanın beklenen logaritmik büyümesini en çok artıran
     kupon seçilir (kesirli Kelly). Bu ölçüt hem avantajı hem riski tartar;
     "yüksek oranlı ama tutmaz" kuponları kendiliğinden eler.
  5. Eşikleri geçen kupon yoksa karar PAS'tır. Oynamamak da bir pozisyondur.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from itertools import combinations
from statistics import mean

from . import oddsmath
from .config import StrategyConfig
from .matching import match_events
from .models import BookEvent, Candidate, Proposal, SharpEvent


@dataclass
class Decision:
    proposal: Proposal | None
    alternatives: list[Proposal] = field(default_factory=list)
    reason: str = ""
    stats: dict = field(default_factory=dict)

    @property
    def is_pass(self) -> bool:
        return self.proposal is None


def build_candidates(
    pairs: list[tuple[BookEvent, SharpEvent, float]], now: datetime, cfg: StrategyConfig
) -> tuple[list[Candidate], list[Candidate]]:
    """(filtreyi geçen adaylar, karşılaştırılabilen tüm seçimler) döndürür."""
    earliest = now + timedelta(minutes=cfg.min_lead_minutes)
    latest = now + timedelta(hours=cfg.window_hours)
    allc: list[Candidate] = []
    good: list[Candidate] = []
    for book, sharp, _score in pairs:
        if not earliest <= book.kickoff <= latest:
            continue
        for market, outcomes in book.odds.items():
            fair = sharp.fair.get(market)
            if not fair:
                continue
            for outcome, odds in outcomes.items():
                prob = fair.get(outcome)
                if prob is None or odds <= 1.0:
                    continue
                c = Candidate(book=book, sharp=sharp, market=market, outcome=outcome,
                              odds=odds, prob=prob)
                allc.append(c)
                if (cfg.min_leg_odds <= odds <= cfg.max_leg_odds
                        and c.ev >= cfg.min_leg_ev):
                    good.append(c)
    good.sort(key=lambda c: c.ev, reverse=True)
    return good, allc


def _stake_fraction(prob: float, odds: float, cfg: StrategyConfig) -> float:
    return min(cfg.kelly_multiplier * oddsmath.kelly_fraction(prob, odds), cfg.max_stake_fraction)


def best_proposals(cands: list[Candidate], cfg: StrategyConfig, top: int = 3) -> list[Proposal]:
    pool = cands[: cfg.max_candidates]
    found: list[Proposal] = []
    for k in range(1, cfg.max_legs + 1):
        sub = pool if k <= 4 else pool[:20]  # büyük kombinelerde arama alanını sınırla
        for idx in combinations(range(len(sub)), k):
            legs = [sub[i] for i in idx]
            if len({leg.book.ref for leg in legs}) < k:
                continue  # aynı maçtan iki seçim olmaz
            if max(leg.mbs for leg in legs) > k:
                continue  # MBS kuralı
            prob, odds = oddsmath.combo([leg.prob for leg in legs], [leg.odds for leg in legs])
            if odds < cfg.min_coupon_odds or prob < cfg.min_win_prob:
                continue
            if prob * odds - 1.0 < cfg.min_coupon_ev:
                continue
            f = _stake_fraction(prob, odds, cfg)
            if f <= 0:
                continue
            found.append(Proposal(legs=legs, odds=odds, prob=prob, stake_fraction=f,
                                  growth=oddsmath.log_growth(prob, odds, f)))
    found.sort(key=lambda p: p.growth, reverse=True)
    # Alternatifler birbirinin neredeyse aynısı olmasın: ilk kuponla maç paylaşmayanları öne al.
    chosen: list[Proposal] = []
    for p in found:
        refs = {leg.book.ref for leg in p.legs}
        if any(refs & {leg.book.ref for leg in c.legs} for c in chosen):
            continue
        chosen.append(p)
        if len(chosen) == top:
            break
    return chosen


def market_margins(book: list[BookEvent]) -> dict[str, float]:
    """Bültenin pazar bazında ortalama marjı (iddaa'nın kesintisi)."""
    out: dict[str, list[float]] = {}
    for ev in book:
        for market, outcomes in ev.odds.items():
            expected = 3 if market == "MS" else 2
            if len(outcomes) == expected and all(o > 1 for o in outcomes.values()):
                out.setdefault(market, []).append(oddsmath.margin(list(outcomes.values())))
    return {m: mean(v) for m, v in out.items() if v}


def decide(
    book: list[BookEvent], sharp: list[SharpEvent], now: datetime, cfg: StrategyConfig
) -> Decision:
    pairs = match_events(book, sharp)
    cands, allc = build_candidates(pairs, now, cfg)
    stats = {
        "iddaa_mac": len(book),
        "keskin_mac": len(sharp),
        "eslesen": len(pairs),
        "karsilastirilan_secim": len(allc),
        "avantajli_secim": len(cands),
        "marjlar": market_margins(book),
        "ortalama_avantaj": mean(c.ev for c in allc) if allc else None,
    }
    if not sharp:
        return Decision(None, reason="Seçili liglerde karar penceresinde maç yok (keskin piyasada 0 maç). "
                                     "O gün oynayan ligleri, ör. Avrupa kupalarını, Ayarlar'dan ekleyebilirsin.",
                        stats=stats)
    if not pairs:
        return Decision(None, reason="iddaa bülteni ile keskin piyasa eşleştirilemedi; "
                                     "veri kaynaklarını kontrol et.", stats=stats)
    if not cands:
        return Decision(None, reason=f"{len(allc)} seçim karşılaştırıldı, hiçbirinde iddaa oranı "
                                     f"adil oranı yeterince geçmiyor. Bugün pas.", stats=stats)
    proposals = best_proposals(cands, cfg)
    if not proposals:
        return Decision(None, reason=f"{len(cands)} avantajlı seçim var ama MBS ve risk "
                                     f"eşiklerini birlikte sağlayan kupon kurulamadı. Bugün pas.",
                        stats=stats)
    return Decision(proposals[0], alternatives=proposals[1:], stats=stats)
