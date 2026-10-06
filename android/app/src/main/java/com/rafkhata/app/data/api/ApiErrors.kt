package com.rafkhata.app.data.api

import androidx.annotation.StringRes
import com.rafkhata.app.R
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A user-facing error: a string resource plus an optional detail from the server. */
data class ErrorMessage(@param:StringRes val res: Int, val detail: String? = null)

private val errorJson = Json { ignoreUnknownKeys = true }

/** The `detail` string FastAPI puts in error bodies, if there is one. */
fun HttpException.serverDetail(): String? {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull() ?: return null
    return runCatching {
        when (val detail = errorJson.parseToJsonElement(body).jsonObject["detail"]) {
            is JsonPrimitive -> detail.contentOrNull
            is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }.getOrNull()
}

/** Segment indices listed in a 409 from `POST /lectures/{id}/finalize`. */
fun parseMissingSegments(body: String?): List<Int> {
    if (body.isNullOrBlank()) return emptyList()
    return runCatching {
        errorJson.parseToJsonElement(body).jsonObject["detail"]!!.jsonObject["missing"]!!.jsonArray.map {
            it.jsonPrimitive.int
        }
    }.getOrDefault(emptyList())
}

fun Throwable.toErrorMessage(): ErrorMessage = when (this) {
    is CancellationException -> throw this
    is HttpException -> when (code()) {
        401 -> ErrorMessage(R.string.error_signed_out)
        403 -> ErrorMessage(R.string.error_forbidden)
        404 -> ErrorMessage(R.string.error_not_found)
        in 500..599 -> ErrorMessage(R.string.error_server)
        else -> ErrorMessage(R.string.error_request, serverDetail())
    }
    is IOException -> ErrorMessage(R.string.error_network)
    is SerializationException -> ErrorMessage(R.string.error_server)
    else -> ErrorMessage(R.string.error_unknown, message)
}

/** True for failures worth retrying later: no connection, timeouts, rate limits and server errors. */
fun Throwable.isTransient(): Boolean = when (this) {
    is IOException -> true
    is HttpException -> code() == 408 || code() == 429 || code() >= 500
    else -> false
}
