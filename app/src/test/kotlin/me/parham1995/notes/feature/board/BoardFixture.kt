package me.parham1995.notes.feature.board

import me.parham1995.notes.data.database.QueryTaskRow
import java.time.LocalDate

/** Made-up tasks for the board's tests: a garden, a kitchen, a Persian note. */
internal object BoardFixture {
    /** A Sunday, the day the Mac app's board tests are set on. */
    val today: LocalDate = LocalDate.of(2026, 10, 25)

    fun row(
        id: Long,
        text: String,
        line: Int,
        state: String = "UNCHECKED",
        day: String? = null,
        due: Boolean = true,
        priority: String? = null,
        recurring: String? = null,
        done: String? = null,
        cancelled: String? = null,
        path: String = "Garden.md",
        section: String = "Beds",
    ) = QueryTaskRow(
        id = id,
        noteId = path.hashCode().toLong(),
        text = text,
        state = state,
        section = section,
        blockIndex = 0,
        line = line,
        actionableOn = day,
        recurring = recurring,
        scheduled = day?.takeIf { !due },
        due = day?.takeIf { due },
        done = done,
        notePath = path,
        noteTitle = path.substringAfterLast('/').removeSuffix(".md"),
        vaultId = 1,
        priority = priority,
        cancelled = cancelled,
    )

    val rows =
        listOf(
            row(1, "Water the seedlings", 3, day = "2026-10-22", priority = "⏫"),
            row(2, "Turn the compost", 4, day = "2026-10-25", recurring = "every week"),
            row(3, "Order bulbs from Fern & Co", 5, day = "2026-10-28", due = false, priority = "🔽"),
            row(4, "کتاب باغبانی را برگردان", 2, day = "2026-10-26", path = "خانه.md", section = "کتابخانه"),
            row(5, "Sharpen the shears", 6),
            row(6, "Mend the fence", 7, state = "IN_PROGRESS", priority = "🔺", path = "Yard.md", section = "Repairs"),
            row(7, "Bake bread", 1, state = "CHECKED", done = "2026-10-24", path = "Kitchen.md", section = "Weekend"),
            row(
                8,
                "Paint the shed",
                8,
                state = "CANCELLED",
                cancelled = "2026-10-20",
                path = "Yard.md",
                section = "Repairs",
            ),
        )
}
