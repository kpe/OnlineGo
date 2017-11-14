package io.zenandroid.onlinego.ogs

import io.reactivex.Single
import io.zenandroid.onlinego.BuildConfig
import io.zenandroid.onlinego.model.ogs.LoginToken
import io.zenandroid.onlinego.model.ogs.UIConfig
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Created by alex on 02/11/2017.
 */
interface OGSRestAPI {
    // The secret and client id are fakes and will not work. Get your own :)
    @POST("oauth2/token/")
    fun login(@Query("username") username: String,
              @Query("password") password: String,
              @Query("client_id") client_id: String = BuildConfig.CLIENT_ID,
              @Query("client_secret") client_secret: String = BuildConfig.CLIENT_SECRET,
              @Query("grant_type") grant_type: String = "password"): Single<LoginToken>

    @POST("oauth2/token/")
    fun refreshToken(@Query("refresh_token") refresh_token: String,
                     @Query("client_id") client_id: String = BuildConfig.CLIENT_ID,
                     @Query("client_secret") client_secret: String = BuildConfig.CLIENT_SECRET,
                     @Query("grant_type") grant_type: String = "refresh_token"): Single<LoginToken>

    @GET("api/v1/ui/config/")
    fun uiConfig(): Single<UIConfig>

}