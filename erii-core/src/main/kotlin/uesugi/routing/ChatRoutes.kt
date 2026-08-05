package uesugi.routing

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.ktor.ext.inject
import uesugi.common.toolkit.logger
import uesugi.core.bot.BotRoleManager
import uesugi.core.chat.ChatBridge
import uesugi.core.chat.ChatHistoryEntry
import uesugi.core.message.resource.ThumbnailService

@Serializable
data class ChatSendResponse(
    val requestId: String? = null,
    val response: String? = null,
    val error: String? = null
)

@Serializable
data class ChatHealthResponse(
    val status: String,
    val mockBotReady: Boolean,
    val roleSelected: Boolean
)

@Serializable
data class ChatRoleDTO(
    val id: String,
    val name: String,
    val character: String,
    val emoticon: String
)

@Serializable
data class ChatSelectRoleRequest(val roleId: String)

@Serializable
data class ChatSelectRoleResponse(
    val success: Boolean,
    val error: String? = null,
    val role: ChatRoleDTO? = null
)

@Serializable
data class ChatHistoryResponse(
    val entries: List<ChatHistoryEntry>,
    val hasMore: Boolean
)

fun Routing.configureChatRoutes() {
    val chatBridge by inject<ChatBridge>()
    val thumbnailService by inject<ThumbnailService>()

    authenticate("basic") {
        get("/api/chat/roles") {
            val roles = BotRoleManager.getAllRoles().map { (_, role) ->
                ChatRoleDTO(
                    id = role.id,
                    name = role.name,
                    character = role.character,
                    emoticon = role.emoticon.name
                )
            }
            call.respond(roles)
        }

        post("/api/chat/select-role") {
            val request = call.receive<ChatSelectRoleRequest>()
            val role = BotRoleManager.getRole(request.roleId)
            if (role == null) {
                call.respond(
                    ChatSelectRoleResponse(
                        success = false,
                        error = "Role not found: ${request.roleId}"
                    )
                )
                return@post
            }

            chatBridge.selectRole(request.roleId)

            call.respond(
                ChatSelectRoleResponse(
                    success = true,
                    role = ChatRoleDTO(
                        id = role.id,
                        name = role.name,
                        character = role.character,
                        emoticon = role.emoticon.name
                    )
                )
            )
        }

        get("/api/chat/history") {
            val beforeId = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?: ChatBridge.HISTORY_LIMIT
            val result = chatBridge.getHistory(beforeId, limit)
            call.respond(ChatHistoryResponse(entries = result.entries, hasMore = result.hasMore))
        }

        get("/api/chat/history/{id}/image") {
            val historyId = call.parameters["id"]?.toIntOrNull()
            if (historyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid history id"))
                return@get
            }

            val resource = chatBridge.getHistoryImage(historyId)
            if (resource == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "chat image not found"))
                return@get
            }

            val bytes = thumbnailService.getThumbnail(resource)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "chat image not available"))
                return@get
            }

            call.response.header(HttpHeaders.ETag, "\"${resource.md5}\"")
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }

        get("/api/chat/health") {
            call.respond(
                ChatHealthResponse(
                    status = "ok",
                    mockBotReady = chatBridge.isReady(),
                    roleSelected = chatBridge.isRoleSelected()
                )
            )
        }

        webSocket("/api/chat/ws") {
            chatBridge.wsResponseCallback = { requestId, responseText ->
                try {
                    send(
                        Frame.Text(
                            Json.encodeToString(
                                ChatSendResponse.serializer(),
                                ChatSendResponse(requestId = requestId, response = responseText)
                            )
                        )
                    )
                } catch (e: Exception) {
                    log.warn("ChatRoutes: failed to send WS response: ${e.message}")
                }
            }

            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val json = try {
                        Json.parseToJsonElement(text) as JsonObject
                    } catch (_: Exception) {
                        continue
                    }
                    val requestId = json["requestId"]?.jsonPrimitive?.content
                    val message = json["content"]?.jsonPrimitive?.content ?: continue
                    try {
                        chatBridge.sendMessage(requestId, message)
                    } catch (e: Exception) {
                        send(
                            Frame.Text(
                                Json.encodeToString(
                                    ChatSendResponse.serializer(),
                                ChatSendResponse(requestId = requestId, error = e.message ?: "Unknown error")
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

private val log = logger("ChatRoutes")
