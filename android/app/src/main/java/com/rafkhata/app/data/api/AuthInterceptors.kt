package com.rafkhata.app.data.api

import com.rafkhata.app.data.auth.TokenStore
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

private fun Request.isAuthCall(): Boolean = url.encodedPath.contains("/auth/")

/** Adds the access token to every API call except the sign-in endpoints. */
class AuthInterceptor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokens.accessToken
        if (token == null || request.isAuthCall() || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Called by OkHttp on a 401: swaps the refresh token for a new pair and repeats the request once.
 * Refresh tokens rotate, so concurrent 401s are serialized and reuse the first refresh's result.
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val authApi: () -> AuthApi,
    private val onSessionExpired: () -> Unit,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.isAuthCall() || response.priorResponse != null) return null
        val sent = request.header("Authorization")?.removePrefix("Bearer ")
        synchronized(lock) {
            val current = tokens.accessToken
            if (current != null && current != sent) {
                return request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refresh = tokens.refreshToken ?: return null
            val result = try {
                authApi().refresh(RefreshIn(refresh)).execute()
            } catch (_: IOException) {
                return null
            }
            val body = result.body()
            if (!result.isSuccessful || body == null) {
                if (result.code() == 401) {
                    tokens.clear()
                    onSessionExpired()
                }
                return null
            }
            tokens.save(body)
            return request.newBuilder().header("Authorization", "Bearer ${body.accessToken}").build()
        }
    }
}
