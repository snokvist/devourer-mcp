package org.openipc.devourer.scratchpad

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Serves a live view of a running scratchpad.
 *
 * Built on the JDK's own `com.sun.net.httpserver` — no dependency, no build
 * weight, and nothing to keep patched. A dashboard for one person watching one
 * bench does not need a web framework.
 *
 * **Bound to loopback, always.** The page shows capture summaries and whatever a
 * program polled; that is bench data and it stays on the bench. Binding to any
 * other interface would need a deliberate change here, not a configuration flag
 * someone sets by accident.
 *
 * The page polls a JSON endpoint rather than holding a socket open: a
 * diagnostic that survives the browser being closed and reopened is worth more
 * than one that streams a few hundred milliseconds sooner.
 */
public class UiServer(
    private val title: String,
    private val state: RunState,
    private val program: ScratchpadProgram,
    port: Int = 0,
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        0,
    )

    public val port: Int get() = server.address.port
    public val url: String get() = "http://127.0.0.1:$port/"

    init {
        server.createContext("/") { ex -> respond(ex, "text/html; charset=utf-8", page()) }
        server.createContext("/data") { ex -> respond(ex, "application/json", data()) }
        server.executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "scratchpad-ui").apply { isDaemon = true }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Cache-Control", "no-store")
        /*
         * Defence in depth behind the escaping. `connect-src 'self'` is the
         * load-bearing directive: it turns any injection that still gets
         * through from "reads the capture and POSTs it anywhere" into a
         * defaced page. The inline script is ours, hence 'unsafe-inline';
         * there is no external resource of any kind, hence default-src 'none'.
         */
        ex.responseHeaders.add(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'self'; img-src 'none'; base-uri 'none'; form-action 'none'",
        )
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun data(): String {
        val sb = StringBuilder("{\"series\":{")
        var first = true
        state.all().forEach { (id, series) ->
            if (!first) sb.append(',')
            first = false
            sb.append(quote(id)).append(":{")
            val stats = series.stats()
            if (stats != null) {
                sb.append("\"last\":").append(num(stats.last))
                sb.append(",\"min\":").append(num(stats.min))
                sb.append(",\"max\":").append(num(stats.max))
                sb.append(",\"mean\":").append(num(stats.mean))
                sb.append(",\"count\":").append(stats.count)
            }
            sb.append(",\"points\":[")
            // Cap what the page receives: a 30-minute run at 500ms holds 3600
            // points per series, and a browser redrawing all of them ten times
            // a second is a worse diagnostic than one showing the recent window.
            val points = series.snapshot().takeLast(MAX_POINTS)
            points.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                sb.append('[').append(s.atEpochMs).append(',').append(num(s.value)).append(']')
            }
            sb.append("]}")
        }
        sb.append("},\"log\":[")
        state.logs().forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append(quote(l))
        }
        sb.append("],\"running\":").append(state.finishedAtEpochMs == 0L)
        sb.append(",\"elapsed_ms\":")
            .append((state.finishedAtEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis()) - state.startedAtEpochMs)
        sb.append('}')
        return sb.toString()
    }

    private fun num(d: Double): String =
        if (d.isNaN() || d.isInfinite()) {
            "null"
        } else {
            // Locale.ROOT or a comma decimal separator under de_DE/fr_FR/sv_SE
            // splices `1,5` into the JSON unquoted, the page's fetch throws,
            // and the live view shows "disconnected" forever with nothing
            // logged anywhere.
            String.format(java.util.Locale.ROOT, "%.4f", d).trimEnd('0').trimEnd('.')
        }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                /*
                 * `<` and `>` are escaped even though JSON does not require it,
                 * because this JSON is embedded in a <script> element. The HTML
                 * parser leaves script-data state on the literal text
                 * "</script" regardless of JavaScript string context, so a
                 * series name or widget title containing it closed the script
                 * and ran attacker-authored JS at the loopback origin — with
                 * same-origin read of /data and unrestricted network egress.
                 * That defeats the entire point of the HTTP allowlist.
                 */
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Escapes text destined for HTML, so a program name cannot inject markup. */
    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun page(): String {
        val widgets = program.ui?.widgets ?: emptyList()
        val widgetJson = widgets.joinToString(",") { w ->
            """{"kind":${quote(w.kind)},"title":${quote(w.title)},"unit":${quote(w.unit)},""" +
                """"series":[${w.series.joinToString(",") { quote(it) }}],""" +
                """"min":${w.min ?: "null"},"max":${w.max ?: "null"}}"""
        }
        return """
<!doctype html>
<meta charset="utf-8">
<title>${esc(title)}</title>
<style>
  :root { color-scheme: dark; --bg:#0e1116; --panel:#171b22; --line:#2a303a;
          --fg:#e6e9ef; --dim:#8b94a3; --accent:#5eb3f6; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--fg);
         font:14px/1.5 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  header { padding:14px 20px; border-bottom:1px solid var(--line);
           display:flex; align-items:baseline; gap:16px; flex-wrap:wrap; }
  h1 { font-size:16px; margin:0; font-weight:600; }
  .meta { color:var(--dim); font-size:12px; }
  .dot { width:8px; height:8px; border-radius:50%; display:inline-block; margin-right:6px; }
  .live { background:#3ddc84; } .done { background:var(--dim); }
  main { padding:16px 20px; display:grid; gap:16px;
         grid-template-columns:repeat(auto-fit,minmax(340px,1fr)); }
  .card { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; }
  .card h2 { font-size:12px; margin:0 0 10px; color:var(--dim);
             text-transform:uppercase; letter-spacing:.06em; font-weight:600; }
  .stat { font-size:30px; font-variant-numeric:tabular-nums; }
  .unit { font-size:13px; color:var(--dim); margin-left:4px; }
  .sub { color:var(--dim); font-size:12px; margin-top:4px; font-variant-numeric:tabular-nums; }
  canvas { width:100%; height:150px; display:block; }
  table { width:100%; border-collapse:collapse; font-variant-numeric:tabular-nums; }
  th,td { text-align:right; padding:4px 6px; border-bottom:1px solid var(--line); }
  th:first-child, td:first-child { text-align:left; }
  th { color:var(--dim); font-weight:500; font-size:12px; }
  pre { margin:0; max-height:220px; overflow:auto; font-size:12px; color:var(--dim);
        white-space:pre-wrap; }
  .legend { display:flex; gap:12px; flex-wrap:wrap; font-size:12px; color:var(--dim);
            margin-top:8px; }
  .swatch { width:10px; height:3px; display:inline-block; vertical-align:middle; margin-right:5px; }
  .empty { color:var(--dim); font-style:italic; }
</style>
<header>
  <h1>${esc(title)}</h1>
  <span class="meta"><span id="dot" class="dot live"></span><span id="status">starting</span></span>
  <span class="meta">${esc(program.purpose)}</span>
</header>
<main id="main"></main>
<script>
const WIDGETS = [$widgetJson];
const COLORS = ["#5eb3f6","#3ddc84","#f6c85e","#f6725e","#b98cf6","#5ef6d4"];
const main = document.getElementById("main");
let cards = null;

function build(){
  main.innerHTML = "";
  cards = [];
  const specs = WIDGETS.length ? WIDGETS : [{kind:"table",title:"All series",series:[]}];
  specs.forEach((w,i)=>{
    const el = document.createElement("div");
    el.className = "card";
    // textContent, not innerHTML: w.title is model-authored.
    const h = document.createElement("h2");
    h.textContent = w.title || w.kind;
    const b = document.createElement("div");
    b.className = "body";
    el.append(h, b);
    main.appendChild(el);
    cards.push({spec:w, body:el.querySelector(".body")});
  });
}

function fmt(v,d){ return v==null||isNaN(v) ? "—" : (+v).toFixed(d==null?1:d); }

function drawChart(body, spec, data){
  let c = body.querySelector("canvas");
  if(!c){
    c = document.createElement("canvas");
    body.appendChild(c);
    const lg = document.createElement("div");
    lg.className = "legend";
    body.appendChild(lg);
  }
  const legend = body.querySelector(".legend");
  const names = spec.series.length ? spec.series : Object.keys(data.series);
  const dpr = window.devicePixelRatio||1;
  c.width = c.clientWidth*dpr; c.height = 150*dpr;
  const ctx = c.getContext("2d");
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,c.clientWidth,150);

  let lo = spec.min, hi = spec.max, t0=Infinity, t1=-Infinity;
  names.forEach(n=>{
    const s = data.series[n]; if(!s||!s.points.length) return;
    s.points.forEach(p=>{
      if(p[1]==null) return;
      if(lo==null||p[1]<lo) lo=p[1];
      if(hi==null||p[1]>hi) hi=p[1];
      if(p[0]<t0) t0=p[0]; if(p[0]>t1) t1=p[0];
    });
  });
  if(lo==null||hi==null||!isFinite(t0)){ ctx.fillStyle="#8b94a3"; ctx.fillText("waiting for data",8,20); legend.innerHTML=""; return; }
  if(hi===lo){ hi=lo+1; lo=lo-1; }
  const pad = (hi-lo)*0.08; lo-=pad; hi+=pad;
  const W=c.clientWidth, H=150, L=42, B=18;

  ctx.strokeStyle="#2a303a"; ctx.lineWidth=1; ctx.fillStyle="#8b94a3"; ctx.font="10px system-ui";
  for(let i=0;i<=3;i++){
    const y = 6 + (H-B-6)*i/3;
    ctx.beginPath(); ctx.moveTo(L,y); ctx.lineTo(W,y); ctx.stroke();
    ctx.fillText(fmt(hi-(hi-lo)*i/3), 4, y+3);
  }
  names.forEach((n,i)=>{
    const s = data.series[n]; if(!s||!s.points.length) return;
    ctx.strokeStyle = COLORS[i%COLORS.length]; ctx.lineWidth=1.6;
    ctx.beginPath();
    let started=false;
    s.points.forEach(p=>{
      if(p[1]==null){ started=false; return; }
      const x = L + (W-L) * (t1===t0 ? 1 : (p[0]-t0)/(t1-t0));
      const y = 6 + (H-B-6) * (1-(p[1]-lo)/(hi-lo));
      if(!started){ ctx.moveTo(x,y); started=true; } else ctx.lineTo(x,y);
    });
    ctx.stroke();
  });
  // Series names are model-authored, so they are set as text and never parsed
  // as markup. The swatch is the only element carrying style, and its colour
  // comes from our own fixed palette, not from the program.
  legend.replaceChildren(...names.map((n,i)=>{
    const s = data.series[n];
    const v = s && s.last!=null ? fmt(s.last) : "—";
    const wrap = document.createElement("span");
    const sw = document.createElement("span");
    sw.className = "swatch";
    sw.style.background = COLORS[i%COLORS.length];
    wrap.append(sw, document.createTextNode(n+" "+v));
    return wrap;
  }));
}

function render(data){
  if(!cards) build();
  const status = document.getElementById("status");
  const dot = document.getElementById("dot");
  status.textContent = (data.running?"running":"finished")+" · "+(data.elapsed_ms/1000).toFixed(1)+"s";
  dot.className = "dot "+(data.running?"live":"done");

  cards.forEach(({spec,body})=>{
    if(spec.kind==="chart"){ drawChart(body,spec,data); return; }
    if(spec.kind==="stat"||spec.kind==="gauge"){
      const n = spec.series[0];
      const s = n ? data.series[n] : null;
      body.replaceChildren();
      if(!s){
        const e = document.createElement("div"); e.className="empty";
        e.textContent = "waiting for data"; body.append(e); return;
      }
      const stat = document.createElement("div"); stat.className="stat";
      stat.textContent = fmt(s.last);
      const unit = document.createElement("span"); unit.className="unit";
      unit.textContent = spec.unit || "";           // model-authored
      stat.append(unit);
      const sub = document.createElement("div"); sub.className="sub";
      sub.textContent = "min "+fmt(s.min)+" · mean "+fmt(s.mean)+" · max "+fmt(s.max)+" · n="+s.count;
      body.append(stat, sub);
      return;
    }
    if(spec.kind==="log"){
      // Log lines quote allowlist hosts, capture ids and exception text — all
      // of it model- or environment-derived.
      body.replaceChildren();
      const pre = document.createElement("pre");
      pre.textContent = data.log.length ? data.log.join("\n") : "no messages";
      body.append(pre);
      return;
    }
    const names = spec.series.length ? spec.series : Object.keys(data.series).sort();
    body.replaceChildren();
    if(!names.length){
      const e = document.createElement("div"); e.className="empty";
      e.textContent = "waiting for data"; body.append(e); return;
    }
    // The table is the DEFAULT widget when a program declares none, so this
    // sink is reachable by every program — series names must be text.
    const t = document.createElement("table");
    const head = t.insertRow();
    ["series","last","min","mean","max","n"].forEach(h=>{
      const th = document.createElement("th"); th.textContent = h; head.append(th);
    });
    names.forEach(n=>{
      const s = data.series[n];
      const row = t.insertRow();
      [n, s?fmt(s.last):"—", s?fmt(s.min):"—", s?fmt(s.mean):"—", s?fmt(s.max):"—", s?String(s.count):"0"]
        .forEach(v=>{ row.insertCell().textContent = v; });
    });
    body.append(t);
  });
}

async function tick(){
  try{
    const r = await fetch("/data",{cache:"no-store"});
    render(await r.json());
  }catch(e){
    document.getElementById("status").textContent = "disconnected";
    document.getElementById("dot").className = "dot done";
  }
}
build(); tick(); setInterval(tick, 500);
</script>
""".trimIndent()
    }

    private companion object {
        const val MAX_POINTS = 600
    }
}
