package com.vezir.android.net

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Dated artifact filenames (v0.10.0, mirrors desktop vezir v0.14.1).
 *
 * Downloaded artifacts are named `YYYYMMDD_<title_slug>.<ext>` instead of
 * generic names (`summary.md`, `transcript.pdf`, ...).  Example:
 * `20260824_brainstorm_phoenix.pdf`.  The date comes from the session's
 * `created_at` converted to the device timezone; untitled sessions fall
 * back to `<date>_recording`.
 *
 * Mirrors `artifact_friendly_name` in vezir/config.py — keep in sync.
 */
object ArtifactNames {

    // Ordered suffix mapping from millet's stored filenames to the
    // extension used with the stem.  ".json" must come AFTER
    // ".frontmatter.json" to avoid shadowing it.
    private val CLIPS_LANG = Regex("""\.clips\.([a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?)\.json$""")

    private val EXTENSIONS = listOf(
        // Template summaries (desktop vezir 0.18.0) before the plain summary.
        ".iteration-plan.md" to ".iteration-plan.md",
        ".video-clipping.md" to ".video-clipping.md",
        ".summary.md" to ".md",
        ".frontmatter.json" to ".frontmatter.json",
        // video-clipping cut list (desktop vezir 0.28.0): before ".json",
        // which would give it the transcript's friendly name.
        ".clips.json" to ".clips.json",
        ".srt" to ".srt",
        ".txt" to ".txt",
        ".pdf" to ".pdf",
        ".json" to ".json",
    )

    /** Session date as YYYYMMDD in the device timezone ("today" if unusable). */
    fun sessionDate(createdAt: String?): String {
        val ts = createdAt?.trim().orEmpty()
        if (ts.isNotEmpty()) {
            try {
                var clean = ts.replace("Z", "+00:00")
                if ("T" !in clean) clean += "T00:00:00"
                val instant = OffsetDateTime.parse(clean).toInstant()
                // LocalDate.ofInstant requires API 34; atZone().toLocalDate()
                // is equivalent and works on API 26+ (our min is 29).
                return instant.atZone(ZoneId.systemDefault()).toLocalDate()
                    .format(DateTimeFormatter.BASIC_ISO_DATE)
            } catch (_: Exception) {
                // fall through to today
            }
        }
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    /**
     * Build the `YYYYMMDD_<slug>` stem; falls back to
     * `<date>_recording` when the title slug is empty.
     */
    fun stem(createdAt: String?, title: String?): String {
        val slug = artifactTitleSlug(title)
        return "${sessionDate(createdAt)}_${slug.ifEmpty { "recording" }}"
    }

    /**
     * Friendly name for a stored artifact; unknown types keep their
     * stored name.
     */
    fun friendlyName(serverName: String, createdAt: String?, title: String?): String {
        val stem = stem(createdAt, title)
        // Additional-language cut list <id>.clips.<lang>.json keeps its
        // language and never falls through to ".json".
        CLIPS_LANG.find(serverName)?.let { return "$stem.clips.${it.groupValues[1]}.json" }
        for ((suffix, ext) in EXTENSIONS) {
            if (serverName.endsWith(suffix)) return "$stem$ext"
        }
        return serverName
    }
}

/**
 * Lowercase filesystem-safe slug: runs of non-alphanumerics become a
 * single underscore, capped at 60 chars.
 */
fun artifactTitleSlug(title: String?): String {
    if (title.isNullOrBlank()) return ""
    val slug = title.trim()
        .replace(Regex("[^a-zA-Z0-9]+"), "_")
        .lowercase()
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(60)
        .trimEnd('_')
    return slug
}
