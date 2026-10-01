import json
from datetime import datetime, timezone

import pytest

from bilincli.models import TR
from bilincli.providers.filesource import FileBookProvider, parse_file_events
from bilincli.providers.http import ProviderError, _safe
from bilincli.providers.nesine import parse_bulletin
from bilincli.providers.theoddsapi import parse_odds, parse_scores


def _book(key, h2h=None, totals=None):
    markets = []
    if h2h:
        markets.append({"key": "h2h", "outcomes": [
            {"name": "Galatasaray", "price": h2h[0]}, {"name": "Draw", "price": h2h[1]},
            {"name": "Fenerbahce", "price": h2h[2]}]})
    if totals:
        markets.append({"key": "totals", "outcomes": [
            {"name": "Over", "price": totals[0], "point": 2.5},
            {"name": "Under", "price": totals[1], "point": 2.5},
            {"name": "Over", "price": 1.3, "point": 1.5}]})
    return {"key": key, "markets": markets}


EVENT = {"id": "abc", "commence_time": "2026-10-03T17:00:00Z", "home_team": "Galatasaray",
         "away_team": "Fenerbahce"}


def test_odds_api_prefers_pinnacle():
    ev = {**EVENT, "bookmakers": [_book("pinnacle", (2.0, 3.6, 3.9), (1.9, 1.95)),
                                  _book("other", (1.8, 3.3, 3.6))]}
    [e] = parse_odds([ev], "soccer_turkey_super_league")
    assert e.kickoff == datetime(2026, 10, 3, 17, tzinfo=timezone.utc)
    assert set(e.fair) == {"MS", "AU25"}
    assert sum(e.fair["MS"].values()) == pytest.approx(1.0)
    assert e.fair["MS"]["1"] > e.fair["MS"]["2"]
    assert e.fair["AU25"]["UST"] > e.fair["AU25"]["ALT"]
    assert "pinnacle" in e.source


def test_odds_api_consensus_needs_min_books():
    books = [_book(f"b{i}", (2.0 + i / 10, 3.5, 3.8)) for i in range(3)]
    [e] = parse_odds([{**EVENT, "bookmakers": books}], "x", min_books=3)
    assert "ortalama(3)" in e.source
    assert parse_odds([{**EVENT, "bookmakers": books[:2]}], "x", min_books=3) == []


def test_parse_scores():
    payload = [
        {**EVENT, "completed": True, "scores": [{"name": "Galatasaray", "score": "2"},
                                                {"name": "Fenerbahce", "score": "1"}]},
        {**EVENT, "id": "live", "completed": False, "scores": None},
    ]
    s = parse_scores(payload)
    assert s["abc"].completed and (s["abc"].home_goals, s["abc"].away_goals) == (2, 1)
    assert not s["live"].completed


NESINE = {"sg": {"EA": [
    {"C": 4321, "HN": "Galatasaray", "AN": "Fenerbahçe", "D": "03.10.2026", "T": "20:00",
     "TYPE": 1, "MBS": 1, "LN": "Süper Lig",
     "MA": [{"MTID": 1, "MBS": 1, "OCA": [{"N": 1, "O": 1.95}, {"N": 2, "O": 3.40},
                                          {"N": 3, "O": 3.60}]},
            {"MTID": 99, "OCA": [{"N": 1, "O": 1.5}]}]},
    {"C": 1, "HN": "Basket A", "AN": "Basket B", "D": "03.10.2026", "T": "20:00", "TYPE": 2,
     "MA": [{"MTID": 1, "OCA": [{"N": 1, "O": 1.5}, {"N": 2, "O": 2.5}]}]},
    {"C": 2, "HN": "Eksik Oran", "AN": "Takım", "D": "03.10.2026", "T": "21:00", "TYPE": 1,
     "MA": [{"MTID": 1, "OCA": [{"N": 1, "O": 1.5}]}]},
    {"C": 3, "HN": "MBS Yok", "AN": "Takım", "D": "03.10.2026", "T": "21:00", "TYPE": 1,
     "MA": [{"MTID": 1, "OCA": [{"N": 1, "O": "1,50"}, {"N": 2, "O": 3.9},
                                {"N": 3, "O": 5.5}]}]},
]}}


def test_nesine_parser():
    events = parse_bulletin(NESINE)
    assert [e.home for e in events] == ["Galatasaray", "MBS Yok"]
    gs = events[0]
    assert gs.code == "4321" and gs.mbs == 1 and gs.league == "Süper Lig"
    assert gs.odds == {"MS": {"1": 1.95, "X": 3.40, "2": 3.60}}
    assert gs.kickoff == datetime(2026, 10, 3, 20, tzinfo=TR)
    assert events[1].mbs == 3  # MBS okunamazsa temkinli
    assert events[1].odds["MS"]["1"] == 1.50


def test_nesine_rejects_garbage():
    with pytest.raises(ProviderError):
        parse_bulletin([])
    assert parse_bulletin({"sg": {}}) == []


def test_file_source(tmp_path):
    data = [{"kod": "77", "lig": "Süper Lig", "ev": "Beşiktaş", "deplasman": "Trabzonspor",
             "baslama": "2026-10-03T19:00:00", "mbs": 2,
             "oranlar": {"MS": {"1": 2.0, "X": 3.3, "2": 3.7}, "AU25": {"ALT": 1.8, "UST": 1.9}}}]
    path = tmp_path / "iddaa.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    [e] = FileBookProvider(path).fetch_events()
    assert e.kickoff.tzinfo == TR and e.mbs == 2 and e.code == "77"
    assert e.odds["AU25"] == {"ALT": 1.8, "UST": 1.9}
    with pytest.raises(ProviderError):
        parse_file_events([{"ev": "eksik"}])
    with pytest.raises(ProviderError):
        FileBookProvider(tmp_path / "yok.json").fetch_events()


def test_api_key_hidden_in_errors():
    assert "SECRET" not in _safe("https://x/v4/sports?apiKey=SECRET&regions=eu")
