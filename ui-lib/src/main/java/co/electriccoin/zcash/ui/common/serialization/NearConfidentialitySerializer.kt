package co.electriccoin.zcash.ui.common.serialization

import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

object NearConfidentialitySerializer : KSerializer<Confidentiality?> {
    override val descriptor = PrimitiveSerialDescriptor("Confidentiality", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Confidentiality?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.apiValue)
    }

    // Every swap echoes this field, so anything unexpected, a JSON null included, is null rather than a
    // failed quote.
    override fun deserialize(decoder: Decoder): Confidentiality? {
        val decoded =
            if (decoder is JsonDecoder) {
                (decoder.decodeJsonElement() as? JsonPrimitive)?.takeIf { it.isString }?.content
            } else {
                decoder.decodeString()
            }
        return Confidentiality
            .entries
            .firstOrNull { it.apiValue == decoded }
    }
}
