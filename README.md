# BookTalk

An Android app that opens an ebook, keeps only the story text, and reads it aloud with natural voices whose mood you can shape.

## What it does

- **Opens EPUB, PDF, Kindle MOBI/AZW/AZW3, FB2 (and .fb2.zip), DOCX, HTML and TXT.** DRM-protected books can't be read.
- **Reads only the book itself.** Page numbers, running headers and footers, footnote markers and footnotes, image captions, tables, covers, contents pages, copyright pages, acknowledgements, indexes, "also by" pages and Project Gutenberg licence text are removed. Each book's page lists its sections, so you can switch any of them back on.
- **Analyses the book.** With a Gemini key it picks a reading mood and writes a narrator's note (for example, "Victorian gothic; give Dracula an Eastern European accent"). Offline, a keyword analysis picks the mood instead.
- **Two voice engines:**
  - **Gemini TTS** gives natural voices, and each mood describes the delivery in plain English ("slowly, in a quiet, somber tone…"). There are 30 voices to choose from.
  - **Android's built-in TTS** works offline and is used automatically if Gemini fails, hits a rate limit or you lose signal.
- **Editable moods.** Seven come built in: Storyteller, Serious, Funny, Somber, Spooky, Thriller and Bedtime. You can edit them, duplicate them or make your own, with Gemini and built-in previews.
- **Player:** follow-along text highlighting, tap a paragraph to jump there, chapter list, speed control (0.75–2×), sleep timer (including end of chapter), lock-screen and headset controls, and reading with the screen off.

## Project layout

```
app/src/main/assets/index.html     the whole app UI and engine (single file)
app/src/main/assets/jszip.min.js   EPUB/DOCX unzip
app/src/main/assets/pdf*.js        PDF.js text extraction
app/src/main/java/com/booktalk/app/
    MainActivity.java     WebView host + JS bridge
    Speaker.java          built-in TTS + Gemini PCM playback (AudioTrack), pauses, audio focus
    PlaybackService.java  foreground media service, notification, media buttons, wake locks
.github/workflows/build-apk.yml    builds and publishes a signed APK on every push to main
```

## Building the APK (GitHub Actions)

Every push to `main` builds `BookTalk.apk` and attaches it to a new Release. The app is signed with `app/booktalk-release.jks`, a sideload key that uses Android's standard public debug password (`android`). Keep this keystore so new builds install over old ones.

## Trying it on a computer

Serve `app/src/main/assets` (for example with `python3 -m http.server -d app/src/main/assets`) and open it in Chrome. The browser uses Web Speech for the built-in voice and Web Audio for Gemini.

## Gemini notes

- Get a key at https://aistudio.google.com/apikey and paste it into Settings. It is stored only on the phone.
- The default speech model is `gemini-3.1-flash-tts-preview`. You can switch to 2.5 Flash or 2.5 Pro TTS, or type another model ID.
- Text is sent in passages of about 650 characters, with a few prepared ahead so playback has no gaps. A whole novel is several hundred requests, so free-tier daily limits will run out partway through. When that happens BookTalk falls back to the built-in voice and retries Gemini later.
