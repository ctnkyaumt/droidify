package com.looker.droidify.utility.extension

import com.looker.droidify.model.InstalledItem
import com.looker.droidify.model.Product
import com.looker.droidify.model.Repository
import com.looker.droidify.model.UpdateResolver
import com.looker.droidify.model.findSuggested
import com.looker.droidify.service.Connection
import com.looker.droidify.service.DownloadService
import com.looker.droidify.service.FailedUpdateTracker
import com.looker.droidify.utility.extension.android.Android

fun Connection<DownloadService.Binder, DownloadService>.startUpdate(
    packageName: String,
    installedItem: InstalledItem?,
    products: List<Pair<Product, Repository>>,
    isAutoUpdate: Boolean = false,
) {
    if (binder == null || products.isEmpty()) return

    val (product, repository) = products.findSuggested(installedItem) ?: return

    val selectedRelease = UpdateResolver.findSuggestedRelease(
        releases = product.releases,
        installedItem = installedItem,
        primaryPlatform = Android.primaryPlatform,
    ) ?: return

    if (isAutoUpdate && FailedUpdateTracker.shouldSkipAutoUpdate(packageName, selectedRelease.versionCode)) {
        return
    }

    if (installedItem != null &&
        !UpdateResolver.isSignatureCompatible(installedItem.signatures, selectedRelease.signature)
    ) {
        return
    }

    requireNotNull(binder).enqueue(
        packageName = packageName,
        name = product.name,
        repository = repository,
        release = selectedRelease,
        isUpdate = installedItem != null,
    )
}
