package fi.bundo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import fi.bundo.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdventureTrailUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun light_is_decorative_and_app_system_and_compact_modes_stop_it() {
        val motion = HouseholdMotion().apply { preferred = true; systemEnabled = true }
        var completed by mutableIntStateOf(2)
        var compact by mutableStateOf(false)
        lateinit var owner: LifecycleOwner
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
            compose.activity.setContent {
            CompositionLocalProvider(LocalHouseholdMotion provides motion, LocalLifecycleOwner provides owner) {
                BunDoTheme("light") { Surface { Box(Modifier.width(300.dp).padding(24.dp)) {
                    BunAdventureTrail(completed, 4, animated = !compact, compact = compact)
                } } }
            }
        } }
        compose.mainClock.advanceTimeBy(32)
        fun image() = compose.onNodeWithTag("bun-adventure-trail").captureToImage().asAndroidBitmap()
        val resting = image()
        compose.mainClock.advanceTimeBy(5300)
        assertFalse("Active trail should have a brief glint", resting.sameAs(image()))
        fun staysStill() {
            compose.mainClock.advanceTimeBy(32)
            val before = image()
            compose.mainClock.advanceTimeBy(5400)
            assertTrue("Decorative motion must stop", before.sameAs(image()))
        }
        compose.runOnUiThread { motion.preferred = false }
        staysStill()
        compose.runOnUiThread { motion.preferred = true; motion.systemEnabled = false }
        staysStill()
        compose.runOnUiThread { motion.systemEnabled = true; compact = true }
        staysStill()
        compose.runOnUiThread {
            compact = false
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED
        }
        staysStill()
        compose.runOnUiThread {
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
            completed = 4
        }
        compose.mainClock.advanceTimeBy(500)
        staysStill()
    }
}
