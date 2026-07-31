package co.electriccoin.zcash.ui.common.serialization

import java.nio.ByteOrder

internal const val ADDRESS_BOOK_SERIALIZATION_V1 = 1
internal const val ADDRESS_BOOK_SERIALIZATION_V2 = 2

// Fork-added versions live in a 1000+ band so upstream (currently at V2, owns the small-int
// namespace of this format) can ship its own V3/V4/... without ever colliding with files
// written by this fork.
internal const val ADDRESS_BOOK_SERIALIZATION_ZAPP_V3 = 1003
internal const val ADDRESS_BOOK_SERIALIZATION_ZAPP_V4 = 1004
internal const val ADDRESS_BOOK_ENCRYPTION_V1 = 1
internal const val ADDRESS_BOOK_ENCRYPTION_KEY_SIZE = 32
internal const val ADDRESS_BOOK_FILE_IDENTIFIER_SIZE = 32
internal const val ADDRESS_BOOK_SALT_SIZE = 32
internal val ADDRESS_BOOK_BYTE_ORDER = ByteOrder.BIG_ENDIAN

/**
 * V1 and V2 did not need migration because we only added new fields which is perfectly well handled by
 * kotlinx.serialization.
 */
internal const val METADATA_SERIALIZATION_V1_V2 = 1
internal const val METADATA_SERIALIZATION_V3 = 3
internal const val METADATA_ENCRYPTION_V1 = 1
internal const val METADATA_SALT_SIZE = 32
internal const val METADATA_ENCRYPTION_KEY_SIZE = 32
internal const val METADATA_FILE_IDENTIFIER_SIZE = 32
