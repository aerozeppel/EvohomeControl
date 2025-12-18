package com.yourname.evohomecontrol.api

import retrofit2.Response
import retrofit2.http.*

interface EvohomeApiService {
    
    @FormUrlEncoded
    @POST("Auth/OAuth/Token")
    suspend fun refreshToken(
        @Header("Authorization") auth: String = "Basic YjAxM2FhMjYtOTcyNC00ZGJkLTg4OTctMDQ4YjlhYWRhMjQ5OnRlc3Q=",
        @Header("Accept") accept: String = "application/json",
        @Field("grant_type") grantType: String,
        @Field("refresh_token") refreshToken: String,
        @Field("scope") scope: String = "EMEA-V1-Basic EMEA-V1-Anonymous EMEA-V1-Get-Current-User-Account"
    ): Response<TokenResponse>
    
    @POST("Auth/OAuth/Token")
    @FormUrlEncoded
    suspend fun getTokens(
        @Field("grant_type") grantType: String = "password",
        @Field("scope") scope: String = "EMEA-V1-Basic EMEA-V1-Anonymous EMEA-V1-Get-Current-User-Account",
        @Field("Username") username: String,
        @Field("Password") password: String
    ): Response<TokenResponse>
    
    @GET("WebAPI/emea/api/v1/userAccount")
    suspend fun getUserAccount(
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249"
    ): Response<Account>
    
    @GET("WebAPI/emea/api/v1/location/installationInfo")
    suspend fun getInstallations(
        @Query("userId") userId: String,
        @Query("includeTemperatureControlSystems") includeSystems: Boolean = true,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249"
    ): Response<List<Installation>>
    
    @GET("WebAPI/emea/api/v1/location/{locationId}/status")
    suspend fun getLocationStatus(
        @Path("locationId") locationId: String,
        @Query("includeTemperatureControlSystems") includeSystems: Boolean = true,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249"
    ): Response<Installation>
    
    @PUT("WebAPI/emea/api/v1/temperatureZone/{zoneId}/heatSetpoint")
    suspend fun setTemperature(
        @Path("zoneId") zoneId: String,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249",
        @Header("Content-Type") contentType: String = "application/json",
        @Body setpoint: HeatSetpoint
    ): Response<Unit>
    
    @GET("WebAPI/emea/api/v1/temperatureZone/{zoneId}/schedule")
    suspend fun getZoneSchedule(
        @Path("zoneId") zoneId: String,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249"
    ): Response<ZoneSchedule>

    @PUT("WebAPI/emea/api/v1/temperatureZone/{zoneId}/schedule")
    suspend fun updateZoneSchedule(
        @Path("zoneId") zoneId: String,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249",
        @Header("Content-Type") contentType: String = "application/json",
        @Body schedule: ZoneSchedule
    ): Response<Unit>

    @GET("WebAPI/emea/api/v1/temperatureControlSystem/{systemId}/status")
    suspend fun getSystemStatus(
        @Path("systemId") systemId: String,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249"
    ): Response<Any>  // Using Any for now since we don't know the structure

    // Try getting gateway status (more detailed)
    @GET("WebAPI/emea/api/v1/gateway/{gatewayId}/status")
    suspend fun getGatewayStatus(
        @Path("gatewayId") gatewayId: String,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249"
    ): Response<Any>

    // Try getting detailed zone status
    @GET("WebAPI/emea/api/v1/temperatureZone/{zoneId}/status")
    suspend fun getZoneStatus(
        @Path("zoneId") zoneId: String,
        @Header("Authorization") auth: String,
        @Header("ApplicationId") appId: String = "b013aa26-9724-4dbd-8897-048b9aada249"
    ): Response<Any>
}