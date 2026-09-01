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
    val isCountryMix: Boolean
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
        "Feyenoord", "PSV", "Panathinaikos", "Olympiacos"
    )

    // 🟢 "Kolay Kulüpler" — frontend'deki Bil Bakalım'daki easyClubs ile AYNI 17
    // kulüp (stadyum verimizin de olduğu, Avrupa'nın en bilindik takımları). Her
    // odanın İLK 3 turu bu havuzdan geliyor — ısınma turu, kimse hemen zor bir
    // soruyla karşılaşıp oyundan soğumasın diye.
    private val easyClubPool = listOf(
        "Galatasaray", "Fenerbahce", "Besiktas", "Kocaelispor",
        "Manchester United", "Manchester City", "Chelsea", "Arsenal",
        "Real Madrid", "Barcelona", "Juventus", "AC Milan", "Inter",
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
        val validMode = if (duelMode == "turkiye") "turkiye" else "genel"
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
            startNewRound(room)
        }
        return room
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
        val clubs = room.currentQuestion?.clubs?.map { DuelClubInfo(it.club, it.season) } ?: emptyList()
        val revealedName = if (room.roundOver) {
            room.currentQuestion?.playerName?.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")?.trim()
        } else null

        val remaining = if (!room.roundOver && room.currentQuestion != null) {
            val elapsed = System.currentTimeMillis() - room.roundStartTime
            maxOf(0L, (ROUND_DURATION_MS - elapsed) / 1000).toInt()
        } else {
            (ROUND_DURATION_MS / 1000).toInt()
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
            isCountryMix = room.isCountryMix
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
        if (!room.roundOver && room.currentQuestion != null && !room.gameOver) {
            val elapsed = System.currentTimeMillis() - room.roundStartTime
            if (elapsed > ROUND_DURATION_MS) {
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
                useCountryMix = !inEasyPhase && kotlin.random.Random.nextDouble() < 0.2
                terms = if (useCountryMix) {
                    val club = clubPool.random()
                    val country = countryPool.random()
                    listOf(club to false, country to true)
                } else {
                    val pool = if (inEasyPhase) easyClubPool else clubPool
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
