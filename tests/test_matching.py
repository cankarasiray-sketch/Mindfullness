from datetime import timedelta

import pytest

from bilincli.matching import match_events, name_similarity, normalize
from bilincli.models import BookEvent, SharpEvent

from .helpers import NOW


def test_normalize_turkish_and_suffixes():
    assert normalize("Beşiktaş JK") == "besiktas"
    assert normalize("Fenerbahçe") == "fenerbahce"
    assert normalize("1. FC Köln") == "koln"
    assert normalize("Paris SG") == "paris saint germain"


@pytest.mark.parametrize("a,b", [
    ("Bayern Münih", "Bayern Munich"),
    ("Başakşehir", "Istanbul Basaksehir"),
    ("Manchester Utd", "Manchester United"),
    ("Atl. Madrid", "Atletico Madrid"),
    ("Galatasaray", "Galatasaray SK"),
    ("Çaykur Rizespor", "Rizespor"),
])
def test_similar_names(a, b):
    assert name_similarity(a, b) >= 0.8


def test_different_clubs_score_lower():
    assert name_similarity("Manchester City", "Manchester United") < 0.8


def _ev(cls, ref, home, away, minutes=0):
    kickoff = NOW + timedelta(hours=5, minutes=minutes)
    if cls is BookEvent:
        return BookEvent(ref=ref, home=home, away=away, kickoff=kickoff)
    return SharpEvent(ref=ref, sport_key="x", home=home, away=away, kickoff=kickoff)


def test_match_events_by_time_and_name():
    book = [_ev(BookEvent, "b1", "Beşiktaş", "Göztepe"),
            _ev(BookEvent, "b2", "Manchester Utd", "Chelsea"),
            _ev(BookEvent, "b3", "Kasımpaşa", "Konyaspor", minutes=120)]
    sharp = [_ev(SharpEvent, "s2", "Manchester United", "Chelsea"),
             _ev(SharpEvent, "s1", "Besiktas JK", "Goztepe"),
             _ev(SharpEvent, "s3", "Kasimpasa", "Konyaspor", minutes=0)]  # saat tutmuyor
    pairs = {b.ref: s.ref for b, s, _ in match_events(book, sharp)}
    assert pairs == {"b1": "s1", "b2": "s2"}


def test_each_event_matched_once():
    book = [_ev(BookEvent, "b1", "Milan", "Roma")]
    sharp = [_ev(SharpEvent, "s1", "AC Milan", "AS Roma"),
             _ev(SharpEvent, "s2", "Inter Milan", "AS Roma")]
    pairs = match_events(book, sharp)
    assert len(pairs) == 1 and pairs[0][1].ref == "s1"
