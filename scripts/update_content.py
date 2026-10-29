#!/usr/bin/env python3
"""Weekly content refresh for Chess Coach.

Writes into content/:
  puzzles.json   fresh Lichess puzzles (this week's theme + a mix), newest first, capped
  openings.json  every named opening line (lichess-org/chess-openings) for opening detection
  manifest.json  version, ISO week, theme, opening focus, engine tag
and bumps engine-version.txt when a newer Stockfish release exists.

Only uses public, unauthenticated endpoints and stays well inside their rate limits.
"""
import argparse
import datetime as dt
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request

import chess
import chess.pgn

UA = "ChessCoach weekly content (github.com/Tasneem-Qureshi/chess-coach)"
THEMES = [
    "fork", "pin", "skewer", "discoveredAttack", "hangingPiece", "mateIn2", "backRankMate", "sacrifice",
    "deflection", "attraction", "trappedPiece", "endgame", "rookEndgame", "advancedPawn", "defensiveMove",
    "quietMove", "kingsideAttack", "doubleCheck", "intermezzo", "xRayAttack", "clearance", "mateIn3",
    "pawnEndgame", "exposedKing",
]
FOCUS_OPENINGS = [
    "Italian Game: Giuoco Piano", "Ruy Lopez: Closed", "Sicilian Defense: Najdorf Variation",
    "Queen's Gambit Declined", "London System", "Caro-Kann Defense: Classical Variation",
    "French Defense: Winawer Variation", "King's Indian Defense: Orthodox Variation", "Scandinavian Defense: Main Line",
    "English Opening: King's English Variation", "Slav Defense", "Nimzo-Indian Defense", "Scotch Game",
    "Vienna Game", "Pirc Defense", "Dutch Defense", "Sicilian Defense: Dragon Variation", "Petrov's Defense",
    "Queen's Gambit Accepted", "Grünfeld Defense", "Catalan Opening", "Alekhine Defense", "Italian Game: Two Knights Defense",
    "Sicilian Defense: Alapin Variation", "Philidor Defense", "King's Gambit Accepted", "Bishop's Opening", "Four Knights Game",
]
MAX_PUZZLES = 160


def get(url, accept="application/json", tries=4):
    for attempt in range(tries):
        headers = {"User-Agent": UA, "Accept": accept}
        if url.startswith("https://api.github.com/") and os.environ.get("GITHUB_TOKEN"):
            headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            if e.code == 429:
                print("rate limited, waiting 65 s", file=sys.stderr)
                time.sleep(65)
                continue
            if attempt == tries - 1:
                raise
        except urllib.error.URLError:
            if attempt == tries - 1:
                raise
        time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"giving up on {url}")


def puzzle_from(raw):
    """Lichess /api/puzzle/next → {id, fen, moves, rating, themes}; the FEN is the position after the game PGN."""
    p = raw["puzzle"]
    game = chess.pgn.read_game(io.StringIO(raw["game"]["pgn"]))
    board = game.board()
    for mv in game.mainline_moves():
        board.push(mv)
    probe = board.copy()
    for uci in p["solution"]:
        mv = chess.Move.from_uci(uci)
        if mv not in probe.legal_moves:
            return None
        probe.push(mv)
    return {
        "id": p["id"], "fen": board.fen(), "moves": p["solution"], "rating": p.get("rating", 0),
        "themes": [t for t in p.get("themes", []) if t not in ("short", "long", "veryLong", "oneMove", "master", "crushing", "advantage")],
    }


def fetch_puzzles(theme, count, seen):
    out = []
    misses = 0
    while len(out) < count and misses < count * 2:
        try:
            raw = json.loads(get(f"https://lichess.org/api/puzzle/next?angle={theme}"))
            pz = puzzle_from(raw)
        except Exception as e:  # noqa: BLE001 — one bad puzzle must not stop the run
            print(f"  skip ({theme}): {e}", file=sys.stderr)
            pz = None
        if pz and pz["id"] not in seen:
            seen.add(pz["id"])
            out.append(pz)
        else:
            misses += 1
        time.sleep(1.2)
    return out


def load_openings():
    rows = []
    for part in "abcde":
        text = get(f"https://raw.githubusercontent.com/lichess-org/chess-openings/master/{part}.tsv", accept="text/plain")
        for line in text.splitlines()[1:]:
            cols = line.split("\t")
            if len(cols) < 3:
                continue
            eco, name, pgn = cols[0], cols[1], cols[2]
            board = chess.Board()
            sans = []
            for tok in pgn.split():
                if tok.endswith(".") or tok[0].isdigit() and "." in tok:
                    continue
                mv = board.parse_san(tok)
                sans.append(board.san(mv).rstrip("+#"))
                board.push(mv)
            rows.append({"eco": eco, "name": name, "moves": " ".join(sans)})
    return rows


def pick_focus(openings, week_no):
    by_name = {}
    for o in openings:
        by_name.setdefault(o["name"], []).append(o)
    picks = []
    start = (week_no * 3) % len(FOCUS_OPENINGS)
    for i in range(len(FOCUS_OPENINGS)):
        name = FOCUS_OPENINGS[(start + i) % len(FOCUS_OPENINGS)]
        cands = by_name.get(name) or [o for o in openings if o["name"].startswith(name)]
        cands = [o for o in cands if len(o["moves"].split()) >= 6] or cands
        if cands:
            picks.append(min(cands, key=lambda o: len(o["moves"].split())))
        if len(picks) == 3:
            break
    return picks


def latest_stockfish():
    data = json.loads(get("https://api.github.com/repos/official-stockfish/Stockfish/releases/latest", "application/vnd.github+json"))
    tag = data["tag_name"]
    has_android = any(a["name"] == "stockfish-android-arm64-universal.tar.gz" for a in data.get("assets", []))
    return tag if has_android else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="content")
    ap.add_argument("--root", default=".")
    ap.add_argument("--theme-count", type=int, default=24)
    ap.add_argument("--mix-themes", type=int, default=8)
    ap.add_argument("--per-mix", type=int, default=3)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    now = dt.datetime.now(dt.timezone.utc)
    year, week_no, _ = now.isocalendar()
    week = f"{year}-W{week_no:02d}"
    theme = THEMES[week_no % len(THEMES)]

    old_path = os.path.join(args.out, "puzzles.json")
    old = json.load(open(old_path))["puzzles"] if os.path.exists(old_path) else []
    seen = {p["id"] for p in old}

    print(f"week {week}: theme {theme}")
    fresh = fetch_puzzles(theme, args.theme_count, seen)
    others = [t for t in THEMES if t != theme]
    for i in range(args.mix_themes):
        t = others[(week_no + i * 3) % len(others)]
        fresh += fetch_puzzles(t, args.per_mix, seen)
    if len(fresh) < 10:
        sys.exit(f"only {len(fresh)} puzzles fetched — refusing to publish a thin week")
    puzzles = (fresh + old)[:MAX_PUZZLES]
    json.dump({"week": week, "puzzles": puzzles}, open(old_path, "w"), separators=(",", ":"))
    print(f"puzzles: {len(fresh)} new, {len(puzzles)} total")

    openings = load_openings()
    json.dump(openings, open(os.path.join(args.out, "openings.json"), "w"), separators=(",", ":"), ensure_ascii=False)
    print(f"openings: {len(openings)}")

    engine_file = os.path.join(args.root, "engine-version.txt")
    engine = open(engine_file).read().strip()
    try:
        latest = latest_stockfish()
        if latest and latest != engine:
            print(f"new Stockfish release: {engine} -> {latest}")
            engine = latest
            open(engine_file, "w").write(engine + "\n")
    except Exception as e:  # noqa: BLE001
        print(f"stockfish check skipped: {e}", file=sys.stderr)

    manifest = {
        "version": int(now.strftime("%Y%m%d%H")),
        "week": week,
        "updated": now.strftime("%Y-%m-%d"),
        "theme": theme,
        "engine": engine,
        "focus": pick_focus(openings, week_no),
    }
    json.dump(manifest, open(os.path.join(args.out, "manifest.json"), "w"), indent=1, ensure_ascii=False)
    print(json.dumps({k: v for k, v in manifest.items() if k != "focus"}))


if __name__ == "__main__":
    main()
