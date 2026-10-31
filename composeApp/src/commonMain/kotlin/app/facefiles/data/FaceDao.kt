package app.facefiles.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceDao {
    @Insert
    suspend fun insertAll(faces: List<FaceEntity>)

    @Query("SELECT COUNT(*) FROM faces")
    fun observeFaceCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun count(): Int

    /** Everything needed for the in-memory cosine-similarity search. */
    @Query("SELECT id, filePath, kind, frameTimeMs, pdfPage, score, embedding FROM faces")
    suspend fun allVectors(): List<FaceVector>

    @Query(
        "SELECT id, source, frameTimeMs, pdfPage, `left`, `top`, `right`, `bottom`, score, thumb " +
            "FROM faces WHERE filePath = :path ORDER BY frameTimeMs, pdfPage, `left`",
    )
    suspend fun facesOfFile(path: String): List<FaceInfo>

    @Query("SELECT thumb FROM faces WHERE id = :id")
    suspend fun thumbOf(id: Long): ByteArray?

    @Query("SELECT embedding FROM faces WHERE id = :id")
    suspend fun embeddingOf(id: Long): ByteArray?
}
