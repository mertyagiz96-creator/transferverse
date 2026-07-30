import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

// 🏆 Arkadaşla Yarış (Duel) modu — polling tabanlı, basit sunucu içi (in-memory)
// oda yönetimi. Veritabanına hiç yazmıyor, sunucu yeniden başlarsa odalar kaybolur
// (2 arkadaşlık casual bir oyun için kabul edilebilir bir sınırlama).

private const val ROUND_DURATION_MS = 60_000L
private const val ROOM_STALE_MS = 2 * 60 * 60 * 1000L // 2 saat hareketsizlik = terk edilmiş say
private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L // 30 dakikada bir kontrol et
private const val OPPONENT_LEFT_THRESHOLD_SECONDS = 8 // ~5-6 kaçırılmış polling turu

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
    val player2SecondsSinceSeen: Int
)

@Serializable
data class DuelAnswerResult(val correct: Boolean, val state: DuelState)

// 🚪 Odaya katılma sonucu — "oda yok" ile "oda dolu" farklı, net hatalar olsun diye
sealed class JoinResult {
    data class Success(val room: DuelRoom) : JoinResult()
    object RoomFull : JoinResult()
    object RoomNotFound : JoinResult()
}

class DuelRoom(val roomCode: String, val player1Name: String, val winTarget: Int) {
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
    val lock = Any()
}

object DuelManager {
    private val rooms = ConcurrentHashMap<String, DuelRoom>()

    init {
        // 🧹 Terk edilmiş odaları periyodik olarak temizleyen arka plan iş parçacığı —
        // sunucunun hafızasını gereksiz yere şişirmesin diye.
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
        "Sivasspor", "Eyupspor", "Kocaelispor"
    )

    fun createRoom(player1Name: String, winTarget: Int): DuelRoom {
        var code: String
        do {
            code = generateCode()
        } while (rooms.containsKey(code))
        val validTarget = if (winTarget == 10) 10 else 5
        val room = DuelRoom(code, player1Name.ifBlank { "Oyuncu 1" }, validTarget)
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

            // Sayfa yenilenmiş olabilir — aynı isimle tekrar "katılmaya" izin veriyoruz
            if (room.player2Name == player2Name || room.player1Name == player2Name) {
                return JoinResult.Success(room)
            }

            return JoinResult.RoomFull
        }
    }

    // 🕵️ playerName verilirse, o oyuncunun "son görülme" zamanı güncelleniyor —
    // rakibin bağlantısının kesilip kesilmediğini anlamak için kullanılıyor.
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

    fun submitAnswer(code: String, playerName: String, guess: String): DuelAnswerResult? {
        val room = rooms[code.uppercase()] ?: return null

        synchronized(room.lock) {
            checkTimeout(room)
            room.lastActivityAt = System.currentTimeMillis()
            when (playerName) {
                room.player1Name -> room.player1LastSeen = System.currentTimeMillis()
                room.player2Name -> room.player2LastSeen = System.currentTimeMillis()
            }

            // Tur zaten bittiyse (rakip bilmiş, süre dolmuş ya da oyun bitmişse)
            // geç kalan cevap işleme alınmıyor
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
            player2SecondsSinceSeen = if (room.player2Name != null) ((now - room.player2LastSeen) / 1000).toInt() else 0
        )
    }

    // ⏱️ Süre (60sn) dolduysa turu otomatik bitiriyor — kimse kazanmamış sayılır,
    // cevap açıklanır, "Sonraki Soru" ile devam edilir.
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
        room.roundStartTime = System.currentTimeMillis()

        var found: MultiClubPlayerResult? = null
        var attempts = 0
        while (found == null && attempts < 8) {
            val pair = clubPool.shuffled().take(2)
            found = DatabaseClient.fetchPlayerAcrossClubs(pair)
            attempts++
        }
        room.currentQuestion = found
        room.noMatchFound = (found == null)
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }

    private fun normalizeForDuel(s: String): String {
        return s.trim().lowercase()
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .replace(Regex("\\s+"), " ")
    }
}