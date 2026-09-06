package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.spackle.Twig
import xyz.justzappit.offramp.orchestrator.OfframpLogger

/** Routes the offramp orchestrator's diagnostics into logcat under the app's Twig tag. */
object TwigOfframpLogger : OfframpLogger {
    override fun info(message: String) = Twig.info { "Offramp: $message" }

    override fun warn(message: String) = Twig.warn { "Offramp: $message" }
}
