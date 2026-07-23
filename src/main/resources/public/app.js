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

// Basketball-flavored labels for how lopsided the final (cash-adjusted) score was.
const MARGIN_BANDS = [
    { max: 1,        term: "Buzzer Beater",        blurb: "Decided at the final whistle." },
    { max: 5,        term: "Close Game",           blurb: "Came right down to the wire." },
    { max: 10,       term: "Comfortable Win",      blurb: "Never really in doubt." },
    { max: 20,       term: "Double-Digit Cushion", blurb: "A clear step above." },
    { max: 35,       term: "Blowout",              blurb: "Bench cleared early." },
    { max: Infinity, term: "Ran Out Of The Gym",   blurb: "Absolute demolition." },
];

function showMarginPopup(r) {
    const popup = el("marginPopup");
    if (!popup) return;
    if (r.tie) { popup.classList.add("hidden"); return; }
    const winner = r.scores.find((s) => s.participantId === r.winnerId);
    const loser = r.scores.find((s) => s.participantId !== r.winnerId);
    if (!winner || !loser || winner.total <= 0) { popup.classList.add("hidden"); return; }
    const pct = ((winner.total - loser.total) / winner.total) * 100;
    const band = MARGIN_BANDS.find((b) => pct < b.max) || MARGIN_BANDS[MARGIN_BANDS.length - 1];
    popup.querySelector(".margin-term").textContent = band.term;
    popup.querySelector(".margin-blurb").textContent = band.blurb;
    popup.querySelector(".margin-pct").textContent = pct.toFixed(1) + "% margin";
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

connect();
