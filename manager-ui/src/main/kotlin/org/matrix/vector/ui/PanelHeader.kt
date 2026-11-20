package org.matrix.vector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Standard Material app bar and search area shared by list destinations. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    description: (@Composable () -> Unit)? = null,
    search: (@Composable () -> Unit)? = null,
    titleOverlay: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (titleOverlay != null) {
            Box(Modifier.fillMaxWidth().height(82.dp)) { titleOverlay() }
        } else {
            androidx.compose.material3.TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { actions?.invoke(this) },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
            if (description != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    description()
                }
            }
        }
        if (search != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                search()
            }
        }
    }
}
