import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

// 💡 "Oyuncu Modu" için yeni, izole veri sınıfları — mevcut Player sınıfına ya da
// başka bir endpoint'e hiç dokunmuyor, tamamen ek/ayrı bir yapı.
@Serializable
data class ClubSeason(val club: String, val season: String)

@Serializable
data class MultiClubPlayerResult(
    val playerName: String,
    val position: String,
    val clubs: List<ClubSeason>,
    val imageUrl: String? = null // 💡 YENİ: Cevap açıldıktan sonra göstermek için
)

// 🏆 Supabase'e YAZILAN skor kaydı — Postgres sütun adı `score` (tek sütun, tek satır).
// ⚠️ ÖNEMLİ: id'ye varsayılan değer VERİLMEMELİ — kotlinx.serialization, değeri
// varsayılanla aynıysa alanı JSON'a hiç eklemiyor, bu da Supabase'e "id" alanı
// olmadan giden bir istek gönderip "null value in column id" hatasına yol açıyordu.
@Serializable
data class SupabaseHighScoreInsert(val id: Int, val score: Int)

object DatabaseClient {

    private val countryMap = mapOf(
        "ingiltere" to "england", "england" to "england", "birlesik krallik" to "england", "uk" to "england",
        "turkiye" to "turkey", "türkiye" to "turkey", "turkey" to "turkey",
        "almanya" to "germany", "germany" to "germany",
        "fransa" to "france", "france" to "france",
        "ispanya" to "spain", "spain" to "spain",
        "italya" to "italy", "italy" to "italy",
        "hollanda" to "netherlands", "netherlands" to "netherlands", "nederland" to "netherlands",
        "portekiz" to "portugal", "portugal" to "portugal",
        "polonya" to "poland", "poland" to "poland",
        "rusya" to "russia", "russia" to "russia",
        "belcika" to "belgium", "belçika" to "belgium", "belgium" to "belgium",
        "isvicre" to "switzerland", "isviçre" to "switzerland", "switzerland" to "switzerland",
        "isvec" to "sweden", "isveç" to "sweden", "sweden" to "sweden",
        "norvec" to "norway", "norveç" to "norway", "norway" to "norway",
        "danimarka" to "danmark", "denmark" to "denmark",
        "yunanistan" to "greece", "greece" to "greece",
        "hirvatistan" to "croatia", "hırvatistan" to "croatia", "croatia" to "croatia",
        "sirbistan" to "serbia", "sırbistan" to "serbia", "serbia" to "serbia",
        "romanya" to "romania", "romania" to "romania",
        "ukrayna" to "ukraine", "ukraine" to "ukraine",
        "avusturya" to "austria", "austria" to "austria",

        "cek" to "czech republic", "çek" to "czech republic",
        "cekya" to "czech republic", "çekya" to "czech republic",
        "cek cumhuriyeti" to "czech republic", "çek cumhuriyeti" to "czech republic",
        "czech republic" to "czech republic", "czechia" to "czech republic",
        "cekoslovakya" to "czechoslovakia", "çekoslovakya" to "czechoslovakia",

        "bosna hersek" to "bosnia-herzegovina", "bosna" to "bosnia-herzegovina",
        "kanada" to "canada", "amerika" to "united states", "abd" to "united states", "united states" to "united states",
        "brezilya" to "brazil", "brazil" to "brazil",
        "arjantin" to "argentina", "argentina" to "argentina",
        "kolombiya" to "colombia", "colombia" to "colombia",
        "uruguay" to "uruguay",
        "meksika" to "mexico", "mexico" to "mexico",
        "sili" to "chile", "şili" to "chile", "chile" to "chile",
        "paraguay" to "paraguay",
        "peru" to "peru",
        "ekvador" to "ecuador", "ecuador" to "ecuador",
        "fildisi sahili" to "cote d'ivoire", "fildişi sahili" to "cote d'ivoire", "fildişi" to "cote d'ivoire",
        "nijerya" to "nigeria", "nigeria" to "nigeria",
        "kamerun" to "cameroon", "cameroon" to "cameroon",
        "senegal" to "senegal",
        "fas" to "morocco", "morocco" to "morocco",
        "cezayir" to "algeria", "algeria" to "algeria",
        "gana" to "ghana", "ghana" to "ghana",
        "mısır" to "egypt", "misir" to "egypt", "egypt" to "egypt",
        "guney afrika" to "south africa", "güney afrika" to "south africa",
        "demokratik kongo" to "dr congo", "kongo" to "congo",
        "japonya" to "japan", "japan" to "japan",
        "guney kore" to "korea, south", "güney kore" to "korea, south", "kore" to "korea, south",
        "cin" to "china", "çin" to "china", "china" to "china",
        "iran" to "iran",
        "avustralya" to "australia", "australia" to "australia",
        "suudi arabistan" to "saudi arabia",
        "katar" to "qatar", "qatar" to "qatar",
        "ozbekistan" to "uzbekistan",

        // 💡 Aşağıdakiler ek olarak eklendi, mevcut hiçbir eşleme değiştirilmedi
        "iskocya" to "scotland", "scotland" to "scotland",
        "galler" to "wales", "wales" to "wales",
        "irlanda" to "ireland", "ireland" to "ireland", "republic of ireland" to "ireland",
        "kuzey irlanda" to "northern ireland", "northern ireland" to "northern ireland",
        "macaristan" to "hungary", "hungary" to "hungary",
        "slovakya" to "slovakia", "slovakia" to "slovakia",
        "slovenya" to "slovenia", "slovenia" to "slovenia",
        "bulgaristan" to "bulgaria", "bulgaria" to "bulgaria",
        "finlandiya" to "finland", "finland" to "finland",
        "izlanda" to "iceland", "iceland" to "iceland",
        "israil" to "israel", "israel" to "israel",
        "arnavutluk" to "albania", "albania" to "albania",
        "karadag" to "montenegro", "montenegro" to "montenegro",
        "kuzey makedonya" to "north macedonia", "north macedonia" to "north macedonia", "makedonya" to "north macedonia",
        "kosova" to "kosovo", "kosovo" to "kosovo",
        "gurcistan" to "georgia", "georgia" to "georgia",
        "ermenistan" to "armenia", "armenia" to "armenia",
        "azerbaycan" to "azerbaijan", "azerbaijan" to "azerbaijan",
        "litvanya" to "lithuania", "lithuania" to "lithuania",
        "letonya" to "latvia", "latvia" to "latvia",
        "estonya" to "estonia", "estonia" to "estonia",
        "kibris" to "cyprus", "kıbrıs" to "cyprus", "cyprus" to "cyprus",
        "malta" to "malta",
        "luksemburg" to "luxembourg", "lüksemburg" to "luxembourg", "luxembourg" to "luxembourg",
        "hindistan" to "india", "india" to "india",
        "endonezya" to "indonesia", "indonesia" to "indonesia",
        "tayland" to "thailand", "thailand" to "thailand",
        "vietnam" to "vietnam",
        "yeni zelanda" to "new zealand", "new zealand" to "new zealand",
        "tunus" to "tunisia", "tunisia" to "tunisia",
        "mali" to "mali",
        "gine" to "guinea", "guinea" to "guinea"
    )

    // 💡 Bazı kulüpler veritabanında tam isimle değil, kısaltmayla kayıtlı
    // (örn. "Manchester United" değil "Man Utd" olarak geçiyor). Bu sözlük,
    // kullanıcı tam ismi yazınca aramayı veritabanındaki gerçek kısaltmaya
    // çeviriyor. Sadece bilinen istisnalar için, diğer kulüplere dokunmuyor.
    private val clubAliasMap = mapOf(
        "manchester united" to "man utd",
        "manchester utd" to "man utd",
        "man united" to "man utd",
        "manchester city" to "man city",
        "borussia dortmund" to "bor. dortmund"
    )

    // Arama teriminin standartlaştırılmış + (varsa) alias'ı çözülmüş hâlini döndürür.
    // SQL sorgusuna ve Kotlin tarafındaki matchesOriginalClub kontrolüne aynı terim
    // gönderilsin diye tek bir yerden hesaplanıyor.
    private fun resolveClubSearchTerm(raw: String): String {
        val std = raw.toStandardSearch()
        return clubAliasMap[std] ?: std
    }

    // 💡 EŞZAMANLI GÜVENLİK: Tek paylaşılan connection yerine küçük bir havuz (pool)
    // kullanıyoruz. SQLite JDBC sürücüsü, tek bir Connection nesnesinin birden fazla
    // thread tarafından TAM OLARAK AYNI ANDA kullanılmasını güvenli desteklemiyor.
    // Bu havuz sayesinde her istek kendi bağlantısını alıp işini bitirince geri
    // bırakıyor; havuz o an boşsa istek kısa bir süre bekliyor (hata almak yerine).
    // Ayrıca WAL modu, eşzamanlı okumaları çok daha güvenli ve hızlı hale getiriyor.
    private const val POOL_SIZE = 6
    private val connectionPool: java.util.concurrent.BlockingQueue<Connection> by lazy { createConnectionPool() }

    // 🏆 Bil Bakalım global rekoru — artık SQLite'ta DEĞİL, Supabase'in ücretsiz
    // Postgres'inde tutuluyor (REST API üzerinden). Render'ın free tier'ı kalıcı
    // disk sunmadığı için SQLite'taki rekor her uyku/deploy/restart sonrası
    // sıfırlanıyordu — Supabase gerçekten kalıcı, ücretsiz bir dış servis.
    // NetKalan'daki quiz_records liderlik tablosuyla AYNI kanıtlanmış yöntem,
    // burada sadece tek bir sayı (leaderboard değil) tutuluyor.
    private val supabaseUrl = System.getenv("SUPABASE_URL")?.trimEnd('/')
    private val supabaseKey = System.getenv("SUPABASE_KEY")

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    // 🏆 4 ayrı Bil Bakalım modu, 4 ayrı rekor — Supabase'teki quiz_highscore
    // tablosunda id=1..4, her biri farklı bir moda karşılık geliyor.
    private val quizModeIds = mapOf(
        "genel" to 1,
        "turkiye" to 2,
        "nba" to 3,
        "eurobasket" to 4
    )
    private fun defaultScoreForMode(modeId: Int): Int = if (modeId == 1) 21 else 10

    fun fetchQuizHighScore(mode: String): Int = kotlinx.coroutines.runBlocking { fetchQuizHighScoreSuspend(quizModeIds[mode] ?: 1) }

    fun submitQuizScore(mode: String, score: Int): Int = kotlinx.coroutines.runBlocking { submitQuizScoreSuspend(quizModeIds[mode] ?: 1, score) }

    private suspend fun fetchQuizHighScoreSuspend(modeId: Int): Int {
        val fallback = defaultScoreForMode(modeId)
        if (supabaseUrl == null || supabaseKey == null) {
            println("⚠️ SUPABASE_URL / SUPABASE_KEY ayarlanmamış, rekor devre dışı (varsayılan $fallback dönüyor).")
            return fallback
        }
        return try {
            val response: HttpResponse = httpClient.get("$supabaseUrl/rest/v1/quiz_highscore") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                parameter("select", "score")
                parameter("id", "eq.$modeId")
                parameter("limit", "1")
            }
            val body = response.bodyAsText()
            val rows = Json.parseToJsonElement(body).jsonArray
            if (rows.isEmpty()) fallback else (rows[0].jsonObject["score"]?.jsonPrimitive?.int ?: fallback)
        } catch (e: Exception) {
            println("🔥 Supabase rekor okuma hatası: ${e.message}")
            fallback
        }
    }

    // 📈 Skoru gönderir, mevcut rekordan büyükse günceller — her durumda sonuçtaki
    // (güncel) rekoru döndürür. Supabase'te "upsert" (varsa güncelle, yoksa oluştur)
    // için Prefer: resolution=merge-duplicates header'ı kullanılıyor.
    private suspend fun submitQuizScoreSuspend(modeId: Int, score: Int): Int {
        val current = fetchQuizHighScoreSuspend(modeId)
        if (supabaseUrl == null || supabaseKey == null) {
            return maxOf(current, score)
        }
        if (score <= current) {
            return current
        }
        return try {
            val response = httpClient.post("$supabaseUrl/rest/v1/quiz_highscore") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                header("Content-Type", "application/json")
                header("Prefer", "resolution=merge-duplicates")
                setBody(Json.encodeToString(SupabaseHighScoreInsert(modeId, score)))
            }
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                println("🔥 Supabase rekor yazma hatası: HTTP ${response.status} — $errorBody")
                return current
            }
            score
        } catch (e: Exception) {
            println("🔥 Supabase rekor yazma hatası: ${e.message}")
            current
        }
    }

    private fun createConnectionPool(): java.util.concurrent.BlockingQueue<Connection> {
        val pool: java.util.concurrent.BlockingQueue<Connection> =
            java.util.concurrent.ArrayBlockingQueue(POOL_SIZE)
        repeat(POOL_SIZE) {
            pool.put(createConnection())
        }
        return pool
    }

    // 🏀 Basketbol — football.db'den TAMAMEN AYRI, ikinci bir veritabanı
    // (basketball.db). EuroLeague + EuroCup verisi, "oyuncu-sezon-takım"
    // şeklinde. Mevki/uyruk verisi YOK (kaynakta bulunmuyor) — sadece
    // "iki takımda da oynayan oyuncu" sorgusu için kullanılıyor.
    private const val BB_POOL_SIZE = 6
    private val bbConnectionPool: java.util.concurrent.BlockingQueue<Connection> by lazy { createBbConnectionPool() }

    private fun createBbConnectionPool(): java.util.concurrent.BlockingQueue<Connection> {
        val pool: java.util.concurrent.BlockingQueue<Connection> =
            java.util.concurrent.ArrayBlockingQueue(BB_POOL_SIZE)
        repeat(BB_POOL_SIZE) {
            pool.put(createBbConnection())
        }
        return pool
    }

    private fun <T> withBbConnection(block: (Connection) -> T): T {
        val conn = bbConnectionPool.take()
        try {
            return block(conn)
        } finally {
            bbConnectionPool.put(conn)
        }
    }

    private fun createBbConnection(): Connection {
        val dbFile = File("basketball.db")
        if (!dbFile.exists()) {
            throw IllegalStateException(
                "❌ basketball.db bulunamadı: ${dbFile.absolutePath}. " +
                        "Local çalıştırıyorsanız dosyayı proje kök dizinine kopyalayın."
            )
        }
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA busy_timeout=5000;")
        }
        return conn
    }

    // 🏀 Öneri (autocomplete) listesi — benzersiz takım isimleri.
    fun fetchAllBasketballSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()
        try {
            withBbConnection { conn ->
                // ⚡ YENİDEN YAZILDI: eski sürüm her satır için 5 ayrı iç içe (correlated)
                // EXISTS sorgusu çalıştırıyordu (indekssiz team_name üzerinden) — 14.836
                // satırda bu ~19 SANİYE sürüyordu! Yeni sürüm TEK GEÇİŞTE (GROUP BY +
                // HAVING) aynı sonucu veriyor, milisaniyeler içinde.
                val sql = """
                    SELECT team_name FROM (
                        SELECT team_name, substr(season_code, -4) as yr
                        FROM bb_players
                        WHERE substr(season_code, -4) IN ('2021','2022','2023','2024','2025')
                        GROUP BY team_name, yr
                    )
                    GROUP BY team_name
                    HAVING COUNT(*) = 5
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("team_name")
                            if (!name.isNullOrBlank() && !isYouthClub(name)) {
                                suggestions.add(name)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchAllBasketballSuggestions HATASI: ${e.message}")
        }
        return suggestions.sorted()
    }

    @Serializable
    data class BasketballPlayerResult(
        val name: String,
        val team1Season: String?,
        val team2Season: String?,
        val competition: String,
        val nbaOfficialId: String? = null // 💡 varsa, NBA'in kendi resmi foto CDN'inde kullanılıyor
    )

    // 🏀 NBA — Avrupa basketbolundan (EuroLeague/EuroCup) TAMAMEN AYRI bir havuz,
    // aynı basketball.db içinde ama farklı tablo (nba_players). 1947-2026 arası,
    // BAA/NBA/ABA liglerini kapsıyor. Rastgele eşleşmede Avrupa ile karışmasın diye
    // ayrı endpoint'ler kullanılıyor.
    fun fetchAllNbaSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()
        try {
            withBbConnection { conn ->
                // 💡 team_name = team_abbr olanlar, tam ismini ÇÖZEMEDİĞİMİZ eski/
                // lağvedilmiş takımlar (örn. "SDC", "NOH" gibi ham kısaltmalar) —
                // bunları öneri/rastgele havuzundan çıkarıyoruz, çirkin/anlamsız
                // görünüyorlardı. Gerçek verileri hâlâ duruyor, sadece önerilmiyorlar.
                conn.prepareStatement(
                    "SELECT DISTINCT team_name FROM nba_players WHERE team_name != team_abbr"
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("team_name")
                            if (!name.isNullOrBlank()) suggestions.add(name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchAllNbaSuggestions HATASI: ${e.message}")
        }
        return suggestions.sorted()
    }

    fun fetchCommonNbaPlayers(team1: String, team2: String): List<BasketballPlayerResult> {
        val startTime = System.currentTimeMillis()
        val std1 = team1.toStandardSearch()
        val std2 = team2.toStandardSearch()

        val sql = """
            SELECT p1.player_id, p1.name,
                   MIN(p1.season) as season1, MIN(p2.season) as season2, p1.league,
                   MAX(p1.nba_official_id) as nba_official_id
            FROM nba_players p1
            JOIN nba_players p2 ON p1.player_id = p2.player_id
            WHERE (p1.team_name_std LIKE ? OR p1.team_abbr_std LIKE ?)
              AND (p2.team_name_std LIKE ? OR p2.team_abbr_std LIKE ?)
            GROUP BY p1.player_id, p1.name
        """.trimIndent()

        val results = mutableListOf<BasketballPlayerResult>()
        try {
            withBbConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "%$std1%")
                    stmt.setString(2, "%$std1%")
                    stmt.setString(3, "%$std2%")
                    stmt.setString(4, "%$std2%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                BasketballPlayerResult(
                                    name = rs.getString("name"),
                                    team1Season = rs.getString("season1"),
                                    team2Season = rs.getString("season2"),
                                    competition = rs.getString("league"),
                                    nbaOfficialId = rs.getString("nba_official_id")
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchCommonNbaPlayers HATASI: ${e.message}")
        }
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 1000) {
            println("⏱️ fetchCommonNbaPlayers YAVAŞ: ${elapsed}ms ($team1 vs $team2, ${results.size} sonuç)")
        }
        return results.sortedByDescending { it.team1Season?.toIntOrNull() ?: 0 }
    }

    // 🖼️ Basketbol logo/foto — TARAYICIDAN DEĞİL, SUNUCUDAN TheSportsDB'ye
    // istek atıyoruz. Tarayıcıdan atılan istekler CORS'a takılıyordu (TheSportsDB'nin
    // ücretsiz anahtarı bunu desteklemiyor gibi görünüyor); sunucudan sunucuya
    // istekte CORS diye bir kavram yok, bu yüzden garanti çalışıyor.
    // 🛡️ Sunucu tarafında, TÜM kullanıcılar için PAYLAŞILAN önbellek — bir takım
    // bir kez bulunduktan sonra bir daha ASLA TheSportsDB'ye sorulmuyor. Ücretsiz
    // anahtarın paylaşılan hız sınırını (rate limit) korumak için kritik.
    private val basketballLogoCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val basketballPhotoCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // 🚀 Uygulama açılışında, TÜM basketbol takımlarının (Avrupa + NBA, toplam
    // ~50-55 takım) logolarını ÖNCEDEN çekip önbelleğe alıyoruz — futbolun
    // stadyum fotoğrafı ön-yükleme mantığıyla aynı. Böylece hiçbir kullanıcı
    // "önce boş, sonra doluyor" gecikmesi yaşamıyor, her şey baştan hazır.
    suspend fun preloadAllBasketballLogos() {
        try {
            val europeTeams = fetchAllBasketballSuggestions()
            val nbaTeams = fetchAllNbaSuggestions()
            val allTeams = (europeTeams + nbaTeams).distinct()

            // 🚀 KRİTİK OPTİMİZASYON: Eğer zaten HEPSİ (ya da neredeyse hepsi)
            // kalıcı veritabanında kayıtlıysa, döngüye HİÇ girmiyoruz — tek bir
            // hızlı COUNT sorgusu yeterli. Böylece sunucu açılışında (soğuk
            // başlangıçta) bağlantı havuzu boşuna meşgul edilmiyor, kullanıcının
            // İLK isteği (Bil Bakalım vb.) hiç beklemeden anında işlenebiliyor.
            val alreadyCachedCount = withBbConnection { conn ->
                var count = 0
                conn.prepareStatement("SELECT COUNT(*) as cnt FROM bb_team_logos").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) count = rs.getInt("cnt")
                    }
                }
                count
            }
            if (alreadyCachedCount >= allTeams.size) {
                println("🏀 Logolar zaten kalıcı veritabanında ($alreadyCachedCount/${allTeams.size}) — ön-yükleme atlandı, sunucu anında hazır.")
                return
            }

            println("🏀 ${allTeams.size} takımın logosu önceden yükleniyor...")
            var found = 0
            for (team in allTeams) {
                val callStart = System.currentTimeMillis()
                val logo = fetchBasketballTeamLogo(team)
                if (logo != null) found++
                val callElapsed = System.currentTimeMillis() - callStart
                // 💡 100ms'den hızlıysa muhtemelen veritabanından geldi (ağa hiç
                // gidilmedi) — bu durumda BEKLEMEYE GEREK YOK. Sadece gerçekten
                // TheSportsDB'ye gidilen (yavaş) durumlarda aralık koyuyoruz.
                if (callElapsed > 100) {
                    kotlinx.coroutines.delay(600) // 💡 hız sınırını zorlamayalım diye aralıklı
                }
            }
            println("🏀 Logo ön-yükleme tamamlandı: $found / ${allTeams.size} bulundu.")
        } catch (e: Exception) {
            println("🔥 preloadAllBasketballLogos HATASI: ${e.message}")
        }
    }

    // ⚽ Futbol kulüp logosu YEDEK sistemi — club_logos tablosunda olmayan
    // kulüpler için. Basketboldan TAMAMEN AYRI önbellek (aynı isimli kulüpler
    // olabilir, örn. "Barcelona" hem futbolda hem basketbolda var — karışmasın).
    private val footballLogoFallbackCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val footballLogoFallbackFailedRecently = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun fetchFootballTeamLogoFallback(teamName: String): String? {
        val cacheKey = teamName.trim().lowercase()
        footballLogoFallbackCache[cacheKey]?.let { return it }
        // 🛡️ Başarısız bir denemeyi de KISA SÜRELİĞİNE önbelleğe alıyoruz — TheSportsDB
        // şu an ulaşılamıyor/yavaşsa, AYNI kulüp için art arda gelen istekler (birden
        // fazla kullanıcı aynı anda arama yapınca) her biri ayrı ayrı 15sn beklemesin.
        if (footballLogoFallbackFailedRecently.contains(cacheKey)) return null

        try {
            val response = httpClient.get("https://www.thesportsdb.com/api/v1/json/123/searchteams.php") {
                parameter("t", teamName)
                timeout { requestTimeoutMillis = 3_000 } // 💡 15sn yerine sadece 3sn — sunucuyu tıkamasın
            }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val root = Json.parseToJsonElement(body).jsonObject
                val teamsElement = root["teams"]
                if (teamsElement != null && teamsElement !is JsonNull) {
                    val teams = teamsElement.jsonArray
                    // 💡 Futbolda "Soccer" sporunu tercih ediyoruz ama bulunamazsa
                    // (bazı kayıtlarda sport alanı boş/farklı olabiliyor) ilk sonucu kullanıyoruz.
                    val footballTeam = teams.map { it.jsonObject }.firstOrNull {
                        (it["strSport"]?.jsonPrimitive?.contentOrNull ?: "").equals("Soccer", ignoreCase = true)
                    } ?: teams.firstOrNull()?.jsonObject
                    val badge = footballTeam?.get("strTeamBadge")?.jsonPrimitive?.contentOrNull
                        ?: footballTeam?.get("strBadge")?.jsonPrimitive?.contentOrNull
                    if (!badge.isNullOrBlank()) {
                        footballLogoFallbackCache[cacheKey] = badge
                        return badge
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchFootballTeamLogoFallback hata: ${e.message}")
            footballLogoFallbackFailedRecently.add(cacheKey)
        }
        return null
    }

    suspend fun fetchBasketballTeamLogo(teamName: String): String? {
        val cacheKey = teamName.trim().lowercase()
        basketballLogoCache[cacheKey]?.let { return it }

        // 🗄️ Kalıcı veritabanı kontrolü — RAM önbelleği sunucu her uyandığında
        // sıfırlanıyordu (Render'ın ücretsiz katmanı uykuya dalıp uyanıyor),
        // ama bu tablo diskte duruyor, hiç kaybolmuyor.
        try {
            val cachedUrl = withBbConnection { conn ->
                var result: String? = null
                conn.prepareStatement("SELECT logo_url FROM bb_team_logos WHERE team_name_std = ?").use { stmt ->
                    stmt.setString(1, cacheKey)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            result = rs.getString("logo_url")
                        }
                    }
                }
                result
            }
            if (cachedUrl != null) {
                basketballLogoCache[cacheKey] = cachedUrl
                return cachedUrl
            }
        } catch (e: Exception) {
            println("🔥 bb_team_logos okuma hatası: ${e.message}")
        }

        // 💡 Artık sonuç KALICI olarak veritabanına yazılıyor — bir takım için
        // fazladan deneme yapmanın maliyeti sadece BİR KEZ ödeniyor (o takım bir
        // daha hiç aranmıyor). Bu yüzden daha kapsamlı deneyebiliriz: hem orijinal
        // hem Title Case, her ikisinde de kelime kelime kırparak (sponsor/şehir
        // isimleri genelde sonda oluyor).
        val titleCased = teamName.trim().split(Regex("\\s+")).joinToString(" ") {
            it.lowercase().replaceFirstChar { c -> c.uppercase() }
        }
        val nameVariants = listOf(teamName.trim(), titleCased).distinct()
        val attempts = mutableListOf<String>()
        for (variant in nameVariants) {
            val words = variant.split(Regex("\\s+"))
            var i = words.size
            while (i >= 1) {
                attempts.add(words.subList(0, i).joinToString(" "))
                i--
            }
        }

        for (attempt in attempts.distinct()) {
            kotlinx.coroutines.delay(150) // 💡 aynı takım içindeki denemeler arasında da küçük bir ara
            try {
                val response = httpClient.get("https://www.thesportsdb.com/api/v1/json/123/searchteams.php") {
                    parameter("t", attempt)
                }
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val root = Json.parseToJsonElement(body).jsonObject
                    val teamsElement = root["teams"]
                    if (teamsElement != null && teamsElement !is JsonNull) {
                        val teams = teamsElement.jsonArray
                        val basketballTeam = teams.map { it.jsonObject }.firstOrNull {
                            (it["strSport"]?.jsonPrimitive?.contentOrNull ?: "").equals("Basketball", ignoreCase = true)
                        }
                        val badge = basketballTeam?.get("strTeamBadge")?.jsonPrimitive?.contentOrNull
                            ?: basketballTeam?.get("strBadge")?.jsonPrimitive?.contentOrNull
                        if (!badge.isNullOrBlank()) {
                            basketballLogoCache[cacheKey] = badge
                            try {
                                withBbConnection { conn ->
                                    conn.prepareStatement("INSERT OR REPLACE INTO bb_team_logos (team_name_std, logo_url) VALUES (?, ?)").use { stmt ->
                                        stmt.setString(1, cacheKey)
                                        stmt.setString(2, badge)
                                        stmt.executeUpdate()
                                    }
                                }
                            } catch (e: Exception) {
                                println("🔥 bb_team_logos yazma hatası: ${e.message}")
                            }
                            return badge
                        }
                    }
                }
            } catch (e: Exception) {
                println("🔥 fetchBasketballTeamLogo hata ($attempt): ${e.message}")
            }
        }
        return null
    }

    suspend fun fetchBasketballPlayerPhoto(playerName: String): String? {
        val cacheKey = playerName.trim().lowercase()
        basketballPhotoCache[cacheKey]?.let { return it }

        try {
            val cachedUrl = withBbConnection { conn ->
                var result: String? = null
                conn.prepareStatement("SELECT photo_url FROM bb_player_photos WHERE player_name_std = ?").use { stmt ->
                    stmt.setString(1, cacheKey)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            result = rs.getString("photo_url")
                        }
                    }
                }
                result
            }
            if (cachedUrl != null) {
                basketballPhotoCache[cacheKey] = cachedUrl
                return cachedUrl
            }
        } catch (e: Exception) {
            println("🔥 bb_player_photos okuma hatası: ${e.message}")
        }

        // 💡 TheSportsDB'nin dokümantasyonu isimleri ALT ÇİZGİLİ gösteriyor
        // (örn. "Danny_Welbeck") — normal boşluklu format tam eşleşmeyebiliyordu.
        // İkisini de deniyoruz.
        val nameVariants = listOf(playerName.trim(), playerName.trim().replace(Regex("\\s+"), "_")).distinct()
        for (variant in nameVariants) {
            try {
                val response = httpClient.get("https://www.thesportsdb.com/api/v1/json/123/searchplayers.php") {
                    parameter("p", variant)
                }
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val root = Json.parseToJsonElement(body).jsonObject
                    val playersElement = root["player"]
                    if (playersElement != null && playersElement !is JsonNull) {
                        val first = playersElement.jsonArray.firstOrNull()?.jsonObject
                        val photo = first?.get("strCutout")?.jsonPrimitive?.contentOrNull
                            ?: first?.get("strThumb")?.jsonPrimitive?.contentOrNull
                        if (!photo.isNullOrBlank()) {
                            basketballPhotoCache[cacheKey] = photo
                            try {
                                withBbConnection { conn ->
                                    conn.prepareStatement("INSERT OR REPLACE INTO bb_player_photos (player_name_std, photo_url) VALUES (?, ?)").use { stmt ->
                                        stmt.setString(1, cacheKey)
                                        stmt.setString(2, photo)
                                        stmt.executeUpdate()
                                    }
                                }
                            } catch (e: Exception) {
                                println("🔥 bb_player_photos yazma hatası: ${e.message}")
                            }
                            return photo
                        }
                    }
                }
            } catch (e: Exception) {
                println("🔥 fetchBasketballPlayerPhoto hata ($variant): ${e.message}")
            }
        }
        return null
    }

    // 🏀 İki takımda da oynamış oyuncuları buluyor — futboldaki fetchCommonPlayers
    // ile aynı ruhta, ama basketbolun (oyuncu-sezon-takım) daha basit yapısına uygun.
    fun fetchCommonBasketballPlayers(team1: String, team2: String): List<BasketballPlayerResult> {
        val startTime = System.currentTimeMillis()
        val std1 = team1.toStandardSearch()
        val std2 = team2.toStandardSearch()

        // 🛡️ Sadece son 5 sezonda (2021-2025) aktif olan takımlar aranabilir —
        // Galatasaray gibi yıllardır bu kupalarda olmayan takımlar, elle yazılsa
        // bile sonuç vermiyor (öneri listesinden zaten çıkarılmıştı, burada da
        // aynı kısıtlama uygulanıyor).
        val sql = """
            SELECT p1.player_id, p1.name,
                   MIN(p1.season_code) as season1, MIN(p2.season_code) as season2,
                   GROUP_CONCAT(DISTINCT p1.competition) as competition
            FROM bb_players p1
            JOIN bb_players p2 ON p1.player_id = p2.player_id
            WHERE p1.team_name_std LIKE ? AND p2.team_name_std LIKE ?
              AND EXISTS (SELECT 1 FROM bb_players r1a WHERE r1a.team_name_std = p1.team_name_std AND r1a.season_code LIKE '%2021')
              AND EXISTS (SELECT 1 FROM bb_players r1b WHERE r1b.team_name_std = p1.team_name_std AND r1b.season_code LIKE '%2022')
              AND EXISTS (SELECT 1 FROM bb_players r1c WHERE r1c.team_name_std = p1.team_name_std AND r1c.season_code LIKE '%2023')
              AND EXISTS (SELECT 1 FROM bb_players r1d WHERE r1d.team_name_std = p1.team_name_std AND r1d.season_code LIKE '%2024')
              AND EXISTS (SELECT 1 FROM bb_players r1e WHERE r1e.team_name_std = p1.team_name_std AND r1e.season_code LIKE '%2025')
              AND EXISTS (SELECT 1 FROM bb_players r2a WHERE r2a.team_name_std = p2.team_name_std AND r2a.season_code LIKE '%2021')
              AND EXISTS (SELECT 1 FROM bb_players r2b WHERE r2b.team_name_std = p2.team_name_std AND r2b.season_code LIKE '%2022')
              AND EXISTS (SELECT 1 FROM bb_players r2c WHERE r2c.team_name_std = p2.team_name_std AND r2c.season_code LIKE '%2023')
              AND EXISTS (SELECT 1 FROM bb_players r2d WHERE r2d.team_name_std = p2.team_name_std AND r2d.season_code LIKE '%2024')
              AND EXISTS (SELECT 1 FROM bb_players r2e WHERE r2e.team_name_std = p2.team_name_std AND r2e.season_code LIKE '%2025')
            GROUP BY p1.player_id, p1.name
        """.trimIndent()

        val results = mutableListOf<BasketballPlayerResult>()
        try {
            withBbConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "%$std1%")
                    stmt.setString(2, "%$std2%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                BasketballPlayerResult(
                                    name = rs.getString("name"),
                                    team1Season = rs.getString("season1"),
                                    team2Season = rs.getString("season2"),
                                    competition = rs.getString("competition")
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchCommonBasketballPlayers HATASI: ${e.message}")
        }
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 1000) {
            println("⏱️ fetchCommonBasketballPlayers YAVAŞ: ${elapsed}ms ($team1 vs $team2, ${results.size} sonuç)")
        }
        // 🕰️ Futboldaki gibi güncelden eskiye sıralıyoruz — season_code'daki
        // (örn. "E2024") yıl kısmını sayıya çevirip azalan sıraya diziyoruz.
        return results.sortedByDescending { it.team1Season?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
    }

    // Havuzdan bir bağlantı alıp işi bitince geri bırakan yardımcı fonksiyon.
    // Tüm sorgular artık bunun üzerinden çalışıyor, hiçbiri connection'ı doğrudan paylaşmıyor.
    private fun <T> withConnection(block: (Connection) -> T): T {
        val conn = connectionPool.take()
        try {
            return block(conn)
        } finally {
            connectionPool.put(conn)
        }
    }

    // 💡 Artık runtime'da resources'tan kopyalama YOK. football.db Docker image'ında
    // doğrudan /app/football.db konumunda hazır bulunuyor (bkz. Dockerfile).
    // Local'de çalıştırırken de proje kök dizininde football.db bulunmalı.
    private fun createConnection(): Connection {
        val dbFile = File("football.db")

        if (!dbFile.exists()) {
            throw IllegalStateException(
                "❌ football.db bulunamadı: ${dbFile.absolutePath}. " +
                "Local çalıştırıyorsanız dosyayı proje kök dizinine kopyalayın."
            )
        }

        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

        // 💡 WAL modu: birden fazla kullanıcı aynı anda arama yaparken okumaları
        // güvenli ve hızlı hale getiriyor. busy_timeout: nadir bir çakışma anında
        // hemen hata vermek yerine kısa süre bekleyip tekrar denemesini sağlıyor.
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA busy_timeout=5000;")
        }

        return conn
    }

    private fun String.toStandardSearch(): String {
        // 💡 ÖNEMLİ SIRA: Türkçe karakter dönüşümlerini lowercase()'DEN ÖNCE yapıyoruz.
        // Sebep: Kotlin'in genel .lowercase() fonksiyonu (locale belirtilmeden), Türkçe
        // büyük "İ" harfini tek bir "i" değil, "i" + görünmez bir nokta işareti (iki ayrı
        // karakter) haline çeviriyor. Bu yüzden önce lowercase() çağrılırsa "İnter" gibi
        // aramalar sessizce bozuluyor ve hiçbir sonuçla eşleşmiyordu. Değişimleri önce
        // yapıp en sona sadece düz ASCII harfler için .lowercase() çağırmak bu sorunu çözüyor.
        return this
            .replace("İ", "i").replace("ı", "i")
            .replace("Ğ", "g").replace("ğ", "g")
            .replace("Ü", "u").replace("ü", "u")
            .replace("Ş", "s").replace("ş", "s")
            .replace("Ö", "o").replace("ö", "o")
            .replace("Ç", "c").replace("ç", "c")
            .lowercase()
            .trim()
    }

    private fun isYouthClub(clubName: String?): Boolean {
        if (clubName == null) return false
        val lower = clubName.toStandardSearch()
        val youthKeywords = listOf(
            "u15", "u16", "u17", "u18", "u19", "u20", "u21", "u23",
            "u-15", "u-16", "u-17", "u-18", "u-19", "u-20", "u-21", "u-23",
            "yth", "youth", "academy", "akademi", "reserves", "amateur", "ii",
            "res.", "sva" // 💡 Çin ligi kısaltmaları (örn. "SH Shenhua Res.", "SH Shenhua SVA")
        )
        if (youthKeywords.any { lower.contains(it) }) return true

        // 💡 "Barcelona B", "Real Madrid C" gibi rezerv takım isimleri — sadece
        // isim SONUNDA, tek başına bir " b"/" c" harfi varsa yakalıyoruz (kelime
        // sınırı ile), yoksa "Bilbao" gibi normal isimleri yanlışlıkla eşleştirebilirdi.
        if (Regex("\\s[bc]$").containsMatchIn(lower)) return true

        return false
    }

    private fun matchesOriginalClub(clubName: String?, resolvedTarget: String): Boolean {
        if (clubName == null) return false
        val cleanClub = clubName.toStandardSearch()

        if (isYouthClub(cleanClub)) {
            return false
        }

        return cleanClub.contains(resolvedTarget)
    }

    // 🎯 Tam eşleşme kontrolü — "Inter" ile "Inter Miami" gibi, biri diğerinin
    // içinde geçen ama FARKLI kulüpleri ayırt etmek için kullanılıyor.
    private fun isExactClubMatch(clubName: String?, resolvedTarget: String): Boolean {
        if (clubName == null) return false
        val cleanClub = clubName.toStandardSearch()
        if (isYouthClub(cleanClub)) return false
        return cleanClub == resolvedTarget
    }

    // 💡 Sadece SADECE ilk/ana uyruğu baz alan kusursuz kontrol
    private fun isPrimaryCountryMatch(playerNationality: String, searchParam: String, mappedCountry: String): Boolean {
        val stdNat = playerNationality.toStandardSearch()
        val stdSearch = searchParam.toStandardSearch()
        val stdMapped = mappedCountry.toStandardSearch()

        // Birden fazla uyruk verisi ÇİFT BOŞLUKLA ayrılıyor (örn: "Cameroon  France").
        // Bu yüzden sadece çift boşlukta bölüyoruz; tek boşluklu veya apostroflu çok
        // kelimelik ülke isimleri (örn. "Czech Republic", "Cote d'Ivoire") bu sayede
        // bölünmeden tek parça kalıyor ve doğru şekilde eşleşiyor.
        val primaryNationality = stdNat.split(Regex("\\s{2,}")).firstOrNull()?.trim()
            ?: return false

        return primaryNationality == stdSearch || primaryNationality == stdMapped
    }

    private fun isCountryParam(param: String): Boolean {
        val std = param.toStandardSearch()
        return countryMap.containsKey(std) || countryMap.containsValue(std)
    }

    private fun parseSeasonToSortValue(season: String?): Int {
        if (season.isNullOrBlank() || season == "-") return -1
        val digits = season.filter { it.isDigit() }
        if (digits.length >= 2) {
            val yearPrefix = digits.substring(0, 2).toIntOrNull() ?: 0
            return if (yearPrefix >= 50) 1900 + yearPrefix else 2000 + yearPrefix
        }
        return 0
    }

    // 🖼️ Kulüp logoları — club_logos tablosundan tek seferde tüm eşlemeyi çekiyor
    // 🎲 Bil Bakalım için rastgele soru — TÜM deneme mantığı SUNUCUDA, tek bir
    // istekte. Önceden istemci 8 farklı takım çiftini TEK TEK sunucuya soruyordu
    // (her biri ayrı bir ağ gidiş-gelişi = Render'da ~3-4sn × 8 = 25-30sn!). Artık
    // döngü burada, dahili — ağ gecikmesi sadece BİR KEZ ödeniyor.
    @Serializable
    data class RandomBasketballQuestion(
        val found: Boolean,
        val team1: String? = null,
        val team2: String? = null,
        val playerName: String? = null,
        val team1Season: String? = null,
        val team2Season: String? = null,
        val nbaOfficialId: String? = null
    )

    // ⚡ HAFİF sürüm — pool'dan gelen takımlar zaten 5-sezon filtresinden geçmiş
    // (fetchAllBasketballSuggestions bunu garanti ediyor), bu yüzden o 10 tane
    // EXISTS kontrolünü BURADA TEKRAR yapmıyoruz — gereksiz yük, sorguyu yavaşlatıyordu.
    private fun fetchCommonBasketballPlayersLean(team1: String, team2: String): List<BasketballPlayerResult> {
        val std1 = team1.toStandardSearch()
        val std2 = team2.toStandardSearch()
        val sql = """
            SELECT p1.player_id, p1.name,
                   MIN(p1.season_code) as season1, MIN(p2.season_code) as season2,
                   GROUP_CONCAT(DISTINCT p1.competition) as competition
            FROM bb_players p1
            JOIN bb_players p2 ON p1.player_id = p2.player_id
            WHERE p1.team_name_std LIKE ? AND p2.team_name_std LIKE ?
            GROUP BY p1.player_id, p1.name
        """.trimIndent()
        val results = mutableListOf<BasketballPlayerResult>()
        try {
            withBbConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "%$std1%")
                    stmt.setString(2, "%$std2%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                BasketballPlayerResult(
                                    name = rs.getString("name"),
                                    team1Season = rs.getString("season1"),
                                    team2Season = rs.getString("season2"),
                                    competition = rs.getString("competition")
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchCommonBasketballPlayersLean HATASI: ${e.message}")
        }
        return results
    }

    fun fetchRandomBasketballQuestion(pool: List<String>, isNba: Boolean): RandomBasketballQuestion {
        if (pool.size < 2) return RandomBasketballQuestion(found = false)
        val overallStart = System.currentTimeMillis()
        repeat(15) { attemptNum ->
            val team1 = pool.random()
            var team2: String
            do { team2 = pool.random() } while (team2 == team1)

            val players = if (isNba) fetchCommonNbaPlayers(team1, team2) else fetchCommonBasketballPlayersLean(team1, team2)
            if (players.isNotEmpty()) {
                val player = players.random()
                val totalElapsed = System.currentTimeMillis() - overallStart
                if (totalElapsed > 500 || attemptNum >= 3) {
                    println("⏱️ fetchRandomBasketballQuestion YAVAŞ: ${totalElapsed}ms, ${attemptNum + 1} deneme")
                }
                return RandomBasketballQuestion(
                    found = true,
                    team1 = team1,
                    team2 = team2,
                    playerName = player.name,
                    team1Season = player.team1Season,
                    team2Season = player.team2Season,
                    nbaOfficialId = player.nbaOfficialId
                )
            }
        }
        return RandomBasketballQuestion(found = false)
    }

    // 📰 GÜNÜN OYUNCUSU — AdSense'in "gerçek, günlük değişen içerik" önerisi için.
    // Tanınmış oyunculardan sabit bir havuzdan, TARİHE GÖRE (herkese aynı gün aynı
    // oyuncu) birini seçip, GERÇEK transfer geçmişini veritabanından çekiyoruz —
    // metin frontend'de bu gerçek veriden oluşturuluyor, uydurma değil.
    private val dailyPlayerPool = listOf(
        "Cristiano Ronaldo", "Lionel Messi", "Zlatan Ibrahimovic", "Wesley Sneijder",
        "Didier Drogba", "Samuel Eto'o", "Arjen Robben", "Frank Lampard",
        "Steven Gerrard", "Andrea Pirlo", "Xavi", "Andres Iniesta",
        "Karim Benzema", "Luka Modric", "Sergio Ramos", "Gareth Bale",
        "Robert Lewandowski", "Neymar", "Kylian Mbappe", "Erling Haaland",
        "Hakan Sukur", "Arda Turan", "Nuri Sahin", "Emre Belozoglu",
        "Rustu Recber", "Tuncay Sanli", "Burak Yilmaz", "Wesley Sneijder"
    ).distinct()

    @Serializable
    data class DailyPlayerBio(
        val name: String,
        val position: String,
        val nationality: String,
        val imageUrl: String?,
        val careerMoves: List<ClubSeason>
    )

    fun fetchDailyPlayerBio(dateSeed: Int): DailyPlayerBio? {
        val playerName = dailyPlayerPool[((dateSeed % dailyPlayerPool.size) + dailyPlayerPool.size) % dailyPlayerPool.size]

        var result: DailyPlayerBio? = null
        try {
            withConnection { conn ->
                conn.prepareStatement(
                    "SELECT id, name, position, nationality, image_url FROM players WHERE name LIKE ? LIMIT 1"
                ).use { stmt ->
                    stmt.setString(1, "%$playerName%")
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val pId = rs.getInt("id")
                            val fullName = rs.getString("name") ?: playerName
                            val position = rs.getString("position") ?: ""
                            val rawNat = rs.getString("nationality") ?: ""
                            val imageUrl = rs.getString("image_url")

                            val moves = mutableListOf<ClubSeason>()
                            conn.prepareStatement(
                                "SELECT from_club, to_club, season FROM transfers WHERE transfer_id = ? ORDER BY season ASC"
                            ).use { stmt2 ->
                                stmt2.setInt(1, pId)
                                stmt2.executeQuery().use { rs2 ->
                                    while (rs2.next()) {
                                        val toClub = rs2.getString("to_club") ?: continue
                                        val season = rs2.getString("season") ?: ""
                                        if (!isYouthClub(toClub)) {
                                            moves.add(ClubSeason(toClub, season))
                                        }
                                    }
                                }
                            }

                            result = DailyPlayerBio(
                                name = fullName.replace(Regex("\\s*\\(\\d+\\)\\s*"), "").trim(),
                                position = position,
                                nationality = cleanNationalityText(rawNat),
                                imageUrl = imageUrl,
                                careerMoves = moves
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchDailyPlayerBio HATASI: ${e.message}")
        }
        return result
    }

    fun fetchAllClubLogos(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            withConnection { conn ->
                conn.prepareStatement("SELECT club_name_std, logo_url FROM club_logos").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val key = rs.getString("club_name_std") ?: continue
                            val url = rs.getString("logo_url") ?: continue
                            result[key] = url
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchAllClubLogos HATASI: ${e.message}")
        }
        return result
    }

    // 🏀 Basketbol için de futboldaki TOPLU yükleme deseni — az önce her takım
    // AYRI AYRI, istek üzerine çekiliyordu (veri zaten hazır olsa bile en az bir
    // ağ gidiş-gelişi gerektiriyordu). Artık tek sorguda HEPSİ birden geliyor.
    fun fetchAllBasketballLogos(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            withBbConnection { conn ->
                conn.prepareStatement("SELECT team_name_std, logo_url FROM bb_team_logos").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val key = rs.getString("team_name_std") ?: continue
                            val url = rs.getString("logo_url") ?: continue
                            result[key] = url
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchAllBasketballLogos HATASI: ${e.message}")
        }
        return result
    }

    fun fetchAllUniqueSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()
        val sql = """
            SELECT DISTINCT from_club FROM transfers UNION
            SELECT DISTINCT to_club FROM transfers UNION
            SELECT DISTINCT nationality FROM players
        """.trimIndent()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val value = rs.getString(1)
                            if (!value.isNullOrBlank()) {
                                val cleaned = value.replace('\u00a0', ' ').trim()
                                // 💡 Çift-uyruklu değerler (örn. "Czech Republic  Angola") için
                                // sadece ana/ilk parçayı öneriye ekliyoruz, böylece "Czech Republic X",
                                // "Czech Republic Y" gibi onlarca anlamsız tekrar yerine tek temiz
                                // "Czech Republic" önerisi kalıyor. Kulüp isimlerinde çift boşluk
                                // olmadığı için bu, kulüp önerilerini etkilemiyor.
                                val primaryValue = cleaned.split(Regex("\\s{2,}")).firstOrNull()?.trim() ?: cleaned
                                if (primaryValue.length > 1 && !isYouthClub(primaryValue)) {
                                    suggestions.add(primaryValue)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 suggestions HATASI: ${e.message}")
        }

        val mappedTurkishCountries = countryMap.keys
            .filter { it.length > 2 }
            .map { it.replaceFirstChar { char -> char.uppercase() } }

        suggestions.addAll(mappedTurkishCountries)

        suggestions.addAll(listOf(
            "Türkiye", "Mısır", "Fas", "Cezayir", "Tunus", "Nijerya", "Gana", "Kamerun",
            "Senegal", "Fildişi Sahili", "Güney Afrika", "Japonya", "Güney Kore", "İran",
            "Suudi Arabistan", "Katar", "Özbekistan", "Brezilya", "Arjantin", "Kolombiya",
            "Uruguay", "Meksika", "Şili", "İsveç", "Norveç", "Danimarka", "Çekya",
            // 💡 Veritabanında bu isimlerle DEĞİL, kısaltmayla ("Man Utd", "Man City")
            // kayıtlı olduğu için otomatik listeye giremiyorlardı. Elle ekliyoruz;
            // aramada zaten clubAliasMap üzerinden doğru şekilde çözümleniyor.
            "Manchester United", "Manchester City"
        ))

        return suggestions.sorted()
    }

    // 💡 ARTIK SQL SEVİYESİNDE FİLTRELİYORUZ: from_club_std / to_club_std / nationality_std
    // kolonları + index'ler sayesinde 1.1M satırlık tabloyu taramak yerine sadece eşleşen
    // satırlar çekiliyor. Kotlin tarafındaki matchesOriginalClub/isPrimaryCountryMatch kontrolü
    // ise SQL'in kaba LIKE eşleşmesinden sonra "youth club eleme" ve "tam eşleşme" gibi ince
    // iş mantığını uygulamak için hâlâ çalışıyor (ama artık çok daha az satır üzerinde).
    fun fetchPlayersByClub(clubOrCountry: String): List<Player> {
        val stdParam = clubOrCountry.toStandardSearch()
        val mappedCountry = countryMap[stdParam] ?: stdParam
        val resolvedClubTerm = resolveClubSearchTerm(clubOrCountry)

        // 💡 Artık "ya kulüp ya ülke" seçmiyoruz — countryMap sadece Türkçe->İngilizce
        // çeviri gereken ~90 ülke için var, TÜM dünya ülkelerini kapsamıyor (örn. Togo
        // orada yoktu). Bu yüzden HER zaman hem kulüp hem uyruk alanını kontrol
        // ediyoruz (union), hangisi eşleşirse o sayılıyor.
        val sql = """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate, p.slug, p.image_url, t.from_club, t.to_club, t.season 
            FROM players p 
            JOIN transfers t ON p.id = t.transfer_id
            WHERE t.from_club_std LIKE ? OR t.to_club_std LIKE ? OR p.nationality_std LIKE ?
            """.trimIndent()

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    val clubTerm = "%$resolvedClubTerm%"
                    val countryTerm = "%$mappedCountry%"
                    stmt.setString(1, clubTerm)
                    stmt.setString(2, clubTerm)
                    stmt.setString(3, countryTerm)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val season = rs.getString("season") ?: continue

                            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(fromClub, toClub, season))

                            if (!playerInfoMap.containsKey(pId)) {
                                val rawNat = rs.getString("nationality") ?: ""
                                val playerName = rs.getString("name") ?: ""

                                playerInfoMap[pId] = Player(
                                    playerId = pId,
                                    name = playerName,
                                    position = rs.getString("position") ?: "",
                                    nationality = cleanNationalityText(rawNat),
                                    team = clubOrCountry,
                                    birthDate = rs.getString("birthdate"),
                                    season1 = null,
                                    season2 = null,
                                    transferId = pId,
                                    slug = rs.getString("slug"),
                                    imageUrl = rs.getString("image_url")
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchPlayersByClub HATASI: ${e.message}")
        }

        val resultList = mutableListOf<Player>()

        for ((pId, transfers) in playerAllTransfers) {
            val player = playerInfoMap[pId] ?: continue
            val validSeasonsForClub = mutableListOf<String>()
            var hasClubMatch = false
            val hasCountryMatch = isPrimaryCountryMatch(player.nationality, clubOrCountry, mappedCountry)

            for (tr in transfers) {
                val f = tr.first
                val t = tr.second
                val s = tr.third

                if (matchesOriginalClub(f, resolvedClubTerm) || matchesOriginalClub(t, resolvedClubTerm)) {
                    hasClubMatch = true
                    validSeasonsForClub.add(s)
                }
            }

            if (hasClubMatch || hasCountryMatch) {
                val seasonValue = if (hasClubMatch) validSeasonsForClub.minOrNull() else "-"
                resultList.add(player.copy(season1 = seasonValue))
            }
        }

        // 🎯 Tam kulüp eşleşmesi (örn. tam olarak "Inter") varsa, SADECE onu göster —
        // "Inter Miami" gibi ismi içeren ama farklı bir kulübü karıştırmasın diye.
        val hasExactClubMatch = playerAllTransfers.values.any { transfers ->
            transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm) || isExactClubMatch(t, resolvedClubTerm) }
        }

        val finalList = if (hasExactClubMatch) {
            resultList.filter { player ->
                val transfers = playerAllTransfers[player.transferId] ?: emptyList()
                transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm) || isExactClubMatch(t, resolvedClubTerm) } ||
                    isPrimaryCountryMatch(player.nationality, clubOrCountry, mappedCountry)
            }
        } else {
            resultList
        }

        return finalList.distinctBy { it.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    fun fetchCommonPlayers(param1: String, param2: String): List<Player> {
        val std1 = param1.toStandardSearch()
        val std2 = param2.toStandardSearch()

        val mappedCountry1 = countryMap[std1] ?: std1
        val mappedCountry2 = countryMap[std2] ?: std2

        val resolvedClubTerm1 = resolveClubSearchTerm(param1)
        val resolvedClubTerm2 = resolveClubSearchTerm(param2)

        // 💡 Her iki parametre için de HEM kulüp HEM uyruk kontrolü yapıyoruz (union) —
        // countryMap sadece ~90 ülke için Türkçe çeviri içeriyor, TÜMÜNü kapsamıyor.
        val sql = """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate, p.slug, p.image_url, t.from_club, t.to_club, t.season 
            FROM players p 
            JOIN transfers t ON p.id = t.transfer_id
            WHERE (t.from_club_std LIKE ? OR t.to_club_std LIKE ? OR p.nationality_std LIKE ?)
               OR (t.from_club_std LIKE ? OR t.to_club_std LIKE ? OR p.nationality_std LIKE ?)
            """.trimIndent()

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    val clubTerm1 = "%$resolvedClubTerm1%"
                    val countryTerm1 = "%$mappedCountry1%"
                    val clubTerm2 = "%$resolvedClubTerm2%"
                    val countryTerm2 = "%$mappedCountry2%"

                    stmt.setString(1, clubTerm1)
                    stmt.setString(2, clubTerm1)
                    stmt.setString(3, countryTerm1)
                    stmt.setString(4, clubTerm2)
                    stmt.setString(5, clubTerm2)
                    stmt.setString(6, countryTerm2)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val season = rs.getString("season") ?: continue

                            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(fromClub, toClub, season))

                            if (!playerInfoMap.containsKey(pId)) {
                                val rawNat = rs.getString("nationality") ?: ""
                                val playerName = rs.getString("name") ?: ""

                                playerInfoMap[pId] = Player(
                                    playerId = pId,
                                    name = playerName,
                                    position = rs.getString("position") ?: "",
                                    nationality = cleanNationalityText(rawNat),
                                    team = "$param1 / $param2",
                                    birthDate = rs.getString("birthdate"),
                                    season1 = null,
                                    season2 = null,
                                    transferId = pId,
                                    slug = rs.getString("slug"),
                                    imageUrl = rs.getString("image_url")
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchCommonPlayers HATASI: ${e.message}")
        }

        val rawList = mutableListOf<Player>()

        for ((pId, transfers) in playerAllTransfers) {
            val player = playerInfoMap[pId] ?: continue

            val seasons1 = mutableListOf<String>()
            val seasons2 = mutableListOf<String>()

            for (tr in transfers) {
                val f = tr.first
                val t = tr.second
                val s = tr.third

                if (matchesOriginalClub(f, resolvedClubTerm1) || matchesOriginalClub(t, resolvedClubTerm1)) {
                    seasons1.add(s)
                }
                if (matchesOriginalClub(f, resolvedClubTerm2) || matchesOriginalClub(t, resolvedClubTerm2)) {
                    seasons2.add(s)
                }
            }

            val countryMatch1 = isPrimaryCountryMatch(player.nationality, param1, mappedCountry1)
            val countryMatch2 = isPrimaryCountryMatch(player.nationality, param2, mappedCountry2)

            val condition1Met = seasons1.isNotEmpty() || countryMatch1
            val condition2Met = seasons2.isNotEmpty() || countryMatch2

            if (condition1Met && condition2Met) {
                val min1 = if (seasons1.isNotEmpty()) seasons1.minOrNull() else "-"
                val min2 = if (seasons2.isNotEmpty()) seasons2.minOrNull() else "-"

                rawList.add(player.copy(season1 = min1, season2 = min2))
            }
        }

        // 🎯 Her terim için ayrı ayrı: tam kulüp eşleşmesi varsa (örn. tam "Inter"),
        // sadece onu tercih ediyoruz — "Inter Miami" gibi farklı bir kulübü karıştırmasın.
        fun hasExactMatchFor(term: String): Boolean {
            return playerAllTransfers.values.any { transfers ->
                transfers.any { (f, t, _) -> isExactClubMatch(f, term) || isExactClubMatch(t, term) }
            }
        }
        val exact1 = hasExactMatchFor(resolvedClubTerm1)
        val exact2 = hasExactMatchFor(resolvedClubTerm2)

        val finalList = rawList.filter { player ->
            val transfers = playerAllTransfers[player.transferId] ?: emptyList()

            val ok1 = if (exact1) {
                transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm1) || isExactClubMatch(t, resolvedClubTerm1) } ||
                    isPrimaryCountryMatch(player.nationality, param1, mappedCountry1)
            } else true

            val ok2 = if (exact2) {
                transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm2) || isExactClubMatch(t, resolvedClubTerm2) } ||
                    isPrimaryCountryMatch(player.nationality, param2, mappedCountry2)
            } else true

            ok1 && ok2
        }

        return finalList.distinctBy { it.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    // 💡 "OYUNCU MODU" — verilen 2 (ya da daha fazla) kulübün HEPSİNDE gerçekten
    // oynamış rastgele bir oyuncu buluyor. Mevcut fetchPlayersByClub/fetchCommonPlayers
    // fonksiyonlarına hiç dokunmuyor, tamamen ayrı ve izole bir sorgu yolu.
    // 💡 GENİŞLETİLMİŞ: her terim artık ya KULÜP ya da ÜLKE olabilir (Pair'in ikinci
    // değeri isCountry). Ülke terimleri nationality_std üzerinden, kulüp terimleri
    // eskisi gibi from_club_std/to_club_std üzerinden eşleştiriliyor.
    fun fetchPlayerAcrossClubs(terms: List<Pair<String, Boolean>>): MultiClubPlayerResult? {
        if (terms.size < 2) return null

        val resolvedClubTerms = terms.map { (term, isCountry) -> if (isCountry) null else resolveClubSearchTerm(term) }
        val mappedCountryTerms = terms.map { (term, isCountry) ->
            if (isCountry) {
                val std = term.toStandardSearch()
                countryMap[std] ?: std
            } else null
        }

        val sql = buildString {
            append(
                """
                SELECT p.id, p.name, p.position, p.nationality, p.image_url, t.from_club, t.to_club, t.season
                FROM players p
                JOIN transfers t ON p.id = t.transfer_id
                WHERE 
                """.trimIndent()
            )
            val conditions = terms.map { (_, isCountry) ->
                if (isCountry) "p.nationality_std LIKE ?" else "(t.from_club_std LIKE ? OR t.to_club_std LIKE ?)"
            }
            append(conditions.joinToString(" OR "))
        }

        // playerId -> (orijinal terim -> o terime ait en erken sezon, ülke için "-")
        val playerTermSeasons = mutableMapOf<Int, MutableMap<String, String>>()
        val playerNames = mutableMapOf<Int, String>()
        val playerPositions = mutableMapOf<Int, String>()
        val playerImageUrls = mutableMapOf<Int, String?>()
        val playerNationalities = mutableMapOf<Int, String>()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    terms.forEachIndexed { i, (_, isCountry) ->
                        if (isCountry) {
                            stmt.setString(idx++, "%${mappedCountryTerms[i]}%")
                        } else {
                            val p = "%${resolvedClubTerms[i]}%"
                            stmt.setString(idx++, p)
                            stmt.setString(idx++, p)
                        }
                    }

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val name = rs.getString("name") ?: continue
                            val position = rs.getString("position") ?: ""
                            val imageUrl = rs.getString("image_url")
                            val rawNat = rs.getString("nationality") ?: ""
                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val season = rs.getString("season") ?: continue

                            playerNames[pId] = name
                            playerPositions[pId] = position
                            playerImageUrls[pId] = imageUrl
                            playerNationalities[pId] = cleanNationalityText(rawNat)

                            terms.forEachIndexed { tIdx, (originalTerm, isCountry) ->
                                if (isCountry) {
                                    if (isPrimaryCountryMatch(playerNationalities[pId] ?: "", originalTerm, mappedCountryTerms[tIdx] ?: "")) {
                                        val bucket = playerTermSeasons.getOrPut(pId) { mutableMapOf() }
                                        bucket[originalTerm] = "-"
                                    }
                                } else {
                                    val resolved = resolvedClubTerms[tIdx] ?: return@forEachIndexed
                                    if (matchesOriginalClub(fromClub, resolved) || matchesOriginalClub(toClub, resolved)) {
                                        val bucket = playerTermSeasons.getOrPut(pId) { mutableMapOf() }
                                        val existing = bucket[originalTerm]
                                        if (existing == null || existing == "-" || parseSeasonToSortValue(season) < parseSeasonToSortValue(existing)) {
                                            bucket[originalTerm] = season
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchPlayerAcrossClubs HATASI: ${e.message}")
            return null
        }

        // Sadece istenen TÜM terimlerde eşleşmiş oyuncular kalsın
        val fullMatches = playerTermSeasons.filter { (_, m) -> terms.all { (term, _) -> m.containsKey(term) } }

        if (fullMatches.isEmpty()) return null

        val (chosenId, termSeasonMap) = fullMatches.entries.random()
        val playerName = playerNames[chosenId] ?: return null
        val position = playerPositions[chosenId] ?: ""

        return MultiClubPlayerResult(
            playerName = playerName,
            position = position,
            clubs = terms.map { (term, _) -> ClubSeason(term, termSeasonMap[term] ?: "-") },
            imageUrl = playerImageUrls[chosenId]
        )
    }

    // 💡 Not: eski List<String> imzalı sürüm kaldırıldı — Kotlin'de jenerik tipler
    // (List<String> vs List<Pair<String,Boolean>>) JVM bytecode seviyesinde aynı
    // imzaya sıkışıyor (tip silme), bu yüzden iki ayrı fonksiyon olarak duramıyorlardı.
    // Zaten hiçbir yerde eski imza çağrılmıyordu, üstteki yeni imza tek başına yeterli.

    private fun cleanNationalityText(rawNat: String): String {
        return rawNat.replace('\u00a0', ' ')
            .replace(160.toChar(), ' ')
            .replace(Regex("Mevki|Uyruk|[0-9()]+"), "")
            .trim()
    }
}
