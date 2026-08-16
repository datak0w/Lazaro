package io.lazaro.directions

import retrofit2.http.GET
import retrofit2.http.Query

interface DirectionsService {

    @GET("directions/json")
    suspend fun getWalkingRoute(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String = "walking",
        @Query("language") language: String = "es",
        @Query("key") apiKey: String,
    ): DirectionsResponse

    @GET("directions/json")
    suspend fun getTransitRoute(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String = "transit",
        @Query("language") language: String = "es",
        @Query("key") apiKey: String,
    ): DirectionsResponse

    @GET("directions/json")
    suspend fun getRouteFromCoordinates(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String = "walking",
        @Query("language") language: String = "es",
        @Query("key") apiKey: String,
    ): DirectionsResponse
}
