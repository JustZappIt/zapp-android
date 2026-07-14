package co.electriccoin.zcash.ui.configuration

import co.electriccoin.zcash.configuration.model.entry.BooleanConfigurationEntry
import co.electriccoin.zcash.configuration.model.entry.ConfigKey
import co.electriccoin.zcash.configuration.model.entry.StringConfigurationEntry

object ConfigurationEntries {
    val IS_FLEXA_AVAILABLE = BooleanConfigurationEntry(ConfigKey("is_flexa_available"), true)

    // Selects the Slipstream sync engine when true. Defaults false so a fresh build and any
    // device without remote config runs the published SDK sync engine (the kill switch's safe
    // state). See docs/slipstream/INTEGRATION.md.
    val IS_SLIPSTREAM_AVAILABLE = BooleanConfigurationEntry(ConfigKey("is_slipstream_available"), false)
    val VOTING_CONFIG_URL = StringConfigurationEntry(ConfigKey("voting_config_url"), "")
    val VOTING_SERVER_URL = StringConfigurationEntry(ConfigKey("voting_server_url"), "")
}
