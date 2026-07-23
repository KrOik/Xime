package com.kingzcheung.xime.speech.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsrServiceCredentialStoreTest {
    private val store = AsrServiceCredentialStore(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        store.clearToken()
    }

    @Test
    fun token可加密保存读取和清除() {
        store.setToken("  test-secret  ")

        assertTrue(store.hasToken())
        assertEquals("test-secret", store.getToken())
        val rawValues = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("asr_service_credentials", Context.MODE_PRIVATE)
            .all.values
            .map { it.toString() }
        assertFalse(rawValues.any { it.contains("test-secret") })

        store.clearToken()
        assertFalse(store.hasToken())
        assertEquals("", store.getToken())
    }
}
