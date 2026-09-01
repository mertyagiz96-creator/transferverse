import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.sql.DriverManager
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// 📰 GÜNCEL HABERLER — RSS kaynaklarından çekip, Google Gemini API'siyle
// (ücretsiz katman) en önemli 3 tanesini seçip özetleyen, tamamen izole bir
// modül. Mevcut DatabaseClient/DuelManager mantığına hiç dokunmuyor, kendi
// bağımsız SQLite bağlantılarını (football.db'deki AYRI bir tabloya) kullanıyor.
//
// 🏀⚽ YENİ: Artık FUTBOL ve BASKETBOL için AYRI haber havuzları tutuyor —
// site futbol modundayken futbol haberleri, basketbol modundayken basketbol
// haberleri gösteriliyor (Günün Oyuncusu'ndaki aynı mantık).
//
// ⚠️ BİLİNEN RİSK: Gemini API'sinin, bazı bulut sunucu (Render gibi) IP
// aralıklarından ara sıra 403 döndürdüğüne dair raporlar var. Bu yüzden
// Gemini çağrısı BAŞARISIZ olursa, otomatik olarak basit kural bazlı bir
// seçime (en yeni 3 haber, özetsiz kısa alıntı) düşüyoruz — haber kartı
// hiçbir zaman tamamen boş/bozuk kalmıyor.
object NewsManager {

    // 💡 Model adı BİLEREK sabit/versiyonlu tutuluyor ("-latest" gibi takma
    // adlar zamanla farklı, bazen kararsız modellere yönlendirilebiliyor).
    // Google ileride bu modeli kullanımdan kaldırırsa, sadece bu satırı
    // güncel bir model ID'siyle değiştirmek yeterli.
    private const val GEMINI_MODEL = "gemini-2.5-flash"
    private val geminiApiKey = System.getenv("GEMINI_API_KEY")

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    // ⚠️ NOT: Fotomaç'ın RSS'i (anasayfa.xml) BİLEREK kullanılmıyor — kendi
    // feed'lerindeki <lastBuildDate> etiketi "31 May 2025" gösteriyor, yani
    // Fotomaç kendi tarafında bu dosyayı aylardır güncellemiyor (bizim
    // kontrolümüz dışında bir sorun). NTV Spor'un feed'leri test edildi ve
    // gerçekten canlı/güncel çıktı.
    //
    // 🏀⚽ Her spor için AYRI kaynak listesi — sport parametresine göre
    // hangisinin kullanılacağı belirleniyor.
    private val RSS_SOURCES: Map<String, List<Pair<String, String>>> = mapOf(
        "football" to listOf(
            "https://www.ntvspor.net/rss/kategori/futbol" to "NTV Spor"
        ),
        "basketball" to listOf(
            "https://www.ntvspor.net/rss/kategori/basketbol" to "NTV Spor"
        )
    )

    private fun defaultTagFor(sport: String) = if (sport == "basketball") "🏀 HABER" else "⚽ HABER"

    @Serializable
    data class NewsItem(
        val tag: String,
        val title: String,
        val summary: String,
        val source: String,
        val url: String
    )

    private data class RawNewsCandidate(
        val title: String,
        val description: String,
        val url: String,
        val source: String
    )

    private data class SelectedNewsItem(
        val tag: String,
        val title: String,
        val summary: String,
        val sourceUrl: String,
        val source: String
    )

    private fun openConnection() =
        DriverManager.getConnection("jdbc:sqlite:football.db").also { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA busy_timeout=5000;")
            }
        }

    fun ensureNewsTable() {
        try {
            openConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS news_items (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            sport TEXT NOT NULL DEFAULT 'football',
                            tag TEXT,
                            title TEXT NOT NULL,
                            summary TEXT NOT NULL,
                            source TEXT,
                            url TEXT,
                            created_at INTEGER
                        )
                        """.trimIndent()
                    )
                }
                // 🛡️ Daha önce bu tablo "sport" sütunu OLMADAN oluşturulmuş
                // olabilir (bu özellik eklenmeden önceki deploy'lardan) — eğer
                // sütun eksikse ekliyoruz. Zaten varsa hata verip sessizce geçiyor.
                try {
                    conn.createStatement().use { stmt ->
                        stmt.execute("ALTER TABLE news_items ADD COLUMN sport TEXT NOT NULL DEFAULT 'football'")
                    }
                    println("ℹ️ news_items tablosuna 'sport' sütunu sonradan eklendi.")
                } catch (e: Exception) {
                    // sütun zaten vardı, normal — sessizce geç
                }
            }
            println("✅ news_items tablosu hazır.")
        } catch (e: Exception) {
            println("🔥 ensureNewsTable HATASI: ${e.message}")
        }
    }

    // 🔁 Sürekli çalışan arka plan döngüsü — her (varsayılan 30 dk) bir HEM
    // futbol HEM basketbol haberlerini ayrı ayrı yeniden çekip günceller.
    // main()'de GlobalScope.launch içinde çağrılması bekleniyor.
    suspend fun startPeriodicRefresh(intervalMinutes: Long = 30) {
        while (true) {
            for (sport in listOf("football", "basketball")) {
                try {
                    refreshNewsOnce(sport)
                } catch (e: Exception) {
                    println("🔥 refreshNewsOnce($sport) (döngü) HATASI: ${e.message}")
                }
            }
            kotlinx.coroutines.delay(intervalMinutes * 60 * 1000)
        }
    }

    suspend fun refreshNewsOnce(sport: String) {
        val sources = RSS_SOURCES[sport] ?: run {
            println("⚠️ Bilinmeyen spor: $sport, atlanıyor.")
            return
        }

        val candidates = mutableListOf<RawNewsCandidate>()
        for ((url, sourceName) in sources) {
            try {
                candidates.addAll(fetchAndParseFeed(url, sourceName))
            } catch (e: Exception) {
                println("⚠️ [$sport] $sourceName RSS çekilemedi: ${e.message}")
            }
        }

        if (candidates.isEmpty()) {
            println("⚠️ [$sport] Hiç haber adayı bulunamadı, bu turu atlıyoruz (eski haberler ekranda kalır).")
            return
        }

        // 💡 Gemini'ye göndermeden önce, hem maliyeti hem gecikmeyi düşürmek
        // için en yeni 15 adayla sınırlıyoruz — RSS zaten en yeniden eskiye sıralı geliyor.
        val trimmed = candidates.distinctBy { it.url }.take(15)

        val selected = try {
            selectWithGemini(trimmed, sport)
        } catch (e: Exception) {
            println("⚠️ [$sport] Gemini seçimi başarısız (${e.message}), kural bazlı seçime düşülüyor.")
            null
        }

        // 🎯 Hangi yolun kullanıldığı NET olarak loglanıyor — Render
        // loglarında arayarak (Ctrl+F) "AI SEÇİMİ" ya da "KURAL BAZLI" yazarak
        // hangi turda hangisinin çalıştığını kolayca görebilirsin.
        if (selected != null) {
            println("🤖 [$sport] AI SEÇİMİ kullanıldı (Gemini, model: $GEMINI_MODEL) — ${selected.size} haber seçildi.")
        } else {
            println("📋 [$sport] KURAL BAZLI seçime düşüldü (Gemini kullanılamadı ya da GEMINI_API_KEY tanımlı değil) — en yeni 3 haber gösteriliyor.")
        }

        val finalItems = selected ?: trimmed.take(3).map {
            SelectedNewsItem(
                tag = defaultTagFor(sport),
                title = it.title,
                summary = it.description.take(160),
                sourceUrl = it.url,
                source = it.source
            )
        }

        saveNews(sport, finalItems)
    }

    private suspend fun fetchAndParseFeed(url: String, sourceName: String): List<RawNewsCandidate> {
        val xmlText = httpClient.get(url).bodyAsText()

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false // 💡 basit tutmak için namespace ayrımını görmezden geliyoruz
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlText.byteInputStream(Charsets.UTF_8))

        val results = mutableListOf<RawNewsCandidate>()

        // RSS 2.0 formatı — <item><title>/<link>/<description>
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val el = items.item(i) as? Element ?: continue
            val title = el.getElementsByTagName("title").item(0)?.textContent?.trim() ?: continue
            val link = el.getElementsByTagName("link").item(0)?.textContent?.trim() ?: continue
            val desc = el.getElementsByTagName("description").item(0)?.textContent?.trim()?.let { stripHtml(it) } ?: ""
            results.add(RawNewsCandidate(title, desc, link, sourceName))
        }

        // Atom formatı (NTV Spor gibi) — <entry><title>/<link href=".."/>/<summary>
        if (results.isEmpty()) {
            val entries = doc.getElementsByTagName("entry")
            for (i in 0 until entries.length) {
                val el = entries.item(i) as? Element ?: continue
                val title = el.getElementsByTagName("title").item(0)?.textContent?.trim() ?: continue
                val linkEl = el.getElementsByTagName("link").item(0) as? Element
                val link = linkEl?.getAttribute("href")?.trim().takeUnless { it.isNullOrBlank() } ?: continue
                val summary = el.getElementsByTagName("summary").item(0)?.textContent?.trim()?.let { stripHtml(it) } ?: ""
                results.add(RawNewsCandidate(title, summary, link, sourceName))
            }
        }

        return results
    }

    private fun stripHtml(s: String): String {
        return s.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
    }

    private suspend fun selectWithGemini(candidates: List<RawNewsCandidate>, sport: String): List<SelectedNewsItem>? {
        if (geminiApiKey.isNullOrBlank()) {
            println("ℹ️ GEMINI_API_KEY tanımlı değil, kural bazlı seçime geçiliyor.")
            return null
        }

        val candidateListText = candidates.mapIndexed { idx, c ->
            "${idx + 1}. Başlık: ${c.title}\nÖzet: ${c.description.take(200)}\nKaynak: ${c.source}"
        }.joinToString("\n\n")

        val sportLabel = if (sport == "basketball") "Türk basketbolu" else "Türk futbolu"
        val exampleTags = if (sport == "basketball")
            "\"🏀 TRANSFER\", \"🔄 RESMİ AÇIKLAMA\", \"🗣️ AÇIKLAMA\", \"📊 SONUÇ\""
        else
            "\"⚽ TRANSFER\", \"🔄 RESMİ AÇIKLAMA\", \"🗣️ AÇIKLAMA\", \"📊 SONUÇ\""

        val prompt = """
            Aşağıda güncel $sportLabel haberlerinin bir listesi var. Bunlardan EN ÖNEMLİ ve EN İLGİ ÇEKİCİ 3 tanesini seç
            (transfer haberleri, resmi açıklamalar, önemli sonuçlar/gelişmeler öncelikli olsun; maç önizlemesi, yayın saati
            gibi sıradan/rutin içerikleri arkaya at). Her biri için KISA (en fazla 2 cümle) bir Türkçe özet yaz ve kısa
            bir etiket belirle (örn: $exampleTags).

            SADECE aşağıdaki JSON formatında, başka HİÇBİR metin eklemeden cevap ver:
            [{"index": <listedeki numarası>, "tag": "...", "summary": "..."}, ...]

            Haberler:
            $candidateListText
        """.trimIndent()

        val requestBodyJson = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
        }.toString()

        val response: HttpResponse = httpClient.post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        ) {
            header("x-goog-api-key", geminiApiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBodyJson)
        }

        if (!response.status.isSuccess()) {
            println("⚠️ Gemini API hatası: HTTP ${response.status} — ${response.bodyAsText().take(300)}")
            return null
        }

        val bodyText = response.bodyAsText()
        val root = Json.parseToJsonElement(bodyText).jsonObject
        val text = root["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content ?: run {
                println("⚠️ Gemini cevabı beklenmedik formatta: ${bodyText.take(300)}")
                return null
            }

        // 🛡️ Gemini bazen ```json ... ``` kod bloğu içinde döndürüyor, temizliyoruz.
        val cleanJson = text.replace(Regex("```json|```"), "").trim()
        val parsed = Json.parseToJsonElement(cleanJson).jsonArray

        val selected = mutableListOf<SelectedNewsItem>()
        for (entry in parsed) {
            val obj = entry.jsonObject
            val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: continue
            val candidate = candidates.getOrNull(idx - 1) ?: continue
            val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: defaultTagFor(sport)
            val summary = obj["summary"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
                ?: candidate.description.take(160)
            selected.add(SelectedNewsItem(tag, candidate.title, summary, candidate.url, candidate.source))
        }
        return if (selected.isEmpty()) null else selected.take(3)
    }

    private fun saveNews(sport: String, items: List<SelectedNewsItem>) {
        try {
            openConnection().use { conn ->
                // 💡 Basit "tam yenileme" — sadece BU SPORA ait eski kayıtları
                // silip yeni 3 haberi yazıyoruz, diğer sporun kayıtlarına dokunmuyoruz.
                conn.prepareStatement("DELETE FROM news_items WHERE sport = ?").use { stmt ->
                    stmt.setString(1, sport)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO news_items (sport, tag, title, summary, source, url, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                ).use { stmt ->
                    val now = System.currentTimeMillis()
                    for (item in items) {
                        stmt.setString(1, sport)
                        stmt.setString(2, item.tag)
                        stmt.setString(3, item.title)
                        stmt.setString(4, item.summary)
                        stmt.setString(5, item.source)
                        stmt.setString(6, item.sourceUrl)
                        stmt.setLong(7, now)
                        stmt.executeUpdate()
                    }
                }
            }
            println("✅ [$sport] ${items.size} haber kaydedildi.")
        } catch (e: Exception) {
            println("🔥 saveNews($sport) HATASI: ${e.message}")
        }
    }

    fun fetchLatestNews(sport: String): List<NewsItem> {
        val results = mutableListOf<NewsItem>()
        try {
            openConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT tag, title, summary, source, url FROM news_items WHERE sport = ? ORDER BY created_at DESC LIMIT 3"
                ).use { stmt ->
                    stmt.setString(1, sport)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                NewsItem(
                                    tag = rs.getString("tag") ?: defaultTagFor(sport),
                                    title = rs.getString("title") ?: "",
                                    summary = rs.getString("summary") ?: "",
                                    source = rs.getString("source") ?: "",
                                    url = rs.getString("url") ?: ""
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchLatestNews($sport) HATASI: ${e.message}")
        }
        return results
    }
}
