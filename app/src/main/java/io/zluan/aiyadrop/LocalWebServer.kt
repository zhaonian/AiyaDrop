package io.zluan.aiyadrop

import android.util.Base64
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LocalWebServer(private val portNum: Int, private val storageDir: File) {
    private var engine: ApplicationEngine? = null

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
    }

    private fun Application.configure() {
        install(ContentNegotiation) { json() }
        routing {
            get("/") {
                call.respondText(buildIndexHtml(), ContentType.Text.Html)
            }
            get("/list") {
                val names = storageDir.listFiles()?.sortedBy { it.name }?.map { it.name } ?: emptyList()
                call.respond(Json.encodeToString(names))
            }
            get("/files/{name}") {
                val name = call.parameters["name"] ?: return@get call.respondText("Not found", status = io.ktor.http.HttpStatusCode.NotFound)
                val file = File(storageDir, name)
                if (!file.exists()) return@get call.respondText("Not found", status = io.ktor.http.HttpStatusCode.NotFound)
                call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
            }
            post("/") {
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
            // no static resources served from classpath
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
          <script>
            async function refresh(){
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
            refresh();
          </script>
        </body>
        </html>
    """.trimIndent()

    private fun safeName(input: String): String = input.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
}


