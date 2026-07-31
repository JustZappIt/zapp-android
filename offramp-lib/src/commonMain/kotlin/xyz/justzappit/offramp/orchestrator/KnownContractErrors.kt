// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

// GENERATED FILE — DO NOT EDIT.
// Source: p2pdotme-sdk/src/contracts/errors.ts + error-messages.ts.
// Regenerate via docs/integrations/scripts/generate-revert-selectors.ts.
//
// Selector count: 189

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.abi.Selector4

/**
 * Wholesale port of the p2p.me Diamond's custom-error selector table. Every selector the
 * contract emits is mapped to its canonical SDK code (`contracts/errors.ts`) and English
 * fallback copy (`contracts/error-messages.ts`). Keeps us byte-aligned with the SDK; do not
 * edit by hand. See the generator header for regeneration.
 *
 * Curated PAY-flow reverts render localised `R.string.*` copy via [KnownRevertReason]; the
 * uncurated long tail falls back to the English [Entry.message] here.
 */
object KnownContractErrors {
    data class Entry(
        val name: String,
        val message: String
    )

    private val ENTRIES: Map<Selector4, Entry> =
        mapOf(
            Selector4.fromHex("0x02a6fdd2") to Entry("CURRENCY_NOT_SUPPORTED", "Currency not supported"),
            Selector4.fromHex("0x03683687") to Entry("ORDER_ALREADY_COMPLETED", "Order already marked completed"),
            Selector4.fromHex("0x0464115c") to Entry("ZK_PASSPORT_AGE_BELOW_MINIMUM", "ZK Passport age below minimum"),
            Selector4.fromHex("0x0569ab3e") to Entry("DUPLICATE_PAYMENT_CHANNEL", "Duplicate payment channel"),
            Selector4.fromHex("0x06b663af") to Entry("SLASH_AMOUNT_EXCEEDS_STAKE", "Slash amount exceeds stake"),
            Selector4.fromHex("0x071ea33c") to
                Entry("USER_HAS_NO_REPUTATION", "Kindly do ZK social verifications and increase your limits to place more orders"),
            Selector4.fromHex("0x074a6991") to Entry("REWARD_PERCENTAGE_TOO_HIGH", "Reward percentage is too high"),
            Selector4.fromHex("0x07a2454f") to
                Entry("DISPUTE_TIME_NOT_REACHED", "Dispute can only be raised after 30 minutes of order placement"),
            Selector4.fromHex("0x0b7c70f3") to Entry("UNSTAKE_REQUEST_NOT_PENDING", "No pending unstake request"),
            Selector4.fromHex("0x0ece93a6") to Entry("RECOMMENDATION_ALREADY_CLAIMED", "Recommendation already claimed"),
            Selector4.fromHex("0x0ee0b659") to Entry("MERCHANT_NOT_BLACKLISTED", "Merchant not blacklisted"),
            Selector4.fromHex("0x0f165e7b") to Entry("NULLIFIER_ALREADY_VERIFIED", "Nullifier already verified"),
            Selector4.fromHex("0x1117a646") to Entry("INVALID_STATUS_TRANSITION", "Invalid status transition"),
            Selector4.fromHex("0x138b9d5a") to Entry("INVALID_ORDER_AMOUNT_TO_COVER_FEE", "Order amount is not enough to cover fee"),
            Selector4.fromHex("0x149f9fca") to Entry("USDC_TRANSFER_FAILED", "USDC transfer failed"),
            Selector4.fromHex("0x16c726b1") to Entry("NOT_SUPER_ADMIN", "You are not a super admin"),
            Selector4.fromHex("0x1775c43e") to Entry("ORDER_NOT_ASSIGNED", "Order not assigned"),
            Selector4.fromHex("0x181b1b2e") to Entry("ORDER_STATUS_INVALID", "Order with placed status only can be re-assigned"),
            Selector4.fromHex("0x18eda032") to Entry("P2P_TOKEN_NOT_SET", "P2P token is not set"),
            Selector4.fromHex("0x1b19ad97") to Entry("MERCHANT_MONTHLY_REFERRAL_LIMIT_REACHED", "Merchant monthly referral limit reached"),
            Selector4.fromHex("0x1b1d7861") to Entry("NO_WITHDRAWABLE_AMOUNT", "No withdrawable amount"),
            Selector4.fromHex("0x1b5433c8") to Entry("ACCOUNT_BOUND_TO_ANOTHER_CIRCLE", "Account is bound to another circle"),
            Selector4.fromHex("0x1e3b9629") to Entry("ORDER_NOT_PAID", "Order has not been paid"),
            Selector4.fromHex("0x1fa24b35") to Entry("ZK_PASSPORT_PROOF_INVALID", "ZK Passport proof is invalid"),
            Selector4.fromHex("0x1fb09b80") to Entry("NONCE_ALREADY_USED", "Nonce already used"),
            Selector4.fromHex("0x201c1ffc") to Entry("ADMIN_ALREADY_HAS_CIRCLE", "Admin already has a circle"),
            Selector4.fromHex("0x20d5910f") to Entry("ORDER_TOO_LATE_FOR_REASSIGNMENT", "Order is too late for reassignment"),
            Selector4.fromHex("0x21311aa3") to Entry("NO_STAKERS", "No stakers found"),
            Selector4.fromHex("0x22a5e34b") to Entry("MANAGER_NOT_FOUND", "Manager not found"),
            Selector4.fromHex("0x2366073b") to Entry("INVALID_SOCIAL_PLATFORM", "Invalid social media platform"),
            Selector4.fromHex("0x279bbc0c") to Entry("USDC_TRANSFER_FAILED_WITH_PANIC", "USDC transfer failed with panic"),
            Selector4.fromHex("0x29c3b7ee") to Entry("NOT_SELF", "Action can only be performed by yourself"),
            Selector4.fromHex("0x2a829f07") to Entry("INVALID_ORDER_STATUS_TO_RAISE_DISPUTE", "Invalid order status to raise dispute"),
            Selector4.fromHex("0x2c5211c6") to Entry("INVALID_AMOUNT", "Invalid amount"),
            Selector4.fromHex("0x2cc11576") to Entry("INSUFFICIENT_MERCHANT_REWARDS", "Insufficient merchant rewards"),
            Selector4.fromHex("0x2d3087f9") to Entry("ZERO_UNSTAKE_AMOUNT", "Unstake amount cannot be zero"),
            Selector4.fromHex("0x2e757a60") to Entry("ORDER_TYPE_INCORRECT", "Incorrect order type"),
            Selector4.fromHex("0x2ef13105") to Entry("EMPTY_NAME", "Name cannot be empty"),
            Selector4.fromHex("0x2f850b6b") to Entry("SOCIAL_ALREADY_VERIFIED", "Social already verified"),
            Selector4.fromHex("0x2f950361") to Entry("UNCLAIMED_REWARDS_EXIST", "Unclaimed rewards exist"),
            Selector4.fromHex("0x2fdec18b") to Entry("SIGNATURE_VALIDATION_FAILED", "Signature validation failed"),
            Selector4.fromHex(
                "0x302c5138"
            ) to Entry("CANNOT_CLAIM_REVENUE_FOR_CURRENT_MONTH", "Cannot claim revenue for the current month"),
            Selector4.fromHex("0x355b0709") to Entry("FACEBOOK_ONLY_RP_UPDATES", "Facebook only supports RP updates"),
            Selector4.fromHex("0x36bdb7b6") to Entry("ZK_PASSPORT_IDENTIFIER_ALREADY_VERIFIED", "ZK Passport identifier already verified"),
            Selector4.fromHex("0x3762bfee") to Entry("INVALID_ADMIN_COMMUNITY_URL", "Invalid admin community URL"),
            Selector4.fromHex("0x3764a75c") to Entry("CANNOT_RAISE_DISPUTE_TWICE", "Cannot raise dispute twice"),
            Selector4.fromHex("0x3a8fbef4") to Entry("NOT_PRICE_UPDATER_FOR_CURRENCY", "Not the price updater for this currency"),
            Selector4.fromHex("0x3c0ca622") to Entry("NO_REPUTATION", "No reputation points"),
            Selector4.fromHex("0x3d90c0a6") to Entry("INVALID_CIRCLE_ID", "Invalid circle ID"),
            Selector4.fromHex("0x3e2c36f2") to Entry("THRESHOLD_NOT_CONFIGURED", "Threshold not configured"),
            Selector4.fromHex("0x3eb17c88") to Entry("INVALID_BLOCK_AMOUNT", "Invalid block amount"),
            Selector4.fromHex("0x3eedee0f") to Entry("INVALID_CAMPAIGN_ID", "Invalid campaign ID"),
            Selector4.fromHex("0x3fb087f4") to Entry("NO_REWARDS", "No rewards"),
            Selector4.fromHex("0x3fd2347e") to Entry("STAKE_AMOUNT_TOO_LOW", "Stake amount is too low"),
            Selector4.fromHex("0x403e7fa6") to Entry("FUNCTION_NOT_FOUND", "Function not found"),
            Selector4.fromHex("0x412dd2b1") to Entry("INSUFFICIENT_RP", "Insufficient reputation points"),
            Selector4.fromHex("0x430f13b3") to Entry("INVALID_NAME", "Invalid name"),
            Selector4.fromHex("0x439cc0cd") to Entry("VERIFICATION_FAILED", "Verification failed"),
            Selector4.fromHex("0x466f52a8") to Entry("YEAR_FIELD_NOT_IN_PROOF", "Year field not found in proof"),
            Selector4.fromHex("0x47bfece5") to Entry("USDC_TRANSFER_FAILED_WITH_ERROR_MESSAGE", "USDC transfer failed with error message"),
            Selector4.fromHex("0x48183836") to Entry("ZK_PASSPORT_MIN_AGE_TOO_HIGH", "ZK Passport minimum age too high"),
            Selector4.fromHex("0x487add97") to Entry("NEW_PAYMENT_CHANNEL_SHOULD_BE_ACTIVE", "New payment channel should be active"),
            Selector4.fromHex("0x49de1789") to Entry("MONTHLY_VOLUME_LIMIT_EXCEEDED", "Monthly volume limit exceeded"),
            Selector4.fromHex("0x4b29cf0a") to Entry("BUY_AMOUNT_EXCEEDS_USDC_LIMIT", "Buy amount exceeds USDC limit"),
            Selector4.fromHex("0x4bbac5de") to Entry("EXCHANGE_NOT_OPERATIONAL", "Exchange is not operational"),
            Selector4.fromHex("0x4d460588") to Entry("USER_ID_FIELD_NOT_IN_PROOF", "User ID field not found in proof"),
            Selector4.fromHex("0x549e2555") to Entry("EXIT_AMOUNT_EXCEEDED_CIRCLE_BALANCE", "Exit amount exceeded circle balance"),
            Selector4.fromHex("0x552ff5ec") to Entry("PAYMENT_CHANNEL_NOT_FOUND", "Payment channel not found"),
            Selector4.fromHex("0x584a7938") to Entry("NOT_WHITELISTED", "Not whitelisted"),
            Selector4.fromHex("0x58db8ed6") to Entry("ORDER_NOT_PLACED", "Order not placed"),
            Selector4.fromHex("0x5d04ff4c") to Entry("NOT_ENOUGH_ELIGIBLE_MERCHANTS", "Not enough eligible merchants"),
            Selector4.fromHex("0x5d706033") to Entry("INVALID_ORDER_ID", "Invalid order ID"),
            Selector4.fromHex("0x5eadc4c2") to Entry("ZK_PASSPORT_SCOPE_EMPTY", "ZK Passport scope is empty"),
            Selector4.fromHex("0x5f765689") to Entry("MERCHANT_ALREADY_BLACKLISTED", "Merchant already blacklisted"),
            Selector4.fromHex("0x6131d13d") to Entry("TRANSACTION_ID_MISMATCH", "Transaction ID does not match"),
            Selector4.fromHex("0x61982c98") to Entry("REQUEST_FAILED", "Request failed"),
            Selector4.fromHex("0x626b7c00") to Entry("REWARD_ALREADY_CLAIMED", "Reward already claimed"),
            Selector4.fromHex("0x64301cb8") to Entry("SELL_ORDER_AMOUNT_LIMIT_EXCEEDED", "Sell order amount limit exceeded"),
            Selector4.fromHex("0x6540a51d") to Entry("CIRCLE_NAME_ALREADY_TAKEN", "Circle name is already taken"),
            Selector4.fromHex("0x668ca75d") to Entry("INVALID_MANAGER_DETAILS", "Invalid manager details"),
            Selector4.fromHex("0x675dbc86") to Entry("MONTHLY_BUY_ORDER_LIMIT_EXCEEDED", "Monthly buy order count limit exceeded"),
            Selector4.fromHex("0x6764f4d6") to Entry("PAYMENT_CHANNEL_NOT_APPROVED", "Payment channel not approved"),
            Selector4.fromHex("0x688c176f") to Entry("INVALID_ORDER_TYPE", "Invalid order type"),
            Selector4.fromHex(
                "0x69470b13"
            ) to Entry("USERNAME_ALREADY_VERIFIED", "The social media account's username is already verified"),
            Selector4.fromHex("0x69f5bfe7") to Entry("ZK_PASSPORT_UNEXPECTED_SENDER", "ZK Passport unexpected sender"),
            Selector4.fromHex("0x69f6994a") to Entry("NOT_ELIGIBLE_TO_REFER", "Not eligible to refer"),
            Selector4.fromHex("0x6b1b90b4") to Entry("ORDER_NOT_ACCEPTED", "Order not placed to be accepted"),
            Selector4.fromHex("0x6d4c3f9e") to
                Entry("ONGOING_ORDER_ON_PAYMENT_CHANNEL", "There is an ongoing order on this payment channel"),
            Selector4.fromHex("0x703cde0a") to Entry("ADDITIONAL_STAKE_NOT_ALLOWED", "Additional stake is not allowed"),
            Selector4.fromHex("0x70d753bd") to
                Entry("MERCHANT_NOT_FULLFILLED_ELIGIBILITY_THRESHOLD", "Merchant has not fulfilled eligibility threshold"),
            Selector4.fromHex("0x71c4efed") to Entry("SLIPPAGE_EXCEEDED", "Slippage exceeded"),
            Selector4.fromHex("0x7290a612") to Entry("MERCHANT_NOT_APPROVED", "Merchant is not approved"),
            Selector4.fromHex("0x73380d99") to Entry("CLAIMABLE_REWARDS_NOT_AVAILABLE", "No rewards to claim"),
            Selector4.fromHex("0x74785d0f") to Entry("CANNOT_VOTE_YOURSELF", "Cannot vote yourself"),
            Selector4.fromHex("0x7642fe15") to Entry("PASSPORT_ALREADY_VERIFIED", "Passport already verified"),
            Selector4.fromHex("0x78317f44") to Entry("INSUFFICIENT_P2P_STAKE", "Insufficient P2P stake"),
            Selector4.fromHex("0x784b6c3c") to Entry("CIRCLE_ID_MISMATCH", "Circle ID mismatch"),
            Selector4.fromHex("0x7a551e38") to Entry("CAMPAIGN_NOT_ACTIVE", "Campaign is not active"),
            Selector4.fromHex("0x7aabdfe3") to Entry("ALREADY_REFERRED", "Already referred"),
            Selector4.fromHex("0x7bfa4b9f") to Entry("NOT_ADMIN", "You are not an admin"),
            Selector4.fromHex("0x7c9a1cf9") to Entry("ALREADY_VOTED", "Already voted"),
            Selector4.fromHex("0x7e2ee654") to Entry("DAILY_VOLUME_LIMIT_EXCEEDED", "Daily volume limit exceeded"),
            Selector4.fromHex("0x7f61b868") to Entry("ORDER_ALREADY_PAID", "Payment address already sent"),
            Selector4.fromHex("0x7f73f237") to Entry("UNEXPECTED_REQUEST_ID", "Unexpected request ID"),
            Selector4.fromHex("0x7ff47425") to Entry("MIGRATION_REQUEST_NOT_PENDING", "No pending migration request"),
            Selector4.fromHex("0x81c2b982") to Entry("NO_FIAT_LIQUIDITY", "No fiat liquidity on exchange to complete order"),
            Selector4.fromHex("0x83463f4a") to Entry("SELF_REFERRAL_NOT_ALLOWED", "Self referral not allowed"),
            Selector4.fromHex("0x8390b2dd") to Entry("USERNAME_NOT_IN_PROOF", "Username field not found in proof"),
            Selector4.fromHex("0x865b21e1") to Entry("UNDELEGATION_AMOUNT_TOO_HIGH", "Undelegation amount is too high"),
            Selector4.fromHex("0x866e9f89") to Entry("DISPUTE_ALREADY_SETTLED", "Dispute already settled"),
            Selector4.fromHex("0x8713aaba") to Entry("MERCHANT_ALREADY_REJECTED", "Merchant already rejected"),
            Selector4.fromHex("0x88d039ce") to Entry("DISPUTE_NOT_RAISED", "Dispute not raised"),
            Selector4.fromHex("0x88ddec46") to Entry("MIGRATION_ALREADY_REQUESTED", "Migration already requested"),
            Selector4.fromHex("0x8beb9d16") to Entry("REENTRANCY_GUARD", "Reentrancy detected"),
            Selector4.fromHex("0x8ec051b8") to Entry("ACCOUNT_NUMBER_MISMATCH", "Account number does not match"),
            Selector4.fromHex("0x8f90a426") to
                Entry("AGGREGATE_DELEGATION_EXCEEDS_TOTAL_STAKED", "Aggregate delegation exceeds total staked"),
            Selector4.fromHex("0x902ade67") to Entry("ONLY_NEW_USERS_ALLOWED", "Only new users allowed"),
            Selector4.fromHex("0x91da284f") to Entry("BUY_ORDER_AMOUNT_EXCEEDS_LIMIT", "Buy order amount exceeds limit"),
            Selector4.fromHex("0x92aa7d0f") to Entry("INVALID_MIGRATION_STATUS", "Invalid migration status"),
            Selector4.fromHex("0x93845d68") to Entry("INVALID_ORDER_AMOUNT", "Invalid order amount"),
            Selector4.fromHex("0x944a2241") to Entry("NO_RECOMMENDER", "No recommender found"),
            Selector4.fromHex("0x99c8ef4d") to Entry("INVALID_PAYMENT_CHANNEL_ID", "Invalid payment channel ID"),
            Selector4.fromHex("0x9ab7872d") to Entry("COOLDOWN_NOT_PASSED", "Cooldown period has not passed"),
            Selector4.fromHex("0x9ae55bc7") to Entry("MERCHANT_BLACKLISTED", "Merchant blacklisted"),
            Selector4.fromHex("0x9c54e5a8") to Entry("MERCHANT_HAS_ONGOING_ORDERS", "Merchant has ongoing orders"),
            Selector4.fromHex("0x9f11a53f") to Entry("TOKEN_EMPTY", "Token is empty"),
            Selector4.fromHex("0xa1610e37") to Entry("MANAGER_INACTIVE", "Manager is inactive"),
            Selector4.fromHex("0xa18ea4e8") to Entry("USER_ID_ALREADY_VERIFIED", "The social media account's user ID is already verified"),
            Selector4.fromHex("0xa24a13a6") to Entry("ARRAY_LENGTH_MISMATCH", "Array length mismatch"),
            Selector4.fromHex("0xa6af7ebe") to Entry("MERCHANT_NOT_REGISTERED", "Merchant is not registered"),
            Selector4.fromHex("0xa8143fbc") to Entry("NOT_CIRCLE_ADMIN", "You are not a circle admin"),
            Selector4.fromHex("0xa9de99ae") to Entry("UNSTAKE_REQUEST_PENDING", "Unstake request is already pending"),
            Selector4.fromHex("0xaa60ec26") to Entry("INVALID_ORDER_UPI", "Invalid order payment address"),
            Selector4.fromHex("0xab284291") to Entry("PAYMENT_CHANNEL_NOT_REJECTED", "Payment channel has not been rejected"),
            Selector4.fromHex("0xab66be18") to Entry("SOURCE_CODE_MISMATCH", "Source code mismatch"),
            Selector4.fromHex("0xab948796") to Entry("ONLY_ROUTER_CAN_FULFILL", "Only the router can fulfill this request"),
            Selector4.fromHex("0xb1198199") to Entry("NEW_PAYMENT_CHANNEL_NOT_FOUND", "New payment channel not found"),
            Selector4.fromHex("0xb14a1ff3") to Entry("USER_YEARLY_VOLUME_LIMIT_EXCEEDED", "Yearly volume limit exceeded"),
            Selector4.fromHex("0xb20277f8") to Entry("TIP_ALREADY_GIVEN", "Tip already given"),
            Selector4.fromHex(
                "0xb28c3e29"
            ) to Entry("DISPUTE_TIME_EXPIRED", "Dispute can only be raised after 24 hours of order placement"),
            Selector4.fromHex("0xb407b9ec") to Entry("SELL_ORDER_AMOUNT_EXCEEDS_LIMIT", "Sell order amount exceeds limit"),
            Selector4.fromHex("0xb4fa3fb3") to Entry("INVALID_INPUT", "Invalid input"),
            Selector4.fromHex("0xb87078f9") to Entry("ZK_PASSPORT_DOMAIN_EMPTY", "ZK Passport domain is empty"),
            Selector4.fromHex("0xbb1cb70b") to Entry("BATCH_TOO_LARGE", "Batch size too large"),
            Selector4.fromHex("0xbb6c216c") to Entry("INVALID_COMPUTED_PRICES", "Invalid computed prices"),
            Selector4.fromHex("0xbb776720") to Entry("ORDER_TOO_EARLY_FOR_REASSIGNMENT", "Order is too early for reassignment"),
            Selector4.fromHex("0xbba2edf9") to Entry("SELL_AMOUNT_EXCEEDS_FIAT_LIMIT", "Sell amount exceeds fiat limit"),
            Selector4.fromHex("0xbf2d0ba1") to Entry("P2P_UNSTAKE_COOLDOWN_NOT_PASSED", "P2P unstake cooldown period has not passed"),
            Selector4.fromHex("0xc0b6c919") to Entry("INVALID_MERCHANT", "Invalid merchant"),
            Selector4.fromHex("0xc1654697") to Entry("UPI_ALREADY_SENT", "Payment address already sent"),
            Selector4.fromHex("0xc26d5f75") to Entry("VOTES_PER_EPOCH_EXCEEDED", "Votes per epoch exceeded"),
            Selector4.fromHex("0xc56873ba") to Entry("ORDER_EXPIRED", "Order expired"),
            Selector4.fromHex("0xc905b99a") to Entry("SAME_PAYMENT_CHANNEL", "Old and new payment channels are the same"),
            Selector4.fromHex("0xc991cbb1") to Entry("TOKEN_ALREADY_EXISTS", "Token already exists"),
            Selector4.fromHex("0xc9b16952") to Entry("TARGET_LONGER_THAN_DATA", "Target is longer than data"),
            Selector4.fromHex("0xcacf989a") to Entry("NO_STAKE", "No stake found"),
            Selector4.fromHex("0xcadc6786") to Entry("P2P_STAKE_CONFIG_NOT_SET", "P2P stake config is not set"),
            Selector4.fromHex("0xcbdb7b30") to Entry("TOKEN_NOT_FOUND", "Token not found"),
            Selector4.fromHex("0xccd87bf0") to Entry("REASSIGNMENT_NOT_REQUIRED", "Reassignment is not required"),
            Selector4.fromHex("0xcedb41f1") to Entry("OLD_PAYMENT_CHANNEL_SHOULD_BE_INACTIVE", "Old payment channel should be inactive"),
            Selector4.fromHex("0xd06ff88e") to Entry("INSUFFICIENT_STAKED_AMOUNT", "Insufficient staked amount"),
            Selector4.fromHex("0xd13a7934") to Entry("ZK_PASSPORT_INVALID_SCOPE", "ZK Passport invalid scope"),
            Selector4.fromHex("0xd2e1e6e0") to Entry("ZERO_REPUTATION_POINTS", "Cannot place buy orders with 0 reputation points"),
            Selector4.fromHex("0xd92e233d") to Entry("ZERO_ADDRESS", "Zero address"),
            Selector4.fromHex("0xd97cf1ba") to Entry("UNDERFLOW_SUBTRACTION", "Cannot subtract more than balance"),
            Selector4.fromHex("0xdab11ea6") to Entry("P2P_UNSTAKE_REQUEST_PENDING", "A P2P unstake request is already pending"),
            Selector4.fromHex("0xdf9f707c") to Entry("CASHBACK_TRANSFER_FAILED", "Cashback transfer failed"),
            Selector4.fromHex("0xe595a7bf") to Entry("DAILY_BUY_ORDER_LIMIT_EXCEEDED", "Daily buy order count limit exceeded"),
            Selector4.fromHex("0xe665491f") to Entry("UNSTAKE_AMOUNT_EXCEEDED", "Unstake amount exceeded"),
            Selector4.fromHex("0xe6c4247b") to Entry("INVALID_ADDRESS", "Invalid address"),
            Selector4.fromHex("0xe7cbf75a") to Entry("INVALID_COMMUNITY_URL", "Invalid community URL"),
            Selector4.fromHex("0xea8e4eb5") to Entry("NOT_AUTHORIZED", "Not authorized"),
            Selector4.fromHex("0xeb1ce40b") to Entry("NO_P2P_UNSTAKE_REQUEST", "No P2P unstake request found"),
            Selector4.fromHex("0xebb6f34b") to Entry("USER_IS_BLACKLISTED", "User is blacklisted"),
            Selector4.fromHex("0xec4b3ce6") to Entry("EXIT_WOULD_BREACH_DELEGATION_INVARIANT", "Exit would breach delegation invariant"),
            Selector4.fromHex("0xee240e49") to Entry("DUPLICATE_ACCOUNT_NAME", "Account name already exists"),
            Selector4.fromHex("0xef053cf4") to Entry("LINKEDIN_ONLY_RP_UPDATES", "LinkedIn only supports RP updates"),
            Selector4.fromHex("0xf2775265") to Entry("CIRCLE_FULL", "Circle is full"),
            Selector4.fromHex("0xf42e41a1") to Entry("ORDER_AMOUNT_EXCEEDS_LIMIT", "Order amount exceeds limit"),
            Selector4.fromHex("0xf4a1e014") to Entry("MERCHANT_ALREADY_REGISTERED", "Merchant already registered"),
            Selector4.fromHex("0xf5993428") to Entry("INVALID_CURRENCY", "Invalid currency"),
            Selector4.fromHex("0xf8bfad32") to Entry("NOT_PAID_BUY_ORDER", "Not a paid buy order"),
            Selector4.fromHex("0xfb42a67d") to Entry("CURRENCY_MISMATCH", "Currency mismatch"),
            Selector4.fromHex("0xfb8f41b2") to Entry("INSUFFICIENT_ALLOWANCE", "Insufficient USDC allowance"),
            Selector4.fromHex("0xfccd93cf") to Entry("PAYMENT_CHANNEL_NOT_ACTIVE", "Payment channel not active"),
            Selector4.fromHex("0xfd8d4a6d") to Entry("ZK_PASSPORT_VERIFIER_NOT_SET", "ZK Passport verifier not set"),
            Selector4.fromHex("0xff2826ef") to Entry("ZERO_MARKET_PRICE", "Market price cannot be zero"),
            Selector4.fromHex("0xff4f83ca") to Entry("OLD_PAYMENT_CHANNEL_NOT_FOUND", "Old payment channel not found"),
            Selector4.fromHex("0xff9b022c") to Entry("CIRCLE_NOT_ACTIVE", "Circle is not active"),
        )

    /** Full SDK entry (code + English message) for [selector], or null. */
    fun entryFor(selector: Selector4?): Entry? = selector?.let { ENTRIES[it] }

    /** Canonical SDK code (`ORDER_EXPIRED`) for [selector], or null. */
    fun nameFor(selector: Selector4?): String? = entryFor(selector)?.name

    /** English fallback message (`"Order expired"`) for [selector], or null. */
    fun messageFor(selector: Selector4?): String? = entryFor(selector)?.message

    /** Total count of mapped selectors. Exposed for SDK-parity tests. */
    val size: Int get() = ENTRIES.size
}
