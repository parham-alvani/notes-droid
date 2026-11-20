package me.parham1995.notes.feature.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.ConceptNotice
import me.parham1995.notes.markdown.OkfConcept
import me.parham1995.notes.markdown.OkfStatus
import me.parham1995.notes.markdown.OkfTier
import me.parham1995.notes.markdown.OkfTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Concept row, above the properties of a note that is a concept of the
 * Open Knowledge Format (`open-knowledge-format.md` in notes-linux's
 * `docs/specs/`, section 2): its type, then a chip for the trust tier, one
 * for the status when it is not stable, and one when it is stale. A long
 * press on the tier says who last verified it and on which day, as the
 * desktop's tooltip does.
 *
 * [onVerify] is Mark as Verified; null where the vault cannot be written,
 * which a read-only one never can, and then the button is not there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConceptRow(
    concept: OkfConcept,
    onVerify: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    now: Instant = Instant.now(),
) {
    Column(modifier.fillMaxWidth().testTag(CONCEPT_ROW), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.okf_concept) + " · " + concept.type,
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = MaterialTheme.colorScheme
            val tier = stringResource(concept.tier.label)
            val verifier =
                concept.lastVerifier?.let { event ->
                    stringResource(R.string.okf_verified_by, event.by, (event.at as? OkfTime.At)?.instant.day())
                }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(verifier ?: tier) } },
                state = rememberTooltipState(),
            ) {
                ConceptChip(
                    text = tier,
                    container =
                        when (concept.tier) {
                            OkfTier.HUMAN_REVIEWED -> colors.primaryContainer
                            OkfTier.MACHINE_CONFIRMED -> colors.secondaryContainer
                            OkfTier.UNVERIFIED -> colors.surfaceVariant
                        },
                    content =
                        when (concept.tier) {
                            OkfTier.HUMAN_REVIEWED -> colors.onPrimaryContainer
                            OkfTier.MACHINE_CONFIRMED -> colors.onSecondaryContainer
                            OkfTier.UNVERIFIED -> colors.onSurfaceVariant
                        },
                    description = listOfNotNull(tier, verifier).joinToString(". "),
                )
            }
            when (val status = concept.status) {
                OkfStatus.Stable -> Unit
                OkfStatus.Draft ->
                    ConceptChip(
                        stringResource(R.string.okf_draft),
                        colors.surfaceVariant,
                        colors.onSurfaceVariant,
                    )
                OkfStatus.Deprecated ->
                    ConceptChip(stringResource(R.string.okf_deprecated), colors.errorContainer, colors.onErrorContainer)
                // Kept as written: no list of statuses is known.
                is OkfStatus.Other -> ConceptChip(status.raw, colors.surfaceVariant, colors.onSurfaceVariant)
            }
            if (concept.isStale(now)) {
                ConceptChip(
                    text =
                        when (val after = concept.staleAfter) {
                            is OkfTime.At -> stringResource(R.string.okf_stale_since, after.instant.day())
                            else -> stringResource(R.string.okf_stale_bad_date)
                        },
                    container = colors.tertiaryContainer,
                    content = colors.onTertiaryContainer,
                )
            }
        }
        if (onVerify != null) {
            OutlinedButton(onClick = onVerify, enabled = enabled) {
                Text(stringResource(R.string.okf_mark_verified))
            }
        }
    }
}

@Composable
private fun ConceptChip(
    text: String,
    container: Color,
    content: Color,
    description: String = text,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The one line above a deprecated or stale concept's text: "Deprecated.
 * Replaced by <link>." with the link left out when `superseded_by` is absent
 * or names nothing, or "Stale since <day>: check it again."
 */
@Composable
internal fun ConceptLine(
    notice: ConceptNotice,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val replacement = (notice as? ConceptNotice.Deprecated)?.replacedBy
    val text =
        when (notice) {
            is ConceptNotice.Deprecated ->
                replacement?.let { stringResource(R.string.okf_line_replaced, it.second) }
                    ?: stringResource(R.string.okf_line_deprecated)
            is ConceptNotice.Stale ->
                notice.since?.let { stringResource(R.string.okf_line_stale, it.day()) }
                    ?: stringResource(R.string.okf_line_stale_bad_date)
        }
    Surface(
        color =
            if (notice is ConceptNotice.Deprecated) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        contentColor =
            if (notice is ConceptNotice.Deprecated) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(CONCEPT_LINE)
                // The whole line is the link, when there is a note to go to.
                .then(
                    if (replacement != null) {
                        Modifier.clickable(role = Role.Button) { onOpen(replacement.first) }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** A day as the phone writes one. */
private fun Instant?.day(): String = this?.let { DAY.format(LocalDate.ofInstant(it, ZoneId.systemDefault())) }.orEmpty()

private val DAY: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private val OkfTier.label: Int
    get() =
        when (this) {
            OkfTier.UNVERIFIED -> R.string.okf_unverified
            OkfTier.MACHINE_CONFIRMED -> R.string.okf_machine_confirmed
            OkfTier.HUMAN_REVIEWED -> R.string.okf_human_reviewed
        }

internal const val CONCEPT_ROW = "concept-row"
internal const val CONCEPT_LINE = "concept-line"
