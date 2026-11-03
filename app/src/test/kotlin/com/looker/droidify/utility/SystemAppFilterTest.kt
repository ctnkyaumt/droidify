package com.looker.droidify.utility

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppFilterTest {

    @Test
    fun preinstalledSystemAppReturnsTrue() {
        val pi = PackageInfo().apply {
            applicationInfo = ApplicationInfo().apply {
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
        assertTrue(pi.isPreinstalledSystemApp())
    }

    @Test
    fun updatedSystemAppReturnsFalse() {
        val pi = PackageInfo().apply {
            applicationInfo = ApplicationInfo().apply {
                flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            }
        }
        assertFalse(pi.isPreinstalledSystemApp())
    }

    @Test
    fun regularUserAppReturnsFalse() {
        val pi = PackageInfo().apply {
            applicationInfo = ApplicationInfo().apply {
                flags = 0
            }
        }
        assertFalse(pi.isPreinstalledSystemApp())
    }

    @Test
    fun nullApplicationInfoReturnsFalse() {
        val pi = PackageInfo()
        assertFalse(pi.isPreinstalledSystemApp())
    }
}
