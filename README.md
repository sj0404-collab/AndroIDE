# AndroIDE 2.1

AI writes the project. You review, correct, Run.

## 2.1

- Chat **sessions** persist (switch / new). Chat is not wiped on tab change.
- **Rounds** `current/max` (Keys → max rounds).
- **Auto-rotate** models on 429 / quota / timeout (Zen free → Pollinations → OpenRouter free → local).
- **Reasoning** shown at the start of a turn (`reasoning_content`, `<think>`).
- **Accounts** isolate keys / GitHub tokens / local URL.
- **Files**: browse projects, move, copy, edit.
- **Plugins**: JSON in app `plugins/` (`example-lint.json`). Agent ` ```plugin name args` `.
- **Templates**: 2D Phaser, 3D Three.js, React CDN, Kotlin stub, Canvas.
- **Art**: ` ```image assets/icon.png | prompt` ` via Pollinations.
- **Web**: ` ```fetch https://…` ` strips HTML and feeds the agent.
- **Local models**: Ollama / llama.cpp / LM Studio URL; download TinyLlama GGUF; `ollama pull`.

## Agent fences

write / delete / read / move / template / image / fetch / github / local pull / plugin

## Run

Open `index.html` in the Run tab (React / Phaser / Three work in WebView if CDN allowed).
