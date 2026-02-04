package com.example.ctrl.mcp

import android.content.Context
import com.example.ctrl.app.AppController
import com.example.ctrl.capture.ScreenCaptureManager
import com.example.ctrl.input.CtrlAccessibilityService
import com.example.ctrl.input.InputController
import com.example.ctrl.input.UiSelector
import com.example.ctrl.input.dumpUiTree
import com.example.ctrl.input.findNodesBySelector
import com.example.ctrl.input.toUiElementInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class McpHttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
)

class McpHandler(private val appContext: Context) {
    companion object {
        val supportedProtocolVersions: List<String> = listOf(
            "2025-11-25",
            "2025-03-26",
            "2024-11-05",
        )

        // Header names (case-insensitive matching)
        private const val HEADER_SESSION_ID = "mcp-session-id"
        private const val HEADER_PROTOCOL_VERSION = "mcp-protocol-version"

        private val emptyObjectSchema: JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("additionalProperties", JsonPrimitive(false))
        }
    }

    // Helper to get header case-insensitively
    private fun getHeader(headers: Map<String, String>, name: String): String? {
        // Try exact match first
        headers[name]?.let { return it }
        // Try case-insensitive match
        for ((k, v) in headers) {
            if (k.equals(name, ignoreCase = true)) return v
        }
        return null
    }

    private data class Session(
        val id: String,
        val protocolVersion: String,
        @Volatile var initialized: Boolean,
        @Volatile var lastSeenMs: Long,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun closeSession(headers: Map<String, String>): Boolean {
        val sid = getHeader(headers, HEADER_SESSION_ID) ?: return false
        return sessions.remove(sid) != null
    }

    suspend fun handle(
        request: JsonObject,
        headers: Map<String, String>,
    ): McpHttpResponse {
        if (!JsonRpc.isJsonRpc20(request)) {
            throw JsonRpcException.invalidRequest("Expected jsonrpc=2.0")
        }

        if (JsonRpc.isResponse(request)) {
            return McpHttpResponse(status = 202)
        }

        val id = JsonRpc.idOrNull(request)
        if (!JsonRpc.hasMethod(request)) {
            throw JsonRpcException.invalidRequest("Missing method")
        }
        val method = JsonRpc.method(request)

        if (id == null) {
            handleNotification(method, request, headers)
            return McpHttpResponse(status = 202)
        }

        val (result, extraHeaders) = handleRequest(method, request, headers)
        return McpHttpResponse(
            status = 200,
            headers = extraHeaders,
            body = JsonRpc.result(id, result),
        )
    }

    private fun handleNotification(method: String, request: JsonObject, headers: Map<String, String>) {
        when (method) {
            "notifications/initialized" -> {
                val session = requireSession(headers)
                session.initialized = true
                session.lastSeenMs = System.currentTimeMillis()
            }
        }
    }

    private suspend fun handleRequest(method: String, request: JsonObject, headers: Map<String, String>): Pair<JsonElement, Map<String, String>> {
        return when (method) {
            "initialize" -> {
                val (result, sessionId, protocolVersion) = handleInitialize(request)
                val outHeaders = buildMap {
                    put(HEADER_SESSION_ID, sessionId)
                }
                result to outHeaders
            }

            "ping" -> {
                val session = requireSession(headers)
                session.lastSeenMs = System.currentTimeMillis()
                buildJsonObject { } to emptyMap()
            }

            "tools/list" -> {
                val session = requireInitializedSession(headers)
                session.lastSeenMs = System.currentTimeMillis()
                handleToolsList() to emptyMap()
            }

            "tools/call" -> {
                val session = requireInitializedSession(headers)
                session.lastSeenMs = System.currentTimeMillis()
                handleToolsCall(request) to emptyMap()
            }

            else -> throw JsonRpcException.methodNotFound("Unknown method: $method")
        }
    }

    private fun handleInitialize(request: JsonObject): Triple<JsonElement, String, String> {
        val params = request["params"] as? JsonObject ?: throw JsonRpcException.invalidParams("Missing params")
        val requestedVersion = (params["protocolVersion"] as? JsonPrimitive)?.content
            ?: throw JsonRpcException.invalidParams("Missing params.protocolVersion")

        val negotiated = if (supportedProtocolVersions.contains(requestedVersion)) {
            requestedVersion
        } else {
            supportedProtocolVersions.first()
        }

        val sessionId = newSession(negotiated)

        val serverCapabilities = buildJsonObject {
            put(
                "tools",
                buildJsonObject {
                    put("listChanged", JsonPrimitive(false))
                },
            )
        }

        val serverInfo = buildJsonObject {
            put("name", JsonPrimitive("ctrl-phone"))
            put("title", JsonPrimitive("CTRL Phone"))
            put("version", JsonPrimitive("1.0"))
            put("description", JsonPrimitive("Phone control MCP server"))
        }

        val result = buildJsonObject {
            put("protocolVersion", JsonPrimitive(negotiated))
            put("capabilities", serverCapabilities)
            put("serverInfo", serverInfo)
            put(
                "instructions",
                JsonPrimitive(
                    "This server controls the phone. Use tools/list then tools/call. " +
                        "Coordinates are in screen pixels relative to the captured screenshot.",
                ),
            )
        }

        return Triple(result, sessionId, negotiated)
    }

    private fun handleToolsList(): JsonElement {
        val tools = buildJsonArray {
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("device.display_info"))
                    put("description", JsonPrimitive("Get display size, density, and rotation"))
                    put("inputSchema", emptyObjectSchema)
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("screen.capture"))
                    put("description", JsonPrimitive("Capture a PNG screenshot"))
                    put("inputSchema", emptyObjectSchema)
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("app.launch"))
                    put("description", JsonPrimitive("Launch an app by package name"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "packageName",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("packageName")) })
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("app.is_installed"))
                    put("description", JsonPrimitive("Check if an app package is installed"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "packageName",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("packageName")) })
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("app.list_launchable"))
                    put("description", JsonPrimitive("List launchable app package names"))
                    put("inputSchema", emptyObjectSchema)
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("app.list_installed"))
                    put("description", JsonPrimitive("List installed apps (optionally filter by query)"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "query",
                                        buildJsonObject { put("type", JsonPrimitive("string")) },
                                    )
                                    put(
                                        "includeSystem",
                                        buildJsonObject { put("type", JsonPrimitive("boolean")) },
                                    )
                                },
                            )
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("device.current_app"))
                    put("description", JsonPrimitive("Get current foreground app package (from accessibility)"))
                    put("inputSchema", emptyObjectSchema)
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("ui.tree"))
                    put("description", JsonPrimitive("Get UI element tree with positions and text"))
                    put("inputSchema", emptyObjectSchema)
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("ui.find"))
                    put("description", JsonPrimitive("Find UI elements by selector"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put("text", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("contentDescription", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("viewId", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("className", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("packageName", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("clickable", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                    put("editable", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                    put("enabled", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                    put("limit", buildJsonObject { put("type", JsonPrimitive("integer")) })
                                },
                            )
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("ui.click"))
                    put("description", JsonPrimitive("Click a UI element by selector"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put("text", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("contentDescription", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("viewId", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("className", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("packageName", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    put("clickable", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                    put("editable", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                    put("enabled", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                    put("index", buildJsonObject { put("type", JsonPrimitive("integer")) })
                                },
                            )
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("input.tap"))
                    put("description", JsonPrimitive("Tap a point on screen (pixel coordinates)"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "x",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                            put("minimum", JsonPrimitive(0))
                                        },
                                    )
                                    put(
                                        "y",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                            put("minimum", JsonPrimitive(0))
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) })
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("input.longPress"))
                    put("description", JsonPrimitive("Long press a point on screen"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "x",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                            put("minimum", JsonPrimitive(0))
                                        },
                                    )
                                    put(
                                        "y",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                            put("minimum", JsonPrimitive(0))
                                        },
                                    )
                                    put(
                                        "durationMs",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                            put("minimum", JsonPrimitive(100))
                                            put("default", JsonPrimitive(500))
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) })
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("input.swipe"))
                    put("description", JsonPrimitive("Swipe from one point to another"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put("x1", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(0)) })
                                    put("y1", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(0)) })
                                    put("x2", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(0)) })
                                    put("y2", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(0)) })
                                    put(
                                        "durationMs",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                            put("minimum", JsonPrimitive(100))
                                            put("default", JsonPrimitive(300))
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { 
                                add(JsonPrimitive("x1")); add(JsonPrimitive("y1"))
                                add(JsonPrimitive("x2")); add(JsonPrimitive("y2"))
                            })
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("input.key"))
                    put("description", JsonPrimitive("Press a system key (back, home, recents)"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "key",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put("enum", buildJsonArray { 
                                                add(JsonPrimitive("back"))
                                                add(JsonPrimitive("home"))
                                                add(JsonPrimitive("recents"))
                                            })
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("key")) })
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", JsonPrimitive("input.text"))
                    put("description", JsonPrimitive("Set text in the focused input field"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "text",
                                        buildJsonObject { put("type", JsonPrimitive("string")) },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("text")) })
                            put("additionalProperties", JsonPrimitive(false))
                        },
                    )
                },
            )
        }

        return buildJsonObject {
            put("tools", tools)
            // nextCursor omitted when null (SDK expects string or omitted, not null)
        }
    }

    private suspend fun handleToolsCall(request: JsonObject): JsonElement {
        val params = request["params"] as? JsonObject ?: throw JsonRpcException.invalidParams("Missing params")
        val name = (params["name"] as? JsonPrimitive)?.content
            ?: throw JsonRpcException.invalidParams("Missing params.name")
        val args = params["arguments"] as? JsonObject

        return when (name) {
            "device.display_info" -> Tools.deviceDisplayInfo(appContext)
            "screen.capture" -> Tools.screenCapture(appContext)
            "app.launch" -> {
                val pkg = (args?.get("packageName") as? JsonPrimitive)?.content
                    ?: throw JsonRpcException.invalidParams("Missing arguments.packageName")
                Tools.appLaunch(appContext, pkg)
            }
            "app.is_installed" -> {
                val pkg = (args?.get("packageName") as? JsonPrimitive)?.content
                    ?: throw JsonRpcException.invalidParams("Missing arguments.packageName")
                Tools.appIsInstalled(appContext, pkg)
            }
            "app.list_launchable" -> Tools.appListLaunchable(appContext)
            "app.list_installed" -> {
                val query = (args?.get("query") as? JsonPrimitive)?.content
                val includeSystem = (args?.get("includeSystem") as? JsonPrimitive)?.content
                    ?.equals("true", ignoreCase = true) ?: false
                Tools.appListInstalled(appContext, includeSystem, query)
            }
            "device.current_app" -> Tools.currentApp()
            "ui.tree" -> Tools.uiTree()
            "ui.find" -> {
                val selector = selectorFromArgs(args)
                val limit = (args?.get("limit") as? JsonPrimitive)?.content?.toIntOrNull() ?: 20
                Tools.uiFind(selector, limit)
            }
            "ui.click" -> {
                val selector = selectorFromArgs(args)
                val index = (args?.get("index") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                Tools.uiClick(selector, index)
            }
            "input.tap" -> {
                val x = (args?.get("x") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.x")
                val y = (args?.get("y") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.y")
                Tools.inputTap(appContext, x, y)
            }
            "input.longPress" -> {
                val x = (args?.get("x") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.x")
                val y = (args?.get("y") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.y")
                val duration = (args?.get("durationMs") as? JsonPrimitive)?.content?.toLongOrNull() ?: 500
                Tools.inputLongPress(appContext, x, y, duration)
            }
            "input.swipe" -> {
                val x1 = (args?.get("x1") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.x1")
                val y1 = (args?.get("y1") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.y1")
                val x2 = (args?.get("x2") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.x2")
                val y2 = (args?.get("y2") as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw JsonRpcException.invalidParams("Missing arguments.y2")
                val duration = (args?.get("durationMs") as? JsonPrimitive)?.content?.toLongOrNull() ?: 300
                Tools.inputSwipe(appContext, x1, y1, x2, y2, duration)
            }
            "input.key" -> {
                val key = (args?.get("key") as? JsonPrimitive)?.content
                    ?: throw JsonRpcException.invalidParams("Missing arguments.key")
                Tools.inputKey(appContext, key)
            }
            "input.text" -> {
                val text = (args?.get("text") as? JsonPrimitive)?.content
                    ?: throw JsonRpcException.invalidParams("Missing arguments.text")
                Tools.inputText(text)
            }
            else -> throw JsonRpcException.invalidParams("Unknown tool: $name")
        }
    }

    private fun selectorFromArgs(args: JsonObject?): UiSelector {
        val text = (args?.get("text") as? JsonPrimitive)?.content
        val contentDescription = (args?.get("contentDescription") as? JsonPrimitive)?.content
        val viewId = (args?.get("viewId") as? JsonPrimitive)?.content
        val className = (args?.get("className") as? JsonPrimitive)?.content
        val packageName = (args?.get("packageName") as? JsonPrimitive)?.content
        val clickable = (args?.get("clickable") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        val editable = (args?.get("editable") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        val enabled = (args?.get("enabled") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        return UiSelector(
            text = text,
            contentDescription = contentDescription,
            viewId = viewId,
            className = className,
            packageName = packageName,
            clickable = clickable,
            editable = editable,
            enabled = enabled,
        )
    }

    private fun newSession(protocolVersion: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        sessions[id] = Session(id = id, protocolVersion = protocolVersion, initialized = false, lastSeenMs = now)
        return id
    }

    private fun requireSession(headers: Map<String, String>): Session {
        val sid = getHeader(headers, HEADER_SESSION_ID)
            ?: throw JsonRpcException.invalidRequest("Missing mcp-session-id header")
        val session = sessions[sid] ?: throw JsonRpcException(-32001, "Session not found")

        // Protocol version header is optional for clients that don't send it
        val proto = getHeader(headers, HEADER_PROTOCOL_VERSION)
        if (proto != null && proto != session.protocolVersion) {
            throw JsonRpcException.invalidRequest("Unsupported mcp-protocol-version")
        }

        return session
    }

    private fun requireInitializedSession(headers: Map<String, String>): Session {
        val session = requireSession(headers)
        if (!session.initialized) {
            throw JsonRpcException.invalidRequest("Server not initialized")
        }
        return session
    }
}

private object Tools {
    fun deviceDisplayInfo(context: Context): JsonElement {
        val info = DisplayInfo.fromContext(context)
        return callToolResult(
            content = listOf(
                textContent(
                    "{" +
                        "\"widthPx\":${info.widthPx}," +
                        "\"heightPx\":${info.heightPx}," +
                        "\"densityDpi\":${info.densityDpi}," +
                        "\"rotation\":${info.rotation}" +
                        "}",
                ),
            ),
            structured = buildJsonObject {
                put("widthPx", JsonPrimitive(info.widthPx))
                put("heightPx", JsonPrimitive(info.heightPx))
                put("densityDpi", JsonPrimitive(info.densityDpi))
                put("rotation", JsonPrimitive(info.rotation))
            },
            isError = false,
        )
    }

    suspend fun screenCapture(context: Context): JsonElement {
        val info = DisplayInfo.fromContext(context)
        val capture = ScreenCaptureManager.capturePngBase64(timeoutMs = 1500)
        return if (capture == null) {
            callToolResult(
                content = listOf(textContent("Screen capture not enabled. Open the app and enable screen capture.")),
                structured = null,
                isError = true,
            )
        } else {
            callToolResult(
                content = listOf(
                    imageContent(capture, "image/png"),
                    textContent("Captured ${info.widthPx}x${info.heightPx} rotation=${info.rotation}"),
                ),
                structured = buildJsonObject {
                    put("widthPx", JsonPrimitive(info.widthPx))
                    put("heightPx", JsonPrimitive(info.heightPx))
                    put("rotation", JsonPrimitive(info.rotation))
                },
                isError = false,
            )
        }
    }

    fun uiTree(): JsonElement {
        val root = CtrlAccessibilityService.getRootNode()
        if (root == null) {
            return callToolResult(
                content = listOf(textContent("Accessibility service not enabled. Enable CTRL Accessibility Service to get UI tree.")),
                structured = null,
                isError = true,
            )
        }

        val elements = dumpUiTree(root)
        val jsonElements = elements.map { it.toJson() }

        return callToolResult(
            content = listOf(textContent("UI tree captured with ${elements.size} root elements")),
            structured = buildJsonObject {
                put("elements", buildJsonArray { jsonElements.forEach { add(it) } })
            },
            isError = false,
        )
    }

    fun uiFind(selector: UiSelector, limit: Int): JsonElement {
        val root = CtrlAccessibilityService.getRootNode()
        if (root == null) {
            return callToolResult(
                content = listOf(textContent("Accessibility service not enabled.")),
                structured = null,
                isError = true,
            )
        }

        val nodes = findNodesBySelector(root, selector, limit.coerceIn(1, 200))
        val elements = nodes.map { it.toUiElementInfo().toJson() }
        return callToolResult(
            content = listOf(textContent("Found ${elements.size} elements")),
            structured = buildJsonObject {
                put("elements", buildJsonArray { elements.forEach { add(it) } })
            },
            isError = false,
        )
    }

    fun uiClick(selector: UiSelector, index: Int): JsonElement {
        val root = CtrlAccessibilityService.getRootNode()
        if (root == null) {
            return callToolResult(
                content = listOf(textContent("Accessibility service not enabled.")),
                structured = null,
                isError = true,
            )
        }
        val nodes = findNodesBySelector(root, selector, 50)
        if (nodes.isEmpty() || index !in nodes.indices) {
            return callToolResult(
                content = listOf(textContent("No matching element for selector")),
                structured = null,
                isError = true,
            )
        }
        val ok = nodes[index].performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) {
            callToolResult(
                content = listOf(textContent("Clicked element")),
                structured = null,
                isError = false,
            )
        } else {
            callToolResult(
                content = listOf(textContent("Failed to click element")),
                structured = null,
                isError = true,
            )
        }
    }

    fun appLaunch(context: Context, packageName: String): JsonElement {
        val ok = AppController.launch(context, packageName)
        return if (!ok) {
            callToolResult(
                content = listOf(textContent("Unable to launch $packageName. Is it installed?")),
                structured = null,
                isError = true,
            )
        } else {
            callToolResult(
                content = listOf(textContent("Launched $packageName")),
                structured = null,
                isError = false,
            )
        }
    }

    fun appIsInstalled(context: Context, packageName: String): JsonElement {
        val installed = AppController.isInstalled(context, packageName)
        return callToolResult(
            content = listOf(textContent("$packageName installed=$installed")),
            structured = buildJsonObject {
                put("packageName", JsonPrimitive(packageName))
                put("installed", JsonPrimitive(installed))
            },
            isError = false,
        )
    }

    fun appListLaunchable(context: Context): JsonElement {
        val packages = AppController.listLaunchablePackages(context)
        return callToolResult(
            content = listOf(textContent("${packages.size} launchable packages")),
            structured = buildJsonObject {
                put("packages", buildJsonArray { packages.forEach { add(JsonPrimitive(it)) } })
            },
            isError = false,
        )
    }

    fun appListInstalled(context: Context, includeSystem: Boolean, query: String?): JsonElement {
        val apps = AppController.listInstalledApps(context, includeSystem, query)
        return callToolResult(
            content = listOf(textContent("${apps.size} installed apps")),
            structured = buildJsonObject {
                put(
                    "apps",
                    buildJsonArray {
                        apps.forEach { app ->
                            add(
                                buildJsonObject {
                                    put("packageName", JsonPrimitive(app.packageName))
                                    put("label", JsonPrimitive(app.label))
                                    put("isSystem", JsonPrimitive(app.isSystem))
                                },
                            )
                        }
                    },
                )
            },
            isError = false,
        )
    }

    fun currentApp(): JsonElement {
        val root = CtrlAccessibilityService.getRootNode()
        val pkg = root?.packageName?.toString()
        return callToolResult(
            content = listOf(textContent("currentApp=${pkg ?: "unknown"}")),
            structured = buildJsonObject {
                put("packageName", pkg?.let { JsonPrimitive(it) } ?: JsonNull)
            },
            isError = pkg == null,
        )
    }

    fun inputTap(context: Context, x: Int, y: Int): JsonElement {
        val info = DisplayInfo.fromContext(context)
        if (x < 0 || y < 0 || x >= info.widthPx || y >= info.heightPx) {
            return callToolResult(
                content = listOf(textContent("Tap out of bounds: ($x,$y) for ${info.widthPx}x${info.heightPx}")),
                structured = null,
                isError = true,
            )
        }

        val ok = InputController.tap(x.toFloat(), y.toFloat())
        return if (!ok) {
            callToolResult(
                content = listOf(textContent("Input control not enabled. Enable the CTRL Accessibility Service.")),
                structured = null,
                isError = true,
            )
        } else {
            callToolResult(
                content = listOf(textContent("Tapped ($x,$y)")),
                structured = null,
                isError = false,
            )
        }
    }

    fun inputLongPress(context: Context, x: Int, y: Int, durationMs: Long): JsonElement {
        val info = DisplayInfo.fromContext(context)
        if (x < 0 || y < 0 || x >= info.widthPx || y >= info.heightPx) {
            return callToolResult(
                content = listOf(textContent("Long press out of bounds: ($x,$y) for ${info.widthPx}x${info.heightPx}")),
                structured = null,
                isError = true,
            )
        }

        val ok = InputController.longPress(x.toFloat(), y.toFloat(), durationMs)
        return if (!ok) {
            callToolResult(
                content = listOf(textContent("Input control not enabled. Enable the CTRL Accessibility Service.")),
                structured = null,
                isError = true,
            )
        } else {
            callToolResult(
                content = listOf(textContent("Long pressed ($x,$y) for ${durationMs}ms")),
                structured = null,
                isError = false,
            )
        }
    }

    fun inputSwipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): JsonElement {
        val info = DisplayInfo.fromContext(context)
        if (x1 < 0 || y1 < 0 || x1 >= info.widthPx || y1 >= info.heightPx ||
            x2 < 0 || y2 < 0 || x2 >= info.widthPx || y2 >= info.heightPx) {
            return callToolResult(
                content = listOf(textContent("Swipe coordinates out of bounds")),
                structured = null,
                isError = true,
            )
        }

        val ok = InputController.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
        return if (!ok) {
            callToolResult(
                content = listOf(textContent("Input control not enabled. Enable the CTRL Accessibility Service.")),
                structured = null,
                isError = true,
            )
        } else {
            callToolResult(
                content = listOf(textContent("Swiped from ($x1,$y1) to ($x2,$y2) in ${durationMs}ms")),
                structured = null,
                isError = false,
            )
        }
    }

    fun inputKey(context: Context, key: String): JsonElement {
        val ok = when (key) {
            "back" -> InputController.globalBack()
            "home" -> InputController.globalHome()
            "recents" -> InputController.globalRecents()
            else -> return callToolResult(
                content = listOf(textContent("Unknown key: $key. Use back, home, or recents.")),
                structured = null,
                isError = true,
            )
        }
        
        return if (!ok) {
            callToolResult(
                content = listOf(textContent("Input control not enabled. Enable the CTRL Accessibility Service.")),
                structured = null,
                isError = true,
            )
        } else {
            callToolResult(
                content = listOf(textContent("Pressed $key key")),
                structured = null,
                isError = false,
            )
        }
    }

    fun inputText(text: String): JsonElement {
        val ok = InputController.setText(text)
        return if (!ok) {
            callToolResult(
                content = listOf(textContent("Unable to set text. Focus an input field first.")),
                structured = null,
                isError = true,
            )
        } else {
            callToolResult(
                content = listOf(textContent("Text set")),
                structured = null,
                isError = false,
            )
        }
    }

    private fun callToolResult(
        content: List<JsonObject>,
        structured: JsonObject?,
        isError: Boolean,
    ): JsonElement {
        return buildJsonObject {
            put("content", buildJsonArray { content.forEach { add(it) } })
            if (structured != null) put("structuredContent", structured)
            put("isError", JsonPrimitive(isError))
        }
    }

    private fun textContent(text: String): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(text))
        }

    private fun imageContent(base64: String, mimeType: String): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("data", JsonPrimitive(base64))
            put("mimeType", JsonPrimitive(mimeType))
        }
}

// Helper extension to convert UI element to JSON
private fun com.example.ctrl.input.UiElementInfo.toJson(): JsonObject {
    return buildJsonObject {
        put("text", text?.let { JsonPrimitive(it) } ?: JsonNull)
        put("contentDescription", contentDescription?.let { JsonPrimitive(it) } ?: JsonNull)
        put("viewId", viewId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("className", className?.let { JsonPrimitive(it) } ?: JsonNull)
        put("packageName", packageName?.let { JsonPrimitive(it) } ?: JsonNull)
        put("clickable", JsonPrimitive(clickable))
        put("editable", JsonPrimitive(editable))
        put("focused", JsonPrimitive(focused))
        put("enabled", JsonPrimitive(enabled))
        put("bounds", buildJsonObject {
            put("left", JsonPrimitive(bounds.left))
            put("top", JsonPrimitive(bounds.top))
            put("right", JsonPrimitive(bounds.right))
            put("bottom", JsonPrimitive(bounds.bottom))
            put("centerX", JsonPrimitive((bounds.left + bounds.right) / 2))
            put("centerY", JsonPrimitive((bounds.top + bounds.bottom) / 2))
        })
        put("children", buildJsonArray { children.forEach { add(it.toJson()) } })
    }
}
