import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

// 🔮 TRANSFER KEHANETİ — "Kim Şampiyon Olur?" anketinin yerini alan yeni
// özellik. Giriş sistemi olmadığı için deviceId (localStorage, tarayıcıya
// özel) ile kimin kehaneti olduğu takip ediliyor.
//
// ⚠️ ÖNEMLİ MİMARİ NOT: Bu veri SQLite (football.db) yerine BİLEREK
// Supabase'e (poll_votes ile AYNI proje) yazılıyor. Sebep: Render'da
// football.db kalıcı değil — her deploy'da GitHub Releases'tan SIFIRDAN
// iniyor (loglarda "football.db bulunamadı, indiriliyor" satırını her
// deploy'da görüyoruz). Yani football.db'ye yazılan HERHANGİ bir veri, bir
// sonraki deploy'da tamamen silinir. Kullanıcı kehanetleri gibi kalıcı olması
// gereken veriler için Supabase (dış, deploy'lardan bağımsız bir veritabanı) şart.
object PredictionManager {

    private val supabaseUrl = System.getenv("SUPABASE_URL")?.trimEnd('/')
    private val supabaseKey = System.getenv("SUPABASE_KEY")

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    @Serializable
    data class Prediction(
        val id: Long? = null,
        @kotlinx.serialization.SerialName("device_id") val deviceId: String? = null,
        @kotlinx.serialization.SerialName("player_name") val playerName: String,
        @kotlinx.serialization.SerialName("target_club") val targetClub: String,
        @kotlinx.serialization.SerialName("predicted_at") val predictedAt: Long,
        val fulfilled: Boolean = false
    )

    @Serializable
    data class PredictionInput(
        val deviceId: String,
        val playerName: String,
        val targetClub: String
    )

    @Serializable
    private data class PredictionInsertBody(
        @kotlinx.serialization.SerialName("device_id") val deviceId: String,
        @kotlinx.serialization.SerialName("player_name") val playerName: String,
        @kotlinx.serialization.SerialName("target_club") val targetClub: String,
        @kotlinx.serialization.SerialName("predicted_at") val predictedAt: Long,
        val fulfilled: Boolean = false
    )

    // 🛡️ YENİ: bir cihazın (deviceId) BUGÜN kaç kehanet yaptığını sayıyor —
    // spam/kötüye kullanımı engellemek için (İstanbul saatine göre "bugün").
    suspend fun countPredictionsToday(deviceId: String): Int {
        if (supabaseUrl == null || supabaseKey == null) return 0
        val cleanDeviceId = deviceId.trim()
        if (cleanDeviceId.isBlank()) return 0
        return try {
            val todayStartMs = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Istanbul"))
                .atStartOfDay(java.time.ZoneId.of("Europe/Istanbul"))
                .toInstant()
                .toEpochMilli()
            val response = httpClient.get("$supabaseUrl/rest/v1/transfer_predictions") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                header("Prefer", "count=exact")
                header("Range", "0-0")
                parameter("select", "id")
                parameter("device_id", "eq.$cleanDeviceId")
                parameter("predicted_at", "gte.$todayStartMs")
            }
            val contentRange = response.headers["Content-Range"]
            contentRange?.substringAfter("/")?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            println("🔥 countPredictionsToday HATASI: ${e.message}")
            0
        }
    }

    suspend fun savePrediction(deviceId: String, playerName: String, targetClub: String): Prediction? {
        if (supabaseUrl == null || supabaseKey == null) {
            println("⚠️ SUPABASE_URL/SUPABASE_KEY tanımlı değil, kehanet kaydedilemedi.")
            return null
        }
        val cleanDeviceId = deviceId.trim()
        val cleanPlayer = playerName.trim()
        val cleanClub = targetClub.trim()
        // 🛡️ Temel doğrulama — boş/aşırı uzun veri kaydetmiyoruz.
        if (cleanDeviceId.isBlank() || cleanPlayer.isBlank() || cleanClub.isBlank()) return null
        if (cleanPlayer.length > 120 || cleanClub.length > 120) return null

        return try {
            val now = System.currentTimeMillis()
            val body = PredictionInsertBody(cleanDeviceId, cleanPlayer, cleanClub, now, false)
            val response = httpClient.post("$supabaseUrl/rest/v1/transfer_predictions") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(Json.encodeToString(body))
            }
            if (!response.status.isSuccess()) {
                println("🔥 savePrediction HATASI: HTTP ${response.status} — ${response.bodyAsText().take(300)}")
                return null
            }
            val resultList = Json.decodeFromString<List<Prediction>>(response.bodyAsText())
            resultList.firstOrNull()
        } catch (e: Exception) {
            println("🔥 savePrediction HATASI: ${e.message}")
            null
        }
    }

    suspend fun fetchPredictionsForDevice(deviceId: String): List<Prediction> {
        if (supabaseUrl == null || supabaseKey == null) return emptyList()
        if (deviceId.isBlank()) return emptyList()
        return try {
            val response = httpClient.get("$supabaseUrl/rest/v1/transfer_predictions") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                parameter("device_id", "eq.${deviceId.trim()}")
                parameter("order", "predicted_at.desc")
                // 🎯 DÜZELTME: eskiden 50'ydi — günlük 3 limitiyle bir kullanıcı
                // 90 günde 270 kehanete ulaşabiliyor, hepsini ana sayfa
                // kartında listelemek kartı aşırı uzatırdı. Sadece SON 10'u
                // gösteriyoruz (bu fonksiyon SADECE ana sayfa kartı için
                // kullanılıyor, /kehanetler sayfası ayrı fonksiyonlar kullanıyor).
                parameter("limit", "10")
            }
            if (!response.status.isSuccess()) {
                println("🔥 fetchPredictionsForDevice HATASI: HTTP ${response.status}")
                return emptyList()
            }
            Json.decodeFromString<List<Prediction>>(response.bodyAsText())
        } catch (e: Exception) {
            println("🔥 fetchPredictionsForDevice HATASI: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchTotalPredictionCount(): Long {
        if (supabaseUrl == null || supabaseKey == null) return 0L
        return try {
            // 💡 PostgREST'in "exact count" özelliği: Range: 0-0 ile sadece 0
            // satır isteyip, Content-Range header'ından toplam sayıyı okuyoruz
            // — böylece tüm satırları çekmeden ucuz bir şekilde sayabiliyoruz.
            val response = httpClient.get("$supabaseUrl/rest/v1/transfer_predictions") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                header("Prefer", "count=exact")
                header("Range", "0-0")
                parameter("select", "id")
            }
            val contentRange = response.headers["Content-Range"] // örn: "0-0/1234"
            contentRange?.substringAfter("/")?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            println("🔥 fetchTotalPredictionCount HATASI: ${e.message}")
            0L
        }
    }

    // 🔮 YENİ: /kehanetler istatistik sayfası için — en son yapılan kehanetler
    // (kimin yaptığı belirtilmeden, herkese açık genel akış).
    suspend fun fetchRecentPredictions(limit: Int = 30): List<Prediction> {
        if (supabaseUrl == null || supabaseKey == null) return emptyList()
        return try {
            val response = httpClient.get("$supabaseUrl/rest/v1/transfer_predictions") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                parameter("order", "predicted_at.desc")
                parameter("limit", limit.toString())
            }
            if (!response.status.isSuccess()) return emptyList()
            Json.decodeFromString<List<Prediction>>(response.bodyAsText())
        } catch (e: Exception) {
            println("🔥 fetchRecentPredictions HATASI: ${e.message}")
            emptyList()
        }
    }

    // ✅ YENİ: gerçekleştiği doğrulanan (fulfilled=true) kehanetler. Bu alan
    // şu an SADECE elle (Supabase Table Editor'den bir satırın "fulfilled"
    // kutusu işaretlenerek) güncelleniyor — otomatik bir doğrulama sistemi yok.
    suspend fun fetchFulfilledPredictions(limit: Int = 30): List<Prediction> {
        if (supabaseUrl == null || supabaseKey == null) return emptyList()
        return try {
            val response = httpClient.get("$supabaseUrl/rest/v1/transfer_predictions") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                parameter("fulfilled", "eq.true")
                parameter("order", "predicted_at.desc")
                parameter("limit", limit.toString())
            }
            if (!response.status.isSuccess()) return emptyList()
            Json.decodeFromString<List<Prediction>>(response.bodyAsText())
        } catch (e: Exception) {
            println("🔥 fetchFulfilledPredictions HATASI: ${e.message}")
            emptyList()
        }
    }

    @Serializable
    data class TopPrediction(val playerName: String, val targetClub: String, val count: Int)

    // 📊 YENİ: en çok tekrarlanan oyuncu+takım kombinasyonları. Supabase'in
    // REST API'sinde basit GROUP BY yok (bunun için özel bir SQL view/RPC
    // gerekirdi) — bu yüzden makul bir üst sınırla (en yeni 2000 kehanet)
    // satırları çekip Kotlin tarafında grupluyoruz. Şu ölçekte tamamen
    // yeterli; ileride kehanet sayısı çok büyürse (örn. 100 binler) bir
    // Postgres view'a geçmek gerekebilir.
    // 🎯 BUG DÜZELTMESİ: fetchTopPredictions sadece player_name/target_club
    // çekiyor (performans için), ama önceden bunu TÜM alanları zorunlu olan
    // Prediction sınıfına çevirmeye çalışıyordu — predicted_at JSON'da hiç
    // olmadığı için dönüştürme HER SEFERİNDE sessizce patlıyordu, sonuç hep
    // boş liste dönüyordu. Sadece bu iki alanı içeren ayrı, minimal bir sınıf
    // kullanıyoruz.
    @Serializable
    private data class PredictionNameOnly(
        @kotlinx.serialization.SerialName("player_name") val playerName: String,
        @kotlinx.serialization.SerialName("target_club") val targetClub: String
    )

    suspend fun fetchTopPredictions(limit: Int = 15): List<TopPrediction> {
        if (supabaseUrl == null || supabaseKey == null) return emptyList()
        return try {
            val response = httpClient.get("$supabaseUrl/rest/v1/transfer_predictions") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                parameter("select", "player_name,target_club")
                parameter("order", "predicted_at.desc")
                parameter("limit", "2000")
            }
            if (!response.status.isSuccess()) return emptyList()
            val rows = Json.decodeFromString<List<PredictionNameOnly>>(response.bodyAsText())
            rows.groupBy { it.playerName.trim().lowercase() to it.targetClub.trim().lowercase() }
                .map { (_, group) ->
                    TopPrediction(group.first().playerName, group.first().targetClub, group.size)
                }
                .filter { it.count >= 5 } // 🎯 YENİ: en az 5 kişi tahmin etmedikçe listeye girmiyor
                .sortedByDescending { it.count }
                .take(limit)
        } catch (e: Exception) {
            println("🔥 fetchTopPredictions HATASI: ${e.message}")
            emptyList()
        }
    }
}
