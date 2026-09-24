package me.parham1995.notes.ui

import androidx.annotation.StringRes
import me.parham1995.notes.R
import me.parham1995.notes.obsidian.Period

/**
 * What the button that opens the current daily note is called. A weekly
 * journal's is "this week's note": "today's" would be a promise of a note
 * that does not exist, kept by opening one that does.
 */
@StringRes
fun Period.openLabel(): Int =
    when (this) {
        Period.DAY -> R.string.today_action
        Period.WEEK -> R.string.this_week_action
        Period.MONTH -> R.string.this_month_action
        Period.QUARTER -> R.string.this_quarter_action
        Period.YEAR -> R.string.this_year_action
    }

/** What is said when the current period has no note yet; takes the path it would have. */
@StringRes
fun Period.missingMessage(): Int =
    when (this) {
        Period.DAY -> R.string.today_missing
        Period.WEEK -> R.string.week_missing
        Period.MONTH -> R.string.month_missing
        Period.QUARTER -> R.string.quarter_missing
        Period.YEAR -> R.string.year_missing
    }
