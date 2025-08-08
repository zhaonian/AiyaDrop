package io.zluan.aiyadrop

import android.util.Base64
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class ClientInfo(val ip: String, val userAgent: String?, val connectedAt: Long)

class LocalWebServer(
    private val portNum: Int,
    private val storageDir: File,
    private val onNewClient: ((ClientInfo) -> Unit)? = null,
    private val onClientMessage: ((String, String) -> Unit)? = null,
    private val indexHtml: String? = null
) {
    private var engine: ApplicationEngine? = null
    private val knownClients = ConcurrentHashMap<String, ClientInfo>()
    private val messageQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val socketsByIp = ConcurrentHashMap<String, MutableSet<io.ktor.websocket.DefaultWebSocketSession>>()
    private val wsJob: Job = Job()
    private val wsScope: CoroutineScope = CoroutineScope(wsJob + Dispatchers.IO)

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = portNum) {
            configure()
        }
        engine?.start(false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        try { wsJob.cancel() } catch (_: Throwable) {}
    }

    private fun Application.configure() {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        routing {
            get("/") {
                debugLogCall("/", call)
                recordClient(call)
                call.respondText(indexHtml ?: buildIndexHtml(), ContentType.Text.Html)
            }
            get("/list") {
                debugLogCall("/list", call)
                recordClient(call)
                val names = storageDir.listFiles()?.sortedBy { it.name }?.map { it.name } ?: emptyList()
                call.respondText(Json.encodeToString(names), ContentType.Application.Json)
            }
            get("/whoami") {
                debugLogCall("/whoami", call)
                recordClient(call)
                val ip = normalizeIp(call.request.local.remoteAddress) ?: ""
                call.respondText(Json.encodeToString(mapOf("ip" to ip)), ContentType.Application.Json)
            }
            webSocket("/ws") {
                val ip = normalizeIp(call.request.local.remoteAddress) ?: ""
                val set = socketsByIp.computeIfAbsent(ip) { mutableSetOf<io.ktor.websocket.DefaultWebSocketSession>() }
                set.add(this)
                Log.d("AiyaDrop", "ws open $ip (sessions=${set.size})")
                // Flush any queued messages for this IP
                try {
                    val queue = messageQueues[ip]
                    if (queue != null && queue.isNotEmpty()) {
                        val pending = queue.toList()
                        queue.clear()
                        for (m in pending) {
                            try { send(Frame.Text(m)) } catch (t: Throwable) { Log.d("AiyaDrop", "ws flush fail to $ip: ${t.message}") }
                        }
                    }
                } catch (_: Throwable) {}
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                Log.d("AiyaDrop", "ws recv from $ip: $text")
                                onClientMessage?.invoke(ip, text)
                            }
                            else -> {}
                        }
                    }
                } finally {
                    set.remove(this)
                    Log.d("AiyaDrop", "ws close $ip (sessions=${set.size})")
                }
            }
            get("/state") {
                debugLogCall("/state", call)
                val clients = knownClients.keys.sorted()
                val queues = messageQueues.entries.associate { it.key to it.value.size }
                call.respondText(Json.encodeToString(mapOf("clients" to clients, "queues" to queues)), ContentType.Application.Json)
            }
            get("/files/{name}") {
                debugLogCall("/files", call)
                recordClient(call)
                val name = call.parameters["name"] ?: return@get call.respondText("Not found", status = io.ktor.http.HttpStatusCode.NotFound)
                val file = File(storageDir, name)
                if (!file.exists()) return@get call.respondText("Not found", status = io.ktor.http.HttpStatusCode.NotFound)
                call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
            }
            post("/") {
                debugLogCall("POST /", call)
                recordClient(call)
                val params = call.receiveParameters()
                val filename = params["filename"]
                val base64 = params["base64"]
                val bytes: ByteArray? = base64?.let { Base64.decode(it, Base64.DEFAULT) }
                val finalName = safeName(
                    filename ?: (System.currentTimeMillis().toString() + ".bin")
                )
                if (bytes == null) return@post call.respondText("Missing file", status = io.ktor.http.HttpStatusCode.BadRequest)
                File(storageDir, finalName).writeBytes(bytes!!)
                call.respondText("{\"ok\":true}", ContentType.Application.Json)
            }
            post("/message") {
                debugLogCall("/message", call)
                recordClient(call)
                val ip = normalizeIp(call.request.local.remoteAddress) ?: return@post call.respondText("Missing ip", status = io.ktor.http.HttpStatusCode.BadRequest)
                val params = call.receiveParameters()
                val text = params["text"]?.trim()
                if (text.isNullOrEmpty()) return@post call.respondText("Missing text", status = io.ktor.http.HttpStatusCode.BadRequest)
                Log.d("AiyaDrop", "message from $ip: $text")
                onClientMessage?.invoke(ip, text)
                call.respondText("{\"ok\":true}", ContentType.Application.Json)
            }
            // no static resources served from classpath
        }
    }

    private fun recordClient(call: ApplicationCall) {
        val ip = normalizeIp(call.request.local.remoteAddress) ?: return
        if (!knownClients.containsKey(ip)) {
            val info = ClientInfo(ip = ip, userAgent = call.request.headers["User-Agent"], connectedAt = System.currentTimeMillis())
            knownClients[ip] = info
            Log.d("AiyaDrop", "new client: $ip UA=${info.userAgent}")
            onNewClient?.invoke(info)
        }
    }

    private fun normalizeIp(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isBlank()) return null
        // On Android/CIO we may see ":ffff:192.168.x.x" or IPv6 loopbacks – try to extract IPv4 tail
        val idx = v.lastIndexOf(':')
        val candidate = if (idx >= 0 && v.count { it == ':' } >= 2) v.substring(idx + 1) else v
        return candidate
    }

    private fun debugLogCall(tag: String, call: ApplicationCall) {
        try {
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            val local = call.request.local
            val remoteAddr = local.remoteAddress
            val remoteHost = local.remoteHost
            val remotePort = local.remotePort
            val ua = call.request.headers["User-Agent"]
            Log.d(
                "AiyaDrop",
                "route=$tag method=$method uri=$uri ip=$remoteAddr host=$remoteHost port=$remotePort ua=$ua"
            )
        } catch (t: Throwable) {
            Log.d("AiyaDrop", "debugLogCall error: ${t.message}")
        }
    }

    fun sendMessageTo(ip: String, text: String) {
        val queue = messageQueues.computeIfAbsent(ip) { ConcurrentLinkedQueue<String>() }
        queue.add(text)
        Log.d("AiyaDrop", "queued to $ip: $text (queue size now ${queue.size})")
        val sessions = socketsByIp[ip]
        if (!sessions.isNullOrEmpty()) {
            val frame = Frame.Text(text)
            sessions.toList().forEach { session ->
                wsScope.launch {
                    try { session.send(frame) } catch (t: Throwable) { Log.d("AiyaDrop", "ws send fail to $ip: ${t.message}") }
                }
            }
        }
    }

    private fun buildIndexHtml(): String = """
        <!doctype html>
        <html lang=\"en\">
        <head>
          <meta charset=\"utf-8\" />
          <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
          <title>AiyaDrop</title>
          <style>
            body { font-family: system-ui, -apple-system, Roboto, Arial, sans-serif; margin: 24px; }
            #files { margin-top: 16px; }
            .row { display: flex; gap: 8px; align-items: center; margin: 6px 0; }
            .thumb { width: 64px; height: 64px; object-fit: cover; border-radius: 8px; background: #eee; }
            input, button { pointer-events: auto; }
          </style>
        </head>
        <body>
          <h2>AiyaDrop</h2>
          <form id=\"uploadForm\" method=\"post\">
            <input id=\"fileInput\" type=\"file\" accept=\"image/*\" />
            <input id=\"nameInput\" type=\"text\" placeholder=\"Optional file name\" />
            <button type=\"submit\">Upload</button>
          </form>
          <div id=\"files\"></div>
          <h3>Messages from server</h3>
          <div id=\"messages\" style=\"min-height:48px;padding:8px;border:1px solid #ddd;border-radius:8px;\"></div>
          <div style=\"margin:8px 0;color:#666;\">You are: <span id=\"whoami\">(loading...)</span></div>
          <h3>Send message to app</h3>
          <div class=\"row\">
            <input id=\"sendInput\" type=\"text\" placeholder=\"Type message\" autocomplete=\"off\" tabindex=\"0\" style=\"flex:1;font-size:16px;padding:8px;\" />
            <button id=\"sendBtn\" type=\"button\">Send</button>
          </div>
          <script>
            function _log(msg){ try{ const root = document.getElementById('messages'); const p=document.createElement('div'); p.textContent='[client] '+String(msg); root.appendChild(p);}catch(e){} }
            async function refresh(){
              _log('refresh /list');
              const res = await fetch('/list');
              const names = await res.json();
              const root = document.getElementById('files');
              root.innerHTML='';
              for(const n of names){
                const row = document.createElement('div');
                row.className='row';
                const img = document.createElement('img');
                img.className='thumb';
                img.src='/files/'+encodeURIComponent(n);
                const a = document.createElement('a');
                a.href='/files/'+encodeURIComponent(n);
                a.textContent=n;
                a.download=n;
                row.appendChild(img);
                row.appendChild(a);
                root.appendChild(row);
              }
            }
            async function whoami(){
              _log('whoami start');
              try{
                const res = await fetch('/whoami');
                const data = await res.json();
                _log('whoami resp '+JSON.stringify(data));
                document.getElementById('whoami').textContent = data && data.ip || '(unknown)';
              }catch(e){ document.getElementById('whoami').textContent = '(error)'; }
            }
            let polling = false;
            async function pollMessages(){
              if(polling) return; polling = true;
              try{
                const res = await fetch('/poll');
                const msgs = await res.json();
                _log('poll got '+(Array.isArray(msgs)?msgs.length:'?')+' msgs');
                if(Array.isArray(msgs) && msgs.length){
                  const root = document.getElementById('messages');
                  for(const m of msgs){
                    const p = document.createElement('div');
                    p.textContent = String(m);
                    root.appendChild(p);
                  }
                }
              }catch(e){ /* ignore */ }
              finally { polling = false; }
            }
            function doSend(){
              const input = document.getElementById('sendInput');
              const v = String(input.value || '').trim();
              _log('click send text="'+v+'"');
              if(!v){ _log('empty text'); return; }
              const body = new URLSearchParams();
              body.set('text', v);
              fetch('/message', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body: body.toString() })
                .then(r=>{ _log('POST /message status='+r.status); if(r.ok){ const root=document.getElementById('messages'); const p=document.createElement('div'); p.textContent='You: '+v; root.appendChild(p); input.value=''; setTimeout(pollMessages, 50); } })
                .catch(e=>_log('send failed '+e));
            }
            document.getElementById('sendBtn').addEventListener('click', doSend);
            document.getElementById('sendInput').addEventListener('keydown', (e)=>{ if(e.key==='Enter'){ e.preventDefault(); doSend(); }});
            setTimeout(()=>{ try{ document.getElementById('sendInput').focus(); }catch(e){} }, 100);
            document.getElementById('uploadForm').addEventListener('submit', async (e)=>{
              e.preventDefault();
              const file = document.getElementById('fileInput').files[0];
              if(!file){ return; }
              const name = document.getElementById('nameInput').value || file.name;
              const dataUrl = await new Promise((res, rej)=>{ const fr=new FileReader(); fr.onload=()=>res(fr.result); fr.onerror=rej; fr.readAsDataURL(file); });
              const base64 = String(dataUrl).split(',')[1] || '';
              const body = new URLSearchParams();
              body.set('filename', name);
              body.set('base64', base64);
              const r = await fetch('/', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body: body.toString() });
              if(r.ok){ await refresh(); e.target.reset(); }
            });
            refresh(); whoami();
            setInterval(pollMessages, 1000);
          </script>
        </body>
        </html>
    """.trimIndent()

    private fun safeName(input: String): String = input.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
}


