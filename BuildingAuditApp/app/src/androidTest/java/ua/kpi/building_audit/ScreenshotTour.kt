package ua.kpi.building_audit

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.kpi.building_audit.settings.ThemeMode
import java.io.File

/**
 * Проходить застосунок так, як це робить користувач, і знімає екран після кожного кроку.
 * Знімки робить системна утиліта screencap, тому в них потрапляють і мапа, і діалоги.
 * Потрібен тестовий сервер на комп'ютері-хості (10.0.2.2:5156).
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val shell = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** Натискає кнопку, якщо вона є на екрані; інакше повертається системною кнопкою «Назад». */
    private fun tapIfShown(text: String) {
        val nodes = compose.onAllNodesWithText(text).fetchSemanticsNodes()
        if (nodes.isNotEmpty()) compose.onAllNodesWithText(text).onFirst().performClick()
        else shell.executeShellCommand("input keyevent 4").close()
        pause(1_000)
    }

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        pause(500)
        shell.executeShellCommand("mkdir -p /sdcard/Download/screens").close()
        shell.executeShellCommand("screencap -p /sdcard/Download/screens/$name.png").close()
        Thread.sleep(1_000)
    }

    private fun open(destination: Destination) {
        compose.onNodeWithContentDescription("Меню").performClick()
        pause(600)
        compose.onNodeWithTag(drawerTag(destination)).performScrollTo().performClick()
        pause(1_500)
    }

    @Test
    fun tour() {
        compose.onNodeWithText("Аудитор").performClick()
        compose.onNodeWithText("Увійти").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(Destination.PZ21.title).fetchSemanticsNodes().isNotEmpty() }

        // Реєстр завантажує будівлі з сервера в Room; решта екранів читає кеш.
        open(Destination.PZ9)
        pause(4_000)
        shot("pz09_registry")

        open(Destination.PZ13)
        pause(12_000)
        shot("pz13_telemetry")

        compose.onNodeWithContentDescription("Меню").performClick()
        pause(600)
        shot("drawer")
        compose.onNodeWithTag(drawerTag(Destination.PZ14)).performScrollTo().performClick()
        pause(6_000)
        compose.onAllNodesWithText("Гуртожиток № 19").onLast().performClick()
        pause(4_000)
        shot("pz14_map_list")
        compose.onAllNodesWithText("Детальніше").onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        pause(1_500)
        shot("pz14_details")
        tapIfShown("До списку")
        pause(1_000)

        open(Destination.PZ15)
        compose.onAllNodesWithText("Житловий будинок").onFirst().performClick()
        compose.onAllNodesWithText("Потребує модернізації").onFirst().performClick()
        pause(1_000)
        shot("pz15_filters")

        open(Destination.PZ16)
        compose.onNodeWithText("Визначити моє місцезнаходження").performClick()
        pause(8_000)
        compose.onNodeWithText("≤ 1 км").performClick()
        pause(4_000)
        shot("pz16_gps")

        // Значення датчиків задає емулятор (adb emu sensor set), вологість 78 % дає ризик конденсату.
        open(Destination.PZ17)
        pause(5_000)
        shot("pz17_sensors")

        shell.executeShellCommand("cmd statusbar expand-notifications").close()
        pause(1_500)
        shot("pz18_shade")
        shell.executeShellCommand("cmd statusbar collapse").close()
        pause(800)

        open(Destination.PZ18)
        compose.onAllNodesWithText("Вихід за поріг").onFirst().performClick()
        pause(800)
        compose.onAllNodesWithText("Відкласти на 5 хв").onFirst().performClick()
        pause(800)
        shot("pz18_alerts")

        open(Destination.PZ19)
        compose.onNodeWithText("24 міс.").performClick()
        pause(1_500)
        shot("pz19_chart")

        open(Destination.PZ20)
        pause(3_000)
        shot("pz20_instruments")

        open(Destination.PZ21)
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onAllNodes(isToggleable()).onLast().performClick()
        pause(2_000)
        shot("pz21_animation")

        open(Destination.PZ22)
        pause(2_000)
        shot("pz22_readings")

        // Тестове зображення фасаду додається у вкладення першої будівлі так само, як після вибору з галереї.
        val buildingId = runBlocking { container.buildingRepository.observeCached().first().first().id }
        runBlocking { container.attachmentStore.import(share(facadeImage(), "facade.jpg"), buildingId) }

        open(Destination.PZ24)
        compose.onNodeWithText("Сформувати PDF").performClick()
        compose.waitUntil(20_000) { container.reportsDir.listFiles().orEmpty().isNotEmpty() }
        pause(1_500)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Відкрити"))
        pause(1_500)
        shot("pz24_report")
        val report = container.reportsDir.listFiles()!!.maxBy { it.lastModified() }
        runBlocking { container.attachmentStore.import(share(report, report.name), buildingId) }

        open(Destination.PZ23)
        pause(2_000)
        shot("pz23_attachments")

        open(Destination.PZ26)
        compose.onNodeWithText("Навантажити на 10 с").performClick()
        pause(4_000)
        shot("pz26_monitor")
        compose.onNodeWithText("Темна").performClick()
        pause(1_500)
        shot("pz26_dark")
        container.themeSettings.set(ThemeMode.SYSTEM)
    }

    private val container get() = (compose.activity.application as EnergyAuditApp).container

    private fun share(file: File, name: String): Uri {
        val target = File(compose.activity.cacheDir, "camera/$name").apply { parentFile?.mkdirs() }
        file.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.files", target)
    }

    /** Схематичний фасад: стіна, ряди вікон і дах. */
    private fun facadeImage(): File {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        canvas.drawColor(Color.rgb(190, 215, 235))
        paint.color = Color.rgb(176, 120, 90)
        canvas.drawRect(150f, 200f, 1050f, 860f, paint)
        paint.color = Color.rgb(90, 90, 100)
        canvas.drawRect(120f, 160f, 1080f, 210f, paint)
        paint.color = Color.rgb(225, 240, 250)
        for (row in 0 until 4) for (col in 0 until 6) {
            val left = 200f + col * 140f
            val top = 250f + row * 150f
            canvas.drawRect(left, top, left + 80f, top + 100f, paint)
        }
        val file = File(compose.activity.cacheDir, "facade_source.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }
}
