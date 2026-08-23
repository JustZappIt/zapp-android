// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import android.app.Application
import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/**
 * Puts the Sapling proving parameters on disk before a spend needs them.
 *
 * Every transaction needs them, whatever pool it spends from: `TransactionEncoderImpl` calls
 * `forceDownload()` unconditionally, and the Rust builder then opens the files regardless (the
 * pool-conditional path is commented out upstream pending librustzcash #1724). They are ~51MB, so
 * they are fetched on first spend rather than shipped in the APK.
 *
 * The SDK's own opportunistic fetch is no help to a gift recipient: it only runs for a wallet that
 * already holds a Sapling or transparent balance, and a recipient holds neither — an empty wallet,
 * then an Ironwood note. Their first spend is the claim, so without this the 51MB download lands at
 * the worst possible moment: after a scan has already found money they were promised, on a card
 * with no reclaim.
 *
 * Best effort throughout. Failing here costs nothing that was not already going to be paid at
 * claim time.
 */
interface ProvingParamsProvider {
    /** Downloads what is missing, unless that is already underway. Returns at once. */
    fun prefetch()
}

class ProvingParamsProviderImpl(
    private val application: Application,
) : ProvingParamsProvider {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val mutex = Mutex()

    // Application-scoped on purpose: the claim screen that starts this is popped the moment the
    // recipient leaves to make a wallet, and a download tied to its view model would die with it.
    private var job: Job? = null

    override fun prefetch() {
        if (job?.isActive == true) return
        job =
            scope.launch {
                mutex.withLock { PARAMS.forEach { ensure(it) } }
            }
    }

    /**
     * Retries, because a single refused connection is not evidence the host is down — the same
     * endpoint answers on the next attempt often enough that giving up once is what turns a wait
     * into a failed claim. Nothing is waiting on this, so the backoff costs nothing.
     */
    private suspend fun ensure(params: SaplingParams) {
        val target = File(directory(), params.fileName)
        if (target.isFile && target.sha1() == params.sha1) return

        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(BACKOFF * attempt)
            if (fetch(params, target)) return
        }
    }

    private fun fetch(params: SaplingParams, target: File): Boolean {
        // Downloaded beside the target and moved only once it verifies, so a half-written file is
        // never left where the SDK would read it as the real thing.
        val partial = File(directory(), "${params.fileName}.partial")
        val downloaded =
            runCatching {
                URL(CLOUD_PARAM_DIR_URL + params.fileName).openStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                partial.sha1() == params.sha1
            }.getOrElse {
                // With the cause: a silent best-effort fetch is what made the original failure
                // surface as an unexplained dead claim three steps later.
                Twig.warn(it) { "Proving parameter ${params.fileName} could not be fetched" }
                false
            }

        if (downloaded && partial.renameTo(target)) {
            Twig.info { "Proving parameter ${params.fileName} is ready" }
            return true
        }
        partial.delete()
        return false
    }

    private fun directory() = File(application.noBackupFilesDir, NO_BACKUP_SUBDIRECTORY).also { it.mkdirs() }

    private fun File.sha1(): String =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            inputStream().use { input ->
                val buffer = ByteArray(DIGEST_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse { "" }

    private data class SaplingParams(
        val fileName: String,
        val sha1: String,
    )

    private companion object {
        const val CLOUD_PARAM_DIR_URL = "https://download.z.cash/downloads/"

        /** Mirrors `Files.NO_BACKUP_SUBDIRECTORY`, which is internal to the SDK. Note the single "c". */
        const val NO_BACKUP_SUBDIRECTORY = "co.electricoin.zcash"

        const val DIGEST_BUFFER_BYTES = 64 * 1024

        const val ATTEMPTS = 3

        val BACKOFF = 3.seconds

        /** Names and hashes as `SaplingParamTool` expects to find them; it re-verifies on use. */
        val PARAMS =
            listOf(
                SaplingParams("sapling-spend.params", "a15ab54c2888880e53c823a3063820c728444126"),
                SaplingParams("sapling-output.params", "0ebc5a1ef3653948e1c46cf7a16071eac4b7e352"),
            )
    }
}
