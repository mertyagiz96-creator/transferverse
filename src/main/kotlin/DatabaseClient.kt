import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import java.io.InputStreamReader
import java.io.File

object DatabaseClient {

    private val countrySynonyms = mapOf(
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
        "bosna hersek" to "bosnia-herzegovina", "bosna" to "bosnia-herzegovina", "bosnia-herzegovina" to "bosnia-herzegovina",
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
        "fildisi sahili" to "cote divoire", "fildişi sahili" to "cote divoire",
        "fildisi" to "cote divoire", "fildişi" to "cote divoire",
        "cote d'ivoire" to "cote divoire", "cote divoire" to "cote divoire",
        "ivory coast" to "cote divoire",
        "nijerya" to "nigeria", "nigeria" to "nigeria",
        "kamerun" to "cameroon", "cameroon" to "cameroon", "kameroon" to "cameroon",
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
        "qatar" to "qatar", "katar" to "qatar",
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

    private val gson = Gson()

    private fun openStream(): InputStreamReader? {
        val directFile = File("/app/src/main/resources/transfers.json")
        if (directFile.exists()) {
            return InputStreamReader(directFile.inputStream(), Charsets.UTF_8)
        }

        val altFile = File("src/main/resources/transfers.json")
        if (altFile.exists()) {
            return InputStreamReader(altFile.inputStream(), Charsets.UTF_8)
        }

        val inputStream = DatabaseClient::class.java.getResourceAsStream("/transfers.json")
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream("transfers.json")
            ?: object {}.javaClass.classLoader.getResourceAsStream("transfers.json")
            ?: return null

        return InputStreamReader(inputStream, Charsets.UTF_8)
    }

    // 🚀 EN KRİTİK DEĞİŞİKLİK: Veriyi bir kez okuyup RAM'de tutan önbellek (In-Memory Cache)
    private val cachedRecords: List<TransferRecord> by lazy {
        val list = mutableListOf<TransferRecord>()
        val reader = openStream()
        if (reader != null) {
            JsonReader(reader).use { jsonReader ->
                jsonReader.beginArray()
                while (jsonReader.hasNext()) {
                    val record = gson.fromJson<TransferRecord>(jsonReader, TransferRecord::class.java)
                    list.add(record)
                }
                jsonReader.endArray()
            }
        }
        println("ÖNBELLEK YÜKLENDİ: Toplam ${list.size} kayıt belleğe alındı.")
        list
    }

    private inline fun forEachRecord(action: (TransferRecord) -> Unit) {
        cachedRecords.forEach(action)
    }

    private fun String.toStandardSearch(): String {
        return this.lowercase()
            .replace("ı", "i").replace("İ", "i")
            .replace("ğ", "g").replace("Ğ", "g")
            .replace("ü", "u").replace("Ü", "u")
            .replace("ş", "s").replace("Ş", "s")
            .replace("ö", "o").replace("Ö", "o")
            .replace("ç", "c").replace("Ç", "c")
            .replace("'", "").replace("'", "").replace("`", "").replace("’", "")
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
        if (isYouthClub(cleanClub)) return false

        if (target == "gs" || target == "galatasaray") {
            return cleanClub.contains("galatasaray") || cleanClub == "gs" || cleanClub.split(Regex("[^a-z0-9]+")).contains("gs")
        }

        return cleanClub.contains(target)
    }

    private fun isCountryParam(param: String): Boolean {
        val stdParam = param.toStandardSearch()
        return countrySynonyms.containsKey(stdParam)
    }

    private fun isCountryMatch(playerNationality: String, searchParam: String): Boolean {
        if (playerNationality.isBlank()) return false

        val primaryNationality = playerNationality.split(Regex("[,/\\s-]"))
            .firstOrNull { it.isNotBlank() } ?: playerNationality

        val stdNat = primaryNationality.toStandardSearch()
        val stdSearch = searchParam.toStandardSearch()
        val resolvedSearch = (countrySynonyms[stdSearch] ?: stdSearch).toStandardSearch()

        val isTargetCote = resolvedSearch.contains("cote") || resolvedSearch.contains("ivory") || resolvedSearch.contains("fildisi")
        val isPlayerCote = stdNat.contains("cote") || stdNat.contains("ivory") || stdNat.contains("fildisi")
        if (isTargetCote || isPlayerCote) {
            return isTargetCote && isPlayerCote
        }

        val isTargetCzech = resolvedSearch.contains("czech") || resolvedSearch.contains("cek")
        val isPlayerCzech = stdNat.contains("czech") || stdNat.contains("cek")
        if (isTargetCzech || isPlayerCzech) {
            return isTargetCzech && isPlayerCzech
        }

        val natTokens = stdNat.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val searchTokens = resolvedSearch.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

        return searchTokens.all { sToken -> natTokens.contains(sToken) }
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
        forEachRecord { record ->
            if (record.fromClub.isNotBlank()) {
                val cleaned = record.fromClub.replace('\u00a0', ' ').trim()
                if (cleaned.length > 1 && !isYouthClub(cleaned)) suggestions.add(cleaned)
            }
            if (record.toClub.isNotBlank()) {
                val cleaned = record.toClub.replace('\u00a0', ' ').trim()
                if (cleaned.length > 1 && !isYouthClub(cleaned)) suggestions.add(cleaned)
            }
            if (record.nationality.isNotBlank()) {
                val cleaned = cleanNationalityText(record.nationality)
                if (cleaned.length > 1) suggestions.add(cleaned)
            }
        }
        suggestions.addAll(countrySynonyms.keys)
        return suggestions.sorted()
    }

    fun fetchPlayersByClub(clubOrCountry: String): List<Player> {
        val isCountry = isCountryParam(clubOrCountry)

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        forEachRecord { record ->
            val pId = record.playerId
            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(record.fromClub, record.toClub, record.season))
            if (!playerInfoMap.containsKey(pId)) {
                playerInfoMap[pId] = Player(
                    playerId = pId, name = record.playerName, position = record.position,
                    nationality = record.nationality, team = clubOrCountry, birthDate = record.birthDate,
                    season1 = null, season2 = null, transferId = record.transferId
                )
            }
        }

        val resultList = mutableListOf<Player>()
        for ((pId, transfers) in playerAllTransfers) {
            val player = playerInfoMap[pId] ?: continue
            val validSeasonsForClub = mutableListOf<String>()
            var hasRealMatch = false

            for (tr in transfers) {
                if (isCountry) {
                    if (isCountryMatch(player.nationality, clubOrCountry)) {
                        hasRealMatch = true
                        validSeasonsForClub.add(tr.third)
                    }
                } else {
                    if (matchesOriginalClub(tr.first, clubOrCountry) || matchesOriginalClub(tr.second, clubOrCountry)) {
                        hasRealMatch = true
                        validSeasonsForClub.add(tr.third)
                    }
                }
            }

            if (hasRealMatch && validSeasonsForSpaceOrList(validSeasonsForClub)) {
                val seasonValue = if (isCountry) "-" else validSeasonsForClub.minOrNull()
                resultList.add(player.copy(season1 = seasonValue))
            }
        }

        return resultList.distinctBy { transfer -> transfer.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    private fun validSeasonsForSpaceOrList(list: List<String>): Boolean = list.isNotEmpty()

    fun fetchCommonPlayers(param1: String, param2: String): List<Player> {
        val isParam1Country = isCountryParam(param1)
        val isParam2Country = isCountryParam(param2)

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        forEachRecord { record ->
            val pId = record.playerId
            playerAllTransfers.getOrPut(pId) { mutableListOf() }.add(Triple(record.fromClub, record.toClub, record.season))
            if (!playerInfoMap.containsKey(pId)) {
                playerInfoMap[pId] = Player(
                    playerId = pId, name = record.playerName, position = record.position,
                    nationality = record.nationality, team = "$param1 / $param2", birthDate = record.birthDate,
                    season1 = null, season2 = null, transferId = record.transferId
                )
            }
        }

        val rawList = mutableListOf<Player>()
        for ((pId, transfers) in playerAllTransfers) {
            val player = playerInfoMap[pId] ?: continue
            val seasons1 = mutableListOf<String>()
            val seasons2 = mutableListOf<String>()

            for (tr in transfers) {
                val match1 = if (isParam1Country) isCountryMatch(player.nationality, param1) else matchesOriginalClub(tr.first, param1) || matchesOriginalClub(tr.second, param1)
                val match2 = if (isParam2Country) isCountryMatch(player.nationality, param2) else matchesOriginalClub(tr.first, param2) || matchesOriginalClub(tr.second, param2)
                if (match1) seasons1.add(tr.third)
                if (match2) seasons2.add(tr.third)
            }

            val condition1Met = if (isParam1Country) isCountryMatch(player.nationality, param1) else seasons1.isNotEmpty()
            val condition2Met = if (isParam2Country) isCountryMatch(player.nationality, param2) else seasons2.isNotEmpty()

            if (condition1Met && condition2Met) {
                val min1 = if (isParam1Country) "-" else seasons1.minOrNull() ?: "-"
                val min2 = if (isParam2Country) "-" else seasons2.minOrNull() ?: "-"
                rawList.add(player.copy(season1 = min1, season2 = min2))
            }
        }

        return rawList.distinctBy { transfer -> transfer.transferId }
            .sortedByDescending { parseSeasonToSortValue(it.season1) }
    }

    private fun cleanNationalityText(rawNat: String): String {
        return rawNat.replace('\u00a0', ' ').replace(160.toChar(), ' ')
            .replace(Regex("Mevki|Uyruk|[0-9()]+"), "").trim()
    }
}