# Sepand AI · سپند

A local AI agent for Android, built for the **POCO X3 GT** (Dimensity 1100, 8 GB RAM).
The AI runs **on the phone** (llama.cpp + Spark-X2.6 by default). It can chat, read your files, use your
Obsidian notes as memory, call MCP tools, and control the phone (tap, swipe, scroll, type, open apps, reply to messages).

* Persian + English, right-to-left aware · glass design · 4 themes: dark red, dark green, white red, white green
* CPU / GPU (Vulkan) switch · thinking levels 1–6 (Low → Ultra) · system-prompt editor with presets
* Send text files, PDF, Word/Excel/PowerPoint, zip and pictures (pictures need a vision model such as Qwen3-VL)
* Obsidian memory: it searches your vault on every message and can write notes into a memory folder
* MCP servers (Streamable HTTP and SSE) · built-in web search and page reading
* Phone agent through the Accessibility Service · automatic replies to messages through notification reply buttons

> Everything that touches your phone asks first: sending a message, tapping "Send/Buy/Delete…" and tool calls
> that act for you show an **Allow / Deny** question (floating button + notification + in the app).

---

## 1. Get the APK (GitHub does the building for you)

You do not need a computer program or Android Studio. GitHub Actions builds the app in the cloud.

**On github.com (browser, "Desktop site" is easiest on a phone):**

1. Sign in → **+** (top right) → **New repository** → name it `sepand-ai` → **Create repository**.
2. In the new repo: **Add file → Upload files** → pick **`sepand-ai.zip`** → **Commit changes**.
3. **Add file → Create new file**. For the name type exactly `.github/workflows/build-apk.yml`
   (typing the `/` creates the folders). Paste the contents of the `build-apk.yml` file that came with the zip. **Commit changes**.
4. Open the **Actions** tab. A run called *Build Sepand AI APK* starts by itself (about 15–25 minutes the first time).
5. When it is green, open **Releases** (right side of the repo page) → **Sepand AI (latest build)** → download **SepandAI.apk**.

If you unzip the project on a PC instead, just upload the *contents* of the folder (keep the `.github` folder) and skip steps 2–3.

Every later push builds a new APK; it installs *over* the old one and keeps your data (the signing key is fixed).

## 2. First run (exact clicks)

1. Install the APK (allow **Install unknown apps** for your browser/files app when Android asks).
2. Open **Sepand AI** → **Settings** tab → **Models** → press the download button next to **Spark-X2.6 4B** (best, ~3.5 GB) or **1.7B** (fast, ~1.8 GB).
   If Hugging Face is blocked for you, open **Advanced** and change the base address to a mirror, or download the `.gguf` file another way and use **Pick a file from phone storage**.
3. **Settings → Engine → Start** (the first load takes up to a minute). Then go to **Chat** and write.
4. Tip for 8 GB RAM: keep context **4096** and KV cache **Compact**; if the engine dies, use the 1.7B model or context 2048.

### Phone control (Agent tab)

1. **Accessibility** → *Open*. On Android 13, Android first blocks it ("restricted setting"). Go back, press **App info**,
   tap **⋮ (top right) → Allow restricted settings**, then turn Accessibility on for Sepand AI.
2. **Notification access** → *Open* → turn on Sepand AI (needed for message reading/replies).
3. **No battery limit**, and in MIUI app permissions turn on **Autostart** and **Display pop-up windows while running in the background**.
4. Write a task, e.g. *Open YouTube and search for calm music*, and press **Run**. A floating **Stop** button stays on top while it works.

Speed: on this phone the 4B model needs roughly 20–40 s per step (it reads the screen as text, not as a picture, which is much faster).
The **1.7B** model is about twice as fast and is the better choice for the agent.

### Messages (Agent tab → Auto reply to messages)

Modes: **Off · Draft** (shows a draft notification) **· Ask** (asks before every reply) **· Auto** (sends by itself — use with care).
Pick the apps, optionally limit it to some contacts, set quiet hours and a per-person hourly limit. Groups are off by default.

### Obsidian memory (Memory tab)

**Choose the vault folder** → pick the folder that holds your vault (e.g. `Documents/MyVault`). Sepand indexes the `.md` files on the phone,
looks up the best matching notes on **every message** (and shows which ones it used), and can search/read/write notes with tools.
New memories are written to `SepandAI/Memory` inside the vault (changeable). Use **Memory folder only** if you do not want it to read the rest of the vault.
Obsidian must be able to see the same folder (open the vault from that folder in Obsidian mobile).

### CPU or GPU

The **CPU / GPU** chip in the top bar (or Settings → Engine) switches the processor; restart the engine to apply it.
The GPU path is Vulkan and the phone has a Mali-G77: on similar phones the CPU is usually about twice as fast, so compare both.

### Thinking (6 levels)

Low · Medium · High · Very high · Max · Ultra. Each level gives the model a token budget for thinking
(≈160 / 400 / 1000 / 2000 / 4000 / unlimited) and picks the matching effort the model template understands.
When the budget is used up the engine is told to stop thinking and answer. Ultra can take many minutes on a phone.

### MCP

**Tools → Add a server**: name, address (`https://host/mcp` or an SSE address), optional headers such as `Authorization: Bearer …`.
**Test connection** lists the tools; switch off the ones you do not want. Local *stdio* servers can be offered through Termux with an HTTP bridge.

## 3. If something goes wrong

| Problem | What to do |
|---|---|
| Engine stops by itself / "out of memory" | Use the 1.7B model, context 2048–4096, KV cache Compact, close other apps. |
| It dies when the screen is off | Battery → *No restrictions*; MIUI Autostart on; Developer options → *Disable child process restrictions* (Android 14) or the "phantom process killer" setting. |
| Accessibility switch is grey | App info → ⋮ → **Allow restricted settings**, then try again. |
| Agent cannot open apps from the background | MIUI → app permissions → *Display pop-up windows while running in the background*. |
| GPU button is disabled | This build has no Vulkan engine (the optional GPU build failed in GitHub Actions). CPU mode is fully functional. |
| A picture is ignored | The loaded model is text-only. Download **Qwen3-VL 2B** in Settings → Models, select it and restart the engine. |
| Download is slow/blocked | Settings → Models → Advanced → Hugging Face base address (use a mirror), or pick a file you downloaded. |
| Build fails in GitHub | Open the failed run → the red step → send me the last 40 lines. |

## 4. Project layout

```
app/src/main/java/ai/sepand/app/   Kotlin: engine process, chat, agent, Obsidian, MCP, attachments, bridge
app/src/main/assets/web/           the interface (HTML/CSS/JS, Persian font Vazirmatn)
scripts/build-native.sh            builds llama-server for Android (CPU + Vulkan) with the NDK
.github/workflows/build-apk.yml    builds the engine, then the APK, then publishes it in Releases
```

The AI engine is the official `llama-server` from llama.cpp (tag `v1.0.0`), shipped inside the APK and started
as a private local process (127.0.0.1). The app talks to it over its OpenAI-compatible API.
Check each model's page for its own license.

---

## فارسی (خلاصه)

**سپند** یک عامل هوش مصنوعی محلی برای اندروید است که کاملاً روی خودِ گوشی (POCO X3 GT) اجرا می‌شود.

1. روی github.com یک مخزن به نام `sepand-ai` بسازید؛ فایل `sepand-ai.zip` را با **Add file → Upload files** بارگذاری کنید و فایل
   `build-apk.yml` را با **Add file → Create new file** در مسیر `.github/workflows/build-apk.yml` بسازید.
2. در تب **Actions** منتظر بمانید تا ساخت تمام شود (۱۵ تا ۲۵ دقیقه). بعد از **Releases** فایل `SepandAI.apk` را دانلود و نصب کنید.
3. در برنامه: **تنظیمات ← مدل‌ها** مدل Spark-X2.6 را دانلود کنید، **موتور** را روشن کنید و گفتگو را شروع کنید.
4. برای کنترل گوشی: تب **عامل** ← مجوزها. در اندروید ۱۳ اول باید «اطلاعات برنامه ← ⋮ ← اجازه‌ی تنظیمات محدودشده» را بزنید.
5. برای حافظه: تب **حافظه** ← انتخاب پوشه‌ی Vault برنامه‌ی Obsidian.

تم‌ها (قرمز تیره، سبز تیره، قرمز روشن، سبز روشن)، زبان فارسی/انگلیسی، سوئیچ CPU/GPU، شش سطح تفکر (کم تا اولترا)، تغییر پرامپت سیستم، سرورهای MCP و ارسال فایل و عکس همگی در برنامه هستند.
