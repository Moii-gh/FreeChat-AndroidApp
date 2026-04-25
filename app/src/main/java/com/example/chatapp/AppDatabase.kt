package com.example.chatapp

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chat_database"
                )
                .addMigrations(MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }

        private fun SupportSQLiteDatabase.addColumnIfMissing(
            tableName: String,
            columnName: String,
            definition: String
        ) {
            if (!hasColumn(tableName, columnName)) {
                execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $definition")
            }
        }

        val MIGRATION_1_3 = object : androidx.room.migration.Migration(1, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.addColumnIfMissing("chats", "isPinned", "INTEGER NOT NULL DEFAULT 0")
                database.addColumnIfMissing("chats", "lastUpdated", "INTEGER NOT NULL DEFAULT 0")
                database.addColumnIfMissing("chats", "summary", "TEXT NOT NULL DEFAULT ''")

                database.execSQL(
                    "UPDATE chats SET lastUpdated = timestamp " +
                        "WHERE lastUpdated IS NULL OR lastUpdated = 0"
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.addColumnIfMissing("chats", "summary", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.addColumnIfMissing("chats", "ownerKey", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.addColumnIfMissing("messages", "syncId", "TEXT NOT NULL DEFAULT ''")
                // Generate a random UUID for existing rows
                database.query("SELECT id FROM messages WHERE syncId = ''").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val uuid = java.util.UUID.randomUUID().toString()
                        database.execSQL("UPDATE messages SET syncId = '$uuid' WHERE id = $id")
                    }
                }
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.addColumnIfMissing("chats", "isDeleted", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.addColumnIfMissing("messages", "attachmentData", "TEXT")
                database.addColumnIfMissing("messages", "attachmentMimeType", "TEXT")
                database.addColumnIfMissing("messages", "attachmentFileName", "TEXT")
                database.addColumnIfMissing("messages", "attachmentContext", "TEXT")
            }
        }
    }
}
