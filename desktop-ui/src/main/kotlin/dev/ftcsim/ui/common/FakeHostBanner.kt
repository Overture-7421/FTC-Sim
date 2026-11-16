package dev.ftcsim.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the window's title says on a stand-in host, which runs no robot code. */
const val FAKE_HOST_TITLE = "FAKE HOST (no robot code runs)"

/**
 * The banner above every screen while the window runs on the stand-in host that developers use for the interface. It
 * says, with an icon as well as colour, that nothing real is running, so that nobody mistakes its field, OpModes and
 * robot for the simulator's.
 */
@Composable
fun FakeHostBanner(modifier: Modifier = Modifier) {
    val text = "$FAKE_HOST_TITLE: stand-in simulator, not your code"
    Surface(
        modifier.fillMaxWidth().semantics { contentDescription = text },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Error, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
