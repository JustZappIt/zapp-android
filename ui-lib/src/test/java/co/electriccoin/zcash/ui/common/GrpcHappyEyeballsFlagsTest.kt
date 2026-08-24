// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the two system properties `ZcashApplication.configureGrpcHappyEyeballs` sets.
 *
 * They make gRPC fall back from a stalled IPv6 connection to IPv4 (RFC 8305). Without that, a
 * silently blackholed IPv6 path leaves a clearnet channel in `waiting_for_connection` until its
 * deadline expires — which is how a perfectly good gift card gets reported as unreachable, since a
 * card's own synchronizer is the one thing that cannot route over Tor.
 *
 * Both flags are experimental in gRPC 1.78, so a version bump can rename or drop them. Nothing at
 * runtime would notice: `System.setProperty` still succeeds and the startup log still prints
 * `true`, while gRPC quietly ignores both. This fails the build instead.
 *
 * Reads the class bytes rather than the field, because the flags are read once into a private
 * static during class initialisation and are not observable afterwards.
 */
class GrpcHappyEyeballsFlagsTest {
    @Test
    fun `gRPC still recognises the Happy Eyeballs flags`() {
        val bytes =
            javaClass.classLoader
                ?.getResourceAsStream(PICK_FIRST_CLASS)
                ?.use { it.readBytes() }

        assertNotNull(bytes, "$PICK_FIRST_CLASS is missing — gRPC moved it, so the flags need rechecking")

        // Latin-1: class files are not UTF-8, and the constant pool holds these as plain ASCII.
        val constants = String(bytes, Charsets.ISO_8859_1)

        FLAGS.forEach { flag ->
            assertTrue(
                constants.contains(flag),
                "gRPC no longer reads $flag. ZcashApplication.configureGrpcHappyEyeballs() is now a " +
                    "no-op and IPv6 blackholes will break gift card claims again — find the new flag, " +
                    "or move the fix into the SDK's ChannelFactory.",
            )
        }
    }

    private companion object {
        const val PICK_FIRST_CLASS = "io/grpc/internal/PickFirstLoadBalancerProvider.class"

        val FLAGS =
            listOf(
                "GRPC_EXPERIMENTAL_ENABLE_NEW_PICK_FIRST",
                "GRPC_PF_USE_HAPPY_EYEBALLS",
            )
    }
}
