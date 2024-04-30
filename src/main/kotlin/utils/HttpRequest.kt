package fr.shikkanime.utils

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

private const val TIMEOUT = 60_000L

class HttpRequest : AutoCloseable {
    private val httpClient = httpClient()

    private fun httpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT
                connectTimeoutMillis = TIMEOUT
                socketTimeoutMillis = TIMEOUT
            }
            engine {
                config {
                    followRedirects(true)
                }
            }
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        return httpClient.get(url) {
            headers.forEach { (key, value) ->
                header(key, value)
            }
        }
    }

    suspend fun post(url: String, headers: Map<String, String> = emptyMap(), body: Any): HttpResponse {
        return httpClient.post(url) {
            headers.forEach { (key, value) ->
                header(key, value)
            }

            setBody(body)
        }
    }

    override fun close() {
        httpClient.close()
    }
}