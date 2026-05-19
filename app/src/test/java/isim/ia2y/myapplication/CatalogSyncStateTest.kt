package isim.ia2y.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncStateTest {
    @Test
    fun isRefreshingOnlyDuringLoading() {
        assertTrue(CatalogSyncState(status = CatalogSyncStatus.LOADING).isRefreshing)
        assertFalse(CatalogSyncState(status = CatalogSyncStatus.SUCCESS).isRefreshing)
        assertFalse(CatalogSyncState(status = CatalogSyncStatus.ERROR).isRefreshing)
    }
}
