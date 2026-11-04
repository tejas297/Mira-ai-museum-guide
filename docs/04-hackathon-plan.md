# Hackathon MVP Plan — 16-Hour Solo Sprint

> Goal: a 3-minute demo that proves proximity + BOTH voice triggers + Muse + admin. Native Android, English only.
> Rule: freeze scope NOW. No new features after Hour 4.

## 1. Hour-by-hour (16h)

| Hours | Block | Deliverable | Done when |
|---|---|---|---|
| 0–1 | Seed content | 3 artifacts JSON (title, short_intro, history 150–300w, 3 fun facts, 2 FAQs each) | File `demo/seed_artifacts.json` exists |
| 1–3 | Backend `/ask` | FastAPI/Express: Muse proxy + prompt + FAQ fallback + `GET /artifacts` | `curl POST /api/ask` returns answer_text |
| 3–4 | Admin ugly-CRUD | List → edit → publish (single password). Deploy server somewhere reachable from phone | Edit fun fact on laptop, phone sees it in 60s |
| 4–7 | Android shell + mock beacons | Artifact card + `BeaconScanner` interface + Mock (slider + auto-walk + manual picker) + cache | Walk sim switches card <3s |
| 7–11 | Voice loop (biggest block) | Mic btn → SpeechRecognizer → `/ask` → TTS + subtitles + barge-in; Porcupine hotword service + chime + toggle + echo guard | Both triggers answer 2 Qs in a row, context kept |
| 11–13 | Human voice + grounding | Tune prompt (40–70 words, break before reveal, hook), TTS rate/voice, "Beyond gallery notes" prefix, greeting on artifact change | Answers sound like stories, not bullets |
| 13–15 | Demo-proofing | Permissions cards, offline cache, typed-question fallback, retry, airplane-mode rehearsal | Demo survives: no net, no hotword, noisy room |
| 15–16 | Video + submit | 3-min video, APK, README, submit | Submitted. Sleep. |

## 2. Wake-word integration (fastest path)

1. Try Picovoice Console signup via **Google/GitHub button** (not the email form) → train "Hey Mira" → Android `.ppn` + `PORCUPINE_ACCESS_KEY` (30 min if it works).
2. If signup blocked (company-email wall): switch to **Vosk** — add `vosk-android` dep, bundle small English model, grammar `["hey mira"]` in a foreground service. Test detection in a quiet room first, isolated.
3. Wire (same for either engine): hotword detect → chime → pause hotword → start SpeechRecognizer → on STT result → `/ask` → TTS → on TTS done → resume hotword (+300ms).
4. If hotword fails at Hour 9: SKIP, ship mic-only + show hotword as "in progress" — a working mic beats two broken triggers.

## 3. Demo script (180 seconds)

- 0:00–0:20 — Hook: "Audio guides talk AT you. Ours talks WITH you." Show venue photo.
- 0:20–0:50 — Admin: edit a fun fact live, publish. "Curator updates once, every visitor hears it."
- 0:50–1:10 — Walk: mock-beacon auto-walk switches artifact card. "No QR, no typing numbers."
- 1:10–1:50 — Trigger 1 (mic): tap, "What's the scandal behind this sword?" → spoken story + subtitle.
- 1:50–2:20 — Trigger 2 (wake): "Hey Mira, how old is it?" → proves always-listening + context.
- 2:20–2:50 — Architecture flex: `BeaconScanner` interface (mock today, ESP32 tomorrow) + Muse proxy + on-device hotword.
- 2:50–3:00 — Close: multilingual + analytics next. End line.

Backup for every step: manual artifact picker + typed question + cached FAQ answer.

## 4. Cut list (16h freeze — say NO)

No real BLE, no maps/nav, no visitor login, no payments, no multi-venue polish, no vector DB (inject full record), no cloud STT, no second language, no analytics UI, no CI/CD beyond one deploy + one APK.

## 5. Risks + mitigations

| Risk | Mitigation |
|---|---|
| Porcupine signup/key slow | Start at Hour 7 sharp; Hour-9 kill-switch to mic-only |
| Hall noise kills STT | Subtitles, retry, hidden text input, headset in demo |
| Muse latency | Short answers, sentence play, cached FAQ first |
| BLE called "fake" | Interface slide: same events, swap mock→real by config |
| TTS robotic | Story prompt + 0.95 rate + best en-IN/en-US neural voice |
| Solo burnout | Admin ugly-but-done; freeze scope at Hour 4; rehearse twice |

## 6. Repo layout

```
/app        native Android (Kotlin, single module)
/server     backend + /ask proxy (+ /admin static if simplest)
/docs       these files
/demo       seed_artifacts.json + demo video + slides
```

## 7. Definition of done

- [ ] 3 artifacts seeded, published via admin.
- [ ] Walk sim switches nearest artifact <3s.
- [ ] Mic AND "Hey Mira" each complete 2 consecutive Q&A, context retained.
- [ ] Subtitles + barge-in (mic or hotword stops TTS).
- [ ] Cached fallback answers without Muse.
- [ ] 3-min video + APK + submit.

## 8. I still need from you

1. ~~Assistant name~~ — LOCKED: Mira ("Hey Mira").
2. ~~Muse model~~ — LOCKED: `muse-spark-1.3` via Responses API, streaming, reasoning low, 300 tokens.
3. Picovoice account: can you create it in the next hour (for Porcupine key + `.ppn`)?
4. Backend pick: FastAPI or Express (whichever you write faster)?
