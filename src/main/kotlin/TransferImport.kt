import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.sql.DriverManager
import java.util.zip.GZIPInputStream

// 🔮 EKİM 2025 SONRASI TRANSFER İTHALATI — dcaribou/transfermarkt-datasets
// projesinden (haftalık güncellenen, gerçekten canlı bir kaynak) yeni
// transferleri çekip mevcut football.db'deki "transfers" tablomuza ekliyor.
//
// ⚠️ ÖNEMLİ MİMARİ NOT: Bu modül MEVCUT hiçbir tabloya/mantığa dokunmuyor —
// sadece "transfers" tablosuna YENİ satırlar EKLİYOR (INSERT), hiçbir şeyi
// SİLMİYOR ya da GÜNCELLEMİYOR. Oyuncu arama, kulüp arama, öneri sistemleri
// vb. HİÇBİRİ etkilenmiyor çünkü onlar zaten var olan players/transfers
// yapısını okuyor, biz sadece o yapıya uygun yeni satırlar ekliyoruz.
//
// İKİ MOD:
// - dryRun=true (varsayılan): HİÇBİR ŞEY YAZMIYOR, sadece kaç transferin
//   eşleştiğini/eşleşmediğini rapor ediyor.
// - dryRun=false: önizlemeyi onayladıktan sonra gerçek INSERT işlemini yapar.
object TransferImport {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 120_000 }
    }

    private const val TRANSFERS_CSV_URL =
        "https://pub-e682421888d945d684bcae8890b0ec20.r2.dev/data/transfers.csv.gz"

    // 💡 Transfer penceresi kapandığı için "Ekim 2025 sonrası" diyoruz —
    // bu tarihi ihtiyaca göre değiştirebilirsin.
    private const val CUTOFF_DATE = "2025-01-01"

    private fun openConnection() =
        DriverManager.getConnection("jdbc:sqlite:football.db").also { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA busy_timeout=5000;")
            }
        }

    // Türkçe + Balkan + genel aksan temizleme — DatabaseClient.kt'deki AYNI
    // mantığın bir kopyası (oradaki private fonksiyona buradan erişemediğimiz
    // için tekrar tanımlıyoruz, davranışı BİREBİR aynı).
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

    // 💡 Basit ama sağlam bir CSV satır ayrıştırıcı — tırnak içindeki
    // virgülleri (örn. "Club, FC" gibi) doğru şekilde ele alıyor.
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    // Kaynak tarihini ("2026-08-15" gibi) bizim "season" formatımıza
    // ("25/26" gibi, Ağustos-Mayıs sezon mantığıyla) çeviriyor.
    private fun dateToSeasonString(dateStr: String): String? {
        return try {
            val parts = dateStr.take(10).split("-")
            if (parts.size < 2) return null
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val startYear = if (month >= 7) year else year - 1
            val endYear = startYear + 1
            "${(startYear % 100).toString().padStart(2, '0')}/${(endYear % 100).toString().padStart(2, '0')}"
        } catch (e: Exception) {
            null
        }
    }

    data class ImportRow(
        val playerId: Long,
        val playerName: String,
        val fromClub: String,
        val toClub: String,
        val season: String,
        val rawDate: String
    )

    private suspend fun fetchTransfersCsvText(): String {
        // 🎯 proje kök dizininde "transfers_local.csv.gz" varsa (elle
        // indirip koyduysan) AĞA HİÇ ÇIKMADAN onu kullanıyoruz — localde
        // test edebilmen için. Yoksa normal şekilde URL'den çekiyor.
        val localFile = java.io.File("transfers_local.csv.gz")
        return if (localFile.exists()) {
            println("📂 Yerel dosya bulundu (transfers_local.csv.gz), ağa çıkılmıyor.")
            GZIPInputStream(localFile.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            val response = httpClient.get(TRANSFERS_CSV_URL)
            if (!response.status.isSuccess()) {
                throw Exception("CSV indirilemedi: HTTP ${response.status}")
            }
            val gzippedBytes = response.body<ByteArray>()
            GZIPInputStream(gzippedBytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    // applyCutoff=true: sadece CUTOFF_DATE sonrasını alır (normal transfer
    // ekleme akışı). applyCutoff=false: TÜM kariyer geçmişini alır (eksik
    // oyuncu oluştururken, tam profil için gerekiyor).
    private suspend fun fetchAndParseTransfersCore(applyCutoff: Boolean): Pair<Map<String, Int>, List<ImportRow>> {
        val text = fetchTransfersCsvText()
        val allLines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (allLines.isEmpty()) throw Exception("CSV boş.")

        val headerCols = parseCsvLine(allLines[0]).map { it.trim().lowercase() }
        val colIndex = headerCols.withIndex().associate { (i, name) -> name to i }

        // 🛡️ Sütun isimlerini ESNEK şekilde arıyoruz — birkaç olası varyasyonu
        // deniyoruz, tam eşleşme olmasa da makul bir tahminle devam ediyoruz.
        fun findCol(vararg candidates: String): Int? {
            for (cand in candidates) {
                colIndex[cand]?.let { return it }
            }
            // kısmi eşleşme (içeriyor mu) son çare olarak
            for (cand in candidates) {
                headerCols.indexOfFirst { it.contains(cand) }.takeIf { it >= 0 }?.let { return it }
            }
            return null
        }

        val playerIdIdx = findCol("player_id")
        val playerNameIdx = findCol("player_name", "name")
        val fromClubIdx = findCol("from_club_name", "from_club")
        val toClubIdx = findCol("to_club_name", "to_club")
        val dateIdx = findCol("transfer_date", "date")
        val seasonColIdx = findCol("transfer_season", "season") // 🎯 CSV'de hazır varsa doğrudan onu kullanıyoruz

        val rows = mutableListOf<ImportRow>()
        if (playerIdIdx != null && playerNameIdx != null && fromClubIdx != null && toClubIdx != null && dateIdx != null) {
            for (line in allLines.drop(1)) {
                val fields = parseCsvLine(line)
                val maxIdx = maxOf(playerIdIdx, playerNameIdx, fromClubIdx, toClubIdx, dateIdx)
                if (fields.size <= maxIdx) continue
                val playerId = fields[playerIdIdx].trim().toLongOrNull() ?: continue
                val rawDate = fields[dateIdx].trim()
                if (applyCutoff && rawDate < CUTOFF_DATE) continue // 🎯 sadece kesim tarihinden sonrasını al
                // 🎯 DÜZELTME: CSV'de zaten hazır bir "transfer_season" sütunu
                // varmış (örn. "25/26") — kendi hesaplamamızdan daha güvenilir,
                // varsa onu kullanıyoruz. Yoksa (sütun bulunamazsa) tarihten
                // hesaplamaya (eski yöntem) geri dönüyoruz.
                val season = seasonColIdx?.let { idx -> fields.getOrNull(idx)?.trim()?.takeUnless { it.isBlank() } }
                    ?: dateToSeasonString(rawDate)
                    ?: continue
                rows.add(
                    ImportRow(
                        playerId = playerId,
                        playerName = fields[playerNameIdx].trim(),
                        fromClub = fields[fromClubIdx].trim(),
                        toClub = fields[toClubIdx].trim(),
                        season = season,
                        rawDate = rawDate
                    )
                )
            }
        }

        return Pair(headerCols.withIndex().associate { (i, n) -> n to i }, rows)
    }

    private suspend fun fetchAndParseTransfers(): Pair<Map<String, Int>, List<ImportRow>> =
        fetchAndParseTransfersCore(applyCutoff = true)

    private suspend fun fetchAndParseTransfersFull(): Pair<Map<String, Int>, List<ImportRow>> =
        fetchAndParseTransfersCore(applyCutoff = false)

    data class ImportResult(
        val totalFound: Int,
        val matched: Int,
        val alreadyExists: Int,
        val unmatched: Int,
        val inserted: Int,
        val sampleMatched: List<String>,
        val sampleUnmatched: List<String>,
        val existingSeasonFormatExamples: List<String>,
        val totalPlayersInDb: Int,
        val rawDbNameSamples: List<String>,
        val headerColumns: List<String>
    )

    suspend fun runImport(dryRun: Boolean): ImportResult {
        val (headerCols, rows) = fetchAndParseTransfers()

        val sampleMatched = mutableListOf<String>()
        val sampleUnmatched = mutableListOf<String>()
        var matchedCount = 0
        var alreadyExistsCount = 0
        var unmatchedCount = 0
        var insertedCount = 0

        val existingSeasonExamples = mutableListOf<String>()
        var totalPlayersLoaded = 0 // 🎯 nameToId bloğun içinde tanımlı, sayısını dışarı taşımak için
        var rawDbNameSamples = listOf<String>() // 🔎 CSV isimleriyle karşılaştırmak için ham örnekler

        openConnection().use { conn ->
            // 🔍 Mevcut "season" formatını gerçek veriden örnekliyoruz —
            // varsayımımızın doğru olup olmadığını görebilelim diye.
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT season FROM transfers ORDER BY RANDOM() LIMIT 5").use { rs ->
                    while (rs.next()) {
                        rs.getString("season")?.let { existingSeasonExamples.add(it) }
                    }
                }
            }

            // 🎯 PERFORMANS DÜZELTMESİ: eskiden her transfer için ayrı bir
            // "LIKE '%isim%'" sorgusu atıyorduk — bu, başında % olduğu için
            // indeks kullanamıyor, HER SEFERİNDE tüm 92.000 oyuncuyu taramak
            // zorunda kalıyordu (9.266 transfer × 92.000 tarama = çok yavaş,
            // dakikalarca sürebiliyordu). Şimdi TÜM oyuncuları TEK SORGUYLA
            // hafızaya alıp, sonra hızlı bir HashMap ile eşleştiriyoruz.
            println("⏳ Oyuncular hafızaya alınıyor...")
            val nameToId = HashMap<String, Long>()
            val rawNameSamples = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id, name FROM players").use { rs ->
                    while (rs.next()) {
                        val rawName = rs.getString("name") ?: ""
                        val norm = stripAccentsForCompare(rawName)
                        if (norm.isNotBlank() && !nameToId.containsKey(norm)) {
                            nameToId[norm] = rs.getLong("id")
                        }
                        // 🔎 Karşılaştırma için ham isim örnekleri topluyoruz.
                        if (rawNameSamples.size < 10) rawNameSamples.add(rawName)
                    }
                }
            }
            println("✅ ${nameToId.size} benzersiz oyuncu adı hafızada.")
            totalPlayersLoaded = nameToId.size
            rawDbNameSamples = rawNameSamples

            // 🎯 Mevcut transferleri de tek seferde hafızaya alıyoruz — "zaten
            // var mı" kontrolünü her satır için ayrı sorgu atmadan yapabilelim.
            val existingTransferKeys = HashSet<String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT transfer_id, from_club, to_club, season FROM transfers").use { rs ->
                    while (rs.next()) {
                        val key = "${rs.getLong("transfer_id")}|${rs.getString("from_club")}|${rs.getString("to_club")}|${rs.getString("season")}"
                        existingTransferKeys.add(key)
                    }
                }
            }
            println("✅ ${existingTransferKeys.size} mevcut transfer hafızada.")

            for (row in rows) {
                val targetNorm = stripAccentsForCompare(row.playerName)
                var matchedPlayerId: Long? = nameToId[targetNorm]

                // 🛡️ YEDEK: tam eşleşme olmazsa (belki fazladan boşluk, farklı
                // format vb.), sadece BU kayıt için hafızadaki isimlerde
                // "içeriyor mu" diye ikinci bir deneme yapıyoruz — 9265
                // kaydın HEPSİ için değil, sadece tam eşleşmeyenler için,
                // hızı ciddi etkilemiyor.
                if (matchedPlayerId == null) {
                    val fallbackMatch = nameToId.entries.firstOrNull { (dbNorm, _) ->
                        dbNorm.contains(targetNorm) || targetNorm.contains(dbNorm)
                    }
                    matchedPlayerId = fallbackMatch?.value
                }

                if (matchedPlayerId == null) {
                    unmatchedCount++
                    if (sampleUnmatched.size < 15) {
                        sampleUnmatched.add("${row.playerName} (${row.fromClub} → ${row.toClub}, ${row.rawDate})")
                    }
                    continue
                }

                // 🛡️ Bu transfer zaten var mı diye kontrol ediyoruz (aynı
                // oyuncu + aynı kulüpler + aynı sezon) — tekrar eklemeyelim.
                val transferKey = "$matchedPlayerId|${row.fromClub}|${row.toClub}|${row.season}"
                if (existingTransferKeys.contains(transferKey)) {
                    alreadyExistsCount++
                    continue
                }

                matchedCount++
                if (sampleMatched.size < 15) {
                    sampleMatched.add("${row.playerName} (id=$matchedPlayerId): ${row.fromClub} → ${row.toClub} [${row.season}]")
                }

                if (!dryRun) {
                    conn.prepareStatement(
                        """INSERT INTO transfers
                           (transfer_id, from_club, to_club, season, from_club_std, to_club_std, transfer_date)
                           VALUES (?, ?, ?, ?, ?, ?, ?)"""
                    ).use { stmt ->
                        stmt.setLong(1, matchedPlayerId!!)
                        stmt.setString(2, row.fromClub)
                        stmt.setString(3, row.toClub)
                        stmt.setString(4, row.season)
                        // 🎯 KRİTİK DÜZELTME: arama özelliği (fetchCommonPlayers)
                        // from_club/to_club DEĞİL, from_club_std/to_club_std
                        // sütunlarına bakıyor — bunları doldurmazsak, eklediğimiz
                        // transfer veride var ama aramada HİÇ görünmüyor (tam
                        // olarak Guendouzi'de yaşadığımız sorun).
                        stmt.setString(5, stripAccentsForCompare(row.fromClub))
                        stmt.setString(6, stripAccentsForCompare(row.toClub))
                        stmt.setString(7, row.rawDate)
                        stmt.executeUpdate()
                    }
                    insertedCount++
                }
            }
        }

        return ImportResult(
            totalFound = rows.size,
            matched = matchedCount,
            alreadyExists = alreadyExistsCount,
            unmatched = unmatchedCount,
            inserted = insertedCount,
            sampleMatched = sampleMatched,
            sampleUnmatched = sampleUnmatched,
            existingSeasonFormatExamples = existingSeasonExamples,
            totalPlayersInDb = totalPlayersLoaded,
            rawDbNameSamples = rawDbNameSamples,
            headerColumns = headerCols.keys.toList()
        )
    }

    fun formatReport(result: ImportResult, dryRun: Boolean): String {
        return buildString {
            appendLine(if (dryRun) "🔍 ÖNİZLEME MODU (hiçbir şey yazılmadı)" else "✅ ONAY MODU (veritabanına yazıldı)")
            appendLine()
            appendLine("👥 Veritabanında toplam benzersiz oyuncu adı: ${result.totalPlayersInDb}")
            appendLine("   (bu sayı ~92.000 civarında değilse, bu localdeki football.db KÜÇÜK/TEST bir kopyadır — sorun bu olabilir)")
            appendLine()
            appendLine("🔎 Veritabanındaki HAM (işlenmemiş) örnek isimler (CSV'deki isimlerle karşılaştır):")
            result.rawDbNameSamples.forEach { appendLine("  - \"$it\"") }
            appendLine()
            appendLine("📋 Kaynak CSV sütunları: ${result.headerColumns.joinToString(", ")}")
            appendLine()
            appendLine("📅 Bizim veritabanımızdaki gerçek 'season' formatı örnekleri:")
            result.existingSeasonFormatExamples.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("📊 SONUÇLAR:")
            appendLine("  Toplam bulunan (Ekim 2025 sonrası): ${result.totalFound}")
            appendLine("  Eşleşen (oyuncu bizde bulundu): ${result.matched}")
            appendLine("  Zaten kayıtlı (atlandı): ${result.alreadyExists}")
            appendLine("  Eşleşmeyen (oyuncu bizde yok): ${result.unmatched}")
            if (!dryRun) appendLine("  ✅ Gerçekten eklenen: ${result.inserted}")
            appendLine()
            appendLine("✅ ÖRNEK EŞLEŞENLER:")
            result.sampleMatched.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("❌ ÖRNEK EŞLEŞMEYENLER (muhtemelen veritabanımızda hiç olmayan oyuncular):")
            result.sampleUnmatched.forEach { appendLine("  - $it") }
        }
    }

    // 🔎 GEÇİCİ TANI: bir oyuncunun HEM players tablosundaki kaydını HEM
    // transfers tablosundaki TÜM satırlarını ham haliyle gösteriyor —
    // arama ekranını hiç karıştırmadan, "gerçekten veritabanında var mı"
    // sorusuna kesin cevap veriyor.
    fun checkPlayerTransfers(nameQuery: String): String {
        if (nameQuery.isBlank()) return "⚠️ ?name=... parametresi gerekli."
        return try {
            openConnection().use { conn ->
                buildString {
                    appendLine("🔍 '$nameQuery' için players tablosunda arama:")
                    conn.prepareStatement("SELECT id, name FROM players WHERE name LIKE ?").use { stmt ->
                        stmt.setString(1, "%$nameQuery%")
                        stmt.executeQuery().use { rs ->
                            var found = false
                            while (rs.next()) {
                                found = true
                                val id = rs.getLong("id")
                                appendLine("  - id=$id, name=\"${rs.getString("name")}\"")
                                appendLine("    Bu id'nin transfers tablosundaki TÜM kayıtları:")
                                conn.prepareStatement(
                                    "SELECT from_club, to_club, season FROM transfers WHERE transfer_id = ? ORDER BY season"
                                ).use { stmt2 ->
                                    stmt2.setLong(1, id)
                                    stmt2.executeQuery().use { rs2 ->
                                        var transferCount = 0
                                        while (rs2.next()) {
                                            transferCount++
                                            appendLine("      ${rs2.getString("from_club")} → ${rs2.getString("to_club")} [${rs2.getString("season")}]")
                                        }
                                        if (transferCount == 0) appendLine("      (HİÇ transfer kaydı yok!)")
                                    }
                                }
                            }
                            if (!found) appendLine("  ❌ players tablosunda '$nameQuery' içeren HİÇBİR kayıt bulunamadı.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            "🔥 HATA: ${e.message}\n${e.stackTraceToString().take(1000)}"
        }
    }

    // 🎯 YENİ: Türkiye + Avrupa'nın büyük kulüpleri için eksik oyuncuları
    // SIFIRDAN oluşturuyor — sadece tek bir transfer değil, TÜM kariyer
    // geçmişleriyle (mevcut oyuncular gibi tam bir profil olsun diye).
    private val BIG_CLUBS = listOf(
        // 🇹🇷 Türkiye Süper Lig
        "galatasaray", "fenerbahce", "besiktas", "trabzonspor", "basaksehir",
        "adana demirspor", "konyaspor", "kasimpasa", "sivasspor", "antalyaspor",
        "alanyaspor", "kayserispor", "gaziantep", "rizespor", "goztepe",
        "samsunspor", "kocaelispor", "eyupspor",
        // 🌍 Avrupa'nın büyükleri
        "real madrid", "barcelona", "atletico madrid", "bayern munich", "borussia dortmund",
        "manchester united", "manchester city", "liverpool", "chelsea", "arsenal", "tottenham",
        "juventus", "ac milan", "inter milan", "napoli", "as roma",
        "paris saint-germain", "marseille", "lyon", "monaco",
        "ajax", "psv eindhoven", "porto", "benfica", "sporting cp"
    )

    private fun matchesBigClub(clubName: String): Boolean {
        val norm = stripAccentsForCompare(clubName)
        return BIG_CLUBS.any { norm.contains(it) }
    }

    data class PlayerProfile(
        val playerId: Long,
        val firstName: String,
        val lastName: String,
        val displayName: String,
        val countryOfBirth: String,
        val cityOfBirth: String,
        val nationality: String,
        val dateOfBirth: String,
        val subPosition: String,
        val position: String,
        val foot: String,
        val heightCm: String,
        val imageUrl: String,
        val playerCode: String,
        val currentClubName: String
    )

    private fun fetchPlayersCsvText(): String {
        val localFile = java.io.File("players_local.csv.gz")
        return if (localFile.exists()) {
            println("📂 Yerel dosya bulundu (players_local.csv.gz), ağa çıkılmıyor.")
            GZIPInputStream(localFile.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            throw Exception("players_local.csv.gz bulunamadı — bu özellik için gerekli, proje köküne koymalısın.")
        }
    }

    data class MissingPlayerResult(
        val totalCandidates: Int,
        val playersCreated: Int,
        val transfersCreated: Int,
        val skippedAlreadyExists: Int,
        val sampleCreated: List<String>,
        val sampleSkippedNoProfile: List<String>
    )

    suspend fun createMissingPlayersForBigClubs(dryRun: Boolean): MissingPlayerResult {
        // 1️⃣ Kaynak transfer verisini (tam geçmiş, tarih filtresi YOK — kariyer
        // boyunca her şeyi görmek istiyoruz) okuyoruz.
        val (_, allTransfersRaw) = fetchAndParseTransfersFull()

        // 2️⃣ players.csv'yi okuyup player_id → profil haritası kuruyoruz.
        val playersCsvText = fetchPlayersCsvText()
        val playersLines = playersCsvText.lineSequence().filter { it.isNotBlank() }.toList()
        val pHeader = parseCsvLine(playersLines[0]).map { it.trim().lowercase() }
        val pIdx = pHeader.withIndex().associate { (i, n) -> n to i }
        fun pCol(name: String): Int? = pIdx[name]

        val profileMap = HashMap<Long, PlayerProfile>()
        for (line in playersLines.drop(1)) {
            val f = parseCsvLine(line)
            val id = f.getOrNull(pCol("player_id") ?: continue)?.trim()?.toLongOrNull() ?: continue
            profileMap[id] = PlayerProfile(
                playerId = id,
                firstName = f.getOrNull(pCol("first_name") ?: -1)?.trim() ?: "",
                lastName = f.getOrNull(pCol("last_name") ?: -1)?.trim() ?: "",
                displayName = f.getOrNull(pCol("name") ?: -1)?.trim() ?: "",
                countryOfBirth = f.getOrNull(pCol("country_of_birth") ?: -1)?.trim() ?: "",
                cityOfBirth = f.getOrNull(pCol("city_of_birth") ?: -1)?.trim() ?: "",
                nationality = f.getOrNull(pCol("country_of_citizenship") ?: -1)?.trim() ?: "",
                dateOfBirth = f.getOrNull(pCol("date_of_birth") ?: -1)?.trim()?.take(10) ?: "",
                subPosition = f.getOrNull(pCol("sub_position") ?: -1)?.trim() ?: "",
                position = f.getOrNull(pCol("position") ?: -1)?.trim() ?: "",
                foot = f.getOrNull(pCol("foot") ?: -1)?.trim() ?: "",
                heightCm = f.getOrNull(pCol("height_in_cm") ?: -1)?.trim() ?: "",
                imageUrl = f.getOrNull(pCol("image_url") ?: -1)?.trim() ?: "",
                playerCode = f.getOrNull(pCol("player_code") ?: -1)?.trim() ?: "",
                currentClubName = f.getOrNull(pCol("current_club_name") ?: -1)?.trim() ?: ""
            )
        }
        println("✅ ${profileMap.size} oyuncu profili players.csv'den okundu.")

        // 3️⃣ "Büyük kulüp" transferlerinde geçen ama bizde HİÇ olmayan
        // oyuncuları buluyoruz.
        val sampleCreated = mutableListOf<String>()
        val sampleSkippedNoProfile = mutableListOf<String>()
        var playersCreated = 0
        var transfersCreated = 0
        var skippedAlreadyExists = 0

        openConnection().use { conn ->
            val existingIds = HashSet<Long>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id FROM players").use { rs ->
                    while (rs.next()) existingIds.add(rs.getLong("id"))
                }
            }
            println("✅ ${existingIds.size} mevcut oyuncu id'si hafızada.")

            // Büyük kulüp geçen transferlerdeki benzersiz player_id'leri topluyoruz.
            val candidatePlayerIds = allTransfersRaw
                .filter { matchesBigClub(it.fromClub) || matchesBigClub(it.toClub) }
                .map { it.playerId }
                .toSet()
                .filter { it !in existingIds } // zaten varsa hiç uğraşmayalım

            println("🔍 ${candidatePlayerIds.size} eksik oyuncu adayı bulundu (büyük kulüplerden).")

            for (playerId in candidatePlayerIds) {
                val profile = profileMap[playerId]
                if (profile == null) {
                    if (sampleSkippedNoProfile.size < 15) {
                        sampleSkippedNoProfile.add("player_id=$playerId (players.csv'de profil bulunamadı)")
                    }
                    continue
                }

                playersCreated++
                val nameWithId = "${profile.displayName} ($playerId)"
                val nameStd = stripAccentsForCompare(nameWithId)
                val nationalityStd = stripAccentsForCompare(profile.nationality)
                val fullName = "${profile.firstName} ${profile.lastName}".trim().ifBlank { profile.displayName }
                val positionCombined = if (profile.subPosition.isNotBlank())
                    "${profile.position} - ${profile.subPosition}" else profile.position

                if (sampleCreated.size < 20) {
                    sampleCreated.add("$nameWithId — ${profile.position}/${profile.subPosition}, ${profile.nationality}")
                }

                val playerTransfers = allTransfersRaw.filter { it.playerId == playerId }
                transfersCreated += playerTransfers.size

                if (!dryRun) {
                    conn.prepareStatement(
                        """INSERT INTO players
                           (id, slug, name, image_url, full_name, birthdate, birth_city, birth_country,
                            height, nationality, active, position, role, foot, club_id, status,
                            retirement_date, nationality_std, name_std)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                    ).use { stmt ->
                        stmt.setLong(1, playerId)
                        stmt.setString(2, profile.playerCode)
                        stmt.setString(3, nameWithId)
                        stmt.setString(4, profile.imageUrl)
                        stmt.setString(5, fullName)
                        stmt.setString(6, profile.dateOfBirth)
                        stmt.setString(7, profile.cityOfBirth)
                        stmt.setString(8, profile.countryOfBirth)
                        val heightVal = profile.heightCm.toDoubleOrNull()
                        if (heightVal != null) stmt.setDouble(9, heightVal) else stmt.setNull(9, java.sql.Types.REAL)
                        stmt.setString(10, profile.nationality)
                        stmt.setString(11, "True")
                        stmt.setString(12, positionCombined)
                        stmt.setString(13, profile.position)
                        stmt.setString(14, profile.foot)
                        stmt.setNull(15, java.sql.Types.INTEGER)
                        stmt.setString(16, profile.currentClubName)
                        stmt.setNull(17, java.sql.Types.VARCHAR)
                        stmt.setString(18, nationalityStd)
                        stmt.setString(19, nameStd)
                        stmt.executeUpdate()
                    }

                    conn.prepareStatement(
                        """INSERT INTO transfers
                           (transfer_id, from_club, to_club, season, from_club_std, to_club_std, transfer_date)
                           VALUES (?, ?, ?, ?, ?, ?, ?)"""
                    ).use { stmt ->
                        for (t in playerTransfers) {
                            stmt.setLong(1, playerId)
                            stmt.setString(2, t.fromClub)
                            stmt.setString(3, t.toClub)
                            stmt.setString(4, t.season)
                            stmt.setString(5, stripAccentsForCompare(t.fromClub))
                            stmt.setString(6, stripAccentsForCompare(t.toClub))
                            stmt.setString(7, t.rawDate)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                }
            }
        }

        return MissingPlayerResult(
            totalCandidates = playersCreated + sampleSkippedNoProfile.size,
            playersCreated = playersCreated,
            transfersCreated = transfersCreated,
            skippedAlreadyExists = skippedAlreadyExists,
            sampleCreated = sampleCreated,
            sampleSkippedNoProfile = sampleSkippedNoProfile
        )
    }

    fun formatMissingPlayerReport(result: MissingPlayerResult, dryRun: Boolean): String {
        return buildString {
            appendLine(if (dryRun) "🔍 ÖNİZLEME MODU (hiçbir şey yazılmadı)" else "✅ ONAY MODU (veritabanına yazıldı)")
            appendLine()
            appendLine("📊 SONUÇLAR:")
            appendLine("  Oluşturulacak/oluşturulan oyuncu: ${result.playersCreated}")
            appendLine("  Bu oyuncularla birlikte eklenen toplam transfer: ${result.transfersCreated}")
            appendLine("  Profili bulunamadığı için atlanan: ${result.sampleSkippedNoProfile.size}")
            appendLine()
            appendLine("✅ ÖRNEK OLUŞTURULAN OYUNCULAR:")
            result.sampleCreated.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("⚠️ PROFİLİ BULUNAMAYAN ÖRNEKLER:")
            result.sampleSkippedNoProfile.forEach { appendLine("  - $it") }
        }
    }

    // 🛠️ GERİYE DÖNÜK ONARIM: from_club_std/to_club_std boş (NULL ya da '')
    // olan TÜM satırları buluyor ve dolduruyor — bu, bizim bugünkü
    // import'larımızı VE daha önce elle eklenmiş Belhanda gibi kayıtları da
    // kapsıyor (hepsi aynı sorunu yaşıyordu). Mevcut, zaten doğru dolu olan
    // satırlara HİÇ dokunmuyor.
    suspend fun repairMissingStdColumns(dryRun: Boolean): String {
        return openConnection().use { conn ->
            data class Row(val rowid: Long, val fromClub: String, val toClub: String)
            val toFix = mutableListOf<Row>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """SELECT rowid, from_club, to_club FROM transfers
                       WHERE from_club_std IS NULL OR from_club_std = ''
                          OR to_club_std IS NULL OR to_club_std = ''"""
                ).use { rs ->
                    while (rs.next()) {
                        toFix.add(Row(rs.getLong("rowid"), rs.getString("from_club") ?: "", rs.getString("to_club") ?: ""))
                    }
                }
            }

            var fixedCount = 0
            if (!dryRun && toFix.isNotEmpty()) {
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        "UPDATE transfers SET from_club_std = ?, to_club_std = ? WHERE rowid = ?"
                    ).use { stmt ->
                        for (r in toFix) {
                            stmt.setString(1, stripAccentsForCompare(r.fromClub))
                            stmt.setString(2, stripAccentsForCompare(r.toClub))
                            stmt.setLong(3, r.rowid)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                    fixedCount = toFix.size
                } finally {
                    conn.autoCommit = true
                }
            }

            buildString {
                appendLine(if (dryRun) "🔍 ÖNİZLEME MODU (hiçbir şey yazılmadı)" else "✅ ONAY MODU (veritabanına yazıldı)")
                appendLine()
                appendLine("🔧 from_club_std/to_club_std BOŞ olan satır sayısı: ${toFix.size}")
                if (!dryRun) appendLine("✅ Onarılan satır sayısı: $fixedCount")
                appendLine()
                appendLine("📄 Örnek (ilk 15):")
                toFix.take(15).forEach { appendLine("  - ${it.fromClub} → ${it.toClub}") }
            }
        }
    }

    // 🛠️ YENİ ONARIM: name'i BOŞ ama slug'ı dolu olan oyuncuları (muhtemelen
    // isimdeki kesme işareti — N'Golo gibi — orijinal veritabanı kurulurken
    // bir ayrıştırma hatasına yol açmış) players.csv'deki gerçek isimlerini
    // kullanarak onarıyor. Bu, bugünkü çalışmamızla İLGİSİZ, çok daha eski
    // bir veri kalitesi sorunu — 520 oyuncuyu etkiliyor.
    suspend fun repairEmptyPlayerNames(dryRun: Boolean): String {
        val playersCsvText = fetchPlayersCsvText()
        val playersLines = playersCsvText.lineSequence().filter { it.isNotBlank() }.toList()
        val pHeader = parseCsvLine(playersLines[0]).map { it.trim().lowercase() }
        val pIdx = pHeader.withIndex().associate { (i, n) -> n to i }
        fun pCol(name: String): Int? = pIdx[name]

        val nameById = HashMap<Long, String>()
        for (line in playersLines.drop(1)) {
            val f = parseCsvLine(line)
            val id = f.getOrNull(pCol("player_id") ?: continue)?.trim()?.toLongOrNull() ?: continue
            val nm = f.getOrNull(pCol("name") ?: continue)?.trim() ?: continue
            if (nm.isNotBlank()) nameById[id] = nm
        }

        return openConnection().use { conn ->
            data class Broken(val id: Long, val slug: String)
            val broken = mutableListOf<Broken>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT id, slug FROM players WHERE (name IS NULL OR name = '') AND slug IS NOT NULL AND slug != ''"
                ).use { rs ->
                    while (rs.next()) broken.add(Broken(rs.getLong("id"), rs.getString("slug") ?: ""))
                }
            }

            var fixed = 0
            val notFoundInCsv = mutableListOf<String>()
            var notFoundCount = 0 // 🎯 DÜZELTME: örnek listesi 15 ile sınırlıydı, gerçek toplam bu sayede ayrı tutuluyor
            val sampleFixed = mutableListOf<String>()

            if (!dryRun) conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "UPDATE players SET name = ?, full_name = ?, name_std = ? WHERE id = ?"
                ).use { stmt ->
                    for (b in broken) {
                        val realName = nameById[b.id]
                        if (realName == null) {
                            notFoundCount++
                            if (notFoundInCsv.size < 15) notFoundInCsv.add("id=${b.id}, slug=${b.slug}")
                            continue
                        }
                        val nameWithId = "$realName (${b.id})"
                        val nameStd = stripAccentsForCompare(nameWithId)
                        if (sampleFixed.size < 20) sampleFixed.add("id=${b.id}: \"$nameWithId\"")
                        fixed++
                        if (!dryRun) {
                            stmt.setString(1, nameWithId)
                            stmt.setString(2, realName)
                            stmt.setString(3, nameStd)
                            stmt.setLong(4, b.id)
                            stmt.addBatch()
                        }
                    }
                    if (!dryRun) stmt.executeBatch()
                }
                if (!dryRun) conn.commit()
            } finally {
                if (!dryRun) conn.autoCommit = true
            }

            buildString {
                appendLine(if (dryRun) "🔍 ÖNİZLEME MODU (hiçbir şey yazılmadı)" else "✅ ONAY MODU (veritabanına yazıldı)")
                appendLine()
                appendLine("🔧 İsmi boş olan oyuncu sayısı: ${broken.size}")
                appendLine("✅ players.csv'den bulunup onarılan: $fixed")
                appendLine("⚠️ players.csv'de de bulunamayan: $notFoundCount")
                appendLine()
                appendLine("📄 Örnek onarılanlar:")
                sampleFixed.forEach { appendLine("  - $it") }
                if (notFoundInCsv.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠️ Bulunamayanlar (örnek):")
                    notFoundInCsv.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("💡 NOT: Bu onarımdan SONRA, /admin/import-transfers?dryRun=false'u")
                appendLine("   TEKRAR çalıştırman iyi olur — bu oyuncuların isimleri artık düzgün")
                appendLine("   olduğu için, daha önce 'eşleşmeyen' sayılan transferleri")
                appendLine("   (örn. Kanté'nin Fenerbahçe transferi) şimdi yakalayabilir.")
            }
        }
    }
}
