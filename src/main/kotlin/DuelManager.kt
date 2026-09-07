import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

// 🏆 Arkadaşla Yarış (Duel) modu — polling tabanlı, basit sunucu içi (in-memory)
// oda yönetimi. Veritabanına hiç yazmıyor, sunucu yeniden başlarsa odalar kaybolur
// (2 arkadaşlık casual bir oyun için kabul edilebilir bir sınırlama).

private const val ROUND_DURATION_MS = 30_000L
private const val ROOM_STALE_MS = 2 * 60 * 60 * 1000L // 2 saat hareketsizlik = terk edilmiş say
private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L // 30 dakikada bir kontrol et
private const val OPPONENT_LEFT_THRESHOLD_SECONDS = 8 // ~5-6 kaçırılmış polling turu
private const val EASY_PHASE_ROUND_COUNT = 5 // 🟢 ısınma turu sayısı
private const val MEDIUM_EASY_PHASE_ROUND_COUNT = 7 // 🟡 6-7. turlar "orta-kolay" — bir kolay + bir orta kulüp
private const val MEDIUM_PHASE_ROUND_COUNT = 10 // 🟠 8-10. turlar "orta-zor" — iki orta kulüp, hardClubPool hariç
// 🎯 YENİ: "3,2,1" modu — oyuncuların KENDİ kulüplerini yazdığı, klasik sözlü
// oyunun dijital hali. İki ayrı süre var: kulüp yazma fazı (kısa) ve tahmin
// fazı (biraz daha uzun, çünkü hem düşünmek hem yazmak gerekiyor).
private const val CLUB_ENTRY_DURATION_MS = 30_000L
private const val DUEL_321_GUESS_DURATION_MS = 45_000L

@Serializable
data class DuelClubInfo(val club: String, val season: String)

@Serializable
data class DuelState(
    val roomCode: String,
    val player1Name: String,
    val player2Name: String?,
    val player1Score: Int,
    val player2Score: Int,
    val winTarget: Int,
    val roundNumber: Int,
    val clubs: List<DuelClubInfo>,
    val position: String?,
    val imageUrl: String?,
    val roundOver: Boolean,
    val roundWinner: String?,
    val timedOut: Boolean,
    val remainingSeconds: Int,
    val revealedPlayerName: String?,
    val waitingForOpponent: Boolean,
    val noMatchFound: Boolean,
    val gameOver: Boolean,
    val gameWinner: String?,
    val player1SecondsSinceSeen: Int,
    val player2SecondsSinceSeen: Int,
    val player1Passed: Boolean,
    val player2Passed: Boolean,
    val bothPassed: Boolean,
    val maskingHintEnabled: Boolean,
    val maskedName: String?,
    val isCountryMix: Boolean,
    // 🎯 YENİ: "3,2,1" modu için — diğer modlarda kullanılmıyor (varsayılan değerlerinde kalıyor).
    val duelMode: String = "genel",
    val phase: String = "guessing",
    val club1Submitted: Boolean = false,
    val club2Submitted: Boolean = false
)

@Serializable
data class DuelAnswerResult(val correct: Boolean, val state: DuelState)

// 🚪 Odaya katılma sonucu — "oda yok" ile "oda dolu" farklı, net hatalar olsun diye
sealed class JoinResult {
    data class Success(val room: DuelRoom) : JoinResult()
    object RoomFull : JoinResult()
    object RoomNotFound : JoinResult()
}

class DuelRoom(val roomCode: String, val player1Name: String, val winTarget: Int, val maskingHintEnabled: Boolean, val duelMode: String = "genel") {
    var player2Name: String? = null
    var player1Score = 0
    var player2Score = 0
    var roundNumber = 0
    var currentQuestion: MultiClubPlayerResult? = null
    var roundOver = false
    var roundWinner: String? = null
    var timedOut = false
    var noMatchFound = false
    var roundStartTime: Long = System.currentTimeMillis()
    var gameOver = false
    var gameWinner: String? = null
    var player1LastSeen: Long = System.currentTimeMillis()
    var player2LastSeen: Long = System.currentTimeMillis()
    var lastActivityAt: Long = System.currentTimeMillis()
    var player1Passed = false
    var player2Passed = false
    var bothPassed = false
    val recentPlayerNames = mutableListOf<String>()
    var isCountryMix = false
    val lock = Any()
    // 🎯 YENİ: "3,2,1" modu durumu — diğer modlarda hiç kullanılmıyor.
    var phase: String = "club_entry"
    var club1Input: String? = null
    var club2Input: String? = null
    var clubEntryStartTime: Long = System.currentTimeMillis()
    // 🎯 YENİ: iki kulüp belli olunca BİR KEZ hesaplanan tüm geçerli
    // cevaplar — her "Gönder"de veritabanına gitmeden hafızadan kontrol
    // yapabilmek için.
    var validPlayersForRound: List<DatabaseClient.SimplePlayerMatch> = emptyList()
    // 🎯 YENİ: doğru tahmin bulununca, tur sonucunda göstermek için kazanan
    // oyuncunun (temizlenmiş) ismini burada tutuyoruz — artık tek bir
    // "önceden seçilmiş currentQuestion" olmadığı için gerekli.
    var winningPlayerDisplayName321: String? = null
    // 🎯 YENİ: oyun boyunca (rematch'e kadar) hangi kulüplerin zaten
    // kullanıldığını tutuyoruz — aynı kulübün tekrar girilmesini engellemek için.
    val usedClubsStd321: MutableSet<String> = mutableSetOf()
}

object DuelManager {
    private val rooms = ConcurrentHashMap<String, DuelRoom>()

    init {
        Thread {
            while (true) {
                try {
                    Thread.sleep(CLEANUP_INTERVAL_MS)
                    val cutoff = System.currentTimeMillis() - ROOM_STALE_MS
                    rooms.entries.removeIf { it.value.lastActivityAt < cutoff }
                } catch (e: Exception) {
                    // sessizce bir sonraki turu bekle
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // 💡 Frontend'deki luckyClubs + superLigClubs ile aynı havuz — tutarlılık için
    private val clubPool = listOf(
        "Galatasaray", "Fenerbahce", "Besiktas", "Trabzonspor",
        "Manchester United", "Manchester City", "Liverpool", "Chelsea", "Arsenal", "Tottenham",
        "Real Madrid", "Barcelona", "Atletico Madrid", "Sevilla FC",
        "Juventus", "Inter", "AC Milan", "Napoli", "AS Roma",
        "Bayern Munich", "Borussia Dortmund", "RB Leipzig",
        "Paris SG", "Marseille", "Olympique Lyon",
        "Ajax", "Benfica", "FC Porto",
        "Boca Juniors", "River Plate",
        "Basaksehir", "Adana Demirspor", "Alanyaspor", "Antalyaspor",
        "Caykur Rizespor", "Gaziantep FK", "Goztepe", "Hatayspor",
        "Kasimpasa", "Kayserispor", "Konyaspor", "Samsunspor",
        "Sivasspor", "Eyupspor", "Kocaelispor",
        "Inter Miami", "Al-Ahli", "Beijing Guoan", "Shanghai Port", "Vissel Kobe", "LA Galaxy",
        "Feyenoord", "PSV", "Panathinaikos", "Olympiacos",
        // 🎯 YENİ: eksik oyuncular tamamlandığı için güvenle eklenen 5 kulüp
        "Atalanta", "Bayer Leverkusen", "Villarreal", "Celtic", "Shakhtar Donetsk", "Flamengo", "Dynamo Kyiv"
    )

    // 🔴 "Zor Kulüpler" — sadece en üst kademede (MEDIUM_PHASE_ROUND_COUNT
    // sonrası) devreye giriyor. Frontend'deki Bil Bakalım'ın hardClubs
    // listesiyle AYNI mantık/liste.
    private val hardClubPool = listOf(
        "Inter Miami", "Al-Ahli", "Beijing Guoan", "Shanghai Port", "Vissel Kobe", "LA Galaxy",
        "Feyenoord", "PSV", "Panathinaikos", "Olympiacos",
        "Villarreal", "Celtic", "Shakhtar Donetsk", "Boca Juniors", "River Plate", "Flamengo", "Dynamo Kyiv"
    )
    private val mediumClubPool = clubPool.filter { it !in hardClubPool }

    // 🟢 "Kolay Kulüpler" — frontend'deki Bil Bakalım'daki easyClubs ile AYNI 17
    // kulüp (stadyum verimizin de olduğu, Avrupa'nın en bilindik takımları). Her
    // odanın İLK 3 turu bu havuzdan geliyor — ısınma turu, kimse hemen zor bir
    // soruyla karşılaşıp oyundan soğumasın diye.
    private val easyClubPool = listOf(
        "Galatasaray", "Fenerbahce", "Besiktas",
        "Manchester United", "Manchester City", "Chelsea", "Arsenal", "Liverpool",
        "Real Madrid", "Barcelona", "Atletico Madrid", "Juventus", "AC Milan", "Inter",
        "Bayern Munich", "Borussia Dortmund", "Paris SG", "Ajax"
    )

    // 🇹🇷 "Türkiye Ligi Modu" havuzu (Duel) — frontend'deki solo modla aynı:
    // Süper Lig'in tamamı + Bursaspor. İlk 3 tur, Süper Lig tarihinde şampiyonluk
    // yaşamış SADECE 5 kulüpten (ısınma turu) geliyor.
    private val turkiyeLigiPool = listOf(
        "Galatasaray", "Fenerbahce", "Besiktas", "Trabzonspor",
        "Basaksehir", "Adana Demirspor", "Alanyaspor", "Antalyaspor",
        "Caykur Rizespor", "Gaziantep FK", "Goztepe", "Hatayspor",
        "Kasimpasa", "Kayserispor", "Konyaspor", "Samsunspor",
        "Sivasspor", "Eyupspor", "Kocaelispor", "Bursaspor"
    )
    private val turkiyeChampions = listOf("Galatasaray", "Fenerbahce", "Besiktas", "Trabzonspor", "Bursaspor")
    private const val TURKIYE_CHAMPIONS_ROUND_COUNT = 3

    // 🎯 YENİ: Süper Lig kulüplerinin (havuzda oransal olarak azınlıkta olsa
    // da) soru olarak orantısız sık çıktığı fark edildi — bir soruda Türk
    // kulübü varsa bu soruyu SADECE %70 ihtimalle kabul edip, %30'unda
    // yeniden çekiyoruz. Bu, gözlemlenen oranı kabaca %10 bandına indiriyor.
    private val turkishSuperLigClubs = setOf("Galatasaray", "Fenerbahce", "Besiktas", "Trabzonspor", "Kocaelispor")

    private val countryPool = listOf(
        "Turkiye", "England", "Germany", "France", "Spain", "Italy", "Netherlands", "Portugal",
        "Brazil", "Argentina", "Croatia", "Serbia", "Belgium", "Sweden", "Norway",
        "Cote d'Ivoire", "Morocco", "Egypt", "Nigeria", "Japan", "Korea, South",
        "Scotland", "Wales", "Uruguay", "Colombia", "Mexico"
    )

    fun createRoom(player1Name: String, winTarget: Int, maskingHintEnabled: Boolean, duelMode: String = "genel"): DuelRoom {
        var code: String
        do {
            code = generateCode()
        } while (rooms.containsKey(code))
        val validTarget = if (winTarget == 10) 10 else 5
        val validMode = if (duelMode == "turkiye") "turkiye" else if (duelMode == "321") "321" else "genel"
        val room = DuelRoom(code, player1Name.ifBlank { "Oyuncu 1" }, validTarget, maskingHintEnabled, validMode)
        rooms[code] = room
        return room
    }

    fun joinRoom(code: String, player2NameRaw: String): JoinResult {
        val room = rooms[code.uppercase()] ?: return JoinResult.RoomNotFound
        val player2Name = player2NameRaw.ifBlank { "Oyuncu 2" }

        synchronized(room.lock) {
            room.lastActivityAt = System.currentTimeMillis()

            if (room.player2Name == null) {
                room.player2Name = player2Name
                room.player2LastSeen = System.currentTimeMillis()
                startNewRound(room)
                return JoinResult.Success(room)
            }

            if (room.player2Name == player2Name || room.player1Name == player2Name) {
                return JoinResult.Success(room)
            }

            return JoinResult.RoomFull
        }
    }

    fun getRoom(code: String, playerName: String? = null): DuelRoom? {
        val room = rooms[code.uppercase()] ?: return null
        synchronized(room.lock) {
            checkTimeout(room)
            room.lastActivityAt = System.currentTimeMillis()
            if (playerName != null) {
                when (playerName) {
                    room.player1Name -> room.player1LastSeen = System.currentTimeMillis()
                    room.player2Name -> room.player2LastSeen = System.currentTimeMillis()
                }
            }
        }
        return room
    }

    fun nextRound(code: String): DuelRoom? {
        val room = rooms[code.uppercase()] ?: return null
        synchronized(room.lock) {
            room.lastActivityAt = System.currentTimeMillis()
            if (!room.gameOver) {
                startNewRound(room)
            }
        }
        return room
    }

    fun rematch(code: String): DuelRoom? {
        val room = rooms[code.uppercase()] ?: return null
        synchronized(room.lock) {
            room.lastActivityAt = System.currentTimeMillis()
            room.player1Score = 0
            room.player2Score = 0
            room.roundNumber = 0 // 🟢 yeniden maç, ısınma turları da baştan başlasın
            room.gameOver = false
            room.gameWinner = null
            room.recentPlayerNames.clear()
            room.usedClubsStd321.clear() // 🎯 YENİ: yeniden maç, kullanılan kulüpler de sıfırlansın
            startNewRound(room)
        }
        return room
    }

    // 🎯 YENİ: "3,2,1" modunda oyuncu kendi kulübünü gönderir. İki taraf da
    // gönderince, o iki kulüp arasındaki ortak oyuncuyu hesaplayıp asıl
    // "tahmin" fazına geçiyoruz — buradan sonrası (submitAnswer, checkTimeout
    // vb.) Genel Mod'la AYNI mekanizmayı kullanıyor, tekrar yazmıyoruz.
    // 🎯 YENİ: reddedilme durumunu (tekrar eden kulüp) sadece o kişiye
    // iletebilmek için ayrı bir sonuç tipi — genel state'e karışmıyor,
    // rakip bunu hiç görmüyor.
    @Serializable
    data class SubmitClub321Result(val accepted: Boolean, val state: DuelState)

    fun submitClub321(code: String, playerName: String, club: String): SubmitClub321Result? {
        val room = rooms[code.uppercase()] ?: return null
        val trimmedClub = club.trim()
        if (trimmedClub.isBlank()) return SubmitClub321Result(false, toState(room))

        synchronized(room.lock) {
            room.lastActivityAt = System.currentTimeMillis()
            when (playerName) {
                room.player1Name -> room.player1LastSeen = System.currentTimeMillis()
                room.player2Name -> room.player2LastSeen = System.currentTimeMillis()
            }

            if (room.duelMode != "321" || room.phase != "club_entry" || room.gameOver) {
                return SubmitClub321Result(false, toState(room))
            }

            // 🛡️ YENİ: bu kulüp (Lyon / Olympique Lyon gibi farklı yazılışlarıyla
            // bile) bu oyun boyunca daha önce kullanıldıysa REDDEDİYORUZ —
            // sadece gönderen kişiye "başka bir kulüp yaz" diyoruz, rakibi
            // etkilemiyor, kendi hakkı hâlâ duruyor.
            val normalizedClub = DatabaseClient.normalizeClubForComparison(trimmedClub)
            if (room.usedClubsStd321.contains(normalizedClub)) {
                return SubmitClub321Result(false, toState(room))
            }

            when (playerName) {
                room.player1Name -> if (room.club1Input == null) {
                    room.club1Input = trimmedClub
                    room.usedClubsStd321.add(normalizedClub)
                }
                room.player2Name -> if (room.club2Input == null) {
                    room.club2Input = trimmedClub
                    room.usedClubsStd321.add(normalizedClub)
                }
            }

            tryStartGuessingPhase321(room)
            return SubmitClub321Result(true, toState(room))
        }
    }

    // 🛡️ Süre dolduğunda VE hâlâ eksik kulüp varsa, oyunun tıkanmaması için
    // rastgele bir kulüp otomatik atanıyor — kullanıcı deneyimi kesintiye
    // uğramasın diye. Çağıran fonksiyon zaten room.lock içinde olmalı.
    private fun tryStartGuessingPhase321(room: DuelRoom) {
        if (room.club1Input == null || room.club2Input == null) return

        // 🎯 DÜZELTME (performans): eskiden fetchPlayerAcrossClubs ile sadece
        // TEK bir rastgele cevap seçiliyordu VE her "Gönder"de tekrar
        // veritabanına gidiliyordu. Şimdi TÜM geçerli cevapları BİR KEZ
        // çekip odada hafızada tutuyoruz — sonraki her tahmin, veritabanına
        // hiç gitmeden bu listeye bakarak anında kontrol ediliyor.
        val validPlayers = DatabaseClient.fetchAllPlayersAcrossTwoClubs(room.club1Input!!, room.club2Input!!)
        room.validPlayersForRound = validPlayers
        room.currentQuestion = null // 🎯 artık tek bir "önceden seçilmiş cevap" yok
        room.noMatchFound = validPlayers.isEmpty()
        room.phase = "guessing"
        room.roundStartTime = System.currentTimeMillis() // 🎯 tahmin fazının SÜRESİ burada başlıyor
        if (validPlayers.isEmpty()) {
            // Ortak oyuncu yoksa bu turu direkt "biten tur" (kazanansız) sayıyoruz
            // — frontend zaten "noMatchFound" durumunu biliyor, "sıradaki tur"
            // deyip club_entry fazına dönmelerini sağlayacak.
            room.roundOver = true
        }
    }

    // 🎲 YENİ: "3,2,1" modunun asıl tahmin kontrolü — submitAnswer'daki gibi
    // ÖNCEDEN seçilmiş TEK bir isimle karşılaştırmıyoruz. Bunun yerine,
    // kullanıcının yazdığı HER ismi gerçek zamanlı olarak veritabanında
    // doğruluyoruz: bu oyuncu GERÇEKTEN bu iki kulüpte oynamış mı? Böylece
    // o iki kulüpte oynamış birden fazla oyuncu varsa, hangisini bilirse
    // bilsin doğru sayılıyor — klasik sözlü oyundaki gibi.
    fun submitGuess321(code: String, playerName: String, guess: String): DuelAnswerResult? {
        val room = rooms[code.uppercase()] ?: return null

        synchronized(room.lock) {
            checkTimeout(room)
            room.lastActivityAt = System.currentTimeMillis()
            when (playerName) {
                room.player1Name -> room.player1LastSeen = System.currentTimeMillis()
                room.player2Name -> room.player2LastSeen = System.currentTimeMillis()
            }

            if (room.duelMode != "321" || room.phase != "guessing" || room.roundOver || room.gameOver) {
                return DuelAnswerResult(correct = false, state = toState(room))
            }

            val club1 = room.club1Input ?: return DuelAnswerResult(correct = false, state = toState(room))
            val club2 = room.club2Input ?: return DuelAnswerResult(correct = false, state = toState(room))

            // 🎯 DÜZELTME (performans): artık veritabanına HİÇ gitmiyoruz —
            // tryStartGuessingPhase321'de bir kez hesaplanan validPlayersForRound
            // listesine bakıyoruz. Bu, milisaniyeler içinde cevap veriyor,
            // kullanıcının 45 saniyesinden hiç zaman çalmıyor.
            val normalizedGuess = normalizeForDuel(guess)
            val match = room.validPlayersForRound.firstOrNull { candidate ->
                val cleanNameStd = candidate.nameStd.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()
                val surnameStd = cleanNameStd.split(Regex("\\s+")).lastOrNull() ?: ""
                normalizedGuess.isNotEmpty() && (normalizedGuess == cleanNameStd || normalizedGuess == surnameStd)
            }
            val isCorrect = match != null

            if (isCorrect) {
                // 🎯 Tur sonucunda göstermek için kazanan ismi (temizlenmiş
                // haliyle) saklıyoruz.
                room.winningPlayerDisplayName321 = match!!.playerName.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()
                room.roundOver = true
                room.roundWinner = playerName
                if (playerName == room.player1Name) room.player1Score++ else room.player2Score++

                if (room.player1Score >= room.winTarget) {
                    room.gameOver = true
                    room.gameWinner = room.player1Name
                } else if (room.player2Score >= room.winTarget) {
                    room.gameOver = true
                    room.gameWinner = room.player2Name
                }
            }

            return DuelAnswerResult(correct = isCorrect, state = toState(room))
        }
    }

    fun submitAnswer(code: String, playerName: String, guess: String): DuelAnswerResult? {
        val room = rooms[code.uppercase()] ?: return null

        synchronized(room.lock) {
            checkTimeout(room)
            room.lastActivityAt = System.currentTimeMillis()
            when (playerName) {
                room.player1Name -> room.player1LastSeen = System.currentTimeMillis()
                room.player2Name -> room.player2LastSeen = System.currentTimeMillis()
            }

            if (room.roundOver || room.currentQuestion == null || room.gameOver) {
                return DuelAnswerResult(correct = false, state = toState(room))
            }

            val realName = room.currentQuestion!!.playerName.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()
            val words = realName.trim().split(Regex("\\s+"))
            val surname = words.lastOrNull() ?: ""
            val normalizedGuess = normalizeForDuel(guess)
            val isCorrect = normalizedGuess.isNotEmpty() &&
                    (normalizedGuess == normalizeForDuel(realName) || normalizedGuess == normalizeForDuel(surname))

            if (isCorrect) {
                room.roundOver = true
                room.roundWinner = playerName
                if (playerName == room.player1Name) room.player1Score++ else room.player2Score++

                if (room.player1Score >= room.winTarget) {
                    room.gameOver = true
                    room.gameWinner = room.player1Name
                } else if (room.player2Score >= room.winTarget) {
                    room.gameOver = true
                    room.gameWinner = room.player2Name
                }
            }

            return DuelAnswerResult(correct = isCorrect, state = toState(room))
        }
    }

    fun submitPass(code: String, playerName: String): DuelState? {
        val room = rooms[code.uppercase()] ?: return null

        synchronized(room.lock) {
            checkTimeout(room)
            room.lastActivityAt = System.currentTimeMillis()

            when (playerName) {
                room.player1Name -> {
                    room.player1LastSeen = System.currentTimeMillis()
                    room.player1Passed = true
                }
                room.player2Name -> {
                    room.player2LastSeen = System.currentTimeMillis()
                    room.player2Passed = true
                }
            }

            if (!room.roundOver && !room.gameOver && room.player1Passed && room.player2Passed) {
                room.roundOver = true
                room.roundWinner = null
                room.bothPassed = true
            }

            return toState(room)
        }
    }

    fun toState(room: DuelRoom): DuelState {
        // 🎯 DÜZELTME: "3,2,1" modunda artık currentQuestion hep null (tek bir
        // önceden seçilmiş cevap yok) — kulüpleri doğrudan oyuncuların kendi
        // girdiği isimlerden, kazanan ismi ise winningPlayerDisplayName321'den
        // gösteriyoruz.
        val clubs = if (room.duelMode == "321") {
            listOfNotNull(room.club1Input, room.club2Input).map { DuelClubInfo(it, "") }
        } else {
            room.currentQuestion?.clubs?.map { DuelClubInfo(it.club, it.season) } ?: emptyList()
        }
        val revealedName = if (room.roundOver) {
            if (room.duelMode == "321") {
                room.winningPlayerDisplayName321
            } else {
                room.currentQuestion?.playerName?.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")?.trim()
            }
        } else null

        // 🎯 YENİ: "3,2,1" modunda süre, hangi fazda olduğumuza göre değişiyor
        // (kulüp girişi: 30 sn, tahmin: 45 sn) — diğer modlarda eskisi gibi 30 sn.
        val remaining = if (room.duelMode == "321" && room.phase == "club_entry" && !room.roundOver) {
            val elapsed = System.currentTimeMillis() - room.clubEntryStartTime
            maxOf(0L, (CLUB_ENTRY_DURATION_MS - elapsed) / 1000).toInt()
        } else if (!room.roundOver && (if (room.duelMode == "321") room.phase == "guessing" else room.currentQuestion != null)) {
            // 🎯 DÜZELTME (BUG): 321 modunda currentQuestion hep null olduğu
            // için bu şart eskiden hiç sağlanmıyordu — her poll'da "else"
            // dalına düşüp süreyi HEP 45'te sabit gösteriyordu (senin
            // gözlemlediğin "yanlış cevaptan sonra süre sıfırlanıyor" hissi
            // buradan geliyordu, aslında sıfırlanmıyordu ama hiç sayılmıyordu).
            val duration = if (room.duelMode == "321") DUEL_321_GUESS_DURATION_MS else ROUND_DURATION_MS
            val elapsed = System.currentTimeMillis() - room.roundStartTime
            maxOf(0L, (duration - elapsed) / 1000).toInt()
        } else {
            val duration = if (room.duelMode == "321") DUEL_321_GUESS_DURATION_MS else ROUND_DURATION_MS
            (duration / 1000).toInt()
        }

        val now = System.currentTimeMillis()

        return DuelState(
            roomCode = room.roomCode,
            player1Name = room.player1Name,
            player2Name = room.player2Name,
            player1Score = room.player1Score,
            player2Score = room.player2Score,
            winTarget = room.winTarget,
            roundNumber = room.roundNumber,
            clubs = clubs,
            position = room.currentQuestion?.position,
            imageUrl = room.currentQuestion?.imageUrl,
            roundOver = room.roundOver,
            roundWinner = room.roundWinner,
            timedOut = room.timedOut,
            remainingSeconds = remaining,
            revealedPlayerName = revealedName,
            waitingForOpponent = room.player2Name == null,
            noMatchFound = room.noMatchFound,
            gameOver = room.gameOver,
            gameWinner = room.gameWinner,
            player1SecondsSinceSeen = ((now - room.player1LastSeen) / 1000).toInt(),
            player2SecondsSinceSeen = if (room.player2Name != null) ((now - room.player2LastSeen) / 1000).toInt() else 0,
            player1Passed = room.player1Passed,
            player2Passed = room.player2Passed,
            bothPassed = room.bothPassed,
            maskingHintEnabled = room.maskingHintEnabled,
            maskedName = if (room.maskingHintEnabled && !room.roundOver && room.currentQuestion != null) {
                val cleanName = room.currentQuestion!!.playerName.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()
                maskNameForHint(cleanName)
            } else null,
            isCountryMix = room.isCountryMix,
            duelMode = room.duelMode,
            phase = room.phase,
            club1Submitted = room.club1Input != null,
            club2Submitted = room.club2Input != null
        )
    }

    private fun maskNameForHint(name: String): String {
        return name.split(" ").joinToString(" ") { word ->
            val letters = word.toCharArray()
            if (letters.size <= 2) {
                word
            } else {
                letters.mapIndexed { idx, ch ->
                    if (idx == 0 || idx == letters.size - 1) ch
                    else if (ch.isLetter()) '✦' else ch
                }.joinToString("")
            }
        }
    }

    private fun checkTimeout(room: DuelRoom) {
        if (room.gameOver) return

        // 🎯 YENİ: "3,2,1" modunun KULÜP GİRİŞİ fazı — 30 sn içinde eksik
        // kalan taraf(lar) için rastgele bir kulüp otomatik atanıp tahmin
        // fazına geçiliyor, oyun tıkanmasın diye.
        if (room.duelMode == "321" && room.phase == "club_entry" && !room.roundOver) {
            val elapsed = System.currentTimeMillis() - room.clubEntryStartTime
            if (elapsed > CLUB_ENTRY_DURATION_MS) {
                // 🎯 Otomatik atama da daha önce kullanılmış bir kulübü tekrar
                // seçmesin diye aynı kurala uyuyor.
                fun pickUnusedRandomClub(): String {
                    repeat(20) {
                        val candidate = clubPool.random()
                        if (!room.usedClubsStd321.contains(DatabaseClient.normalizeClubForComparison(candidate))) return candidate
                    }
                    return clubPool.random() // 🛡️ 20 denemede bulunamazsa (çok nadir) yine de devam et
                }
                if (room.club1Input == null) {
                    room.club1Input = pickUnusedRandomClub()
                    room.usedClubsStd321.add(DatabaseClient.normalizeClubForComparison(room.club1Input!!))
                }
                if (room.club2Input == null) {
                    room.club2Input = pickUnusedRandomClub()
                    room.usedClubsStd321.add(DatabaseClient.normalizeClubForComparison(room.club2Input!!))
                }
                tryStartGuessingPhase321(room)
            }
            return
        }

        // 🎯 DÜZELTME (BUG): "3,2,1" modunda artık currentQuestion hep null
        // (tek önceden seçilmiş cevap yok) — bu yüzden aşağıdaki eski kontrol
        // (currentQuestion != null) 321 modunda ASLA doğru olmuyordu, yani
        // tahmin fazı HİÇ zaman aşımına uğramıyordu (sonsuza kadar sürerdi).
        // 321 modunda artık "phase == guessing" şartına bakıyoruz.
        val inGuessingPhase = if (room.duelMode == "321") {
            room.phase == "guessing"
        } else {
            room.currentQuestion != null
        }
        if (!room.roundOver && inGuessingPhase) {
            // 🎯 "3,2,1" modunda tahmin fazı 45 sn, diğer modlarda 30 sn.
            val duration = if (room.duelMode == "321") DUEL_321_GUESS_DURATION_MS else ROUND_DURATION_MS
            val elapsed = System.currentTimeMillis() - room.roundStartTime
            if (elapsed > duration) {
                room.roundOver = true
                room.roundWinner = null
                room.timedOut = true
            }
        }
    }

    private fun startNewRound(room: DuelRoom) {
        room.roundNumber++
        room.roundOver = false
        room.roundWinner = null
        room.timedOut = false
        room.noMatchFound = false
        room.player1Passed = false
        room.player2Passed = false
        room.bothPassed = false
        room.roundStartTime = System.currentTimeMillis()

        // 🎯 YENİ: "3,2,1" Modu — tamamen ayrı, basit bir dal: rastgele kulüp
        // seçmiyoruz, oyunculara kendi kulüplerini YAZDIRIYORUZ. Bu round henüz
        // bir soru sormuyor, sadece "kulüp yazma" fazını başlatıyor — asıl soru
        // (currentQuestion), ikisi de kulübünü girdikten SONRA hesaplanıyor
        // (bkz. tryStartGuessingPhase321). Genel Mod'un mantığına hiç dokunmuyor.
        if (room.duelMode == "321") {
            room.phase = "club_entry"
            room.club1Input = null
            room.club2Input = null
            room.clubEntryStartTime = System.currentTimeMillis()
            room.currentQuestion = null
            room.validPlayersForRound = emptyList()
            room.winningPlayerDisplayName321 = null
            return
        }

        // 🇹🇷 Türkiye Ligi Modu — tamamen ayrı, basit bir dal: ilk 3 tur 5
        // şampiyon kulüpten, sonrası tüm Türkiye Ligi havuzundan. Ülke karışımı
        // YOK, Genel Mod'un mantığına hiç dokunmuyor.
        if (room.duelMode == "turkiye") {
            val inChampionsPhase = room.roundNumber <= TURKIYE_CHAMPIONS_ROUND_COUNT
            val pool = if (inChampionsPhase) turkiyeChampions else turkiyeLigiPool

            var found: MultiClubPlayerResult? = null
            var attempts = 0
            while (attempts < 12) {
                val terms: List<Pair<String, Boolean>> = pool.shuffled().take(2).map { it to false }
                // 🎯 YENİ: en az bir taraf 2007 sonrası bir sezonda örtüşsün diye
                // minYear filtresi eklendi (bulunamazsa fetchPlayerAcrossClubs
                // otomatik olarak filtresiz devam ediyor, boş ekran çıkmaz).
                val candidate = DatabaseClient.fetchPlayerAcrossClubs(terms, minYear = 2007)
                attempts++
                if (candidate != null) {
                    val cleanName = candidate.playerName.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()
                    if (!room.recentPlayerNames.contains(cleanName) || attempts >= 12) {
                        found = candidate
                        room.recentPlayerNames.add(cleanName)
                        if (room.recentPlayerNames.size > 6) room.recentPlayerNames.removeAt(0)
                        break
                    }
                }
            }
            room.currentQuestion = found
            room.isCountryMix = false
            room.noMatchFound = (found == null)
            return
        }

        // 🟢 İlk EASY_PHASE_ROUND_COUNT tur (varsayılan 3), sadece en bilindik
        // 17 kulüpten geliyor — ısınma turu. Bu fazda ülke karışımı da kapalı,
        // tamamen kulüp-kulüp gidiyor (Bil Bakalım'daki mantıkla birebir aynı).
        val inEasyPhase = room.roundNumber <= EASY_PHASE_ROUND_COUNT
        // 🟡 6-7. turlar "orta-kolay" — bir taraf kolay, bir taraf orta kulüp
        // (sert bir sıçrama yerine yumuşak bir geçiş için).
        val inMediumEasyPhase = !inEasyPhase && room.roundNumber <= MEDIUM_EASY_PHASE_ROUND_COUNT
        // 🟠 8. tur "orta-zor" — iki taraf da orta kulüp (hardClubPool hariç).
        val inMediumHardPhase = !inEasyPhase && !inMediumEasyPhase && room.roundNumber <= MEDIUM_PHASE_ROUND_COUNT
        val inMediumPhase = inMediumEasyPhase || inMediumHardPhase

        var found: MultiClubPlayerResult? = null
        var attempts = 0
        var isCountryMix = false
        while (attempts < 12) {
            // 🎯 YENİ: kulüp SEÇİMİ (bedava, veritabanına gitmiyor) ile GERÇEK
            // veritabanı arama denemesi (pahalı, 12 hakkımız var) artık BİRBİRİNDEN
            // AYRI — Süper Lig oranı kontrolü burada, ayrı ve ucuz bir iç döngüde
            // yapılıyor. Böylece "elenen" bir kulüp çifti, asıl 12 arama hakkımızdan
            // HİÇBİRİNİ tüketmiyor — sadece "daha iyi bir çift seç" diyor, arama
            // şansımızı asla azaltmıyor.
            var terms: List<Pair<String, Boolean>>
            var useCountryMix: Boolean
            var pickAttempts = 0
            val turkishAcceptProbability = if (inEasyPhase) 0.15 else 0.59
            do {
                useCountryMix = !inEasyPhase && kotlin.random.Random.nextDouble() < 0.05
                terms = if (useCountryMix) {
                    // 🎯 DÜZELTME: eskiden ülke-karışımı sorularında kulüp HER ZAMAN
                    // tam havuzdan (clubPool) seçiliyordu, faz'a bakılmaksızın — bu
                    // yüzden orta fazda bile bazen zor bir kulüp çıkabiliyordu. Artık
                    // kulüp de aynı faz mantığına uyuyor.
                    val clubSourcePool = if (inMediumHardPhase) mediumClubPool
                        else if (inMediumEasyPhase) (easyClubPool + mediumClubPool)
                        else clubPool
                    val club = clubSourcePool.random()
                    val country = countryPool.random()
                    listOf(club to false, country to true)
                } else if (inMediumEasyPhase) {
                    // 🟡 Bir taraf kolay havuzdan, bir taraf orta havuzdan — ikisi
                    // aynı isimde çıkarsa (nadir de olsa mümkün, easyClubPool zaten
                    // mediumClubPool'un alt kümesi) tekrar deniyoruz.
                    var easyPick: String
                    var mediumPick: String
                    do {
                        easyPick = easyClubPool.random()
                        mediumPick = mediumClubPool.random()
                    } while (easyPick == mediumPick)
                    listOf(easyPick to false, mediumPick to false)
                } else {
                    val pool = if (inEasyPhase) easyClubPool else if (inMediumHardPhase) mediumClubPool else clubPool
                    pool.shuffled().take(2).map { it to false }
                }
                pickAttempts++
                val hasTurkishClub = terms.any { (term, isCountry) -> !isCountry && turkishSuperLigClubs.contains(term) }
                val rejectedForRatio = hasTurkishClub && kotlin.random.Random.nextDouble() > turkishAcceptProbability
                if (!rejectedForRatio) break
                // 🛡️ 20 denemede uygun (Süper Lig'siz ya da kabul edilen) bir
                // çift çıkmazsa, elimizdeki son çifti kabul edip devam ediyoruz —
                // asıl arama şansımızı asla bu yüzden kaybetmiyoruz.
            } while (pickAttempts < 20)

            // 🎯 YENİ: en az bir taraf 2010 sonrası bir sezonda örtüşsün diye
            // minYear filtresi eklendi (bulunamazsa fetchPlayerAcrossClubs
            // otomatik olarak filtresiz devam ediyor, boş ekran çıkmaz).
            val candidate = DatabaseClient.fetchPlayerAcrossClubs(terms, minYear = 2010)
            attempts++

            // 🎯 YENİ: bazı kayıtlarda oyuncu ismi bozuk/eksik geliyor (sadece
            // "-" gibi) — bu durumda soru asla çözülemez hale geliyordu.
            // Böyle bir isim gelirse adayı reddedip yeniden çekiyoruz.
            val cleanCandidateName = candidate?.playerName
                ?.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
                ?.trim()
            if (candidate != null && (cleanCandidateName.isNullOrBlank() || cleanCandidateName == "-")) {
                continue
            }

            if (candidate != null) {
                val cleanName = candidate.playerName.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()
                if (!room.recentPlayerNames.contains(cleanName) || attempts >= 12) {
                    found = candidate
                    isCountryMix = useCountryMix
                    room.recentPlayerNames.add(cleanName)
                    if (room.recentPlayerNames.size > 6) room.recentPlayerNames.removeAt(0)
                    break
                }
            }
        }
        room.currentQuestion = found
        room.isCountryMix = isCountryMix
        room.noMatchFound = (found == null)
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }

    private fun normalizeForDuel(s: String): String {
        val turkishFolded = s.trim().lowercase()
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")

        val nfdNormalized = java.text.Normalizer.normalize(turkishFolded, java.text.Normalizer.Form.NFD)
        val accentsStripped = nfdNormalized.replace(Regex("\\p{Mn}+"), "")

        return accentsStripped
            .replace("ł", "l").replace("đ", "d").replace("ø", "o").replace("ß", "ss")
            .replace(Regex("\\s+"), " ")
    }
}
