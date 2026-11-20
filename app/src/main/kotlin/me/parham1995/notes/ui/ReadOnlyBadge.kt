package me.parham1995.notes.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R

/**
 * "Read-only", by a vault's name: the vault is a clone that is pulled and
 * never written (`read-only-vault.md` in notes-linux's `docs/specs/`, section
 * 4). A long press says so in full, as the desktop's tooltip does, and
 * TalkBack reads the full sentence in place of the one word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadOnlyBadge(modifier: Modifier = Modifier) {
    val full = stringResource(R.string.read_only_tooltip)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(full) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.semantics { contentDescription = full },
        ) {
            Text(
                stringResource(R.string.read_only_badge),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
