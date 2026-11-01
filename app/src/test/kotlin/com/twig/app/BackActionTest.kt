package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackActionTest {
    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("twig_prefs", 0)

    @Before fun setup() {
        prefs.edit().clear().commit()
    }

    @Test fun `fresh installs and legacy disabled setting close the screen`() {
        assertEquals(Prefs.BackAction.CLOSE, Prefs.backAction(ctx))
        prefs.edit().putBoolean("exit_on_back", false).commit()
        assertEquals(Prefs.BackAction.CLOSE, Prefs.backAction(ctx))
    }

    @Test fun `legacy enabled setting retains confirmed exit until explicitly changed`() {
        prefs.edit().putBoolean("exit_on_back", true).commit()
        assertEquals(Prefs.BackAction.EXIT, Prefs.backAction(ctx))
        Prefs.setBackAction(ctx, Prefs.BackAction.UP)
        assertEquals(Prefs.BackAction.UP, Prefs.backAction(ctx))
        assertFalse(prefs.contains("exit_on_back"))
        Prefs.setBackAction(ctx, Prefs.BackAction.CLOSE)
        assertEquals(Prefs.BackAction.CLOSE, Prefs.backAction(ctx))
    }

    @Test fun `explicit choice overrides legacy values in imported preferences`() {
        Prefs.setBackAction(ctx, Prefs.BackAction.CLOSE)
        prefs.edit().putBoolean("exit_on_back", true).commit()
        assertEquals(Prefs.BackAction.CLOSE, Prefs.backAction(ctx))
    }

    @Test fun `up choices keep independent behavior at the tree top`() {
        Prefs.setBackAction(ctx, Prefs.BackAction.UP)
        assertEquals(Prefs.BackAction.UP, Prefs.backAction(ctx))
        Prefs.setBackAction(ctx, Prefs.BackAction.UP_EXIT)
        assertEquals(Prefs.BackAction.UP_EXIT, Prefs.backAction(ctx))
    }
}
