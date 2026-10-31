"""Per-process sliding-window rate limiter (429 RATE_LIMITED).

Single-instance MVP guard for the costly chat endpoint. With several backend replicas,
move this to the reverse proxy or a shared store such as Redis.
"""
import threading
import time
from collections import defaultdict, deque

from app.core.exceptions import AppError


class RateLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, key: str, limit: int, window_seconds: float = 60.0) -> None:
        if limit <= 0:
            return
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] >= window_seconds:
                hits.popleft()
            if len(hits) >= limit:
                retry_after = int(window_seconds - (now - hits[0])) + 1
                raise AppError(429, "RATE_LIMITED", "Too many requests", {"retry_after_seconds": retry_after})
            hits.append(now)

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


chat_limiter = RateLimiter()
