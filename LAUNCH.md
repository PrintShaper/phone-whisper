# Phone Whisper — Launch Plan

## Current State

Working MVP: overlay dot → tap → record → tap → transcribe (local or cloud) → inject text.
Programmatic Android views (no XML). Basic single-page UI. Models require ADB push.

## Goal

Ship today as a free, open-source MVP. Validate interest before investing more effort.
Indie-hacker style: launch fast, get feedback, iterate if there's traction.

---

## Launch & Monetization Strategy

### Why free for launch

- **Friction is already high** — sideloaded APK + accessibility permissions + model download.
  Adding a paywall on top kills adoption before we know if people want this.
- **Trust matters** — the app requests accessibility service permissions. Open source + free
  is how you earn that trust. People will read the code before granting those permissions.
- **No marginal cost per user** — users bring their own OpenAI keys, local transcription
  runs on their phone. Zero infra costs.
- **HN/PH love this** — "open-source macWhisper for Android" with a GitHub link will do
  better than a paid product page. Free tool → traction → then monetize.

### Launch day plan

1. Push repo to GitHub (public)
2. Add `FUNDING.yml` → GitHub Sponsors button (+ Ko-fi or Buy Me a Coffee)
3. Post to **Hacker News** (Show HN) and/or **Product Hunt**
4. README links to repo, APK release download, sponsor link
5. Collect feedback — does anyone actually use it?

### Monetization options considered

| Option | Verdict |
|--------|---------|
| **Subscription** | Too complex for today — needs a backend server |
| **One-time Pro purchase** (MacWhisper model) | No Play Store listing to handle payments; building payment verification into a sideloaded APK is a rabbit hole |
| **Free + sponsor links** ✅ | Zero effort, no friction, validates demand first |

### If traction materializes

- **Play Store listing** with free tier + paid Pro (in-app purchase)
- Pro features: post-processing, larger models, custom prompts, overlay customization
- Subscription only if we add a hosted backend (e.g., bundled transcription API so users
  don't need their own OpenAI keys)

---

## Chunk 1: Model Downloader (~45 min)

New file: `ModelDownloader.kt`

- Download tar.bz2 from GitHub releases (sherpa-onnx asr-models)
- Extract to app internal storage (`filesDir/models/`)
- Report progress via callback (for UI progress bar)
- Use Android `DownloadManager` or OkHttp for download
- Apache Commons Compress for tar.bz2 extraction
- Add dependency to `build.gradle.kts`

### Model Catalog (hardcoded for now)

| Display Name | Archive | Download Size | Quality |
|---|---|---|---|
| Parakeet 110M ⭐ | `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8` | 100 MB | ★★★ Best value |
| Whisper Base | `sherpa-onnx-whisper-base.en` | 199 MB | ★★★ |
| Parakeet 0.6B | `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` | 465 MB | ★★★★ Best quality |
| Moonshine Tiny | `sherpa-onnx-moonshine-tiny-en-int8` | 103 MB | ★★☆ Fast |

All URLs follow: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{archive}.tar.bz2`

### Tests

- `ModelDownloaderTest.kt` — extraction logic (feed a small tar.bz2, verify files)

---

## Chunk 2: UI Rewrite (~60 min)

Full rewrite of `MainActivity.kt`. Single scrollable page, collapsible sections.

```
┌─────────────────────────────────┐
│ 🎤 Phone Whisper                │
│ ✅ Ready  (or 🔴 Setup needed)  │
├─────────────────────────────────┤
│ ▸ Setup  (auto-collapses)      │
│   Audio permission: ✅ / [Grant] │
│   Accessibility: ✅ / [Enable]  │
├─────────────────────────────────┤
│ ▸ Transcription                 │
│   Engine: ○ Local  ○ Cloud      │
│                                 │
│   ┌─ ⭐ Recommended ──────────┐ │
│   │ Parakeet 110M     100 MB │ │
│   │ Best quality/size ratio   │ │
│   │ [Download] / [██░░] / [✓]│ │
│   └───────────────────────────┘ │
│   ┌───────────────────────────┐ │
│   │ Whisper Base      199 MB │ │
│   │ ...                       │ │
│   └───────────────────────────┘ │
│   (more models)                 │
├─────────────────────────────────┤
│ ▸ Post-Processing               │
│   [✓] Enable cleanup            │
│   Prompt: [editable text]       │
├─────────────────────────────────┤
│ ▸ Settings                      │
│   API Key: ••••sk-xx [👁] [Save]│
│   Overlay: [Show/Hide]          │
│   About · v1.0 · GitHub · ♥    │
└─────────────────────────────────┘
```

### Model card states

- **Download** — not on device, tap to download
- **Progress bar** — downloading / extracting
- **Select** — downloaded, tap to activate
- **Active ✓** — currently selected model
- Long-press → delete

### Key decisions

- Programmatic views (no XML) — consistent with existing code
- API key shared between cloud transcription and post-processing
- Model cards only shown when Local engine selected
- Setup section auto-collapses when all permissions granted

---

## Chunk 3: Post-Processing (~30 min)

New file: `PostProcessor.kt`

- OpenAI chat completion (gpt-4o-mini or similar)
- Takes raw transcript → returns cleaned text
- Default prompt: "Clean up this speech-to-text transcript. Fix punctuation,
  capitalization, and obvious errors. Keep the original meaning. Return only
  the cleaned text."
- Prompt stored in SharedPreferences, editable in UI
- Toggle on/off

### Wiring

In `WhisperAccessibilityService.kt`, after transcription:
```
record → transcribe → [post-process if enabled] → inject
```

### Tests

- `PostProcessorTest.kt` — request body construction, response parsing

---

## Priority

1. **Chunk 1 + 2** = launchable (model downloads + new UI)
2. **Chunk 3** = nice to have for launch, can ship without

## Files Changed

```
build.gradle.kts                  — add Commons Compress dependency
+ ModelDownloader.kt              — download + extract models
+ ModelDownloaderTest.kt          — extraction tests
  MainActivity.kt                 — full rewrite (sections UI)
+ PostProcessor.kt                — OpenAI chat cleanup
+ PostProcessorTest.kt            — tests
  WhisperAccessibilityService.kt  — wire post-processing, overlay show/hide
```
