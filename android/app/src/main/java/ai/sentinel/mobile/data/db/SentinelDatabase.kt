package ai.sentinel.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FlowEntity::class,
        AlertEntity::class,
        BlockEventEntity::class,
        RuleEntity::class,
        AppPolicyEntity::class,
        DailyStatEntity::class,
        IntelCacheEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SentinelDatabase : RoomDatabase() {
    abstract fun flowDao(): FlowDao
    abstract fun alertDao(): AlertDao
    abstract fun blockEventDao(): BlockEventDao
    abstract fun ruleDao(): RuleDao
    abstract fun appPolicyDao(): AppPolicyDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun intelCacheDao(): IntelCacheDao

    companion object {
        @Volatile
        private var instance: SentinelDatabase? = null

        fun get(context: Context): SentinelDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SentinelDatabase::class.java,
                    "sentinelai.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
