package br.com.curiosidades.narrador.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val text: String = "",
    val voice: String = "pf_dora",
    val speed: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: String = "RASCUNHO", // RASCUNHO, GERANDO, PAUSADO, PRONTO, ERRO
    val doneChunks: Int = 0,
    val totalChunks: Int = 0,
    val chunkKey: String = "",
    val audioPath: String = "",
    val error: String = ""
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun all(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): Project?

    @Insert
    suspend fun insert(p: Project): Long

    @Query("UPDATE projects SET name=:name, text=:text, voice=:voice, speed=:speed, updatedAt=:now WHERE id=:id")
    suspend fun saveEditor(id: Long, name: String, text: String, voice: String, speed: Float, now: Long)

    @Query("UPDATE projects SET name=:name, updatedAt=:now WHERE id=:id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE projects SET status=:status, doneChunks=:done, totalChunks=:total, chunkKey=:key, error=:error WHERE id=:id")
    suspend fun progress(id: Long, status: String, done: Int, total: Int, key: String, error: String)

    @Query("UPDATE projects SET status=:status, error=:error WHERE id=:id")
    suspend fun status(id: Long, status: String, error: String)

    @Query("UPDATE projects SET status='PRONTO', audioPath=:path, error='' WHERE id=:id")
    suspend fun finish(id: Long, path: String)

    @Query("DELETE FROM projects WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("UPDATE projects SET status='PAUSADO' WHERE status='GERANDO'")
    suspend fun markInterrupted()
}

@Database(entities = [Project::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): ProjectDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "narrador.db").build().also { inst = it }
        }
    }
}
