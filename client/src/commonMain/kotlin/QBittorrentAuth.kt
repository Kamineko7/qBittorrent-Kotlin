package qbittorrent

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import qbittorrent.QBittorrentClient.Config

internal typealias ExecuteAuth = suspend (HttpClient, String, String, String) -> HttpResponse

internal class QBittorrentAuth {

    private lateinit var config: Config
    private var executeAuth: ExecuteAuth = { _, _, _, _ ->
        throw NotImplementedError("executeAuth must be implemented for QBittorrentAuth.")
    }

    fun setConfig(config: Config) {
        this.config = config
    }

    fun setLogin(block: ExecuteAuth) {
        executeAuth = block
    }

    private val authMutex = Mutex()
    private val lastAuthResponse = MutableStateFlow<HttpResponse?>(null)

    val lastAuthResponseState: StateFlow<HttpResponse?> = lastAuthResponse

    suspend fun tryAuth(http: HttpClient, config: Config, executeAuth: ExecuteAuth): Boolean {
        return authMutex.withLock {
            if (lastAuthResponse.value?.isValidForAuth() == true) {
                // Authentication completed while waiting for lock, skip
                return@withLock true
            }

            val (baseUrl, username, password) = config
            val response = executeAuth(http, baseUrl, username, password)

            lastAuthResponse.value = response
            yield()
            response.isValidForAuth()
        }
    }

    companion object : HttpClientPlugin<QBittorrentAuth, QBittorrentAuth> {

        override val key: AttributeKey<QBittorrentAuth> = AttributeKey("QBittorrentAuth")

        override fun prepare(block: QBittorrentAuth.() -> Unit): QBittorrentAuth =
            QBittorrentAuth().apply(block)

        override fun install(plugin: QBittorrentAuth, scope: HttpClient) {
            scope.sendPipeline.intercept(HttpSendPipeline.Before) {
                // Attempt user's request
                val call = proceed() as? HttpClientCall ?: return@intercept
                if (call.request.url.pathSegments.lastOrNull() == "login") {
                    return@intercept
                }
                when (call.response.status) {
                    HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                        plugin.lastAuthResponse.value = call.response
                        // Authentication required
                        if (plugin.tryAuth(scope, plugin.config, plugin.executeAuth)) {
                            // Authentication Succeeded, retry original request
                            proceedWith(scope.request(HttpRequestBuilder().takeFrom(context)).call)
                        }
                    }
                }
            }
        }
    }

    private suspend fun HttpResponse.isValidForAuth(): Boolean {
        return status.isSuccess() && bodyAsText().equals("ok.", true)
    }
}
