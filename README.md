# BidTwenty

A real-time, two-player NBA auction game. Each player gets **$20** to bid on NBA
players revealed one at a time in an **open ascending auction**, grouped by award
category (MVP, All-Star, DPOY, 6th Man, Most Improved). At the end, teams are
scored by a **category-weighted** algorithm and a winner is declared.

- **Backend:** Java 21 + [Javalin](https://javalin.io) (HTTP + WebSockets)
- **Frontend:** static HTML / CSS / vanilla JS
- **Data:** curated category membership + optional **live NBA stats** (balldontlie API) with a graceful curated fallback
- **Sync:** WebSockets — two browsers, one shared room code

## Run locally

```bash
mvn clean package
java -jar target/bidtwenty.jar
# open http://localhost:7070 in two tabs (or share your LAN IP for two devices)
```

Optional live stats: set `BALLDONTLIE_API_KEY` before launching. Without it, the
game uses curated player values (fully offline).

## Deploy — Render free web service (recommended)

The whole app (frontend + WebSocket backend) runs as **one** service, so there is
no cross-origin wiring to do.

1. Push this repo to GitHub.
2. In [Render](https://render.com): **New + → Blueprint**, pick this repo. It reads
   [`render.yaml`](render.yaml) and builds from the [`Dockerfile`](Dockerfile).
   (Or **New + → Web Service → Docker** and accept defaults.)
3. Wait for the first build. Your app is live at `https://<name>.onrender.com`.
4. (Optional) In the service's **Environment** tab, add `BALLDONTLIE_API_KEY` to
   switch on live stats.

> Free tier note: the service sleeps after ~15 min idle and cold-starts in ~30s.
> The loading page below hides that behind a nice animation.

## Deploy — the GitHub Pages loading page (cold-start UX)

Because a sleeping server can't serve its own loading screen, the pretty loader
lives on GitHub Pages (always instant). It wakes the Render backend, animates,
and redirects when it's live.

1. In [`docs/loading.js`](docs/loading.js), set `DEFAULT_BACKEND_URL` to your
   Render URL (e.g. `https://bidtwenty.onrender.com`).
2. Commit and push.
3. GitHub repo **Settings → Pages → Source: Deploy from a branch → `main` / `/docs`**.
4. Share the **GitHub Pages URL** as your game link. It shows the loader, wakes
   the backend, then forwards players into the live game.

You can also test without editing files by passing the backend as a query param:
`https://<you>.github.io/BidTwenty/?backend=https://bidtwenty.onrender.com`

## Project layout

```
pom.xml                       Maven build (fat jar via shade)
Dockerfile                    Multi-stage build for Render/any container host
render.yaml                   Render blueprint (free plan, /health check)
docs/                         GitHub Pages loading page (index.html, loading.css, loading.js)
src/main/resources/players.json   Curated categories + players + fallback values
src/main/resources/public/    Served frontend (index.html, style.css, app.js)
src/main/java/com/bidtwenty/
  Main.java                   Javalin server, static files, /health, /ws
  game/                       Game (auction state machine), GameManager, ScoringEngine
  data/                       PlayerRepository, StatsProvider (live API + fallback)
  model/                      Category, NbaPlayer, Participant, Auction
  web/StateView.java          JSON snapshot broadcast to clients
```

## How the auction works

1. Player 1 **creates** a room → gets a 4-letter code. Player 2 **joins** with it.
2. Host **starts**; a shuffled pool of players is revealed one at a time.
3. For each player, GMs **alternate turns** raising the bid by ≥ $1 or **passing**.
   A pass concedes to the current high bidder; if nobody has bid, two passes leave
   the player **unsold**. A GM who can't afford the minimum is auto-passed.
4. When the pool is exhausted, each team scores `Σ (player value × category weight)`.
   Highest total wins.
