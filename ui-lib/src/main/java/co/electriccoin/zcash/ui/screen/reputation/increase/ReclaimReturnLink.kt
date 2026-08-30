package co.electriccoin.zcash.ui.screen.reputation.increase

/**
 * Where the Reclaim Verifier sends the user when a verification finishes.
 *
 * The Verifier hands off to whatever `redirectUrl` the session template carried; sending an empty
 * one leaves the user staring at "you can now return to Zapp" in a browser, with no way back but
 * the launcher. Reclaim's own SDK validates this field with nothing more than `new URL(...)`, so a
 * private scheme is as acceptable to it as an https link — and a private scheme is the only one
 * that reaches an app rather than a web page.
 *
 * ☠ The host has to be its own thing, not a bare `zcash://`. [co.electriccoin.zcash.ui.MainActivity]
 * forwards every unrecognised `zcash://` URI to the QR scanner, so a redirect without a host of its
 * own would land returning users in the camera — worse than never coming back at all.
 *
 * Nothing is carried in the URL. The proof is fetched by polling the session the app already
 * holds, so this link's only job is to bring the task forward; a payload here would be an
 * untrusted second source for something already known.
 */
object ReclaimReturnLink {
    const val HOST = "reclaim-return"

    const val URL = "zcash://$HOST"
}
