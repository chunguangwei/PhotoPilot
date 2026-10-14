package dev.photopilot.scaffold.data.repo

import dev.photopilot.scaffold.data.db.SettingsKvDao
import dev.photopilot.scaffold.data.db.SettingsKvEntity
import dev.photopilot.scaffold.data.logic.LibraryVersionLogic

class LibraryVersionRepository(private val dao: SettingsKvDao) {

    suspend fun current(): Long? = LibraryVersionLogic.parseStored(dao.get(KEY)?.valueJson)

    suspend fun bump(now: Long): Long {
        val next = LibraryVersionLogic.nextFromStored(dao.get(KEY)?.valueJson)
        dao.upsert(
            SettingsKvEntity(
                key = KEY,
                valueJson = LibraryVersionLogic.storedJson(next),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return next
    }

    companion object {
        val KEY = LibraryVersionLogic.KEY
    }
}
