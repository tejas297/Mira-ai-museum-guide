# MuseGuide — Voice AI Museum Guide (Meta AI Hackathon)

Solo indie build: proximity-aware, voice-first AI docent. Stand near an artifact, ask out loud, hear a human-like answer.

## Docs (read in order)

1. [PRD](docs/01-PRD.md) — users, journey, MVP scope.
2. [Architecture](docs/02-architecture.md) — BLE simulation, voice pipeline, Muse proxy, admin.
3. [Data model + APIs](docs/03-data-model-api.md) — artifact schema, `/ask` contract, prompt sketch.
4. [Hackathon plan](docs/04-hackathon-plan.md) — build order, 3-min demo script, cut list.

## MVP in one paragraph

Simulated BLE beacons resolve the nearest artifact. Both triggers work: mic button AND always-listening "Hey [Name]". Either drives listen → Muse (artifact-grounded) → neural TTS with human prosody. Curators manage all content in a simple admin portal. Real beacons are stretch.

## Locked decisions (your calls, 16h sprint)

- Mobile: **Native Android (Kotlin)** — decided by architect, see below.
- Voice: **mic button + always-listening wake word, both P0**. Battery explicitly out of scope.
- BLE: in-app mock scanner implementing a `BeaconScanner` interface; real hardware later.
- Muse key lives only on backend, never in the app.
- Demo language: **English only**.

## Why native Android over React Native for THIS project

Single phone demo, 16 hours, wake word + BLE + low-latency audio. Native wins: first-class `BluetoothLeScanner`, `SpeechRecognizer` + `TextToSpeech` built in, Porcupine wake-word Android SDK with sample code, no JS-bridge audio debugging, one APK to install. RN only if you must demo on an iPhone — you don't.
