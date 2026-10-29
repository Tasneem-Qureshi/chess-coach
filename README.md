# Chess Coach

A personal Android training app that reads the chess board on your screen and coaches you: the best move with the reason behind it, a grade for every move you play, and a full strengths and weaknesses report after each match. Pure-black AMOLED theme.

**[Download the latest APK](https://github.com/Tasneem-Qureshi/chess-coach/releases/latest)** (Android 10+, 64-bit ARM phones).

## What it does

| | |
|---|---|
| **Live coach overlay** | Reads the board from the screen (Chess.com, Lichess or any 2D board). On your turn it shows the engine's top 3 moves as arrows on the board, plus *why* ("Wins a piece", "Stops the threat Nxf7", "Forces mate in 3") and the line that follows. |
| **Think-first mode** | On by default: the answer stays hidden until you tap **Reveal**. **Hint** only circles the piece to move. |
| **Move grading** | After each of your moves: Best / Excellent / Good / Inaccuracy / Mistake / Blunder, and the better move. |
| **Match report** | Accuracy (yours and your opponent's), accuracy by phase, an evaluation graph, key moments, and named strengths and weaknesses (hanging pieces, missed tactics, missed mates, time pressure, letting a win slip…), each with a tip. |
| **Chess.com import** | Enter your username to review any game from the last two months. Pasting a PGN works too. |
| **Train** | Fresh Lichess puzzles every week, your own mistakes turned into puzzles, and three openings to study each week. |
| **Progress** | Trends across all reviewed games: average accuracy, weakest phase, the habits that cost you most, and your openings. |

The engine is **Stockfish** running on the phone, so no internet is needed during a game.

## Fair play

Use the live overlay against bots, in unrated or casual games, or on an analysis board. Engine help in rated games against people breaks Chess.com's and Lichess's fair-play rules, and they detect it and close accounts. Post-game review, puzzles and the progress report are fine everywhere.

## Install

1. Open the [latest release](https://github.com/Tasneem-Qureshi/chess-coach/releases/latest) on your phone and download `ChessCoach-v….apk`.
2. Open it; allow "Install unknown apps" for your browser when asked.
3. In Chess Coach tap **Start coach overlay**, allow **Display over other apps**, then choose **Entire screen** when Android asks what to share.
4. Start a new game once: the coach learns your board and piece style from the starting position (it remembers it).

## Staying up to date (automatic)

- **Every week** (Mondays, with a Thursday backup) a GitHub Action adds new tactics puzzles from Lichess, rotates the opening focus, and checks for a new Stockfish release. The app downloads this content by itself on launch, with no reinstall.
- **New engine or app changes** produce a new signed release. The app sees it and offers **Update** on the Coach tab.
- **Keeping the schedule alive:** GitHub pauses scheduled workflows after 60 days without repository activity. The weekly content commit counts as activity, and the workflow also makes an empty keep-alive commit if 45 days pass with no commits. Only GitHub's own actions are used, so a blocked third-party action can't break the job. If a run fails, an issue labelled `weekly-failed` is opened (GitHub emails you) and it closes itself after the next successful run.

## Project layout

```
app/src/main/java/com/tasneem/chesscoach/
  chess/      move generation, FEN, SAN, PGN (perft-tested)
  engine/     Stockfish over UCI (binary shipped as libstockfish.so)
  vision/     board detection, piece learning, game tracking from screen frames
  capture/    MediaProjection foreground service + live coaching loop
  overlay/    floating coach panel (Compose) and arrow layer
  analysis/   move grading, explanations, match report, cross-game insights
  data/       saved games, Chess.com API, weekly content, self-update
  ui/         AMOLED screens: Coach, Games, Report, Train, Progress, Settings
content/      weekly puzzles / openings (bundled into the APK and served raw)
scripts/      update_content.py (run by the weekly workflow)
```

## Build it yourself

Open the folder in Android Studio (AGP 9.3, Gradle 9.6, JDK 17+) or run `./gradlew assembleDebug`. The first build downloads the official Stockfish Android binary (version in `engine-version.txt`). Unit tests: `./gradlew :app:testDebugUnitTest`.

Release signing uses `keystore.properties` + `release.jks` (never committed). CI reads them from the `KEYSTORE_B64`, `KEYSTORE_PASSWORD` and `KEY_ALIAS` secrets.

## License

GPL-3.0, because the app ships [Stockfish](https://github.com/official-stockfish/Stockfish), which is GPL-3.0. Puzzles come from the [Lichess puzzle database](https://database.lichess.org/#puzzles) (CC0); opening names from [lichess-org/chess-openings](https://github.com/lichess-org/chess-openings) (CC0).
