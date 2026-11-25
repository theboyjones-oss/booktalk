# BookTalk

An Android app that opens an ebook, keeps only the story text, and reads it aloud with natural voices whose mood you can shape.

## What it does

- **Opens EPUB, PDF, Kindle MOBI/AZW/AZW3, FB2 (and .fb2.zip), DOCX, HTML and TXT** and keeps only the story text. Page numbers, running headers, footnotes, covers, contents, copyright pages, indexes and similar are removed, and each book's sections can be switched back on.
- **Reads aloud with ready-made narrators.** There are 20 real voices from Kyutai's open voice collection, recreated on the phone by Pocket TTS (via sherpa-onnx). They're free, offline and unlimited after a one-time 98 MB voice pack download.
- **Picking a narrator:** set a default in the Narrators tab, then give each book its own narrator from the book page or the player. You can hear each person's real voice, or hear them reading.
- **Player:** follow-along scrolling, tap a paragraph to jump there, chapter list, speed, sleep timer, lock-screen and headset controls, screen-off reading, and preparing ahead (including a whole chapter).
- **Save as audiobook:** turns the whole book into one .m4a file per chapter in Music/BookTalk/<book>.

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
