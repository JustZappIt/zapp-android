package co.electriccoin.zcash.ui.common.repository

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class MigrationHandoffAccountGuardTest {

    @Test
    fun pendingScheduleReturnsNullForDifferentAccount() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertNull(repo.get("accountB"))
    }

    @Test
    fun pendingScheduleReturnsValueForSameAccount() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertSame(schedule, repo.get("accountA"))
    }

    @Test
    fun restartScheduleConsumeReturnsNullForDifferentAccount() {
        val repo = RestartMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertNull(repo.consume("accountB"))
        // mismatched consume clears — value is also gone for the right account
        assertNull(repo.consume("accountA"))
    }

    @Test
    fun keystonePcztsReturnsNullForDifferentAccount() {
        val repo = PendingKeystoneMigrationPcztsRepositoryImpl()
        val pczts = PendingKeystoneMigrationPczts(
            requestId = byteArrayOf(1),
            splitUnsignedPczt = null,
            transferUnsignedPczts = emptyList(),
        )
        repo.set("accountA", pczts)
        assertNull(repo.get("accountB"))
        assertSame(pczts, repo.get("accountA"))
    }
}
