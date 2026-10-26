package suwayomi.tachidesk.graphql.queries

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.manga.impl.fork.ChapterDownloadSpeeds
import suwayomi.tachidesk.manga.impl.fork.ChapterDownloadStats
import suwayomi.tachidesk.server.JavalinSetup.future
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture

/** Fork-only: chapter download speeds (docs/superpowers/specs/2026-10-25-chapter-download-speeds-design.md, unraid repo). */
class ForkDownloadSpeedQuery {
    data class DownloadFailureCount(
        val outcome: String,
        val count: Int,
    )

    data class SourceDownloadSpeed(
        val sourceId: Long,
        @GraphQLDescription("Attempts that downloaded the chapter")
        val chapters: Int,
        val failedAttempts: Int,
        val pages: Int,
        val bytes: Long,
        val secondsPerPageMedian: Double?,
        val secondsPerPageP90: Double?,
        @GraphQLDescription("Megabytes (10^6 bytes) per second over the downloaded chapters together")
        val megabytesPerSecond: Double?,
        @GraphQLDescription("Median time to get a page list, over the attempts that got one")
        val listSecondsMedian: Double?,
        val pageRetries: Int,
        @GraphQLDescription("Up to five failure reasons, most frequent first")
        val topFailures: List<DownloadFailureCount>,
    )

    data class DayDownloadSpeed(
        @GraphQLDescription("Local date in the server's time zone, yyyy-MM-dd")
        val day: String,
        val chapters: Int,
        val failedAttempts: Int,
        val secondsPerPageMedian: Double?,
        val megabytesPerSecond: Double?,
    )

    @RequireAuth
    @GraphQLDescription(
        "Fork-only: download speed per source, over the download attempts started in [since, until) (epoch ms; no end " +
            "without until)",
    )
    fun downloadSpeedBySource(
        since: Long,
        until: Long? = null,
    ): CompletableFuture<List<SourceDownloadSpeed>> =
        future {
            ChapterDownloadSpeeds
                .bySource(ChapterDownloadStats.attempts(since, until ?: Long.MAX_VALUE))
                .map { s ->
                    SourceDownloadSpeed(
                        sourceId = s.sourceId,
                        chapters = s.chapters,
                        failedAttempts = s.failedAttempts,
                        pages = s.pages,
                        bytes = s.bytes,
                        secondsPerPageMedian = s.secondsPerPageMedian,
                        secondsPerPageP90 = s.secondsPerPageP90,
                        megabytesPerSecond = s.megabytesPerSecond,
                        listSecondsMedian = s.listSecondsMedian,
                        pageRetries = s.pageRetries,
                        topFailures = s.topFailures.map { DownloadFailureCount(it.outcome, it.count) },
                    )
                }
        }

    @RequireAuth
    @GraphQLDescription(
        "Fork-only: one source's download speed per local day, for the last [days] days (1 to 365, today included); " +
            "days without attempts are left out",
    )
    fun downloadSpeedByDay(
        sourceId: Long,
        days: Int,
    ): CompletableFuture<List<DayDownloadSpeed>> =
        future {
            require(days in 1..365) { "days must be between 1 and 365" }
            val zone = ZoneId.systemDefault()
            val since = ChapterDownloadSpeeds.daysWindowStart(days, Instant.now(), zone)
            ChapterDownloadSpeeds
                .byDay(ChapterDownloadStats.attempts(since, Long.MAX_VALUE, sourceId), zone)
                .map { DayDownloadSpeed(it.day.toString(), it.chapters, it.failedAttempts, it.secondsPerPageMedian, it.megabytesPerSecond) }
        }
}
