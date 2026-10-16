package suwayomi.tachidesk.manga.impl.download.model

/** Fork-only (Batch E): why a download attempt failed, as the downloader's `failed due to` log line prints it. */
data class DownloadFailure(
    val exception: String,
    val message: String?,
) {
    companion object {
        /** The same parts `Throwable.toString()` joins with ": ". */
        fun of(e: Throwable) = DownloadFailure(e.javaClass.name, e.localizedMessage)
    }
}
