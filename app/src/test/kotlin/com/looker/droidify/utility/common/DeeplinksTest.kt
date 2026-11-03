package com.looker.droidify.utility.common

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeeplinksTest {

    @Test
    fun parseSearchFdroidOrgUrl() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://search.f-droid.org/?q=term"))
        val result = intent.deeplinkType()

        assertTrue(result is DeeplinkType.AppSearch)
        assertEquals("term", (result as DeeplinkType.AppSearch).query)
    }

    @Test
    fun parseSearchFdroidOrgUrlWithLang() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://search.f-droid.org/?q=my+search&lang=de"))
        val result = intent.deeplinkType()

        assertTrue(result is DeeplinkType.AppSearch)
        assertEquals("my search", (result as DeeplinkType.AppSearch).query)
    }

    @Test
    fun parseSearchFdroidOrgHttpUrl() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://search.f-droid.org/?q=matrix"))
        val result = intent.deeplinkType()

        assertTrue(result is DeeplinkType.AppSearch)
        assertEquals("matrix", (result as DeeplinkType.AppSearch).query)
    }
}
