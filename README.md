# Lil One

A clean PDF study app for Android phones and tablets: read, highlight, draw and take notes on PDFs.
Annotations are saved inside the PDF as standard annotations, so they show up in any PDF app.

## Features
- Subject folders, search, recent files and a "continue reading" card
- Highlight, pen, text notes, undo; Day / Sepia / Night reading modes
- Import PDFs, photos/scans (become a PDF) and Word / PowerPoint / EPUB files
- Opening a file from another app (Files, WhatsApp...) shows a back button to that app
- Quick-scroll slider, page jump, bookmarks, contents and a highlights list
- Study assistant (Google Gemini): explain a selection, summarise, quizzes, flashcards, chat.
  Uses your own free Gemini API key (https://aistudio.google.com/apikey). Model: Auto, Fast or your choice.

## Build
Requirements: JDK 17+ and the Android SDK (compileSdk 36).

```bash
./gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/`. Without a `keystore.properties` file the release build
is unsigned; sign it with your own key (or use `./gradlew assembleDebug` to try it out).

## Licence
Lil One is free software: you can redistribute it and/or modify it under the terms of the
**GNU Affero General Public License v3.0** (see [LICENSE](LICENSE)). It comes with no warranty.

It uses [MuPDF](https://mupdf.com) by Artifex Software, also licensed under the AGPL v3.

The name "Lil One" and its logo are not covered by the licence: if you distribute a modified version,
please give it a different name and icon.

## Code map
- `pdf/PdfSession.kt`: MuPDF wrapper (render, annotations, undo, save, search, outline)
- `data/Library.kt`, `data/Importer.kt`: library storage, import + Word/PowerPoint/photo to PDF conversion
- `data/Gemini.kt`, `ui/reader/AiAssistant.kt`, `ui/reader/AiPanel.kt`: study assistant
- `ui/library/`: library screen
- `ui/reader/`: reader, gestures (`PagesView.kt`), edit toolbar, side panel, quick scroll
