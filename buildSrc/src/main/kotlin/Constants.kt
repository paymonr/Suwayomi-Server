import java.io.BufferedReader

/*
 * Copyright (C) Contributors to the Suwayomi project
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

const val MainClass = "suwayomi.tachidesk.MainKt"

// should be bumped with each stable release
// fork: report the upstream base version, so WebUI/server compatibility checks (WebInterfaceManager) match what
// this fork actually contains; fork builds are told apart by the revision (r<commit count>-fork)
const val FORK_BASE_VERSION = "v2.4.2366"
val getTachideskVersion = { FORK_BASE_VERSION }

val webUIRevisionTag = "r3518"

val webviewJbrRelease = "jbr-release-25.0.4b508.27"

private val getCommitCount = {
    runCatching {
        ProcessBuilder()
            .command("git", "rev-list", "HEAD", "--count")
            .start()
            .let { process ->
                process.waitFor()
                val output = process.inputStream.use {
                    it.bufferedReader().use(BufferedReader::readText)
                }
                process.destroy()
                output.trim()
            }
    }.getOrDefault("0")
}

// counts commits on the current checked out branch
// fork marker: shows in the WebUI's About page and aboutServer.revision; the version string stays numeric
val getTachideskRevision = { "r${getCommitCount()}-fork" }

