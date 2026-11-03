package com.looker.droidify.model

data class InstalledItem(
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val signature: String,
    val signatures: List<String> = if (signature.isBlank()) emptyList() else listOf(signature),
)
