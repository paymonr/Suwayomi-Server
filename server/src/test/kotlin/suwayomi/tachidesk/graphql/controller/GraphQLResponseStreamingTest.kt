package suwayomi.tachidesk.graphql.controller

import com.expediagroup.graphql.server.types.GraphQLResponse
import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson3
import io.javalin.json.JsonMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import org.eclipse.jetty.io.EofException
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraphQLResponseStreamingTest {
    private class CapturingOutputStream : ServletOutputStream() {
        val bytes = ByteArrayOutputStream()

        override fun write(b: Int) = bytes.write(b)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = bytes.write(b, off, len)

        override fun isReady() = true

        override fun setWriteListener(writeListener: WriteListener?) = Unit
    }

    private fun context(
        out: CapturingOutputStream,
        mapper: JsonMapper = JavalinJackson3(),
    ): Context =
        mockk<Context>(relaxed = true).also {
            every { it.jsonMapper() } returns mapper
            every { it.outputStream() } returns out
        }

    @Test
    fun `responses are written straight to the response instead of built as one string`() {
        val out = CapturingOutputStream()
        val ctx = context(out)
        ctx.respondGraphQL(GraphQLResponse(data = mapOf("ping" to "pong")))
        assertTrue(out.bytes.size() > 0)
        verify(exactly = 0) { ctx.json(any()) }
        verify(exactly = 0) { ctx.jsonStream(any()) }
    }

    @Test
    fun `the bytes and content type are what json would have sent`() {
        val out = CapturingOutputStream()
        val ctx = context(out)
        val response =
            GraphQLResponse(
                data =
                    mapOf(
                        "mangas" to
                            mapOf("nodes" to listOf(mapOf("id" to 1, "title" to "Ünïcødé \"quoted\" 😀", "genre" to emptyList<String>()))),
                    ),
            )
        ctx.respondGraphQL(response)
        assertContentEquals(JavalinJackson3().toJsonString(response, response::class.java).toByteArray(), out.bytes.toByteArray())
        verify { ctx.contentType(ContentType.APPLICATION_JSON) }
    }

    @Test
    fun `a non-Jackson json mapper falls back to json`() {
        val ctx = context(CapturingOutputStream(), mockk(relaxed = true))
        val response = GraphQLResponse(data = mapOf("ping" to "pong"))
        ctx.respondGraphQL(response)
        verify { ctx.json(response) }
    }

    @Test
    fun `no response is a bad request`() {
        val ctx = mockk<Context>(relaxed = true)
        ctx.respondGraphQL(null)
        verify { ctx.status(HttpStatus.BAD_REQUEST) }
    }

    /**
     * Javalin logs a client abort at debug only when the exception (or a CompletionException's cause) is Jetty's
     * EofException itself (`isClientAbortException` in its ExceptionMapper); Jackson's wrapper around it is logged as an error.
     */
    @Test
    fun `a client that hangs up mid-response is reported as a client abort`() {
        val closed =
            object : ServletOutputStream() {
                override fun write(b: Int) = throw EofException("Closed")

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = throw EofException("Closed")

                override fun isReady() = true

                override fun setWriteListener(writeListener: WriteListener?) = Unit
            }
        val ctx =
            mockk<Context>(relaxed = true).also {
                every { it.jsonMapper() } returns JavalinJackson3()
                every { it.outputStream() } returns closed
            }
        val big = GraphQLResponse(data = mapOf("nodes" to List(10_000) { mapOf("id" to it, "title" to "series $it") }))

        val e = assertFailsWith<Throwable> { ctx.respondGraphQL(big) }

        assertIs<EofException>(e)
    }
}
