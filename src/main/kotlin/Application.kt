import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
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

            // 📅 Sunucunun kendi tarihini döndürür — Günün Sorusu artık cihazın
            // kendi saatine değil, buna göre hesaplanıyor. Böylece farklı saat
            // dilimindeki/yanlış saatli cihazlar bile HERKESE AYNI günü görür.
            get("/serverDate") {
                // 💡 DÜZELTME: UTC yerine Türkiye saati (Europe/Istanbul) kullanıyoruz —
                // hedef kitlemiz Türkiye olduğu için "gün değişimi" onların gece
                // yarısına göre olmalı, UTC'ye göre değil (UTC 3 saat GERİDE kalıyordu).
                val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).toString()
                call.respond(mapOf("date" to today))
            }

            // 📰 BLOG — gerçek, sunucu tarafında oluşturulan HTML sayfaları (JS'e
            // bağımlı değil), arama motoru botlarının tarayıp indeksleyebilmesi için.
            val blogArticles = mapOf(
                "de-gea-fax-makinesi-transferi-batirdi" to Pair(
                    "Bir Fax Makinesi Yüzünden Batan Dev Transfer",
                    """
                    <p>2015 yaz transfer döneminin son gecesi, futbol tarihinin en garip anlarından birine sahne oldu. 
                    Real Madrid ve Manchester United, kaleci David de Gea'nın Madrid'e, Keylor Navas'ın ise 
                    karşılığında Manchester'a gitmesi için anlaşmaya varmıştı. Her şey hazırdı, imzalar atılacaktı — 
                    ama gecenin geç saatlerinde, İspanyol Ligi'nin transfer sistemi (TMS) üzerinden gönderilmesi 
                    gereken evraklar bir türlü karşı tarafa ulaşmadı.</p>
                    <p>United, evrakları saat kapanmadan önce gönderdiğini iddia ederken, Real Madrid evrakların 
                    eksik/yanlış formatta geldiğini öne sürdü. Bazı kaynaklara göre belgeler tam da gece yarısından 
                    2 dakika sonra sisteme düştü — yani transfer penceresi kapandıktan hemen sonra. İki kulüp de 
                    birbirini suçlayan resmi açıklamalar yaptı, olay günlerce basında "fax makinesi skandalı" 
                    olarak konuşuldu.</p>
                    <p>Sonuç: De Gea, hayalindeki Real Madrid transferini kaçırdı ve Manchester United'da kalmaya 
                    devam etti. İlginç bir şekilde bu "kayıp" transfer, kaleci için kötü sonuçlanmadı — United'da 
                    sekiz sezon daha forma giyip kulübün tarihindeki en çok maça çıkan oyunculardan biri oldu.</p>
                    <p>Bazen milyonlarca euroluk bir transferi, teknolojik bir aksaklık ya da birkaç dakikalık bir 
                    gecikme belirleyebiliyor. TransferKolik'te "Manchester United" ve "Real Madrid" aratarak, 
                    bu iki kulüpte gerçekten forma giymiş oyuncuların tam listesini görebilirsiniz.</p>
                    """.trimIndent()
                ),
                "alexis-sanchez-mourinho-telefon-hamlesi" to Pair(
                    "Mourinho'nun Telefon Açıp Çaldığı Transfer",
                    """
                    <p>Ocak 2018'de Alexis Sanchez, Arsenal'den ayrılmaya kararlıydı ve gözü Manchester City'deydi 
                    — eski hocası Pep Guardiola ile yeniden bir araya gelmek istiyordu. Şili'li yıldız, City ile 
                    neredeyse her gün mesajlaşıyor, kişisel şartlarda anlaşmışlardı. Herkes, bu transferin sadece 
                    bir formalite meselesi olduğunu düşünüyordu.</p>
                    <p>Ama tam o sırada telefonu çaldı. Arayan, Manchester United teknik direktörü Jose Mourinho'ydu. 
                    Sanchez'in kendi ifadesiyle: "Mourinho bana dedi ki: Alexis, işte sana 7 numaralı forma hazır." 
                    Bu basit ama etkili teklif, City ile neredeyse tamamlanmış olan anlaşmayı bir anda gölgede 
                    bıraktı.</p>
                    <p>City, Sanchez'in menajerinin son anda daha yüksek ücret talep etmesi üzerine görüşmelerden 
                    çekildi — bu da United'a alanı tamamen açtı. United, Henrikh Mkhitaryan'ı da işin içine katarak 
                    takas şeklinde bir anlaşma yaptı ve City'nin gözdesini son anda kapmayı başardı.</p>
                    <p>Ama bu "zafer" United için kısa sürede hayal kırıklığına dönüştü — Sanchez, kulüpte geçirdiği 
                    19 ay boyunca sadece 5 gol atabildi ve kariyerinin en kötü dönemini yaşadı. Bazen bir transferi 
                    "kazanmak", o transferi başarılı kılmıyor. TransferKolik'te "Arsenal" ve "Manchester United" 
                    aratarak, bu iki kulüpte de forma giymiş diğer oyuncuları keşfedebilirsiniz.</p>
                    """.trimIndent()
                ),
                "robinho-chelsea-tisortleri-iptal-transfer" to Pair(
                    "Satışa Çıkan Formalar Yüzünden İptal Olan Transfer",
                    """
                    <p>2008 yazında Robinho, Real Madrid'den ayrılmak istiyordu ve Chelsea ile anlaşmaya çok 
                    yakındı — hatta Londra'da yaşamak istediğini bile açıklamıştı. Chelsea o kadar emindi ki, 
                    kulübün resmi mağaza sitesinde Robinho isimli formalar **anlaşma daha resmileşmeden** satışa 
                    sunuldu.</p>
                    <p>Bu detay, Real Madrid Başkanı Ramón Calderón'u çileden çıkardı. Kulübe göre bu, saygısızca 
                    bir davranıştı — sanki transfer çoktan bitmiş gibi hareket ediliyordu. Öfkelenen Calderón, 
                    görüşmeleri tamamen kesti ve Chelsea ile anlaşmayı iptal etti.</p>
                    <p>Tam o sırada, transfer döneminin son günü, Manchester City'nin Abu Dabi Birleşik Grubu 
                    tarafından satın alındığı açıklandı — kulüp bir gecede dünyanın en zengin takımı haline geldi. 
                    Yeni sahipler, hızlıca büyük bir imza atmak istiyordu ve gözlerini Robinho'ya çevirdiler. 
                    Saatler içinde anlaşma tamamlandı — sağlık kontrolü bile yapılmadan.</p>
                    <p>İşin komik tarafı, Robinho tanıtım basın toplantısında yanlışlıkla "Chelsea'nin teklifini 
                    kabul ettim" dedi — bir gazeteci ona gittiği kulübün aslında Manchester City olduğunu 
                    hatırlatmak zorunda kaldı! TransferKolik'te "Real Madrid" ve "Manchester City" aratarak, 
                    bu iki kulüpte de forma giymiş oyuncuların tam listesine ulaşabilirsiniz.</p>
                    """.trimIndent()
                ),
                "robben-united-tesislerini-gezdi-chelseaya-gitti" to Pair(
                    "United'ın Tesislerini Gezip Chelsea'ye İmza Atan Oyuncu",
                    """
                    <p>2004 yazında Arjen Robben, PSV Eindhoven'daki parlak performansının ardından Avrupa'nın 
                    en çok istenen kanat oyuncularından biri haline gelmişti. Manchester United, transferi neredeyse 
                    bitirmişti — Robben, kulübün antrenman tesislerini ziyaret etmiş, kişisel şartlarda anlaşmaya 
                    varmıştı. Herkese göre bu, sadece imza atma meselesiydi.</p>
                    <p>Ama Chelsea, sahneye son anda girdi. Roman Abramovich'in parasal gücüyle desteklenen Londra 
                    kulübü, daha cazip bir teklif sundu — hem Robben'a hem PSV'ye. Hollandalı yıldız, United'a 
                    verdiği sözlü sözden vazgeçip yönünü Stamford Bridge'e çevirdi.</p>
                    <p>Manchester United için bu, gerçek bir hayal kırıklığıydı — kulüp, transferin neredeyse 
                    kesinleştiğini düşünüyordu. Ama Robben'ın kararı doğru çıktı: Chelsea'de geçirdiği sezonlarda 
                    lig şampiyonluğu yaşadı, ardından Real Madrid ve Bayern Münih'te oynayarak kariyerinin zirvesine 
                    ulaştı.</p>
                    <p>Bu hikaye, bir oyuncunun sözlü anlaşmasının bile son ana kadar garanti olmadığını gösteriyor 
                    — özellikle parasal gücü yüksek bir rakip devreye girdiğinde. TransferKolik'te "PSV" ve 
                    "Chelsea" aratarak, bu iki kulüpte de forma giymiş oyuncuları bulabilirsiniz.</p>
                    """.trimIndent()
                )
            )

            fun blogPageHtml(title: String, bodyHtml: String, description: String): String = """
                <!DOCTYPE html>
                <html lang="tr">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>$title | TransferKolik Blog</title>
                    <meta name="description" content="$description">
                    <style>
                        body { font-family: -apple-system, sans-serif; max-width: 720px; margin: 0 auto; padding: 24px 20px 60px; background: #12140f; color: rgba(255,255,255,0.88); line-height: 1.75; }
                        h1 { font-size: 1.6rem; color: #d4af37; margin-bottom: 8px; }
                        h2 { font-size: 1.3rem; color: #d4af37; margin-top: 32px; }
                        a { color: #d4af37; }
                        p { margin-bottom: 16px; font-size: 0.95rem; }
                        .back-link { display: inline-block; margin-right: 16px; margin-bottom: 24px; font-size: 0.85rem; opacity: 0.7; }
                        .article-list a { display: block; padding: 14px 0; border-bottom: 1px solid rgba(255,255,255,0.08); text-decoration: none; font-weight: 700; color: rgba(255,255,255,0.9); }
                    </style>
                </head>
                <body>
                    <a href="/" class="back-link">← TransferKolik Ana Sayfa</a>
                    <a href="/blog" class="back-link">📰 Tüm Yazılar</a>
                    $bodyHtml
                </body>
                </html>
            """.trimIndent()

            get("/blog") {
                val listHtml = blogArticles.entries.joinToString("") { (slug, pair) ->
                    "<a href=\"/blog/$slug\">${pair.first}</a>"
                }
                val html = blogPageHtml(
                    "Transfer Hikayeleri",
                    "<h1>⚽ Transfer Hikayeleri</h1><p>Futbol tarihinin en dramatik, en şaşırtıcı transfer anları.</p><div class=\"article-list\">$listHtml</div>",
                    "Futbol dünyasının en dramatik ve şaşırtıcı transfer hikayeleri — TransferKolik Blog."
                )
                call.respondText(html, ContentType.Text.Html)
            }

            get("/blog/{slug}") {
                val slug = call.parameters["slug"] ?: ""
                val article = blogArticles[slug]
                if (article == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val (title, content) = article
                val html = blogPageHtml(
                    title,
                    "<h1>$title</h1>$content",
                    content.take(150).replace(Regex("<[^>]*>"), "")
                )
                call.respondText(html, ContentType.Text.Html)
            }


            // 📰 Günün Oyuncusu — tarihe göre sabit (herkese aynı gün aynı), gerçek veri
            get("/dailyPlayerBio") {
                val dateSeed = call.request.queryParameters["seed"]?.toIntOrNull() ?: 0
                val bio = DatabaseClient.fetchDailyPlayerBio(dateSeed)
                if (bio == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(bio)
                }
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

            // 🗳️ Şampiyonluk Anketi
            get("/poll/results") {
                call.respond(DatabaseClient.fetchPollResults())
            }

            post("/poll/vote") {
                val league = call.request.queryParameters["league"]
                val team = call.request.queryParameters["team"]
                if (league.isNullOrBlank() || team.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                call.respond(DatabaseClient.submitPollVote(league, team))
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
