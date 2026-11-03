package com.looker.droidify.model

data class ProductPreference(
    val ignoreUpdates: Boolean,
    val ignoreVersionCode: Long,
    // Repository whose releases are used for updates, 0 means automatic
    val preferredRepositoryId: Long = 0L,
    val note: String = "",
) {
    fun shouldIgnoreUpdate(versionCode: Long): Boolean {
        return ignoreUpdates || ignoreVersionCode == versionCode
    }
}
