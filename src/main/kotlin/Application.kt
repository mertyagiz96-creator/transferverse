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

@Serializable data class CreateRoomRequest(val playerName: String, val winTarget: Int = 5)
@Serializable data class JoinRoomRequest(val roomCode: String, val playerName: String)
@Serializable data class DuelAnswerRequest(val roomCode: String, val playerName: String, val guess: String)
@Serializable data class NextRoundRequest(val roomCode: String)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

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

            // 🖼️ YENİ: Kulüp logoları — frontend sayfa yüklenirken bir kez çekip
            // hafızada tutuyor, her istek için tekrar tekrar sormuyor.
            get("/clubLogos") {
                val logos = DatabaseClient.fetchAllClubLogos()
                call.respond(logos)
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

                val result = DatabaseClient.fetchPlayerAcrossClubs(clubs)
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
                val room = DuelManager.createRoom(body.playerName, body.winTarget)
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

            post("/duel/next") {
                val body = call.receive<NextRoundRequest>()
                val room = DuelManager.nextRound(body.roomCode)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(DuelManager.toState(room))
                }
            }
        }
    }.start(wait = true)
}