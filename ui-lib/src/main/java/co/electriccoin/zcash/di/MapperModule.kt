package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.common.mapper.ActivityMapper
import co.electriccoin.zcash.ui.screen.home.HomeMessageMapper
import co.electriccoin.zcash.ui.screen.swap.ExactInputVMMapper
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteVMMapper
import co.electriccoin.zcash.ui.screen.transactiondetail.CommonTransactionDetailMapper
import co.electriccoin.zcash.ui.screen.unifiedsend.UnifiedSendVMMapper
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val mapperModule =
    module {
        factoryOf(::ActivityMapper)
        factoryOf(::HomeMessageMapper)
        factoryOf(::ExactInputVMMapper)
        factoryOf(::SwapQuoteVMMapper)
        factoryOf(::CommonTransactionDetailMapper)
        factoryOf(::UnifiedSendVMMapper)
    }
