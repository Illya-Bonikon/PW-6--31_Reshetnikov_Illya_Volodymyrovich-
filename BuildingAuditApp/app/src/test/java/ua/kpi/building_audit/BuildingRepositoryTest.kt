package ua.kpi.building_audit

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.building_audit.data.local.AuditDao
import ua.kpi.building_audit.data.local.BuildingEntity
import ua.kpi.building_audit.data.local.CacheEntry
import ua.kpi.building_audit.data.local.LocalBuildingDataSource
import ua.kpi.building_audit.data.remote.RemoteBuildingDataSource
import ua.kpi.building_audit.data.remote.toDto
import ua.kpi.building_audit.data.repository.BuildingRepository
import ua.kpi.building_audit.data.repository.DataOrigin
import ua.kpi.building_audit.data.repository.DataSourceMode
import ua.kpi.building_audit.error.ErrorCenter
import ua.kpi.building_audit.error.ErrorJournal
import ua.kpi.building_audit.error.ErrorMapper
import ua.kpi.building_audit.error.JournalEntry
import ua.kpi.building_audit.error.Outcome
import ua.kpi.building_audit.network.ConnectivityStatus
import ua.kpi.building_audit.network.NetworkState
import ua.kpi.building_audit.network.RetrofitFactory

/** DAO у пам'яті з тією самою поведінкою, що й Room: сортування за назвою, upsert, заміна серверних копій. */
private class InMemoryDao : AuditDao() {
    private val buildings = MutableStateFlow<Map<Long, BuildingEntity>>(emptyMap())
    private val entries = MutableStateFlow<Map<String, CacheEntry>>(emptyMap())

    override fun observeBuildings(): Flow<List<BuildingEntity>> = buildings.map { it.values.sortedBy(BuildingEntity::name) }
    override suspend fun building(id: Long) = buildings.value[id]
    override suspend fun minId() = buildings.value.keys.minOrNull()
    override suspend fun upsert(entity: BuildingEntity) { buildings.value += entity.id to entity }
    override suspend fun upsertAll(entities: List<BuildingEntity>) { buildings.value += entities.associateBy { it.id } }
    override suspend fun delete(id: Long) { buildings.value -= id }
    override suspend fun deleteServerCopies() { buildings.value = buildings.value.filterKeys { it <= 0 } }
    override suspend fun entry(key: String) = entries.value[key]
    override fun observeEntry(key: String): Flow<CacheEntry?> = entries.map { it[key] }
    override suspend fun upsertEntry(entry: CacheEntry) { entries.value += entry.key to entry }
}

private class SilentJournal : ErrorJournal {
    override suspend fun record(entry: JournalEntry) = Unit
    override suspend fun recent(limit: Int) = emptyList<JournalEntry>()
    override suspend fun clear() = Unit
}

private class SwitchableConnectivity : ConnectivityStatus {
    override val state = MutableStateFlow(NetworkState(isOnline = true, transport = "test"))
    fun goOffline() { state.value = NetworkState.OFFLINE }
}

/** Репозиторій зі справжніми Retrofit, Gson і мапером проти MockWebServer; Room замінено DAO у пам'яті. */
class BuildingRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val gson = Gson()
    private val connectivity = SwitchableConnectivity()
    private var now = 1_000_000L
    private val errors = ErrorCenter(ErrorMapper(gson), SilentJournal(), connectivity, clock = { now }, sleep = {})
    private val api = RetrofitFactory.create(server.url("/api/v1/").toString(), gson, connectivity, { "token" }, logBodies = false)
    private val repository = BuildingRepository(
        LocalBuildingDataSource(InMemoryDao(), gson), RemoteBuildingDataSource(api), errors, clock = { now }, cacheTtlMs = 60_000
    )

    @After
    fun stop() = server.shutdown()

    private fun respondWithBuildings(vararg items: ua.kpi.building_audit.domain.model.Building) =
        server.enqueue(MockResponse().setBody(gson.toJson(items.map { it.toDto() })))

    @Test
    fun cacheIsRefreshedOnlyAfterTtl() = runTest {
        respondWithBuildings(DORMITORY)
        assertEquals(Outcome.Success(true), repository.refreshCache(force = false))
        assertEquals(listOf("Гуртожиток № 19"), repository.observeCached().first().map { it.name })

        now += 30_000
        assertEquals(Outcome.Success(false), repository.refreshCache(force = false))
        assertEquals(1, server.requestCount)

        now += 31_000
        respondWithBuildings(DORMITORY, DORMITORY.copy(id = 7, name = "Корпус № 7"))
        assertEquals(Outcome.Success(true), repository.refreshCache(force = false))
        assertEquals(2, repository.observeCached().first().size)
    }

    @Test
    fun offlineRefreshKeepsLastCachedData() = runTest {
        respondWithBuildings(DORMITORY)
        repository.refreshCache(force = true)
        connectivity.goOffline()

        val result = repository.refreshCache(force = true)

        assertTrue(result is Outcome.Failure)
        assertEquals(1, server.requestCount)
        assertEquals(listOf(DORMITORY.id), repository.observeCached().first().map { it.id })
    }

    @Test
    fun detailsFallBackToCacheWhenServerFails() = runTest {
        respondWithBuildings(DORMITORY)
        repository.refreshCache(force = true)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"INTERNAL_ERROR","message":"x"}}"""))

        val result = repository.details(DORMITORY.id)

        assertEquals(DataOrigin.CACHE, (result as Outcome.Success).value.origin)
        assertEquals(DORMITORY.name, result.value.value.name)
    }

    @Test
    fun localRecordsSurviveServerReplacement() = runTest {
        val saved = repository.save(DORMITORY.copy(id = 0, name = "Чернетка"), DataSourceMode.LOCAL) as Outcome.Success
        assertEquals(-1L, saved.value.id)

        respondWithBuildings(DORMITORY)
        repository.refreshCache(force = true)

        assertEquals(setOf(-1L, DORMITORY.id), repository.observeCached().first().map { it.id }.toSet())
    }

    @Test
    fun tariffsFallBackWithoutNetworkAndCache() = runTest {
        connectivity.goOffline()
        val tariffs = repository.tariffs()
        assertEquals(DataOrigin.FALLBACK, tariffs.origin)
        assertEquals(1654.06, tariffs.value.heatPriceUahPerGcal, 1e-9)
    }
}
