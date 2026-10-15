"""Request timings and recent errors for the assistant, shown under Admin > Monitoring in the Java app."""
from __future__ import annotations

import logging
import threading
import time
import traceback
from collections import deque
from datetime import datetime

log = logging.getLogger("assistant.monitoring")

SLOW_MS = 2000
MAX_PATHS = 30          # unknown paths are grouped so random URLs can't grow the table
STARTED = time.time()

_lock = threading.Lock()
_requests: dict[str, dict[str, float]] = {}
_errors: deque[dict] = deque(maxlen=30)


def record(path: str, millis: float, failed: bool) -> None:
    with _lock:
        key = path if path in _requests or len(_requests) < MAX_PATHS else "other"
        s = _requests.setdefault(key, {"count": 0, "total_ms": 0.0, "max_ms": 0.0, "errors": 0})
        s["count"] += 1
        s["total_ms"] += millis
        s["max_ms"] = max(s["max_ms"], millis)
        if failed:
            s["errors"] += 1
    if millis >= SLOW_MS:
        log.warning("Slow request: %s took %.0f ms", path, millis)


def record_error(path: str, exc: BaseException) -> None:
    log.exception("Request to %s failed", path, exc_info=exc)
    with _lock:
        _errors.appendleft({
            "time": datetime.now().strftime("%d %b %H:%M:%S"),
            "path": path,
            "type": type(exc).__name__,
            "message": str(exc)[:500],
            "trace": "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))[-8000:],
        })


def snapshot() -> dict:
    with _lock:
        requests = {
            path: {"count": int(s["count"]), "avg_ms": round(s["total_ms"] / s["count"], 1) if s["count"] else 0,
                   "max_ms": round(s["max_ms"], 1), "errors": int(s["errors"])}
            for path, s in sorted(_requests.items())
        }
        return {"uptime_s": int(time.time() - STARTED), "requests": requests, "errors": list(_errors)}


async def middleware(request, call_next):
    """Times every request and keeps the details of any that fail."""
    start = time.perf_counter()
    path = request.url.path
    try:
        response = await call_next(request)
    except Exception as exc:  # the framework still answers 500; this keeps the details
        record(path, (time.perf_counter() - start) * 1000, True)
        record_error(path, exc)
        raise
    record(path, (time.perf_counter() - start) * 1000, response.status_code >= 500)
    return response
