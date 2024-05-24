package fr.shikkanime

import fr.shikkanime.utils.HttpRequest
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.*
import kotlin.system.exitProcess

data class Page(
    val url: String,
    val title: String,
    val description: String,
    val links: Set<String>,
    @Transient
    val dom: Document? = null,
    val content: String = "",
    val responseTime: Long = 0,
    val incomingInternalLinks: MutableSet<String> = mutableSetOf(),
    val issues: MutableSet<IssueBuilder.IssueType> = mutableSetOf()
) : Serializable

suspend fun main() {
    val baseUrl = "https://www.shikkanime.fr"
    val httpRequest = HttpRequest()
    val scrapedPages = mutableSetOf<Page>()
    val waitingUrls = mutableSetOf(baseUrl)
    val startedTime = System.currentTimeMillis()

    canCrawl(httpRequest, baseUrl)

    while (waitingUrls.isNotEmpty()) {
        val url = waitingUrls.first()
        val newPage = scrape(httpRequest, url, baseUrl)

        scrapedPages.add(newPage)
        waitingUrls.addAll(newPage.links.map { "$baseUrl$it".removeSuffix("/") })
        waitingUrls.removeAll(scrapedPages.map { it.url.removeSuffix("/") }.toSet())

        // Wait 500ms between each request
        delay(250)
        drawProgressbar(
            scrapedPages.size.toString(),
            waitingUrls.size + scrapedPages.size,
            scrapedPages.size.toDouble() / (waitingUrls.size + scrapedPages.size)
        )
    }

    println("\n")
    val endTime = System.currentTimeMillis()
    httpRequest.close()

    // Calculate the incoming links
    scrapedPages.forEach { page ->
        page.incomingInternalLinks.addAll(scrapedPages
            .filterNot { it == page }
            .filter { it.links.any { link -> "$baseUrl$link".removeSuffix("/") == page.url } }
            .map { it.url }
        )

        page.issues.addAll(IssueBuilder(page.dom!!, page).build())
    }

    val file = File("crawl.history")

    val history = if (file.exists()) {
        ByteArrayInputStream(file.readBytes()).use { bais ->
            ObjectInputStream(bais).use {
                it.readObject() as MutableList<MutableSet<Page>> // NOSONAR
            }
        }
    } else {
        mutableListOf()
    }

    history.add(scrapedPages)

    ByteArrayOutputStream().use { baos ->
        ObjectOutputStream(baos).use { oos ->
            oos.writeObject(history)
            file.writeBytes(baos.toByteArray())
        }
    }

    if (history.size == 1) {
        scrapedPages.forEach { page ->
            println("Page: ${page.url}")
            println("Title: ${page.title}")
            println("Description: ${page.description}")
            println("Content: ${page.content}")
            println("Response time: ${page.responseTime}ms")
            println("Incoming links: ${page.incomingInternalLinks.size}")
            println("Issues: ${page.issues.joinToString(", ")}")
            println()
        }

        println("Scraping done in ${(endTime - startedTime) / 1000}s")
        println("Total pages: ${scrapedPages.size}")
        println("Average response time: ${scrapedPages.sumOf { it.responseTime } / scrapedPages.size}ms")
    } else {
        val previousCrawl = history[history.size - 2]

        // Calculate the diff of new words
        val newWords = scrapedPages.sumOf { it.content.split(" ").size }
        val previousWords = previousCrawl.sumOf { it.content.split(" ").size }
        val diffWords = newWords - previousWords

        // Calculate the diff of new pages
        val newPages = scrapedPages.size
        val previousPages = previousCrawl.size
        val diffPages = newPages - previousPages

        // Calculate the diff of new issues
        val newIssues = scrapedPages.sumOf { it.issues.size }
        val previousIssues = previousCrawl.sumOf { it.issues.size }
        val diffIssues = newIssues - previousIssues

        // Calculate the diff of average response time
        val newResponseTime = scrapedPages.sumOf { it.responseTime } / scrapedPages.size
        val previousResponseTime = previousCrawl.sumOf { it.responseTime } / previousCrawl.size
        val diffResponseTime = newResponseTime - previousResponseTime

        println("New words: $diffWords")
        println("New pages: $diffPages")
        println("New issues: $diffIssues")
        println("New response time: ${diffResponseTime}ms")
    }
}

private suspend fun canCrawl(httpRequest: HttpRequest, baseUrl: String) {
    // Check if a robots.txt file exists
    val robotsTxt = httpRequest.get("$baseUrl/robots.txt", mapOf("User-Agent" to "ahrefs-lite"))

    if (robotsTxt.status.value == 404) {
        println("No robots.txt file found")
        exitProcess(1)
    }

    // Check if the robots.txt file allows the bot to scrape the website
    // Or if the website is not allowed to be scraped
    val robotsTxtContent = robotsTxt.bodyAsText()

    if (robotsTxtContent.contains("User-agent: *", ignoreCase = true) && robotsTxtContent.contains("Disallow: /", ignoreCase = true)) {
        println("The website is not allowed to be scraped")
        exitProcess(1)
    }

    if (robotsTxtContent.contains("User-agent: ahrefs-lite", ignoreCase = true) && robotsTxtContent.contains("Disallow: /", ignoreCase = true)) {
        println("The bot is not allowed to scrape the website")
        exitProcess(1)
    }
}

suspend fun scrape(httpRequest: HttpRequest, url: String, baseUrl: String = url): Page {
    val start = System.currentTimeMillis()
    val response = httpRequest.get(url, mapOf("User-Agent" to "ahrefs-lite"))
    val requestedTime = System.currentTimeMillis() - start
    val parse = Jsoup.parse(response.bodyAsText())

    val links = parse
        .select("a")
        .mapNotNull { link ->
            val internalUrl = link.attr("href")
            val isRelative = internalUrl.startsWith("/") || internalUrl.startsWith(baseUrl)

            return@mapNotNull if (isRelative) {
                internalUrl.removePrefix(baseUrl)
            } else {
                null
            }
        }.toSet()

    val title = parse.title()
    val description = parse.select("meta[name=description]").attr("content")

    return Page(url.removeSuffix("/"), title, description, links, parse, parse.select("body").text(), requestedTime)
}

fun drawProgressbar(currentIndex: String, length: Int, progress: Double, drawLength: Int = 50) {
    val copyProgressBar = " ".repeat(drawLength).toCharArray()
    val currentPosition = (copyProgressBar.size * progress).toInt()
    (0 until currentPosition).forEach { copyProgressBar[it] = '•' }
    val str = "$currentIndex / $length\t|${copyProgressBar.joinToString("")}|\t${
        String.format(
            "%.2f",
            progress * 100
        )
    }%"
    print("\b".repeat(str.length) + str)
}
