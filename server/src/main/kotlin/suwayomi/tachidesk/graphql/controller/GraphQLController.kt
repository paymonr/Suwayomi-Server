/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.controller

import com.expediagroup.graphql.server.types.GraphQLServerResponse
import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson3
import io.javalin.websocket.WsConfig
import suwayomi.tachidesk.graphql.server.TachideskGraphQLServer
import suwayomi.tachidesk.graphql.server.TemporaryFileStorage
import suwayomi.tachidesk.server.JavalinSetup.future
import tools.jackson.core.JacksonException
import java.io.IOException
import kotlin.io.path.inputStream

object GraphQLController {
    private val server = TachideskGraphQLServer.create()

    /** execute graphql query */
    fun execute(ctx: Context) {
        ctx.future {
            future {
                server.execute(ctx)
            }.thenApply { ctx.respondGraphQL(it) }
        }
    }

    fun playground(ctx: Context) {
        ctx.contentType(ContentType.TEXT_HTML)
        ctx.result(javaClass.getResourceAsStream("/graphql-playground.html")!!)
    }

    fun retrieveFile(ctx: Context) {
        val filename = ctx.pathParam("file")
        val file = TemporaryFileStorage.retrieveFile(filename)
        ctx.contentType("application/octet-stream")
        ctx.header("Content-Disposition", """attachment; filename="$filename"""")
        ctx.result(file.inputStream())
    }

    fun webSocket(ws: WsConfig) {
        ws.onMessage { ctx ->
            server.handleSubscriptionMessage(ctx)
        }
        ws.onClose { ctx ->
            server.handleSubscriptionDisconnect(ctx)
        }
    }
}

/**
 * Fork (F6): write the JSON straight to the response instead of building it as one String first
 * (suwayomi-dm's snapshot is ~44 MB). Not `jsonStream`: Javalin pipes that through a 1 KB buffer on
 * another thread, which took 3.7 s instead of 0.15 s for a 56 MB response.
 */
internal fun Context.respondGraphQL(response: GraphQLServerResponse?) {
    if (response == null) {
        status(HttpStatus.BAD_REQUEST)
        return
    }
    val mapper = (jsonMapper() as? JavalinJackson3)?.mapper
    if (mapper == null) {
        json(response)
        return
    }
    contentType(ContentType.APPLICATION_JSON)
    try {
        mapper.writeValue(outputStream(), response)
    } catch (e: JacksonException) {
        // Jackson wraps a write to a closed connection; Javalin logs a client abort at debug only when it sees
        // Jetty's EofException itself, so rethrow the I/O error underneath
        throw generateSequence<Throwable>(e) { it.cause }.filterIsInstance<IOException>().firstOrNull() ?: e
    }
}
