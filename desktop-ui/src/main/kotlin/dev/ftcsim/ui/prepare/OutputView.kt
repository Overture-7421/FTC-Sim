package dev.ftcsim.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The output of a preparation as selectable monospace text, which follows the newest line until the person scrolls up
 * to read something and follows again once they scroll back down to the end. Scrolling by wheel or touch is seen by the
 * nested scroll connection, which programmatic scrolling does not go through, so following the tail does not undo itself.
 * The list takes the keyboard too: Page Up and Page Down, Home and End.
 */
@Composable
fun OutputView(log: OutputLog, modifier: Modifier = Modifier) {
    val version by log.version.collectAsState()
    val view = remember(version) { log.view() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    val connection = remember(listState) { UserScrollWatcher(listState) { following = it } }

    LaunchedEffect(view.total) {
        if (following && view.lines.isNotEmpty()) listState.scrollToItem(view.lines.lastIndex)
    }

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.small,
            )
            .semantics { contentDescription = "Output of the preparation" }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.PageUp -> scope.launch { following = false; listState.scrollBy(-listState.pageSize()) }
                    Key.PageDown -> scope.launch { listState.scrollBy(listState.pageSize()); following = !listState.canScrollForward }
                    Key.MoveHome -> scope.launch { following = false; listState.scrollToItem(0) }
                    Key.MoveEnd -> scope.launch { following = true; listState.scrollToItem(maxOf(0, view.lines.lastIndex)) }
                    else -> return@onKeyEvent false
                }
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .nestedScroll(connection),
    ) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(8.dp)) {
                items(count = view.lines.size, key = { view.first + it }) { index ->
                    Text(
                        view.lines[index].ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun LazyListState.pageSize(): Float = layoutInfo.viewportSize.height.toFloat()

/** Tells whether the person is reading along the end of the list: scrolling up leaves it, reaching the end again returns. */
private class UserScrollWatcher(private val list: LazyListState, private val onFollowing: (Boolean) -> Unit) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f && list.canScrollBackward) onFollowing(false)
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && consumed.y < 0f && !list.canScrollForward) onFollowing(true)
        return Offset.Zero
    }
}
