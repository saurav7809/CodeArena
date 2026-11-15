import ipaddress
import logging
import os
import secrets
from threading import BoundedSemaphore

from fastapi import FastAPI, Header, HTTPException, Request

from .runner import Job, Judgment, evaluate, toolchains

app = FastAPI(title="CodeArena local judge", docs_url=None, redoc_url=None, openapi_url=None)
logger = logging.getLogger(__name__)
concurrency = int(os.getenv("CODEARENA_JUDGE_CONCURRENCY", "1"))
if not 1 <= concurrency <= 8:
    raise ValueError("Set CODEARENA_JUDGE_CONCURRENCY between 1 and 8.")
slots = BoundedSemaphore(concurrency)


def trusted_enabled() -> bool:
    return os.getenv("CODEARENA_TRUST_LOCAL_CODE") == "1" and len(os.getenv("CODEARENA_JUDGE_TOKEN", "")) >= 32


def allowed_client(request: Request) -> bool:
    try:
        if not request.client:
            return False
        address = ipaddress.ip_address(request.client.host)
        return address.is_loopback or (os.getenv("CODEARENA_JUDGE_CONTAINER") == "1" and address.is_private)
    except ValueError:
        return False


@app.get("/health")
def health(request: Request):
    if not allowed_client(request):
        raise HTTPException(403, "The judge accepts requests from its development network.")
    tools = toolchains()
    return {"enabled": trusted_enabled(), "languages": {"PYTHON": True, "JAVA": bool(tools["java"] and tools["javac"]),
                                                        "CPP": bool(tools["cpp"])}}


@app.post("/execute")
def execute(job: Job, request: Request, x_judge_token: str = Header(default="")) -> Judgment:
    expected = os.getenv("CODEARENA_JUDGE_TOKEN", "")
    if not allowed_client(request) or not trusted_enabled():
        raise HTTPException(403, "Enable the judge explicitly for trusted local development.")
    if not secrets.compare_digest(expected.encode(), x_judge_token.encode()):
        raise HTTPException(401, "Invalid judge credential.")
    if not slots.acquire(blocking=False):
        raise HTTPException(429, "The local judge is busy.")
    try:
        return evaluate(job)
    except (OSError, ValueError, RuntimeError) as error:
        logger.warning("Local execution failed with %s", type(error).__name__)
        return Judgment(verdict="INTERNAL_ERROR", totalCases=len(job.cases), diagnostic="Local execution could not start.")
    finally:
        slots.release()
