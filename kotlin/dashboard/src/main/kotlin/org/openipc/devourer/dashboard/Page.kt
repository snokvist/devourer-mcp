package org.openipc.devourer.dashboard

/**
 * The dashboard document.
 *
 * A compile-time constant with no interpolation of any kind. Every dynamic
 * value arrives as JSON and is written with `textContent` or as an element
 * attribute set through the DOM API — there is no string-to-HTML path in this
 * file or anywhere it is served from, so the escaping bug class has nowhere
 * to live.
 *
 * Note for anyone editing: this is a Kotlin raw string, so a bare dollar sign
 * starts a template. The script below therefore uses string concatenation and
 * never a JS template literal. That mistake has been made twice in this
 * repository and both times it shipped literal Kotlin syntax into the output.
 */
internal const val PAGE: String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>devourer instrument</title>
<style>
  :root {
    --bg: #0f1113; --panel: #16191d; --line: #262b31; --ink: #dfe3e8;
    --dim: #8b949e; --accent: #6ea8fe; --ok: #56d364; --warn: #e3b341;
    --bad: #f85149; --mono: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--ink);
    font: 13px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
  }
  header {
    display: flex; align-items: baseline; gap: 16px; flex-wrap: wrap;
    padding: 12px 16px; border-bottom: 1px solid var(--line); background: var(--panel);
    position: sticky; top: 0; z-index: 2;
  }
  h1 { font-size: 15px; margin: 0; font-weight: 600; letter-spacing: .02em; }
  .meta { color: var(--dim); font-family: var(--mono); font-size: 11px; }
  .dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
  .dot.live { background: var(--ok); } .dot.dead { background: var(--bad); }
  main { display: grid; grid-template-columns: minmax(0,3fr) minmax(0,2fr); gap: 14px; padding: 14px; }
  @media (max-width: 900px) { main { grid-template-columns: 1fr; } }
  section { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; margin-bottom: 14px; }
  section > h2 {
    font-size: 11px; text-transform: uppercase; letter-spacing: .09em; color: var(--dim);
    margin: 0; padding: 9px 12px; border-bottom: 1px solid var(--line); font-weight: 600;
  }
  .body { padding: 4px 12px 10px; }
  .empty { color: var(--dim); padding: 10px 0; font-style: italic; }
  table { width: 100%; border-collapse: collapse; font-size: 12px; }
  th { text-align: left; color: var(--dim); font-weight: 500; padding: 6px 8px 6px 0; white-space: nowrap; }
  td { padding: 5px 8px 5px 0; border-top: 1px solid var(--line); vertical-align: top; }
  td.num, th.num { text-align: right; font-family: var(--mono); }
  .mono { font-family: var(--mono); }
  .tag {
    display: inline-block; padding: 1px 6px; border-radius: 4px; font-size: 10.5px;
    font-family: var(--mono); border: 1px solid var(--line); color: var(--dim);
  }
  .tag.ok { color: var(--ok); border-color: #2b5c36; }
  .tag.warn { color: var(--warn); border-color: #5c4a1a; }
  .tag.bad { color: var(--bad); border-color: #6e2b28; }
  .tag.run { color: var(--accent); border-color: #2d4a72; }
  .banner {
    margin: 0 14px; padding: 9px 12px; border-radius: 8px; background: #2a1416;
    border: 1px solid #6e2b28; color: #ffb4ae; font-size: 12px;
  }
  .banner + .banner { margin-top: 8px; }
  .bar { height: 5px; background: #23272d; border-radius: 3px; overflow: hidden; min-width: 90px; }
  .bar > i { display: block; height: 100%; background: var(--accent); }
  button {
    font: inherit; font-size: 11px; padding: 2px 9px; border-radius: 5px; cursor: pointer;
    background: #2a1416; color: #ffb4ae; border: 1px solid #6e2b28;
  }
  button:hover { background: #3a1b1e; }
  button:disabled { opacity: .35; cursor: default; }
  a { color: var(--accent); }
  #feed { max-height: 78vh; overflow-y: auto; }
  .ev { border-top: 1px solid var(--line); padding: 6px 0; display: grid; grid-template-columns: 62px 1fr auto; gap: 8px; }
  .ev:first-child { border-top: none; }
  .ev .t { color: var(--dim); font-family: var(--mono); font-size: 11px; }
  .ev .n { font-family: var(--mono); font-size: 12px; word-break: break-word; }
  .ev .n small { color: var(--dim); display: block; font-size: 11px; word-break: break-all; }
  .ev .d { color: var(--dim); font-family: var(--mono); font-size: 11px; white-space: nowrap; }
  .ev.err .n { color: #ffb4ae; }
  .note { color: var(--dim); font-size: 11px; padding: 2px 0 6px; }
</style>
</head>
<body>
<header>
  <h1>devourer instrument</h1>
  <span class="meta" id="bridge"></span>
  <span class="meta" id="uptime"></span>
  <span class="meta"><span class="dot dead" id="pulse"></span> <span id="conn">connecting</span></span>
</header>
<div id="warnings"></div>
<main>
  <div>
    <section><h2>Radios</h2><div class="body">
      <div class="note">As of the last control-plane operation on each radio. This page never
        polls the bridge, so it cannot slow the instrument or hang with it.</div>
      <div id="radios"></div></div></section>
    <section><h2>Captures</h2><div class="body"><div id="captures"></div></div></section>
    <section><h2>Experiments</h2><div class="body"><div id="experiments"></div></div></section>
    <section><h2>Scratchpads</h2><div class="body"><div id="pads"></div></div></section>
    <section><h2>Characterized adapters</h2><div class="body"><div id="records"></div></div></section>
  </div>
  <div>
    <section><h2>Activity</h2><div class="body">
      <div class="note">Every MCP tool call, as it happens.</div>
      <div id="feed"></div></div></section>
  </div>
</main>
<script>
"use strict";
var lastSeq = 0;
var failures = 0;

function el(tag, cls, text) {
  var n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined && text !== null) n.textContent = String(text);
  return n;
}
function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
function clock(ms) {
  var d = new Date(ms);
  return String(d.getHours()).padStart(2, "0") + ":" +
         String(d.getMinutes()).padStart(2, "0") + ":" +
         String(d.getSeconds()).padStart(2, "0");
}
function since(ms) {
  var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 90) return s + "s ago";
  if (s < 5400) return Math.round(s / 60) + "m ago";
  return Math.round(s / 3600) + "h ago";
}
function dur(ms) {
  if (ms < 1000) return ms + "ms";
  if (ms < 90000) return (ms / 1000).toFixed(1) + "s";
  return Math.round(ms / 60000) + "m";
}
function num(v, digits) {
  if (v === null || v === undefined) return "—";
  return Number(v).toFixed(digits === undefined ? 0 : digits);
}
function table(head, rows, into) {
  clear(into);
  if (!rows.length) { into.appendChild(el("div", "empty", "none")); return; }
  var t = el("table"), thead = el("thead"), tr = el("tr");
  head.forEach(function (h) {
    var th = el("th", h.num ? "num" : null, h.label);
    tr.appendChild(th);
  });
  thead.appendChild(tr); t.appendChild(thead);
  var tb = el("tbody");
  rows.forEach(function (cells) {
    var r = el("tr");
    cells.forEach(function (c, i) {
      var td = el("td", head[i].num ? "num" : null);
      if (c instanceof Node) td.appendChild(c); else td.textContent = c === null || c === undefined ? "—" : String(c);
      r.appendChild(td);
    });
    tb.appendChild(r);
  });
  t.appendChild(tb); into.appendChild(t);
}
function tag(text, kind) { return el("span", "tag " + (kind || ""), text); }

function renderWarnings(list) {
  var box = document.getElementById("warnings");
  clear(box);
  list.forEach(function (w) { box.appendChild(el("div", "banner", w)); });
}

function renderRadios(rows) {
  table(
    [{label: "sess", num: true}, {label: "chip"}, {label: "backend"}, {label: "where"},
     {label: "channel"}, {label: "state"}, {label: "seen"}],
    rows.map(function (r) {
      var st = el("span");
      st.appendChild(tag(r.monitoring ? "monitoring" : (r.brought_up ? "up" : "idle"),
                         r.monitoring ? "run" : ""));
      if (r.carrier_sense_disabled) { st.appendChild(document.createTextNode(" ")); st.appendChild(tag("CCA OFF", "bad")); }
      return [r.session, r.chip, r.backend, r.locator, r.channel, st, since(r.seen_at_epoch_ms)];
    }),
    document.getElementById("radios"));
}

function renderCaptures(rows) {
  table(
    [{label: "id"}, {label: "radio"}, {label: "channel"}, {label: "frames", num: true},
     {label: "fps", num: true}, {label: "crc", num: true}, {label: "retries", num: true}, {label: "started"}],
    rows.map(function (c) {
      return [c.id, c.radio, c.channel, num(c.frames), num(c.frames_per_second, 1),
              num(c.crc_errors), num(c.retries), since(c.started_at_epoch_ms)];
    }),
    document.getElementById("captures"));
}

function renderExperiments(rows) {
  var into = document.getElementById("experiments");
  clear(into);
  if (!rows.length) { into.appendChild(el("div", "empty", "none")); return; }
  var t = el("table"), tb = el("tbody");
  rows.forEach(function (e) {
    var r = el("tr");
    r.appendChild(el("td", "mono", e.id));
    r.appendChild(el("td", null, e.kind));
    var phase = el("td");
    phase.appendChild(tag(e.phase,
      e.phase === "RUNNING" ? "run" : e.phase === "DONE" ? "ok" : e.phase === "CANCELLED" ? "warn" : "bad"));
    r.appendChild(phase);
    var prog = el("td");
    var bar = el("div", "bar"), fill = el("i");
    fill.style.width = (e.total_points ? (100 * e.completed_points / e.total_points) : 0) + "%";
    bar.appendChild(fill);
    prog.appendChild(bar);
    prog.appendChild(el("div", "note", e.completed_points + "/" + e.total_points +
      (e.current_point ? "  ·  " + e.current_point : "")));
    r.appendChild(prog);
    r.appendChild(el("td", "num", dur(e.elapsed_ms)));
    var act = el("td");
    if (e.phase === "RUNNING") {
      var b = el("button", null, "stop");
      b.addEventListener("click", function () { b.disabled = true; stop(e.id); });
      act.appendChild(b);
    } else if (e.message) {
      act.appendChild(el("span", "note", e.message));
    }
    r.appendChild(act);
    tb.appendChild(r);
  });
  t.appendChild(tb); into.appendChild(t);
}

function renderPads(rows) {
  var into = document.getElementById("pads");
  clear(into);
  if (!rows.length) { into.appendChild(el("div", "empty", "none")); return; }
  rows.forEach(function (p) {
    var box = el("div");
    box.style.borderTop = "1px solid var(--line)";
    box.style.padding = "7px 0";
    var head = el("div");
    head.appendChild(el("strong", null, p.title));
    head.appendChild(document.createTextNode(" "));
    head.appendChild(tag(p.running ? "running" : "finished", p.running ? "run" : "ok"));
    head.appendChild(document.createTextNode(" "));
    head.appendChild(el("span", "mono note", p.id));
    if (p.ui_url) {
      head.appendChild(document.createTextNode(" "));
      var a = el("a", null, "open live view");
      a.href = p.ui_url; a.target = "_blank"; a.rel = "noreferrer";
      head.appendChild(a);
    }
    box.appendChild(head);
    if (p.capabilities && p.capabilities.length) {
      var caps = el("div");
      p.capabilities.forEach(function (c) { caps.appendChild(tag(c)); caps.appendChild(document.createTextNode(" ")); });
      box.appendChild(caps);
    }
    var keys = Object.keys(p.series || {});
    if (keys.length) {
      var vals = el("div", "mono note");
      vals.textContent = keys.map(function (k) { return k + "=" + num(p.series[k], 2); }).join("   ");
      box.appendChild(vals);
    }
    (p.log_tail || []).slice(-4).forEach(function (line) {
      box.appendChild(el("div", "mono note", line));
    });
    into.appendChild(box);
  });
}

function renderRecords(rows) {
  table(
    [{label: "adapter"}, {label: "chip"}, {label: "backend"}, {label: "reached"},
     {label: "runs", num: true}, {label: "unverified", num: true}, {label: "last"}],
    rows.map(function (c) {
      return [c.key, c.chip, c.backend, tag(c.state, c.state.indexOf("VERIFIED") >= 0 ? "ok" : ""),
              c.runs, c.unverified, since(c.last_seen_epoch_ms)];
    }),
    document.getElementById("records"));
}

function appendActivity(entries) {
  var feed = document.getElementById("feed");
  var pinned = feed.scrollTop < 24;
  entries.forEach(function (e) {
    var row = el("div", "ev" + (e.ok ? "" : " err"));
    row.appendChild(el("div", "t", clock(e.at_epoch_ms)));
    var n = el("div", "n", e.tool);
    if (e.arguments) n.appendChild(el("small", null, e.arguments));
    if (e.error) n.appendChild(el("small", null, e.error));
    row.appendChild(n);
    row.appendChild(el("div", "d", dur(e.duration_ms)));
    feed.insertBefore(row, feed.firstChild);
  });
  while (feed.childNodes.length > 300) feed.removeChild(feed.lastChild);
  if (pinned) feed.scrollTop = 0;
}

function stop(id) {
  fetch("/api/experiment/" + encodeURIComponent(id) + "/cancel", {
    method: "POST",
    headers: {"X-Devourer-Dashboard": "1"}
  }).then(tick);
}

function live(ok) {
  document.getElementById("pulse").className = "dot " + (ok ? "live" : "dead");
  document.getElementById("conn").textContent = ok ? "live" : "no server";
}

function tick() {
  return fetch("/api/state").then(function (r) { return r.json(); }).then(function (s) {
    document.getElementById("bridge").textContent =
      "bridge " + s.bridge.protocol + "  ·  devourer " + s.bridge.devourer_commit +
      "  ·  " + s.bridge.backends.join(" ");
    document.getElementById("uptime").textContent = "up " + dur(Date.now() - s.bridge.started_at_epoch_ms);
    renderWarnings(s.warnings || []);
    renderRadios(s.radios || []);
    renderCaptures(s.captures || []);
    renderExperiments(s.experiments || []);
    renderPads(s.scratchpads || []);
    renderRecords(s.characterizations || []);
    failures = 0; live(true);
    return fetch("/api/activity?since=" + lastSeq);
  }).then(function (r) { return r.json(); }).then(function (a) {
    if (a.entries && a.entries.length) { appendActivity(a.entries); lastSeq = a.latest; }
    else if (a.latest !== undefined) { lastSeq = a.latest; }
  }).catch(function () { failures += 1; if (failures > 1) live(false); });
}

tick();
setInterval(tick, 1000);
</script>
</body>
</html>
"""
