package app.jev.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationRecord::class, TransactionRecord::class, SenderRule::class, MerchantRule::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notifications(): NotificationDao
    abstract fun transactions(): TransactionDao
    abstract fun rules(): RuleDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jev.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
