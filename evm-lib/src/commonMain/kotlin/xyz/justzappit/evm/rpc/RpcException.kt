// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import xyz.justzappit.evm.abi.Selector4

sealed class RpcException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    abstract val method: String

    /** The JSON-RPC server returned `error.code == 3` (or vendor variant) with revert data. */
    class ExecutionReverted(
        override val method: String,
        val selector: Selector4?,
        val data: ByteArray,
        val solidityErrorString: String?,
        rawMessage: String,
    ) : RpcException(
            buildString {
                append("RPC ").append(method).append(" execution reverted")
                solidityErrorString?.let { append(": ").append(it) }
                    ?: selector?.let { append(" with selector ").append(it.hex) }
                    ?: rawMessage.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            },
        )

    class MethodNotFound(
        override val method: String
    ) : RpcException("RPC method not found: $method")

    class InvalidParams(
        override val method: String,
        val reason: String
    ) : RpcException("RPC invalid params for $method: $reason")

    /** HTTP 429 surfaced into the typed layer; cause carries the original ktor exception. */
    class RateLimited(
        override val method: String,
        val retryAfterMillis: Long?,
        cause: Throwable? = null
    ) : RpcException("RPC $method rate limited (retry after ${retryAfterMillis ?: "?"} ms)", cause)

    class TransportError(
        override val method: String,
        cause: Throwable
    ) : RpcException("RPC $method transport error: ${cause.message}", cause)

    /** Any JSON-RPC error code we do not specifically classify. */
    class Unknown(
        override val method: String,
        val code: Int?,
        val raw: String,
        val errorMessage: String?,
    ) : RpcException(
            buildString {
                append("RPC ").append(method)
                if (code != null) append(" failed with code=").append(code)
                if (!errorMessage.isNullOrBlank()) append(": ").append(errorMessage)
            },
        )
}
