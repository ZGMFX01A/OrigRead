package me.ash.reader.infrastructure.share

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class NotionShareTargetTest {
    @Test
    fun `installed notion is available as dedicated target`() {
        val context = mock<Context>()
        val packageManager = mock<PackageManager>()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackageInfo(NotionShareTarget.packageName, 0)).thenReturn(PackageInfo())

        val availability = NotionShareTarget.availability(context)

        assertTrue(availability.detected)
        assertTrue(availability.available)
    }

    @Test
    fun `uninstalled notion is not exposed as dedicated target`() {
        val context = mock<Context>()
        val packageManager = mock<PackageManager>()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackageInfo(NotionShareTarget.packageName, 0))
            .thenThrow(PackageManager.NameNotFoundException())

        val availability = NotionShareTarget.availability(context)

        assertFalse(availability.detected)
        assertFalse(availability.available)
    }
}
