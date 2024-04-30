package fr.shikkanime

import org.jsoup.nodes.Document

class IssueBuilder(
    private val dom: Document,
    private val page: Page
) {
    enum class IssueType {
        TITLE_EMPTY,
        TITLE_TOO_LONG,
        DESCRIPTION_EMPTY,
        DESCRIPTION_TOO_SHORT,
        DESCRIPTION_TOO_LONG,
        CONTENT_EMPTY,
        H1_MISSING,
        MULTIPLE_H1,
        H1_TOO_LONG,
        OPEN_GRAPH_TAGS_INCOMPLETE,
        INCOMING_LINKS_TOO_FEW,
        DATA_INCONSISTENCY
    }

    fun build(): Set<IssueType> = mutableSetOf<IssueType>().apply {
        titleIssues(this@apply)
        descriptionIssues(this@apply)
        contentIssues(this@apply)
        openGraphIssues(this@apply)
        if (page.incomingInternalLinks.size <= 1) add(IssueType.INCOMING_LINKS_TOO_FEW)
        dataInconsistencyIssues(this@apply)
    }

    private fun titleIssues(issues: MutableSet<IssueType>) {
        if (page.title.isBlank()) issues.add(IssueType.TITLE_EMPTY)
        if (page.title.length > 70) issues.add(IssueType.TITLE_TOO_LONG)
    }

    private fun descriptionIssues(issues: MutableSet<IssueType>) {
        if (page.description.isBlank()) issues.add(IssueType.DESCRIPTION_EMPTY)
        if (page.description.length < 110) issues.add(IssueType.DESCRIPTION_TOO_SHORT)
        if (page.description.length > 160) issues.add(IssueType.DESCRIPTION_TOO_LONG)
    }

    private fun contentIssues(issues: MutableSet<IssueType>) {
        if (page.content.isBlank()) issues.add(IssueType.CONTENT_EMPTY)

        val titles = dom.select("h1")
        if (titles.isEmpty() || titles.any { it.text().isBlank() }) issues.add(IssueType.H1_MISSING)
        if (titles.size > 1) issues.add(IssueType.MULTIPLE_H1)
        if (titles.any { it.text().length > 70 }) issues.add(IssueType.H1_TOO_LONG)
    }

    private fun openGraphIssues(issues: MutableSet<IssueType>) {
        val properties = dom.select("meta[property^=og:]").map { it.attr("property") }
        // Check if the Open Graph tags are incomplete
        // means that the title, type, image, and url are missing
        if (!properties.contains("og:title") || !properties.contains("og:type") || !properties.contains("og:image") || !properties.contains("og:url")) {
            issues.add(IssueType.OPEN_GRAPH_TAGS_INCOMPLETE)
        }
    }

    private fun dataInconsistencyIssues(issues: MutableSet<IssueType>) {
        if (page.url.contains("/animes")) {
            val list: MutableList<Pair<Int, Int>> = mutableListOf()

            dom.select("p.text-muted.mb-0").forEach { episodeDetails ->
                val text = episodeDetails.text()
                val regexSeason = Regex("Saison (\\d+)")
                val regexNumber = Regex("(Épisode|Spécial|Film) (\\d+)")
                if (!regexSeason.containsMatchIn(text) || !regexNumber.containsMatchIn(text)) return@forEach
                val season = regexSeason.find(text)!!.groupValues[1].toInt()
                val type = regexNumber.find(text)!!.groupValues[1]
                val number = regexNumber.find(text)!!.groupValues[2].toInt()

                if (type != "Épisode") return@forEach
                list.add(season to number)
            }

            // If the list is empty
            if (list.isEmpty()) {
                issues.add(IssueType.CONTENT_EMPTY)
                return
            }

            // Check if the list is not sorted
            if (list != list.sortedWith(compareBy({ it.first }, { it.second }))) {
                issues.add(IssueType.DATA_INCONSISTENCY)
            }
        }
    }
}