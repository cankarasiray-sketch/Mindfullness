"""Ayar dosyası (bilincli.toml) okuma."""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field, fields
from pathlib import Path

DEFAULT_CONFIG_NAME = "bilincli.toml"

DEFAULT_LEAGUES = [
    "soccer_turkey_super_league",
    "soccer_epl",
    "soccer_spain_la_liga",
    "soccer_italy_serie_a",
    "soccer_germany_bundesliga",
    "soccer_france_ligue_one",
]


@dataclass
class SourceConfig:
    odds_api_key: str = ""
    leagues: list[str] = field(default_factory=lambda: list(DEFAULT_LEAGUES))
    markets: list[str] = field(default_factory=lambda: ["h2h"])
    regions: str = "eu"
    preferred_book: str = "pinnacle"
    min_books: int = 3
    iddaa_source: str = "nesine"  # "nesine" | "dosya"
    iddaa_file: str = "veri/iddaa.json"


@dataclass
class StrategyConfig:
    window_hours: float = 24.0
    min_lead_minutes: float = 30.0
    min_leg_ev: float = 0.03
    min_coupon_ev: float = 0.05
    min_win_prob: float = 0.20
    min_leg_odds: float = 1.25
    max_leg_odds: float = 3.50
    min_coupon_odds: float = 1.50
    max_legs: int = 4
    max_candidates: int = 30
    kelly_multiplier: float = 0.25
    max_stake_fraction: float = 0.03
    min_coupon_amount: float = 50.0
    auto_play: bool = False


@dataclass
class LimitConfig:
    weekly_loss_limit: float = 0.15
    chase_cooldown_hours: float = 48.0


@dataclass
class NotifyConfig:
    telegram_bot_token: str = ""
    telegram_chat_id: str = ""


@dataclass
class Config:
    data_dir: Path = Path("veri")
    sources: SourceConfig = field(default_factory=SourceConfig)
    strategy: StrategyConfig = field(default_factory=StrategyConfig)
    limits: LimitConfig = field(default_factory=LimitConfig)
    notify: NotifyConfig = field(default_factory=NotifyConfig)
    path: Path | None = None

    @property
    def db_path(self) -> Path:
        return self.data_dir / "kasa.sqlite3"

    @property
    def panel_path(self) -> Path:
        return self.data_dir / "panel.html"


# TOML'daki Türkçe anahtar -> dataclass alanı
_SECTIONS = {
    "kaynaklar": ("sources", {
        "odds_api_key": "odds_api_key",
        "ligler": "leagues",
        "pazarlar": "markets",
        "bolge": "regions",
        "tercih_edilen_site": "preferred_book",
        "min_site_sayisi": "min_books",
        "iddaa_kaynagi": "iddaa_source",
        "iddaa_dosyasi": "iddaa_file",
    }),
    "strateji": ("strategy", {
        "pencere_saat": "window_hours",
        "min_sure_dakika": "min_lead_minutes",
        "min_bacak_avantaji": "min_leg_ev",
        "min_kupon_avantaji": "min_coupon_ev",
        "min_kazanma_olasiligi": "min_win_prob",
        "min_bacak_orani": "min_leg_odds",
        "max_bacak_orani": "max_leg_odds",
        "min_kupon_orani": "min_coupon_odds",
        "max_mac": "max_legs",
        "max_aday": "max_candidates",
        "kelly_carpani": "kelly_multiplier",
        "max_bahis_orani": "max_stake_fraction",
        "min_kupon_tutari": "min_coupon_amount",
        "oneriyi_otomatik_oyna": "auto_play",
    }),
    "limitler": ("limits", {
        "haftalik_kayip_limiti": "weekly_loss_limit",
        "kovalama_bekleme_saat": "chase_cooldown_hours",
    }),
    "bildirim": ("notify", {
        "telegram_bot_token": "telegram_bot_token",
        "telegram_chat_id": "telegram_chat_id",
    }),
}


class ConfigError(Exception):
    pass


def _apply(section_obj, mapping: dict[str, str], values: dict, section_name: str) -> None:
    types = {f.name: f.type for f in fields(section_obj)}
    for key, value in values.items():
        if key not in mapping:
            raise ConfigError(f"[{section_name}] altında bilinmeyen ayar: {key}")
        attr = mapping[key]
        current = getattr(section_obj, attr)
        if isinstance(current, bool) and not isinstance(value, bool):
            raise ConfigError(f"{section_name}.{key} true/false olmalı")
        if isinstance(current, float) and isinstance(value, int) and not isinstance(value, bool):
            value = float(value)
        if types[attr] in ("int",) and not isinstance(value, int):
            raise ConfigError(f"{section_name}.{key} tam sayı olmalı")
        setattr(section_obj, attr, value)


def load_config(path: str | Path | None = None) -> Config:
    cfg = Config()
    candidate = Path(path) if path else Path(DEFAULT_CONFIG_NAME)
    if candidate.exists():
        with candidate.open("rb") as fh:
            raw = tomllib.load(fh)
        base = candidate.resolve().parent
        general = raw.get("genel", {})
        cfg.data_dir = (base / general.get("veri_klasoru", "veri")).resolve()
        for section_name, (attr, mapping) in _SECTIONS.items():
            if section_name in raw:
                _apply(getattr(cfg, attr), mapping, raw[section_name], section_name)
        cfg.path = candidate.resolve()
        if not Path(cfg.sources.iddaa_file).is_absolute():
            cfg.sources.iddaa_file = str((base / cfg.sources.iddaa_file).resolve())
    elif path:
        raise ConfigError(f"ayar dosyası bulunamadı: {candidate}")
    else:
        cfg.data_dir = Path("veri").resolve()

    # Gizli anahtarlar ortam değişkeniyle de verilebilir (dosyada tutmamak için).
    cfg.sources.odds_api_key = os.environ.get("ODDS_API_KEY", cfg.sources.odds_api_key)
    cfg.notify.telegram_bot_token = os.environ.get(
        "TELEGRAM_BOT_TOKEN", cfg.notify.telegram_bot_token
    )
    cfg.notify.telegram_chat_id = os.environ.get("TELEGRAM_CHAT_ID", cfg.notify.telegram_chat_id)
    _validate(cfg)
    return cfg


def _validate(cfg: Config) -> None:
    s = cfg.strategy
    if not 0 < s.kelly_multiplier <= 1:
        raise ConfigError("kelly_carpani 0 ile 1 arasında olmalı")
    if not 0 < s.max_stake_fraction <= 0.25:
        raise ConfigError("max_bahis_orani 0 ile 0.25 arasında olmalı")
    if s.max_legs < 1 or s.max_legs > 6:
        raise ConfigError("max_mac 1 ile 6 arasında olmalı")
    if cfg.sources.iddaa_source not in ("nesine", "dosya"):
        raise ConfigError("iddaa_kaynagi 'nesine' ya da 'dosya' olmalı")
    unknown = set(cfg.sources.markets) - {"h2h", "totals"}
    if unknown:
        raise ConfigError(f"desteklenmeyen pazar: {sorted(unknown)}")
