# Product Requirements Document — Mira, Voice AI Museum Guide

> Role: Product Owner. Audience: solo indie developer + hackathon judges.
> Version: 0.1 draft. Status: for review.

## 1. One-line pitch

A voice-first mobile app that knows which artifact you stand near (via BLE beacons) and answers your spoken questions like a human guide, powered by Meta Muse.

## 2. Problem

- Human guides don't scale: 1 guide per group, fixed language, fixed timings.
- Text/chat apps break immersion: visitor looks at phone, not the artifact.
- Audio guides are linear: press number, hear recording, no follow-up questions.
- Museums/palaces/galleries want rich storytelling without hardware cost per visitor.

## 3. Users

| Persona | Goal | Pain today |
|---|---|---|
| Visitor (primary) | Point, ask, listen. Hands-free learning. | Can't ask follow-ups to audio guides |
| Admin / Curator (buyer) | Configure artifacts once, update anytime | Re-recording audio is costly |
| Venue owner | More engagement, analytics, upsell | No data on what visitors care about |

Assumption: visitor has smartphone + headphones. Venue has BLE beacons (simulated for hackathon).

## 4. Core user journey (happy path)

1. Visitor installs app, grants Bluetooth + Mic, selects language.
2. Visitor walks in. App scans beacons, resolves `nearest_artifact`.
3. App shows "You are near: [Artifact name]" card with mic button.
4. Visitor taps mic OR says "Hey Mira" (both P0), asks: "What is the story behind this?"
5. App listens (STT) → sends query + artifact context to Muse → streams spoken answer (TTS) with human prosody.
6. Visitor asks follow-ups ("Who made it?", "How old is it?") without re-selecting the artifact.
7. Visitor walks away. App detects new nearest artifact, updates context, greets: "You're now near..."

## 5. Scope: MVP (hackathon demo) vs stretch

### MVP must-have (P0) — 16h sprint, both triggers

- [ ] Simulated BLE discovery: mock beacon scanner with venue → gallery → artifact resolution + manual "walk mode".
- [ ] Near-artifact detection: nearest-artifact card + confidence (immediate/near/far).
- [ ] Mic-button voice loop: tap → listen → transcribe → answer via voice + text fallback.
- [ ] Always-listening wake word "Hey [Name]": hotword → auto-start STT → answer. Battery explicitly ignored. On/off toggle in UI.
- [ ] Muse-powered answers: artifact-grounded responses, no hallucination outside admin content + general knowledge flag.
- [ ] Admin portal (web): CRUD artifact (id, title, beacon binding, short/long description, history, fun facts, language).
- [ ] Human-like voice output: neural TTS with pauses, emphasis, storytelling tone.
- [ ] Offline-safe demo: pre-cached current gallery content + graceful "I don't know" fallback.

### Stretch (P1, only if time)

- [ ] Real BLE hardware (ESP32 / Estimote) integration.
- [ ] Analytics: dwell time, top questions per artifact.
- [ ] Kids mode / expert mode personas.

### Out of scope (16h freeze)

- Ticketing, indoor navigation/maps, AR overlays, social features, multilingual (English only), background BLE scan.

## 6. Functional requirements

### FR-1: Proximity

- FR-1.1 App scans BLE (or simulated) in foreground; background scan is stretch.
- FR-1.2 Resolve nearest artifact by strongest RSSI with debounce (e.g. stable 2s before switch).
- FR-1.3 Show signal state: `searching | near: X (high/med/low confidence) | no beacon`.

### FR-2: Voice interaction

- FR-2.1 Mic button with states: idle / listening / thinking / speaking / error.
- FR-2.2 Wake-word path: "Hey Mira" detected → chime → auto-start STT without tap. Same downstream as mic button.
- FR-2.3 Wake-word toggle in UI (default ON for demo) + visual "listening for Hey Mira..." indicator.
- FR-2.4 Barge-in: tapping mic OR saying wake word while speaking stops TTS and listens again.
- FR-2.5 Auto-stop on silence (VAD ~1.2s) + max 30s per utterance.
- FR-2.6 Always show text transcript of Q + A for noisy halls and judging.

### FR-3: Answers

- FR-3.1 Every answer uses injected context: current artifact, conversation history, visited stops, visitor interests, and summaries of other gallery artifacts.
- FR-3.2 Muse is the normal brain. It may answer beyond the admin record, compare artifacts, explain for different ages, recommend, and handle reasonable small talk. Stored FAQs are only an offline/emergency fallback, never the normal script.
- FR-3.3 If a specific detail is absent, Muse may use general knowledge when reasonably confident and should qualify naturally when uncertain. It must not invent venue-specific timings, prices, locations, or policies that were not supplied.
- FR-3.4 Answer depth follows the visitor: brief by default for voice, expanded when asked "in detail", simplified when asked for a child.

### FR-4: Admin portal

- FR-4.1 CRUD: venue → gallery → artifact → beacon binding (UUID/major/minor).
- FR-4.2 Fields per artifact: `title, short_intro (spoken on arrival), description, history, maker, era, materials, fun_facts[], faq[], media_urls[], language`.
- FR-4.3 Publish/draft toggle. Changes reflect in app within 60s (poll or push).
- FR-4.4 Single admin login for hackathon (no RBAC).

## 7. Non-functional requirements

- Voice latency: tap-to-first-audio < 4s on 4G (P0); < 2s stretch with streaming.
- Proximity switch: < 3s after simulated move.
- Cost: Muse calls only per question, with cached artifact context; no polling LLM.
- Privacy: MVP is always-listening for hotword ON-DEVICE (no audio leaves phone until wake word or tap). State this in demo. Toggle to disable.
- Accessibility: subtitles always, large mic button, haptic feedback.

## 8. Success metrics (hackathon)

- Demo completes: 3 artifacts → 3 voice Q&A loops, zero manual artifact selection.
- Judges remember: voice-first (not chat), proximity-aware, human-like speech.
- Differentiation line: "Shazam for place + ChatGPT for voice + CMS for curators."

## 9. Open questions (need your call)

See [hackathon plan](04-hackathon-plan.md) §8. Biggest three are in the chat reply.

## 10. Assistant name — LOCKED: Mira

Wake word: "Hey Mira". Project: Mira. Repo: `mira-ai-museum-guide`.
