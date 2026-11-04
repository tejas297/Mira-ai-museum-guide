# System Architecture — Voice AI Museum Guide

> Role: Software Architect (IoT + mobile + voice). Status: draft v0.1.
> Guiding constraint: solo dev, no BLE hardware, hackathon timeline. Simulate hardware, prove software.

## 1. Architecture at a glance (text diagram)

```
[ Admin Web Portal ] --CRUD--> [ Backend API + CMS DB ]
        |                              |
        | publish                      | GET /venues/:id/artifacts (cached)
        v                              v
[ Curator content ]            [ Mobile App ]
                                        |
              +-------------------------+-------------------------+
              |                         |                         |
   [Beacon Layer]              [Voice Pipeline]           [Muse Client]
   Real BLE scan (P1)           Mic btn -> VAD             prompt = system
   MockBeaconService (P0)       STT -> LLM -> TTS          + artifact ctx
   (slider/auto-walk)           barge-in, subtitles        + chat history
```

Backend is thin: content delivery + Muse proxy (keeps API key off device) + optional TTS proxy.

## 2. Component decisions

### 2.1 Mobile app (the demo star) — LOCKED: Native Android (Kotlin)

- Single-module Kotlin app, minSdk 26+. No cross-platform bridge to debug in 16h.
- First-class APIs: `BluetoothLeScanner` (later), `SpeechRecognizer` (STT), `TextToSpeech` with Google neural voices (TTS), `AudioRecord` for hotword feed.
- State: `currentVenue, nearestArtifact {id, rssi, confidence, since}, voiceState, hotwordEnabled, chatHistory[last 6]`.
- Permissions upfront: Bluetooth scan/connect, record audio, internet. Foreground service for hotword loop (battery ignored per your call).

### 2.2 BLE / proximity layer (IoT domain)

Real model (design for this, simulate now):

- Standard: **iBeacon** (UUID + Major + Minor) or Eddystone-UID. Pick one; iBeacon has best plugin support.
- Mapping: `UUID = venue`, `Major = gallery/room`, `Minor = artifact`.
- RSSI → zone: `immediate (<0.5m) / near (<3m) / far (>3m)` + debounce 2s + hysteresis to avoid flapping.
- Scan: foreground 1Hz; background stretch.

Simulation for hackathon (P0) — pick ONE primary + one backup:

| Option | How | Pros | Cons |
|---|---|---|---|
| A. In-app mock scanner (recommended) | `MockBeaconService` emits same events as real scanner; UI slider + "auto-walk" tour + venue map tap | Zero hardware, deterministic demo, works on emulator | Judges may ask "is BLE real?" — answer with interface |
| B. Phone-as-beacon | Second phone advertises iBeacon via app (e.g. Beacon Simulator) | Real radio path | Needs 2 phones, flaky |
| C. ESP32 (stretch) | 3x ESP32 as iBeacons | Real IoT story | Procurement + flashing time |

Key: code against a `BeaconScanner` interface. Mock and real implement it. Demo uses mock; swapping to real is config-only. This is your architecture win to tell judges.

```text
interface BeaconScanner {
  stream<BeaconSightings> scan();   // id, rssi, timestamp
}
class BleBeaconScanner implements BeaconScanner { ... }   // P1
class MockBeaconScanner implements BeaconScanner { ... }  // P0
```

### 2.3 Voice pipeline — BOTH triggers P0

```
Path A (mic):  tap mic → STT (SpeechRecognizer, VAD 1.2s / max 30s) → Muse → TTS → play
Path B (hotword): Porcupine "Hey {Name}" → chime → auto-STT → Muse → TTS → play
                                          ↑ live transcript      ↑ text + speech + hook
```

- Hotword engine (16h pick): **Picovoice Porcupine Android SDK** — on-device, sample app runs in ~30 min, custom wake word via console. Needs free `PORCUPINE_ACCESS_KEY`. Fallback if key/signup fails: OpenWakeWord TFLite, last resort: looping SpeechRecognizer (flaky, keep mic as backup).
- STT: Android `SpeechRecognizer` (free, English, offline pack available). No cloud STT in 16h.
- TTS: Android `TextToSpeech` + Google TTS neural voice (en-IN or en-US). SSML-ish control via prompt-shaped punctuation + `setSpeechRate(0.95)` + utterance pauses. Cache per FAQ answer.
- Human-like checklist: 30–60s answers, 1 question-hook at end ("Want the scandal behind it?"), contractions, pauses before reveal, no bullet-reading.
- Barge-in: tap mic OR wake word while speaking → `tts.stop()` → listen again.
- Hotword lifecycle: foreground service, auto-restart on STT/TTS end, toggle in UI. Echo guard: pause hotword while TTS plays, resume 300ms after.

### 2.4 Meta Muse integration

- All Muse calls go via backend proxy: `POST /api/ask` with `{artifact_id, question_text, history[]}`. Never ship the API key in the app.
- System prompt (per request): persona + current artifact facts + venue tone + length + "say when unsure".
- Retrieval: for MVP, inject full artifact record (small). For large catalogs later: embed + vector search top-3 chunks.
- Streaming: stream tokens → sentence-buffer → streaming TTS for <2s first audio (stretch; MVP can wait-then-speak).
- Guardrails: artifact grounding, "Beyond gallery notes..." prefix, profanity/decline policy, language match.

### 2.5 Backend + admin portal

- Backend (solo-friendly): Node (Express/Fastify) or Python (FastAPI) + SQLite/Postgres. Endpoints in [data model doc](03-data-model-api.md).
- Admin portal: simplest web (Next.js / plain React / even Retool for hackathon). Auth: single password for demo.
- Content sync: app polls `GET /artifacts?venue=&updated_since=` every 60s + on resume; cache to disk for offline demo.

## 3. Data flow: one question

1. Mock scanner emits `minor=12, rssi=-55` → resolver sets `nearest=artifact_12`.
2. UI shows artifact card. User taps mic, speaks.
3. STT text + `artifact_12` + history → `POST /api/ask`.
4. Backend loads artifact record, builds prompt, calls Muse, returns `{answer_text, ssml, sources}`.
5. App displays text, speaks SSML, saves history.

## 4. Deployment (hackathon-minimal)

- Backend + admin: single free-tier host (Render/Fly/Railway). SQLite is fine.
- App: debug APK / TestFlight. Pre-install demo venue pack for offline.
- Secrets: `MUSE_API_KEY` only on server env. No key in repo.

## 5. Failure modes to demo-proof

- No beacon: show "Walk closer or pick a stop" + manual artifact picker (always have this backup).
- Noisy hall: transcript visible, retry button, text input fallback (hidden but present).
- Muse down/slow: cached FAQ answers per artifact + "Let me tell you what the gallery notes say..." path.
- Mic denied / BT off: inline permission cards, deep-link to settings.

## 6. What to tell judges (30s)

"Hardware-agnostic proximity interface with a mock BLE implementation today, real beacons tomorrow. Voice-first RAG over curator content via Meta Muse. One backend, one CMS, one app."
