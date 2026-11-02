package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.config.Io13PinRole
import com.example.config.WledHardwareConfig
import com.example.wled.WledController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

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
            io13Role = Io13PinRole.STRIP_CH3.wireValue,
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
}
