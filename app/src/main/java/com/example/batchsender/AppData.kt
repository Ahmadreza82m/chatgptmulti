package com.example.batchsender

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "connections")
data class ConnectionEntity(@PrimaryKey val id: String, val displayName: String, val provider: String = "openai", val enabled: Boolean = true, val maxConcurrency: Int = 2, val status: String = "AVAILABLE", val running: Int = 0, val lastActivity: Long = 0L, val lastError: String? = null)
@Entity(tableName = "batches")
data class BatchEntity(@PrimaryKey val id: String, val name: String, val prompt: String, val state: String = "PENDING", val createdAt: Long = System.currentTimeMillis(), val startedAt: Long? = null, val endedAt: Long? = null)
@Entity(tableName = "tasks", indices = [Index("batchId"), Index("state")])
data class TaskEntity(@PrimaryKey val id: String, val batchId: String, val imageUri: String, val prompt: String, val connectionId: String? = null, val state: String = "PENDING", val retryCount: Int = 0, val progress: Int = 0, val error: String? = null, val resultUri: String? = null, val createdAt: Long = System.currentTimeMillis(), val startedAt: Long? = null, val completedAt: Long? = null)
@Entity(tableName = "prompts")
data class PromptEntity(@PrimaryKey val id: String, val name: String, val text: String, val favorite: Boolean = false, val updatedAt: Long = System.currentTimeMillis())

@Dao interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY displayName") fun observe(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections WHERE enabled = 1") suspend fun enabled(): List<ConnectionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: ConnectionEntity)
}
@Dao interface BatchDao {
    @Insert suspend fun insert(b: BatchEntity)
    @Query("SELECT * FROM batches ORDER BY createdAt DESC") fun observe(): Flow<List<BatchEntity>>
    @Query("UPDATE batches SET state = :state, startedAt = :started, endedAt = :ended WHERE id = :id") suspend fun updateState(id: String, state: String, started: Long?, ended: Long?)
}
@Dao interface TaskDao {
    @Insert suspend fun insertAll(tasks: List<TaskEntity>)
    @Query("SELECT * FROM tasks WHERE batchId = :batchId ORDER BY createdAt") fun observe(batchId: String): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE batchId = :batchId AND state = 'PENDING' ORDER BY createdAt") suspend fun pending(batchId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE batchId = :batchId AND state IN ('PROCESSING','UPLOADING','DOWNLOADING','RETRYING')") suspend fun running(batchId: String): List<TaskEntity>
    @Query("UPDATE tasks SET state = :state, connectionId = :connectionId, progress = :progress, retryCount = :retry, error = :error, resultUri = :result, startedAt = :started, completedAt = :completed WHERE id = :id") suspend fun update(id: String, state: String, connectionId: String?, progress: Int, retry: Int, error: String?, result: String?, started: Long?, completed: Long?)
    @Query("UPDATE tasks SET state = 'PENDING', error = NULL WHERE id = :id") suspend fun retry(id: String)
}

@Database(entities = [ConnectionEntity::class, BatchEntity::class, TaskEntity::class, PromptEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connections(): ConnectionDao
    abstract fun batches(): BatchDao
    abstract fun tasks(): TaskDao
    companion object { @Volatile private var instance: AppDatabase? = null; fun get(c: Context): AppDatabase = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, "multisend.db").build().also { instance = it } } }
}

class SecretStore(private val context: Context) {
    private val alias = "multisend_api_keys"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return kg.generateKey()
    }
    fun put(id: String, value: String) { val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); val packed = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)); context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString(id, Base64.getEncoder().encodeToString(packed)).apply() }
    fun get(id: String): String? = context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString(id, null)?.let { encoded -> try { val packed = Base64.getDecoder().decode(encoded); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12))); String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8) } catch (_: Exception) { null } }
}

class AppRepository(private val context: Context, private val db: AppDatabase) {
    val connections = db.connections().observe()
    private val secrets = SecretStore(context)
    suspend fun addConnection(name: String, apiKey: String) = withContext(Dispatchers.IO) { val id = "conn-" + java.util.UUID.randomUUID(); secrets.put(id, apiKey); db.connections().upsert(ConnectionEntity(id, name)); }
    suspend fun createBatch(name: String, prompt: String, uris: List<Uri>): String { val id = "batch-" + java.util.UUID.randomUUID(); db.batches().insert(BatchEntity(id, name.ifBlank { "Batch ${System.currentTimeMillis()}" }, prompt)); db.tasks().insertAll(uris.map { TaskEntity("task-" + java.util.UUID.randomUUID(), id, it.toString(), prompt) }); return id }
    suspend fun apiKey(connectionId: String): String? = withContext(Dispatchers.IO) { secrets.get(connectionId) }
}
