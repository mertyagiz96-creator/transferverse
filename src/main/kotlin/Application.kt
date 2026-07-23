import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.sql.DriverManager
import com.google.gson.GsonBuilder

fun main() {
    // EĞER transfers.json dosyası henüz yoksa, football.db'den otomatik oluştur!
    val jsonCheckFile = File("src/main/resources/transfers.json")
    if (!jsonCheckFile.exists()) {
        generateJsonExplicitly()
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json()
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

// Bu fonksiyon football.db'yi okuyup JSON dosyasını oluşturur
fun generateJsonExplicitly() {
    val dbFile = File("src/main/resources/football.db")
    if (!dbFile.exists()) {
        println("HATA: src/main/resources/ içinde football.db dosyası bulunamadı!")
        return
    }

    val records = mutableListOf<DatabaseClient.TransferRecord>()
    val sql = """
        SELECT p.id, p.name, p.position, p.nationality, p.birthdate, t.from_club, t.to_club, t.season, t.transfer_id 
        FROM transfers t 
        JOIN players p ON p.id = t.transfer_id
    """.trimIndent()

    try {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        records.add(
                            DatabaseClient.TransferRecord(
                                playerId = rs.getInt("id"),
                                playerName = rs.getString("name") ?: "",
                                position = rs.getString("position") ?: "",
                                nationality = rs.getString("nationality") ?: "",
                                birthDate = rs.getString("birthdate"),
                                fromClub = rs.getString("from_club") ?: "",
                                toClub = rs.getString("to_club") ?: "",
                                season = rs.getString("season") ?: "",
                                transferId = rs.getInt("transfer_id")
                            )
                        )
                    }
                }
            }
        }

        val jsonFile = File("src/main/resources/transfers.json")
        val gson = GsonBuilder().create()
        jsonFile.writeText(gson.toJson(records), Charsets.UTF_8)
        println("BAŞARILI: transfers.json oluşturuldu! Toplam kayıt: ${records.size}")
    } catch (e: Exception) {
        e.printStackTrace()
    }
}