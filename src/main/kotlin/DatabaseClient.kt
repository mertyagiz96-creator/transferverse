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

@Serializable
data class ClubSeason(val club: String, val season: String)

@Serializable
data class MultiClubPlayerResult(
    val playerName: String,
    val position: String,
    val clubs: List<ClubSeason>,
    val imageUrl: String? = null,
    val nationality: String? = null,
    val birthDate: String? = null,
    val playerId: Int? = null,
    val slug: String? = null
)

@Serializable
data class PlayerBasicInfo(
    val name: String,
    val position: String?,
    val nationality: String?,
    val birthDate: String?
)

@Serializable
data class SupabaseHighScoreInsert(val id: Int, val score: Int)

@Serializable
data class PollOption(val league: String, val team: String, val votes: Int)

@Serializable
data class PollVoteUpdate(val votes: Int)

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

    private val clubAliasMap = mapOf(
        "manchester united" to "man utd",
        "manchester utd" to "man utd",
        "man united" to "man utd",
        "manchester city" to "man city",
        "borussia dortmund" to "bor. dortmund"
    )

    private fun resolveClubSearchTerm(raw: String): String {
        val std = raw.toStandardSearch()
        return clubAliasMap[std] ?: std
    }

    private const val POOL_SIZE = 6
    private val connectionPool: java.util.concurrent.BlockingQueue<Connection> by lazy { createConnectionPool() }

    private val supabaseUrl = System.getenv("SUPABASE_URL")?.trimEnd('/')
    private val supabaseKey = System.getenv("SUPABASE_KEY")

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

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
            println("SUPABASE_URL / SUPABASE_KEY ayarlanmamış, rekor devre dışı (varsayılan $fallback dönüyor).")
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
            println("Supabase rekor okuma hatası: ${e.message}")
            fallback
        }
    }

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
                println("Supabase rekor yazma hatası: HTTP ${response.status} — $errorBody")
                return current
            }
            score
        } catch (e: Exception) {
            println("Supabase rekor yazma hatası: ${e.message}")
            current
        }
    }

    fun fetchPollResults(): List<PollOption> = kotlinx.coroutines.runBlocking { fetchPollResultsSuspend() }

    fun submitPollVote(league: String, team: String): List<PollOption> = kotlinx.coroutines.runBlocking { submitPollVoteSuspend(league, team) }

    private suspend fun fetchPollResultsSuspend(): List<PollOption> {
        if (supabaseUrl == null || supabaseKey == null) return emptyList()
        return try {
            val response: HttpResponse = httpClient.get("$supabaseUrl/rest/v1/poll_votes") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                parameter("select", "league,team,votes")
            }
            val body = response.bodyAsText()
            Json.decodeFromString<List<PollOption>>(body)
        } catch (e: Exception) {
            println("Anket okuma hatası: ${e.message}")
            emptyList()
        }
    }

    private suspend fun submitPollVoteSuspend(league: String, team: String): List<PollOption> {
        if (supabaseUrl == null || supabaseKey == null) return emptyList()
        try {
            val current = fetchPollResultsSuspend()
            val currentVotes = current.firstOrNull { it.league == league && it.team == team }?.votes ?: 0
            val response = httpClient.patch("$supabaseUrl/rest/v1/poll_votes") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                header("Content-Type", "application/json")
                parameter("league", "eq.$league")
                parameter("team", "eq.$team")
                setBody(Json.encodeToString(PollVoteUpdate(currentVotes + 1)))
            }
            if (!response.status.isSuccess()) {
                println("Anket oy yazma hatası: HTTP ${response.status} — ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            println("Anket oy yazma hatası: ${e.message}")
        }
        return fetchPollResultsSuspend()
    }

    private fun createConnectionPool(): java.util.concurrent.BlockingQueue<Connection> {
        val pool: java.util.concurrent.BlockingQueue<Connection> =
            java.util.concurrent.ArrayBlockingQueue(POOL_SIZE)
        repeat(POOL_SIZE) {
            pool.put(createConnection())
        }
        return pool
    }

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
                "basketball.db bulunamadı: ${dbFile.absolutePath}. " +
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

    fun fetchAllBasketballSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()
        try {
            withBbConnection { conn ->
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
            println("fetchAllBasketballSuggestions HATASI: ${e.message}")
        }
        return suggestions.sorted()
    }

    @Serializable
    data class BasketballPlayerResult(
        val name: String,
        val team1Season: String?,
        val team2Season: String?,
        val competition: String,
        val nbaOfficialId: String? = null
    )

    fun fetchAllNbaSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()
        try {
            withBbConnection { conn ->
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
            println("fetchAllNbaSuggestions HATASI: ${e.message}")
        }
        return suggestions.sorted()
    }

    // 🏀 Basketbol oyuncu isim önerisi — futboldaki fetchPlayerNameSuggestions ile
    // AYNI mantık: bağlam takımlarından birinde oynamış olanlar önce, sonra en
    // çok kayıtlı (muhtemelen en tanınan). "league" parametresi hangi tabloyu
    // (Avrupa/EuroLeague-EuroCup ya da NBA) arayacağımızı belirliyor — Günün
    // Sorusu her gün SADECE birini gösterdiği için, ikisini karıştırmıyoruz.
    fun fetchBasketballPlayerNameSuggestions(query: String, league: String, contextTeams: List<String> = emptyList()): List<String> {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 3) return emptyList()
        val targetNorm = stripAccentsForCompare(cleanQuery)
        val isNba = league == "nba"
        val tableName = if (isNba) "nba_players" else "bb_players"
        val teamColumn = "team_name_std"

        data class Cand(val name: String, var contextMatch: Boolean, var appearances: Int)
        val candidates = mutableMapOf<String, Cand>()

        try {
            withBbConnection { conn ->
                val sql = "SELECT name, $teamColumn FROM $tableName WHERE name_std LIKE ? LIMIT 200"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "%$targetNorm%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("name") ?: continue
                            val nameNorm = stripAccentsForCompare(name)
                            if (!nameNorm.contains(targetNorm)) continue

                            val teamStd = rs.getString(teamColumn) ?: ""
                            val teamMatch = contextTeams.any { t ->
                                val tStd = t.toStandardSearch()
                                teamStd.contains(tStd) || tStd.contains(teamStd)
                            }

                            val existing = candidates[name]
                            if (existing == null) {
                                candidates[name] = Cand(name, teamMatch, 1)
                            } else {
                                existing.appearances++
                                if (teamMatch) existing.contextMatch = true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("fetchBasketballPlayerNameSuggestions HATASI: ${e.message}")
        }

        return candidates.values
            .sortedWith(compareByDescending<Cand> { it.contextMatch }.thenByDescending { it.appearances })
            .take(12)
            .map { it.name }
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
            println("fetchCommonNbaPlayers HATASI: ${e.message}")
        }
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 1000) {
            println("fetchCommonNbaPlayers YAVAŞ: ${elapsed}ms ($team1 vs $team2, ${results.size} sonuç)")
        }
        return results.sortedByDescending { it.team1Season?.toIntOrNull() ?: 0 }
    }

    private val basketballLogoCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val basketballPhotoCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun preloadAllBasketballLogos() {
        try {
            val europeTeams = fetchAllBasketballSuggestions()
            val nbaTeams = fetchAllNbaSuggestions()
            val allTeams = (europeTeams + nbaTeams).distinct()

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
                println("Logolar zaten kalıcı veritabanında ($alreadyCachedCount/${allTeams.size}) — ön-yükleme atlandı.")
                return
            }

            println("${allTeams.size} takımın logosu önceden yükleniyor...")
            var found = 0
            for (team in allTeams) {
                val callStart = System.currentTimeMillis()
                val logo = fetchBasketballTeamLogo(team)
                if (logo != null) found++
                val callElapsed = System.currentTimeMillis() - callStart
                if (callElapsed > 100) {
                    kotlinx.coroutines.delay(600)
                }
            }
            println("Logo ön-yükleme tamamlandı: $found / ${allTeams.size} bulundu.")
        } catch (e: Exception) {
            println("preloadAllBasketballLogos HATASI: ${e.message}")
        }
    }

    private val footballLogoFallbackCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val footballLogoFallbackFailedRecently = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun fetchFootballTeamLogoFallback(teamName: String): String? {
        val cacheKey = teamName.trim().lowercase()
        footballLogoFallbackCache[cacheKey]?.let { return it }
        if (footballLogoFallbackFailedRecently.contains(cacheKey)) return null

        try {
            val response = httpClient.get("https://www.thesportsdb.com/api/v1/json/123/searchteams.php") {
                parameter("t", teamName)
                timeout { requestTimeoutMillis = 3_000 }
            }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val root = Json.parseToJsonElement(body).jsonObject
                val teamsElement = root["teams"]
                if (teamsElement != null && teamsElement !is JsonNull) {
                    val teams = teamsElement.jsonArray
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
            println("fetchFootballTeamLogoFallback hata: ${e.message}")
            footballLogoFallbackFailedRecently.add(cacheKey)
        }
        return null
    }

    suspend fun fetchBasketballTeamLogo(teamName: String): String? {
        val cacheKey = teamName.trim().lowercase()
        basketballLogoCache[cacheKey]?.let { return it }

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
            println("bb_team_logos okuma hatası: ${e.message}")
        }

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
            kotlinx.coroutines.delay(150)
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
                                println("bb_team_logos yazma hatası: ${e.message}")
                            }
                            return badge
                        }
                    }
                }
            } catch (e: Exception) {
                println("fetchBasketballTeamLogo hata ($attempt): ${e.message}")
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
            println("bb_player_photos okuma hatası: ${e.message}")
        }

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
                                println("bb_player_photos yazma hatası: ${e.message}")
                            }
                            return photo
                        }
                    }
                }
            } catch (e: Exception) {
                println("fetchBasketballPlayerPhoto hata ($variant): ${e.message}")
            }
        }
        return null
    }

    fun fetchCommonBasketballPlayers(team1: String, team2: String): List<BasketballPlayerResult> {
        val startTime = System.currentTimeMillis()
        val std1 = team1.toStandardSearch()
        val std2 = team2.toStandardSearch()

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
            println("fetchCommonBasketballPlayers HATASI: ${e.message}")
        }
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 1000) {
            println("fetchCommonBasketballPlayers YAVAŞ: ${elapsed}ms ($team1 vs $team2, ${results.size} sonuç)")
        }
        return results.sortedByDescending { it.team1Season?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        val conn = connectionPool.take()
        try {
            return block(conn)
        } finally {
            connectionPool.put(conn)
        }
    }

    private fun createConnection(): Connection {
        val dbFile = File("football.db")

        if (!dbFile.exists()) {
            throw IllegalStateException(
                "football.db bulunamadı: ${dbFile.absolutePath}. " +
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

    private fun String.toStandardSearch(): String {
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
            "res.", "sva"
        )
        if (youthKeywords.any { lower.contains(it) }) return true
        if (Regex("\\s[bc]$").containsMatchIn(lower)) return true
        return false
    }

    private fun matchesOriginalClub(clubName: String?, resolvedTarget: String): Boolean {
        if (clubName == null) return false
        val cleanClub = clubName.toStandardSearch()
        if (isYouthClub(cleanClub)) {
            return false
        }
        // 🎯 KESİN DÜZELTME: "Barcelona" araması, "RCD Espanyol de Barcelona"
        // (Espanyol'un resmi tam adı, FC Barcelona'dan TAMAMEN FARKLI bir kulüp)
        // ile yanlışlıkla eşleşiyordu — çünkü isimde "barcelona" kelimesi geçiyor.
        // Bu, Raúl de Tomás'ın (gerçekte sadece Espanyol'da oynamış, Barça'da hiç
        // oynamamış) yanlışlıkla "Barcelona" cevabı olarak çıkmasına sebep olmuştu.
        if (resolvedTarget == "barcelona" && cleanClub.contains("espanyol")) {
            return false
        }
        return cleanClub.contains(resolvedTarget)
    }

    private fun stripClubSuffix(s: String): String {
        var result = s.replace(Regex("\\s+(bc|fc|cf|sk|ac|sc|afc|kk|fk|cp|sd|cd|if|bk|ff|bsc)$"), "").trim()
        // 🎯 KESİN DÜZELTME: veritabanında AYNI kulüp için iki farklı kayıt
        // türü olduğu doğrulandı — bazı kayıtlarda yalın "Milan", bazılarında
        // (örn. Zlatan Ibrahimović'in transferlerinde) "AC Milan" kullanılmış.
        // "ac" öneki eskiden sadece SONEK olarak temizleniyordu ("Something AC"),
        // ama "AC Milan" gibi ÖNEK durumunda hiç temizlenmiyordu — bu da kesin
        // eşleşme kontrolünün gerçek oyuncuları yanlışlıkla reddetmesine yol
        // açıyordu. Artık "ac" önek olarak da tanınıyor.
        result = result.replace(Regex("^(1\\.\\s*fc|vfb|vfl|tsv|ac|as|ad|af|aj|ao)\\s+"), "").trim()
        return result
    }

    private fun isExactClubMatch(clubName: String?, resolvedTarget: String): Boolean {
        if (clubName == null) return false
        val cleanClub = clubName.toStandardSearch()
        if (isYouthClub(cleanClub)) return false
        return cleanClub == resolvedTarget || stripClubSuffix(cleanClub) == stripClubSuffix(resolvedTarget)
    }

    private fun isPrimaryCountryMatch(playerNationality: String, searchParam: String, mappedCountry: String): Boolean {
        val stdNat = playerNationality.toStandardSearch()
        val stdSearch = searchParam.toStandardSearch()
        val stdMapped = mappedCountry.toStandardSearch()
        if (stdNat.isBlank()) return false

        // 🎯 DÜZELTME (v2 — daha güvenli): ilk denemem tek tek KELİMELERE
        // bölüyordu, bu da "South Africa" gibi çok kelimeli ülke isimlerinde
        // yanlış pozitif riski taşıyordu (örn. sadece "Africa" araması yanlışlıkla
        // eşleşebilirdi). Artık aranan İFADENİN TAMAMINI (tek parça olarak),
        // kelime sınırlarıyla (\b) uyruk alanının içinde arıyoruz — çok kelimeli
        // ülke isimleri bozulmadan korunuyor, ama biçim tutarsızlıklarına
        // (fazladan boşluk, farklı sıralama) karşı hâlâ toleranslı.
        fun containsAsWholeTerm(term: String): Boolean {
            if (term.isBlank()) return false
            return Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(stdNat)
        }
        return containsAsWholeTerm(stdSearch) || containsAsWholeTerm(stdMapped)
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

    // 🎯 En az bir sezon 4 haneli bir yıl içeriyorsa VE bu yıl 2010+ ise true —
    // basketbol sezon formatları (Avrupa: "E2024", NBA: "2021-22" gibi) farklı
    // olabildiği için, biçimden bağımsız, düz metinde 4 haneli yıl arıyoruz.
    private fun seasonHasRecentYear(season: String?, minYear: Int): Boolean {
        if (season.isNullOrBlank()) return false
        val match = Regex("(19|20)\\d{2}").find(season) ?: return false
        val year = match.value.toIntOrNull() ?: return false
        return year >= minYear
    }

    fun fetchRandomBasketballQuestion(pool: List<String>, isNba: Boolean, minYear: Int = 2010): RandomBasketballQuestion {
        if (pool.size < 2) return RandomBasketballQuestion(found = false)
        val overallStart = System.currentTimeMillis()
        // 🎯 DÜZELTME: Günün Sorusu'nda çok eski (örn. 1986-1997 gibi) yıl
        // aralıkları çıkıp tamamen tanınmaz oyuncular çıkabiliyordu. Artık en az
        // 20 deneme boyunca, EN AZ BİR takım-sezon eşleşmesi 2010+ olan bir
        // oyuncu arıyoruz — bulamazsak (nadir takım çiftleri için mümkün),
        // son çare olarak yıl şartı olmadan devam ediyoruz (soru üretilemeyip
        // boş ekran çıkmasındansa, eski bile olsa bir soru göstermek daha iyi).
        var fallbackQuestion: RandomBasketballQuestion? = null
        repeat(20) { attemptNum ->
            val team1 = pool.random()
            var team2: String
            do { team2 = pool.random() } while (team2 == team1)

            val players = if (isNba) fetchCommonNbaPlayers(team1, team2) else fetchCommonBasketballPlayersLean(team1, team2)
            if (players.isNotEmpty()) {
                val recentPlayers = players.filter {
                    seasonHasRecentYear(it.team1Season, minYear) || seasonHasRecentYear(it.team2Season, minYear)
                }
                val chosenPool = if (recentPlayers.isNotEmpty()) recentPlayers else players
                val player = chosenPool.random()
                val totalElapsed = System.currentTimeMillis() - overallStart
                if (totalElapsed > 500 || attemptNum >= 3) {
                    println("fetchRandomBasketballQuestion YAVAŞ: ${totalElapsed}ms, ${attemptNum + 1} deneme")
                }
                val question = RandomBasketballQuestion(
                    found = true,
                    team1 = team1,
                    team2 = team2,
                    playerName = player.name,
                    team1Season = player.team1Season,
                    team2Season = player.team2Season,
                    nbaOfficialId = player.nbaOfficialId
                )
                if (recentPlayers.isNotEmpty()) return question // 🎯 2010+ bulundu, hemen dön
                if (fallbackQuestion == null) fallbackQuestion = question // 💡 yedek olarak sakla, aramaya devam et
            }
        }
        return fallbackQuestion ?: RandomBasketballQuestion(found = false)
    }

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
            println("fetchCommonBasketballPlayersLean HATASI: ${e.message}")
        }
        return results
    }

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
        // 🎯 KÖK SEBEP DÜZELTMESİ: havuzdaki isimler aksansız yazılmış ("Mbappe"),
        // ama veritabanında muhtemelen aksanlı kayıtlı ("Mbappé") — düz LIKE bu
        // yüzden hiç eşleşmiyor, 404 dönüyordu. Diğer arama fonksiyonlarındaki
        // AYNI aksan-toleranslı yöntemi burada da kullanıyoruz.
        val targetNorm = stripAccentsForCompare(playerName)

        var result: DailyPlayerBio? = null
        try {
            withConnection { conn ->
                conn.prepareStatement(
                    "SELECT id, name, position, nationality, image_url FROM players WHERE name_std LIKE ? LIMIT 1"
                ).use { stmt ->
                    stmt.setString(1, "%$targetNorm%")
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
            println("fetchDailyPlayerBio HATASI: ${e.message}")
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
            println("fetchAllClubLogos HATASI: ${e.message}")
        }
        return result
    }

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
            println("fetchAllBasketballLogos HATASI: ${e.message}")
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
            println("suggestions HATASI: ${e.message}")
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
            "Manchester United", "Manchester City"
        ))

        return suggestions.sorted()
    }

    fun fetchPlayersByClub(clubOrCountry: String): List<Player> {
        val stdParam = clubOrCountry.toStandardSearch()
        val mappedCountry = countryMap[stdParam] ?: stdParam
        val resolvedClubTerm = resolveClubSearchTerm(clubOrCountry)

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
            println("fetchPlayersByClub HATASI: ${e.message}")
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
            println("fetchCommonPlayers HATASI: ${e.message}")
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

    // 🎯 KÖK SEBEP DÜZELTMESİ (bu turda): transfers tablosu SEZON-SEZON değil,
    // OLAY (TRANSFER) BAZLI kayıt tutuyor — bir oyuncu bir kulübe transfer olup
    // yıllarca orada kalsa bile, veritabanında SADECE O TRANSFERİN olduğu tek
    // bir sezon satırı var (örn. Rodri, City'ye 2019'da geldi ve hâlâ orada —
    // kaydı sadece "19/20"). Önceki sürüm, "oyuncunun sezonu TAM OLARAK
    // startYear/endYear'a eşit olmalı" diye kontrol ediyordu — bu, uzun süre
    // aynı kulüpte kalan oyuncuları YANLIŞLIKLA reddediyordu.
    //
    // DOĞRU MANTIK: bir köprü oyuncusunun katılım sezonu, [startYear, endYear]
    // aralığının EN GEÇ (en küçük) sınırından ÖNCE ya da TAM O ANDA olmalı —
    // çünkü veride yeni bir transfer YOKSA oyuncu hâlâ o kulüpte olduğu
    // varsayılıyor. Bu, hem yeni gelenleri hem uzun süredir orada olanları
    // (Rodri gibi) doğru şekilde kabul ediyor.
    fun verifyPlayerPlayedForClub(playerName: String, clubName: String, startYear: Int? = null, endYear: Int? = null, requiredNationality: String? = null): Boolean {
        val cleanName = playerName.trim()
        if (cleanName.isEmpty()) return false
        val resolvedClub = resolveClubSearchTerm(clubName)
        val originalStdClub = clubName.toStandardSearch()
        val clubVariants = listOf(resolvedClub, originalStdClub).distinct()
        val targetNorm = stripAccentsForCompare(cleanName)
        // 🎯 YENİ: uyruk şartı — verilmişse, köprü oyuncusunun UYRUĞU da eşleşmeli.
        val requiredNatStd = requiredNationality?.toStandardSearch()
        val sql = """
            SELECT p.name, p.nationality, t.from_club, t.to_club, t.season
            FROM (
                SELECT id, name, nationality FROM players
                WHERE name_std LIKE ?
                LIMIT 30
            ) p
            JOIN transfers t ON p.id = t.transfer_id
        """.trimIndent()

        val minTarget = listOfNotNull(startYear, endYear).minOrNull()
        val maxTarget = listOfNotNull(startYear, endYear).maxOrNull()
        var found = false
        // 🎯 KESİN DÜZELTME: eskiden sadece "en erken katılım yılı" bakılıyordu
        // — oyuncu SONRADAN ayrılmış olsa bile fark edilmiyordu (basketbolda
        // bulduğumuz AYNI hata). Artık her transfer kaydını "VARIŞ" (to_club
        // eşleşirse) ya da "AYRILIŞ" (from_club eşleşirse) olarak işaretleyip,
        // GERÇEK dönemleri (örn. 2010'da geldi, 2014'te ayrıldı) hesaplıyoruz.
        val arrivals = mutableListOf<Int>()
        val departures = mutableListOf<Int>()

        try {
            fun tryPattern(likePattern: String) {
                if (found) return
                withConnection { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, likePattern)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val pName = rs.getString("name") ?: continue
                                val nameNorm = stripAccentsForCompare(pName.replace(Regex("\\s*\\(\\d+\\)\\s*"), ""))
                                val words = nameNorm.trim().split(Regex("\\s+"))
                                val surnameNorm = words.lastOrNull() ?: ""
                                val firstNameNorm = words.firstOrNull() ?: ""
                                val isNicknameMatch = targetNorm.length >= 4 &&
                                    (firstNameNorm.startsWith(targetNorm) || surnameNorm.startsWith(targetNorm))
                                if (nameNorm != targetNorm && surnameNorm != targetNorm && !isNicknameMatch) continue

                                // 🎯 YENİ: uyruk şartı varsa, oyuncunun ANA uyruğu bununla eşleşmeli.
                                if (requiredNatStd != null) {
                                    val rawNat = rs.getString("nationality") ?: ""
                                    val cleanedNat = cleanNationalityText(rawNat)
                                    val primaryNat = cleanedNat.toStandardSearch().split(Regex("\\s{2,}")).firstOrNull()?.trim() ?: ""
                                    if (primaryNat != requiredNatStd) continue
                                }

                                val fromClub = rs.getString("from_club") ?: ""
                                val toClub = rs.getString("to_club") ?: ""
                                val isArrival = clubVariants.any { matchesOriginalClub(toClub, it) }
                                val isDeparture = clubVariants.any { matchesOriginalClub(fromClub, it) }
                                if (!isArrival && !isDeparture) continue

                                found = true
                                val seasonYear = parseSeasonToSortValue(rs.getString("season"))
                                if (seasonYear > 0) {
                                    if (isArrival) arrivals.add(seasonYear)
                                    if (isDeparture) departures.add(seasonYear)
                                }
                            }
                        }
                    }
                }
            }

            tryPattern("%$targetNorm%")
        } catch (e: Exception) {
            println("verifyPlayerPlayedForClub HATASI: ${e.message}")
        }

        // 🎯 Yıl şartı yoksa, sadece isim+kulüp eşleşmesi yeterli.
        if (minTarget == null || maxTarget == null) {
            return found
        }
        if (!found) return false

        // 🎯 GERÇEK DÖNEM HESABI: varışları sıralayıp, her biri için bir SONRAKİ
        // ayrılışı eşleştiriyoruz — böylece "2010'da geldi, 2014'te ayrıldı"
        // gibi GERÇEK aralıklar oluşuyor. Eşleşecek ayrılış yoksa (hâlâ orada
        // ya da veri eksik), dönem AÇIK UÇLU kabul ediliyor.
        val sortedArrivals = arrivals.sorted()
        val sortedDepartures = departures.sorted().toMutableList()
        val intervals = mutableListOf<Pair<Int, Int?>>() // (başlangıç, bitiş-veya-null)
        for (arrival in sortedArrivals) {
            val matchingDeparture = sortedDepartures.firstOrNull { it >= arrival }
            if (matchingDeparture != null) sortedDepartures.remove(matchingDeparture)
            intervals.add(arrival to matchingDeparture)
        }
        // 🛡️ Eşleşmemiş ayrılışlar varsa (varış kaydı yoksa — akademiden çıkma
        // gibi), "en başından beri oradaydı, şu yılda ayrıldı" olarak ekle.
        sortedDepartures.forEach { dep -> intervals.add(Int.MIN_VALUE to dep) }
        // 💡 Hiç varış/ayrılış ayrıştırılamadıysa (nadiren, kulüp isim eşleşme
        // sorunuyla), eski (daha gevşek) davranışa güvenli şekilde düş.
        if (intervals.isEmpty()) return found

        val hasOverlap = intervals.any { (start, end) ->
            val effectiveEnd = end ?: Int.MAX_VALUE
            start <= maxTarget && effectiveEnd >= minTarget
        }
        return hasOverlap
    }

    private fun stripAccentsForCompare(s: String): String {
        val turkishFixed = s
            .replace('İ', 'I').replace('ı', 'i')
            .replace('Ğ', 'G').replace('ğ', 'g')
            .replace('Ş', 'S').replace('ş', 's')
            .replace('Ç', 'C').replace('ç', 'c')
            .replace('Ö', 'O').replace('ö', 'o')
            .replace('Ü', 'U').replace('ü', 'u')
        val normalized = java.text.Normalizer.normalize(turkishFixed, java.text.Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{M}"), "").lowercase()
    }

    @Serializable
    data class PlayerTransferCountResult(val name: String, val transferCount: Int)

    private val EASY_CLUBS_TM = listOf(
        "Real Madrid", "Barcelona", "Manchester United", "Manchester City", "Chelsea", "Liverpool",
        "Juventus", "Inter", "Bayern Munich", "Paris SG", "Galatasaray", "Fenerbahce", "Besiktas"
    )

    fun fetchRandomTransferCandidates(excludeNames: List<String>, count: Int = 3): List<PlayerTransferCountResult> {
        val excludeNorm = excludeNames.map { stripAccentsForCompare(it) }.toSet()
        val clubLikeParams = EASY_CLUBS_TM.flatMap { listOf("%$it%", "%$it%") }
        val whereClubs = EASY_CLUBS_TM.joinToString(" OR ") { "t.from_club LIKE ? OR t.to_club LIKE ?" }

        val candidateIds = mutableSetOf<Int>()
        try {
            withConnection { conn ->
                val sql = """
                    SELECT DISTINCT p.id
                    FROM players p
                    JOIN transfers t ON p.id = t.transfer_id
                    WHERE ($whereClubs)
                    ORDER BY RANDOM()
                    LIMIT 60
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    clubLikeParams.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) candidateIds.add(rs.getInt("id"))
                    }
                }
            }
        } catch (e: Exception) {
            println("fetchRandomTransferCandidates (aday ID) HATASI: ${e.message}")
            return emptyList()
        }

        val results = mutableListOf<PlayerTransferCountResult>()
        for (pId in candidateIds.shuffled()) {
            if (results.size >= count) break
            try {
                var pName = ""
                var realCount = 0
                withConnection { conn ->
                    conn.prepareStatement("SELECT name, from_club, to_club FROM players p JOIN transfers t ON p.id = t.transfer_id WHERE p.id = ?").use { stmt ->
                        stmt.setInt(1, pId)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                if (pName.isEmpty()) pName = (rs.getString("name") ?: "").replace(Regex("\\s*\\(\\d+\\)\\s*"), "").trim()
                                val fromClub = rs.getString("from_club")
                                val toClub = rs.getString("to_club")
                                if (!isYouthClub(fromClub) && !isYouthClub(toClub)) realCount++
                            }
                        }
                    }
                }
                if (pName.isEmpty() || realCount == 0) continue
                if (stripAccentsForCompare(pName) in excludeNorm) continue
                if (results.any { it.name == pName }) continue
                results.add(PlayerTransferCountResult(pName, realCount))
            } catch (e: Exception) {
                println("fetchRandomTransferCandidates (detay) HATASI: ${e.message}")
            }
        }
        return results
    }

    fun fetchPlayerTransferCount(query: String): PlayerTransferCountResult? {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return null
        val targetNorm = stripAccentsForCompare(cleanQuery)

        data class MatchedPlayer(val id: Int, val name: String)
        val matched = mutableListOf<MatchedPlayer>()
        try {
            withConnection { conn ->
                val sql1 = "SELECT id, name FROM players WHERE name_std LIKE ? LIMIT 30"
                conn.prepareStatement(sql1).use { stmt ->
                    stmt.setString(1, "%$targetNorm%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val rawName = rs.getString("name") ?: continue
                            val cleanName = rawName.replace(Regex("\\s*\\(\\d+\\)\\s*"), "").trim()
                            val nameNorm = stripAccentsForCompare(cleanName)
                            if (nameNorm.contains(targetNorm) || nameNorm == targetNorm) {
                                matched.add(MatchedPlayer(pId, cleanName))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("fetchPlayerTransferCount (1. adım) HATASI: ${e.message}")
            return null
        }

        if (matched.isEmpty()) return null

        var best: PlayerTransferCountResult? = null
        var bestCount = -1
        try {
            withConnection { conn ->
                conn.prepareStatement("SELECT from_club, to_club FROM transfers WHERE transfer_id = ?").use { stmt ->
                    for (m in matched) {
                        var count = 0
                        stmt.setInt(1, m.id)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val fromClub = rs.getString("from_club")
                                val toClub = rs.getString("to_club")
                                if (!isYouthClub(fromClub) && !isYouthClub(toClub)) count++
                            }
                        }
                        if (count > bestCount) {
                            bestCount = count
                            best = PlayerTransferCountResult(m.name, count)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("fetchPlayerTransferCount (2. adım) HATASI: ${e.message}")
        }
        return best
    }

    private fun sqlAccentStripExpr(column: String): String {
        var expr = "LOWER($column)"
        val replacements = listOf(
            "İ" to "i", "I" to "i", "ı" to "i",
            "Ğ" to "g", "ğ" to "g",
            "Ş" to "s", "ş" to "s",
            "Ç" to "c", "ç" to "c",
            "Ö" to "o", "ö" to "o",
            "Ü" to "u", "ü" to "u",
            "Á" to "a", "á" to "a", "É" to "e", "é" to "e",
            "Í" to "i", "í" to "i", "Ó" to "o", "ó" to "o",
            "Ú" to "u", "ú" to "u", "Ñ" to "n", "ñ" to "n",
            "Ć" to "c", "ć" to "c"
        )
        for ((from, to) in replacements) {
            expr = "REPLACE($expr, '$from', '$to')"
        }
        return expr
    }

    // 🚀 PERFORMANS MİGRASYONU (tek seferlik): "players" tablosunda ~92.000 satır
    // var, ve isim aramalarında HER SATIR için ~20 iç içe REPLACE() (aksan
    // temizleme) ANLIK olarak hesaplanıyordu — bu, sunucu biraz yoğunken
    // aramanın "bazen hızlı bazen çok yavaş" olmasının ana sebebiydi. Bu
    // fonksiyon, sonucu ÖNCEDEN HESAPLANMIŞ bir sütuna (name_std) yazıp
    // indeksliyor — böylece her arama, bu ağır hesaplamayı tekrar tekrar
    // yapmak yerine hazır veriyi okuyor. Sunucu her başladığında çalışır ama
    // sütun zaten varsa ANINDA çıkar (tekrar tekrar migration yapmaz).
    // 🏀 KÖPRÜ OYUNCUSU DOĞRULAMA (basketbol) — futboldaki verifyPlayerPlayedForClub
    // ile AYNI mantık (isim eşleşmesi + en geç sınırdan önce/o anda katılım
    // kontrolü), ama UYRUK ŞARTI YOK — çünkü bb_players/nba_players tablolarında
    // uyruk verisi hiç bulunmuyor (kaynak veride yok, uydurmuyoruz).
    // 🎯 YENİ: soru göstermeden ÖNCE, GERÇEKTEN çözülebilir olduğunu doğrulamak
    // için — verilen takımda, hedef yıl aralığıyla örtüşen sezonu olan,
    // start/end OYUNCULARININ KENDİSİ OLMAYAN başka biri var mı diye arıyor.
    // Varsa, o oyuncunun ismini döndürüyor (soru kesin çözülebilir demektir).
    fun findAnyBridgeCandidate(teamName: String, league: String, minYear: Int, maxYear: Int, excludeNames: List<String>): String? {
        val teamStd = teamName.toStandardSearch()
        val isNba = league == "nba"
        val tableName = if (isNba) "nba_players" else "bb_players"
        val teamColumn = "team_name_std"
        val seasonColumn = if (isNba) "season" else "season_code"
        val excludeNorm = excludeNames.map { stripAccentsForCompare(it) }.toSet()

        try {
            withBbConnection { conn ->
                val sql = "SELECT DISTINCT name, $seasonColumn FROM $tableName WHERE $teamColumn LIKE ? LIMIT 300"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "%$teamStd%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pName = rs.getString("name") ?: continue
                            if (stripAccentsForCompare(pName) in excludeNorm) continue
                            val rawSeason = rs.getString(seasonColumn)
                            val seasonYear = Regex("(19|20)\\d{2}").find(rawSeason ?: "")?.value?.toIntOrNull() ?: continue
                            if (seasonYear in (minYear - 1)..(maxYear + 1)) {
                                return@withBbConnection pName
                            }
                        }
                    }
                }
                null
            }?.let { return it }
        } catch (e: Exception) {
            println("findAnyBridgeCandidate HATASI: ${e.message}")
        }
        return null
    }

    fun verifyBasketballPlayerPlayedForTeam(playerName: String, teamName: String, league: String, startYear: Int? = null, endYear: Int? = null): Boolean {
        val cleanName = playerName.trim()
        if (cleanName.isEmpty()) return false
        val targetNorm = stripAccentsForCompare(cleanName)
        val teamStd = teamName.toStandardSearch()
        val isNba = league == "nba"
        val tableName = if (isNba) "nba_players" else "bb_players"
        val teamColumn = "team_name_std"
        val seasonColumn = if (isNba) "season" else "season_code"

        // 🎯 KESİN DÜZELTME: basketbol verisi futboldan FARKLI — her SEZON için
        // ayrı kayıt var (futbolda sadece TRANSFER olayı vardı). Bu yüzden
        // "en erken hangi yıl katıldı, o yıldan sonra hep orada kaldı" diye
        // VARSAYMAK yerine, oyuncunun GERÇEKTEN o takımda olduğu TÜM sezonları
        // topluyoruz ve hedef aralıkla GERÇEKTEN örtüşüyor mu diye bakıyoruz —
        // araya bir ayrılık girmiş olsa bile (örn. Teodosić 2017'de CSKA'dan
        // ayrılmışsa ve soru 2018-2020 arasını soruyorsa), artık YANLIŞLIKLA
        // kabul edilmiyor.
        val targetYears = listOfNotNull(startYear, endYear)
        val minTarget = targetYears.minOrNull()
        val maxTarget = targetYears.maxOrNull()

        var found = false
        val playerSeasonYears = mutableSetOf<Int>()

        try {
            withBbConnection { conn ->
                val sql = "SELECT name, $teamColumn, $seasonColumn FROM $tableName WHERE name_std LIKE ? LIMIT 60"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "%$targetNorm%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pName = rs.getString("name") ?: continue
                            val nameNorm = stripAccentsForCompare(pName)
                            val words = nameNorm.trim().split(Regex("\\s+"))
                            val surnameNorm = words.lastOrNull() ?: ""
                            val firstNameNorm = words.firstOrNull() ?: ""
                            val isNicknameMatch = targetNorm.length >= 4 &&
                                (firstNameNorm.startsWith(targetNorm) || surnameNorm.startsWith(targetNorm))
                            if (nameNorm != targetNorm && surnameNorm != targetNorm && !isNicknameMatch) continue

                            val rowTeam = rs.getString(teamColumn) ?: ""
                            if (!(rowTeam.contains(teamStd) || teamStd.contains(rowTeam))) continue

                            found = true
                            val rawSeason = rs.getString(seasonColumn)
                            val seasonYear = Regex("(19|20)\\d{2}").find(rawSeason ?: "")?.value?.toIntOrNull() ?: 0
                            if (seasonYear > 0) playerSeasonYears.add(seasonYear)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("verifyBasketballPlayerPlayedForTeam HATASI: ${e.message}")
        }

        // 🎯 Yıl şartı yoksa (nadir), sadece isim+takım eşleşmesi yeterli.
        if (minTarget == null || maxTarget == null) {
            return found
        }
        // 🎯 GERÇEK ÖRTÜŞME KONTROLÜ: oyuncunun kayıtlı sezonlarından EN AZ
        // biri, hedef aralığın İÇİNDE (ya da 1 yıl toleransla sınırlarında) mı?
        // Bu, "araya bir ayrılık girmiş" durumları doğru şekilde reddediyor.
        val hasOverlap = playerSeasonYears.any { year -> year in (minTarget - 1)..(maxTarget + 1) }
        val joinedInTime = hasOverlap
        return found && joinedInTime
    }

    fun ensureNameStdColumn() {
        try {
            withConnection { conn ->
                var columnExists = false
                conn.prepareStatement("PRAGMA table_info(players)").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            if (rs.getString("name") == "name_std") columnExists = true
                        }
                    }
                }

                if (columnExists) {
                    // 🛡️ DÜZELTME: sütun VAR diye migrasyonun TAM tamamlandığı anlamına
                    // gelmiyor — yerel geliştirmede sunucu, migrasyon bitmeden
                    // durdurulmuşsa, sütun eklenmiş ama İÇİ BOŞ (NULL) kalmış
                    // olabilir. Bu durumda TÜM aramalar sessizce boş dönerdi
                    // (hata vermeden). Doldurulup doldurulmadığını kontrol edip,
                    // gerekirse EKSİK KALAN kısmı tamamlıyoruz.
                    var nullCount = 0
                    conn.prepareStatement("SELECT COUNT(*) as cnt FROM players WHERE name_std IS NULL").use { stmt ->
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) nullCount = rs.getInt("cnt")
                        }
                    }
                    if (nullCount == 0) {
                        println("✅ name_std sütunu zaten mevcut ve dolu, performans migrasyonu atlanıyor.")
                        return@withConnection
                    }
                    println("⚠️ name_std sütunu var ama $nullCount satır BOŞ (yarım kalmış migrasyon) — tamamlanıyor...")
                    conn.createStatement().use { stmt ->
                        stmt.execute("UPDATE players SET name_std = ${sqlAccentStripExpr("name")} WHERE name_std IS NULL")
                    }
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_players_name_std ON players(name_std)")
                    }
                    println("✅ name_std eksik kısmı tamamlandı.")
                    return@withConnection
                }

                println("⏳ name_std sütunu ekleniyor ve dolduruluyor (tek seferlik, biraz sürebilir)...")
                val startTime = System.currentTimeMillis()

                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER TABLE players ADD COLUMN name_std TEXT")
                }
                conn.createStatement().use { stmt ->
                    stmt.execute("UPDATE players SET name_std = ${sqlAccentStripExpr("name")}")
                }
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_players_name_std ON players(name_std)")
                }

                val elapsed = System.currentTimeMillis() - startTime
                println("✅ name_std migrasyonu tamamlandı (${elapsed}ms).")
            }
        } catch (e: Exception) {
            // 🛡️ Migrasyon başarısız olursa uygulama ÇÖKMESİN — sadece log basıp
            // devam ediyoruz, mevcut (daha yavaş ama çalışan) sorgu yolu hâlâ geçerli.
            println("🔥 ensureNameStdColumn HATASI: ${e.message}")
        }
    }

    // 🏀 KESİN DÜZELTME: futboldaki AYNI, KANITLANMIŞ mimariyi basketbola da
    // uyguluyoruz — aksan temizleme, HER aramada CANLI olarak (iç içe REPLACE
    // zinciriyle) hesaplanmak yerine, sunucu başlarken BİR KEZ hesaplanıp
    // "name_std" sütununa yazılıyor ve indeksleniyor. Bu, hem çok daha hızlı
    // (her arama artık hazır sütuna bakıyor) hem de güvenli — canlı sorgularda
    // karmaşık ifade riski tamamen ortadan kalkıyor.
    private fun ensureBbNameStdColumn(tableName: String) {
        try {
            withBbConnection { conn ->
                var columnExists = false
                conn.prepareStatement("PRAGMA table_info($tableName)").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            if (rs.getString("name") == "name_std") columnExists = true
                        }
                    }
                }

                if (!columnExists) {
                    conn.createStatement().use { stmt ->
                        stmt.execute("ALTER TABLE $tableName ADD COLUMN name_std TEXT")
                    }
                }

                var nullCount = 0
                conn.prepareStatement("SELECT COUNT(*) as cnt FROM $tableName WHERE name_std IS NULL").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) nullCount = rs.getInt("cnt")
                    }
                }
                if (nullCount == 0) {
                    println("✅ $tableName.name_std zaten mevcut ve dolu, migrasyon atlanıyor.")
                    return@withBbConnection
                }

                // 🎯 KESİN DÜZELTME: SQL'in karmaşık REPLACE() zinciri yerine —
                // ki bu, "š" gibi Slav harflerini eklemeye çalışınca ayrıştırıcıyı
                // çökertmişti — hesaplamayı TAMAMEN Kotlin'de yapıyoruz. Kotlin'in
                // stripAccentsForCompare fonksiyonu, Unicode NFD normalizasyonu
                // sayesinde İSPANYOLCA, SIRPÇA/HIRVATÇA (š, č, ž DAHİL) ve daha
                // fazla dildeki aksanlı harfi ZATEN doğru şekilde işliyor — SQL'e
                // hiçbir karmaşık ifade yazmadan, satır satır güncelliyoruz.
                // 🛠️ DÜZELTME: bu tablolarda "rowid" erişilebilir değilmiş
                // (muhtemelen WITHOUT ROWID tablo) — bunun yerine DOĞRUDAN İSİM
                // ile güncelliyoruz. Aynı isimli birden fazla satır olsa bile,
                // hepsi zaten AYNI doğru değeri alacağı için bu tamamen güvenli.
                println("⏳ $tableName.name_std hesaplanıyor (Kotlin tarafında, isme göre)...")
                val startTime = System.currentTimeMillis()
                val distinctNames = mutableSetOf<String>()
                conn.prepareStatement("SELECT DISTINCT name FROM $tableName WHERE name_std IS NULL").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("name")
                            if (!name.isNullOrBlank()) distinctNames.add(name)
                        }
                    }
                }
                conn.autoCommit = false
                try {
                    conn.prepareStatement("UPDATE $tableName SET name_std = ? WHERE name = ?").use { stmt ->
                        for (name in distinctNames) {
                            stmt.setString(1, stripAccentsForCompare(name))
                            stmt.setString(2, name)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                } finally {
                    conn.autoCommit = true
                }
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_${tableName}_name_std ON $tableName(name_std)")
                }
                val elapsed = System.currentTimeMillis() - startTime
                println("✅ $tableName.name_std migrasyonu tamamlandı (${distinctNames.size} benzersiz isim, ${elapsed}ms).")
            }
        } catch (e: Exception) {
            println("🔥 ensureBbNameStdColumn($tableName) HATASI: ${e.message}")
        }
    }

    fun ensureBasketballNameStdColumns() {
        ensureBbNameStdColumn("bb_players")
        ensureBbNameStdColumn("nba_players")
    }

    fun fetchPlayerNameSuggestions(query: String, contextClubs: List<String> = emptyList()): List<String> {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 3) return emptyList()
        val targetNorm = stripAccentsForCompare(cleanQuery)
        val resolvedContextClubs = contextClubs.map { resolveClubSearchTerm(it) }
        val sql = """
            SELECT p.id, p.name, t.from_club, t.to_club, COUNT(t.transfer_id) OVER (PARTITION BY p.id) as transfer_count
            FROM (
                SELECT id, name FROM players
                WHERE name_std LIKE ?
                LIMIT 40
            ) p
            LEFT JOIN transfers t ON p.id = t.transfer_id
            ORDER BY transfer_count DESC
        """.trimIndent()
        data class Cand(val name: String, val count: Int, var contextMatch: Boolean)

        fun runQuery(likePattern: String, candidates: MutableMap<Int, Cand>) {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, likePattern)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val name = rs.getString("name") ?: continue
                            val cleanName = name.replace(Regex("\\s*\\(\\d+\\)\\s*"), "").trim()
                            val nameNorm = stripAccentsForCompare(cleanName)
                            if (!nameNorm.contains(targetNorm)) continue

                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val clubMatch = resolvedContextClubs.any { c ->
                                matchesOriginalClub(fromClub, c) || matchesOriginalClub(toClub, c)
                            }

                            val existing = candidates[pId]
                            if (existing == null) {
                                candidates[pId] = Cand(cleanName, rs.getInt("transfer_count"), clubMatch)
                            } else if (clubMatch) {
                                existing.contextMatch = true
                            }
                        }
                    }
                }
            }
        }

        val candidates = mutableMapOf<Int, Cand>()
        try {
            runQuery("%$targetNorm%", candidates)
        } catch (e: Exception) {
            println("fetchPlayerNameSuggestions HATASI: ${e.message}")
        }
        return candidates.values
            .sortedWith(compareByDescending<Cand> { it.contextMatch }.thenByDescending { it.count })
            .distinctBy { it.name }
            .take(12)
            .map { it.name }
    }

    fun fetchPlayerBasicInfoByName(name: String, contextClubs: List<String> = emptyList()): PlayerBasicInfo? {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return null
        val targetNorm = stripAccentsForCompare(cleanName)

        val sql = """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate,
                   t.from_club, t.to_club,
                   (SELECT COUNT(*) FROM transfers t2 WHERE t2.transfer_id = p.id) as transfer_count
            FROM (
                SELECT id, name, position, nationality, birthdate FROM players
                WHERE name_std LIKE ?
                LIMIT 30
            ) p
            LEFT JOIN transfers t ON p.id = t.transfer_id
        """.trimIndent()

        data class Candidate(val id: Int, val name: String, val position: String?, val nationality: String, val birthDate: String?, val transferCount: Int, var matchesClub: Boolean)
        val candidates = mutableMapOf<Int, Candidate>()

        try {
            fun tryPattern(likePattern: String) {
                withConnection { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, likePattern)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val pId = rs.getInt("id")
                                val pName = rs.getString("name") ?: continue
                                val nameNorm = stripAccentsForCompare(pName.replace(Regex("\\s*\\(\\d+\\)\\s*"), ""))
                                val words = nameNorm.trim().split(Regex("\\s+"))
                                val surnameNorm = words.lastOrNull() ?: ""
                                if (nameNorm != targetNorm && surnameNorm != targetNorm) continue

                                val fromClub = rs.getString("from_club") ?: ""
                                val toClub = rs.getString("to_club") ?: ""
                                val clubMatch = contextClubs.any { c ->
                                    val resolved = resolveClubSearchTerm(c)
                                    matchesOriginalClub(fromClub, resolved) || matchesOriginalClub(toClub, resolved)
                                }

                                val existing = candidates[pId]
                                if (existing == null) {
                                    candidates[pId] = Candidate(
                                        id = pId, name = pName,
                                        position = rs.getString("position"),
                                        nationality = cleanNationalityText(rs.getString("nationality") ?: ""),
                                        birthDate = rs.getString("birthdate"),
                                        transferCount = rs.getInt("transfer_count"),
                                        matchesClub = clubMatch
                                    )
                                } else if (clubMatch) {
                                    existing.matchesClub = true
                                }
                            }
                        }
                    }
                }
            }

            tryPattern("%$targetNorm%")
        } catch (e: Exception) {
            println("fetchPlayerBasicInfoByName HATASI: ${e.message}")
            return null
        }

        if (candidates.isEmpty()) return null

        val best = candidates.values
            .sortedWith(compareByDescending<Candidate> { it.matchesClub }.thenByDescending { it.transferCount })
            .first()

        return PlayerBasicInfo(
            name = best.name,
            position = best.position,
            nationality = best.nationality,
            birthDate = best.birthDate
        )
    }

    fun fetchPlayerAcrossClubs(terms: List<Pair<String, Boolean>>, minYear: Int? = null, seed: Long? = null): MultiClubPlayerResult? {
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
                SELECT p.id, p.name, p.position, p.nationality, p.birthdate, p.image_url, p.slug, t.from_club, t.to_club, t.season
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

        val playerTermSeasons = mutableMapOf<Int, MutableMap<String, String>>()
        val playerNames = mutableMapOf<Int, String>()
        val playerPositions = mutableMapOf<Int, String>()
        val playerImageUrls = mutableMapOf<Int, String?>()
        val playerNationalities = mutableMapOf<Int, String>()
        val playerBirthDates = mutableMapOf<Int, String?>()
        val playerSlugs = mutableMapOf<Int, String?>()

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
                            val birthDate = rs.getString("birthdate")
                            val slug = rs.getString("slug")
                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val season = rs.getString("season") ?: continue

                            playerNames[pId] = name
                            playerPositions[pId] = position
                            playerImageUrls[pId] = imageUrl
                            playerNationalities[pId] = cleanNationalityText(rawNat)
                            playerBirthDates[pId] = birthDate
                            playerSlugs[pId] = slug

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
            println("fetchPlayerAcrossClubs HATASI: ${e.message}")
            return null
        }

        var fullMatches = playerTermSeasons.filter { (_, m) -> terms.all { (term, _) -> m.containsKey(term) } }

        if (minYear != null) {
            val yearFiltered = fullMatches.filter { (_, m) ->
                m.values.any { season -> season != "-" && parseSeasonToSortValue(season) >= minYear }
            }
            if (yearFiltered.isNotEmpty()) fullMatches = yearFiltered
        }

        if (fullMatches.isEmpty()) return null

        // 🎯 KESİN DÜZELTME: Günün Sorusu için aynı gün herkese AYNI iki takım
        // çıkıyordu (tarihe göre seçildiği için), ama HANGİ OYUNCU olduğu bu
        // satırda TAMAMEN rastgeleydi — bu yüzden aynı takımlar, farklı
        // kullanıcılarda (hatta aynı kullanıcının farklı denemelerinde) farklı
        // oyuncu/yıl olarak çıkabiliyordu. Artık seed verilmişse (Günün Sorusu
        // bunu gönderiyor), sıralı bir liste üzerinden SEED'E GÖRE SABİT bir
        // seçim yapıyoruz — herkes gerçekten AYNI soruyu görüyor.
        val sortedEntries = fullMatches.entries.sortedBy { it.key }
        val (chosenId, termSeasonMap) = if (seed != null && sortedEntries.isNotEmpty()) {
            val idx = ((seed % sortedEntries.size) + sortedEntries.size) % sortedEntries.size
            sortedEntries[idx.toInt()].let { it.key to it.value }
        } else {
            fullMatches.entries.random().let { it.key to it.value }
        }
        val playerName = playerNames[chosenId] ?: return null
        val position = playerPositions[chosenId] ?: ""

        return MultiClubPlayerResult(
            playerName = playerName,
            position = position,
            clubs = terms.map { (term, _) -> ClubSeason(term, termSeasonMap[term] ?: "-") },
            imageUrl = playerImageUrls[chosenId],
            nationality = playerNationalities[chosenId],
            birthDate = playerBirthDates[chosenId],
            playerId = chosenId,
            slug = playerSlugs[chosenId]
        )
    }

    private fun cleanNationalityText(rawNat: String): String {
        return rawNat.replace('\u00a0', ' ')
            .replace(160.toChar(), ' ')
            .replace(Regex("Mevki|Uyruk|[0-9()]+"), "")
            .trim()
    }
}
