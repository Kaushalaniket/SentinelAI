package ai.sentinel.mobile.data

import ai.sentinel.mobile.data.db.AlertDao
import ai.sentinel.mobile.data.db.AlertEntity
import ai.sentinel.mobile.data.db.BlockEventDao
import ai.sentinel.mobile.data.db.BlockEventEntity
import ai.sentinel.mobile.data.db.CategoryCount
import ai.sentinel.mobile.data.db.DailyStatDao
import ai.sentinel.mobile.data.db.DailyStatEntity
import ai.sentinel.mobile.data.db.DestinationCount
import ai.sentinel.mobile.data.db.FlowDao
import ai.sentinel.mobile.data.db.FlowEntity
import ai.sentinel.mobile.data.db.RuleDao
import ai.sentinel.mobile.data.db.RuleEntity
import kotlinx.coroutines.flow.Flow

class FlowRepository(private val dao: FlowDao) {
    suspend fun insert(flow: FlowEntity): Long = dao.insert(flow)
    suspend fun recent(limit: Int = 50): List<FlowEntity> = dao.page(limit, 0)
    suspend fun page(limit: Int, offset: Int) = dao.page(limit, offset)
    suspend fun pageFiltered(protocol: String, category: String, query: String, limit: Int, offset: Int) =
        dao.pageFiltered(protocol, category, query, limit, offset)
    suspend fun byId(id: Long) = dao.byId(id)
    suspend fun count() = dao.count()
    suspend fun countSince(since: Long) = dao.countSince(since)
    suspend fun threatsSince(since: Long) = dao.threatsSince(since)
    suspend fun bytesSince(since: Long) = dao.bytesSince(since) ?: 0L
    suspend fun topDestinations(since: Long, limit: Int = 8): List<DestinationCount> =
        dao.topDestinations(since, limit)
    suspend fun categoryCounts(since: Long): List<CategoryCount> = dao.categoryCounts(since)
    suspend fun recentSince(since: Long, limit: Int = 200) = dao.recentSince(since, limit)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun trim(days: Int, rows: Int): Pair<Int, Int> {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val byAge = dao.deleteOlderThan(cutoff)
        val byCount = dao.trimToNewest(rows)
        return byAge to byCount
    }
    fun latest(limit: Int = 20): Flow<List<FlowEntity>> = dao.latest(limit)
    fun observeCount(): Flow<Long> = dao.observeCount()
}

class AlertRepository(private val dao: AlertDao) {
    suspend fun insert(alert: AlertEntity): Long = dao.insert(alert)
    suspend fun page(limit: Int, offset: Int) = dao.page(limit, offset)
    suspend fun recent(limit: Int = 200) = dao.recent(limit)
    suspend fun countSince(since: Long) = dao.countSince(since)
    suspend fun highSeveritySince(since: Long) = dao.highSeveritySince(since)
    suspend fun acknowledge(id: Long) = dao.acknowledge(id)
    suspend fun acknowledgeAll() = dao.acknowledgeAll()
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun trim(days: Int): Int =
        dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
    fun latest(limit: Int = 20): Flow<List<AlertEntity>> = dao.latest(limit)
}

class BlockEventRepository(private val dao: BlockEventDao) {
    suspend fun insert(event: BlockEventEntity) = dao.insert(event)
    suspend fun page(limit: Int, offset: Int) = dao.page(limit, offset)
    suspend fun countSince(since: Long) = dao.countSince(since)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun trim(days: Int): Int =
        dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
    fun latest(limit: Int = 20): Flow<List<BlockEventEntity>> = dao.latest(limit)
}

class RuleRepository(private val dao: RuleDao) {
    suspend fun all() = dao.all()
    suspend fun active() = dao.active()
    suspend fun insert(rule: RuleEntity): Long = dao.insert(rule)
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun recordHit(id: Long) = dao.recordHit(id)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun purgeExpired(): Int = dao.deleteExpired(System.currentTimeMillis())
    fun observeAll(): Flow<List<RuleEntity>> = dao.observeAll()
}

class StatsRepository(private val dao: DailyStatDao) {
    suspend fun upsert(stat: DailyStatEntity) = dao.upsert(stat)
    suspend fun since(fromDay: String) = dao.since(fromDay)
    suspend fun recent(limit: Int = 30) = dao.recent(limit)
}
