"""Sentetik veri: uygulamayı API anahtarı olmadan uçtan uca denemek için.

Takımlar hayalidir. Her maçın "gerçek" olasılıkları rastgele üretilir;
keskin piyasa bunları küçük bir hatayla bilir, iddaa ise üstüne marj koyup
gürültülü fiyatlar. Sonuçlar gerçek olasılıklardan çekilir. Bu dünyada bazı
seçimlerin avantajlı olduğu tasarım gereğidir; gerçek piyasada avantajın
var olup olmadığını yalnızca gerçek veri ve yeterince uzun kayıt gösterir.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from datetime import datetime, timedelta

from ..matching import normalize
from ..models import TR, BookEvent, ScoreResult, SharpEvent

TEAMS = [
    "Anadolu FK", "Boğaziçi SK", "Ege Gücü", "Karadeniz Yıldızı", "Toros Spor",
    "Kapadokya FK", "Marmara Birlik", "Fırat Spor", "Trakya FK", "Akdeniz Güneşi",
    "Erciyes SK", "Kaçkar Spor", "Uludağ FK", "Munzur Spor", "Pamukkale FK",
    "Efes Spor", "Truva FK", "Nemrut SK", "Göbeklitepe FK", "Sümela Spor",
    "Kızılırmak FK", "Ağrı Dağı SK", "Palandöken Spor", "Harran FK",
    "Datça Spor", "Assos FK", "Bafa SK", "Zigana Spor", "Salda FK", "Hasankeyf SK",
    "Olimpos FK", "Likya Spor",
]
SPORT_KEY = "demo_lig"


@dataclass
class _Match:
    book: BookEvent
    sharp: SharpEvent
    true_probs: tuple[float, float, float]
    score: tuple[int, int]


def _scoreline(rng: random.Random, outcome: str) -> tuple[int, int]:
    low = min(int(rng.expovariate(1.2)), 3)
    diff = 1 + min(int(rng.expovariate(1.0)), 3)
    if outcome == "1":
        return low + diff, low
    if outcome == "2":
        return low, low + diff
    return low, low


class DemoWorld:
    def __init__(self, seed: int = 7, margin: float = 0.08, price_noise: float = 0.07,
                 sharp_noise: float = 0.02, matches_per_day: int = 16):
        self.seed = seed
        self.margin = margin
        self.price_noise = price_noise
        self.sharp_noise = sharp_noise
        self.matches_per_day = matches_per_day
        self.now = datetime.now(TR)
        self._days: dict[str, list[_Match]] = {}

    def _day(self, day: datetime) -> list[_Match]:
        key = day.strftime("%Y-%m-%d")
        if key in self._days:
            return self._days[key]
        rng = random.Random(f"{self.seed}-{key}")
        teams = TEAMS[:]
        rng.shuffle(teams)
        matches = []
        base = datetime(day.year, day.month, day.day, tzinfo=TR)
        for n in range(self.matches_per_day):
            home, away = teams[2 * n % len(teams)], teams[(2 * n + 1) % len(teams)]
            kickoff = base + timedelta(hours=rng.choice([13, 15, 16, 18, 19, 20, 21, 22]),
                                       minutes=rng.choice([0, 30, 45]))
            strength = rng.gauss(0.25, 0.9)  # ev sahibi avantajı + güç farkı
            draw = min(max(rng.gauss(0.27, 0.03), 0.18), 0.33)
            p_home = (1 - draw) / (1 + math.exp(-strength))
            true = (p_home, draw, 1 - draw - p_home)

            def jitter(probs, sigma):
                noisy = [p * math.exp(rng.gauss(0, sigma)) for p in probs]
                s = sum(noisy)
                return [p / s for p in noisy]

            sharp_p = jitter(true, self.sharp_noise)
            iddaa_odds = [
                round(max(1.01, 1 / (p * (1 + self.margin)) * math.exp(rng.gauss(0, self.price_noise))), 2)
                for p in true
            ]
            outcome = rng.choices(["1", "X", "2"], weights=true)[0]
            mbs = rng.choices([1, 2, 3], weights=[0.5, 0.3, 0.2])[0]
            ref = f"{key}-{n}"
            book = BookEvent(ref=f"demo:{ref}", home=home, away=away, kickoff=kickoff,
                             league="Demo Lig", mbs=mbs, code=str(10000 + n),
                             odds={"MS": dict(zip(["1", "X", "2"], iddaa_odds, strict=True))})
            sharp = SharpEvent(ref=f"sharp:{ref}", sport_key=SPORT_KEY,
                               home=normalize(home).title(), away=normalize(away).title(),
                               kickoff=kickoff, source="demo",
                               fair={"MS": dict(zip(["1", "X", "2"], sharp_p, strict=True))})
            matches.append(_Match(book, sharp, true, _scoreline(rng, outcome)))
        self._days[key] = matches
        return matches

    def _upcoming(self) -> list[_Match]:
        local = self.now.astimezone(TR)
        out = []
        for d in (0, 1):
            out += [m for m in self._day(local + timedelta(days=d)) if m.book.kickoff >= self.now]
        return out

    # Sağlayıcı arayüzleri
    def fetch_book(self) -> list[BookEvent]:
        return [m.book for m in self._upcoming()]

    def fetch_sharp(self) -> list[SharpEvent]:
        return [m.sharp for m in self._upcoming()]

    def fetch_scores(self, sport_keys: set[str]) -> dict[str, ScoreResult]:
        out = {}
        for matches in self._days.values():
            for m in matches:
                done = m.book.kickoff + timedelta(hours=2) <= self.now
                out[m.sharp.ref] = ScoreResult(done, *(m.score if done else (None, None)))
        return out

    def favourites(self, day: datetime, count: int = 3) -> list[tuple[float, bool]]:
        """'Banko favoriler' kıyası: günün en düşük oranlı MS favorileri (oran, tuttu mu)."""
        picks = []
        for m in self._day(day):
            odds = m.book.odds["MS"]
            key = min(odds, key=odds.get)
            h, a = m.score
            actual = "1" if h > a else "2" if a > h else "X"
            picks.append((odds[key], key == actual))
        picks.sort()
        return picks[:count]


class DemoBookProvider:
    def __init__(self, world: DemoWorld):
        self.world = world

    def fetch_events(self) -> list[BookEvent]:
        return self.world.fetch_book()


class DemoSharpProvider:
    def __init__(self, world: DemoWorld):
        self.world = world
        self.remaining = None

    def fetch_events(self) -> list[SharpEvent]:
        return self.world.fetch_sharp()

    def fetch_scores(self, sport_keys: set[str]) -> dict[str, ScoreResult]:
        return self.world.fetch_scores(sport_keys)
