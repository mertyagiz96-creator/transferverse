import java.io.File
import java.sql.Connection
import java.sql.DriverManager

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
        "ozbekistan" to "uzbekistan"
    )

    data class TransferRecord(
        val playerId: Int,
        val playerName: String,
        val position: String,
        val nationality: String,
        val birthDate: String?,
        val fromClub: String,
        val toClub: String,
        val season: String,
        val transferId: Int
    )

    private val memoryCache: List<TransferRecord> by lazy {
        println("🚀 RAM'e veri yükleme işlemi başlatılıyor...")
        val startTime = System.currentTimeMillis()
        val records = mutableListOf<TransferRecord>()

        val dbFile = File(System.getProperty("java.io.tmpdir"), "football_cached.db")
        if (!dbFile.exists()) {
            println("📂 football_cached.db bulunamadı, resource'dan kopyalanıyor...")
            val inputStream = object {}.javaClass.classLoader.getResourceAsStream("football.db")
                ?: throw IllegalStateException("❌ 'football.db' resources klasöründe bulunamadı!")

            inputStream.use { input ->
                dbFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        val sql = """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate, t.from_club, t.to_club, t.season, t.transfer_id 
            FROM players p 
            JOIN transfers t ON p.id = t.transfer_id
        """.trimIndent()

        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            records.add(
                                TransferRecord(
                                    playerId = rs.getInt("id"),
                                    playerName = rs.getString("name") ?: "",
                                    position = rs.getString("position") ?: "",
                                    nationality = cleanNationalityText(rs.getString("nationality") ?: ""),
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
        } catch (e: Exception) {
            println("🔥 Canlıda yükleme hatası: ${e.message}")
            e.printStackTrace()
        }

        val duration = System.currentTimeMillis() - startTime
        println("✅ RAM'e yükleme tamamlandı! Süre: ${duration}ms, Toplam kayıt: ${records.size}")
        records
    }

    private fun String.toStandardSearch(): String {
        return this.lowercase()
            .replace("ı", "i").replace("İ", "i")
            .replace("ğ", "g").replace("Ğ", "g")
            .replace("ü", "u").replace("Ü", "u")
            .replace("ş", "s").replace("Ş", "s")
            .replace("ö", "o").replace("Ö", "o")
            .replace("ç", "c").replace("Ç", "c")
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

    private fun matchesOriginalClub(clubName: String?, targetClub: String): Boolean {
        if (clubName == null) return false
        val cleanClub = clubName.toStandardSearch()
        val target = targetClub.toStandardSearch()

        if (isYouthClub(cleanClub)) {
            return false
        }

        return cleanClub.contains(target)
    }

    private fun isPrimaryCountryMatch(playerNationality: String, searchParam: String, mappedCountry: String): Boolean {
        val stdNat = playerNationality.toStandardSearch()
        val stdSearch = searchParam.toStandardSearch()
        val stdMapped = mappedCountry.toStandardSearch()

        val natWords = stdNat.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val primaryNationality = natWords.firstOrNull() ?: return false

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

    fun fetchAllUniqueSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()

        for (record in memoryCache) {
            if (record.fromClub.isNotBlank()) {
                val cleaned = record.fromClub.replace('\u00a0', ' ').trim()
                if (cleaned.length > 1 && !isYouthClub(cleaned)) suggestions.add(cleaned)
            }
            if (record.toClub.isNotBlank()) {
                val cleaned = record.toClub.replace('\u00a0', ' ').trim()
                if (cleaned.length > 1 && !isYouthClub(cleaned)) suggestions.add(cleaned)
            }
            if (record.nationality.isNotBlank()) {
                val cleaned = record.nationality.replace('\u00a0', ' ').trim()
                if (cleaned.length > 1) suggestions.add(cleaned)
            }
        }

        val mappedTurkishCountries = countryMap.keys
            .filter { it.length > 2 }
            .map { it.replaceFirstChar { char -> char.uppercase() } }

        suggestions.addAll(mappedTurkishCountries)
        suggestions.addAll(listOf(
            "Türkiye", "Mısır", "Fas", "Cezayir", "Tunus", "Nijerya", "Gana", "Kamerun",
            "Senegal", "Fildişi Sahili", "Güney Afrika", "Japonya", "Güney Kore", "İran",
            "Suudi Arabistan", "Katar", "Özbekistan", "Brezilya", "Arjantin", "Kolombiya",
            "Uruguay", "Meksika", "Şili", "İsveç", "Norveç", "Danimarka", "Çekya"
        ))

        return suggestions.sorted()
    }

    fun fetchPlayersByClub(clubOrCountry: String): List<Player> {
        val stdParam = clubOrCountry.toStandardSearch()
        val mappedCountry = countryMap[stdParam] ?: stdParam
        val isCountry = isCountryParam(clubOrCountry)

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        for (record in memoryCache) {
            val pId = record.playerId
            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(record.fromClub, record.toClub, record.season))

            if (!playerInfoMap.containsKey(pId)) {
                playerInfoMap[pId] = Player(
                    playerId = pId,
                    name = record.playerName,
                    position = record.position,
                    nationality = record.nationality,
                    team = clubOrCountry,
                    birthDate = record.birthDate,
                    season1 = null,
                    season2 = null,
                    transferId = record.transferId
                )
            }
        }

        val resultList = mutableListOf<Player>()

        for ((pId, transfers) in playerAllTransfers) {
            val player = playerInfoMap[pId] ?: continue
            val validSeasonsForClub = mutableListOf<String>()
            var hasRealMatch = false

            for (tr in transfers) {
                val f = tr.first
                val t = tr.second
                val s = tr.third

                if (isCountry) {
                    if (isPrimaryCountryMatch(player.nationality, clubOrCountry, mappedCountry)) {
                        hasRealMatch = true
                        validSeasonsForClub.add(s)
                    }
                } else {
                    val isFromReal = matchesOriginalClub(f, clubOrCountry)
                    val isToReal = matchesOriginalClub(t, clubOrCountry)

                    if (isFromReal || isToReal) {
                        hasRealMatch = true
                        validSeasonsForClub.add(s)
                    }
                }
            }

            if (hasRealMatch && validSeasonsForClub.isNotEmpty()) {
                val seasonValue = if (isCountry) "-" else validSeasonsForClub.minOrNull()
                resultList.add(player.copy(season1 = seasonValue))
            }
        }

        return resultList.distinctBy { it.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    fun fetchCommonPlayers(param1: String, param2: String): List<Player> {
        val std1 = param1.toStandardSearch()
        val std2 = param2.toStandardSearch()

        val mappedCountry1 = countryMap[std1] ?: std1
        val mappedCountry2 = countryMap[std2] ?: std2

        val isParam1Country = isCountryParam(param1)
        val isParam2Country = isCountryParam(param2)

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        for (record in memoryCache) {
            val pId = record.playerId
            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(record.fromClub, record.toClub, record.season))

            if (!playerInfoMap.containsKey(pId)) {
                playerInfoMap[pId] = Player(
                    playerId = pId,
                    name = record.playerName,
                    position = record.position,
                    nationality = record.nationality,
                    team = "$param1 / $param2",
                    birthDate = record.birthDate,
                    season1 = null,
                    season2 = null,
                    transferId = record.transferId
                )
            }
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

                val match1 = if (isParam1Country) {
                    isPrimaryCountryMatch(player.nationality, param1, mappedCountry1)
                } else {
                    matchesOriginalClub(f, param1) || matchesOriginalClub(t, param1)
                }

                val match2 = if (isParam2Country) {
                    isPrimaryCountryMatch(player.nationality, param2, mappedCountry2)
                } else {
                    matchesOriginalClub(f, param2) || matchesOriginalClub(t, param2)
                }

                if (match1) seasons1.add(s)
                if (match2) seasons2.add(s)
            }

            val condition1Met = if (isParam1Country) {
                isPrimaryCountryMatch(player.nationality, param1, mappedCountry1)
            } else {
                seasons1.isNotEmpty()
            }

            val condition2Met = if (isParam2Country) {
                isPrimaryCountryMatch(player.nationality, param2, mappedCountry2)
            } else {
                seasons2.isNotEmpty()
            }

            val isValidCommonMatch = if (isParam1Country && isParam2Country) {
                false
            } else {
                condition1Met && condition2Met
            }

            if (isValidCommonMatch) {
                val min1 = if (isParam1Country) "-" else seasons1.minOrNull() ?: "-"
                val min2 = if (isParam2Country) "-" else seasons2.minOrNull() ?: "-"

                rawList.add(player.copy(season1 = min1, season2 = min2))
            }
        }

        return rawList.distinctBy { it.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    private fun cleanNationalityText(rawNat: String): String {
        return rawNat.replace('\u00a0', ' ')
            .replace(160.toChar(), ' ')
            .replace(Regex("Mevki|Uyruk|[0-9()]+"), "")
            .trim()
    }
}