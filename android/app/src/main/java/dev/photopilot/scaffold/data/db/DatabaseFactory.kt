package dev.photopilot.scaffold.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseFactory {

    const val DATABASE_NAME = "photopilot.db"

    fun builder(context: Context): RoomDatabase.Builder<PhotoPilotDatabase> =
        Room.databaseBuilder(context, PhotoPilotDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                PhotoPilotDatabase.MIGRATION_1_2,
                PhotoPilotDatabase.MIGRATION_2_3,
                PhotoPilotDatabase.MIGRATION_3_4,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    PhotoPilotDatabase.applyContractDdl(db, System.currentTimeMillis())
                }
            })
}
