package ua.kpi.building_audit

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.kpi.building_audit.data.files.ReadingsStore
import ua.kpi.building_audit.data.files.StorageFormat
import ua.kpi.building_audit.domain.readings.MeterReading
import ua.kpi.building_audit.domain.readings.Period
import ua.kpi.building_audit.domain.readings.ReadingStatus
import ua.kpi.building_audit.domain.readings.ReadingsCsv
import ua.kpi.building_audit.domain.readings.ReadingsSeed
import ua.kpi.building_audit.practical22.ReadingsViewModel
import java.time.LocalDate

class ReadingsCsvTest {
    private val day = LocalDate.of(2026, 11, 20)
    private val tricky = MeterReading(day, 1.25, 430.5, ReadingStatus.ESTIMATED, "лічильник \"А\"; повірка\nнаступного тижня")

    @Test
    fun roundTripKeepsQuotesSeparatorsAndNewlines() {
        val result = ReadingsCsv.read(ReadingsCsv.write(listOf(tricky)))
        assertEquals(listOf(tricky), result.readings)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun badRowsAreReportedWithoutStoppingParse() {
        val csv = """
            date;heat_gcal;electricity_kwh;status;note
            2026-11-01;1.2;400;NORMAL;
            01.11.2026;1.2;400;NORMAL;
            2026-11-02;багато;400;NORMAL;
            2026-11-03;1,5;410;;з комою
            2026-11-04;-1;400;NORMAL;
            2026-11-05;1;400;BROKEN;
        """.trimIndent()
        val result = ReadingsCsv.read(csv)
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)), result.readings.map { it.date })
        assertEquals(1.5, result.readings[1].heatGcal, 1e-9)
        assertEquals(4, result.errors.size)
        assertTrue(result.errors[0].startsWith("Рядок 3"))
    }

    @Test
    fun headerIsOptionalAndWindowsLineEndingsAccepted() {
        val result = ReadingsCsv.read("2026-11-01;1;2;NORMAL;\r\n2026-11-02;3;4\r\n")
        assertEquals(2, result.readings.size)
        assertEquals("", result.readings[0].note)
    }

    @Test
    fun periodIncludesTodayAndCutsOlderDays() {
        val readings = ReadingsSeed.generate(day, days = 100)
        assertEquals(30, Period.MONTH.filter(readings, day).size)
        assertEquals(day, Period.MONTH.filter(readings, day).last().date)
        assertEquals(100, Period.ALL.filter(readings, day).size)
    }

    @Test
    fun seedHasWinterPeakAndNoSummerHeating() {
        val year = ReadingsSeed.generate(LocalDate.of(2026, 12, 31), days = 365)
        val january = year.filter { it.date.monthValue == 1 }.sumOf { it.heatGcal }
        val july = year.filter { it.date.monthValue == 7 }.sumOf { it.heatGcal }
        assertTrue(january > 30)
        assertEquals(0.0, july, 1e-9)
    }
}

class ReadingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun savesBothFormatsAndReadsEitherBack() = runTest {
        val store = ReadingsStore(folder.root, Gson(), Dispatchers.Unconfined)
        val readings = ReadingsSeed.generate(LocalDate.of(2026, 11, 20), days = 10)
        store.save(readings.shuffled())
        assertEquals(readings, store.load(StorageFormat.CSV).readings)
        assertEquals(readings, store.load(StorageFormat.JSON).readings)
        assertTrue(folder.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun corruptJsonGivesErrorInsteadOfCrash() {
        val store = ReadingsStore(folder.root, Gson())
        val result = store.decode("{not json", StorageFormat.JSON)
        assertTrue(result.readings.isEmpty())
        assertTrue(result.errors.single().startsWith("Файл не є коректним JSON"))
    }

    @Test
    fun jsonRecordWithUnknownStatusIsSkipped() {
        val store = ReadingsStore(folder.root, Gson())
        val json = """[{"date":"2026-11-01","heat_gcal":1.0,"electricity_kwh":2.0,"status":"NORMAL"},
                      {"date":"2026-11-02","heat_gcal":1.0,"electricity_kwh":2.0,"status":"???"}]"""
        val result = store.decode(json, StorageFormat.JSON)
        assertEquals(1, result.readings.size)
        assertEquals(1, result.errors.size)
    }
}

/** ViewModel разом зі справжнім сховищем у тимчасовій теці: перевіряється весь шлях форма → файл → список. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingsViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ReadingsViewModel(ReadingsStore(folder.root, Gson(), dispatcher))

    @Test
    fun firstLaunchSeedsDemoData() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(400, vm.state.value.all.size)
        assertTrue(vm.state.value.fileSizes.values.all { it > 0 })
    }

    @Test
    fun submittedReadingReplacesSameDayAndIsPersisted() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val today = LocalDate.now().toString()
        vm.editForm { it.copy(date = today, heat = "2,5", electricity = "500") }
        vm.submit()
        advanceUntilIdle()
        val saved = vm.state.value.all.filter { it.date.toString() == today }
        assertEquals(1, saved.size)
        assertEquals(2.5, saved.single().heatGcal, 1e-9)

        val reopened = viewModel()
        advanceUntilIdle()
        assertEquals(2.5, reopened.state.value.all.first { it.date.toString() == today }.heatGcal, 1e-9)
    }

    @Test
    fun invalidFormShowsErrorAndSavesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val before = vm.state.value.all.size
        vm.editForm { it.copy(date = "вчора", heat = "1", electricity = "1") }
        vm.submit()
        advanceUntilIdle()
        assertNotNull(vm.state.value.formError)
        assertEquals(before, vm.state.value.all.size)
    }

    @Test
    fun importMergesAndReportsCount() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.importText("2001-01-01;1;1;NORMAL;\nбитий рядок", StorageFormat.CSV)
        advanceUntilIdle()
        assertTrue(vm.state.value.all.any { it.date == LocalDate.of(2001, 1, 1) })
        assertEquals("Імпортовано 1 записів", vm.state.value.errors.first())
        assertEquals(2, vm.state.value.errors.size)
    }
}
