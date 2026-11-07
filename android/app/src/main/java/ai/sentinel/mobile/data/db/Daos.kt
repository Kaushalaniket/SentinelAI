package ai.sentinel.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FlowDao {
    @Insert
    suspend fun insert(flow: FlowEntity): Long

    @Insert
    suspend fun insertAll(flows: List<FlowEntity>)

    @Query("SELECT * FROM flows ORDER BY tsStart DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<FlowEntity>

    @Query(
        """SELECT * FROM flows
           WHERE (:protocol = '' OR protocol = :protocol)
             AND (:category = '' OR category = :category)
             AND (:query = '' OR dstIp LIKE '%' || :query || '%' OR IFNULL(domain,'') LIKE '%' || :query || '%')
           ORDER BY tsStart DESC LIMIT :limit OFFSET :offset"""
    )
    suspend fun pageFiltered(protocol: String, category: String, query: String, limit: Int, offset: Int): List<FlowEntity>

    @Query("SELECT * FROM flows ORDER BY tsStart DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<FlowEntity>>

    @Query("SELECT * FROM flows WHERE id = :id")
    suspend fun byId(id: Long): FlowEntity?

    @Query("SELECT COUNT(*) FROM flows")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM flows WHERE tsStart >= :since")
    suspend fun countSince(since: Long): Long

    @Query("SELECT COUNT(*) FROM flows WHERE tsStart >= :since AND category IS NOT NULL AND category != 'benign'")
    suspend fun threatsSince(since: Long): Long

    @Query("SELECT SUM(bytesFwd + bytesBwd) FROM flows WHERE tsStart >= :since")
    suspend fun bytesSince(since: Long): Long?

    @Query(
        """SELECT dstIp, COUNT(*) AS hits, SUM(bytesFwd + bytesBwd) AS bytes
           FROM flows WHERE tsStart >= :since GROUP BY dstIp ORDER BY hits DESC LIMIT :limit"""
    )
    suspend fun topDestinations(since: Long, limit: Int): List<DestinationCount>

    @Query("SELECT category, COUNT(*) AS hits FROM flows WHERE tsStart >= :since AND category IS NOT NULL GROUP BY category ORDER BY hits DESC")
    suspend fun categoryCounts(since: Long): List<CategoryCount>

    @Query("SELECT * FROM flows WHERE tsStart >= :since ORDER BY tsStart DESC LIMIT :limit")
    suspend fun recentSince(since: Long, limit: Int): List<FlowEntity>

    @Query("DELETE FROM flows WHERE tsStart < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM flows WHERE id IN (SELECT id FROM flows ORDER BY tsStart DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM flows")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM flows")
    fun observeCount(): Flow<Long>
}

data class DestinationCount(val dstIp: String, val hits: Long, val bytes: Long)
data class CategoryCount(val category: String, val hits: Long)

@Dao
interface AlertDao {
    @Insert
    suspend fun insert(alert: AlertEntity): Long

    @Query("SELECT * FROM alerts ORDER BY ts DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<AlertEntity>

    @Query("SELECT * FROM alerts ORDER BY ts DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AlertEntity>

    @Query("SELECT COUNT(*) FROM alerts WHERE ts >= :since")
    suspend fun countSince(since: Long): Long

    @Query("SELECT COUNT(*) FROM alerts WHERE ts >= :since AND severity = 'high'")
    suspend fun highSeveritySince(since: Long): Long

    @Query("UPDATE alerts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE alerts SET acknowledged = 1")
    suspend fun acknowledgeAll(): Int

    @Query("DELETE FROM alerts WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM alerts")
    suspend fun deleteAll()
}

@Dao
interface BlockEventDao {
    @Insert
    suspend fun insert(event: BlockEventEntity)

    @Query("SELECT * FROM block_events ORDER BY ts DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<BlockEventEntity>

    @Query("SELECT COUNT(*) FROM block_events WHERE ts >= :since")
    suspend fun countSince(since: Long): Long

    @Query("SELECT * FROM block_events ORDER BY ts DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<BlockEventEntity>>

    @Query("DELETE FROM block_events WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM block_events")
    suspend fun deleteAll()
}

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun active(): List<RuleEntity>

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE rules SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun recordHit(id: Long)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rules WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int
}

@Dao
interface AppPolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: AppPolicyEntity)

    @Query("SELECT * FROM app_policy")
    suspend fun all(): List<AppPolicyEntity>

    @Query("SELECT * FROM app_policy")
    fun observeAll(): Flow<List<AppPolicyEntity>>

    @Query("SELECT packageName FROM app_policy WHERE mode = 'deny'")
    suspend fun deniedPackages(): List<String>

    @Query("SELECT packageName FROM app_policy WHERE mode = 'allow'")
    suspend fun allowedPackages(): List<String>

    @Query("DELETE FROM app_policy WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface DailyStatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: DailyStatEntity)

    @Query("SELECT * FROM daily_stats WHERE day >= :fromDay ORDER BY day ASC")
    suspend fun since(fromDay: String): List<DailyStatEntity>

    @Query("SELECT * FROM daily_stats ORDER BY day DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DailyStatEntity>
}

@Dao
interface IntelCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: IntelCacheEntity)

    @Query("SELECT * FROM intel_cache WHERE indicator = :indicator")
    suspend fun get(indicator: String): IntelCacheEntity?

    @Query("SELECT COUNT(*) FROM intel_cache")
    suspend fun count(): Long

    @Query("DELETE FROM intel_cache")
    suspend fun clear()
}
