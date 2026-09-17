package me.parham1995.notes.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RepoDto(
    @SerialName("full_name") val fullName: String,
    @SerialName("default_branch") val defaultBranch: String,
    val private: Boolean,
    @SerialName("pushed_at") val pushedAt: String? = null,
)

@Serializable
internal data class RefDto(
    val ref: String,
    val `object`: RefObjectDto,
)

@Serializable
internal data class RefObjectDto(
    val sha: String,
    val type: String,
)

@Serializable
internal data class TreeDto(
    val sha: String,
    val tree: List<TreeEntryDto>,
    /**
     * GitHub caps a recursive tree at 100,000 entries / 7MB. A vault of a few
     * thousand files is nowhere near that, but a truncated response would
     * silently look like a vault with missing notes, so it is checked.
     */
    val truncated: Boolean = false,
)

@Serializable
internal data class TreeEntryDto(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String? = null,
    val size: Long? = null,
)

@Serializable
internal data class CompareDto(
    @SerialName("total_commits") val totalCommits: Int = 0,
    val files: List<CompareFileDto> = emptyList(),
)

@Serializable
internal data class CompareFileDto(
    val filename: String,
    val status: String,
    val sha: String? = null,
    @SerialName("previous_filename") val previousFilename: String? = null,
)
