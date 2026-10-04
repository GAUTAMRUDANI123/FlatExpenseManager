package com.flatexpense.data

import android.content.Context
import com.flatexpense.data.api.ApiErrorBody
import com.flatexpense.data.api.ApiService
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Raised for anything the user should see a message about. */
class ApiException(message: String) : Exception(message)

/**
 * Single entry point for network calls. The Retrofit instance is rebuilt when
 * the API base changes (Profile screen) or the token changes (sign in/out),
 * which keeps auth state in exactly one place.
 */
class Repository(context: Context) {

    val sessionStore = SessionStore(context.applicationContext)

    private var cachedBase: String? = null
    private var cachedToken: String? = null
    private var cachedService: ApiService? = null

    private suspend fun service(): ApiService {
        val session = sessionStore.current()
        val base = session.apiBase
        val token = session.token

        val existing = cachedService
        if (existing != null && cachedBase == base && cachedToken == token) return existing

        val client = OkHttpClient.Builder()
            // Generous on purpose. A free hosting tier stops the service after
            // a quiet spell and takes the better part of a minute to wake on
            // the next request, so the first person to open the app each
            // morning pays that wait. At 30 seconds they instead saw "cannot
            // reach the server" on a server that was simply still getting up,
            // and the obvious reading of that message — wrong address, no
            // internet — sends people looking in entirely the wrong place.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            })
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        val created = retrofit.create(ApiService::class.java)
        cachedBase = base
        cachedToken = token
        cachedService = created
        return created
    }

    /**
     * Turns transport and HTTP failures into one readable message. The server's
     * own error text is surfaced when it sent one, since those messages are
     * written for the user ("Only the group Admin can do this").
     */
    suspend fun <T> call(block: suspend (ApiService) -> T): Result<T> = try {
        Result.success(block(service()))
    } catch (e: HttpException) {
        // A 401 means the stored token is no longer good for anything — it has
        // expired, or the account behind it is gone. Every screen would
        // otherwise show its own dead end with a Try again button that can
        // never succeed, so the credential is dropped here and the app falls
        // back to the login screen, which is the only thing that can actually
        // fix it.
        if (e.code() == 401) {
            sessionStore.signOut()
            invalidate()
        }
        Result.failure(ApiException(e.readMessage()))
    } catch (e: IOException) {
        // A timeout and a wrong address both arrive here, and they need
        // different things done about them: one wants patience, the other
        // wants the address corrected. Telling them apart saves someone
        // retyping a perfectly good address at a server that was only asleep.
        val timedOut = e is java.net.SocketTimeoutException ||
            e is java.io.InterruptedIOException
        Result.failure(
            ApiException(
                if (timedOut) {
                    "The server is taking a long time to answer. It may be waking " +
                        "up, which can take up to a minute after a quiet spell — " +
                        "wait a moment and try again."
                } else {
                    "Cannot reach the server. Check the API address under Server " +
                        "settings, and that you have internet."
                }
            )
        )
    } catch (e: Exception) {
        Result.failure(ApiException(e.message ?: "Something went wrong"))
    }

    private fun HttpException.readMessage(): String {
        val raw = try {
            response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        val parsed = raw?.let {
            try {
                json.decodeFromString<ApiErrorBody>(it).error?.message
            } catch (_: Exception) {
                null
            }
        }
        return parsed ?: when (code()) {
            401 -> "Your session has expired. Please sign in again."
            403 -> "You do not have permission to do that."
            404 -> "Not found."
            else -> "Request failed (${code()})"
        }
    }

    /** Drops the cached Retrofit so the next call picks up new config. */
    fun invalidate() {
        cachedService = null
    }

    companion object {
        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context).also { instance = it }
            }
    }
}
