package app.opentv.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodRefreshPolicyTest {
    @Test fun neverSyncedCatalogueIsStale() {
        assertThat(VodRefreshPolicy.shouldRefresh(0L, 10_000L)).isTrue()
    }

    @Test fun freshCatalogueDoesNotRefreshOnHourlyWorkerRun() {
        val last = 1_000_000L
        assertThat(VodRefreshPolicy.shouldRefresh(last, last + 60 * 60 * 1000L)).isFalse()
    }

    @Test fun catalogueRefreshesAfterTwelveHours() {
        val last = 1_000_000L
        assertThat(VodRefreshPolicy.shouldRefresh(last, last + VodRefreshPolicy.TTL_MILLIS)).isTrue()
    }

    @Test fun forcedRefreshBypassesFreshnessWindow() {
        val last = 1_000_000L
        assertThat(VodRefreshPolicy.shouldRefresh(last, last + 1L, force = true)).isTrue()
    }

    @Test fun clockMovingBackwardsTreatsTimestampAsStale() {
        assertThat(VodRefreshPolicy.shouldRefresh(2_000L, 1_000L)).isTrue()
    }
}
