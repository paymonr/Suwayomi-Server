package suwayomi.tachidesk.graphql.controller

import com.expediagroup.graphql.server.types.GraphQLResponse
import io.javalin.Javalin
import io.javalin.json.JavalinJackson3
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals

/** Characterization (F6): a large response written by [respondGraphQL] arrives intact through a real Javalin, as `json` sent it. */
class GraphQLResponseHttpTest {
    @Test
    fun `a large response arrives byte for byte, plain or gzipped`() {
        val response =
            GraphQLResponse(data = mapOf("nodes" to (1..20_000).map { mapOf("id" to it, "title" to "Ünïcødé \"$it\" 😀") }))
        val expected = JavalinJackson3().toJsonString(response, response::class.java)
        val app =
            Javalin
                .create { config ->
                    config.jsonMapper(JavalinJackson3())
                    config.routes.post("/graphql") { ctx ->
                        ctx.future { CompletableFuture.supplyAsync { response }.thenApply { ctx.respondGraphQL(it) } }
                    }
                }.start(0)
        try {
            val client = HttpClient.newHttpClient()
            listOf("identity", "gzip").forEach { encoding ->
                val request =
                    HttpRequest
                        .newBuilder(URI("http://localhost:${app.port()}/graphql"))
                        .header("Accept-Encoding", encoding)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build()
                val res = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val contentEncoding = res.headers().firstValue("Content-Encoding").orElse("identity")
                assertEquals(encoding, contentEncoding, "Javalin's default gzip still applies")
                val body =
                    if (contentEncoding == "gzip") {
                        GZIPInputStream(res.body().inputStream()).readAllBytes()
                    } else {
                        res.body()
                    }
                assertEquals(200, res.statusCode(), encoding)
                assertEquals(
                    "application/json",
                    res
                        .headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .substringBefore(';'),
                    encoding,
                )
                assertEquals(expected, body.toString(Charsets.UTF_8), encoding)
            }
        } finally {
            app.stop()
        }
    }
}
