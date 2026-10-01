"""Telegram bildirimi (isteğe bağlı): günün kuponu sabah telefona düşsün."""

from __future__ import annotations

from .config import NotifyConfig
from .providers.http import ProviderError, post_json

TELEGRAM_LIMIT = 4000


def send_telegram(cfg: NotifyConfig, text: str) -> bool:
    """Ayarlıysa mesajı gönderir; ayarlı değilse sessizce False döner."""
    if not cfg.telegram_bot_token or not cfg.telegram_chat_id:
        return False
    url = f"https://api.telegram.org/bot{cfg.telegram_bot_token}/sendMessage"
    resp = post_json(url, {"chat_id": cfg.telegram_chat_id, "text": text[:TELEGRAM_LIMIT],
                           "disable_web_page_preview": True})
    if not isinstance(resp, dict) or not resp.get("ok"):
        raise ProviderError("Telegram mesajı kabul etmedi")
    return True
