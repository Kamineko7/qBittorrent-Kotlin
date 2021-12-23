package drewcarlson.qbittorrent

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.util.*
import io.ktor.utils.io.errors.IOException

/**
 * Ktor client feature to work around the flaky behavior
 * of qBittorrent's HTTP API.
 *
 * Occasionally requests will be accepted by the server but
 * the request is closed before any content is transferred
 */
internal class FlakyRetry {
    companion object : HttpClientPlugin<Unit, FlakyRetry> {
        override val key: AttributeKey<FlakyRetry> = AttributeKey("FlakyRetry")

        override fun prepare(block: Unit.() -> Unit): FlakyRetry = FlakyRetry()

        override fun install(plugin: FlakyRetry, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Render) {
                try {
                    proceed()
                } catch (e: IOException) {
                    if ((e.message ?: "").contains("unexpected end of stream")) {
                        val req = HttpRequestBuilder().takeFrom(context)
                        val response = scope.request(req)
                        proceedWith(response.call)
                    }
                }
            }
        }
    }
}
