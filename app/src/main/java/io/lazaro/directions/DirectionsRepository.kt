package io.lazaro.directions

import io.lazaro.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectionsRepository @Inject constructor() {

    private val apiKey: String = BuildConfig.GOOGLE_MAPS_API_KEY

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://maps.googleapis.com/maps/api/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(DirectionsService::class.java)

    suspend fun getWalkingRoute(origin: String, destination: String): Result<Route> {
        return fetchRoute {
            service.getWalkingRoute(
                origin = origin,
                destination = destination,
                apiKey = apiKey,
            )
        }
    }

    suspend fun getWalkingRouteFromCoords(
        originLat: Double,
        originLng: Double,
        destination: String,
    ): Result<Route> {
        val origin = "$originLat,$originLng"
        return fetchRoute {
            service.getWalkingRoute(
                origin = origin,
                destination = destination,
                apiKey = apiKey,
            )
        }
    }

    suspend fun getWalkingRouteCoordsToCoords(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
    ): Result<Route> {
        val origin = "$originLat,$originLng"
        val dest = "$destLat,$destLng"
        return fetchRoute {
            service.getWalkingRoute(
                origin = origin,
                destination = dest,
                apiKey = apiKey,
            )
        }
    }

    suspend fun getTransitRoute(origin: String, destination: String): Result<Route> {
        return fetchRoute {
            service.getTransitRoute(
                origin = origin,
                destination = destination,
                apiKey = apiKey,
            )
        }
    }

    private suspend fun fetchRoute(call: suspend () -> DirectionsResponse): Result<Route> {
        return try {
            if (apiKey.isBlank()) {
                return Result.failure(IllegalStateException("GOOGLE_MAPS_API_KEY no configurada en local.properties"))
            }
            val response = call()
            when {
                response.status != "OK" -> {
                    Result.failure(Exception("Directions API error: ${response.status} - ${response.errorMessage ?: ""}"))
                }
                response.routes.isEmpty() -> {
                    Result.failure(Exception("No se encontró ninguna ruta."))
                }
                else -> {
                    Result.success(response.routes.first())
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
