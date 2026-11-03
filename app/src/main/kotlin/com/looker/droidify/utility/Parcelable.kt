@file:Suppress("DEPRECATION")

package com.looker.droidify.utility

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String?): T? =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key)
        }
    } catch (_: Throwable) {
        try {
            getParcelableExtra(key) as? T
        } catch (_: Throwable) {
            null
        }
    }

inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String?): T? =
    try {
        classLoader = T::class.java.classLoader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            getParcelable(key)
        }
    } catch (_: Throwable) {
        try {
            getParcelable(key) as? T
        } catch (_: Throwable) {
            null
        }
    }

inline fun <reified T : Parcelable> Bundle.getParcelableArrayListCompat(key: String?): ArrayList<T>? =
    try {
        classLoader = T::class.java.classLoader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayList(key, T::class.java)
        } else {
            getParcelableArrayList(key)
        }
    } catch (_: Throwable) {
        try {
            getParcelableArrayList(key)
        } catch (_: Throwable) {
            null
        }
    }
