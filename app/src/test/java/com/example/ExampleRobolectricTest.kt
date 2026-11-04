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
import com.example.config.ChannelConfig
import com.example.config.SegmentConfig
import com.example.config.SegmentRole
import com.example.config.WledLook
import com.example.home.HomeContent
import com.example.patterns.PatternsContent
import com.example.settings.SettingsContent
import com.example.ui.theme.MyApplicationTheme
import com.example.wled.WledController
import com.example.wled.WledPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun wledController_buildsHardwareCfgAndChannelRelativeSegmentsAndPerSegmentTt() {
        val controller = WledController(null)
        val channels = listOf(
            ChannelConfig(
                gpioPin = 16,
                ledCount = 60,
                stripType = 22,
                colorOrder = 0,
                reversed = false,
                segments = listOf(
                    SegmentConfig(
                        wledSegmentId = 0,
                        startLed = 0,
                        endLed = 45,
                        label = "Main Frame",
                        role = SegmentRole.MAIN_FRAME
                    )
                )
            ),
            ChannelConfig(
                gpioPin = 2,
                ledCount = 30,
                stripType = 30,
                colorOrder = 1,
                reversed = true,
                segments = listOf(
                    SegmentConfig(
                        wledSegmentId = 1,
                        startLed = 5,
                        endLed = 25,
                        label = "Rear Tail",
                        role = SegmentRole.BRAKE_LIGHTS
                    )
                )
            )
        )

        val cfgJson = controller.buildHardwareCfgJson(channels)
        assertTrue(cfgJson.contains("\"total\":90"))
        assertTrue(cfgJson.contains("[16]"))
        assertTrue(cfgJson.contains("[2]"))

        // Channel 2 starts at global offset 60, so channel-relative [5, 25) maps to global [65, 85)
        val segJson = controller.buildSegmentLayoutJson(channels)
        val segRoot = JSONObject(segJson)
        val segArr = segRoot.getJSONArray("seg")
        assertEquals(2, segArr.length())

        val seg0 = segArr.getJSONObject(0)
        assertEquals(0, seg0.getInt("id"))
        assertEquals(0, seg0.getInt("start"))
        assertEquals(45, seg0.getInt("stop"))
        assertEquals("Main Frame", seg0.getString("n"))

        val seg1 = segArr.getJSONObject(1)
        assertEquals(1, seg1.getInt("id"))
        assertEquals(65, seg1.getInt("start"))
        assertEquals(85, seg1.getInt("stop"))
        assertEquals("Rear Tail", seg1.getString("n"))

        // Verify sendComposedState JSON has per-segment tt and no top-level tt/transition
        val composedJson = controller.buildComposedStateJson(
            segmentLooks = mapOf(
                0 to WledLook(effectId = 0, colors = listOf("#00B4FF"), transitionMs = 600),
                1 to WledLook(effectId = 0, colors = listOf("#FF0000"), transitionMs = 0)
            ),
            brightness = 1.0f
        )
        val stateObj = JSONObject(composedJson)
        assertFalse(stateObj.has("transition"))
        assertFalse(stateObj.has("tt"))
        val stateSegs = stateObj.getJSONArray("seg")
        assertEquals(6, stateSegs.getJSONObject(0).getInt("tt"))
        assertEquals(0, stateSegs.getJSONObject(1).getInt("tt"))
    }

    @Test
    fun statelessScreenContents_renderCleanlyWithChannelCoverageIndicators() {
        var screenIndex by mutableStateOf(0)
        val sampleConfig = AppConfig(
            channels = listOf(
                ChannelConfig(
                    gpioPin = 16,
                    ledCount = 60,
                    stripType = 22,
                    colorOrder = 0,
                    segments = listOf(
                        SegmentConfig(
                            wledSegmentId = 0,
                            startLed = 0,
                            endLed = 45,
                            label = "Top Tube",
                            role = SegmentRole.MAIN_FRAME
                        )
                    )
                )
            )
        )

        composeRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                when (screenIndex) {
                    0 -> HomeContent(config = AppConfig(logOnlyMode = true))
                    1 -> SettingsContent(
                        config = sampleConfig,
                        isLocked = false,
                        onConfigSave = {}
                    )
                    2 -> PatternsContent(
                        config = sampleConfig,
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
        composeRule.onNodeWithText("GPIO 16 — 60 LEDs").assertExists()
        composeRule.onNodeWithText("45 / 60 LEDs configured").assertExists()

        screenIndex = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Glow Pattern Assignments").assertExists()
    }
}
