package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Port of iOS `VoiceTestSupport.swift`: shared voice test helpers. */

/** The JSON body of a voice handoff (the line after the first newline). */
fun handoffBody(text: String): Map<String, RealtimeJSON>? {
    val json = text.split("\n", limit = 2).lastOrNull() ?: return null
    return runCatching { Json.parseToJsonElement(json).jsonObject as JsonObject }.getOrNull()
}

/** Holds an async dependency open per key until the test resolves it. */
class Cue<Value>(keys: List<String>) {
    private val channels: Map<String, Channel<Result<Value>>> =
        keys.associateWith { Channel(Channel.UNLIMITED) }

    suspend fun wait(key: String): Value {
        val channel = channels[key] ?: throw OpenCodeClientError(message = "No answer for $key")
        return channel.receive().getOrThrow()
    }

    fun resolve(key: String, value: Value) {
        channels[key]?.trySend(Result.success(value))
    }

    fun fail(key: String, message: String) {
        channels[key]?.trySend(Result.failure(OpenCodeClientError(message = message)))
    }
}
