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
        if (d.isNaN() || d.isInfinite()) "null" else String.format("%.4f", d).trimEnd('0').trimEnd('.')

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
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
    el.innerHTML = "<h2>"+(w.title||w.kind)+"</h2><div class='body'></div>";
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
  legend.innerHTML = names.map((n,i)=>{
    const s = data.series[n];
    const v = s && s.last!=null ? fmt(s.last) : "—";
    return "<span><span class='swatch' style='background:"+COLORS[i%COLORS.length]+"'></span>"+n+" "+v+"</span>";
  }).join("");
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
      body.innerHTML = s
        ? "<div class='stat'>"+fmt(s.last)+"<span class='unit'>"+(spec.unit||"")+"</span></div>"+
          "<div class='sub'>min "+fmt(s.min)+" · mean "+fmt(s.mean)+" · max "+fmt(s.max)+" · n="+s.count+"</div>"
        : "<div class='empty'>waiting for data</div>";
      return;
    }
    if(spec.kind==="log"){
      body.innerHTML = "<pre>"+(data.log.length?data.log.join("\n"):"no messages")+"</pre>";
      return;
    }
    const names = spec.series.length ? spec.series : Object.keys(data.series).sort();
    if(!names.length){ body.innerHTML="<div class='empty'>waiting for data</div>"; return; }
    body.innerHTML = "<table><tr><th>series</th><th>last</th><th>min</th><th>mean</th><th>max</th><th>n</th></tr>"+
      names.map(n=>{
        const s = data.series[n];
        return "<tr><td>"+n+"</td><td>"+(s?fmt(s.last):"—")+"</td><td>"+(s?fmt(s.min):"—")+
               "</td><td>"+(s?fmt(s.mean):"—")+"</td><td>"+(s?fmt(s.max):"—")+
               "</td><td>"+(s?s.count:0)+"</td></tr>";
      }).join("")+"</table>";
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
