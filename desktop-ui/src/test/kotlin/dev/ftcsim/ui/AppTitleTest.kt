package dev.ftcsim.ui

import dev.ftcsim.ui.common.FAKE_HOST_TITLE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppTitleTest {
    @Test
    fun theTitleNamesTheProjectThatIsOpenAndSaysWhenNothingRealRuns() {
        assertEquals("FTC-Sim", App.title(null, fake = false))
        assertEquals("FTC-Sim - FTC-Hornet", App.title("FTC-Hornet", fake = false))
        assertEquals("FTC-Sim - $FAKE_HOST_TITLE", App.title(null, fake = true))
        assertEquals("FTC-Sim - FTC-Hornet - $FAKE_HOST_TITLE", App.title("FTC-Hornet", fake = true))
    }
}
