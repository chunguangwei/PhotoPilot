package dev.photopilot.scaffold.data.repo

import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.WeatherSnapshotEntity
import dev.photopilot.scaffold.data.logic.WeatherLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val placeMutex = Mutex()

    override suspend fun snapshot(placeId: String): WeatherSnapshotEntity? = withContext(Dispatchers.IO) {
        db.weatherSnapshotDao().get(placeId)
    }

    override suspend fun saveSnapshot(snapshot: WeatherSnapshotEntity) = withContext(Dispatchers.IO) {
        db.weatherSnapshotDao().upsert(snapshot)
    }

    suspend fun places(): Map<String, WeatherLogic.StoredPlace> =
        WeatherLogic.decodePlaces(settings.rawValue(WeatherLogic.KEY_PLACES))

    override suspend fun place(placeId: String): WeatherLogic.StoredPlace? = places()[placeId]

    suspend fun putPlace(place: WeatherLogic.StoredPlace) = placeMutex.withLock {
        val current = places().toMutableMap()
        current[place.placeId] = place
        settings.putRaw(WeatherLogic.KEY_PLACES, WeatherLogic.encodePlaces(current))
        settings.putRaw(WeatherLogic.KEY_SELECTED_PLACE, place.placeId)
    }

    suspend fun putPlace(place: WeatherLogic.StoredPlace, followLocation: Boolean) {
        putPlace(place)
        settings.putRaw(
            WeatherLogic.KEY_FOLLOW_LOCATION,
            if (followLocation) AppSettingsRepository.VALUE_TRUE else AppSettingsRepository.VALUE_FALSE,
        )
    }

    suspend fun followsLocation(): Boolean =
        WeatherLogic.followsLocation(
            settings.rawValue(WeatherLogic.KEY_FOLLOW_LOCATION),
            settings.rawValue(WeatherLogic.KEY_SELECTED_PLACE),
        )

    suspend fun selectedPlace(): WeatherLogic.StoredPlace? {
        val all = places()
        if (all.isEmpty()) return null
        val selectedId = settings.rawValue(WeatherLogic.KEY_SELECTED_PLACE)
        return selectedId?.let { all[it] } ?: all.values.firstOrNull()
    }
}
