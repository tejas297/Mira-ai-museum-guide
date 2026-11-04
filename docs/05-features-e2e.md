# End-to-End Features — Mira System

> The single feature checklist for the whole system. Status key: DONE / NEXT / STRETCH.
> Owners: A = Android app, S = server (FastAPI), W = admin web, D = demo.

## 1. Visitor app (Android, Kotlin) — owner A

| ID | Feature | Priority | Status |
|---|---|---|---|
| F1.1 | Load venue artifacts from server, cache on device (offline demo) | P0 | DONE |
| F1.2 | Mock beacon walk: slider + auto-walk + tap-a-stop manual picker | P0 | DONE |
| F1.3 | Nearest-artifact resolution with debounce (no flapping) | P0 | DONE |
| F1.4 | Arrival greeting: play artifact `short_intro` on stop change | P0 | DONE (TTS triggered; audibility needs physical device) |
| F1.5 | Mic-button voice loop: STT → `/ask` → TTS | P0 | BUILT (voice I/O needs physical device) |
| F1.6 | Always-listening "Hey Mira" via Vosk keyword spotting (foreground service) | P0 | DONE (model + service verified; phrase firing needs device mic) |
| F1.7 | Follow-up context: last 6 turns sent with each question | P0 | DONE (live-context check needs Muse key) |
| F1.8 | Barge-in: tap mic (or wake word) stops TTS and listens | P0 | BUILT (audio check on device) |
| F1.9 | Live subtitles: transcript of question + answer always visible | P0 | DONE |
| F1.10 | Typed-question fallback (noisy hall) | P0 | DONE |
| F1.11 | Hotword on/off toggle + "listening for Hey Mira" indicator | P0 | DONE |
| F1.12 | Permission cards (mic, bluetooth) with settings deep-link | P1 | PARTIAL (runtime requests; no cards UI) |
| F1.14 | Streaming playback: TTS starts on first sentence, not full answer | P0 | DONE |
| F1.15 | AI arrival greeting per stop (cached per session) | P0 | DONE (fallback = short_intro) |
| F1.16 | Proactive Mira: unsolicited hook after 20s dwell, max 3 per stop | P0 | DONE (verified on phone) |
| F1.17 | Tour memory + interests: visited titles and visitor interests shape every answer | P0 | DONE (prompt-verified via stub) |
| F1.18 | Conversational follow-ups: mic reopens after each answer ("anything else?"), silence ends quietly, tap pauses; after greetings/hooks too when hands-free mode is on | P0 | DONE (follow-up listen opening logcat-verified on phone) |
| F1.19 | First-run welcome screen: what Mira does + English/हिंदी picker; shown once, then straight to guide | P0 | DONE (verified on phone: fresh install shows it, relaunch skips it) |
| F1.20 | Hindi end-to-end: `hi-IN` speech recognition, Hindi TTS voice, greetings/answers in Devanagari; Language button switches live | P0 | DONE (Hindi greeting from live Muse verified on phone) |
| F1.21 | Redesigned voice-first UI: teal “You are near” hero with proximity pill, large Ask Mira button with Listening/Thinking/Speaking states, status pill, card layout | P0 | DONE (screenshot-verified on phone) |
| F1.22 | Three beacon views with switcher (remembered): **List** nearest-first, **Cards** 2-column tiles, **Radar** proximity rings with YOU in centre; tap a beacon in Simulated mode to jump there | P0 | DONE (all three screenshot-verified; tap-to-jump switches the stop) |
| F1.23 | Smooth interaction: single mic state machine (Ask/Listening/Thinking/Tap-to-interrupt) with colour + pulse + live voice-level scaling, true barge-in (stale streamed sentences dropped by generation guard), reusable recognizer, smoothed RSSI with stable list order, animated hero on stop change, haptics, no auto-scroll jumps | P0 | DONE (Thinking/Speaking/Listening-after-barge-in screenshot-verified; proactive re-speak after 20s dwell observed) |
| F1.24 | Voice-first chrome: Ask Mira fixed at bottom centre with press bounce, haptic tick, and expanding rings while listening/speaking; Conversation hidden by default behind a Show/Hide button (choice remembered) | P0 | DONE (screenshot-verified on phone, incl. ring mid-animation) |
| F1.25 | Real beacon honesty: panel lists only beacons heard in the last seconds (`visibleBeaconMinors`), header shows “N active”, clear empty state; mock still lists all | P0 | DONE (3 unit tests in `BeaconFilterTest`; phone shows 0 active with no rows) |
| F1.26 | Minimal modern chrome: top-right icon buttons (globe EN/हिं toggle, heart = My interests), Hey Mira on-off as a small chip above the mic, status strip removed in favour of event toasts, smaller bottom-centre Ask Mira with a chat button that opens the conversation as a scrollable popup (✕ or outside to close), Auto tour/Next hidden in Real mode | P0 | DONE (icons, wake chip, toast “Mira will speak English now.”, popup open/close, and Real-mode control hiding all screenshot-verified) |

## 6. Verification evidence (2026-11-04, this machine)

- `gradle assembleDebug` OK → `app/build/outputs/apk/debug/app-debug.apk` (41MB); `gradle lintDebug` OK.
- Emulator (API 37): app loads 3 stops from backend at `10.0.2.2:8000`; slider walk switches card to Chinese Porcelain with greeting; typed "Who painted this?" returns the Ravi Varma FAQ answer in transcript; wake toggle downloads the Vosk model and reaches "Listening for Hey Mira..." with the foreground service running.
- Backend (fallback mode, no key yet): `/health`, artifacts, `/ask`, admin login + PUT persist all verified by curl.
- Physical phone (USB, `adb reverse` to backend): app loads 3 stops; "Hey Mira" hotword fired from speaker playback, STT transcribed, spoken FAQ answer returned, wake loop resumed ("Listening for Hey Mira..."). Mic-button sessions also captured live speech → spoken answers. Multiple back-and-forth exchanges visible in transcript + screenshot. Beacon slider switched Sita → Chinese Porcelain with greeting. Frame stats: 950 frames, p50 12ms, p95 24ms, 9.3% janky.
- Emulator audio is unusable (host PulseAudio driver init fails in guest), so voice rows were verified on the phone instead. Emulator still covers UI/walk/typed paths.
- Live Muse is now verified through `/ask` with open-ended interpretation, cross-artifact comparison, child-friendly detail, small talk, and a safe non-invented answer for an unknown closing time. Admin edit appearing in app remains the main unverified integration item.
- Curated FAQ matching is only an offline/emergency fallback. It now requires at least two shared content words; live Muse does not use it as a script.
- Welcome + Hindi (2026-11-05): fresh install (`pm clear`) shows the welcome screen; choosing हिंदी enters the guide and Mira greets in Hindi from live Muse; relaunch goes straight to the guide with the language kept.
- UI redesign (2026-11-05): new main + welcome screens verified by phone screenshots; List/Cards/Radar beacon views all render live data; tapping the Bronze row in List switched the hero card and proximity pill to Bronze Sculpture. `gradle lintDebug` passes after adding BLE permission guards in `BleBeaconScanner` and `ACCESS_COARSE_LOCATION`.
| F1.13 | Real BLE scan (iBeacon) behind same `BeaconScanner` interface, Simulated/Real switch in UI | P0 | DONE (real-radio proof: laptop iBeacon minor 12 detected at -47 dBm / Immediate on phone) |
| F2.7 | `POST /events` + `GET /admin/analytics`: questions, dwell, top questions per artifact | P1 | DONE (app dwell events verified in DB) |

## 2. Backend (FastAPI + SQLite) — owner S

| ID | Feature | Priority | Status |
|---|---|---|---|
| F2.1 | `GET /venues/{id}/artifacts` + `GET /artifacts/{id}` (published only) | P0 | DONE |
| F2.2 | `POST /ask`: Muse proxy, artifact-grounded system prompt, SSE sentence streaming, greeting/proactive intents, visited+interests in prompt | P0 | DONE (live Muse needs `server/.env` key; streaming path verified against stub Muse) |
| F2.3 | Curated offline fallback only when Muse is unconfigured/fails; never the normal answer path | P0 | DONE |
| F2.4 | Seed load on first run from `demo/seed_artifacts.json` | P0 | DONE |
| F2.5 | Human-like flexible answers: open-ended reasoning, cross-artifact context, age/depth adaptation, natural uncertainty | P0 | DONE (live-verified) |
| F2.6 | `muse-voice-transcribe-1.0` STT upgrade | STRETCH | — |

## 3. Admin web (served by FastAPI) — owner W

| ID | Feature | Priority | Status |
|---|---|---|---|
| F3.1 | Single-password login | P0 | DONE |
| F3.2 | Artifact CRUD (title, intro, history, maker, era, materials, facts, FAQs) | P0 | DONE |
| F3.3 | Beacon binding (major/minor) + publish/draft | P0 | DONE |
| F3.4 | Live-edit demo: publish reflects in app on next fetch (<60s) | P0 | DONE |

## 4. Voice quality (cross-cutting) — owners A+S

Rules enforced in prompt + TTS: 30–45s spoken answers, contractions, one vivid detail, pause before reveal, one follow-up hook, admits uncertainty, redirects off-topic questions.

## 5. Demo (3 minutes) — owner D

Walk 3 seeded stops (Ravi Varma painting → bronze → Chinese porcelain). Mic question, wake-word follow-up, one live admin edit. Backups: manual picker, typed question, FAQ fallback, pre-cached content. Script: [04-hackathon-plan.md](04-hackathon-plan.md).
