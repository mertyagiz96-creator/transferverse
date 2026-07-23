import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }

        // 🚀 Sunucu ayağa kalkar kalkmaz arka planda JSON'ı RAM'e doldurur
        launch(Dispatchers.IO) {
            println("Açılışta önbellekleme başlatılıyor...")
            DatabaseClient.prefetchData()
        }

        routing {
            staticResources("/", "static", index = "index.html")

            get("/suggestions") {
                val suggestions = DatabaseClient.fetchAllUniqueSuggestions()
                call.respond(suggestions)
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
        }
    }.start(wait = true)
}