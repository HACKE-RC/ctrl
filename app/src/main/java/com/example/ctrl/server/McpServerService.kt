package com.example.ctrl.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ctrl.R
import com.example.ctrl.mcp.JsonRpc
import com.example.ctrl.mcp.JsonRpcException
import com.example.ctrl.mcp.McpHandler
import com.example.ctrl.net.NetworkUtils
import com.example.ctrl.security.Allowlist
import com.example.ctrl.security.AllowlistConfig
import com.example.ctrl.security.AllowlistStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.request.host
import io.ktor.server.request.port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Writer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class McpServerService : Service() {
    companion object {
        private const val CHANNEL_ID = "ctrl_server"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int = 8787) {
            val i = Intent(context, McpServerService::class.java).apply {
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpServerService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: ApplicationEngine? = null
    private lateinit var allowlistStore: AllowlistStore
    @Volatile private var allowConfig: AllowlistConfig = AllowlistConfig(enabled = true, entries = emptyList(), lastBlockedIp = null)
    @Volatile private var compiledAllowRules = emptyList<com.example.ctrl.security.AllowRule>()
    private lateinit var handler: McpHandler
    private val rateLimiter = RateLimiter(capacity = 20.0, refillTokensPerSecond = 10.0)
    
    // SSE session management - maps session ID to response channel
    private val sseSessions = ConcurrentHashMap<String, Channel<String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        allowlistStore = AllowlistStore(applicationContext)
        handler = McpHandler(applicationContext)
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting"))

        scope.launch {
            allowlistStore.configFlow.collect { cfg ->
                allowConfig = cfg
                compiledAllowRules = cfg.compiledRules()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 8787) ?: 8787
        if (engine != null) return START_STICKY

        scope.launch {
            try {
                startServer(port)
            } catch (e: Exception) {
                ServerState.update(ServerStatus(running = false, host = "0.0.0.0", port = port, lastError = e.message))
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch {
            try {
                engine?.stop(500, 2000)
            } catch (_: Exception) {
            }
            engine = null
            sseSessions.values.forEach { it.close() }
            sseSessions.clear()
            ServerState.update(ServerStatus.stopped(port = ServerState.status.value.port))
        }
        super.onDestroy()
    }

    private suspend fun startServer(port: Int) {
        val host = "0.0.0.0"
        val server = embeddedServer(CIO, host = host, port = port) {
            routing {
                // SSE endpoint for legacy MCP SSE transport
                get("/sse") {
                    handleSseConnection(call, port)
                }
                
                // Streamable HTTP: return 405 for GET per spec (we only support POST)
                get("/mcp") {
                    call.response.headers.append("Allow", "POST, DELETE")
                    call.respond(HttpStatusCode.MethodNotAllowed)
                }

                delete("/mcp") {
                    val closed = handler.closeSession(headerMap(call))
                    call.respond(if (closed) HttpStatusCode.OK else HttpStatusCode.NotFound)
                }
                
                // SSE message endpoint - client POSTs here, response goes back via SSE
                post("/sse/message") {
                    handleSseMessage(call)
                }

                // Streamable HTTP endpoint - direct POST/response
                post("/mcp") {
                    handleStreamableHttp(call, port)
                }
            }
        }

        withContext(Dispatchers.IO) {
            engine = server
            server.start(wait = false)
        }

        ServerState.update(ServerStatus(running = true, host = host, port = port, lastError = null))
        updateNotification("Running on ${NetworkUtils.localIpv4Addresses().joinToString(",")}:$port")
    }
    
    private suspend fun handleSseConnection(call: io.ktor.server.application.ApplicationCall, port: Int) {
        val remoteHost = call.request.origin.remoteHost
        val remoteIpv4 = Allowlist.parseRemoteIpv4(remoteHost)
        val config = allowConfig
        val rules = compiledAllowRules
        val isLocalhost = remoteIpv4 == 0x7F000001
        if (config.enabled && !isLocalhost) {
            val allowed = remoteIpv4 != null && rules.any { it.matches(remoteIpv4) }
            if (!allowed) {
                call.respond(HttpStatusCode.Forbidden)
                return
            }
        }

        val sessionId = UUID.randomUUID().toString()
        val messageChannel = Channel<String>(Channel.BUFFERED)
        sseSessions[sessionId] = messageChannel

        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        call.response.headers.append("X-Accel-Buffering", "no")
        
        try {
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                // Send endpoint event with the POST URL including session ID
                val postEndpoint = "http://${call.request.host()}:${port}/sse/message?sessionId=$sessionId"
                write("event: endpoint\n")
                write("data: $postEndpoint\n\n")
                flush()

                // Listen for messages to send back
                try {
                    while (true) {
                        val result = messageChannel.tryReceive()
                        if (result.isSuccess) {
                            val message = result.getOrNull()!!
                            write("event: message\n")
                            write("data: $message\n\n")
                            flush()
                        } else if (result.isClosed) {
                            break
                        } else {
                            // No message, send keepalive
                            delay(100)
                            // Send ping every 15 seconds
                            write(": ping\n\n")
                            flush()
                            delay(14900)
                        }
                    }
                } catch (e: Exception) {
                    // Client disconnected
                }
            }
        } finally {
            sseSessions.remove(sessionId)
            messageChannel.close()
        }
    }
    
    private suspend fun handleSseMessage(call: io.ktor.server.application.ApplicationCall) {
        val sessionId = call.request.queryParameters["sessionId"]
        if (sessionId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
            return
        }
        
        val messageChannel = sseSessions[sessionId]
        if (messageChannel == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return
        }
        
        val body = call.receiveText()
        
        val requestObj = try {
            JsonRpc.parseObject(body)
        } catch (e: Exception) {
            val error = JsonRpc.error(null, -32700, "Parse error")
            messageChannel.send(error.toString())
            call.respond(HttpStatusCode.Accepted)
            return
        }
        
        val response = try {
            handler.handle(requestObj, headerMap(call))
        } catch (e: JsonRpcException) {
            val id = requestObj["id"]
            val err = JsonRpc.error(id, e.code, e.message, e.data)
            messageChannel.send(err.toString())
            call.respond(HttpStatusCode.Accepted)
            return
        } catch (e: Exception) {
            val id = requestObj["id"]
            val err = JsonRpc.error(id, -32603, "Internal error")
            messageChannel.send(err.toString())
            call.respond(HttpStatusCode.Accepted)
            return
        }
        
        // Send response via SSE
        val jsonBody = response.body
        if (jsonBody != null) {
            messageChannel.send(jsonBody.toString())
        }
        
        call.respond(HttpStatusCode.Accepted)
    }

    private suspend fun handleStreamableHttp(call: io.ktor.server.application.ApplicationCall, port: Int) {
        val remoteHost = call.request.origin.remoteHost
        val remoteIpv4 = Allowlist.parseRemoteIpv4(remoteHost)
        val remoteIpString = remoteIpv4?.let { Allowlist.ipv4ToString(it) } ?: remoteHost

        val config = allowConfig
        val rules = compiledAllowRules
        val isLocalhost = remoteIpv4 == 0x7F000001
        if (config.enabled && !isLocalhost) {
            val ip = remoteIpv4
            val allowed = ip != null && rules.any { it.matches(ip) }
            if (!allowed) {
                if (remoteIpv4 != null) {
                    allowlistStore.setLastBlockedIp(Allowlist.ipv4ToString(remoteIpv4))
                }
                call.respond(HttpStatusCode.Forbidden)
                return
            }
        }

        val origin = call.request.header(HttpHeaders.Origin)
        if (!origin.isNullOrBlank()) {
            val ok = runCatching {
                val uri = java.net.URI(origin.trim())
                val scheme = uri.scheme?.lowercase(Locale.US)
                val host = uri.host
                (scheme == "http" || scheme == "https") && host != null && host == remoteIpString
            }.getOrDefault(false)
            if (!ok) {
                call.respond(HttpStatusCode.Forbidden)
                return
            }
        }

        if (!rateLimiter.tryAcquire(remoteIpString)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return
        }

        val accept = call.request.header(HttpHeaders.Accept)?.lowercase(Locale.US) ?: ""
        if (!accept.contains("application/json") && !accept.contains("*/*") && !accept.contains("text/event-stream")) {
            call.respond(HttpStatusCode.NotAcceptable)
            return
        }

        val contentType = call.request.contentType()
        if (!contentType.match(ContentType.Application.Json)) {
            call.respond(HttpStatusCode.UnsupportedMediaType)
            return
        }

        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: -1
        if (contentLength > 4L * 1024L * 1024L) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }

        val body = call.receiveText()
        if (body.length > 4 * 1024 * 1024) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }

        val requestObj = try {
            JsonRpc.parseObject(body)
        } catch (e: JsonRpcException) {
            call.respondText(
                text = JsonRpc.error(null, e.code, e.message, e.data).toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest,
            )
            return
        } catch (e: Exception) {
            call.respondText(
                text = JsonRpc.error(null, -32700, "Parse error").toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest,
            )
            return
        }

        val response = try {
            handler.handle(requestObj, headerMap(call))
        } catch (e: JsonRpcException) {
            val status = if (e.code == -32001) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
            val id = requestObj["id"]
            val err = JsonRpc.error(id, e.code, e.message, e.data)
            call.respondText(text = err.toString(), contentType = ContentType.Application.Json, status = status)
            return
        } catch (e: Exception) {
            val id = requestObj["id"]
            val err = JsonRpc.error(id, -32603, "Internal error")
            call.respondText(
                text = err.toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError,
            )
            return
        }

        response.headers.forEach { (k, v) -> call.response.headers.append(k, v) }
        val jsonBody = response.body
        if (response.status == 202 || jsonBody == null) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        call.respondText(text = jsonBody.toString(), contentType = ContentType.Application.Json, status = HttpStatusCode.OK)
    }

    private fun headerMap(call: io.ktor.server.application.ApplicationCall): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (name in call.request.headers.names()) {
            val value = call.request.headers[name] ?: continue
            map[name] = value
        }
        return map
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "CTRL Server", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("CTRL")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
