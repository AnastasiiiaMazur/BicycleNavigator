package com.bccle.navigator.fragments.helpers

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

@Keep data class OrsRoundTrip(
    @SerializedName("length") val length: Int,
    @SerializedName("points") val points: Int = 5,
    @SerializedName("seed")   val seed: Int = 1
)

@Keep data class OrsOptions(
    @SerializedName("round_trip") val roundTrip: OrsRoundTrip? = null
)

@Keep data class OrsDirectionsBody(
    @SerializedName("coordinates")        val coordinates: List<List<Double>>,
    @SerializedName("options")            val options: OrsOptions? = null,
    @SerializedName("instructions")       val instructions: Boolean = false,
    @SerializedName("elevation")          val elevation: Boolean = false,
    @SerializedName("geometry_simplify")  val geometrySimplify: Boolean = true
)

// ---- Response (GeoJSON) ----
@Keep data class OrsGeoJsonResponse(
    val features: List<OrsFeature>
)
@Keep data class OrsFeature(
    val geometry: OrsGeometry,
    val properties: OrsProperties?
)
@Keep data class OrsGeometry(
    val coordinates: List<List<Double>> // [[lon, lat], ...]
)
@Keep data class OrsProperties(
    val summary: OrsSummary?
)
@Keep data class OrsSummary(
    val distance: Double, // meters
    val duration: Double  // seconds
)

// ---- Retrofit API ----
interface OrsService {
    @POST("v2/directions/{profile}/geojson")
    suspend fun routeGeoJson(
        @Header("Authorization") apiKey: String,
        @Path("profile") profile: String, // e.g., cycling-regular / cycling-road / cycling-mountain
        @Body body: OrsDirectionsBody
    ): OrsGeoJsonResponse
}



// ---- Factory ----
object OrsClient {
    private const val BASE_URL = "https://api.openrouteservice.org/"

    fun create(logging: Boolean = false): OrsService {
        val builder = OkHttpClient.Builder()
        if (logging) {
            val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            builder.addInterceptor(log)
        }
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(builder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OrsService::class.java)
    }
}

fun Context.manifestMetaString(): String? {
    var devKey = ""
    val ai    = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    val value = ai.metaData["keyValue"]
    devKey    = value?.toString() ?: ""
    return devKey
}