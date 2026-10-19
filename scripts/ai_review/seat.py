"""Client for the resident vision model behind the local seat proxy.

The seat is OpenAI-compatible at LOCAL_SEAT_URL (default
http://127.0.0.1:8001/v1, reachable only on the Spark) and the model id is
always `local` — the seat decides which engine that is. Never point this at
the engine port (8003) or name a model.

Two kinds of failure are kept apart on purpose:

- SeatUnavailable: the seat itself is down or overloaded (connection refused,
  timeout, 5xx, 429). Retried with backoff; if it persists the whole run
  stops WITHOUT recording anything, so a restarting tunnel cannot turn the
  queue into a pile of 'error' rows in one second (the 10-13 incident).
- SeatRequestError: the seat answered but rejected this request (4xx). That
  is about this job's input, so the caller records it on the job.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

import requests

DEFAULT_SEAT_URL = "http://127.0.0.1:8001/v1"
SEAT_MODEL = "local"


class SeatUnavailable(RuntimeError):
    pass


class SeatRequestError(RuntimeError):
    pass


@dataclass
class SeatClient:
    base_url: str = DEFAULT_SEAT_URL
    timeout_s: float = 180.0
    # Waits before each retry of a SeatUnavailable failure.
    backoff_s: tuple[float, ...] = (5.0, 20.0, 60.0)
    max_tokens: int = 700
    post: Callable[..., requests.Response] = requests.post
    get: Callable[..., requests.Response] = requests.get
    sleep: Callable[[float], None] = time.sleep

    def healthy(self) -> bool:
        try:
            resp = self.get(f"{self.base_url}/models", timeout=10)
        except requests.RequestException:
            return False
        if resp.status_code != 200:
            return False
        try:
            ids = [m.get("id") for m in resp.json().get("data", [])]
        except ValueError:
            return False
        return SEAT_MODEL in ids

    def chat(self, system: str, text: str, image_data_urls: list[str]) -> str:
        """One chat completion; returns the message content."""
        content: list[dict] = [{"type": "text", "text": text}]
        content += [{"type": "image_url", "image_url": {"url": u}} for u in image_data_urls]
        body = {
            "model": SEAT_MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": content},
            ],
            "temperature": 0,
            "max_tokens": self.max_tokens,
            "response_format": {"type": "json_object"},
        }
        last: Exception | None = None
        for attempt, wait in enumerate((0.0, *self.backoff_s)):
            if wait:
                print(f"  seat unavailable ({last}); retry {attempt} in {wait:.0f}s")
                self.sleep(wait)
            try:
                resp = self.post(
                    f"{self.base_url}/chat/completions", json=body, timeout=self.timeout_s
                )
            except requests.RequestException as exc:  # refused, reset, timeout
                last = exc
                continue
            if resp.status_code == 429 or resp.status_code >= 500:
                last = SeatUnavailable(f"HTTP {resp.status_code}: {resp.text[:200]}")
                continue
            if resp.status_code != 200:
                raise SeatRequestError(f"HTTP {resp.status_code}: {resp.text[:300]}")
            try:
                message = resp.json()["choices"][0]["message"]
            except (ValueError, KeyError, IndexError, TypeError) as exc:
                raise SeatRequestError(f"malformed completion: {exc}") from exc
            return message.get("content") or ""
        raise SeatUnavailable(str(last))
