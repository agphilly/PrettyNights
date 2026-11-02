package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.config.AppConfig
import com.example.config.Io13PinRole
import com.example.config.WledHardwareConfig
import com.example.home.HomeContent
import com.example.patterns.PatternsContent
import com.example.settings.SettingsContent
import com.example.ui.theme.MyApplicationTheme
import com.example.wled.WledController
import com.example.wled.WledPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Pretty Nights", appName)
    }

    @Test
    fun wledController_buildsHardwareCfgAndSegmentsFromTypedConfig() {
        val controller = WledController(null)
        val hw = WledHardwareConfig(
            maxCurrentMa = 2500,
            ch1LedCount = 60,
            ch2Enabled = true,
            ch2LedCount = 24,
            io13Role = Io13PinRole.STRIP_CH3,
            ch3LedCount = 12
        )

        val cfgJson = controller.buildHardwareCfgJson(hw)
        assertTrue(cfgJson.contains("\"maxpwr\":2500"))
        assertTrue(cfgJson.contains("[16]"))
        assertTrue(cfgJson.contains("[2]"))
        assertTrue(cfgJson.contains("[13]"))

        val segJson = controller.buildSegmentLayoutJson(hw)
        assertTrue(segJson.contains("\"stop\":60"))
        assertTrue(segJson.contains("\"stop\":84"))
        assertTrue(segJson.contains("\"stop\":96"))
    }

    @Test
    fun statelessScreenContents_renderCleanlyWithoutApplicationSingleton() {
        var screenIndex by mutableStateOf(0)

        composeRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                when (screenIndex) {
                    0 -> HomeContent(config = AppConfig(logOnlyMode = true))
                    1 -> SettingsContent(
                        config = AppConfig(),
                        isLocked = false,
                        onConfigSave = {}
                    )
                    2 -> PatternsContent(
                        config = AppConfig(),
                        isLocked = false,
                        presets = listOf(WledPreset(1, "Amber")),
                        effects = listOf("Solid"),
                        palettes = listOf("Default"),
                        isFetching = false,
                        fetchFailed = false,
                        onConfigSave = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("app_logo_area").assertExists()

        screenIndex = 1
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("wled_hardware_config_card").assertExists()

        screenIndex = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Glow Pattern Assignments").assertExists()
    }
}
