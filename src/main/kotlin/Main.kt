package fr.shikkanime

import fr.shikkanime.utils.HttpRequest
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.system.exitProcess

data class Page(
    val url: String,
    val title: String,
    val description: String,
    val links: Set<String>,
    var inSitemap: Boolean = false,
    @Transient
    val dom: Document? = null,
    val content: String = "",
    val responseTime: Long = 0,
    val incomingInternalLinks: MutableSet<String> = mutableSetOf(),
    val issues: MutableSet<IssueBuilder.IssueType> = mutableSetOf()
) : Serializable

data class Site(
    val url: String,
    var history: MutableList<MutableSet<Page>>
) : Serializable

fun toGzip(source: ByteArray): ByteArray {
    ByteArrayOutputStream().use { baos ->
        GZIPOutputStream(baos).use { gzos ->
            gzos.write(source)
        }

        return baos.toByteArray()
    }
}

fun fromGzip(source: ByteArray): ByteArray {
    val bais = ByteArrayInputStream(source)
    val baos = ByteArrayOutputStream()

    GZIPInputStream(bais).use { gzis ->
        gzis.copyTo(baos)
    }

    return baos.toByteArray()
}

private val file = File("crawl.history")

suspend fun main() {
    println("Enter a URL: (Without http:// or https://)")
    val url = "https://" + readln().removePrefix("http://").removePrefix("https://")
    println("Enter a command (crawl, inconsistency-history):")
    val command = readln()

    when (command) {
        "crawl" -> crawl(url)
        "inconsistency-history" -> {
            val lastCraw = getCrawlHistory().find { it.url == url }?.history?.lastOrNull()
                ?: run {
                    println("No crawl history found for $url")
                    exitProcess(1)
                }

            lastCraw.filter {
                it.issues.any { issue ->
                    issue in setOf(
                        IssueBuilder.IssueType.DATA_INCONSISTENCY,
                        IssueBuilder.IssueType.DATA_INCONSISTENCY_SEASON,
                        IssueBuilder.IssueType.DATA_INCONSISTENCY_SUMMARY,
                        IssueBuilder.IssueType.DATA_INCONSISTENCY_EPISODE,
                        IssueBuilder.IssueType.DATA_INCONSISTENCY_TOO_MANY_EPISODES_ON_SAME_DAY,
                        IssueBuilder.IssueType.NOT_IN_SITEMAP
                    )
                }
            }.forEach { page ->
                println("Page: ${page.url}")
                println("Title: ${page.title}")
                println("Description: ${page.description}")
                println("Content: ${page.content}")
                println("Response time: ${page.responseTime}ms")
                println("Incoming links: ${page.incomingInternalLinks.size}")
                println("Issues: ${page.issues.joinToString(", ")}")
                println()
            }
        }

        else -> println("Unknown command")
    }
}

private suspend fun crawl(baseUrl: String) {
    val httpRequest = HttpRequest()
    val scrapedPages = mutableSetOf<Page>()
    val waitingUrls = mutableSetOf(baseUrl)
    val startedTime = System.currentTimeMillis()

    canCrawl(httpRequest, baseUrl)

    val sitemap = httpRequest.get("$baseUrl/sitemap.xml")
    require(sitemap.status == HttpStatusCode.OK) { "Sitemap not found or not accessible" }
    val urls = Jsoup.parse(sitemap.bodyAsText()).select("loc").map { it.text().removeSuffix("/") }

    while (waitingUrls.isNotEmpty()) {
        val url = waitingUrls.first()
        val newPage = scrape(httpRequest, url, baseUrl)
        newPage.inSitemap = urls.contains(newPage.url)

        scrapedPages.add(newPage)
        waitingUrls.addAll(newPage.links.map { "$baseUrl$it".removeSuffix("/") })
        waitingUrls.removeAll(scrapedPages.map { it.url.removeSuffix("/") }.toSet())

        delay(250)
        val max = max(urls.size, (waitingUrls.size + scrapedPages.size))

        drawProgressbar(
            scrapedPages.size.toString(),
            max,
            scrapedPages.size.toDouble() / max
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

    val sites = getCrawlHistory()
    val history = sites.find { it.url == baseUrl }?.history ?: mutableListOf()
    history.add(scrapedPages)

    if (sites.none { it.url == baseUrl }) {
        sites.add(Site(baseUrl, history))
    }

    ByteArrayOutputStream().use { baos ->
        ObjectOutputStream(baos).use { oos ->
            oos.writeObject(sites)
            file.writeBytes(toGzip(baos.toByteArray()))
        }
    }

    if (sites.size == 1) {
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
        println("Max response time: ${scrapedPages.maxOf { it.responseTime }}ms")
        println("Min response time: ${scrapedPages.minOf { it.responseTime }}ms")
    } else {
        val previousCrawl = history[sites.size - 2]

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

        println("New words: ${toAbs(diffWords)}")
        println("New pages: ${toAbs(diffPages)}")
        println("New issues: ${toAbs(diffIssues)}")
        println("New response time: ${toAbs(diffResponseTime)} ms")
    }
}

private fun getCrawlHistory(): MutableSet<Site> {
    return if (file.exists()) {
        ByteArrayInputStream(fromGzip(file.readBytes())).use { bais ->
            ObjectInputStream(bais).use {
                it.readObject() as MutableSet<Site> // NOSONAR
            }
        }
    } else {
        mutableSetOf()
    }
}

private fun toAbs(number: Number): String = if (number.toInt() > 0) "+$number" else number.toString()

private suspend fun canCrawl(httpRequest: HttpRequest, baseUrl: String) {
    // Check if a robots.txt file exists
    val robotsTxt = httpRequest.get("$baseUrl/robots.txt", mapOf("User-Agent" to "ahrefs-lite"))

    if (robotsTxt.status == HttpStatusCode.NotFound) {
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

    if (response.status != HttpStatusCode.OK) {
        println("Error while scraping $url: ${response.status.value}")
        exitProcess(1)
    }

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

    return Page(url.removeSuffix("/"), title, description, links, false, parse, parse.select("body").text(), requestedTime)
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
