package ai.sentinel.mobile

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.sentinel.mobile.data.db.AlertEntity
import ai.sentinel.mobile.data.db.FlowEntity
import ai.sentinel.mobile.data.db.RuleEntity
import ai.sentinel.mobile.data.db.SentinelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that Room actually persists to disk on the device: rows are written,
 * the database is closed, reopened, and read back. This is the check the dashboard depends on
 * and it cannot be run as a JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class DatabasePersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: SentinelDatabase? = null

    private fun open(): SentinelDatabase =
        Room.databaseBuilder(context, SentinelDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Before
    fun reset() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun flowSurvivesDatabaseReopen() = runBlocking {
        var database = open()
        val id = database.flowDao().insert(
            FlowEntity(
                tsStart = 1_000L,
                tsEnd = 2_000L,
                srcIp = "10.0.0.2",
                srcPort = 51000,
                dstIp = "93.184.216.34",
                dstPort = 443,
                protocol = "TCP",
                packetsFwd = 3,
                packetsBwd = 2,
                bytesFwd = 300,
                bytesBwd = 900,
                durationMs = 1_000,
                category = "portscan",
                confidence = 0.91,
                source = "ai",
                provenance = "captured",
                captureMode = "capture",
                domain = "example.com"
            )
        )
        assertTrue("insert must return a row id", id > 0)
        database.close()

        database = open()
        val restored = database.flowDao().byId(id)
        assertNotNull("row must survive a reopen", restored)
        assertEquals("93.184.216.34", restored!!.dstIp)
        assertEquals("portscan", restored.category)
        assertEquals(1L, database.flowDao().threatsSince(0L))
        assertEquals(1_200L, database.flowDao().bytesSince(0L))
        assertEquals(1, database.flowDao().protocolCounts(0L).size)
    }

    @Test
    fun ruleToggleAndAlertAcknowledgementPersist() = runBlocking {
        var database = open()
        val ruleId = database.ruleDao().insert(
            RuleEntity(
                type = "domain",
                value = "ads.example.com",
                action = "block",
                enabled = true,
                createdAt = 5_000L,
                note = "instrumented"
            )
        )
        assertTrue(ruleId > 0)
        assertEquals(1, database.ruleDao().active().size)

        database.ruleDao().setEnabled(ruleId, false)
        assertEquals(0, database.ruleDao().active().size)
        database.close()

        database = open()
        assertEquals(0, database.ruleDao().active().size)
        assertEquals(1, database.ruleDao().all().size)

        val alertId = database.alertDao().insert(
            AlertEntity(
                ts = 9_000L,
                kind = "ai",
                category = "portscan",
                severity = "high",
                confidence = 0.8,
                dstIp = "203.0.113.10",
                dstPort = 443,
                reason = "instrumented alert"
            )
        )
        assertEquals(1L, database.alertDao().unacknowledgedHighSince(0L))
        database.alertDao().acknowledge(alertId)
        assertEquals(0L, database.alertDao().unacknowledgedHighSince(0L))
        assertEquals(1, database.alertDao().severityCounts(0L).first().hits)
    }

    private companion object {
        const val DB_NAME = "sentinelai-instrumented.db"
    }
}
