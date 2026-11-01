package io.github.marcomow.yuvomi.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One JSON configuration for every upstream answer (ADR 0003): unknown fields are
 * ignored, nulls for non-null properties fall back to their defaults.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = false
}

/**
 * Upstream stores flags as SQLite integers (`locked: 1`) in some answers and as
 * JSON booleans in others (`tasks_default_locked: true`). This reads all of
 * `true`, `false`, `1`, `0`, `"1"`, `"true"` and writes a JSON boolean, which the
 * server accepts everywhere (`req.body.locked ? 1 : 0`).
 */
object FlexBoolSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexBool", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        val primitive = element as? JsonPrimitive ?: return false
        primitive.booleanOrNull?.let { return it }
        primitive.doubleOrNull?.let { return it != 0.0 }
        return primitive.content.equals("true", ignoreCase = true) || primitive.content == "1"
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

typealias FlexBool = @kotlinx.serialization.Serializable(with = FlexBoolSerializer::class) Boolean
