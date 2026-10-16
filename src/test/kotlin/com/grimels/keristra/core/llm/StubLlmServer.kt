package com.grimels.keristra.core.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Local HTTP server that records requests and answers every POST with a canned JSON body. */
internal class StubLlmServer(private val responseJson: String) : AutoCloseable {

    data class Recorded(val path: String, val headers: Map<String, String>, val body: JsonObject)

    val requests = mutableListOf<Recorded>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Recorded(
                path = exchange.requestURI.path,
                headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() },
                body = JsonParser.parseString(body).asJsonObject,
            )
            val bytes = responseJson.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val lastRequest: Recorded get() = requests.last()

    override fun close() = server.stop(0)
}
