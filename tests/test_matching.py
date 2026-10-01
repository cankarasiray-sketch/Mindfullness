from datetime import timedelta

import pytest

from bilincli.matching import match_events, name_similarity, normalize, variant
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


@pytest.mark.parametrize(
    "a,b",
    [("Galatasaray (K)", "Galatasaray"), ("Arsenal W", "Arsenal"), ("Fenerbahçe U19", "Fenerbahce"),
     ("Barcelona B", "Barcelona"), ("Jong Ajax", "Ajax"), ("Bayern Munich II", "Bayern Munich"),
     ("Besiktas U21", "Besiktas U19")],
)
def test_variant_teams_never_match_main_team(a, b):
    assert variant(a) != variant(b)
    book = [BookEvent(ref="b", home=a, away="Rakip", kickoff=NOW, league="", mbs=1, odds={})]
    sharp = [SharpEvent(ref="s", sport_key="x", home=b, away="Rakip", kickoff=NOW, fair={})]
    assert match_events(book, sharp) == []


def test_variant_markers():
    assert variant("Galatasaray") == ""
    assert variant("B. Mönchengladbach") == ""  # baştaki "B" rezerv değil
    assert variant("Fenerbahçe Kadın") == variant("Fenerbahce (K)") == "kadin"
    assert variant("Besiktas U21") == "u21"
    assert normalize("Galatasaray (K)") == "galatasaray"
    assert normalize("Barcelona B") == "barcelona"
    assert normalize("B. Mönchengladbach") == "borussia monchengladbach"
    # aynı tür takımlar eşleşir
    book = [BookEvent(ref="b", home="Arsenal (K)", away="Chelsea (K)", kickoff=NOW, league="", mbs=1, odds={})]
    sharp = [SharpEvent(ref="s", sport_key="x", home="Arsenal W", away="Chelsea W", kickoff=NOW, fair={})]
    assert len(match_events(book, sharp)) == 1


@pytest.mark.parametrize(
    "tr,en",
    [("Türkiye", "Turkey"), ("Türkiye", "Türkiye"), ("Almanya", "Germany"), ("Bosna Hersek", "Bosnia & Herzegovina"),
     ("Çekya", "Czech Republic"), ("Çekya", "Czechia"), ("Güney Kore", "Korea Republic"), ("ABD", "USA"),
     ("Fildişi Sahili", "Côte d'Ivoire"), ("İrlanda", "Republic of Ireland"), ("Kuzey İrlanda", "Northern Ireland"),
     ("G.Kıbrıs Rum Kesimi", "Cyprus"), ("Galler", "Wales")],
)
def test_national_team_names(tr, en):
    assert normalize(tr) == normalize(en)
    book = [BookEvent(ref="b", home=tr, away="Galler" if tr != "Galler" else "İskoçya", kickoff=NOW, league="", mbs=1, odds={})]
    sharp = [SharpEvent(ref="s", sport_key="x", home=en, away="Wales" if tr != "Galler" else "Scotland", kickoff=NOW, fair={})]
    assert len(match_events(book, sharp)) == 1
    # milli takımın U21 ya da kadın takımı ana takımla eşleşmez
    u21 = [SharpEvent(ref="s", sport_key="x", home=en + " U21", away="Wales U21", kickoff=NOW, fair={})]
    assert match_events(book, u21) == []


def test_each_event_matched_once():
    book = [_ev(BookEvent, "b1", "Milan", "Roma")]
    sharp = [_ev(SharpEvent, "s1", "AC Milan", "AS Roma"),
             _ev(SharpEvent, "s2", "Inter Milan", "AS Roma")]
    pairs = match_events(book, sharp)
    assert len(pairs) == 1 and pairs[0][1].ref == "s1"
