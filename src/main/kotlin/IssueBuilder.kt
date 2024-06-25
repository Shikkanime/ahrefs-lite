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
        DATA_INCONSISTENCY,
        DATA_INCONSISTENCY_SEASON,
        DATA_INCONSISTENCY_SUMMARY,
        NOT_IN_SITEMAP
    }

    fun build(): Set<IssueType> = mutableSetOf<IssueType>().apply {
        titleIssues(this@apply)
        descriptionIssues(this@apply)
        contentIssues(this@apply)
        openGraphIssues(this@apply)
        if (page.incomingInternalLinks.size <= 1) add(IssueType.INCOMING_LINKS_TOO_FEW)
        dataInconsistencyIssues(this@apply)
        if (!page.inSitemap) add(IssueType.NOT_IN_SITEMAP)
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

    data class Episode(
        val season: Int,
        val type: String,
        val number: Int,
        val title: String?,
        val description: String?
    )

    private fun dataInconsistencyIssues(issues: MutableSet<IssueType>) {
        if (page.url.contains("/animes")) {
            val list: MutableList<Episode> = mutableListOf()

            val regexSeason = Regex("Saison (\\d+)")
            val seasonText = dom.select("button.btn.btn-dark.dropdown-toggle.mt-3").text()
            if (!regexSeason.containsMatchIn(seasonText)) return
            val season = regexSeason.find(seasonText)!!.groupValues[1].toInt()

            dom.select("div.h6.mt-2.mb-0.text-truncate-2.fw-bold").forEach { episodeDetails ->
                val text = episodeDetails.text()
                val regexNumber = Regex("(Épisode|Épisode récapitulatif|Spécial|Film) (\\d+)")
                if (!regexNumber.containsMatchIn(text)) return@forEach
                val type = regexNumber.find(text)!!.groupValues[1]
                val number = regexNumber.find(text)!!.groupValues[2].toInt()
                val closestArticle = episodeDetails.closest("article")
                val title = closestArticle?.selectFirst("div.h6.text-truncate-2.fw-bold")?.text()
                val description = closestArticle?.selectFirst("div.text-truncate-4.my-2.m-0")?.text()
                list.add(Episode(season, type, number, title, description))
            }

            // If the list is empty
            if (list.isEmpty()) {
                issues.add(IssueType.CONTENT_EMPTY)
                return
            }

            val episodes = list.filter { it.type == "Épisode" }
            // Check if the episodes is not sorted
            if (episodes != episodes.sortedWith(compareBy({ it.season }, { it.number }))) {
                issues.add(IssueType.DATA_INCONSISTENCY)
            }

            if (season > 5) {
                issues.add(IssueType.DATA_INCONSISTENCY_SEASON)
            }

            if (list.any {
                    (it.title?.contains("récap", ignoreCase = true) == true || it.description?.contains("récap", ignoreCase = true) == true)
                            && it.type != "Épisode récapitulatif"
                }) {
                issues.add(IssueType.DATA_INCONSISTENCY_SUMMARY)
            }
        }
    }
}