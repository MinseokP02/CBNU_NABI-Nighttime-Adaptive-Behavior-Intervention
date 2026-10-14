from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router
from app.core.config import get_settings
from app.core.exceptions import install_exception_handlers

settings = get_settings()
app = FastAPI(title="NABI API", version="1.0.0", debug=settings.debug)
app.add_middleware(CORSMiddleware, allow_origins=settings.parsed_cors_origins, allow_credentials=True, allow_methods=["GET", "POST"], allow_headers=["Authorization", "Content-Type"])
install_exception_handlers(app)
app.include_router(api_router)
