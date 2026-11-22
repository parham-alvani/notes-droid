package me.parham1995.notes.markdown

import java.time.LocalDate

/** The colour a task's box or a status's icon is drawn in, by what it is, for a theme to say which colour that is. */
enum class TaskTint {
    LIST_MARKER,
    DONE,
    ACCENT,
    BLUE,
    RED,
    YELLOW,
    VIVID_YELLOW,
    WARM_GREY,
    ORANGE,
    SPRING_GREEN,
}

/** How one status is drawn: a Lucide icon in one colour, and what a screen reader says. */
data class TaskSymbol(
    val icon: String,
    val tint: TaskTint,
    val description: String,
)

/**
 * The statuses themes and the Tasks plugin give meaning to beyond the four --
 * forwarded, scheduled, important, a question -- each with its own icon. The
 * reader and the editor both draw from this table, so they agree.
 */
object TaskSymbols {
    private val TABLE =
        mapOf(
            '>' to TaskSymbol("square-arrow-right", TaskTint.BLUE, "forwarded"),
            '<' to TaskSymbol("calendar-clock", TaskTint.BLUE, "scheduled"),
            '!' to TaskSymbol("circle-alert", TaskTint.RED, "important"),
            '?' to TaskSymbol("circle-help", TaskTint.YELLOW, "question"),
            '*' to TaskSymbol("star", TaskTint.VIVID_YELLOW, "star"),
            '"' to TaskSymbol("quote", TaskTint.WARM_GREY, "quote"),
            'i' to TaskSymbol("info", TaskTint.BLUE, "information"),
            'b' to TaskSymbol("bookmark", TaskTint.ORANGE, "bookmark"),
            'l' to TaskSymbol("map-pin", TaskTint.RED, "location"),
            'I' to TaskSymbol("lightbulb", TaskTint.VIVID_YELLOW, "idea"),
            'S' to TaskSymbol("piggy-bank", TaskTint.SPRING_GREEN, "savings"),
            'f' to TaskSymbol("flame", TaskTint.RED, "fire"),
            'k' to TaskSymbol("key", TaskTint.VIVID_YELLOW, "key"),
            'w' to TaskSymbol("trophy", TaskTint.VIVID_YELLOW, "win"),
            'u' to TaskSymbol("thumbs-up", TaskTint.SPRING_GREEN, "up"),
            'p' to TaskSymbol("thumbs-up", TaskTint.SPRING_GREEN, "pro"),
            'd' to TaskSymbol("thumbs-down", TaskTint.RED, "down"),
            'c' to TaskSymbol("thumbs-down", TaskTint.RED, "con"),
        )

    /** The table's drawing of [status], or null for the four every vault has and for one the table lacks. */
    fun of(status: Char): TaskSymbol? = TABLE[status]

    /** A status nothing knows: a dashed box. */
    fun unknown(status: Char) = TaskSymbol("square-dashed", TaskTint.LIST_MARKER, "status $status")

    /** How a status is drawn by its type alone. */
    fun of(type: StatusType): TaskSymbol =
        when (type) {
            StatusType.TODO -> TaskSymbol("square", TaskTint.LIST_MARKER, "unchecked")
            StatusType.DONE -> TaskSymbol("square-check-big", TaskTint.DONE, "checked")
            StatusType.IN_PROGRESS -> TaskSymbol("square-dot", TaskTint.ACCENT, "in progress")
            StatusType.CANCELLED -> TaskSymbol("square-x", TaskTint.DONE, "cancelled")
            StatusType.NON_TASK -> TaskSymbol("square-dashed", TaskTint.LIST_MARKER, "not a task")
        }
}

/**
 * The rules of a task drawn in the editor (`look.md` in notes-linux's
 * `docs/specs/`, "Editor: the task box" and "Editor: date chips"). Each is a
 * pure rule over a line, so none needs a screen.
 */
object EditorTasks {
    /**
     * The box of a task whose status is [status]. The four every vault has
     * come first, then the reader's table, then the type the vault's
     * [statuses] give the symbol. A symbol the vault does not have is a
     * dashed box.
     */
    fun drawing(
        status: Char,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): TaskSymbol =
        when (status) {
            ' ' -> TaskSymbols.of(StatusType.TODO)
            'x', 'X' -> TaskSymbols.of(StatusType.DONE)
            '/' -> TaskSymbols.of(StatusType.IN_PROGRESS)
            '-' -> TaskSymbols.of(StatusType.CANCELLED)
            else ->
                TaskSymbols.of(status)
                    ?: statuses.all.firstOrNull { it.symbol == status }?.let { TaskSymbols.of(it.type) }
                    ?: TaskSymbols.unknown(status)
        }

    /** A done or a cancelled task: its text is struck through, and its chips are drawn weaker. */
    fun isStruck(
        status: Char,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): Boolean = statuses.of(status).type.let { it == StatusType.DONE || it == StatusType.CANCELLED }

    /**
     * A due date is late when its day is before [today] and the task is
     * open: to do, or under way. A task due today is not late, and no other
     * date is ever late.
     */
    fun isLate(
        chip: EditorChip,
        status: Char,
        today: LocalDate,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): Boolean {
        if (chip.kind != EditorChipKind.DUE || chip.day == null || !chip.day.isBefore(today)) return false
        val type = statuses.of(status).type
        return type == StatusType.TODO || type == StatusType.IN_PROGRESS
    }

    /** Whether a caret or a selection from [start] to [end] of the line touches [range], both ends counted. */
    fun touches(
        range: EditorRange,
        start: Int,
        end: Int = start,
    ): Boolean = end >= range.start && start <= range.end

    /** Whether the box is drawn with the selection there: raw while the caret is in its marks. */
    fun boxDrawn(
        task: EditorTask,
        selection: EditorRange?,
    ): Boolean = selection == null || !touches(task.box, selection.start, selection.end)

    /** Whether [chip] is drawn with the selection there: raw while the caret is in it. */
    fun chipDrawn(
        chip: EditorChip,
        selection: EditorRange?,
    ): Boolean = selection == null || !touches(EditorRange(chip.start, chip.end), selection.start, selection.end)
}
