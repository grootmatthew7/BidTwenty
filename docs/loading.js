"use strict";

// =====================================================================
//  CONFIG — after you deploy to Render, put your service URL here.
//  Example: "https://bidtwenty.onrender.com"  (no trailing slash)
//  You can also override at runtime with ?backend=https://... in the URL.
// =====================================================================
const DEFAULT_BACKEND_URL = "https://bidtwenty.onrender.com";

const backendUrl = (new URLSearchParams(location.search).get("backend")
    || DEFAULT_BACKEND_URL).replace(/\/+$/, "");

const statusText = document.getElementById("statusText");
const subText = document.getElementById("subText");
const fill = document.getElementById("fill");
const manualLink = document.getElementById("manualLink");

manualLink.href = backendUrl;

const MESSAGES = [
    "Waking up the arena…",
    "Lacing up the sneakers…",
    "Chalking up the hands…",
    "Warming up the shooters…",
    "Almost tip-off…"
];

let attempts = 0;
let done = false;
const startedAt = Date.now();

// Simulated progress that eases toward ~90% over ~30s, then snaps to 100%.
function tickProgress() {
    if (done) return;
    const elapsed = (Date.now() - startedAt) / 1000;
    const pct = Math.min(90, 6 + 84 * (1 - Math.exp(-elapsed / 12)));
    fill.style.width = pct.toFixed(1) + "%";
    requestAnimationFrame(tickProgress);
}
requestAnimationFrame(tickProgress);

async function pingOnce(timeoutMs) {
    const controller = new AbortController();
    const t = setTimeout(() => controller.abort(), timeoutMs);
    try {
        const res = await fetch(backendUrl + "/health", {
            signal: controller.signal,
            cache: "no-store"
        });
        return res.ok;
    } catch (_) {
        return false;
    } finally {
        clearTimeout(t);
    }
}

function ready() {
    done = true;
    fill.classList.add("ready");
    statusText.textContent = "Tip-off! Redirecting…";
    statusText.classList.add("ready");
    subText.textContent = "";
    setTimeout(() => { location.href = backendUrl; }, 700);
}

async function wake() {
    while (!done) {
        statusText.textContent = MESSAGES[Math.min(attempts, MESSAGES.length - 1)];
        const up = await pingOnce(8000);
        if (up) { ready(); return; }
        attempts++;
        if ((Date.now() - startedAt) > 15000) {
            manualLink.classList.remove("hidden");
        }
        await new Promise((r) => setTimeout(r, 2000));
    }
}

wake();
