/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gradlenexus.publishplugin.modern

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class MemoryRepository(private val evidence: File) : Closeable {
    val files = ConcurrentHashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
    val authenticated = CopyOnWriteArrayList<Boolean>()
    data class Response(val status: Int, val location: String? = null, val delayMillis: Long = 0, val commit: Boolean = false, val disconnect: Boolean = false, val data: String? = null)

    @Volatile var response: ((String, String) -> Response?)? = null

    @Volatile private var expectedAuthorization: String? = null
    fun expect(username: String, password: String) {
        expectedAuthorization = "Basic " + java.util.Base64.getEncoder().encodeToString((username + ":" + password).toByteArray(Charsets.UTF_8))
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val endpoint: URI get() = URI("http://127.0.0.1:${server.address.port}/")
    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.use { it.readBytes() }
            requests.add(Triple(exchange.requestMethod, path, body))
            val stored = files[path] ?: if (path.endsWith(".sha1")) files[path.removeSuffix(".sha1")]?.let { digest(it).toByteArray() } else null
            val expected = expectedAuthorization
            val accepted = expected == null || MessageDigest.isEqual((exchange.requestHeaders.getFirst("Authorization") ?: "").toByteArray(), expected.toByteArray())
            authenticated.add(accepted)
            val scripted = if (accepted) response?.invoke(exchange.requestMethod, path) else null
            if (!accepted) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"TransportFixture test\"")
                exchange.sendResponseHeaders(401, -1)
            } else if (scripted != null) {
                if (scripted.commit) files[path] = body
                if (scripted.delayMillis > 0) Thread.sleep(scripted.delayMillis)
                scripted.location?.let { exchange.responseHeaders.add("Location", it) }
                if (!scripted.disconnect) {
                    val responseBody = scripted.data?.toByteArray(Charsets.UTF_8)
                    if (responseBody != null) exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(scripted.status, responseBody?.size?.toLong() ?: -1)
                    if (responseBody != null) exchange.responseBody.use { it.write(responseBody) }
                }
            } else if (exchange.requestMethod == "PUT") {
                files[path] = body
                exchange.sendResponseHeaders(201, -1)
            } else if (exchange.requestMethod !in listOf("GET", "HEAD")) {
                exchange.sendResponseHeaders(405, -1)
            } else if (stored != null) {
                exchange.sendResponseHeaders(200, if (exchange.requestMethod == "HEAD") -1 else stored.size.toLong())
                if (exchange.requestMethod != "HEAD") exchange.responseBody.use { it.write(stored) }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        server.start()
    }
    fun seed(version: String) {
        files["/probe/native/maven-metadata.xml"] = "<metadata><groupId>probe</groupId><artifactId>native</artifactId><versioning><latest>0.9</latest><release>0.9</release><versions><version>0.9</version></versions><lastUpdated>20200101000000</lastUpdated></versioning></metadata>".toByteArray()
        if (version.endsWith("SNAPSHOT")) {
            files["/probe/native/$version/maven-metadata.xml"] = "<metadata><groupId>probe</groupId><artifactId>native</artifactId><version>$version</version><versioning><snapshot><timestamp>20200101.000000</timestamp><buildNumber>7</buildNumber></snapshot><lastUpdated>20200101000000</lastUpdated></versioning></metadata>".toByteArray()
            files["/probe/native/$version/native-1.0-20200101.000000-7.jar"] = byteArrayOf(1, 2, 3)
        }
    }
    override fun close() {
        server.stop(0)
        evidence.mkdirs()
        File(evidence, "requests.tsv").writeText(
            requests.mapIndexed { index, request ->
                File(evidence, "request-$index.body").writeBytes(request.third)
                "$index\t${request.first}\t${request.second}"
            }.joinToString("\n") + "\n"
        )
        File(evidence, "remote-files.tsv").writeText(
            files.entries.sortedBy { it.key }.mapIndexed { index, item ->
                File(evidence, "remote-$index.body").writeBytes(item.value)
                "$index\t${item.key}"
            }.joinToString("\n") + "\n"
        )
    }
    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
