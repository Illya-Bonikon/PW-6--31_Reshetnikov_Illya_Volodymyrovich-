package ua.kpi.building_audit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.kpi.building_audit.data.files.ReadingsStore
import ua.kpi.building_audit.practical22.ReadingsScreen
import ua.kpi.building_audit.practical22.ReadingsViewModel
import ua.kpi.building_audit.settings.ThemeMode
import java.io.File

/**
 * UI-тести без сервера: екран показів працює з тимчасовою текою замість справжнього сховища,
 * навігація перевіряється на екранах, яким мережа не потрібна.
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<EnergyAuditApp>()
    private val dir = File(app.cacheDir, "ui-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        app.container.themeSettings.set(ThemeMode.SYSTEM)
    }

    private fun showReadings() {
        compose.setContent { ReadingsScreen(viewModel = ReadingsViewModel(ReadingsStore(dir, Gson()))) }
        // Перший запуск генерує 400 демонстраційних днів у фоновому потоці.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("з 90", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun newReadingAppearsInList() {
        showReadings()
        scrollTo("Новий показ")
        compose.onNodeWithText("Дата, РРРР-ММ-ДД").performTextClearance()
        compose.onNodeWithText("Дата, РРРР-ММ-ДД").performTextInput("2030-01-01")
        compose.onNodeWithText("Тепло, Гкал").performTextInput("1.75")
        compose.onNodeWithText("Ел., кВт·год").performTextInput("512")
        compose.onNodeWithText("Зберегти").performClick()

        // Запис у файл іде на Dispatchers.IO, якого синхронізація Compose не бачить.
        Thread.sleep(1_500)
        scrollTo("2030-01-01")
        compose.onNodeWithText("2030-01-01").assertIsDisplayed()
        // Після збереження форма пропонує наступну добу.
        compose.onNodeWithText("2030-01-02").assertExists()
    }

    @Test
    fun invalidNumberShowsValidationMessage() {
        showReadings()
        scrollTo("Новий показ")
        compose.onNodeWithText("Тепло, Гкал").performTextInput("багато")
        compose.onNodeWithText("Ел., кВт·год").performTextInput("1")
        compose.onNodeWithText("Зберегти").performClick()
        scrollTo("Перевірте дату (РРРР-ММ-ДД) і невід'ємні числові покази")
        compose.onNodeWithText("Перевірте дату (РРРР-ММ-ДД) і невід'ємні числові покази").assertIsDisplayed()
    }

    @Test
    fun drawerNavigatesAndThemeChoiceIsSaved() {
        compose.setContent { MainHub(app.container.errorCenter, Destination.PZ22.route) }
        compose.onNodeWithTag("screen_title").assertTextEquals(Destination.PZ22.title)

        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithTag(drawerTag(Destination.PZ26)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screen_title").assertTextEquals(Destination.PZ26.title)

        compose.onNodeWithText("Темна").performClick()
        assertEquals(ThemeMode.DARK, app.container.themeSettings.mode.value)
    }
}
