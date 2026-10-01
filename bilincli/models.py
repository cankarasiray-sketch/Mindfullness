"""Uygulama genelinde kullanılan veri yapıları."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

# Türkiye 2016'dan beri yıl boyu UTC+3 kullanıyor. Sabit ofset kullanmak,
# Windows'ta tzdata paketi gerektirmeden doğru saati verir.
TR = timezone(timedelta(hours=3), "TRT")

MARKETS: dict[str, dict] = {
    "MS": {"label": "Maç Sonucu", "outcomes": {"1": "MS 1", "X": "MS X", "2": "MS 2"}},
    "AU25": {"label": "2,5 Alt/Üst", "outcomes": {"ALT": "2,5 Alt", "UST": "2,5 Üst"}},
}

WON = "kazandi"
LOST = "kaybetti"
VOID = "iade"


def outcome_label(market: str, outcome: str) -> str:
    return MARKETS.get(market, {}).get("outcomes", {}).get(outcome, f"{market} {outcome}")


@dataclass
class BookEvent:
    """iddaa bülteninden bir maç ve oranları."""

    ref: str
    home: str
    away: str
    kickoff: datetime  # UTC, timezone-aware
    league: str = ""
    mbs: int = 1
    odds: dict[str, dict[str, float]] = field(default_factory=dict)
    market_mbs: dict[str, int] = field(default_factory=dict)
    code: str = ""  # iddaa maç kodu

    def mbs_for(self, market: str) -> int:
        return self.market_mbs.get(market, self.mbs)


@dataclass
class SharpEvent:
    """Keskin (düşük marjlı) piyasadan marjı arındırılmış adil olasılıklar."""

    ref: str
    sport_key: str
    home: str
    away: str
    kickoff: datetime
    fair: dict[str, dict[str, float]] = field(default_factory=dict)
    source: str = ""


@dataclass
class ScoreResult:
    completed: bool
    home_goals: int | None = None
    away_goals: int | None = None


@dataclass
class Candidate:
    """Kupona girebilecek tek bir seçim."""

    book: BookEvent
    sharp: SharpEvent
    market: str
    outcome: str
    odds: float
    prob: float

    @property
    def ev(self) -> float:
        return self.prob * self.odds - 1.0

    @property
    def fair_odds(self) -> float:
        return 1.0 / self.prob

    @property
    def mbs(self) -> int:
        return self.book.mbs_for(self.market)

    @property
    def label(self) -> str:
        return outcome_label(self.market, self.outcome)


@dataclass
class Proposal:
    """Önerilen kupon: bacaklar, toplam oran, kazanma olasılığı ve Kelly payı."""

    legs: list[Candidate]
    odds: float
    prob: float
    stake_fraction: float
    growth: float

    @property
    def ev(self) -> float:
        return self.prob * self.odds - 1.0

    @property
    def min_mbs_ok(self) -> bool:
        return len(self.legs) >= max(leg.mbs for leg in self.legs)
