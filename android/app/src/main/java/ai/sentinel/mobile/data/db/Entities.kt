package ai.sentinel.mobile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A flow observed by the tunnel. provenance is always "captured" for real traffic. */
@Entity(
    tableName = "flows",
    indices = [Index("tsStart"), Index("category"), Index("dstIp")]
)
data class FlowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsStart: Long,
    val tsEnd: Long,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val protocol: String,
    val packetsFwd: Int,
    val packetsBwd: Int,
    val bytesFwd: Long,
    val bytesBwd: Long,
    val durationMs: Long,
    val category: String?,
    val confidence: Double?,
    val source: String,
    val provenance: String = "captured",
    val ruleFlags: String? = null,
    val modelVersion: String? = null,
    val captureMode: String = "dns",
    val domain: String? = null
)

@Entity(tableName = "alerts", indices = [Index("ts"), Index("acknowledged")])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val kind: String,
    val category: String,
    val severity: String,
    val confidence: Double?,
    val dstIp: String,
    val dstPort: Int,
    val reason: String,
    val flowId: Long? = null,
    val domain: String? = null,
    val acknowledged: Boolean = false,
    val topFeatures: String? = null
)

@Entity(tableName = "block_events", indices = [Index("ts")])
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val dstIp: String,
    val dstPort: Int,
    val ruleId: Long,
    val ruleDescription: String,
    val protocol: String,
    val bytesDropped: Long
)

@Entity(tableName = "rules", indices = [Index("enabled")])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val value: String,
    val action: String = "block",
    val enabled: Boolean = true,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val note: String = "",
    val hitCount: Long = 0
)

@Entity(tableName = "app_policy")
data class AppPolicyEntity(
    @PrimaryKey val packageName: String,
    val mode: String,
    val label: String
)

@Entity(tableName = "daily_stats")
data class DailyStatEntity(
    @PrimaryKey val day: String,
    val flows: Long = 0,
    val threats: Long = 0,
    val blocked: Long = 0,
    val bytes: Long = 0
)

@Entity(tableName = "intel_cache")
data class IntelCacheEntity(
    @PrimaryKey val indicator: String,
    val verdict: String,
    val source: String,
    val ts: Long
)
