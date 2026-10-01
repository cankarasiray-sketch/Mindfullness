import json
from datetime import datetime, timedelta

from bilincli import render
from bilincli.app import App, run_daily
from bilincli.cli import main
from bilincli.config import Config, load_config
from bilincli.ledger import Ledger
from bilincli.models import TR


def _write_config(tmp_path, iddaa_items):
    (tmp_path / "iddaa.json").write_text(json.dumps(iddaa_items), encoding="utf-8")
    cfg = tmp_path / "bilincli.toml"
    cfg.write_text('[genel]\nveri_klasoru = "veri"\n[kaynaklar]\niddaa_kaynagi = "dosya"\n'
                   'iddaa_dosyasi = "iddaa.json"\nodds_api_key = "x"\n', encoding="utf-8")
    return cfg


def test_demo_end_to_end(tmp_path, capsys):
    out = tmp_path / "demo"
    assert main(["demo", "--gun", "20", "--klasor", str(out)]) == 0
    text = capsys.readouterr().out
    assert "Bilinçli strateji" in text and "Banko" in text
    html = (out / "panel.html").read_text(encoding="utf-8")
    assert "DEMO" in html and "Kupon geçmişi" in html
    lg = Ledger(out / "kasa.sqlite3")
    assert len(lg.runs(100)) == 21  # 20 gün + bugün, her gün için bir karar kaydı
    s = lg.stats()
    assert s.balance == s.deposited + s.betting_pl - s.open_stake
    lg.close()


def test_cli_money_and_status(tmp_path, capsys):
    cfg = _write_config(tmp_path, [])
    assert main(["--ayar", str(cfg), "yatir", "5.000"]) == 0
    assert main(["--ayar", str(cfg), "cek", "1000,50"]) == 0
    assert main(["--ayar", str(cfg), "cek", "99999"]) == 2  # yetersiz bakiye
    assert main(["--ayar", str(cfg), "durum"]) == 0
    out = capsys.readouterr().out
    assert "3.999,50 TL" in out


def test_kur_creates_config(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    assert main(["kur"]) == 0
    cfg = load_config(tmp_path / "bilincli.toml")
    assert cfg.strategy.max_stake_fraction == 0.03
    assert (tmp_path / "veri").is_dir()


class FakeSharp:
    remaining = None

    def __init__(self, events):
        self.events = events

    def fetch_events(self):
        return self.events

    def fetch_scores(self, sports):
        return {}


def test_daily_with_file_source(tmp_path):
    from bilincli.models import SharpEvent

    kickoff = (datetime.now(TR) + timedelta(hours=6)).replace(second=0, microsecond=0)
    items = [{"kod": "1", "ev": "Beşiktaş <script>", "deplasman": "Göztepe",
              "baslama": kickoff.isoformat(), "mbs": 1,
              "oranlar": {"MS": {"1": 2.40, "X": 3.3, "2": 3.2}}}]
    cfg = load_config(_write_config(tmp_path, items))
    app = App(cfg)
    app.__dict__["sharp"] = FakeSharp([SharpEvent(
        ref="s1", sport_key="soccer_turkey_super_league", home="Besiktas JK <script>",
        away="Goztepe", kickoff=kickoff, fair={"MS": {"1": 0.52, "X": 0.26, "2": 0.22}})])
    app.ledger.deposit(1_000_000)
    result = run_daily(app, notify=False)
    assert result.headline == "KUPON", result
    c = app.ledger.coupon(result.coupon_id)
    assert c["suggested_stake"] > 0 and c["suggested_stake"] % 100 == 0
    assert c["suggested_stake"] <= 0.03 * 1_000_000
    text = render.daily_text(app.ledger, result)
    assert "GÜNÜN KUPONU" in text and "MS 1 @ 2,40" in text
    html = cfg.panel_path.read_text(encoding="utf-8")
    assert "<script>" not in html and "&lt;script&gt;" in html
    # Aynı gün ikinci çalıştırma yeni kupon üretmez.
    assert run_daily(app, notify=False).skipped


def test_oynadim_with_changed_odds(tmp_path, capsys):
    cfg_path = _write_config(tmp_path, [])
    cfg = load_config(cfg_path)
    from bilincli.models import Candidate, Proposal

    from .helpers import make_pair
    legs = [Candidate(*make_pair(i, (2.0, 3.4, 4.0), (0.55, 0.25, 0.2)), "MS", "1", 2.0, 0.55)
            for i in range(2)]
    lg = Ledger(cfg.db_path)
    lg.deposit(100000)
    cid = lg.add_coupon(Proposal(legs, 4.0, 0.3, 0.02, 0.001), "2026-10-01", 2000)
    lg.close()
    assert main(["--ayar", str(cfg_path), "oynadim", str(cid), "--oranlar", "1.9,x"]) == 2
    assert main(["--ayar", str(cfg_path), "oynadim", str(cid), "--oranlar", "1,95 2.05"]) == 0
    lg = Ledger(cfg.db_path)
    assert [leg["odds"] for leg in lg.legs(cid)] == [1.95, 2.05]
    assert lg.balance() == 98000
    lg.close()


def test_dashboard_renders_empty(tmp_path):
    lg = Ledger(tmp_path / "k.sqlite3")
    html = render.dashboard(lg, Config(data_dir=tmp_path), interactive=True, token="t")
    assert "Henüz kupon yok" in html and 'name="token" value="t"' in html
    lg.close()
