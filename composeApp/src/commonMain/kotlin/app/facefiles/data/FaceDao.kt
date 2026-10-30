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

    /** Everything needed for an in-memory cosine-similarity search. */
    @Query("SELECT id, filePath, kind, embedding FROM faces")
    suspend fun allVectors(): List<FaceVector>
}
