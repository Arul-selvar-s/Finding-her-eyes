package app.facefiles.domain

enum class FileCategory(val label: String) {
    PHOTO("Photos"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    OTHER("Other");

    companion object {
        private val photoExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        private val videoExt = setOf("mp4", "mkv", "avi", "mov", "wmv", "webm", "3gp", "m4v", "flv", "mpg", "mpeg")
        private val audioExt = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "amr")
        private val documentExt = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "xlsm", "csv", "ppt", "pptx",
            "txt", "rtf", "odt", "ods", "md",
        )

        /** [extension] must already be lower-case and without the dot. */
        fun fromExtension(extension: String): FileCategory = when (extension) {
            in photoExt -> PHOTO
            in videoExt -> VIDEO
            in audioExt -> AUDIO
            in documentExt -> DOCUMENT
            else -> OTHER
        }
    }
}

enum class FaceScanState {
    /** Needs to go through the face pipeline. */
    PENDING,
    DONE,
    FAILED,
    /** Audio, spreadsheets, etc. — nothing to look for. */
    SKIPPED;

    companion object {
        fun initialFor(category: FileCategory, extension: String): FaceScanState = when {
            category == FileCategory.PHOTO -> PENDING
            category == FileCategory.VIDEO -> PENDING
            extension == "pdf" -> PENDING
            else -> SKIPPED
        }
    }
}

enum class FaceKind { HUMAN, ANIMAL }

enum class FaceSource { IMAGE, VIDEO_FRAME, PDF_IMAGE }
