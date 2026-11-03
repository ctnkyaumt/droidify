package com.looker.droidify.service

import com.looker.droidify.model.Release
import com.looker.droidify.model.Repository

data class DownloadTask(
    val packageName: String,
    val name: String,
    val repository: Repository,
    val release: Release,
    val isUpdate: Boolean = false,
) {
    val url: String
        get() = release.getDownloadUrl(repository)
    val authentication: String
        get() = repository.authentication
    val notificationTag: String
        get() = "$packageName:${release.identifier}"
}

class DownloadQueue {
    val pendingTasks = mutableListOf<DownloadTask>()
    var activeTask: DownloadTask? = null
        private set

    // Stub / Old behaviour: blindly adds tasks without checking for active or pending duplicates
    fun enqueue(task: DownloadTask): Boolean {
        pendingTasks.add(task)
        return true
    }

    fun poll(): DownloadTask? {
        if (pendingTasks.isEmpty()) return null
        val next = pendingTasks.removeAt(0)
        activeTask = next
        return next
    }

    fun completeActive(packageName: String): Boolean {
        if (activeTask?.packageName == packageName) {
            activeTask = null
            return true
        }
        return false
    }

    fun cancel(packageName: String?): Boolean {
        var removed = false
        if (packageName == null) {
            removed = pendingTasks.isNotEmpty() || activeTask != null
            pendingTasks.clear()
            activeTask = null
        } else {
            removed = pendingTasks.removeAll { it.packageName == packageName }
            if (activeTask?.packageName == packageName) {
                activeTask = null
                removed = true
            }
        }
        return removed
    }

    fun isEnqueuedOrActive(packageName: String): Boolean =
        activeTask?.packageName == packageName || pendingTasks.any { it.packageName == packageName }

    val queuedPackageNames: List<String>
        get() = pendingTasks.map { it.packageName }

    val size: Int
        get() = pendingTasks.size + (if (activeTask != null) 1 else 0)
}
