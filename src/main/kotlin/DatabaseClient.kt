import java.sql.DriverManager

object DatabaseClient {

    private val countryMap = mapOf(
        // Avrupa
        "ingiltere" to "england", "turkiye" to "turkey", "türkiye" to "turkey",
        "almanya" to "germany", "fransa" to "france", "ispanya" to "spain",
        "italya" to "italy", "hollanda" to "netherlands", "portekiz" to "portugal",
        "polonya" to "poland", "rusya" to "russia", "belcika" to "belgium", "belçika" to "belgium",
        "isvicre" to "switzerland", "isviçre" to "switzerland", "isveç" to "sweden", "isvec" to "sweden",
        "norvec" to "norway", "norveç" to "norway", "danimarka" to "denmark",
        "yunanistan" to "greece", "hirvatistan" to "croatia", "hırvatistan" to "croatia",
        "sirbistan" to "serbia", "sırbistan" to "serbia", "romanya" to "romania",
        "ukrayna" to "ukraine", "avusturya" to "austria", "cekyas" to "czech republic",
        "bosna hersek" to "bosnia-herzegovina", "bosna" to "bosnia-herzegovina",

        // Amerika (Kuzey & Güney)
        "kanada" to "canada", "amerika" to "united states", "abd" to "united states",
        "brezilya" to "brazil", "arjantin" to "argentina", "kolombiya" to "colombia",
        "uruguay" to "uruguay", "meksika" to "mexico", "sili" to "chile", "şili" to "chile",
        "paraguay" to "paraguay", "peru" to "peru", "ekvador" to "ecuador",

        // Afrika
        "fildisi sahili" to "cote d'ivoire", "fildişi sahili" to "cote d'ivoire", "fildişi" to "cote d'ivoire",
        "nijerya" to "nigeria", "kamerun" to "cameroon", "senegal" to "senegal",
        "fas" to "morocco", "cezayir" to "algeria", "gana" to "ghana", "gane" to "ghana",
        "mısır" to "egypt", "misir" to "egypt", "guney afrika" to "south africa",
        "güney afrika" to "south africa", "demokratik kongo" to "dr congo", "kongo" to "congo",

        // Asya & Okyanusya
        "japonya" to "japan", "guney kore" to "korea, south", "güney kore" to "korea, south",
        "cin" to "china", "çin" to "china", "iran" to "iran", "avustralya" to "australia",
        "suudi arabistan" to "saudi arabia", "katar" to "qatar", "ozbekistan" to "uzbekistan"
    )

    private fun getConnection(): java.sql.Connection {
        val resource = object {}.javaClass.classLoader.getResource("football.db")
            ?: throw IllegalStateException("❌ 'football.db' resources klasöründe bulunamadı!")

        val dbPath = resource.file
        return DriverManager.getConnection("jdbc:sqlite:$dbPath")
    }

    private fun String.toStandardSearch(): String {
        val lower = this.lowercase()
            .replace("ı", "i")
            .replace("İ", "i")
            .replace("ğ", "g")
            .replace("Ğ", "g")
            .replace("ü", "u")
            .replace("Ü", "u")
            .replace("ş", "s")
            .replace("Ş", "s")
            .replace("ö", "o")
            .replace("Ö", "o")
            .replace("ç", "c")
            .replace("Ç", "c")
            .trim()

        return countryMap[lower] ?: lower
    }

    fun fetchPlayersByClub(clubOrCountry: String): List<Player> {
        val cleanParam = clubOrCountry.toStandardSearch()
        val isCountryParam = countryMap.containsValue(cleanParam) || countryMap.containsKey(clubOrCountry.lowercase())

        val sql = """
            SELECT DISTINCT p.id, p.name, p.position, p.nationality 
            FROM players p 
            WHERE p.id IN (
                SELECT transfer_id FROM transfers 
                WHERE LOWER(from_club) LIKE ? OR LOWER(to_club) LIKE ?
            )
            OR LOWER(REPLACE(p.nationality, char(160), ' ')) LIKE ?
        """.trimIndent()

        val players = mutableListOf<Player>()

        try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    val param = "%$cleanParam%"
                    stmt.setString(1, param)
                    stmt.setString(2, param)
                    stmt.setString(3, param)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val rawNat = rs.getString("nationality") ?: ""
                            val cleanNat = cleanNationalityText(rawNat)
                            val pId = rs.getInt("id")

                            val player = Player(
                                id = pId,
                                name = rs.getString("name") ?: "",
                                position = rs.getString("position") ?: "",
                                nationality = cleanNat,
                                team = clubOrCountry,
                                transferId = pId
                            )

                            if (!isCountryParam || isPrimaryCountryMatch(player, cleanParam)) {
                                players.add(player)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchPlayersByClub HATASI: ${e.message}")
        }

        return players
    }

    fun fetchCommonPlayers(param1: String, param2: String): List<Player> {
        val clean1 = param1.toStandardSearch()
        val clean2 = param2.toStandardSearch()

        val sql = """
            SELECT DISTINCT p.id, p.name, p.position, p.nationality 
            FROM players p 
            WHERE (
                p.id IN (
                    SELECT transfer_id FROM transfers 
                    WHERE LOWER(from_club) LIKE ? OR LOWER(to_club) LIKE ?
                ) 
                OR LOWER(REPLACE(p.nationality, char(160), ' ')) LIKE ?
            ) 
            AND (
                p.id IN (
                    SELECT transfer_id FROM transfers 
                    WHERE LOWER(from_club) LIKE ? OR LOWER(to_club) LIKE ?
                ) 
                OR LOWER(REPLACE(p.nationality, char(160), ' ')) LIKE ?
            )
        """.trimIndent()

        val rawList = mutableListOf<Player>()

        try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    val p1 = "%$clean1%"
                    val p2 = "%$clean2%"

                    stmt.setString(1, p1)
                    stmt.setString(2, p1)
                    stmt.setString(3, p1)

                    stmt.setString(4, p2)
                    stmt.setString(5, p2)
                    stmt.setString(6, p2)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val rawNat = rs.getString("nationality") ?: ""
                            val cleanNat = cleanNationalityText(rawNat)
                            val pId = rs.getInt("id")

                            rawList.add(
                                Player(
                                    id = pId,
                                    name = rs.getString("name") ?: "",
                                    position = rs.getString("position") ?: "",
                                    nationality = cleanNat,
                                    team = "$param1 / $param2",
                                    transferId = pId
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchCommonPlayers HATASI: ${e.message}")
        }

        return rawList.filter { isStrictMatch(it, clean1, clean2) }
    }

    private fun cleanNationalityText(rawNat: String): String {
        return rawNat.replace('\u00a0', ' ')
            .replace(160.toChar(), ' ')
            .replace(Regex("Mevki|Uyruk|[0-9()]+"), "")
            .trim()
    }

    private fun isPrimaryCountryMatch(player: Player, countryParam: String): Boolean {
        val cleanNat = player.nationality.lowercase().trim()
        val parts = cleanNat.split(Regex("\\s{2,}|,|/|-")).map { it.trim() }.filter { it.isNotEmpty() }
        val primaryBlock = parts.firstOrNull() ?: cleanNat
        val targetCountry = countryParam.lowercase().trim()

        return primaryBlock == targetCountry || primaryBlock.startsWith("$targetCountry ")
    }

    private fun isStrictMatch(player: Player, param1: String, param2: String): Boolean {
        val isParam1Country = countryMap.containsValue(param1)
        val isParam2Country = countryMap.containsValue(param2)

        val matches1 = if (isParam1Country) isPrimaryCountryMatch(player, param1) else true
        val matches2 = if (isParam2Country) isPrimaryCountryMatch(player, param2) else true

        return matches1 && matches2
    }
}