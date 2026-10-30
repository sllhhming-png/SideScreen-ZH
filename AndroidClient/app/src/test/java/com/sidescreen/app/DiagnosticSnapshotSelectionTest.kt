package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSnapshotSelectionTest {
    @Test fun settingsPrefersEntirePreDialogSnapshotAndRefreshExplicitlyUsesCurrent() {
        val stats=DiagnosticStats();val session=stats.beginSession(0)
        stats.received(session,0);val cached=stats.snapshot()
        stats.received(session,200_000_000);val current=stats.snapshot()
        val first=DiagnosticSnapshotSelection.choose(current,cached,true)
        assertSame(cached,first.snapshot);assertEquals(DiagnosticSnapshotOrigin.PRE_SETTINGS,first.origin)
        val refresh=DiagnosticSnapshotSelection.choose(current,cached,true,true)
        assertSame(current,refresh.snapshot);assertEquals(DiagnosticSnapshotOrigin.IN_DIALOG_REFRESH,refresh.origin)
    }

    @Test fun homeEndedSessionNewSessionAndNewCodecCannotBorrowAnObsoleteCache() {
        val stats=DiagnosticStats();val old=stats.beginSession(0);val cached=stats.snapshot()
        assertEquals(DiagnosticSnapshotOrigin.CURRENT,DiagnosticSnapshotSelection.choose(stats.snapshot(),cached,false).origin)
        stats.endSession(old);val ended=stats.snapshot()
        assertSame(ended,DiagnosticSnapshotSelection.choose(ended,cached,true).snapshot)
        val fresh=stats.beginSession(20_000_000);val current=stats.snapshot()
        assertSame(current,DiagnosticSnapshotSelection.choose(current,cached,true).snapshot)
        val beforeReset=stats.snapshot();stats.resetDecoder(fresh,30_000_000);val replaced=stats.snapshot()
        assertSame(replaced,DiagnosticSnapshotSelection.choose(replaced,beforeReset,true).snapshot)
    }
}
