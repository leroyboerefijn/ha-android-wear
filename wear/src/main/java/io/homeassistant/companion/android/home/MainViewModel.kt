package io.homeassistant.companion.android.home

import android.app.Application
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.HomeAssistantApplication
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.domain
import io.homeassistant.companion.android.common.data.websocket.WebSocketState
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.DeviceRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.EntityRegistryResponse
import io.homeassistant.companion.android.common.sensors.SensorManager
import io.homeassistant.companion.android.data.SimplifiedEntity
import io.homeassistant.companion.android.database.sensor.SensorDao
import io.homeassistant.companion.android.database.wear.FavoriteCaches
import io.homeassistant.companion.android.database.wear.FavoriteCachesDao
import io.homeassistant.companion.android.database.wear.FavoritesDao
import io.homeassistant.companion.android.database.wear.getAllFlow
import io.homeassistant.companion.android.sensors.SensorReceiver
import io.homeassistant.companion.android.util.RegistriesDataHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val favoritesDao: FavoritesDao,
    private val favoriteCachesDao: FavoriteCachesDao,
    private val sensorsDao: SensorDao,
    application: Application
) : AndroidViewModel(application) {

    companion object {
        const val TAG = "MainViewModel"
    }

    enum class LoadingState {
        LOADING, READY, ERROR
    }

    private lateinit var homePresenter: HomePresenter
    private var areaRegistry: List<AreaRegistryResponse>? = null
    private var deviceRegistry: List<DeviceRegistryResponse>? = null
    private var entityRegistry: List<EntityRegistryResponse>? = null

    // TODO: This is bad, do this instead: https://stackoverflow.com/questions/46283981/android-viewmodel-additional-arguments
    fun init(homePresenter: HomePresenter) {
        this.homePresenter = homePresenter
        loadSettings()
        loadEntities()
    }

    // entities
    var entities = mutableStateMapOf<String, Entity<*>>()
        private set

    private val _supportedEntities = MutableStateFlow(emptyList<String>())
    val supportedEntities = _supportedEntities.asStateFlow()

    /**
     * IDs of favorites in the Favorites database.
     */
    val favoriteEntityIds = favoritesDao.getAllFlow().collectAsState()
    private val favoriteCaches = favoriteCachesDao.getAll()

    var shortcutEntities = mutableStateListOf<SimplifiedEntity>()
        private set
    var areas = mutableListOf<AreaRegistryResponse>()
        private set

    var entitiesByArea = mutableStateMapOf<String, SnapshotStateList<Entity<*>>>()
        private set
    var entitiesByDomain = mutableStateMapOf<String, SnapshotStateList<Entity<*>>>()
        private set
    var entitiesByAreaOrder = mutableStateListOf<String>()
        private set
    var entitiesByDomainOrder = mutableStateListOf<String>()
        private set

    // Content of EntityListView
    var entityLists = mutableStateMapOf<String, List<Entity<*>>>()
    var entityListsOrder = mutableStateListOf<String>()
    var entityListFilter: (Entity<*>) -> Boolean = { true }

    // settings
    var loadingState = mutableStateOf(LoadingState.LOADING)
        private set
    var isHapticEnabled = mutableStateOf(false)
        private set
    var isToastEnabled = mutableStateOf(false)
        private set
    var isShowShortcutTextEnabled = mutableStateOf(false)
        private set
    var templateTileContent = mutableStateOf("")
        private set
    var templateTileRefreshInterval = mutableStateOf(0)
        private set

    fun supportedDomains(): List<String> = HomePresenterImpl.supportedDomains

    fun stringForDomain(domain: String): String? =
        HomePresenterImpl.domainsWithNames[domain]?.let { getApplication<Application>().getString(it) }

    val sensors = sensorsDao.getAllFlow().collectAsState()

    var availableSensors = emptyList<SensorManager.BasicSensor>()

    private fun loadSettings() {
        viewModelScope.launch {
            if (!homePresenter.isConnected()) {
                return@launch
            }
            shortcutEntities.addAll(homePresenter.getTileShortcuts())
            isHapticEnabled.value = homePresenter.getWearHapticFeedback()
            isToastEnabled.value = homePresenter.getWearToastConfirmation()
            isShowShortcutTextEnabled.value = homePresenter.getShowShortcutText()
            templateTileContent.value = homePresenter.getTemplateTileContent()
            templateTileRefreshInterval.value = homePresenter.getTemplateTileRefreshInterval()
        }
    }

    fun loadEntities() {
        viewModelScope.launch {
            if (!homePresenter.isConnected()) {
                return@launch
            }
            try {
                // Load initial state
                loadingState.value = LoadingState.LOADING
                updateUI()

                // Finished initial load, update state
                val webSocketState = homePresenter.getWebSocketState()
                if (webSocketState == WebSocketState.CLOSED_AUTH) {
                    homePresenter.onInvalidAuthorization()
                    return@launch
                }
                loadingState.value = if (webSocketState == WebSocketState.ACTIVE) {
                    LoadingState.READY
                } else {
                    LoadingState.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while loading entities", e)
                loadingState.value = LoadingState.ERROR
            }
        }
    }

    private fun updateEntityStates(entity: Entity<*>) {
        if (supportedDomains().contains(entity.domain)) {
            entities[entity.entityId] = entity
            // add to cache if part of favorites
            if (favoriteEntityIds.value.contains(entity.entityId)) {
                addCachedFavorite(entity.entityId)
            }
        }
    }

    suspend fun updateUI() = withContext(Dispatchers.IO) {
        val getAreaRegistry = async { homePresenter.getAreaRegistry() }
        val getDeviceRegistry = async { homePresenter.getDeviceRegistry() }
        val getEntityRegistry = async { homePresenter.getEntityRegistry() }
        val getEntities = async { homePresenter.getEntities() }

        areaRegistry = getAreaRegistry.await()?.also {
            areas.clear()
            areas.addAll(it)
        }
        deviceRegistry = getDeviceRegistry.await()
        entityRegistry = getEntityRegistry.await()

        _supportedEntities.value = getSupportedEntities()

        getEntities.await()?.also {
            entities.clear()
            it.forEach { state -> updateEntityStates(state) }
        }
        updateEntityDomains()
    }

    suspend fun entityUpdates() {
        if (!homePresenter.isConnected())
            return
        homePresenter.getEntityUpdates(supportedEntities.value)?.collect {
            updateEntityStates(it)
            updateEntityDomains()
        }
    }

    suspend fun areaUpdates() {
        if (!homePresenter.isConnected())
            return
        homePresenter.getAreaRegistryUpdates()?.collect {
            areaRegistry = homePresenter.getAreaRegistry()
            areas.clear()
            areaRegistry?.let {
                areas.addAll(it)
            }
            updateEntityDomains()
        }
    }

    suspend fun deviceUpdates() {
        if (!homePresenter.isConnected())
            return
        homePresenter.getDeviceRegistryUpdates()?.collect {
            deviceRegistry = homePresenter.getDeviceRegistry()
            updateEntityDomains()
        }
    }

    suspend fun entityRegistryUpdates() {
        if (!homePresenter.isConnected())
            return
        homePresenter.getEntityRegistryUpdates()?.collect {
            entityRegistry = homePresenter.getEntityRegistry()
            _supportedEntities.value = getSupportedEntities()
            updateEntityDomains()
        }
    }

    private fun getSupportedEntities(): List<String> =
        entityRegistry
            .orEmpty()
            .map { it.entityId }
            .filter { it.split(".")[0] in supportedDomains() }

    private fun updateEntityDomains() {
        val entitiesList = entities.values.toList().sortedBy { it.entityId }
        val areasList = areaRegistry.orEmpty().sortedBy { it.name }
        val domainsList = entitiesList.map { it.domain }.distinct()

        // Create a list with all areas + their entities
        areasList.forEach { area ->
            val entitiesInArea = mutableStateListOf<Entity<*>>()
            entitiesInArea.addAll(
                entitiesList
                    .filter { getAreaForEntity(it.entityId)?.areaId == area.areaId }
                    .map { it as Entity<Map<String, Any>> }
                    .sortedBy { (it.attributes["friendly_name"] ?: it.entityId) as String }
            )
            entitiesByArea[area.areaId]?.let {
                it.clear()
                it.addAll(entitiesInArea)
            } ?: run {
                entitiesByArea[area.areaId] = entitiesInArea
            }
        }
        entitiesByAreaOrder.clear()
        entitiesByAreaOrder.addAll(areasList.map { it.areaId })
        // Quick check: are there any areas in the list that no longer exist?
        entitiesByArea.forEach {
            if (!areasList.any { item -> item.areaId == it.key }) {
                entitiesByArea.remove(it.key)
            }
        }

        // Create a list with all discovered domains + their entities
        domainsList.forEach { domain ->
            val entitiesInDomain = mutableStateListOf<Entity<*>>()
            entitiesInDomain.addAll(entitiesList.filter { it.domain == domain })
            entitiesByDomain[domain]?.let {
                it.clear()
                it.addAll(entitiesInDomain)
            } ?: run {
                entitiesByDomain[domain] = entitiesInDomain
            }
        }
        entitiesByDomainOrder.clear()
        entitiesByDomainOrder.addAll(domainsList)
    }

    fun toggleEntity(entityId: String, state: String) {
        viewModelScope.launch {
            homePresenter.onEntityClicked(entityId, state)
        }
    }
    fun setFanSpeed(entityId: String, speed: Float) {
        viewModelScope.launch {
            homePresenter.onFanSpeedChanged(entityId, speed)
        }
    }
    fun setBrightness(entityId: String, brightness: Float) {
        viewModelScope.launch {
            homePresenter.onBrightnessChanged(entityId, brightness)
        }
    }
    fun setColorTemp(entityId: String, colorTemp: Float) {
        viewModelScope.launch {
            homePresenter.onColorTempChanged(entityId, colorTemp)
        }
    }

    fun enableDisableSensor(sensorManager: SensorManager, sensorId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val basicSensor = sensorManager.getAvailableSensors(getApplication())
                .first { basicSensor -> basicSensor.id == sensorId }
            updateSensorEntity(sensorsDao, basicSensor, isEnabled)

            if (isEnabled) try {
                sensorManager.requestSensorUpdate(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Exception while requesting update for sensor $sensorId", e)
            }
        }
    }

    private suspend fun updateSensorEntity(
        sensorDao: SensorDao,
        basicSensor: SensorManager.BasicSensor,
        isEnabled: Boolean
    ) {
        sensorDao.setSensorsEnabled(listOf(basicSensor.id), isEnabled)
        SensorReceiver.updateAllSensors(getApplication())
    }

    fun updateAllSensors(sensorManager: SensorManager) {
        availableSensors = emptyList()
        viewModelScope.launch {
            val context = getApplication<HomeAssistantApplication>().applicationContext
            availableSensors = sensorManager
                .getAvailableSensors(context)
                .sortedBy { context.getString(it.name) }.distinct()
        }
    }

    fun initAllSensors() {
        viewModelScope.launch {
            for (manager in SensorReceiver.MANAGERS) {
                for (basicSensor in manager.getAvailableSensors(getApplication())) {
                    manager.isEnabled(getApplication(), basicSensor.id)
                }
            }
        }
    }

    fun getAreaForEntity(entityId: String): AreaRegistryResponse? =
        RegistriesDataHandler.getAreaForEntity(entityId, areaRegistry, deviceRegistry, entityRegistry)

    fun getCategoryForEntity(entityId: String): String? =
        RegistriesDataHandler.getCategoryForEntity(entityId, entityRegistry)

    fun getHiddenByForEntity(entityId: String): String? =
        RegistriesDataHandler.getHiddenByForEntity(entityId, entityRegistry)

    /**
     * Clears all favorites in the database.
     */
    fun clearFavorites() {
        viewModelScope.launch {
            favoritesDao.deleteAll()
        }
    }

    fun setTileShortcut(index: Int, entity: SimplifiedEntity) {
        viewModelScope.launch {
            if (index < shortcutEntities.size) {
                shortcutEntities[index] = entity
            } else {
                shortcutEntities.add(entity)
            }
            homePresenter.setTileShortcuts(shortcutEntities)
        }
    }

    fun clearTileShortcut(index: Int) {
        viewModelScope.launch {
            if (index < shortcutEntities.size) {
                shortcutEntities.removeAt(index)
                homePresenter.setTileShortcuts(shortcutEntities)
            }
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        viewModelScope.launch {
            homePresenter.setWearHapticFeedback(enabled)
            isHapticEnabled.value = enabled
        }
    }

    fun setToastEnabled(enabled: Boolean) {
        viewModelScope.launch {
            homePresenter.setWearToastConfirmation(enabled)
            isToastEnabled.value = enabled
        }
    }

    fun setShowShortcutTextEnabled(enabled: Boolean) {
        viewModelScope.launch {
            homePresenter.setShowShortcutTextEnabled(enabled)
            isShowShortcutTextEnabled.value = enabled
        }
    }

    fun setTemplateTileContent(content: String) {
        viewModelScope.launch {
            homePresenter.setTemplateTileContent(content)
            templateTileContent.value = content
        }
    }

    fun setTemplateTileRefreshInterval(interval: Int) {
        viewModelScope.launch {
            homePresenter.setTemplateTileRefreshInterval(interval)
            templateTileRefreshInterval.value = interval
        }
    }

    fun addFavoriteEntity(entityId: String) {
        viewModelScope.launch {
            favoritesDao.addToEnd(entityId)
            addCachedFavorite(entityId)
        }
    }

    fun removeFavoriteEntity(entityId: String) {
        viewModelScope.launch {
            favoritesDao.delete(entityId)
            favoriteCachesDao.delete(entityId)
        }
    }

    fun getCachedEntity(entityId: String): FavoriteCaches? =
        favoriteCaches.find { it.id == entityId }

    private fun addCachedFavorite(entityId: String) {
        viewModelScope.launch {
            val entity = entities[entityId]
            val attributes = entity?.attributes as Map<*, *>
            val icon = attributes["icon"] as String?
            val name = attributes["friendly_name"]?.toString() ?: entityId
            favoriteCachesDao.add(FavoriteCaches(entityId, name, icon))
        }
    }

    fun logout() {
        homePresenter.onLogoutClicked()

        // also clear cache when logging out
        clearCache()
    }

    private fun clearCache() {
        viewModelScope.launch {
            favoriteCachesDao.deleteAll()
        }
    }

    /**
     * Convert a Flow into a State object that updates until the view model is cleared.
     */
    private fun <T> Flow<T>.collectAsState(
        initial: T
    ): State<T> {
        val state = mutableStateOf(initial)
        viewModelScope.launch {
            collect { state.value = it }
        }
        return state
    }
    private fun <T> Flow<List<T>>.collectAsState(): State<List<T>> = collectAsState(initial = emptyList())
}
