import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Serializable data class CreateRoomRequest(val playerName: String, val winTarget: Int = 5, val maskingHintEnabled: Boolean = false, val duelMode: String = "genel")
@Serializable data class JoinRoomRequest(val roomCode: String, val playerName: String)
@Serializable data class DuelAnswerRequest(val roomCode: String, val playerName: String, val guess: String)
@Serializable data class DuelPassRequest(val roomCode: String, val playerName: String)
@Serializable data class NextRoundRequest(val roomCode: String)
@Serializable data class QuizScoreSubmission(val score: Int, val mode: String = "genel")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    // 🚀 Sunucu ayağa kalkarken, basketbol logolarını arka planda (sunucuyu
    // hiç bekletmeden) önceden yükleyip önbelleğe alıyoruz.
    GlobalScope.launch {
        DatabaseClient.preloadAllBasketballLogos()
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }

        routing {
            staticResources("/", "static", index = "index.html")

            get("/suggestions") {
                val suggestions = DatabaseClient.fetchAllUniqueSuggestions()
                call.respond(suggestions)
            }

            // 🏀 Basketbol — futboldan tamamen ayrı, izole uç noktalar.
            get("/basketball/suggestions") {
                val suggestions = DatabaseClient.fetchAllBasketballSuggestions()
                call.respond(suggestions)
            }

            get("/basketball/commonPlayers") {
                val team1 = call.request.queryParameters["team1"]
                val team2 = call.request.queryParameters["team2"]
                if (team1.isNullOrBlank() || team2.isNullOrBlank()) {
                    call.respond(emptyList<DatabaseClient.BasketballPlayerResult>())
                    return@get
                }
                val common = DatabaseClient.fetchCommonBasketballPlayers(team1, team2)
                call.respond(common)
            }

            // 🎲 Bil Bakalım rastgele soru — TÜM deneme mantığı tek istekte,
            // sunucu içinde. İstemci sadece hangi ligi istediğini söylüyor.
            get("/basketball/randomQuestion") {
                val league = call.request.queryParameters["league"] ?: "europe"
                val isNba = league == "nba"
                val poolStart = System.currentTimeMillis()
                val pool = if (isNba) DatabaseClient.fetchAllNbaSuggestions() else DatabaseClient.fetchAllBasketballSuggestions()
                val poolElapsed = System.currentTimeMillis() - poolStart
                if (poolElapsed > 500) {
                    println("⏱️ havuz hesaplama YAVAŞ (${if (isNba) "NBA" else "Avrupa"}): ${poolElapsed}ms, ${pool.size} takım")
                }
                val result = DatabaseClient.fetchRandomBasketballQuestion(pool, isNba)
                call.respond(result)
            }

            // 🏀 NBA — Avrupa basketbolundan ayrı endpoint'ler.
            get("/nba/suggestions") {
                call.respond(DatabaseClient.fetchAllNbaSuggestions())
            }

            get("/nba/commonPlayers") {
                val team1 = call.request.queryParameters["team1"]
                val team2 = call.request.queryParameters["team2"]
                if (team1.isNullOrBlank() || team2.isNullOrBlank()) {
                    call.respond(emptyList<DatabaseClient.BasketballPlayerResult>())
                    return@get
                }
                val common = DatabaseClient.fetchCommonNbaPlayers(team1, team2)
                call.respond(common)
            }

            // 🖼️ Basketbol logo/foto — sunucu üzerinden TheSportsDB'ye gidiyor,
            // tarayıcıdaki CORS sorununu tamamen bypass ediyor.
            get("/basketball/teamLogo") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(mapOf("logo" to null))
                    return@get
                }
                val logo = DatabaseClient.fetchBasketballTeamLogo(name)
                call.respond(mapOf("logo" to logo))
            }

            // ⚽ Futbol kulüp logosu yedek sistemi — club_logos'ta olmayan kulüpler için
            get("/football/teamLogoFallback") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(mapOf("logo" to null))
                    return@get
                }
                val logo = DatabaseClient.fetchFootballTeamLogoFallback(name)
                call.respond(mapOf("logo" to logo))
            }

            get("/basketball/playerPhoto") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(mapOf("photo" to null))
                    return@get
                }
                val photo = DatabaseClient.fetchBasketballPlayerPhoto(name)
                call.respond(mapOf("photo" to photo))
            }

            // 🖼️ YENİ: Kulüp logoları — frontend sayfa yüklenirken bir kez çekip
            // hafızada tutuyor, her istek için tekrar tekrar sormuyor.
            get("/clubLogos") {
                val logos = DatabaseClient.fetchAllClubLogos()
                call.respond(logos)
            }

            get("/basketball/allLogos") {
                val logos = DatabaseClient.fetchAllBasketballLogos()
                call.respond(logos)
            }

            // 🏆 Bil Bakalım rekoru — mod bazlı (Genel/Türkiye/NBA/Avrupa Basketbolu)
            get("/quiz/highscore") {
                val mode = call.request.queryParameters["mode"] ?: "genel"
                call.respond(mapOf("highScore" to DatabaseClient.fetchQuizHighScore(mode)))
            }

            post("/quiz/highscore") {
                val body = call.receive<QuizScoreSubmission>()
                val newRecord = DatabaseClient.submitQuizScore(body.mode, body.score)
                call.respond(mapOf("highScore" to newRecord))
            }

            get("/players") {
                val clubParam = call.request.queryParameters["club"]
                if (clubParam.isNullOrBlank()) {
                    call.respond(emptyList<Player>())
                    return@get
                }
                val players = DatabaseClient.fetchPlayersByClub(clubParam)
                call.respond(players)
            }

            get("/commonPlayers") {
                val club1 = call.request.queryParameters["club1"]
                val club2 = call.request.queryParameters["club2"]

                if (club1.isNullOrBlank() || club2.isNullOrBlank()) {
                    call.respond(emptyList<Player>())
                    return@get
                }
                val common = DatabaseClient.fetchCommonPlayers(club1, club2)
                call.respond(common)
            }

            // 💡 YENİ: "Oyuncu Modu" — mevcut 3 endpoint'e hiç dokunulmadı, bu tamamen
            // ayrı/ek bir uç nokta. Verilen kulüplerin (virgülle ayrılmış) HEPSİNDE
            // gerçekten oynamış rastgele bir oyuncuyu döndürür, yoksa 404 döner.
            get("/playerMode") {
                val clubsParam = call.request.queryParameters["clubs"]
                if (clubsParam.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                val clubs = clubsParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (clubs.size < 2) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                // 💡 YENİ: ?isCountry=0,1 gibi paralel bir bayrak listesi — hangi terimin
                // ülke, hangisinin kulüp olduğunu belirtir. Verilmezse hepsi kulüp sayılır
                // (geriye dönük uyumluluk).
                val isCountryParam = call.request.queryParameters["isCountry"]
                val isCountryFlags = isCountryParam?.split(",")?.map { it.trim() == "1" }

                val terms = clubs.mapIndexed { idx, term ->
                    term to (isCountryFlags?.getOrNull(idx) ?: false)
                }

                val result = DatabaseClient.fetchPlayerAcrossClubs(terms)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(result)
                }
            }

            // 🏆 YENİ: Arkadaşla Yarış (Duel) modu — tamamen ayrı, mevcut hiçbir
            // endpoint'e dokunmuyor. Polling tabanlı basit gerçek-zamanlı yarışma.
            post("/duel/create") {
                val body = call.receive<CreateRoomRequest>()
                val room = DuelManager.createRoom(body.playerName, body.winTarget, body.maskingHintEnabled, body.duelMode)
                call.respond(DuelManager.toState(room))
            }

            post("/duel/join") {
                val body = call.receive<JoinRoomRequest>()
                when (val result = DuelManager.joinRoom(body.roomCode, body.playerName)) {
                    is JoinResult.Success -> call.respond(DuelManager.toState(result.room))
                    is JoinResult.RoomFull -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Bu oda dolu"))
                    is JoinResult.RoomNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                }
            }

            get("/duel/state") {
                val code = call.request.queryParameters["roomCode"] ?: ""
                val playerName = call.request.queryParameters["playerName"]
                val room = DuelManager.getRoom(code, playerName)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(DuelManager.toState(room))
                }
            }

            post("/duel/answer") {
                val body = call.receive<DuelAnswerRequest>()
                val result = DuelManager.submitAnswer(body.roomCode, body.playerName, body.guess)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(result)
                }
            }

            post("/duel/pass") {
                val body = call.receive<DuelPassRequest>()
                val state = DuelManager.submitPass(body.roomCode, body.playerName)
                if (state == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(state)
                }
            }

            post("/duel/next") {
                val body = call.receive<NextRoundRequest>()
                val room = DuelManager.nextRound(body.roomCode)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(DuelManager.toState(room))
                }
            }

            post("/duel/rematch") {
                val body = call.receive<NextRoundRequest>()
                val room = DuelManager.rematch(body.roomCode)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(DuelManager.toState(room))
                }
            }
        }
    }.start(wait = true)
}
