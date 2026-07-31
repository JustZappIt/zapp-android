package co.electriccoin.zcash.ui.common.push

/** Strict validation for the blind-push gateway's contentless FCM envelope. */
internal object ChatDoorbellValidator {
    private val topicPattern = Regex("^[0-9a-f]{64}$")
    private val base64Pattern = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val expectedKeys = setOf("title", "body", "payload")

    fun validate(
        from: String?,
        data: Map<String, String>,
    ): String? {
        val topic = from?.takeIf { it.startsWith(TOPIC_PREFIX) }?.removePrefix(TOPIC_PREFIX)
        val title = data["title"]
        val body = data["body"]
        val payload = data["payload"]
        val hasValidTitle = title != null && title.isNotBlank() && title.length <= MAX_TITLE_LENGTH
        val hasValidBody = body != null && body.isNotBlank() && body.length <= MAX_BODY_LENGTH
        val hasValidPayloadLength =
            payload?.length?.let { it in MIN_PAYLOAD_LENGTH..MAX_PAYLOAD_LENGTH } == true
        val hasValidPayloadEncoding =
            payload != null && payload.length % BASE64_QUANTUM == 0 && base64Pattern.matches(payload)
        val isValid =
            listOf(
                data.keys == expectedKeys,
                topic?.let(topicPattern::matches) == true,
                hasValidTitle,
                hasValidBody,
                hasValidPayloadLength,
                hasValidPayloadEncoding,
            ).all { it }
        return topic?.takeIf { isValid }
    }

    private const val TOPIC_PREFIX = "/topics/"
    private const val MIN_PAYLOAD_LENGTH = 32
    private const val MAX_PAYLOAD_LENGTH = 1_960
    private const val MAX_TITLE_LENGTH = 128
    private const val MAX_BODY_LENGTH = 256
    private const val BASE64_QUANTUM = 4
}
