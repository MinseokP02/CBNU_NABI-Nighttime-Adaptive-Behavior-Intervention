import logging
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router
from app.core.config import get_settings
from app.core.exceptions import install_exception_handlers

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("nabi.api")

settings = get_settings()
app = FastAPI(title="NABI API", version="1.0.0", debug=settings.debug)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.parsed_cors_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
)
install_exception_handlers(app)
app.include_router(api_router)


@app.middleware("http")
async def request_log(request: Request, call_next):
    """NFR-09: request id, latency and status per request. Bodies are never logged."""
    request_id = request.headers.get("X-Request-ID") or uuid.uuid4().hex
    started = time.perf_counter()
    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    logger.info(
        "request_id=%s method=%s path=%s status=%d latency_ms=%.1f",
        request_id, request.method, request.url.path, response.status_code, (time.perf_counter() - started) * 1000,
    )
    return response
