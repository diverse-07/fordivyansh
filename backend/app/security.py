"""Bound request memory, require explicit access, and bound paid attempts durably."""
import asyncio
from contextlib import closing
import hmac
import sqlite3
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from starlette.responses import JSONResponse
from app.config import settings

async def reserve_attempt():
    def reserve():
        path = Path(settings.QUOTA_DATABASE)
        path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(path, timeout=2)) as db:
            db.execute("CREATE TABLE IF NOT EXISTS quota(day TEXT PRIMARY KEY, attempts INTEGER NOT NULL)")
            db.execute("BEGIN IMMEDIATE")
            day = datetime.now(timezone.utc).date().isoformat()
            row = db.execute("SELECT attempts FROM quota WHERE day=?", (day,)).fetchone()
            if (row[0] if row else 0) >= settings.DAILY_AI_ATTEMPTS:
                raise RuntimeError("AI_BUDGET_EXHAUSTED")
            db.execute("INSERT INTO quota VALUES(?,1) ON CONFLICT(day) DO UPDATE SET attempts=attempts+1", (day,))
            db.commit()
    await asyncio.to_thread(reserve)

class SecurityMiddleware:
    def __init__(self, app):
        self.app = app
        self.global_requests = deque()
        self.lock = asyncio.Lock()
    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        async def reject(code, status):
            await JSONResponse({"detail": code},status_code=status,headers={"Cache-Control":"no-store"})(scope,receive,send)
        headers = dict(scope["headers"])
        if scope["path"] not in ("/", "/health", "/docs", "/openapi.json", "/redoc") and scope["method"] != "OPTIONS":
            if not settings.API_ACCESS_TOKEN:
                return await reject("ACCESS_NOT_CONFIGURED",503)
            supplied = headers.get(b"authorization",b"").decode("latin1")
            if not hmac.compare_digest(supplied, "Bearer "+settings.API_ACCESS_TOKEN):
                return await reject("UNAUTHORIZED",401)
            async with self.lock:
                now=time.monotonic()
                # The authenticated service token is the principal; do not trust forwarded IP headers.
                q=self.global_requests
                while q and q[0] <= now-60: q.popleft()
                if len(q)>=36: return await reject("RATE_LIMIT",429)
                q.append(now)
        try:
            length=int(headers.get(b"content-length",b"0"))
        except ValueError:
            return await reject("INVALID_CONTENT_LENGTH",400)
        if length < 0 or length > settings.MAX_BODY_BYTES:
            return await reject("BODY_TOO_LARGE",413)
        chunks=[]; total=0
        deadline=time.monotonic()+10
        while True:
            try:
                message=await asyncio.wait_for(receive(),timeout=max(.001,deadline-time.monotonic()))
            except asyncio.TimeoutError:
                return await reject("BODY_TIMEOUT",408)
            if message["type"]=="http.disconnect": return
            chunk=message.get("body",b"");total+=len(chunk)
            if total>settings.MAX_BODY_BYTES: return await reject("BODY_TOO_LARGE",413)
            chunks.append(chunk)
            if not message.get("more_body",False):break
        delivered=False
        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered=True
                return {"type":"http.request","body":b"".join(chunks),"more_body":False}
            return await receive()
        await self.app(scope,bounded_receive,send)
