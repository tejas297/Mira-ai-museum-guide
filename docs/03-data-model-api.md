# Data Model + API Contracts

> Companion to PRD + Architecture. Keep this stable before coding.

## 1. Entities

### Venue
```json
{
  "id": "venue_jaipur_palace",
  "name": "City Palace, Jaipur",
  "beacon_uuid": "B9407F30-F5F8-466E-AFF9-25556B57FE6D",
  "default_language": "en",
  "persona": "warm storyteller, short sentences, pauses for drama"
}
```

### Gallery (optional grouping)
```json
{ "id": "gal_armoury", "venue_id": "venue_jaipur_palace", "major": 1, "name": "Armoury Hall" }
```

### Artifact (core)
```json
{
  "id": "art_012",
  "venue_id": "venue_jaipur_palace",
  "gallery_id": "gal_armoury",
  "beacon": { "major": 1, "minor": 12 },
  "title": "Ceremonial Sword of Sawai Jai Singh II",
  "short_intro": "You're looking at the ceremonial sword... <break time='400ms'/> carried only on Dussehra.",
  "description": "2-3 sentence spoken-first summary.",
  "history": "Long-form, Muse-grounding text.",
  "maker": "Jaipur royal workshop",
  "era": "c. 1727",
  "materials": "steel, gold koftgari, ruby",
  "fun_facts": ["It weighs...", "It was never sharpened..."],
  "faq": [{ "q": "How old is it?", "a": "..." }],
  "media_urls": ["https://.../sword.jpg"],
  "language": "en",
  "status": "published",
  "updated_at": "2026-11-04T00:00:00Z"
}
```

Rule: `short_intro` is what the app auto-speaks on arrival (or shows with Play). Everything else grounds Muse answers.

### Interaction log (analytics stretch)
```json
{ "artifact_id": "art_012", "question": "...", "answer_len": 412, "dwell_s": 48, "ts": "..." }
```

## 2. API contracts (backend)

Base: `https://<host>/api`

### GET /venues/:venueId/artifacts
Query: `?updated_since=ISO8601&lang=en`. Response: `{ artifacts: Artifact[] }`. Cached on device.

### GET /artifacts/:id
Response: `{ artifact: Artifact }`. Used for deep-link / manual pick.

### POST /ask (the Muse proxy)
Request:
```json
{
  "artifact_id": "art_012",
  "question_text": "What is this sword famous for?",
  "history": [{ "role": "user", "text": "..." }, { "role": "assistant", "text": "..." }],
  "lang": "en"
}
```
Response:
```json
{
  "answer_text": "Plain text for UI.",
  "answer_ssml": "<speak>...<break time='500ms'/>...</speak>",
  "sources": ["history", "fun_facts[1]"],
  "followup": "Want to hear about the Dussehra ritual?"
}
```
Errors: `429` (rate-limit → app plays cached FAQ), `503` (Muse down → offline path).

### Admin (web only, single auth for hackathon)
- `POST /admin/login` → session cookie.
- `CRUD /admin/artifacts` + `POST /admin/artifacts/:id/publish`.

## 3. Prompt contract (backend → Muse)

System (sketch, tune in code):
```
You are {assistant_name}, a warm museum storyteller at {venue}.
Current artifact JSON: {...}
Rules: answer in {lang}, 40-70 words spoken (~30-45s). Conversational, contractions,
one vivid detail, one <break/> before the reveal. If unsure, say so. If question is
off-topic, redirect to the artifact. If using general knowledge beyond gallery notes,
start with "Beyond the gallery notes,".
End with one short follow-up hook (optional).
```

## 4. Beacon → artifact resolution (client)

```
sightings[] (minor, rssi) → filter venue UUID → strongest RSSI
→ debounce 2s + hysteresis 8dBm → nearestArtifactId
→ load cached artifact → update UI + prefetch Muse greeting
```

Manual override always available: `?demo_artifact=<id>` and in-app picker. Demo-proofing is a feature.

## 5. Seed data for hackathon

Create 3 artifacts minimum, 5 ideal: varied eras + one "scandal/mystery" story (judges remember stories). Each needs: title, short_intro, history (150-300 words), 3 fun facts, 2 FAQs.
