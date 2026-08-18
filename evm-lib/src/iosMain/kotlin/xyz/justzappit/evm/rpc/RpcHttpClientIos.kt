// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun createDefaultRpcHttpClient(config: RpcHttpClient.Config): HttpClient =
    HttpClient(Darwin) { with(RpcHttpClient) { applyRpcDefaults(config) } }
