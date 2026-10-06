package com.rafkhata.app.data.repo

import com.rafkhata.app.data.api.RafKhataApi
import com.rafkhata.app.data.api.RoleIn
import com.rafkhata.app.data.api.SpaceDto
import com.rafkhata.app.data.api.SpaceIn
import com.rafkhata.app.data.api.SpaceJoinIn
import kotlinx.serialization.builtins.ListSerializer
import retrofit2.HttpException

class SpaceRepository(private val api: RafKhataApi, private val cache: CacheStore) {
    suspend fun cached(): List<SpaceDto>? = cache.get(KEY, SERIALIZER)

    suspend fun list(): List<SpaceDto> = api.spaces().also { cache.put(KEY, SERIALIZER, it) }

    suspend fun cachedSpace(id: String): SpaceDto? = cache.get("space:$id", SpaceDto.serializer())

    suspend fun get(id: String): SpaceDto = api.space(id).also { cache.put("space:$id", SpaceDto.serializer(), it) }

    suspend fun create(name: String, university: String?, sectionLabel: String?): SpaceDto =
        api.createSpace(SpaceIn(name.trim(), university?.trim()?.ifEmpty { null }, sectionLabel?.trim()?.ifEmpty { null }))

    suspend fun join(code: String): SpaceDto = api.joinSpace(SpaceJoinIn(code.trim().uppercase()))

    suspend fun rotateInviteCode(id: String): SpaceDto = api.rotateInviteCode(id)

    suspend fun setRole(id: String, userId: String, role: String): SpaceDto =
        api.setMemberRole(id, userId, RoleIn(role)).also { cache.put("space:$id", SpaceDto.serializer(), it) }

    suspend fun leave(id: String) {
        val response = api.leaveSpace(id)
        if (!response.isSuccessful) throw HttpException(response)
        cache.remove("space:$id")
    }

    private companion object {
        const val KEY = "spaces"
        val SERIALIZER = ListSerializer(SpaceDto.serializer())
    }
}
