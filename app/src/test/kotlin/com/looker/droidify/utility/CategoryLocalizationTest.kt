package com.looker.droidify.utility

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryLocalizationTest {

    @Test
    fun getLocalizedNameReturnsExactLocaleMatch() {
        CategoryLocalization.register("Internet", "fr-FR", "Internet Français")
        CategoryLocalization.register("Internet", "zh-CN", "网络")

        assertEquals("网络", CategoryLocalization.getLocalizedName("Internet", "zh-CN"))
        assertEquals("Internet Français", CategoryLocalization.getLocalizedName("Internet", "fr-FR"))
    }

    @Test
    fun getLocalizedNameFallsBackToLanguagePrefix() {
        CategoryLocalization.register("Development", "es", "Desarrollo")

        assertEquals("Desarrollo", CategoryLocalization.getLocalizedName("Development", "es-ES"))
    }

    @Test
    fun getLocalizedNameFallsBackToDefaultNameWhenUnregistered() {
        assertEquals("UnknownCategory", CategoryLocalization.getLocalizedName("UnknownCategory", "zh-CN"))
    }
}
