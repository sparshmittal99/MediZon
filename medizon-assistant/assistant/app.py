"""HTTP API used by the MediZon Java app. Run with: uvicorn assistant.app:app --port 8001"""
from __future__ import annotations

import os
import secrets
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from . import dialog, insights, monitoring, nlu

TOKEN = os.environ.get("ASSISTANT_TOKEN", "")


@asynccontextmanager
async def lifespan(_: FastAPI):
    nlu.warm_up()          # train both intent models before the first request
    yield


app = FastAPI(title="MediZon Assistant", version="1.0.0", lifespan=lifespan)
app.middleware("http")(monitoring.middleware)


def check_token(token: str | None) -> None:
    if TOKEN and not secrets.compare_digest(token or "", TOKEN):
        raise HTTPException(status_code=401, detail="Bad assistant token")


class ChatRequest(BaseModel):
    role: str = "customer"
    user: dict[str, Any] = Field(default_factory=dict)
    message: str = ""
    payload: dict[str, Any] = Field(default_factory=dict)
    state: dict[str, Any] = Field(default_factory=dict)
    tool_results: dict[str, Any] = Field(default_factory=dict)
    categories: list[str] = Field(default_factory=list)
    now: str | None = None


class InsightsRequest(BaseModel):
    reviews: list[dict[str, Any]] = Field(default_factory=list)
    feedback: list[dict[str, Any]] = Field(default_factory=list)


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.get("/metrics")
def metrics(x_assistant_token: str | None = Header(default=None)) -> dict:
    """Request timings and recent errors, for Admin > Monitoring in the Java app."""
    check_token(x_assistant_token)
    return monitoring.snapshot()


@app.post("/v1/chat")
def chat(req: ChatRequest, x_assistant_token: str | None = Header(default=None)) -> dict:
    check_token(x_assistant_token)
    return dialog.respond(req.model_dump())


@app.post("/v1/insights")
def review_insights(req: InsightsRequest, x_assistant_token: str | None = Header(default=None)) -> dict:
    check_token(x_assistant_token)
    return insights.analyze(req.reviews, req.feedback)
