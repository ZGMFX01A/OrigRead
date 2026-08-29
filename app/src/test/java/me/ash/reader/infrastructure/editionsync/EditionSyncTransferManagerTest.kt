package me.ash.reader.infrastructure.editionsync

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EditionSyncTransferManagerTest {
    @Test
    fun `installed peer edition is exposed`() {
        val context = mock<Context>()
        val packageManager = mock<PackageManager>()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackageInfo("me.ash.reader.llm", 0)).thenReturn(PackageInfo())
        val manager = EditionSyncTransferManager(context, mock())

        assertTrue(manager.isPeerInstalled("me.ash.reader.llm"))
    }

    @Test
    fun `missing peer edition is hidden`() {
        val context = mock<Context>()
        val packageManager = mock<PackageManager>()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackageInfo("me.ash.reader.llm", 0))
            .thenThrow(PackageManager.NameNotFoundException())
        val manager = EditionSyncTransferManager(context, mock())

        assertFalse(manager.isPeerInstalled("me.ash.reader.llm"))
    }
}
