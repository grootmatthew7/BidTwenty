"use strict";

// ---- State ----------------------------------------------------------------
let ws = null;
let myId = null;
let myRoom = null;
let latest = null;         // last state snapshot
let resultsAnimated = false; // guard so the tally animation plays only once
const logLines = [];

// ---- Elements -------------------------------------------------------------
const el = (id) => document.getElementById(id);
const statusEl = el("status");

// ---- WebSocket ------------------------------------------------------------
function connect() {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws`);

    ws.onopen = () => {
        setStatus("Connected. Create or join a game.", "ok");
        show("lobby");
    };
    ws.onclose = () => setStatus("Disconnected. Refresh to reconnect.", "error");
    ws.onerror = () => setStatus("Connection error.", "error");
    ws.onmessage = (ev) => {
        const msg = JSON.parse(ev.data);
        if (msg.type === "joined") onJoined(msg);
        else if (msg.type === "state") onState(msg);
        else if (msg.type === "error") setStatus(msg.message, "error");
    };
}

function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

// ---- Message handlers -----------------------------------------------------
function onJoined(msg) {
    myId = msg.participantId;
    myRoom = msg.room;
    el("roomCode").textContent = msg.room;
    setStatus("You're in room " + msg.room, "ok");
}

function onState(state) {
    latest = state;
    updateLog(state.lastAction);
    renderLiveBadge(state.liveStats);

    if (state.phase === "LOBBY") {
        renderWaiting(state);
        show("waiting");
    } else if (state.phase === "AUCTION") {
        renderGame(state);
        show("game");
    } else if (state.phase === "FINISHED") {
        renderResults(state);
        show("results");
    }
}

// ---- Rendering ------------------------------------------------------------
function renderWaiting(state) {
    el("roomCode").textContent = state.room;
    const list = el("waitingPlayers");
    list.innerHTML = "";
    state.participants.forEach((p) => {
        const li = document.createElement("li");
        li.innerHTML = `<span>${escape(p.name)}${p.connected ? "" : " (disconnected)"}</span>` +
            `<span class="badge-host">${p.isHost ? "HOST" : ""}</span>`;
        list.appendChild(li);
    });

    const amHost = state.hostId === myId;
    const full = state.participants.length >= 2;
    const startBtn = el("startBtn");
    startBtn.classList.toggle("hidden", !amHost);
    startBtn.disabled = !full;
    el("waitingHint").textContent = full
        ? (amHost ? "Both players in — start when ready." : "Waiting for host to start…")
        : "Waiting for a second player to join…";
}

function renderGame(state) {
    const me = state.participants.find((p) => p.id === myId);
    const them = state.participants.find((p) => p.id !== myId);
    const a = state.auction;
    const ac = state.autoClaim;
    const myTurn = a && a.turnId === myId;

    const rosterSize = state.rosterSize || 5;
    renderTeam(el("me"), me, "me", a, rosterSize);
    renderTeam(el("them"), them, "them", a, rosterSize);

    const catLabel = state.category ? state.category.label : "";
    el("progress").innerHTML =
        `<span class="cat-banner">${escape(catLabel)}</span> auction — ` +
        `${escape(me.name)} ${me.roster.length}/${rosterSize} · ` +
        `${escape(them ? them.name : "opponent")} ${them ? them.roster.length : 0}/${rosterSize}`;

    // Auto-fill takes over once one squad is set: no bidding, just watch the
    // trailing squad fill out one player at a time.
    if (!a && ac) {
        renderAutoClaim(state, me, them, ac);
        return;
    }
    if (!a) return; // transient (shouldn't happen); nothing to draw

    // Player up for bid
    const pc = el("playerCard");
    const p = a.player;
    const src = p.valueSource === "live" ? "live NBA stats" : "curated";
    pc.innerHTML =
        `<div class="pc-name">${escape(p.name)}</div>` +
        `<div class="pc-team">${escape(p.team)}</div>` +
        `<div class="pc-cat">${escape(p.categoryLabel)}</div>` +
        `<div class="pc-value">Value <b>${p.value}</b> <span>(${src})</span></div>`;

    // Bid state
    const bidState = el("bidState");
    if (a.highBidderId) {
        const leader = state.participants.find((pp) => pp.id === a.highBidderId);
        const who = leader && leader.id === myId ? "You lead" : `${escape(leader ? leader.name : "?")} leads`;
        bidState.innerHTML = `${who} at <span class="amt">$${a.currentBid}</span>`;
    } else {
        bidState.innerHTML = `No bids yet — opening bid <span class="amt">$0</span>`;
    }

    // Turn banner + controls
    const banner = el("turnBanner");
    const bidBtn = el("bidBtn");
    const passBtn = el("passBtn");
    const amt = el("bidAmount");
    const minBid = a.minBid;
    // Bids open at $0, so a GM can always claim a player they can afford the
    // minimum for — the only limit is your remaining budget.
    const openSpots = rosterSize - me.roster.length;
    const myMaxBid = me.budget;
    const canAfford = openSpots > 0 && myMaxBid >= minBid;

    if (myTurn) {
        banner.textContent = canAfford
            ? `Your turn — raise or pass (up to $${myMaxBid})`
            : "Your turn — you're priced out, you can only pass";
        banner.className = "turn-banner your-turn";
    } else {
        banner.textContent = `Waiting for ${escape(them ? them.name : "opponent")}…`;
        banner.className = "turn-banner their-turn";
    }

    bidBtn.disabled = !myTurn || !canAfford;
    passBtn.disabled = !myTurn;
    amt.disabled = !myTurn || !canAfford;
    amt.min = String(minBid);
    amt.max = String(Math.max(minBid, myMaxBid));
    if (document.activeElement !== amt) {
        amt.value = canAfford ? String(minBid) : "";
    }
    passBtn.textContent = a.currentBid > 0
        ? (myTurn && a.highBidderId !== myId ? "Pass (concede)" : "Pass")
        : "Pass";
}

// One squad is already set; show the uncontested player being auto-added to the
// other squad, with bidding controls disabled.
function renderAutoClaim(state, me, them, ac) {
    const p = ac.player;
    const target = state.participants.find((pp) => pp.id === ac.targetId);
    const targetName = target ? (target.id === myId ? "your" : escape(target.name) + "'s") : "the";
    const src = p.valueSource === "live" ? "live NBA stats" : "curated";

    el("playerCard").innerHTML =
        `<div class="pc-name">${escape(p.name)}</div>` +
        `<div class="pc-team">${escape(p.team)}</div>` +
        `<div class="pc-cat">${escape(p.categoryLabel)}</div>` +
        `<div class="pc-value">Value <b>${p.value}</b> <span>(${src})</span></div>`;

    el("bidState").innerHTML = `Auto-filling ${targetName} squad — no bidding`;

    const banner = el("turnBanner");
    banner.textContent = `${escape(p.name)} joining ${targetName} squad…`;
    banner.className = "turn-banner their-turn";

    el("bidBtn").disabled = true;
    el("passBtn").disabled = true;
    el("bidAmount").disabled = true;
    el("bidAmount").value = "";
}

function renderTeam(container, p, side, a, rosterSize) {
    if (!p) { container.innerHTML = ""; return; }
    const size = rosterSize || 5;
    const isTurn = a && a.turnId === p.id;
    const full = p.roster.length >= size;
    container.className = `team ${side}` + (isTurn ? " turn" : "") + (full ? " full" : "");
    const filled = p.roster.map((np) =>
        `<li><span>${escape(np.name)}</span><span class="cat">${escape(np.team)} · ${np.value}</span></li>`
    ).join("");
    let empties = "";
    for (let i = p.roster.length; i < size; i++) {
        empties += `<li class="empty"><span>Empty slot</span><span class="cat">—</span></li>`;
    }
    container.innerHTML =
        `<div class="team-head"><span class="team-name">${escape(p.name)}${p.id === myId ? " (you)" : ""}` +
        `${full ? ' <span class="badge-full">FULL</span>' : ""}</span>` +
        `<span class="budget">$${p.budget} · ${p.roster.length}/${size}</span></div>` +
        `<ul class="roster">${filled}${empties}</ul>`;
}

function renderResults(state) {
    const r = state.result;

    // Build both empty team panels (running total starts at 0).
    const box = el("resultScores");
    box.innerHTML = "";
    const panels = r.scores.map((s) => {
        const div = document.createElement("div");
        div.className = "result-team";
        div.dataset.pid = s.participantId;
        div.innerHTML =
            `<h3>${escape(s.name)}${s.participantId === myId ? " (you)" : ""}</h3>` +
            `<div class="total">0 pts</div><table></table>`;
        box.appendChild(div);
        return div;
    });

    if (resultsAnimated) {
        // Already played once (e.g. a later state re-render): fill instantly.
        finalizeResults(r, panels);
        return;
    }
    resultsAnimated = true;
    el("resultHeadline").textContent = "Tallying the squads…";

    // Interleave selections from first pick to last: p1[0], p2[0], p1[1], ...
    const steps = [];
    const maxLen = Math.max(0, ...r.scores.map((s) => s.breakdown.length));
    for (let i = 0; i < maxLen; i++) {
        r.scores.forEach((s, ti) => {
            if (i < s.breakdown.length) steps.push({ ti, item: s.breakdown[i] });
        });
    }

    const totals = r.scores.map(() => 0);
    let k = 0;
    const STEP_MS = 650;
    function tick() {
        if (k >= steps.length) { applyBonusThenReveal(r, panels); return; }
        const { ti, item } = steps[k++];
        totals[ti] = Math.round((totals[ti] + item.points) * 10) / 10;
        const panel = panels[ti];
        panel.querySelector(".total").textContent = totals[ti] + " pts";
        panel.querySelector("table").appendChild(scoreRow(item));
        setTimeout(tick, STEP_MS);
    }
    tick();
}

// After the straight-sum tally, reveal the frugality bonus: the GM who banked
// the most cash gets their total multiplied by 1.xx. Shown as a final row that
// bumps the running total up to the adjusted score, then the winner + margin.
function applyBonusThenReveal(r, panels) {
    const bonused = r.scores
        .map((s, ti) => ({ s, ti }))
        .filter(({ s }) => s.bonusMultiplier && s.bonusMultiplier > 1);
    if (bonused.length === 0) {
        revealWinner(r);
        showMarginPopup(r);
        return;
    }
    const PAUSE_MS = 900;
    setTimeout(() => {
        bonused.forEach(({ s, ti }) => {
            const panel = panels[ti];
            panel.querySelector("table").appendChild(bonusRow(s));
            panel.querySelector(".total").textContent = s.total + " pts";
            panel.classList.add("bonus-pop");
        });
        setTimeout(() => { revealWinner(r); showMarginPopup(r); }, PAUSE_MS);
    }, PAUSE_MS);
}

function bonusRow(s) {
    const tr = document.createElement("tr");
    tr.className = "bonus-line";
    tr.innerHTML =
        `<td>💰 Cash bonus ($${s.cashLeft} banked)</td>` +
        `<td class="num"></td>` +
        `<td class="num">×${s.bonusMultiplier.toFixed(2)}</td>`;
    return tr;
}

// How lopsided the final (cash-adjusted) score was, by margin band. Each band
// holds a pool of relative NBA phrases; one is picked at random per game so the
// same margin can read differently each time.
//   0–2%  buzzer-beater · 2.01–6% nail-biter · 6.01–10% confident · 10.01%+ blowout
const MARGIN_BANDS = [
    {
        max: 2,
        terms: ["Buzzer-Beater", "Cardiac Finish", "Overtime Thriller", "Ice in the Veins", "Instant Classic", "Photo Finish"],
        blurbs: ["Decided at the final horn.", "Heart-stopping to the last possession.", "Neither side blinked.", "One shot settled it."],
    },
    {
        max: 6,
        terms: ["Nail-Biter", "Four-Quarter Battle", "Closing-Time Edge", "Grind-It-Out Win", "Clutch-Time Separation", "Hard-Fought W"],
        blurbs: ["Traded haymakers to the buzzer.", "Pulled away in crunch time.", "A war of attrition.", "Earned every bit of it."],
    },
    {
        max: 10,
        terms: ["Confident Win", "Wire-to-Wire Control", "Comfortable Cruise", "Statement Stretch", "Never-in-Doubt", "Handled Business"],
        blurbs: ["In control down the stretch.", "Answered every run.", "Rarely felt threatened.", "Closed it out with ease."],
    },
    {
        max: Infinity,
        terms: ["Blowout", "Ran Out of the Gym", "Bench-Clearing Rout", "Wire-to-Wire Beatdown", "Garbage-Time Special", "Statement Demolition"],
        blurbs: ["Starters got the night off early.", "Not remotely close.", "A total mismatch.", "Cleared the bench in the third."],
    },
];

function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function showMarginPopup(r) {
    const popup = el("marginPopup");
    if (!popup) return;
    if (r.tie) { popup.classList.add("hidden"); return; }
    const winner = r.scores.find((s) => s.participantId === r.winnerId);
    const loser = r.scores.find((s) => s.participantId !== r.winnerId);
    if (!winner || !loser || winner.total <= 0) { popup.classList.add("hidden"); return; }
    const pct = ((winner.total - loser.total) / winner.total) * 100;
    const band = MARGIN_BANDS.find((b) => pct <= b.max) || MARGIN_BANDS[MARGIN_BANDS.length - 1];
    popup.querySelector(".margin-term").textContent = pick(band.terms);
    popup.querySelector(".margin-blurb").textContent = pick(band.blurbs);
    popup.querySelector(".margin-pct").textContent = pct.toFixed(1) + "% margin";
    const m = buildScoreModel(r);
    popup.querySelector(".margin-score").innerHTML = m.board.length === 2 ? scorelineHTML(m) : "";
    popup.classList.remove("hidden");
}

function scoreRow(item) {
    const tr = document.createElement("tr");
    tr.innerHTML =
        `<td>${escape(item.player)}</td>` +
        `<td class="num">${escape(item.team)}</td>` +
        `<td class="num">${item.points}</td>`;
    return tr;
}

function revealWinner(r) {
    const headline = el("resultHeadline");
    if (r.tie) {
        headline.textContent = "It's a tie!";
    } else {
        const won = r.winnerId === myId;
        const winner = r.scores.find((s) => s.participantId === r.winnerId);
        headline.textContent = won ? "You win! 🏆" : `${winner ? winner.name : "Opponent"} wins`;
    }
    document.querySelectorAll("#resultScores .result-team").forEach((d) => {
        d.classList.toggle("winner", d.dataset.pid === r.winnerId);
    });
    renderScoreline(r);
}

// Modern NBA teams average roughly this many points a night; we rescale both
// final totals so their sum lands here, turning the raw point tally into a
// believable box-score final (e.g. 118–109) while preserving the real margin.
// Each overtime period is ~5 minutes, worth roughly this many extra points/team.
const MODERN_NBA_AVG = 114;
const OT_POINTS_PER_TEAM = 12;

// Built once per game (cached) so the scoreline and the margin popup agree on the
// same overtime designation and box-score numbers even across re-renders.
let scoreModel = null;

function buildScoreModel(r) {
    if (scoreModel) return scoreModel;
    if (r.scores.length !== 2) {
        scoreModel = { board: [], otTag: null, pct: 0, tie: r.tie };
        return scoreModel;
    }
    const sorted = [...r.scores].sort((a, b) => (b.total || 0) - (a.total || 0));
    const sum = (sorted[0].total || 0) + (sorted[1].total || 0);
    const pct = sorted[0].total > 0
        ? ((sorted[0].total - sorted[1].total) / sorted[0].total) * 100
        : 0;

    // A dead heat (<=2% and not an exact tie) is decided in overtime. Pick how
    // many extra periods it took, which also inflates the box score below.
    let otTag = null;
    let periods = 0;
    if (!r.tie && pct <= 2 && sum > 0) {
        periods = 1 + Math.floor(Math.random() * 3);      // 1..3
        otTag = "OT" + (periods > 1 ? periods : "");
    }

    let board;
    if (sum <= 0) {
        board = sorted.map((s) => ({ name: s.name, score: 0 }));
    } else {
        const perTeam = MODERN_NBA_AVG + OT_POINTS_PER_TEAM * periods;
        const scale = (2 * perTeam) / sum;
        board = sorted.map((s) => ({ name: s.name, score: Math.round((s.total || 0) * scale) }));
        // A decided game can't end level on the scoreboard — nudge the winner up
        // so the two figures always differ by at least one.
        if (!r.tie && board[0].score <= board[1].score) {
            board[0].score = board[1].score + 1;
        }
    }
    scoreModel = { board, otTag, pct, tie: r.tie };
    return scoreModel;
}

function scorelineHTML(m) {
    const label = m.otTag ? "Final · " + m.otTag : "Final";
    return `<span class="sl-label">${label}</span> ` +
        `${escape(m.board[0].name)} <b>${m.board[0].score}</b>` +
        `<span class="sl-dash">–</span>` +
        `<b>${m.board[1].score}</b> ${escape(m.board[1].name)}`;
}

function renderScoreline(r) {
    const line = el("resultScoreline");
    if (!line) return;
    const m = buildScoreModel(r);
    if (m.board.length !== 2 || (m.board[0].score === 0 && m.board[1].score === 0)) {
        line.textContent = "";
        return;
    }
    line.innerHTML = scorelineHTML(m);
}

// Fill both panels instantly (used when results are re-rendered after the
// one-time tally animation has already played).
function finalizeResults(r, panels) {
    r.scores.forEach((s, ti) => {
        const panel = panels[ti];
        panel.querySelector(".total").textContent = s.total + " pts";
        const table = panel.querySelector("table");
        table.innerHTML = "";
        s.breakdown.forEach((item) => table.appendChild(scoreRow(item)));
        if (s.breakdown.length === 0) {
            table.innerHTML = "<tr><td>No players won</td></tr>";
        } else if (s.bonusMultiplier && s.bonusMultiplier > 1) {
            table.appendChild(bonusRow(s));
        }
    });
    revealWinner(r);
}

function renderLiveBadge(live) {
    el("liveBadge").innerHTML = live
        ? 'NBA data: <span class="on">live stats</span>'
        : 'NBA data: <span class="off">curated values</span>';
}

// ---- Helpers --------------------------------------------------------------
function updateLog(line) {
    if (!line) return;
    if (logLines[0] === line) return;
    logLines.unshift(line);
    if (logLines.length > 40) logLines.pop();
    el("log").innerHTML = logLines.map((l) => `<div>${escape(l)}</div>`).join("");
}

function setStatus(text, kind) {
    statusEl.textContent = text;
    statusEl.className = "status" + (kind ? " " + kind : "");
}

function show(sectionId) {
    ["lobby", "waiting", "game", "results"].forEach((id) => {
        el(id).classList.toggle("hidden", id !== sectionId);
    });
}

function escape(s) {
    return String(s == null ? "" : s)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// ---- Wiring ---------------------------------------------------------------
el("createBtn").onclick = () => {
    send({ type: "create", name: el("createName").value });
};
el("joinBtn").onclick = () => {
    const code = el("joinCode").value.trim().toUpperCase();
    if (!code) { setStatus("Enter a room code to join.", "error"); return; }
    send({ type: "join", name: el("joinName").value, room: code });
};
el("startBtn").onclick = () => send({ type: "start" });
el("bidBtn").onclick = () => {
    const amount = parseInt(el("bidAmount").value, 10);
    if (Number.isNaN(amount)) { setStatus("Enter a bid amount.", "error"); return; }
    send({ type: "bid", amount });
};
el("passBtn").onclick = () => send({ type: "pass" });
el("bidAmount").addEventListener("keydown", (e) => {
    if (e.key === "Enter") el("bidBtn").click();
});
el("playAgainBtn").onclick = () => location.reload();
el("marginDismiss").onclick = () => el("marginPopup").classList.add("hidden");

// ---- Theme toggle (light "Hardwood" / dark "Blacktop") --------------------
function currentTheme() {
    return document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
}
function applyTheme(theme) {
    document.documentElement.setAttribute("data-theme", theme);
    const btn = el("themeToggle");
    if (btn) {
        btn.textContent = theme === "light" ? "☀️" : "🌙";
        btn.title = theme === "light" ? "Switch to dark mode" : "Switch to light mode";
    }
}
el("themeToggle").onclick = () => {
    const next = currentTheme() === "light" ? "dark" : "light";
    try { localStorage.setItem("bidtwenty-theme", next); } catch (e) { /* private mode */ }
    applyTheme(next);
};
applyTheme(currentTheme());

connect();
