package dev.photopilot.scaffold.data.repo

import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.WeatherSnapshotEntity
import dev.photopilot.scaffold.data.logic.WeatherLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface WeatherStore {
    suspend fun snapshot(placeId: String): WeatherSnapshotEntity?
    suspend fun saveSnapshot(snapshot: WeatherSnapshotEntity)
    suspend fun place(placeId: String): WeatherLogic.StoredPlace?
}

class WeatherRepository(
    private val db: PhotoPilotDatabase,
    private val settings: AppSettingsRepository,
) : WeatherStore {

    override suspend fun snapshot(placeId: String): WeatherSnapshotEntity? = withContext(Dispatchers.IO) {
        db.weatherSnapshotDao().get(placeId)
    }

    override suspend fun saveSnapshot(snapshot: WeatherSnapshotEntity) = withContext(Dispatchers.IO) {
        db.weatherSnapshotDao().upsert(snapshot)
    }

    suspend fun places(): Map<String, WeatherLogic.StoredPlace> =
        WeatherLogic.decodePlaces(settings.rawValue(WeatherLogic.KEY_PLACES))

    override suspend fun place(placeId: String): WeatherLogic.StoredPlace? = places()[placeId]

    suspend fun putPlace(place: WeatherLogic.StoredPlace) {
        val current = places().toMutableMap()
        current[place.placeId] = place
        settings.putRaw(WeatherLogic.KEY_PLACES, WeatherLogic.encodePlaces(current))
    }
}
