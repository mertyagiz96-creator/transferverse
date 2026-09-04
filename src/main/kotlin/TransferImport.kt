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
    private const val CUTOFF_DATE = "2025-10-01"

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
        val playerName: String,
        val fromClub: String,
        val toClub: String,
        val season: String,
        val rawDate: String
    )

    private suspend fun fetchAndParseTransfers(): Pair<Map<String, Int>, List<ImportRow>> {
        val response = httpClient.get(TRANSFERS_CSV_URL)
        if (!response.status.isSuccess()) {
            throw Exception("CSV indirilemedi: HTTP ${response.status}")
        }
        val gzippedBytes = response.body<ByteArray>()
        val text = GZIPInputStream(gzippedBytes.inputStream()).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
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

        val playerNameIdx = findCol("player_name", "name")
        val fromClubIdx = findCol("from_club_name", "from_club")
        val toClubIdx = findCol("to_club_name", "to_club")
        val dateIdx = findCol("transfer_date", "date")

        val rows = mutableListOf<ImportRow>()
        if (playerNameIdx != null && fromClubIdx != null && toClubIdx != null && dateIdx != null) {
            for (line in allLines.drop(1)) {
                val fields = parseCsvLine(line)
                val maxIdx = maxOf(playerNameIdx, fromClubIdx, toClubIdx, dateIdx)
                if (fields.size <= maxIdx) continue
                val rawDate = fields[dateIdx].trim()
                if (rawDate < CUTOFF_DATE) continue // 🎯 sadece kesim tarihinden sonrasını al
                val season = dateToSeasonString(rawDate) ?: continue
                rows.add(
                    ImportRow(
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

    data class ImportResult(
        val totalFound: Int,
        val matched: Int,
        val alreadyExists: Int,
        val unmatched: Int,
        val inserted: Int,
        val sampleMatched: List<String>,
        val sampleUnmatched: List<String>,
        val existingSeasonFormatExamples: List<String>,
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

            for (row in rows) {
                val targetNorm = stripAccentsForCompare(row.playerName)

                // 🎯 Oyuncuyu isme göre (aksan-toleranslı) buluyoruz.
                var matchedPlayerId: Long? = null
                conn.prepareStatement(
                    "SELECT id, name FROM players WHERE name_std LIKE ? LIMIT 5"
                ).use { stmt ->
                    stmt.setString(1, "%$targetNorm%")
                    stmt.executeQuery().use { rs ->
                        while (rs.next() && matchedPlayerId == null) {
                            val candidateNorm = stripAccentsForCompare(rs.getString("name") ?: "")
                            if (candidateNorm == targetNorm) {
                                matchedPlayerId = rs.getLong("id")
                            }
                        }
                    }
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
                var exists = false
                conn.prepareStatement(
                    "SELECT 1 FROM transfers WHERE transfer_id = ? AND from_club = ? AND to_club = ? AND season = ? LIMIT 1"
                ).use { stmt ->
                    stmt.setLong(1, matchedPlayerId!!)
                    stmt.setString(2, row.fromClub)
                    stmt.setString(3, row.toClub)
                    stmt.setString(4, row.season)
                    stmt.executeQuery().use { rs -> exists = rs.next() }
                }

                if (exists) {
                    alreadyExistsCount++
                    continue
                }

                matchedCount++
                if (sampleMatched.size < 15) {
                    sampleMatched.add("${row.playerName} (id=$matchedPlayerId): ${row.fromClub} → ${row.toClub} [${row.season}]")
                }

                if (!dryRun) {
                    conn.prepareStatement(
                        "INSERT INTO transfers (transfer_id, from_club, to_club, season) VALUES (?, ?, ?, ?)"
                    ).use { stmt ->
                        stmt.setLong(1, matchedPlayerId!!)
                        stmt.setString(2, row.fromClub)
                        stmt.setString(3, row.toClub)
                        stmt.setString(4, row.season)
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
            headerColumns = headerCols.keys.toList()
        )
    }

    fun formatReport(result: ImportResult, dryRun: Boolean): String {
        return buildString {
            appendLine(if (dryRun) "🔍 ÖNİZLEME MODU (hiçbir şey yazılmadı)" else "✅ ONAY MODU (veritabanına yazıldı)")
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
}
