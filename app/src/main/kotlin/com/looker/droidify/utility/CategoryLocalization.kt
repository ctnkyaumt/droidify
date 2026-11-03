package com.looker.droidify.utility

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object CategoryLocalization {
    // Map of defaultName -> (locale -> localizedName)
    private val translations = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    fun register(defaultName: String, locale: String, localizedName: String) {
        if (defaultName.isNotBlank() && localizedName.isNotBlank()) {
            translations.computeIfAbsent(defaultName) { ConcurrentHashMap() }[locale] = localizedName
        }
    }

    fun getLocalizedName(defaultName: String, requestedLocale: String? = null): String {
        val categoryMap = translations[defaultName] ?: return defaultName
        val targetLocale = requestedLocale?.takeIf { it.isNotBlank() && it != "system" }
            ?: DateFormatter.getEffectiveLocale().toLanguageTag()

        // 1. Exact match (e.g. "zh-CN", "fr-FR", "de-DE")
        categoryMap[targetLocale]?.let { return it }

        // 2. Language only match (e.g. "zh", "fr", "de")
        val language = targetLocale.substringBefore('-').substringBefore('_')
        categoryMap[language]?.let { return it }

        // 3. Dialect of same language
        categoryMap.entries.firstOrNull {
            it.key.startsWith("$language-", ignoreCase = true) || it.key.equals(language, ignoreCase = true)
        }?.value?.let { return it }

        // 4. Default english fallbacks
        categoryMap["en-US"]?.let { return it }
        categoryMap["en"]?.let { return it }

        return defaultName
    }
}
