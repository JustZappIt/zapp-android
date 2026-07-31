package co.electriccoin.zcash.ui.screen.settings.p2p

import co.electriccoin.zcash.ui.design.util.StringResource
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.P2pOrderHistoryItem
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class P2pTransactionsFormatTest {
    @Test
    fun payOrderDetailShowsCachedScannedUpiWhenMerchantCiphertextIsEmpty() {
        val row =
            historyItem(
                orderType = OrderType.PAY,
                recipientUpiPlain = "upi://pay?pa=zeptonow.bdpg111@kotakpay&am=116.99&cu=INR",
                merchantUpiPlain = null,
            ).toRow(network)

        assertEquals("zeptonow.bdpg111@kotakpay", row.detail?.paidToUpiPlain)
        assertNull(row.detail?.paidByUpiPlain)
        assertEquals(listOf("0.100"), row.detail?.fee?.resourceArgs())
    }

    @Test
    fun payOrderDetailKeepsPaidByWhenMerchantCiphertextDecrypts() {
        val row =
            historyItem(
                orderType = OrderType.PAY,
                recipientUpiPlain = "shop@ybl",
                merchantUpiPlain = "merchant@okhdfc",
            ).toRow(network)

        assertEquals("merchant@okhdfc", row.detail?.paidByUpiPlain)
        assertEquals("shop@ybl", row.detail?.paidToUpiPlain)
    }

    private fun historyItem(
        orderType: OrderType,
        recipientUpiPlain: String?,
        merchantUpiPlain: String?,
    ) = P2pOrderHistoryItem(
        orderId = BigInteger("581276"),
        orderType = orderType,
        status = OrderStatus.COMPLETED,
        usdcAmount = Usdc6.ofMicros(1_332_746),
        fiatAmount = Usdc6.ofMicros(116_999_922),
        currencyHex = INR_BYTES32_HEX,
        placedAtEpochSeconds = 1_781_646_921,
        completedAtEpochSeconds = 1_781_646_971,
        cancelledAtEpochSeconds = null,
        acceptedMerchantAddress = Address.parse("0xaa19556e33cff3d709de2e389187f14d8db27480"),
        recipientUpiPlain = recipientUpiPlain,
        merchantUpiPlain = merchantUpiPlain,
        fixedFeePaid = Usdc6.ofMicros(100_000),
    )

    private fun StringResource.resourceArgs(): List<Any> = (this as StringResource.ByResource).args

    private companion object {
        const val INR_BYTES32_HEX = "0x494e520000000000000000000000000000000000000000000000000000000000"

        val network =
            P2pNetworkConfig(
                name = "mainnet",
                chainId = ChainId.BASE_MAINNET,
                rpcUrl = "https://example.invalid/rpc",
                diamondAddress = Address.parse("0x4cad6ec90e65babec9335cad728ddc610c316368"),
                usdcAddress = Address.parse("0x833589fcD6edb6e08f4c7c32D4f71b54bdA02913"),
                subgraphUrl = "https://example.invalid/subgraph",
                baseExplorerUrl = "https://basescan.org",
            )
    }
}
