"""Focused regression tests for /ask identity + fallback matching.

Run: .venv/bin/python server/test_main.py  (fallback forced: no Muse key)
"""
import json
import os
import sys

os.environ["MODEL_API_KEY"] = ""  # force fallback before importing main
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402

client = TestClient(main.app)


def ask(question: str, artifact: str = "art_001") -> dict:
    resp = client.post(
        "/ask", json={"artifact_id": artifact, "question_text": question}
    )
    assert resp.status_code == 200, resp.text
    done = None
    for line in resp.text.splitlines():
        if line.startswith("data:"):
            evt = json.loads(line[5:])
            if evt.get("type") == "done":
                done = evt
    assert done is not None, resp.text
    return done


def test_identity_introduces_mira():
    done = ask("who are you")
    assert "Mira" in done["answer_text"], done
    assert "guide" in done["answer_text"].lower(), done


def test_faq_still_matches():
    done = ask("Who painted this?")
    assert "Ravi Varma" in done["answer_text"], done


def test_no_false_faq_match():
    done = ask("what is this")
    assert "pioneer of modern Indian art" not in done["answer_text"], done
    assert "final moment" in done["answer_text"], done


def test_live_prompt_is_flexible_not_faq_scripted():
    art = main._get("art_001")
    req = main.AskRequest(
        artifact_id="art_001",
        question_text="Why does this scene still feel painful today?",
        visited=["Bronze Sculpture"],
        interests=["Stories & legends"],
    )
    prompt = main._system_prompt(art, req)
    assert "not a script" in prompt, prompt
    assert "admin record wins" in prompt, prompt
    assert "broader knowledge" in prompt, prompt
    assert "other artifacts" in prompt, prompt
    assert "Bronze Sculpture" in prompt, prompt
    assert "Chinese Porcelain Artifact" in prompt, prompt
    assert "in detail" in prompt, prompt
    assert "If the question is off-topic, redirect" not in prompt, prompt
    assert "start with 'Beyond the gallery notes,'" not in prompt, prompt


def test_analytics_events():
    probe = "analytics probe question xyz"
    for _ in range(2):
        r = client.post(
            "/events",
            json={"type": "question", "artifact_id": "art_001", "question_text": probe},
        )
        assert r.status_code == 200, r.text
    r = client.post(
        "/events",
        json={"type": "dwell", "artifact_id": "art_001", "dwell_s": 42.0},
    )
    assert r.status_code == 200, r.text
    r = client.get(
        "/admin/analytics", headers={"Authorization": f"Bearer {main.ADMIN_TOKEN}"}
    )
    assert r.status_code == 200, r.text
    stats = r.json()["artifacts"]["art_001"]
    assert stats["questions"] >= 2, stats
    assert stats["dwell_visits"] >= 1, stats
    assert any(t["question"] == probe for t in stats["top_questions"]), stats


if __name__ == "__main__":
    test_identity_introduces_mira()
    print("identity ok")
    test_faq_still_matches()
    print("faq ok")
    test_no_false_faq_match()
    print("false-match ok")
    test_live_prompt_is_flexible_not_faq_scripted()
    print("flexible prompt ok")
    test_analytics_events()
    print("analytics ok")
    print("ALL PASS")
