package io.github.marcomow.yuvomi.feature.tags

/**
 * The reserved tag `#archived` (TAGS-R15, ADR 0023): the household's shared way of saying
 * "archived" wherever upstream has no archive of its own. It is an ordinary tag to upstream
 * and the web app, so everyone sees it, and the app treats it specially:
 * - a task is archived when it carries the real tag `archived` (or upstream archived it);
 * - a note or a shopping item is archived when its text has the hashtag `#archived`;
 * - any other item is archived when one of its tags is `archived`;
 * - a tag is archived when every item carrying it is completed or archived, and one is archived.
 * Pure, so it is unit tested.
 */
object Archived {
    const val TAG = "archived"

    private val KEY = TagKey.of(TAG)

    /** `#archived` in text, by the rules of [Hashtags] (case-insensitive, not inside a word or URL). */
    private val inTextPattern = Regex("""(?<![\p{L}\p{N}_#&/])#archived(?![\p{L}\p{N}_-])""", RegexOption.IGNORE_CASE)

    fun isTag(tag: String): Boolean = TagKey.of(tag.removePrefix("#")) == KEY

    fun inTags(tags: Collection<String>): Boolean = tags.any(::isTag)

    fun inText(vararg texts: String?): Boolean = inTags(Hashtags.extract(*texts))

    /** [tags] with `archived` added once, or without it. */
    fun tags(tags: List<String>, archived: Boolean): List<String> =
        if (archived) (if (inTags(tags)) tags else tags + TAG) else tags.filterNot(::isTag)

    /** [text] with `#archived` on a line of its own at the end, or with every `#archived` taken out. */
    fun text(text: String?, archived: Boolean): String {
        val body = text.orEmpty()
        if (archived) return if (inText(body)) body else if (body.isBlank()) "#$TAG" else body.trimEnd() + "\n\n#$TAG"
        return body.replace(inTextPattern, "")
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd()
    }
}
