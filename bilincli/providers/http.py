"""Bağımlılıksız küçük HTTP yardımcıları."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "Mozilla/5.0 (bilincli-kupon)"


class ProviderError(Exception):
    pass


def get_json(url: str, params: dict | None = None, headers: dict | None = None,
             timeout: float = 30) -> tuple[object, dict[str, str]]:
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                               "Accept": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            resp_headers = {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as exc:
        detail = exc.read()[:300].decode("utf-8", "replace")
        raise ProviderError(f"{_safe(url)} -> HTTP {exc.code}: {detail}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ProviderError(f"{_safe(url)} -> bağlantı hatası: {exc}") from exc
    try:
        return json.loads(body), resp_headers
    except json.JSONDecodeError as exc:
        raise ProviderError(f"{_safe(url)} -> JSON çözülemedi") from exc


def post_json(url: str, payload: dict, timeout: float = 30) -> object:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json",
                                          "User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as exc:
        raise ProviderError(f"POST -> HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ProviderError(f"POST -> bağlantı hatası: {exc}") from exc


def _safe(url: str) -> str:
    """Hata mesajlarında API anahtarı görünmesin."""
    parts = urllib.parse.urlsplit(url)
    query = urllib.parse.parse_qsl(parts.query)
    clean = [(k, "***" if "key" in k.lower() or "token" in k.lower() else v) for k, v in query]
    return urllib.parse.urlunsplit(parts._replace(query=urllib.parse.urlencode(clean)))
