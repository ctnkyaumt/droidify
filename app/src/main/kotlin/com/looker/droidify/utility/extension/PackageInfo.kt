package com.looker.droidify.utility.extension

import android.content.pm.PackageInfo
import com.looker.droidify.model.InstalledItem
import com.looker.droidify.utility.common.extension.allSignatureHashes
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.singleSignature
import com.looker.droidify.utility.common.extension.versionCodeCompat

fun PackageInfo.toInstalledItem(): InstalledItem {
    val sigList = allSignatureHashes
    val signatureString = singleSignature?.calculateHash().orEmpty()
        .ifEmpty { sigList.firstOrNull().orEmpty() }
    return InstalledItem(
        packageName = packageName,
        version = versionName.orEmpty(),
        versionCode = versionCodeCompat,
        signature = signatureString,
        signatures = if (sigList.isNotEmpty()) sigList else if (signatureString.isNotEmpty()) listOf(signatureString) else emptyList(),
    )
}
