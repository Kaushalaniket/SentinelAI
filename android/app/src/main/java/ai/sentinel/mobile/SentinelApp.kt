package ai.sentinel.mobile

import android.app.Application
import ai.sentinel.mobile.data.AlertRepository
import ai.sentinel.mobile.data.BlockEventRepository
import ai.sentinel.mobile.data.FlowRepository
import ai.sentinel.mobile.data.RuleRepository
import ai.sentinel.mobile.data.SettingsStore
import ai.sentinel.mobile.data.StatsRepository
import ai.sentinel.mobile.data.db.SentinelDatabase
import ai.sentinel.mobile.model.ModelContract
import ai.sentinel.mobile.model.OnnxDetector
import ai.sentinel.mobile.util.Notifications
import ai.sentinel.mobile.work.RetentionWorker

class SentinelApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        RetentionWorker.schedule(this)
    }
}

/**
 * Hand-wired dependency container. One database, one settings store, one detector; the
 * detector is created lazily so a cold start does not page in an 11 MB model.
 */
class AppContainer(app: Application) {
    val settings: SettingsStore = SettingsStore(app)
    private val database: SentinelDatabase by lazy { SentinelDatabase.get(app) }
    val flows: FlowRepository by lazy { FlowRepository(database.flowDao()) }
    val alerts: AlertRepository by lazy { AlertRepository(database.alertDao()) }
    val blocks: BlockEventRepository by lazy { BlockEventRepository(database.blockEventDao()) }
    val rules: RuleRepository by lazy { RuleRepository(database.ruleDao()) }
    val stats: StatsRepository by lazy { StatsRepository(database.dailyStatDao()) }
    val databaseProvider: SentinelDatabase get() = database
    val contract: ModelContract by lazy { ModelContract.load(app) }
    val detector: OnnxDetector by lazy { OnnxDetector(app, contract) }
}
