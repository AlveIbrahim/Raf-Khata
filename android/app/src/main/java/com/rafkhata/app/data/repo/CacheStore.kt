package com.rafkhata.app.data.repo

import com.rafkhata.app.data.db.CacheDao
import com.rafkhata.app.data.db.CacheEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** JSON snapshots of server responses in Room, used to show screens offline. */
class CacheStore(private val dao: CacheDao, private val json: Json) {
    suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
        dao.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        dao.put(CacheEntity(key, json.encodeToString(serializer, value), System.currentTimeMillis()))
    }

    suspend fun remove(key: String) = dao.delete(key)

    suspend fun clear() = dao.clear()
}
