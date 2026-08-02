import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable

// 💡 "Oyuncu Modu" için yeni, izole veri sınıfları — mevcut Player sınıfına ya da
// başka bir endpoint'e hiç dokunmuyor, tamamen ek/ayrı bir yapı.
@Serializable
data class ClubSeason(val club: String, val season: String)

@Serializable
data class MultiClubPlayerResult(
    val playerName: String,
    val position: String,
    val clubs: List<ClubSeason>,
    val imageUrl: String? = null // 💡 YENİ: Cevap açıldıktan sonra göstermek için
)

object DatabaseClient {

    private val countryMap = mapOf(
        "ingiltere" to "england", "england" to "england", "birlesik krallik" to "england", "uk" to "england",
        "turkiye" to "turkey", "türkiye" to "turkey", "turkey" to "turkey",
        "almanya" to "germany", "germany" to "germany",
        "fransa" to "france", "france" to "france",
        "ispanya" to "spain", "spain" to "spain",
        "italya" to "italy", "italy" to "italy",
        "hollanda" to "netherlands", "netherlands" to "netherlands", "nederland" to "netherlands",
        "portekiz" to "portugal", "portugal" to "portugal",
        "polonya" to "poland", "poland" to "poland",
        "rusya" to "russia", "russia" to "russia",
        "belcika" to "belgium", "belçika" to "belgium", "belgium" to "belgium",
        "isvicre" to "switzerland", "isviçre" to "switzerland", "switzerland" to "switzerland",
        "isvec" to "sweden", "isveç" to "sweden", "sweden" to "sweden",
        "norvec" to "norway", "norveç" to "norway", "norway" to "norway",
        "danimarka" to "danmark", "denmark" to "denmark",
        "yunanistan" to "greece", "greece" to "greece",
        "hirvatistan" to "croatia", "hırvatistan" to "croatia", "croatia" to "croatia",
        "sirbistan" to "serbia", "sırbistan" to "serbia", "serbia" to "serbia",
        "romanya" to "romania", "romania" to "romania",
        "ukrayna" to "ukraine", "ukraine" to "ukraine",
        "avusturya" to "austria", "austria" to "austria",

        "cek" to "czech republic", "çek" to "czech republic",
        "cekya" to "czech republic", "çekya" to "czech republic",
        "cek cumhuriyeti" to "czech republic", "çek cumhuriyeti" to "czech republic",
        "czech republic" to "czech republic", "czechia" to "czech republic",
        "cekoslovakya" to "czechoslovakia", "çekoslovakya" to "czechoslovakia",

        "bosna hersek" to "bosnia-herzegovina", "bosna" to "bosnia-herzegovina",
        "kanada" to "canada", "amerika" to "united states", "abd" to "united states", "united states" to "united states",
        "brezilya" to "brazil", "brazil" to "brazil",
        "arjantin" to "argentina", "argentina" to "argentina",
        "kolombiya" to "colombia", "colombia" to "colombia",
        "uruguay" to "uruguay",
        "meksika" to "mexico", "mexico" to "mexico",
        "sili" to "chile", "şili" to "chile", "chile" to "chile",
        "paraguay" to "paraguay",
        "peru" to "peru",
        "ekvador" to "ecuador", "ecuador" to "ecuador",
        "fildisi sahili" to "cote d'ivoire", "fildişi sahili" to "cote d'ivoire", "fildişi" to "cote d'ivoire",
        "nijerya" to "nigeria", "nigeria" to "nigeria",
        "kamerun" to "cameroon", "cameroon" to "cameroon",
        "senegal" to "senegal",
        "fas" to "morocco", "morocco" to "morocco",
        "cezayir" to "algeria", "algeria" to "algeria",
        "gana" to "ghana", "ghana" to "ghana",
        "mısır" to "egypt", "misir" to "egypt", "egypt" to "egypt",
        "guney afrika" to "south africa", "güney afrika" to "south africa",
        "demokratik kongo" to "dr congo", "kongo" to "congo",
        "japonya" to "japan", "japan" to "japan",
        "guney kore" to "korea, south", "güney kore" to "korea, south", "kore" to "korea, south",
        "cin" to "china", "çin" to "china", "china" to "china",
        "iran" to "iran",
        "avustralya" to "australia", "australia" to "australia",
        "suudi arabistan" to "saudi arabia",
        "katar" to "qatar", "qatar" to "qatar",
        "ozbekistan" to "uzbekistan",

        // 💡 Aşağıdakiler ek olarak eklendi, mevcut hiçbir eşleme değiştirilmedi
        "iskocya" to "scotland", "scotland" to "scotland",
        "galler" to "wales", "wales" to "wales",
        "irlanda" to "ireland", "ireland" to "ireland", "republic of ireland" to "ireland",
        "kuzey irlanda" to "northern ireland", "northern ireland" to "northern ireland",
        "macaristan" to "hungary", "hungary" to "hungary",
        "slovakya" to "slovakia", "slovakia" to "slovakia",
        "slovenya" to "slovenia", "slovenia" to "slovenia",
        "bulgaristan" to "bulgaria", "bulgaria" to "bulgaria",
        "finlandiya" to "finland", "finland" to "finland",
        "izlanda" to "iceland", "iceland" to "iceland",
        "israil" to "israel", "israel" to "israel",
        "arnavutluk" to "albania", "albania" to "albania",
        "karadag" to "montenegro", "montenegro" to "montenegro",
        "kuzey makedonya" to "north macedonia", "north macedonia" to "north macedonia", "makedonya" to "north macedonia",
        "kosova" to "kosovo", "kosovo" to "kosovo",
        "gurcistan" to "georgia", "georgia" to "georgia",
        "ermenistan" to "armenia", "armenia" to "armenia",
        "azerbaycan" to "azerbaijan", "azerbaijan" to "azerbaijan",
        "litvanya" to "lithuania", "lithuania" to "lithuania",
        "letonya" to "latvia", "latvia" to "latvia",
        "estonya" to "estonia", "estonia" to "estonia",
        "kibris" to "cyprus", "kıbrıs" to "cyprus", "cyprus" to "cyprus",
        "malta" to "malta",
        "luksemburg" to "luxembourg", "lüksemburg" to "luxembourg", "luxembourg" to "luxembourg",
        "hindistan" to "india", "india" to "india",
        "endonezya" to "indonesia", "indonesia" to "indonesia",
        "tayland" to "thailand", "thailand" to "thailand",
        "vietnam" to "vietnam",
        "yeni zelanda" to "new zealand", "new zealand" to "new zealand",
        "tunus" to "tunisia", "tunisia" to "tunisia",
        "mali" to "mali",
        "gine" to "guinea", "guinea" to "guinea"
    )

    // 💡 Bazı kulüpler veritabanında tam isimle değil, kısaltmayla kayıtlı
    // (örn. "Manchester United" değil "Man Utd" olarak geçiyor). Bu sözlük,
    // kullanıcı tam ismi yazınca aramayı veritabanındaki gerçek kısaltmaya
    // çeviriyor. Sadece bilinen istisnalar için, diğer kulüplere dokunmuyor.
    private val clubAliasMap = mapOf(
        "manchester united" to "man utd",
        "manchester utd" to "man utd",
        "man united" to "man utd",
        "manchester city" to "man city",
        "borussia dortmund" to "bor. dortmund"
    )

    // Arama teriminin standartlaştırılmış + (varsa) alias'ı çözülmüş hâlini döndürür.
    // SQL sorgusuna ve Kotlin tarafındaki matchesOriginalClub kontrolüne aynı terim
    // gönderilsin diye tek bir yerden hesaplanıyor.
    private fun resolveClubSearchTerm(raw: String): String {
        val std = raw.toStandardSearch()
        return clubAliasMap[std] ?: std
    }

    // 💡 EŞZAMANLI GÜVENLİK: Tek paylaşılan connection yerine küçük bir havuz (pool)
    // kullanıyoruz. SQLite JDBC sürücüsü, tek bir Connection nesnesinin birden fazla
    // thread tarafından TAM OLARAK AYNI ANDA kullanılmasını güvenli desteklemiyor.
    // Bu havuz sayesinde her istek kendi bağlantısını alıp işini bitirince geri
    // bırakıyor; havuz o an boşsa istek kısa bir süre bekliyor (hata almak yerine).
    // Ayrıca WAL modu, eşzamanlı okumaları çok daha güvenli ve hızlı hale getiriyor.
    private const val POOL_SIZE = 6
    private val connectionPool: java.util.concurrent.BlockingQueue<Connection> by lazy { createConnectionPool() }

    // 🏆 Bil Bakalım global rekoru — tek satırlık küçük bir tablo, sunucu ilk
    // ayağa kalkarken yoksa otomatik oluşturuluyor. Not: sunucunun dosya sistemi
    // geçici (ephemeral), her yeni deploy'da bu tablo (ve rekor) sıfırlanır.
    init {
        try {
            withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS quiz_high_score (
                            id INTEGER PRIMARY KEY CHECK (id = 1),
                            score INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    stmt.execute("INSERT OR IGNORE INTO quiz_high_score (id, score) VALUES (1, 14)")
                }
            }
        } catch (e: Exception) {
            println("🔥 quiz_high_score tablo oluşturma HATASI: ${e.message}")
        }
    }

    fun fetchQuizHighScore(): Int {
        return try {
            withConnection { conn ->
                conn.prepareStatement("SELECT score FROM quiz_high_score WHERE id = 1").use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt("score") else 0 }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchQuizHighScore HATASI: ${e.message}")
            0
        }
    }

    // 📈 Skoru gönderir, mevcut rekordan büyükse günceller — her durumda sonuçtaki
    // (güncel) rekoru döndürür.
    fun submitQuizScore(score: Int): Int {
        return try {
            withConnection { conn ->
                conn.prepareStatement("UPDATE quiz_high_score SET score = MAX(score, ?) WHERE id = 1").use { stmt ->
                    stmt.setInt(1, score)
                    stmt.executeUpdate()
                }
                conn.prepareStatement("SELECT score FROM quiz_high_score WHERE id = 1").use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt("score") else score }
                }
            }
        } catch (e: Exception) {
            println("🔥 submitQuizScore HATASI: ${e.message}")
            score
        }
    }

    private fun createConnectionPool(): java.util.concurrent.BlockingQueue<Connection> {
        val pool: java.util.concurrent.BlockingQueue<Connection> =
            java.util.concurrent.ArrayBlockingQueue(POOL_SIZE)
        repeat(POOL_SIZE) {
            pool.put(createConnection())
        }
        return pool
    }

    // Havuzdan bir bağlantı alıp işi bitince geri bırakan yardımcı fonksiyon.
    // Tüm sorgular artık bunun üzerinden çalışıyor, hiçbiri connection'ı doğrudan paylaşmıyor.
    private fun <T> withConnection(block: (Connection) -> T): T {
        val conn = connectionPool.take()
        try {
            return block(conn)
        } finally {
            connectionPool.put(conn)
        }
    }

    // 💡 Artık runtime'da resources'tan kopyalama YOK. football.db Docker image'ında
    // doğrudan /app/football.db konumunda hazır bulunuyor (bkz. Dockerfile).
    // Local'de çalıştırırken de proje kök dizininde football.db bulunmalı.
    private fun createConnection(): Connection {
        val dbFile = File("football.db")

        if (!dbFile.exists()) {
            throw IllegalStateException(
                "❌ football.db bulunamadı: ${dbFile.absolutePath}. " +
                        "Local çalıştırıyorsanız dosyayı proje kök dizinine kopyalayın."
            )
        }

        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

        // 💡 WAL modu: birden fazla kullanıcı aynı anda arama yaparken okumaları
        // güvenli ve hızlı hale getiriyor. busy_timeout: nadir bir çakışma anında
        // hemen hata vermek yerine kısa süre bekleyip tekrar denemesini sağlıyor.
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA busy_timeout=5000;")
        }

        return conn
    }

    private fun String.toStandardSearch(): String {
        // 💡 ÖNEMLİ SIRA: Türkçe karakter dönüşümlerini lowercase()'DEN ÖNCE yapıyoruz.
        // Sebep: Kotlin'in genel .lowercase() fonksiyonu (locale belirtilmeden), Türkçe
        // büyük "İ" harfini tek bir "i" değil, "i" + görünmez bir nokta işareti (iki ayrı
        // karakter) haline çeviriyor. Bu yüzden önce lowercase() çağrılırsa "İnter" gibi
        // aramalar sessizce bozuluyor ve hiçbir sonuçla eşleşmiyordu. Değişimleri önce
        // yapıp en sona sadece düz ASCII harfler için .lowercase() çağırmak bu sorunu çözüyor.
        return this
            .replace("İ", "i").replace("ı", "i")
            .replace("Ğ", "g").replace("ğ", "g")
            .replace("Ü", "u").replace("ü", "u")
            .replace("Ş", "s").replace("ş", "s")
            .replace("Ö", "o").replace("ö", "o")
            .replace("Ç", "c").replace("ç", "c")
            .lowercase()
            .trim()
    }

    private fun isYouthClub(clubName: String?): Boolean {
        if (clubName == null) return false
        val lower = clubName.toStandardSearch()
        val youthKeywords = listOf(
            "u15", "u16", "u17", "u18", "u19", "u20", "u21", "u23",
            "u-15", "u-16", "u-17", "u-18", "u-19", "u-20", "u-21", "u-23",
            "yth", "youth", "academy", "akademi", "reserves", "amateur", "ii"
        )
        return youthKeywords.any { lower.contains(it) }
    }

    private fun matchesOriginalClub(clubName: String?, resolvedTarget: String): Boolean {
        if (clubName == null) return false
        val cleanClub = clubName.toStandardSearch()

        if (isYouthClub(cleanClub)) {
            return false
        }

        return cleanClub.contains(resolvedTarget)
    }

    // 🎯 Tam eşleşme kontrolü — "Inter" ile "Inter Miami" gibi, biri diğerinin
    // içinde geçen ama FARKLI kulüpleri ayırt etmek için kullanılıyor.
    private fun isExactClubMatch(clubName: String?, resolvedTarget: String): Boolean {
        if (clubName == null) return false
        val cleanClub = clubName.toStandardSearch()
        if (isYouthClub(cleanClub)) return false
        return cleanClub == resolvedTarget
    }

    // 💡 Sadece SADECE ilk/ana uyruğu baz alan kusursuz kontrol
    private fun isPrimaryCountryMatch(playerNationality: String, searchParam: String, mappedCountry: String): Boolean {
        val stdNat = playerNationality.toStandardSearch()
        val stdSearch = searchParam.toStandardSearch()
        val stdMapped = mappedCountry.toStandardSearch()

        // Birden fazla uyruk verisi ÇİFT BOŞLUKLA ayrılıyor (örn: "Cameroon  France").
        // Bu yüzden sadece çift boşlukta bölüyoruz; tek boşluklu veya apostroflu çok
        // kelimelik ülke isimleri (örn. "Czech Republic", "Cote d'Ivoire") bu sayede
        // bölünmeden tek parça kalıyor ve doğru şekilde eşleşiyor.
        val primaryNationality = stdNat.split(Regex("\\s{2,}")).firstOrNull()?.trim()
            ?: return false

        return primaryNationality == stdSearch || primaryNationality == stdMapped
    }

    private fun isCountryParam(param: String): Boolean {
        val std = param.toStandardSearch()
        return countryMap.containsKey(std) || countryMap.containsValue(std)
    }

    private fun parseSeasonToSortValue(season: String?): Int {
        if (season.isNullOrBlank() || season == "-") return -1
        val digits = season.filter { it.isDigit() }
        if (digits.length >= 2) {
            val yearPrefix = digits.substring(0, 2).toIntOrNull() ?: 0
            return if (yearPrefix >= 50) 1900 + yearPrefix else 2000 + yearPrefix
        }
        return 0
    }

    // 🖼️ Kulüp logoları — club_logos tablosundan tek seferde tüm eşlemeyi çekiyor
    fun fetchAllClubLogos(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            withConnection { conn ->
                conn.prepareStatement("SELECT club_name_std, logo_url FROM club_logos").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val key = rs.getString("club_name_std") ?: continue
                            val url = rs.getString("logo_url") ?: continue
                            result[key] = url
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchAllClubLogos HATASI: ${e.message}")
        }
        return result
    }

    fun fetchAllUniqueSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()
        val sql = """
            SELECT DISTINCT from_club FROM transfers UNION
            SELECT DISTINCT to_club FROM transfers UNION
            SELECT DISTINCT nationality FROM players
        """.trimIndent()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val value = rs.getString(1)
                            if (!value.isNullOrBlank()) {
                                val cleaned = value.replace('\u00a0', ' ').trim()
                                // 💡 Çift-uyruklu değerler (örn. "Czech Republic  Angola") için
                                // sadece ana/ilk parçayı öneriye ekliyoruz, böylece "Czech Republic X",
                                // "Czech Republic Y" gibi onlarca anlamsız tekrar yerine tek temiz
                                // "Czech Republic" önerisi kalıyor. Kulüp isimlerinde çift boşluk
                                // olmadığı için bu, kulüp önerilerini etkilemiyor.
                                val primaryValue = cleaned.split(Regex("\\s{2,}")).firstOrNull()?.trim() ?: cleaned
                                if (primaryValue.length > 1 && !isYouthClub(primaryValue)) {
                                    suggestions.add(primaryValue)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 suggestions HATASI: ${e.message}")
        }

        val mappedTurkishCountries = countryMap.keys
            .filter { it.length > 2 }
            .map { it.replaceFirstChar { char -> char.uppercase() } }

        suggestions.addAll(mappedTurkishCountries)

        suggestions.addAll(listOf(
            "Türkiye", "Mısır", "Fas", "Cezayir", "Tunus", "Nijerya", "Gana", "Kamerun",
            "Senegal", "Fildişi Sahili", "Güney Afrika", "Japonya", "Güney Kore", "İran",
            "Suudi Arabistan", "Katar", "Özbekistan", "Brezilya", "Arjantin", "Kolombiya",
            "Uruguay", "Meksika", "Şili", "İsveç", "Norveç", "Danimarka", "Çekya",
            // 💡 Veritabanında bu isimlerle DEĞİL, kısaltmayla ("Man Utd", "Man City")
            // kayıtlı olduğu için otomatik listeye giremiyorlardı. Elle ekliyoruz;
            // aramada zaten clubAliasMap üzerinden doğru şekilde çözümleniyor.
            "Manchester United", "Manchester City"
        ))

        return suggestions.sorted()
    }

    // 💡 ARTIK SQL SEVİYESİNDE FİLTRELİYORUZ: from_club_std / to_club_std / nationality_std
    // kolonları + index'ler sayesinde 1.1M satırlık tabloyu taramak yerine sadece eşleşen
    // satırlar çekiliyor. Kotlin tarafındaki matchesOriginalClub/isPrimaryCountryMatch kontrolü
    // ise SQL'in kaba LIKE eşleşmesinden sonra "youth club eleme" ve "tam eşleşme" gibi ince
    // iş mantığını uygulamak için hâlâ çalışıyor (ama artık çok daha az satır üzerinde).
    fun fetchPlayersByClub(clubOrCountry: String): List<Player> {
        val stdParam = clubOrCountry.toStandardSearch()
        val mappedCountry = countryMap[stdParam] ?: stdParam
        val resolvedClubTerm = resolveClubSearchTerm(clubOrCountry)

        // 💡 Artık "ya kulüp ya ülke" seçmiyoruz — countryMap sadece Türkçe->İngilizce
        // çeviri gereken ~90 ülke için var, TÜM dünya ülkelerini kapsamıyor (örn. Togo
        // orada yoktu). Bu yüzden HER zaman hem kulüp hem uyruk alanını kontrol
        // ediyoruz (union), hangisi eşleşirse o sayılıyor.
        val sql = """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate, p.slug, p.image_url, t.from_club, t.to_club, t.season 
            FROM players p 
            JOIN transfers t ON p.id = t.transfer_id
            WHERE t.from_club_std LIKE ? OR t.to_club_std LIKE ? OR p.nationality_std LIKE ?
            """.trimIndent()

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    val clubTerm = "%$resolvedClubTerm%"
                    val countryTerm = "%$mappedCountry%"
                    stmt.setString(1, clubTerm)
                    stmt.setString(2, clubTerm)
                    stmt.setString(3, countryTerm)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val season = rs.getString("season") ?: continue

                            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(fromClub, toClub, season))

                            if (!playerInfoMap.containsKey(pId)) {
                                val rawNat = rs.getString("nationality") ?: ""
                                val playerName = rs.getString("name") ?: ""

                                playerInfoMap[pId] = Player(
                                    playerId = pId,
                                    name = playerName,
                                    position = rs.getString("position") ?: "",
                                    nationality = cleanNationalityText(rawNat),
                                    team = clubOrCountry,
                                    birthDate = rs.getString("birthdate"),
                                    season1 = null,
                                    season2 = null,
                                    transferId = pId,
                                    slug = rs.getString("slug"),
                                    imageUrl = rs.getString("image_url")
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchPlayersByClub HATASI: ${e.message}")
        }

        val resultList = mutableListOf<Player>()

        for ((pId, transfers) in playerAllTransfers) {
            val player = playerInfoMap[pId] ?: continue
            val validSeasonsForClub = mutableListOf<String>()
            var hasClubMatch = false
            val hasCountryMatch = isPrimaryCountryMatch(player.nationality, clubOrCountry, mappedCountry)

            for (tr in transfers) {
                val f = tr.first
                val t = tr.second
                val s = tr.third

                if (matchesOriginalClub(f, resolvedClubTerm) || matchesOriginalClub(t, resolvedClubTerm)) {
                    hasClubMatch = true
                    validSeasonsForClub.add(s)
                }
            }

            if (hasClubMatch || hasCountryMatch) {
                val seasonValue = if (hasClubMatch) validSeasonsForClub.minOrNull() else "-"
                resultList.add(player.copy(season1 = seasonValue))
            }
        }

        // 🎯 Tam kulüp eşleşmesi (örn. tam olarak "Inter") varsa, SADECE onu göster —
        // "Inter Miami" gibi ismi içeren ama farklı bir kulübü karıştırmasın diye.
        val hasExactClubMatch = playerAllTransfers.values.any { transfers ->
            transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm) || isExactClubMatch(t, resolvedClubTerm) }
        }

        val finalList = if (hasExactClubMatch) {
            resultList.filter { player ->
                val transfers = playerAllTransfers[player.transferId] ?: emptyList()
                transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm) || isExactClubMatch(t, resolvedClubTerm) } ||
                        isPrimaryCountryMatch(player.nationality, clubOrCountry, mappedCountry)
            }
        } else {
            resultList
        }

        return finalList.distinctBy { it.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    fun fetchCommonPlayers(param1: String, param2: String): List<Player> {
        val std1 = param1.toStandardSearch()
        val std2 = param2.toStandardSearch()

        val mappedCountry1 = countryMap[std1] ?: std1
        val mappedCountry2 = countryMap[std2] ?: std2

        val resolvedClubTerm1 = resolveClubSearchTerm(param1)
        val resolvedClubTerm2 = resolveClubSearchTerm(param2)

        // 💡 Her iki parametre için de HEM kulüp HEM uyruk kontrolü yapıyoruz (union) —
        // countryMap sadece ~90 ülke için Türkçe çeviri içeriyor, TÜMÜNü kapsamıyor.
        val sql = """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate, p.slug, p.image_url, t.from_club, t.to_club, t.season 
            FROM players p 
            JOIN transfers t ON p.id = t.transfer_id
            WHERE (t.from_club_std LIKE ? OR t.to_club_std LIKE ? OR p.nationality_std LIKE ?)
               OR (t.from_club_std LIKE ? OR t.to_club_std LIKE ? OR p.nationality_std LIKE ?)
            """.trimIndent()

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    val clubTerm1 = "%$resolvedClubTerm1%"
                    val countryTerm1 = "%$mappedCountry1%"
                    val clubTerm2 = "%$resolvedClubTerm2%"
                    val countryTerm2 = "%$mappedCountry2%"

                    stmt.setString(1, clubTerm1)
                    stmt.setString(2, clubTerm1)
                    stmt.setString(3, countryTerm1)
                    stmt.setString(4, clubTerm2)
                    stmt.setString(5, clubTerm2)
                    stmt.setString(6, countryTerm2)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val season = rs.getString("season") ?: continue

                            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(fromClub, toClub, season))

                            if (!playerInfoMap.containsKey(pId)) {
                                val rawNat = rs.getString("nationality") ?: ""
                                val playerName = rs.getString("name") ?: ""

                                playerInfoMap[pId] = Player(
                                    playerId = pId,
                                    name = playerName,
                                    position = rs.getString("position") ?: "",
                                    nationality = cleanNationalityText(rawNat),
                                    team = "$param1 / $param2",
                                    birthDate = rs.getString("birthdate"),
                                    season1 = null,
                                    season2 = null,
                                    transferId = pId,
                                    slug = rs.getString("slug"),
                                    imageUrl = rs.getString("image_url")
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchCommonPlayers HATASI: ${e.message}")
        }

        val rawList = mutableListOf<Player>()

        for ((pId, transfers) in playerAllTransfers) {
            val player = playerInfoMap[pId] ?: continue

            val seasons1 = mutableListOf<String>()
            val seasons2 = mutableListOf<String>()

            for (tr in transfers) {
                val f = tr.first
                val t = tr.second
                val s = tr.third

                if (matchesOriginalClub(f, resolvedClubTerm1) || matchesOriginalClub(t, resolvedClubTerm1)) {
                    seasons1.add(s)
                }
                if (matchesOriginalClub(f, resolvedClubTerm2) || matchesOriginalClub(t, resolvedClubTerm2)) {
                    seasons2.add(s)
                }
            }

            val countryMatch1 = isPrimaryCountryMatch(player.nationality, param1, mappedCountry1)
            val countryMatch2 = isPrimaryCountryMatch(player.nationality, param2, mappedCountry2)

            val condition1Met = seasons1.isNotEmpty() || countryMatch1
            val condition2Met = seasons2.isNotEmpty() || countryMatch2

            if (condition1Met && condition2Met) {
                val min1 = if (seasons1.isNotEmpty()) seasons1.minOrNull() else "-"
                val min2 = if (seasons2.isNotEmpty()) seasons2.minOrNull() else "-"

                rawList.add(player.copy(season1 = min1, season2 = min2))
            }
        }

        // 🎯 Her terim için ayrı ayrı: tam kulüp eşleşmesi varsa (örn. tam "Inter"),
        // sadece onu tercih ediyoruz — "Inter Miami" gibi farklı bir kulübü karıştırmasın.
        fun hasExactMatchFor(term: String): Boolean {
            return playerAllTransfers.values.any { transfers ->
                transfers.any { (f, t, _) -> isExactClubMatch(f, term) || isExactClubMatch(t, term) }
            }
        }
        val exact1 = hasExactMatchFor(resolvedClubTerm1)
        val exact2 = hasExactMatchFor(resolvedClubTerm2)

        val finalList = rawList.filter { player ->
            val transfers = playerAllTransfers[player.transferId] ?: emptyList()

            val ok1 = if (exact1) {
                transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm1) || isExactClubMatch(t, resolvedClubTerm1) } ||
                        isPrimaryCountryMatch(player.nationality, param1, mappedCountry1)
            } else true

            val ok2 = if (exact2) {
                transfers.any { (f, t, _) -> isExactClubMatch(f, resolvedClubTerm2) || isExactClubMatch(t, resolvedClubTerm2) } ||
                        isPrimaryCountryMatch(player.nationality, param2, mappedCountry2)
            } else true

            ok1 && ok2
        }

        return finalList.distinctBy { it.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    // 💡 "OYUNCU MODU" — verilen 2 (ya da daha fazla) kulübün HEPSİNDE gerçekten
    // oynamış rastgele bir oyuncu buluyor. Mevcut fetchPlayersByClub/fetchCommonPlayers
    // fonksiyonlarına hiç dokunmuyor, tamamen ayrı ve izole bir sorgu yolu.
    // 💡 GENİŞLETİLMİŞ: her terim artık ya KULÜP ya da ÜLKE olabilir (Pair'in ikinci
    // değeri isCountry). Ülke terimleri nationality_std üzerinden, kulüp terimleri
    // eskisi gibi from_club_std/to_club_std üzerinden eşleştiriliyor.
    fun fetchPlayerAcrossClubs(terms: List<Pair<String, Boolean>>): MultiClubPlayerResult? {
        if (terms.size < 2) return null

        val resolvedClubTerms = terms.map { (term, isCountry) -> if (isCountry) null else resolveClubSearchTerm(term) }
        val mappedCountryTerms = terms.map { (term, isCountry) ->
            if (isCountry) {
                val std = term.toStandardSearch()
                countryMap[std] ?: std
            } else null
        }

        val sql = buildString {
            append(
                """
                SELECT p.id, p.name, p.position, p.nationality, p.image_url, t.from_club, t.to_club, t.season
                FROM players p
                JOIN transfers t ON p.id = t.transfer_id
                WHERE 
                """.trimIndent()
            )
            val conditions = terms.map { (_, isCountry) ->
                if (isCountry) "p.nationality_std LIKE ?" else "(t.from_club_std LIKE ? OR t.to_club_std LIKE ?)"
            }
            append(conditions.joinToString(" OR "))
        }

        // playerId -> (orijinal terim -> o terime ait en erken sezon, ülke için "-")
        val playerTermSeasons = mutableMapOf<Int, MutableMap<String, String>>()
        val playerNames = mutableMapOf<Int, String>()
        val playerPositions = mutableMapOf<Int, String>()
        val playerImageUrls = mutableMapOf<Int, String?>()
        val playerNationalities = mutableMapOf<Int, String>()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    terms.forEachIndexed { i, (_, isCountry) ->
                        if (isCountry) {
                            stmt.setString(idx++, "%${mappedCountryTerms[i]}%")
                        } else {
                            val p = "%${resolvedClubTerms[i]}%"
                            stmt.setString(idx++, p)
                            stmt.setString(idx++, p)
                        }
                    }

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val pId = rs.getInt("id")
                            val name = rs.getString("name") ?: continue
                            val position = rs.getString("position") ?: ""
                            val imageUrl = rs.getString("image_url")
                            val rawNat = rs.getString("nationality") ?: ""
                            val fromClub = rs.getString("from_club") ?: ""
                            val toClub = rs.getString("to_club") ?: ""
                            val season = rs.getString("season") ?: continue

                            playerNames[pId] = name
                            playerPositions[pId] = position
                            playerImageUrls[pId] = imageUrl
                            playerNationalities[pId] = cleanNationalityText(rawNat)

                            terms.forEachIndexed { tIdx, (originalTerm, isCountry) ->
                                if (isCountry) {
                                    if (isPrimaryCountryMatch(playerNationalities[pId] ?: "", originalTerm, mappedCountryTerms[tIdx] ?: "")) {
                                        val bucket = playerTermSeasons.getOrPut(pId) { mutableMapOf() }
                                        bucket[originalTerm] = "-"
                                    }
                                } else {
                                    val resolved = resolvedClubTerms[tIdx] ?: return@forEachIndexed
                                    if (matchesOriginalClub(fromClub, resolved) || matchesOriginalClub(toClub, resolved)) {
                                        val bucket = playerTermSeasons.getOrPut(pId) { mutableMapOf() }
                                        val existing = bucket[originalTerm]
                                        if (existing == null || existing == "-" || parseSeasonToSortValue(season) < parseSeasonToSortValue(existing)) {
                                            bucket[originalTerm] = season
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchPlayerAcrossClubs HATASI: ${e.message}")
            return null
        }

        // Sadece istenen TÜM terimlerde eşleşmiş oyuncular kalsın
        val fullMatches = playerTermSeasons.filter { (_, m) -> terms.all { (term, _) -> m.containsKey(term) } }

        if (fullMatches.isEmpty()) return null

        val (chosenId, termSeasonMap) = fullMatches.entries.random()
        val playerName = playerNames[chosenId] ?: return null
        val position = playerPositions[chosenId] ?: ""

        return MultiClubPlayerResult(
            playerName = playerName,
            position = position,
            clubs = terms.map { (term, _) -> ClubSeason(term, termSeasonMap[term] ?: "-") },
            imageUrl = playerImageUrls[chosenId]
        )
    }

    // 💡 Not: eski List<String> imzalı sürüm kaldırıldı — Kotlin'de jenerik tipler
    // (List<String> vs List<Pair<String,Boolean>>) JVM bytecode seviyesinde aynı
    // imzaya sıkışıyor (tip silme), bu yüzden iki ayrı fonksiyon olarak duramıyorlardı.
    // Zaten hiçbir yerde eski imza çağrılmıyordu, üstteki yeni imza tek başına yeterli.

    private fun cleanNationalityText(rawNat: String): String {
        return rawNat.replace('\u00a0', ' ')
            .replace(160.toChar(), ' ')
            .replace(Regex("Mevki|Uyruk|[0-9()]+"), "")
            .trim()
    }
}