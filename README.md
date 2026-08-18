# AndroIDE 2.0 — AI game/app IDE on Android

Visual Blueprint-inspired mobile IDE. **The agent writes the project. You only correct files and press Run.**

## AI providers

| Provider | Key |
|---|---|
| **Zen / OpenCode** (`https://opencode.ai/zen/v1`) | **not required** — verified live (`big-pickle` and `*-free` models) |
| **OpenRouter** | optional; free slugs like `qwen/qwen3-coder:free` |
| **Pollinations** `text.pollinations.ai/openai` | works without key; `pk_`/`sk_` optional |
| **Pollinations gen** | key preferred |
| **Glean / Glens** | `instanceHost\|token` |
| **Groq** | `gsk_…` |
| **Gemini** | `AIza…` |
| **OpenAI-compatible** | `url\|key` or key only |

Keys live in app private prefs. Zen and Pollinations can run with an empty key field.

## GitHub (no stubs)

Uses the real GitHub REST + Git Data API with your PAT (`repo` + `workflow`):

- `GET /user`, `GET /user/repos`
- clone via `git/trees?recursive=1` + file contents
- commit + push via blobs → tree → commit → update ref
- create repo, release, `workflow_dispatch` on `android.yml`

## Agent protocol

The model emits:

````
```write path/file.ext
contents
```
````

Also: `delete`, `read`, `github list|clone|bind|commit|release|workflow`.

## Run

If the agent writes `index.html` (or `game.html`), open the **Run** tab.

## Build

```
./gradlew assembleRelease
```

CI: `.github/workflows/android.yml`

Legacy Eclipse sources are in `legacy/`.
