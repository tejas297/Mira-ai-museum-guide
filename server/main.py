"""Mira backend: content API + Muse proxy + admin CRUD.

Run:  uvicorn main:app --host 0.0.0.0 --port 8000  (from server/ dir, venv active)
Data: SQLite (server/mira.db), seeded from ../demo/seed_artifacts.json on first run.
Secrets: server/.env (see .env.example). Never commit .env.
"""
import hashlib
import json
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

BASE = Path(__file__).resolve().parent
ROOT = BASE.parent

# --- tiny .env loader (no extra dep) ---
def _load_env(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())

_load_env(BASE / ".env")

MUSE_API_URL = os.environ.get("MUSE_API_URL", "https://api.meta.ai/v1").rstrip("/")
MUSE_MODEL = os.environ.get("MUSE_MODEL", "muse-spark-1.3")
MODEL_API_KEY = os.environ.get("MODEL_API_KEY", "")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "changeme")
ADMIN_TOKEN = hashlib.sha256(f"{ADMIN_PASSWORD}:mira".encode()).hexdigest()

DB_PATH = Path(os.environ.get("MIRA_DB_PATH", BASE / "mira.db"))

def _db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def _init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with _db() as conn:
        conn.execute(
            """CREATE TABLE IF NOT EXISTS artifacts (
            id TEXT PRIMARY KEY, payload TEXT NOT NULL,
            status TEXT NOT NULL, updated_at TEXT NOT NULL)"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS interactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, artifact_id TEXT NOT NULL,
            type TEXT NOT NULL, question TEXT DEFAULT '',
            dwell_s REAL DEFAULT 0, ts TEXT NOT NULL)"""
        )
        empty = conn.execute("SELECT COUNT(*) c FROM artifacts").fetchone()["c"] == 0
        if empty:
            seed = ROOT / "demo" / "seed_artifacts.json"
            if seed.exists():
                for art in json.loads(seed.read_text())["artifacts"]:
                    _store(conn, art)

def _store(conn: sqlite3.Connection, art: dict) -> dict:
    art = dict(art)
    art["status"] = art.get("status", "draft")
    art["updated_at"] = datetime.now(timezone.utc).isoformat()
    conn.execute(
        "INSERT OR REPLACE INTO artifacts (id, payload, status, updated_at) VALUES (?,?,?,?)",
        (art["id"], json.dumps(art), art["status"], art["updated_at"]),
    )
    return art

def _get(art_id: str) -> dict | None:
    with _db() as conn:
        row = conn.execute("SELECT payload FROM artifacts WHERE id=?", (art_id,)).fetchone()
    return json.loads(row["payload"]) if row else None

def _list(published_only: bool, updated_since: str | None) -> list[dict]:
    q = "SELECT payload FROM artifacts"
    args: list[str] = []
    if published_only:
        q += " WHERE status='published'"
    with _db() as conn:
        rows = conn.execute(q, args).fetchall()
    arts = [json.loads(r["payload"]) for r in rows]
    return [a for a in arts if not updated_since or a.get("updated_at", "") > updated_since]

app = FastAPI(title="Mira API")
_init_db()

@app.get("/health")
def health() -> dict:
    return {"ok": True, "model": MUSE_MODEL, "muse_configured": bool(MODEL_API_KEY)}

@app.get("/venues/{venue_id}/artifacts")
def venue_artifacts(venue_id: str, updated_since: str | None = None, lang: str = "en") -> dict:
    arts = [a for a in _list(True, updated_since) if a.get("venue_id") == venue_id and a.get("language", "en") == lang]
    return {"artifacts": arts}

@app.get("/artifacts/{art_id}")
def one_artifact(art_id: str) -> dict:
    art = _get(art_id)
    if not art:
        raise HTTPException(404, "artifact not found")
    return {"artifact": art}

# --- /ask: streaming Muse proxy. Muse answers flexibly from the admin-provided
# artifact knowledge base (title/history/maker/facts). The curated fallback below
# only runs when Muse is unavailable; it is never the normal answer path. ---
# Events (SSE): {"type":"sentence","text":...} ... {"type":"done","answer_text":...,"sources":[...]}

class AskRequest(BaseModel):
    artifact_id: str
    question_text: str = ""
    history: list[dict] = []
    lang: str = "en"
    intent: str = "question"  # question | greeting | proactive
    visited: list[str] = []  # titles of artifacts already seen, in order
    interests: list[str] = []  # e.g. ["Stories & legends", "Craft & making"]
    proactive_index: int = 0

_IDENTITY_ANSWER = (
    "I'm Mira, your personal guide in this gallery. I know the stories behind "
    "every artifact here, and I'll tell them as you walk. Ask me about whatever "
    "you're standing in front of."
)
_IDENTITY_HINTS = (
    "who are you", "your name", "what are you", "introduce yourself",
    "who is mira", "about yourself", "hey mira", "hello mira",
)

def _is_identity(question: str) -> bool:
    q = " ".join(question.lower().replace("?", " ").split()).strip()
    return q in ("mira", "hey mira", "hello mira") or any(h in q for h in _IDENTITY_HINTS)

def _faq_fallback(art: dict, question: str) -> str:
    q = question.lower()
    for item in art.get("faq", []):
        words = {w for w in item["q"].lower().replace("?", "").split() if len(w) >= 3}
        if words and len(words & set(q.split())) >= max(2, len(words) // 2):
            return item["a"]
    intro = art.get("short_intro", "")
    note = "Let me tell you what the gallery notes say. "
    return note + intro if intro else note + art.get("description", "")

def _fallback_answer(art: dict, req: AskRequest) -> str:
    if req.intent == "greeting":
        return art.get("short_intro") or art.get("description", "")
    if req.intent == "proactive":
        facts = art.get("fun_facts") or []
        if facts:
            fact = facts[req.proactive_index % len(facts)]
            return f"Here's something most people walk right past. {fact} Want me to tell you more?"
        return art.get("short_intro", "")
    return _faq_fallback(art, req.question_text)

def _catalog_summaries(current_id: str) -> list[dict]:
    keys = ("title", "description", "category", "maker", "era", "materials", "origin")
    summaries = []
    for other in _list(True, None):
        if other.get("id") == current_id:
            continue
        summaries.append(
            {k: other.get(k) for k in keys if other.get(k) not in (None, "", [])}
        )
    return summaries


def _system_prompt(art: dict, req: AskRequest) -> str:
    fact_keys = (
        "title", "description", "history", "maker", "artist", "category",
        "era", "materials", "dimensions", "origin", "collection", "fun_facts",
    )
    facts = {
        k: art.get(k) for k in fact_keys if art.get(k) not in (None, "", [])
    }
    catalog = _catalog_summaries(art.get("id", ""))
    memory = ""
    if req.visited:
        memory = (
            f" The visitor has already seen, in order: {', '.join(req.visited)}."
            " When it adds warmth, reference an earlier stop in one brief comparison. Never list them all."
        )
    taste = ""
    if req.interests:
        taste = f" The visitor leans toward: {', '.join(req.interests)}. Angle your telling that way."
    lang_rule = (
        "Speak in natural, warm Hindi written in Devanagari script. Mix in English "
        "only for names and common terms, the way a real Indian guide speaks. "
        if req.lang == "hi"
        else "Speak in English. "
    )
    base = (
        "You are Mira, a warm museum storyteller in a museum gallery. "
        "Your name is Mira. If anyone asks who you are, introduce yourself as Mira, "
        "their gallery guide. Never call yourself an AI model or language model. "
        f"{lang_rule}"
        f"Current artifact record: {json.dumps(facts)}. "
        f"Other artifacts in this gallery: {json.dumps(catalog)}.{memory}{taste} "
    )
    if req.intent == "greeting":
        return base + (
            "Task: give a spoken arrival greeting, 25-40 words. Warm welcome, one vivid hook about "
            "this artifact, end by inviting a question. No lists. Sound like a person, not a label."
        )
    if req.intent == "proactive":
        return base + (
            "Task: the visitor has been standing here quietly. Offer ONE intriguing hook from the "
            "fun facts, 15-25 words, fresh angle, and end with an inviting question like "
            "'Want to hear it?' Do not repeat the greeting."
        )
    return base + (
        "Guide like an attentive human, not a script and not an FAQ lookup. Answer the visitor's "
        "actual question directly. For anything about this artifact, the admin record is your "
        "ground truth: if broader knowledge conflicts with it, the admin record wins. For related "
        "questions the record does not cover, answer from broader knowledge the way a human guide "
        "would — naturally, without announcing that the notes ran out. Then use conversation "
        "context, visited stops, and the catalog of other artifacts when they help. You may "
        "explain, compare, recommend, simplify, translate or pronounce terms, share a guide's opinion, "
        "handle follow-ups and small talk, and answer questions about other artifacts. If the visitor's "
        "meaning is ambiguous, ask one short clarifying question. Do not refuse or redirect merely "
        "because a question goes beyond the record or the current artifact; if it is unrelated, answer "
        "briefly when appropriate and return to the gallery only when that feels natural. If a specific "
        "detail is absent from the curated record, use broader knowledge only when reasonably confident "
        "and qualify naturally when uncertain. Never invent venue-specific timings, prices, locations, "
        "or policies that are not supplied. Match the requested depth: keep quick answers to about "
        "20-35 words, default to 45-80 words, and expand to 100-160 words when asked to explain "
        "'in detail'. Speak conversationally, with contractions and a vivid detail when it fits. "
        "End with a follow-up only when it feels natural."
    )

def _messages(art: dict, req: AskRequest) -> list[dict]:
    msgs = [{"role": "system", "content": _system_prompt(art, req)}]
    for turn in req.history[-4:]:
        msgs.append({"role": turn.get("role", "user"), "content": turn.get("text", "")})
    user_text = req.question_text or {
        "greeting": "(The visitor just arrived in front of this artifact.)",
        "proactive": "(The visitor has been standing here quietly for a while.)",
    }.get(req.intent, "")
    msgs.append({"role": "user", "content": user_text})
    return msgs

import re as _re

def _split_sentences(text: str) -> list[str]:
    parts = _re.split(r"(?<=[.!?])\s+", text.strip())
    return [p for p in (s.strip() for s in parts) if p]

async def _muse_sentence_stream(art: dict, req: AskRequest):
    """Yield sentences from Muse's streamed Responses API output."""
    buffer = ""
    async with httpx.AsyncClient(timeout=60) as client:
        async with client.stream(
            "POST",
            f"{MUSE_API_URL}/responses",
            headers={
                "Authorization": f"Bearer {MODEL_API_KEY}",
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            },
            json={
                "model": MUSE_MODEL,
                "input": _messages(art, req),
                "stream": True,
                "temperature": 0.7,
                "max_output_tokens": 1000,
                "reasoning": {"effort": "low"},
            },
        ) as resp:
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                if not line.startswith("data:"):
                    continue
                raw = line[5:].strip()
                if not raw or raw == "[DONE]":
                    continue
                try:
                    evt = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                delta = ""
                if evt.get("type") == "response.output_text.delta":
                    delta = evt.get("delta", "")
                elif evt.get("type") == "response.output_text.done":
                    delta = ""
                if not delta:
                    continue
                buffer += delta
                sentences = _split_sentences(buffer)
                if len(sentences) > 1:
                    for s in sentences[:-1]:
                        yield s
                    buffer = sentences[-1]
    if buffer.strip():
        yield buffer.strip()

def _sse(obj: dict) -> str:
    return f"data: {json.dumps(obj)}\n\n"

@app.post("/ask")
async def ask(req: AskRequest):
    art = _get(req.artifact_id)
    if not art:
        raise HTTPException(404, "artifact not found")

    async def gen():
        full: list[str] = []
        if req.intent == "question" and _is_identity(req.question_text):
            for s in _split_sentences(_IDENTITY_ANSWER):
                full.append(s)
                yield _sse({"type": "sentence", "text": s})
            yield _sse({
                "type": "done",
                "answer_text": " ".join(full),
                "sources": ["identity"],
                "followup": "Want to hear more about it?",
            })
            return
        source = "faq_fallback"
        if MODEL_API_KEY:
            try:
                async for s in _muse_sentence_stream(art, req):
                    full.append(s)
                    yield _sse({"type": "sentence", "text": s})
                if full:
                    source = "muse"
            except Exception:
                full = []
        if not full:
            answer = _fallback_answer(art, req)
            source = "faq_fallback"
            for s in _split_sentences(answer):
                full.append(s)
                yield _sse({"type": "sentence", "text": s})
        yield _sse({
            "type": "done",
            "answer_text": " ".join(full),
            "sources": [source],
            "followup": "Want to hear more about it?",
        })

    from fastapi.responses import StreamingResponse

    return StreamingResponse(gen(), media_type="text/event-stream")

# --- admin: single-password CRUD (hackathon-simple) ---

def _require_admin(request: Request) -> None:
    if request.headers.get("authorization") != f"Bearer {ADMIN_TOKEN}":
        raise HTTPException(401, "unauthorized")

@app.post("/admin/login")
def admin_login(body: dict) -> dict:
    if body.get("password") != ADMIN_PASSWORD:
        raise HTTPException(401, "bad password")
    return {"token": ADMIN_TOKEN}

class EventRequest(BaseModel):
    type: str  # question | dwell
    artifact_id: str
    question_text: str = ""
    dwell_s: float = 0.0

@app.post("/events")
def log_event(evt: EventRequest) -> dict:
    with _db() as conn:
        conn.execute(
            "INSERT INTO interactions (artifact_id, type, question, dwell_s, ts) VALUES (?,?,?,?,?)",
            (evt.artifact_id, evt.type, evt.question_text.strip(),
             evt.dwell_s, datetime.now(timezone.utc).isoformat()),
        )
    return {"ok": True}

@app.get("/admin/analytics")
def admin_analytics(request: Request) -> dict:
    _require_admin(request)
    with _db() as conn:
        q_counts = dict(conn.execute(
            "SELECT artifact_id, COUNT(*) FROM interactions WHERE type='question' GROUP BY artifact_id"
        ).fetchall())
        dwell = {
            r[0]: {"visits": r[1], "total_s": round(r[2] or 0, 1), "avg_s": round(r[3] or 0, 1)}
            for r in conn.execute(
                "SELECT artifact_id, COUNT(*), SUM(dwell_s), AVG(dwell_s) "
                "FROM interactions WHERE type='dwell' GROUP BY artifact_id"
            ).fetchall()
        }
        top_rows = conn.execute(
            "SELECT artifact_id, lower(trim(question)) AS q, COUNT(*) AS c "
            "FROM interactions WHERE type='question' AND question <> '' "
            "GROUP BY artifact_id, q ORDER BY c DESC"
        ).fetchall()
    top_by_art: dict[str, list] = {}
    for art_id, q, c in top_rows:
        top_by_art.setdefault(art_id, [])
        if len(top_by_art[art_id]) < 3:
            top_by_art[art_id].append({"question": q, "count": c})
    arts: dict[str, dict] = {}
    for art_id in set(q_counts) | set(dwell) | set(top_by_art):
        d = dwell.get(art_id, {"visits": 0, "total_s": 0.0, "avg_s": 0.0})
        arts[art_id] = {
            "questions": q_counts.get(art_id, 0),
            "dwell_visits": d["visits"],
            "dwell_total_s": d["total_s"],
            "dwell_avg_s": d["avg_s"],
            "top_questions": top_by_art.get(art_id, []),
        }
    return {"artifacts": arts}

@app.get("/admin/artifacts")
def admin_list(request: Request) -> dict:
    _require_admin(request)
    return {"artifacts": _list(False, None)}

@app.put("/admin/artifacts/{art_id}")
def admin_put(art_id: str, art: dict, request: Request) -> dict:
    _require_admin(request)
    art["id"] = art_id
    with _db() as conn:
        saved = _store(conn, art)
    return {"artifact": saved}

@app.post("/admin/artifacts")
def admin_create(art: dict, request: Request) -> dict:
    _require_admin(request)
    with _db() as conn:
        saved = _store(conn, art)
    return {"artifact": saved}

app.mount("/static", StaticFiles(directory=BASE / "static"), name="static")

@app.get("/admin")
def admin_page() -> FileResponse:
    return FileResponse(
        BASE / "static" / "admin.html", headers={"Cache-Control": "no-store"}
    )

@app.get("/")
def home() -> dict:
    return {"app": "Mira", "admin": "/admin", "health": "/health"}
