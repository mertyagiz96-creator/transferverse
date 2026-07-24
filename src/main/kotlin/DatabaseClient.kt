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
        "manchester city" to "man city"
    )

    // Arama teriminin standartlaştırılmış + (varsa) alias'ı çözülmüş hâlini döndürür.
    // SQL sorgusuna ve Kotlin tarafındaki matchesOriginalClub kontrolüne aynı terim
    // gönderilsin diye tek bir yerden hesaplanıyor.
    private fun resolveClubSearchTerm(raw: String): String {
        val std = raw.toStandardSearch()
        return clubAliasMap[std] ?: std
    }

    // 💡 Tek, kalıcı connection. SQLite dosya tabanlı olduğu için tek connection yeterli ve
    // her istekte yeni connection açıp kapatma maliyetini ortadan kaldırır.
    private val connection: Connection by lazy { createConnection() }

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

        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
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

    fun fetchAllUniqueSuggestions(): List<String> {
        val suggestions = mutableSetOf<String>()
        val sql = """
            SELECT DISTINCT from_club FROM transfers UNION
            SELECT DISTINCT to_club FROM transfers UNION
            SELECT DISTINCT nationality FROM players
        """.trimIndent()

        try {
            connection.prepareStatement(sql).use { stmt ->
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
            "Uruguay", "Meksika", "Şili", "İsveç", "Norveç", "Danimarka", "Çekya"
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
        val isCountry = isCountryParam(clubOrCountry)
        val resolvedClubTerm = resolveClubSearchTerm(clubOrCountry)

        val sql = if (isCountry) {
            """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate, t.from_club, t.to_club, t.season 
            FROM players p 
            JOIN transfers t ON p.id = t.transfer_id
            WHERE p.nationality_std LIKE ?
            """.trimIndent()
        } else {
            """
            SELECT p.id, p.name, p.position, p.nationality, p.birthdate, t.from_club, t.to_club, t.season 
            FROM players p 
            JOIN transfers t ON p.id = t.transfer_id
            WHERE t.from_club_std LIKE ? OR t.to_club_std LIKE ?
            """.trimIndent()
        }

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        try {
            connection.prepareStatement(sql).use { stmt ->
                if (isCountry) {
                    val searchTerm = "%$mappedCountry%"
                    stmt.setString(1, searchTerm)
                } else {
                    val searchTerm = "%$resolvedClubTerm%"
                    stmt.setString(1, searchTerm)
                    stmt.setString(2, searchTerm)
                }

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
                                transferId = pId
                            )
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
                    val isFromReal = matchesOriginalClub(f, resolvedClubTerm)
                    val isToReal = matchesOriginalClub(t, resolvedClubTerm)

                    if (isFromReal || isToReal) {
                        hasRealMatch = true
                        validSeasonsForClub.add(s)
                    }
                }
            }

            if (hasRealMatch && validSeasonsForClub.isNotEmpty()) {
                val seasonValue = if (isCountry) "-" else validSeasonsForClub.minOrNull()
                val finalPlayer = player.copy(season1 = seasonValue)
                resultList.add(finalPlayer)
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

        val resolvedClubTerm1 = resolveClubSearchTerm(param1)
        val resolvedClubTerm2 = resolveClubSearchTerm(param2)

        // 💡 SQL'de "aday oyuncu id" havuzunu daraltıyoruz: iki parametreden en az biriyle
        // eşleşen transfer/oyuncu satırlarını çekiyoruz (OR mantığıyla), sonrasında Kotlin'de
        // her iki koşulun da (AND) sağlandığı oyuncuları filtreliyoruz. Bu sayede yine
        // 1.1M satırlık tam tabloyu değil, sadece iki arama terimiyle eşleşen satırları çekiyoruz.
        val sql = buildString {
            append(
                """
                SELECT p.id, p.name, p.position, p.nationality, p.birthdate, t.from_club, t.to_club, t.season 
                FROM players p 
                JOIN transfers t ON p.id = t.transfer_id
                WHERE 
                """.trimIndent()
            )

            val conditions = mutableListOf<String>()
            if (isParam1Country) conditions.add("p.nationality_std LIKE ?") else conditions.add("(t.from_club_std LIKE ? OR t.to_club_std LIKE ?)")
            if (isParam2Country) conditions.add("p.nationality_std LIKE ?") else conditions.add("(t.from_club_std LIKE ? OR t.to_club_std LIKE ?)")

            append(conditions.joinToString(" OR "))
        }

        val playerAllTransfers = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()
        val playerInfoMap = mutableMapOf<Int, Player>()

        try {
            connection.prepareStatement(sql).use { stmt ->
                var paramIndex = 1

                if (isParam1Country) {
                    stmt.setString(paramIndex++, "%$mappedCountry1%")
                } else {
                    val term = "%$resolvedClubTerm1%"
                    stmt.setString(paramIndex++, term)
                    stmt.setString(paramIndex++, term)
                }

                if (isParam2Country) {
                    stmt.setString(paramIndex++, "%$mappedCountry2%")
                } else {
                    val term = "%$resolvedClubTerm2%"
                    stmt.setString(paramIndex++, term)
                    stmt.setString(paramIndex, term)
                }

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
                                transferId = pId
                            )
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

                val match1 = if (isParam1Country) {
                    isPrimaryCountryMatch(player.nationality, param1, mappedCountry1)
                } else {
                    matchesOriginalClub(f, resolvedClubTerm1) || matchesOriginalClub(t, resolvedClubTerm1)
                }

                val match2 = if (isParam2Country) {
                    isPrimaryCountryMatch(player.nationality, param2, mappedCountry2)
                } else {
                    matchesOriginalClub(f, resolvedClubTerm2) || matchesOriginalClub(t, resolvedClubTerm2)
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

            // 💡 İki ülke aratıldığında aynı anda iki ana uyruğa sahip olmak imkansız olduğundan doğrudan elenir
            val isValidCommonMatch = if (isParam1Country && isParam2Country) {
                false
            } else {
                condition1Met && condition2Met
            }

            if (isValidCommonMatch) {
                val min1 = if (isParam1Country) "-" else seasons1.minOrNull() ?: "-"
                val min2 = if (isParam2Country) "-" else seasons2.minOrNull() ?: "-"

                val finalPlayer = player.copy(season1 = min1, season2 = min2)
                rawList.add(finalPlayer)
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