"""Java çekirdeğinin Python sürümüyle aynı sonucu verdiğini doğrulamak için referans veriler üretir.

Çalıştırma (repo kökünden): PYTHONPATH=. python3 android/test/fixtures/make_fixtures.py
"""

import json
import random
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

from bilincli import oddsmath
from bilincli.config import StrategyConfig
from bilincli.engine import decide
from bilincli.ledger import parse_tl
from bilincli.matching import name_similarity, normalize
from bilincli.models import TR
from bilincli.providers.demo import TEAMS, DemoWorld

OUT = Path(__file__).parent
rng = random.Random(20261001)

# ---- oran matematiği ----
odds_cases = []
for _ in range(60):
    n = rng.choice([2, 3])
    odds = [round(rng.uniform(1.05, 12.0), 2) for _ in range(n)]
    odds_cases.append({"odds": odds, "power": oddsmath.devig_power(odds), "margin": oddsmath.margin(odds),
                       "kelly": oddsmath.kelly_fraction(0.55, odds[0]),
                       "growth": oddsmath.log_growth(0.55, odds[0], 0.02)})

# ---- eşleştirme ----
names = TEAMS + [
    "Beşiktaş JK", "Besiktas", "Fenerbahçe", "Fenerbahce", "Galatasaray SK", "Galatasaray",
    "Başakşehir", "Istanbul Basaksehir", "Bayern Münih", "Bayern Munich", "Manchester Utd",
    "Manchester United", "Manchester City", "Atl. Madrid", "Atletico Madrid", "Inter", "Inter Milan",
    "AC Milan", "Milan", "AS Roma", "Roma", "1. FC Köln", "Köln", "Paris SG", "Paris Saint Germain",
    "Çaykur Rizespor", "Rizespor", "Kasımpaşa", "Kasimpasa", "Göztepe", "Goztepe", "İstanbulspor",
    "Wolves", "Wolverhampton Wanderers", "M.Gladbach", "Borussia Monchengladbach", "Sporting Lizbon",
    "Sporting Lisbon", "Nottingham", "Nottingham Forest", "Tottenham", "Real Sociedad",
    "IŞIK ÜNİVERSİTESİ", "Iğdır FK", "Ümraniyespor", "Şanlıurfaspor", "Ankaragücü", "MKE Ankaragücü",
]
pairs = [(rng.choice(names), rng.choice(names)) for _ in range(400)]
pairs += [(names[i], names[i + 1]) for i in range(len(names) - 1)]
match_cases = [{"a": a, "b": b, "na": normalize(a), "sim": name_similarity(a, b)} for a, b in pairs]


# ---- motor ----
def ev_json(e, kind):
    d = {"ref": e.ref, "home": e.home, "away": e.away,
         "kickoff": e.kickoff.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    if kind == "book":
        d.update(mbs=e.mbs, odds=e.odds, league=e.league, code=e.code)
    else:
        d.update(sport=e.sport_key, fair=e.fair)
    return d


def prop_json(p):
    return {"legs": [[leg.book.ref, leg.market, leg.outcome] for leg in p.legs], "odds": p.odds,
            "prob": p.prob, "fraction": p.stake_fraction, "growth": p.growth}


engine_cases = []
base_day = datetime(2026, 9, 1, 6, 0, tzinfo=TR)
for n in range(150):
    world = DemoWorld(seed=rng.randrange(10_000), margin=rng.choice([0.04, 0.06, 0.08]),
                      price_noise=rng.choice([0.05, 0.07, 0.1]))
    world.now = base_day + timedelta(days=rng.randrange(60), minutes=rng.choice([0, 0, 180, 420]))
    book, sharp = world.fetch_book(), world.fetch_sharp()
    # Eşleştirmeyi zorlamak için bazı adları keskin tarafta karıştır / bazı maçları çıkar
    if n % 3 == 0:
        sharp = [replace(s, home=s.home.upper()) for s in sharp]
    if n % 5 == 0 and sharp:
        sharp = sharp[1:]
    cfg = StrategyConfig(max_legs=rng.choice([2, 3, 4, 5]), min_leg_ev=rng.choice([0.0, 0.02, 0.03]),
                         min_coupon_ev=rng.choice([0.0, 0.03, 0.05]),
                         min_win_prob=rng.choice([0.1, 0.2, 0.3]),
                         max_candidates=rng.choice([12, 20, 30]))
    d = decide(book, sharp, world.now.astimezone(timezone.utc), cfg)
    engine_cases.append({
        "now": world.now.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cfg": {"maxLegs": cfg.max_legs, "minLegEv": cfg.min_leg_ev, "minCouponEv": cfg.min_coupon_ev,
                "minWinProb": cfg.min_win_prob, "maxCandidates": cfg.max_candidates},
        "book": [ev_json(b, "book") for b in book],
        "sharp": [ev_json(s, "sharp") for s in sharp],
        "pass": d.is_pass,
        "reason": d.reason,
        "stats": {k: d.stats[k] for k in ("eslesen", "karsilastirilan_secim", "avantajli_secim")},
        "proposal": prop_json(d.proposal) if d.proposal else None,
        "alternatives": [prop_json(p) for p in d.alternatives],
    })

tl_cases = [[t, parse_tl(t)] for t in ["1500", "1500.50", "1.500,50", "1500,5", "250 TL", "₺75", "5.000",
                                       "1.250.000", "2.50", "0,75", "12.345,6"]]

data = {"odds": odds_cases, "matching": match_cases, "engine": engine_cases, "tl": tl_cases}
(OUT / "parity.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
passes = sum(c["pass"] for c in engine_cases)
print(f"{len(odds_cases)} oran, {len(match_cases)} eşleştirme, {len(engine_cases)} motor senaryosu "
      f"({len(engine_cases) - passes} kupon, {passes} pas)")
