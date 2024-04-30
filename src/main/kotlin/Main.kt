package fr.shikkanime

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import fr.shikkanime.utils.HttpRequest
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
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
)

suspend fun main() {
//    sendMail()

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
        delay(500)
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

//    scrapedPages.forEach { page ->
//        println("Page: ${page.url}")
//        println("Title: ${page.title}")
//        println("Description: ${page.description}")
//        println("Content: ${page.content}")
//        println("Response time: ${page.responseTime}ms")
//        println("Incoming links: ${page.incomingInternalLinks.size}")
//        println("Issues: ${page.issues.joinToString(", ")}")
//        println()
//    }
//
//    println("Scraping done in ${(endTime - startedTime) / 1000}s")
//    println("Total pages: ${scrapedPages.size}")
//    println("Average response time: ${scrapedPages.sumOf { it.responseTime } / scrapedPages.size}ms")

    val file = File("crawl.json")
    val gson = GsonBuilder().setPrettyPrinting().create()

    val history = if (file.exists()) {
        gson.fromJson(file.readText(), object : TypeToken<MutableList<MutableSet<Page>>>() {})
    } else {
        mutableListOf()
    }

    history.add(scrapedPages)
    gson.toJson(history).let { file.writeText(it) }

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

//fun generateRandomString(length: Int): String {
//    val source = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
//    return (1..length)
//        .map { source.random() }
//        .joinToString("")
//}
//
//fun sendMail() {
//    val mailHost = requireNotNull(System.getenv("MAIL_HOST")) { "MAIL_HOST is not set" }
//    val mailUsername = requireNotNull(System.getenv("MAIL_USERNAME")) { "MAIL_USERNAME is not set" }
//    val mailPassword = requireNotNull(System.getenv("MAIL_PASSWORD")) { "MAIL_PASSWORD is not set" }
//
//    val properties = Properties()
//    properties["mail.smtp.auth"] = "true"
//    properties["mail.smtp.starttls.enable"] = "true"
//    properties["mail.smtp.host"] = mailHost
//    properties["mail.smtp.port"] = "25"
//    properties["mail.smtp.ssl.trust"] = mailHost
//
//    val session = Session.getInstance(properties, object : Authenticator() {
//        override fun getPasswordAuthentication(): PasswordAuthentication {
//            return PasswordAuthentication(mailUsername, mailPassword)
//        }
//    })
//
//    val message = MimeMessage(session)
//    message.setFrom(InternetAddress(mailUsername, "Shikkanime - Ne pas répondre"))
//    message.setRecipients(MimeMessage.RecipientType.TO, InternetAddress.parse("ziedelth@gmail.com"))
//    message.subject = "Code de synchronisation à usage unique"
//
//    val mimeBodyPart = MimeBodyPart()
//    val list = generateRandomString(6).chunked(1)
//    val body = StringBuilder()
//
//    list.forEachIndexed { index, s ->
//        if (index == list.size - 1) {
//            body.append("<td style=\"padding: 5px 10px;\">$s</td>")
//        } else {
//            body.append("<td style=\"border-right: 1px solid #cccccc; padding: 5px 10px;\">$s</td>")
//        }
//    }
//
//    mimeBodyPart.setContent(
//        """
//        <div style="font-family: Arial, sans-serif; margin: 0; padding: 0; box-sizing: border-box; width: 100%;">
//            <span style="font-size: 0; max-height: 0; line-height: 0; display: none;">Voici votre code de synchronisation à usage unique. Veuillez le saisir dans l'application pour vous connecter. Ce code est valable pendant 15 minutes.</span>
//            <table style="width: 100%; border-collapse: collapse; background-color: #f5f5f5;">
//                <tr>
//                    <td style="vertical-align: top; text-align: center;">
//                        <div style="display: inline-block; max-width: 600px; width: 100%; margin: 50px 10px; background-color: #ffffff; border-radius: 8px; box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1); padding: 20px; box-sizing: border-box;">
//                            <a href="#"><img src="https://www.shikkanime.fr/assets/img/dark_banner.png" alt="Illustration" style="width: 100%; max-width: 400px; height: auto; margin-bottom: 20px;"></a>
//                            <div style="font-size: 24px; font-weight: bold; margin-bottom: 20px;"> Votre code de synchronisation </div>
//                            <div style="font-size: 48px; font-weight: bold; background-color: #f0f0f0; border-radius: 4px; padding: 20px 30px; margin-bottom: 20px; display: inline-table;">
//                                <table style="border-collapse: collapse;">
//                                    <tr>$body</tr>
//                                </table>
//                            </div>
//                            <div style="font-size: 14px; color: #666666; margin-bottom: 30px;"> Ce code est valable pendant 15 minutes. Veuillez saisir ce code dans l'application. </div>
//                            <p style="font-size: 16px; color: #333333; margin-bottom: 20px;"> Merci d'utiliser notre application ! Nous apprécions votre confiance et espérons que notre service répondra à vos attentes. </p>
//                            <p style="font-size: 12px; color: #999999; margin: 0;"> Ceci est un email automatique, merci de ne pas répondre. </p>
//                        </div>
//                    </td>
//                </tr>
//            </table>
//        </div>
//    """.trimIndent(), "text/html; charset=utf-8"
//    )
//
//    val multipart = MimeMultipart("related")
//    multipart.addBodyPart(mimeBodyPart)
//    message.setContent(multipart)
//    Transport.send(message)
//}