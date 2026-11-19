package com.finsmart.app

import org.junit.Assert.assertTrue
import org.junit.Test

class RetrofitClientTest {

    @Test
    fun baseUrlIsLoadedFromBuildConfig() {
        assertTrue(BuildConfig.BASE_URL.startsWith("http"))
    }
}