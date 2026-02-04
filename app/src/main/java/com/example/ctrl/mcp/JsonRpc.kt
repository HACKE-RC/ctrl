package com.example.ctrl.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object JsonRpc {
    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun parseObject(body: String): JsonObject {
        val element = json.parseToJsonElement(body)
        return element as? JsonObject ?: throw JsonRpcException.invalidRequest("Body must be a JSON object")
    }

    fun isJsonRpc20(obj: JsonObject): Boolean = obj["jsonrpc"]?.let {
        (it as? JsonPrimitive)?.content == "2.0"
    } ?: false

    fun hasMethod(obj: JsonObject): Boolean = obj["method"] is JsonPrimitive

    fun method(obj: JsonObject): String =
        (obj["method"] as? JsonPrimitive)?.content
            ?: throw JsonRpcException.invalidRequest("Missing method")

    fun idOrNull(obj: JsonObject): JsonElement? {
        val id = obj["id"] ?: return null
        // JSON-RPC allows string|number|null; we treat null as notification-like.
        if (id is JsonNull) return null
        return id
    }

    fun isResponse(obj: JsonObject): Boolean {
        val hasId = obj.containsKey("id")
        val hasResult = obj.containsKey("result")
        val hasError = obj.containsKey("error")
        return hasId && (hasResult || hasError) && !obj.containsKey("method")
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject =
        buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id ?: JsonNull)
            put(
                "error",
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("message", JsonPrimitive(message))
                    if (data != null) put("data", data)
                },
            )
        }

    fun result(id: JsonElement, result: JsonElement): JsonObject =
        buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put("result", result)
        }
}

class JsonRpcException(val code: Int, override val message: String, val data: JsonElement? = null) :
    IllegalArgumentException(message) {
    companion object {
        fun parseError(msg: String) = JsonRpcException(-32700, msg)
        fun invalidRequest(msg: String) = JsonRpcException(-32600, msg)
        fun methodNotFound(msg: String) = JsonRpcException(-32601, msg)
        fun invalidParams(msg: String) = JsonRpcException(-32602, msg)
        fun internalError(msg: String) = JsonRpcException(-32603, msg)
    }
}
