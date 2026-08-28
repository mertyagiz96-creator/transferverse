import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable data class CreateRoomRequest(val playerName: String, val winTarget: Int = 5, val maskingHintEnabled: Boolean = false, val duelMode: String = "genel")
@Serializable data class JoinRoomRequest(val roomCode: String, val playerName: String)
@Serializable data class DuelAnswerRequest(val roomCode: String, val playerName: String, val guess: String)
@Serializable data class DuelPassRequest(val roomCode: String, val playerName: String)
@Serializable data class NextRoundRequest(val roomCode: String)
@Serializable data class QuizScoreSubmission(val score: Int, val mode: String = "genel")

// 🚀 football.db, artık Git LFS ile DEĞİL, GitHub Releases'tan indiriliyor —
// bu, her deploy'da Docker build sırasında tüketilen LFS bant genişliğini
// SIFIRA indiriyor. Dosya zaten varsa (örn. yerel geliştirmede) hiç dokunmuyor.
fun ensureFootballDbExists() {
    val dbFile = File("football.db")
    if (dbFile.exists()) {
        println("✅ football.db zaten mevcut (${dbFile.length() / 1_000_000} MB), indirme atlanıyor.")
        return
    }

    println("⬇️ football.db bulunamadı, GitHub Releases'tan indiriliyor...")
    val dbUrl = "https://github.com/mertyagiz96-creator/transferverse/releases/download/v1.0.0/football.db"

    try {
        URL(dbUrl).openStream().use { input ->
            Files.copy(input, dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        println("✅ football.db başarıyla indirildi (${dbFile.length() / 1_000_000} MB).")
    } catch (e: Exception) {
        // 🛡️ Veritabanı olmadan uygulama zaten çalışamaz — hatayı loglayıp
        // süreci durduruyoruz, Render bunu görüp otomatik yeniden dener.
        System.err.println("❌ football.db indirilemedi: ${e.message}")
        e.printStackTrace()
        throw e
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    // 🚀 Sunucu HERHANGİ bir isteği kabul etmeden ÖNCE, veritabanının hazır
    // olduğundan emin oluyoruz — yoksa ilk gelen istek anında hataya düşerdi.
    ensureFootballDbExists()

    // 🚀 PERFORMANS: oyuncu isim aramasının "bazen hızlı bazen çok yavaş"
    // olmasının ana sebebi buydu — artık gerekli sütun+indeks, sunucu
    // isteklere başlamadan ÖNCE garanti altına alınıyor. Sütun zaten varsa
    // (ikinci ve sonraki her deploy'da) bu neredeyse anında biter.
    DatabaseClient.ensureNameStdColumn()

    // 🚀 Sunucu ayağa kalkarken, basketbol logolarını arka planda (sunucuyu
    // hiç bekletmeden) önceden yükleyip önbelleğe alıyoruz.
    GlobalScope.launch {
        DatabaseClient.preloadAllBasketballLogos()
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        // 🚀 Sıkıştırma (gzip) — index.html tek dosyada ~480KB'a ulaştı (bugün
        // eklenen tüm özellikler yüzünden). Gzip, metin tabanlı içerikte
        // genelde %70-80 küçültme sağlıyor — mobilde yükleme süresini
        // belirgin şekilde kısaltması bekleniyor.
        install(Compression) {
            gzip {
                priority = 1.0
            }
        }
        install(ContentNegotiation) {
            json()
        }
        // 🚀 DÜZELTME: tarayıcı bazen index.html'in ESKİ (önbelleğe alınmış)
        // sürümünü sunuyordu — "bir yenilemede düzeliyor, bir sonrakinde eski
        // haline dönüyor" tuhaflığının sebebi muhtemelen buydu. HTML içeriğini
        // ASLA önbelleğe almamasını söylüyoruz — her istek TAZE gelsin.
        // 🚀 DÜZELTME: CachingOptions API'sinin bu Ktor sürümünde bilinen bir
        // "belirsiz overload" hatası var (expires parametresi bazen null kabul
        // etmiyor, bazen zorunlu görünüyor) — eklentiyi kullanmak yerine,
        // doğrudan yanıt başlığına Cache-Control ekleyen basit bir interceptor
        // kullanıyoruz. Aynı amaca hizmet ediyor, daha güvenilir.
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.pipeline.intercept(ApplicationSendPipeline.Before) { message ->
                val contentType = (message as? OutgoingContent)?.contentType?.withoutParameters()
                if (contentType == ContentType.Text.Html) {
                    call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                }
            }
        }

        routing {
            staticResources("/", "static", index = "index.html")

            get("/suggestions") {
                val suggestions = DatabaseClient.fetchAllUniqueSuggestions()
                call.respond(suggestions)
            }

            // 🏀 Basketbol — futboldan tamamen ayrı, izole uç noktalar.
            get("/basketball/suggestions") {
                val suggestions = DatabaseClient.fetchAllBasketballSuggestions()
                call.respond(suggestions)
            }

            get("/basketball/commonPlayers") {
                val team1 = call.request.queryParameters["team1"]
                val team2 = call.request.queryParameters["team2"]
                if (team1.isNullOrBlank() || team2.isNullOrBlank()) {
                    call.respond(emptyList<DatabaseClient.BasketballPlayerResult>())
                    return@get
                }
                val common = DatabaseClient.fetchCommonBasketballPlayers(team1, team2)
                call.respond(common)
            }

            // 🎲 Bil Bakalım rastgele soru — TÜM deneme mantığı tek istekte,
            // sunucu içinde. İstemci sadece hangi ligi istediğini söylüyor.
            get("/basketball/randomQuestion") {
                val league = call.request.queryParameters["league"] ?: "europe"
                val isNba = league == "nba"
                val poolStart = System.currentTimeMillis()
                val pool = if (isNba) DatabaseClient.fetchAllNbaSuggestions() else DatabaseClient.fetchAllBasketballSuggestions()
                val poolElapsed = System.currentTimeMillis() - poolStart
                if (poolElapsed > 500) {
                    println("⏱️ havuz hesaplama YAVAŞ (${if (isNba) "NBA" else "Avrupa"}): ${poolElapsed}ms, ${pool.size} takım")
                }
                val result = DatabaseClient.fetchRandomBasketballQuestion(pool, isNba)
                call.respond(result)
            }

            // 🏀 NBA — Avrupa basketbolundan ayrı endpoint'ler.
            get("/nba/suggestions") {
                call.respond(DatabaseClient.fetchAllNbaSuggestions())
            }

            get("/nba/commonPlayers") {
                val team1 = call.request.queryParameters["team1"]
                val team2 = call.request.queryParameters["team2"]
                if (team1.isNullOrBlank() || team2.isNullOrBlank()) {
                    call.respond(emptyList<DatabaseClient.BasketballPlayerResult>())
                    return@get
                }
                val common = DatabaseClient.fetchCommonNbaPlayers(team1, team2)
                call.respond(common)
            }

            // 🖼️ Basketbol logo/foto — sunucu üzerinden TheSportsDB'ye gidiyor,
            // tarayıcıdaki CORS sorununu tamamen bypass ediyor.
            get("/basketball/teamLogo") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(mapOf("logo" to null))
                    return@get
                }
                val logo = DatabaseClient.fetchBasketballTeamLogo(name)
                call.respond(mapOf("logo" to logo))
            }

            // ⚽ Futbol kulüp logosu yedek sistemi — club_logos'ta olmayan kulüpler için
            get("/football/teamLogoFallback") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(mapOf("logo" to null))
                    return@get
                }
                val logo = DatabaseClient.fetchFootballTeamLogoFallback(name)
                call.respond(mapOf("logo" to logo))
            }

            get("/basketball/playerPhoto") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(mapOf("photo" to null))
                    return@get
                }
                val photo = DatabaseClient.fetchBasketballPlayerPhoto(name)
                call.respond(mapOf("photo" to photo))
            }

            // 🖼️ YENİ: Kulüp logoları — frontend sayfa yüklenirken bir kez çekip
            // hafızada tutuyor, her istek için tekrar tekrar sormuyor.
            get("/clubLogos") {
                val logos = DatabaseClient.fetchAllClubLogos()
                call.respond(logos)
            }

            // 📅 Sunucunun kendi tarihini döndürür — Günün Sorusu artık cihazın
            // kendi saatine değil, buna göre hesaplanıyor. Böylece farklı saat
            // dilimindeki/yanlış saatli cihazlar bile HERKESE AYNI günü görür.
            get("/serverDate") {
                // 💡 DÜZELTME: UTC yerine Türkiye saati (Europe/Istanbul) kullanıyoruz —
                // hedef kitlemiz Türkiye olduğu için "gün değişimi" onların gece
                // yarısına göre olmalı, UTC'ye göre değil (UTC 3 saat GERİDE kalıyordu).
                val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).toString()
                call.respond(mapOf("date" to today))
            }

            // 📰 BLOG — gerçek, sunucu tarafında oluşturulan HTML sayfaları (JS'e
            // bağımlı değil), arama motoru botlarının tarayıp indeksleyebilmesi için.
            val blogArticles = mapOf(
                "de-gea-fax-makinesi-transferi-batirdi" to Pair(
                    "Bir Fax Makinesi Yüzünden Batan Dev Transfer",
                    """
                    <p>2015 yaz transfer döneminin son gecesi, futbol tarihinin en garip anlarından birine sahne oldu. 
                    Real Madrid ve Manchester United, kaleci David de Gea'nın Madrid'e, Keylor Navas'ın ise 
                    karşılığında Manchester'a gitmesi için anlaşmaya varmıştı. Her şey hazırdı, imzalar atılacaktı — 
                    ama gecenin geç saatlerinde, İspanyol Ligi'nin transfer sistemi (TMS) üzerinden gönderilmesi 
                    gereken evraklar bir türlü karşı tarafa ulaşmadı.</p>
                    <p>United, evrakları saat kapanmadan önce gönderdiğini iddia ederken, Real Madrid evrakların 
                    eksik/yanlış formatta geldiğini öne sürdü. Bazı kaynaklara göre belgeler tam da gece yarısından 
                    2 dakika sonra sisteme düştü — yani transfer penceresi kapandıktan hemen sonra. İki kulüp de 
                    birbirini suçlayan resmi açıklamalar yaptı, olay günlerce basında "fax makinesi skandalı" 
                    olarak konuşuldu.</p>
                    <p>Sonuç: De Gea, hayalindeki Real Madrid transferini kaçırdı ve Manchester United'da kalmaya 
                    devam etti. İlginç bir şekilde bu "kayıp" transfer, kaleci için kötü sonuçlanmadı — United'da 
                    sekiz sezon daha forma giyip kulübün tarihindeki en çok maça çıkan oyunculardan biri oldu.</p>
                    <p>Bazen milyonlarca euroluk bir transferi, teknolojik bir aksaklık ya da birkaç dakikalık bir 
                    gecikme belirleyebiliyor. TransferKolik'te "Manchester United" ve "Real Madrid" aratarak, 
                    bu iki kulüpte gerçekten forma giymiş oyuncuların tam listesini görebilirsiniz.</p>
                    """.trimIndent()
                ),
                "alexis-sanchez-mourinho-telefon-hamlesi" to Pair(
                    "Mourinho'nun Telefon Açıp Çaldığı Transfer",
                    """
                    <p>Ocak 2018'de Alexis Sanchez, Arsenal'den ayrılmaya kararlıydı ve gözü Manchester City'deydi 
                    — eski hocası Pep Guardiola ile yeniden bir araya gelmek istiyordu. Şili'li yıldız, City ile 
                    neredeyse her gün mesajlaşıyor, kişisel şartlarda anlaşmışlardı. Herkes, bu transferin sadece 
                    bir formalite meselesi olduğunu düşünüyordu.</p>
                    <p>Ama tam o sırada telefonu çaldı. Arayan, Manchester United teknik direktörü Jose Mourinho'ydu. 
                    Sanchez'in kendi ifadesiyle: "Mourinho bana dedi ki: Alexis, işte sana 7 numaralı forma hazır." 
                    Bu basit ama etkili teklif, City ile neredeyse tamamlanmış olan anlaşmayı bir anda gölgede 
                    bıraktı.</p>
                    <p>City, Sanchez'in menajerinin son anda daha yüksek ücret talep etmesi üzerine görüşmelerden 
                    çekildi — bu da United'a alanı tamamen açtı. United, Henrikh Mkhitaryan'ı da işin içine katarak 
                    takas şeklinde bir anlaşma yaptı ve City'nin gözdesini son anda kapmayı başardı.</p>
                    <p>Ama bu "zafer" United için kısa sürede hayal kırıklığına dönüştü — Sanchez, kulüpte geçirdiği 
                    19 ay boyunca sadece 5 gol atabildi ve kariyerinin en kötü dönemini yaşadı. Bazen bir transferi 
                    "kazanmak", o transferi başarılı kılmıyor. TransferKolik'te "Arsenal" ve "Manchester United" 
                    aratarak, bu iki kulüpte de forma giymiş diğer oyuncuları keşfedebilirsiniz.</p>
                    """.trimIndent()
                ),
                "robinho-chelsea-tisortleri-iptal-transfer" to Pair(
                    "Satışa Çıkan Formalar Yüzünden İptal Olan Transfer",
                    """
                    <p>2008 yazında Robinho, Real Madrid'den ayrılmak istiyordu ve Chelsea ile anlaşmaya çok 
                    yakındı — hatta Londra'da yaşamak istediğini bile açıklamıştı. Chelsea o kadar emindi ki, 
                    kulübün resmi mağaza sitesinde Robinho isimli formalar **anlaşma daha resmileşmeden** satışa 
                    sunuldu.</p>
                    <p>Bu detay, Real Madrid Başkanı Ramón Calderón'u çileden çıkardı. Kulübe göre bu, saygısızca 
                    bir davranıştı — sanki transfer çoktan bitmiş gibi hareket ediliyordu. Öfkelenen Calderón, 
                    görüşmeleri tamamen kesti ve Chelsea ile anlaşmayı iptal etti.</p>
                    <p>Tam o sırada, transfer döneminin son günü, Manchester City'nin Abu Dabi Birleşik Grubu 
                    tarafından satın alındığı açıklandı — kulüp bir gecede dünyanın en zengin takımı haline geldi. 
                    Yeni sahipler, hızlıca büyük bir imza atmak istiyordu ve gözlerini Robinho'ya çevirdiler. 
                    Saatler içinde anlaşma tamamlandı — sağlık kontrolü bile yapılmadan.</p>
                    <p>İşin komik tarafı, Robinho tanıtım basın toplantısında yanlışlıkla "Chelsea'nin teklifini 
                    kabul ettim" dedi — bir gazeteci ona gittiği kulübün aslında Manchester City olduğunu 
                    hatırlatmak zorunda kaldı! TransferKolik'te "Real Madrid" ve "Manchester City" aratarak, 
                    bu iki kulüpte de forma giymiş oyuncuların tam listesine ulaşabilirsiniz.</p>
                    """.trimIndent()
                ),
                "robben-united-tesislerini-gezdi-chelseaya-gitti" to Pair(
                    "United'ın Tesislerini Gezip Chelsea'ye İmza Atan Oyuncu",
                    """
                    <p>2004 yazında Arjen Robben, PSV Eindhoven'daki parlak performansının ardından Avrupa'nın 
                    en çok istenen kanat oyuncularından biri haline gelmişti. Manchester United, transferi neredeyse 
                    bitirmişti — Robben, kulübün antrenman tesislerini ziyaret etmiş, kişisel şartlarda anlaşmaya 
                    varmıştı. Herkese göre bu, sadece imza atma meselesiydi.</p>
                    <p>Ama Chelsea, sahneye son anda girdi. Roman Abramovich'in parasal gücüyle desteklenen Londra 
                    kulübü, daha cazip bir teklif sundu — hem Robben'a hem PSV'ye. Hollandalı yıldız, United'a 
                    verdiği sözlü sözden vazgeçip yönünü Stamford Bridge'e çevirdi.</p>
                    <p>Manchester United için bu, gerçek bir hayal kırıklığıydı — kulüp, transferin neredeyse 
                    kesinleştiğini düşünüyordu. Ama Robben'ın kararı doğru çıktı: Chelsea'de geçirdiği sezonlarda 
                    lig şampiyonluğu yaşadı, ardından Real Madrid ve Bayern Münih'te oynayarak kariyerinin zirvesine 
                    ulaştı.</p>
                    <p>Bu hikaye, bir oyuncunun sözlü anlaşmasının bile son ana kadar garanti olmadığını gösteriyor 
                    — özellikle parasal gücü yüksek bir rakip devreye girdiğinde. TransferKolik'te "PSV" ve 
                    "Chelsea" aratarak, bu iki kulüpte de forma giymiş oyuncuları bulabilirsiniz.</p>
                    """.trimIndent()
                ),
                "ray-allen-kevin-garnett-telefon-numarasi" to Pair(
                    "Silinen Bir Telefon Numarası: Ray Allen'ın Rakip Takıma Geçişi",
                    """
                    <p>2012 yazında Ray Allen, NBA tarihinin en dramatik takım değiştirme hikayelerinden birine 
                    imza attı. Boston Celtics'te Kevin Garnett ve Paul Pierce ile birlikte "Big Three" olarak 
                    2008 şampiyonluğunu yaşamış olan Allen, serbest kalınca herkesi şok eden bir karar aldı: 
                    Boston'un en büyük rakibi Miami Heat'e transfer oldu.</p>
                    <p>Bu karar, eski takım arkadaşlarını derinden yaraladı. Kevin Garnett, basın toplantısında 
                    gazetecilere açıkça şunu söyledi: "Artık Ray'in telefon numarası bende yok. İletişim kurmaya 
                    çalışmıyorum." Paul Pierce ise "Kariyerimizi birlikte bitireceğimizi düşünüyordum" diyerek 
                    hayal kırıklığını dile getirdi.</p>
                    <p>Gerginlik sadece sözlerle kalmadı — Allen'ın Miami formasıyla Boston'a ilk dönüşünde, 
                    Garnett onunla el sıkışmayı ve hatta ona bakmayı bile reddetti. Bu soğukluk yıllarca sürdü; 
                    Allen, aradan geçen zamanda Garnett ile hiç konuşmadığını defalarca doğruladı.</p>
                    <p>Sportif açıdan ise Allen'ın kararı isabetliydi — Miami'de bir şampiyonluk daha kazandı. 
                    Ama bu transfer, basketbolda "takım sadakati" kavramının ne kadar hassas bir konu olduğunu 
                    gösteren, yıllarca konuşulan bir örnek olarak kaldı.</p>
                    """.trimIndent()
                ),
                "kevin-durant-yenildigi-takima-katildi" to Pair(
                    "Kaybettiği Seride Rakibine Katılan Yıldız: Kevin Durant'ın Tartışmalı Kararı",
                    """
                    <p>2016 yazı, NBA tarihinin en çok tartışılan transfer kararlarından birine sahne oldu. 
                    Kevin Durant'ın takımı Oklahoma City Thunder, o sezonki konferans finalinde Golden State 
                    Warriors'a karşı 3-1 öne geçmişti — şampiyonluğa adeta bir adım kalmıştı. Ama Warriors, 
                    inanılmaz bir geri dönüşle seriyi çevirdi ve finale kendisi gitti.</p>
                    <p>Herkesin beklediği, Durant'ın bu yenilginin acısını çıkarmak için takımını güçlendirmesiydi. 
                    Ama yaz transfer döneminde Durant, tam da kendisini eleyen o Warriors kadrosuna katılma 
                    kararı aldı. Bu haber, sosyal medyada adeta bir kaosa yol açtı — bazı Thunder taraftarları, 
                    tepkilerini göstermek için Durant formalarını yakacak kadar ileri gitti.</p>
                    <p>Eleştirmenler, Durant'ı "kendi gücüyle şampiyonluk kazanmak yerine, kendisini yenen takıma 
                    sığınmakla" suçladı. Ama Durant, kararının arkasında durdu ve Golden State'te geçirdiği üç 
                    sezonda üst üste iki şampiyonluk kazanıp iki kez Final MVP'si seçildi.</p>
                    <p>Bu hikaye, bugün hâlâ "bir yıldızın kariyer kararı ile sportmenlik anlayışı çatışabilir mi" 
                    tartışmasının en somut örneği olarak basketbol dünyasında konuşulmaya devam ediyor.</p>
                    """.trimIndent()
                ),
                "hamilton-ferrari-1-nisan-mi-bu" to Pair(
                    "'1 Nisan mı Bu?': Hamilton'ın Ferrari Şoku",
                    """
                    <p>Şubat 2024'te Formula 1 dünyası, sporun tarihindeki en büyük sürpriz transferlerinden 
                    birine sahne oldu. Lewis Hamilton, Mercedes ile sadece altı ay önce iki yıllık yeni bir 
                    sözleşme imzalamıştı — kariyerinin tamamının Mercedes'te geçeceği düşünülüyordu, kulüple 
                    tam 11 yıllık bir ortaklığı vardı ve yedi kez dünya şampiyonu olmuştu.</p>
                    <p>Ama sözleşmesindeki gizli bir madde, bir yıl sonra ayrılma hakkı tanıyordu — ve Hamilton, 
                    bunu kullandı. Haberi, takım patronu Toto Wolff'un bizzat açıkladığı bir toplantıda öğrenen 
                    Mercedes çalışanları arasında, Hamilton'ın uzun yıllık yarış mühendisi "Bono" bile "Bu 1 Nisan 
                    şakası mı?" diye sormuştu — haber o kadar inanılmazdı.</p>
                    <p>Ferrari, aslında o dönem Carlos Sainz ile sözleşme yenileme görüşmelerindeydi. Ama Ferrari 
                    Başkanı John Elkann, Hamilton'ın müsait olabileceğini öğrenince görüşmeleri hemen değiştirdi 
                    ve İngiliz pilotu kapmak için harekete geçti. Sainz'e veda edilirken, 39 yaşındaki Hamilton, 
                    2025 sezonundan itibaren kırmızılara geçiş yaptı.</p>
                    <p>Bu transfer, "kariyerinin sonuna kadar aynı takımda kalacağı" düşünülen bir efsanenin bile, 
                    doğru fırsat karşısında köklü bir değişiklik yapabileceğinin en çarpıcı kanıtı oldu.</p>
                    """.trimIndent()
                ),
                "verstappen-bir-haftada-sampiyon-koltuguna" to Pair(
                    "Bir Haftada Yedek Pilottan Yarış Galibiyetine: Verstappen'in Şok Terfisi",
                    """
                    <p>Mayıs 2016'da Formula 1'de, sporun tarihinde eşi benzeri az görülen bir karar alındı. 
                    Red Bull, o ana kadar küçük kardeş takımı Toro Rosso'da yarışan 18 yaşındaki Max Verstappen'i, 
                    ana takıma terfi ettirmeye karar verdi — mevcut pilotu Daniil Kvyat'ın yerine, üstelik 
                    sezonun ortasında.</p>
                    <p>Bu karar, çoğu kişiye acımasız göründü — Kvyat, sadece birkaç hafta önce Çin'de podyuma 
                    çıkmıştı. Ama Rusya'daki iki çarpışmanın ardından Red Bull yönetimi kararını verdi. Değişiklik, 
                    İspanya Grand Prix'sinden dört gün önce açıklandı — Verstappen'in yeni takımıyla ilk yarışına 
                    sadece birkaç günü kalmıştı.</p>
                    <p>Sonuç, kimsenin tahmin edemeyeceği kadar muhteşemdi: Verstappen, Red Bull'daki ilk yarışında 
                    — kariyerinin sadece 24. yarışında — birinci sırada bitirdi ve F1 tarihinin en genç yarış 
                    galibi oldu. Takım patronu Christian Horner bile şaka yollu "İlk yarışını kazandın, bundan 
                    sonra sadece kötüye gidebilir!" demişti.</p>
                    <p>Bu zafer, Red Bull'un riskli kararını anında haklı çıkardı ve Verstappen'in, yıllar sonra 
                    dünya şampiyonluğuna uzanacak kariyerinin ilk büyük dönüm noktası oldu.</p>
                    """.trimIndent()
                ),
                "schumacher-zayif-ferrariyi-secti" to Pair(
                    "Şampiyon Takımı Bırakıp Zayıf Ferrari'ye İmza Atan Pilot",
                    """
                    <p>1996 yılında Michael Schumacher, F1 tarihinin en akılda kalıcı kararlarından birini aldı. 
                    Benetton takımıyla üst üste iki dünya şampiyonluğu kazanmış olan Alman pilot, güçlü ve 
                    şampiyonluğa aday bir takımdan ayrılıp, o dönem yıllardır şampiyonluk kazanamayan, sportif 
                    açıdan zayıf bir Ferrari'ye transfer oldu.</p>
                    <p>Karar, birçok kişiyi şaşırttı — neden şampiyon bir pilot, kazanan bir takımı bırakıp 
                    mücadele eden bir takıma gitsin ki? Ama Schumacher'in vizyonu farklıydı: Ferrari'yi yeniden 
                    inşa etmek istiyordu. İlk sezonlarında sonuçlar beklenildiği gibi zorlu geçti, takım hâlâ 
                    rakiplerinin gerisindeydi.</p>
                    <p>Ama Schumacher sabırla çalışmaya devam etti, teknik ekibiyle (özellikle Ross Brawn ve 
                    Rory Byrne ile) güçlü bir ortaklık kurdu. Yıllar süren bu inşa süreci sonunda meyvesini verdi: 
                    2000-2004 yılları arasında Ferrari ile üst üste beş dünya şampiyonluğu kazanarak, takımı 
                    F1 tarihinin en başarılı dönemlerinden birine taşıdı.</p>
                    <p>Bu hikaye, bazen "geriye gitmiş" görünen bir transferin, aslında uzun vadeli bir vizyonun 
                    parçası olabileceğini gösteren, F1 tarihinin en öğretici örneklerinden biri.</p>
                    """.trimIndent()
                ),
                "vettel-alonsonun-ayrildigi-koltuga-oturdu" to Pair(
                    "Alonso'nun Terk Ettiği Koltuğa Oturan Şampiyon: Vettel'in Ferrari Dönemi",
                    """
                    <p>2014 sezonu sonunda Fernando Alonso, yıllarca şampiyonluk için mücadele ettiği ama bir 
                    türlü kazanamadığı Ferrari'den ayrılma kararı aldı — hayal kırıklığı doluydu, takımın 
                    rekabetçi olamamasından bıkmıştı. Bu ayrılık, F1 dünyasında büyük yankı uyandırdı; İspanyol 
                    pilot, kariyerinin en parlak yıllarını kırmızılarla mücadele ederek geçirmişti.</p>
                    <p>Tam bu sırada, dört kez üst üste dünya şampiyonu olmuş Sebastian Vettel, Red Bull'daki 
                    başarılı döneminin ardından sürpriz bir şekilde Ferrari'ye transfer oldu — adeta Alonso'nun 
                    bıraktığı koltuğa oturdu. Vettel için bu, çocukluk hayaliydi; Ferrari'de yarışmak, kendisinin 
                    de belirttiği gibi "her pilotun içinde yatan bir arzu"ydu.</p>
                    <p>İlk sezonunda Vettel, Ferrari'ye 2008'den beri ilk kez bir Grand Prix galibiyeti yaşattı 
                    ve şampiyonada üçüncü sırada bitirdi — takımın yeniden rekabetçi olabileceğinin sinyalini 
                    verdi. Ne yazık ki beklenen dünya şampiyonluğu bir türlü gelmedi, altı sezon boyunca birkaç 
                    kez şampiyonluğa çok yaklaşsa da kazanamadı.</p>
                    <p>Bu hikaye, bir pilotun çocukluk hayalini gerçekleştirmesinin bile, garantili bir başarı 
                    anlamına gelmediğinin çarpıcı bir örneği olarak F1 tarihinde yerini aldı.</p>
                    """.trimIndent()
                ),
                "lebron-karari-canli-yayinda-acikladi" to Pair(
                    "'Yeteneklerimi Miami'ye Taşıyorum': Canlı Yayında Açıklanan Karar",
                    """
                    <p>2010 yazında LeBron James, NBA tarihinin en tartışmalı ve en özgün transfer duyurusuna 
                    imza attı. Cleveland Cavaliers'te geçirdiği yedi sezonun ardından serbest kalan James, 
                    kararını açıklamak için sıradan bir basın toplantısı yerine, ESPN'de canlı yayınlanan özel 
                    bir televizyon programı düzenledi — adı basitçe "The Decision" (Karar) olacaktı.</p>
                    <p>Milyonlarca izleyicinin ekran başında beklediği o an geldiğinde James, tarihe geçecek 
                    cümleyi kurdu: "Bu sonbahar yeteneklerimi Miami'ye taşıyacağım." Bu sözler, Cleveland 
                    taraftarlarını derinden yaraladı — kulüp sahibi Dan Gilbert, James'e açık bir mektupla sert 
                    bir tepki verdi ve onu "hain" olarak nitelendirdi.</p>
                    <p>Miami'de Dwyane Wade ve Chris Bosh ile bir araya gelen James, "Big Three" olarak anılan 
                    bu kadroyla dört yıl üst üste finale çıktı ve iki şampiyonluk kazandı. Ama "Decision" programı, 
                    sportif başarısından bağımsız olarak, bir oyuncunun takım değiştirme kararını nasıl "aşırı 
                    gösterişli" bir şekilde sunabileceğinin sembolü haline geldi.</p>
                    <p>James, yıllar sonra bu duyuru şeklinin bir hata olduğunu kabul etti — ama bu olay, 
                    basketbol tarihinde "star oyuncu transferi" kavramını sonsuza dek değiştirdi.</p>
                    """.trimIndent()
                ),
                "carmelo-anthony-knicksi-zorla-kazandi" to Pair(
                    "Gitmek İstediği Takımı Zorla Elde Eden Yıldız: Carmelo Anthony",
                    """
                    <p>2011 yılı başında Carmelo Anthony, Denver Nuggets'teki geleceğinin belirsiz olduğunu 
                    açıkça belirtti — sözleşmesi bitmek üzereydi ve New York Knicks'e gitmek istediğini kamuoyuna 
                    duyurdu. Bu, basketbolda nadir görülen bir stratejiydi: bir oyuncu, sadece "istediği" için 
                    bir kulübü, kendisini istemediği bir takasa zorluyordu.</p>
                    <p>Nuggets yönetimi başta direndi, farklı takımlarla görüşmeler yürüttü. Ama Anthony'nin 
                    kararlılığı ve New York'a gitme konusundaki ısrarı, sonunda kulübü zorladı. Şubat 2011'de 
                    dev bir çoklu takas gerçekleşti — Knicks, Anthony'yi elde etmek için kadrosunun büyük bir 
                    kısmını (Danilo Gallinari, Wilson Chandler ve daha fazlasını) Denver'a gönderdi.</p>
                    <p>Bu transfer, NBA'de "oyuncu gücü" kavramının ne kadar etkili olabileceğinin erken bir 
                    örneğiydi — yıldız oyuncular, artık sadece kulüplerin kararlarına bağlı kalmıyor, kendi 
                    gelecekleri üzerinde doğrudan söz sahibi olabiliyordu.</p>
                    <p>Anthony, Knicks'te geçirdiği yıllar boyunca takımın yıldızı oldu, ama beklenen şampiyonluk 
                    bir türlü gelmedi. Yine de bu hikaye, modern NBA'de oyuncu-kulüp ilişkisinin nasıl değiştiğinin 
                    önemli bir dönüm noktası olarak basketbol tarihine geçti.</p>
                    """.trimIndent()
                ),
            )

            // 🏷️ Hangi makale hangi spora ait — mevcut makale tanımlarına dokunmadan,
            // ayrı bir eşleme ile /blog listesinde futbol/basketbol ayrımı yapıyoruz.
            val articleSport = mapOf(
                "de-gea-fax-makinesi-transferi-batirdi" to "futbol",
                "alexis-sanchez-mourinho-telefon-hamlesi" to "futbol",
                "robinho-chelsea-tisortleri-iptal-transfer" to "futbol",
                "robben-united-tesislerini-gezdi-chelseaya-gitti" to "futbol",
                "ray-allen-kevin-garnett-telefon-numarasi" to "basketbol",
                "kevin-durant-yenildigi-takima-katildi" to "basketbol",
                "lebron-karari-canli-yayinda-acikladi" to "basketbol",
                "carmelo-anthony-knicksi-zorla-kazandi" to "basketbol",
                "hamilton-ferrari-1-nisan-mi-bu" to "f1",
                "verstappen-bir-haftada-sampiyon-koltuguna" to "f1",
                "schumacher-zayif-ferrariyi-secti" to "f1",
                "vettel-alonsonun-ayrildigi-koltuga-oturdu" to "f1"
            )

            // 🎨 Ana sayfadaki modern çizim ikonlarıyla AYNI SVG'ler — blog
            // sayfasındaki eski emoji ikonların yerine geçiyor.
            fun svgFootball(): String = """<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" style="vertical-align:-2px; margin-right:5px;"><circle cx="12" cy="12" r="10"/><path d="M12 7l3.5 2.5-1.3 4.1H9.8L8.5 9.5z" fill="currentColor" stroke="none"/><path d="M12 2v5M12 17v5M2.5 9.5l4.5 1.5M17 11l4.5-1.5M6 19.5l1.8-4M16.2 15.5L18 19.5"/></svg>"""
            fun svgBasketball(): String = """<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" style="vertical-align:-2px; margin-right:5px;"><circle cx="12" cy="12" r="10"/><path d="M12 2v20M2 12h20M4.2 5.5c2.5 2.5 3.8 5.5 3.8 6.5s-1.3 4-3.8 6.5M19.8 5.5c-2.5 2.5-3.8 5.5-3.8 6.5s1.3 4 3.8 6.5"/></svg>"""
            fun svgF1(): String = """<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" style="vertical-align:-2px; margin-right:5px;"><rect x="3" y="4" width="7" height="7"/><rect x="14" y="4" width="7" height="7"/><rect x="3" y="13" width="7" height="7"/><rect x="14" y="13" width="7" height="7"/></svg>"""

            fun blogPageHtml(title: String, bodyHtml: String, description: String): String = """
                <!DOCTYPE html>
                <html lang="tr">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>$title | TransferKolik Blog</title>
                    <meta name="description" content="$description">
                    <link rel="preconnect" href="https://fonts.googleapis.com">
                    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Oswald:wght@500;600;700&display=swap" rel="stylesheet">
                    <style>
                        * { box-sizing: border-box; }
                        body {
                            font-family: 'Inter', -apple-system, sans-serif;
                            max-width: 720px;
                            margin: 0 auto;
                            padding: 32px 20px 70px;
                            background: linear-gradient(180deg, #fbfbfd 0%, #f5f5f7 100%);
                            color: #1d1d1f;
                            line-height: 1.75;
                            min-height: 100vh;
                        }
                        h1 {
                            font-family: 'Oswald', sans-serif;
                            font-size: 1.7rem;
                            font-weight: 600;
                            letter-spacing: 0.3px;
                            color: #1d1d1f;
                            margin-bottom: 10px;
                        }
                        h2 {
                            font-family: 'Oswald', sans-serif;
                            font-size: 1.15rem;
                            font-weight: 600;
                            letter-spacing: 0.3px;
                            color: #1d1d1f;
                            margin-top: 36px;
                            margin-bottom: 6px;
                        }
                        a { color: #0071e3; text-decoration: none; }
                        p { margin-bottom: 16px; font-size: 0.95rem; color: rgba(29,29,31,0.82); }
                        .back-links { display: flex; gap: 10px; margin-bottom: 28px; flex-wrap: wrap; }
                        .back-link {
                            display: inline-flex;
                            align-items: center;
                            gap: 6px;
                            padding: 8px 14px;
                            border-radius: 20px;
                            background: rgba(0,0,0,0.04);
                            border: 1px solid rgba(0,0,0,0.08);
                            font-size: 0.8rem;
                            font-weight: 600;
                            color: rgba(29,29,31,0.75);
                            transition: background 0.2s ease, transform 0.2s ease;
                        }
                        .back-link:hover { background: rgba(0,0,0,0.07); transform: translateY(-1px); }
                        .article-list { display: flex; flex-direction: column; gap: 10px; }
                        .article-list a {
                            display: block;
                            padding: 18px 20px;
                            border-radius: 14px;
                            background: rgba(0,0,0,0.03);
                            border: 1px solid rgba(0,0,0,0.06);
                            box-shadow: 0 4px 14px rgba(0,0,0,0.04);
                            text-decoration: none;
                            font-weight: 600;
                            font-size: 0.95rem;
                            letter-spacing: 0.1px;
                            color: #1d1d1f;
                            transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
                        }
                        .article-list a:hover {
                            transform: translateY(-2px);
                            box-shadow: 0 10px 26px rgba(0,0,0,0.08);
                            border-color: rgba(0,113,227,0.25);
                        }
                        .subtitle { color: rgba(29,29,31,0.55); font-size: 0.9rem; margin-top: -4px; margin-bottom: 28px; }
                        .sport-filter-bar { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
                        .sport-filter-btn {
                            padding: 8px 16px;
                            border-radius: 20px;
                            border: 1px solid rgba(0,0,0,0.1);
                            background: rgba(0,0,0,0.03);
                            color: rgba(29,29,31,0.7);
                            font-size: 0.82rem;
                            font-weight: 600;
                            font-family: 'Inter', sans-serif;
                            cursor: pointer;
                            transition: background 0.2s ease, color 0.2s ease, transform 0.15s ease;
                        }
                        .sport-filter-btn:hover { transform: translateY(-1px); background: rgba(0,0,0,0.06); }
                        .sport-filter-btn.active { background: #0071e3; color: #fff; border-color: #0071e3; }
                    </style>
                </head>
                <body>
                    <div class="back-links">
                        <a href="/" class="back-link">← Ana Sayfa</a>
                        <a href="/blog" class="back-link">📰 Tüm Yazılar</a>
                    </div>
                    $bodyHtml
                </body>
                </html>
            """.trimIndent()

            get("/blog") {
                val footballHtml = blogArticles.entries
                    .filter { articleSport[it.key] == "futbol" }
                    .joinToString("") { (slug, pair) -> "<a href=\"/blog/$slug\">${pair.first}</a>" }
                val basketballHtml = blogArticles.entries
                    .filter { articleSport[it.key] == "basketbol" }
                    .joinToString("") { (slug, pair) -> "<a href=\"/blog/$slug\">${pair.first}</a>" }
                val f1Html = blogArticles.entries
                    .filter { articleSport[it.key] == "f1" }
                    .joinToString("") { (slug, pair) -> "<a href=\"/blog/$slug\">${pair.first}</a>" }
                val html = blogPageHtml(
                    "Transfer Hikayeleri",
                    """
                    <h1>Bonservissiz Yazarın Kaleminden — Haftanın Blogları</h1>
                    <p class="subtitle">Futbol, basketbol ve Formula 1 tarihinin en dramatik, en şaşırtıcı transfer anları.</p>
                    <div class="sport-filter-bar">
                        <button class="sport-filter-btn active" data-filter="all" onclick="filterBlogBySport('all', this)">Tümü</button>
                        <button class="sport-filter-btn" data-filter="futbol" onclick="filterBlogBySport('futbol', this)">${svgFootball()} Futbol</button>
                        <button class="sport-filter-btn" data-filter="basketbol" onclick="filterBlogBySport('basketbol', this)">${svgBasketball()} Basketbol</button>
                        <button class="sport-filter-btn" data-filter="f1" onclick="filterBlogBySport('f1', this)">${svgF1()} Formula 1</button>
                    </div>
                    <div class="sport-section" data-sport="futbol">
                        <h2>${svgFootball()} Futbol</h2>
                        <div class="article-list">$footballHtml</div>
                    </div>
                    <div class="sport-section" data-sport="basketbol">
                        <h2>${svgBasketball()} Basketbol</h2>
                        <div class="article-list">$basketballHtml</div>
                    </div>
                    <div class="sport-section" data-sport="f1">
                        <h2>${svgF1()} Formula 1</h2>
                        <div class="article-list">$f1Html</div>
                    </div>
                    <script>
                        function filterBlogBySport(sport, btn) {
                            document.querySelectorAll('.sport-filter-btn').forEach(b => b.classList.remove('active'));
                            btn.classList.add('active');
                            document.querySelectorAll('.sport-section').forEach(sec => {
                                sec.style.display = (sport === 'all' || sec.dataset.sport === sport) ? '' : 'none';
                            });
                        }
                    </script>
                    """.trimIndent(),
                    "Futbol, basketbol ve Formula 1 dünyasının en dramatik ve şaşırtıcı transfer hikayeleri — TransferKolik Blog."
                )
                call.respondText(html, ContentType.Text.Html)
            }

            get("/blog/{slug}") {
                val slug = call.parameters["slug"] ?: ""
                val article = blogArticles[slug]
                if (article == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val (title, content) = article
                val html = blogPageHtml(
                    title,
                    "<h1>$title</h1>$content",
                    content.take(150).replace(Regex("<[^>]*>"), "")
                )
                call.respondText(html, ContentType.Text.Html)
            }


            // 📰 Günün Oyuncusu — tarihe göre sabit (herkese aynı gün aynı), gerçek veri
            get("/dailyPlayerBio") {
                val dateSeed = call.request.queryParameters["seed"]?.toIntOrNull() ?: 0
                val bio = DatabaseClient.fetchDailyPlayerBio(dateSeed)
                if (bio == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(bio)
                }
            }

            get("/basketball/allLogos") {
                val logos = DatabaseClient.fetchAllBasketballLogos()
                call.respond(logos)
            }

            // 🏆 Bil Bakalım rekoru — mod bazlı (Genel/Türkiye/NBA/Avrupa Basketbolu)
            get("/quiz/highscore") {
                val mode = call.request.queryParameters["mode"] ?: "genel"
                call.respond(mapOf("highScore" to DatabaseClient.fetchQuizHighScore(mode)))
            }

            // 🗳️ Şampiyonluk Anketi
            get("/poll/results") {
                call.respond(DatabaseClient.fetchPollResults())
            }

            post("/poll/vote") {
                val league = call.request.queryParameters["league"]
                val team = call.request.queryParameters["team"]
                if (league.isNullOrBlank() || team.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                call.respond(DatabaseClient.submitPollVote(league, team))
            }

            post("/quiz/highscore") {
                val body = call.receive<QuizScoreSubmission>()
                val newRecord = DatabaseClient.submitQuizScore(body.mode, body.score)
                call.respond(mapOf("highScore" to newRecord))
            }

            get("/players") {
                val clubParam = call.request.queryParameters["club"]
                if (clubParam.isNullOrBlank()) {
                    call.respond(emptyList<Player>())
                    return@get
                }
                val players = DatabaseClient.fetchPlayersByClub(clubParam)
                call.respond(players)
            }

            get("/commonPlayers") {
                val club1 = call.request.queryParameters["club1"]
                val club2 = call.request.queryParameters["club2"]

                if (club1.isNullOrBlank() || club2.isNullOrBlank()) {
                    call.respond(emptyList<Player>())
                    return@get
                }
                val common = DatabaseClient.fetchCommonPlayers(club1, club2)
                call.respond(common)
            }

            // 💡 YENİ: "Oyuncu Modu" — mevcut 3 endpoint'e hiç dokunulmadı, bu tamamen
            // ayrı/ek bir uç nokta. Verilen kulüplerin (virgülle ayrılmış) HEPSİNDE
            // gerçekten oynamış rastgele bir oyuncuyu döndürür, yoksa 404 döner.
            // 🎯 Günün Sorusu (Wordle modu) — tahmin edilen oyuncunun bilgilerini
            // döndürüyor, hedefle karşılaştırıp renkli ipucu üretmek için.
            // 🔗 Transfer Bağlantısı — kullanıcının yazdığı köprü oyuncunun
            // gerçekten o kulüpte oynayıp oynamadığını doğrular.
            get("/verifyBridge") {
                val name = call.request.queryParameters["name"]
                val club = call.request.queryParameters["club"]
                if (name.isNullOrBlank() || club.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val startYear = call.request.queryParameters["startYear"]?.toIntOrNull()
                val endYear = call.request.queryParameters["endYear"]?.toIntOrNull()
                val nationality = call.request.queryParameters["nationality"]
                val valid = DatabaseClient.verifyPlayerPlayedForClub(name, club, startYear, endYear, nationality)
                call.respond(mapOf("valid" to valid))
            }

            // 🔍 Günün Sorusu / Transfer Bağlantısı tahmin kutuları için isim önerisi
            // 🃏 Transfermatik — oyuncunun toplam transfer sayısı
            // 🃏 Transfermatik YENİ SİSTEM — 3 rastgele seçilebilir oyuncu
            get("/randomTransferCandidates") {
                val excludeParam = call.request.queryParameters["exclude"]
                val excludeNames = excludeParam?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                val results = DatabaseClient.fetchRandomTransferCandidates(excludeNames, 3)
                call.respond(results)
            }

            get("/playerTransferCount") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val result = DatabaseClient.fetchPlayerTransferCount(name)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(result)
                }
            }

            get("/playerNameSuggestions") {
                val q = call.request.queryParameters["q"]
                if (q.isNullOrBlank()) {
                    call.respond(emptyList<String>())
                    return@get
                }
                val clubsParam = call.request.queryParameters["clubs"]
                val contextClubs = clubsParam?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                call.respond(DatabaseClient.fetchPlayerNameSuggestions(q, contextClubs))
            }

            // 🏀 Basketbol için oyuncu isim önerisi — futboldakiyle aynı desen,
            // ayrıca hangi ligi (europe/nba) arayacağını belirten "league" parametresi var.
            get("/basketball/playerNameSuggestions") {
                val q = call.request.queryParameters["q"]
                if (q.isNullOrBlank()) {
                    call.respond(emptyList<String>())
                    return@get
                }
                val league = call.request.queryParameters["league"] ?: "europe"
                val teamsParam = call.request.queryParameters["teams"]
                val contextTeams = teamsParam?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                call.respond(DatabaseClient.fetchBasketballPlayerNameSuggestions(q, league, contextTeams))
            }

            get("/playerInfo") {
                val name = call.request.queryParameters["name"]
                if (name.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                // 🎯 Bağlam kulüpleri (Günün Sorusu'ndaki 2 hedef kulüp gibi) — aynı
                // soyadlı birden fazla oyuncu varsa, doğru olanı seçmeye yardımcı olur.
                val clubsParam = call.request.queryParameters["clubs"]
                val contextClubs = clubsParam?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                val info = DatabaseClient.fetchPlayerBasicInfoByName(name, contextClubs)
                if (info == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(info)
                }
            }

            get("/playerMode") {
                val clubsParam = call.request.queryParameters["clubs"]
                if (clubsParam.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                val clubs = clubsParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (clubs.size < 2) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                // 💡 YENİ: ?isCountry=0,1 gibi paralel bir bayrak listesi — hangi terimin
                // ülke, hangisinin kulüp olduğunu belirtir. Verilmezse hepsi kulüp sayılır
                // (geriye dönük uyumluluk).
                val isCountryParam = call.request.queryParameters["isCountry"]
                val isCountryFlags = isCountryParam?.split(",")?.map { it.trim() == "1" }

                val terms = clubs.mapIndexed { idx, term ->
                    term to (isCountryFlags?.getOrNull(idx) ?: false)
                }

                val minYear = call.request.queryParameters["minYear"]?.toIntOrNull()
                val seed = call.request.queryParameters["seed"]?.toLongOrNull()
                val result = DatabaseClient.fetchPlayerAcrossClubs(terms, minYear, seed)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(result)
                }
            }

            // 🏆 YENİ: Arkadaşla Yarış (Duel) modu — tamamen ayrı, mevcut hiçbir
            // endpoint'e dokunmuyor. Polling tabanlı basit gerçek-zamanlı yarışma.
            post("/duel/create") {
                val body = call.receive<CreateRoomRequest>()
                val room = DuelManager.createRoom(body.playerName, body.winTarget, body.maskingHintEnabled, body.duelMode)
                call.respond(DuelManager.toState(room))
            }

            post("/duel/join") {
                val body = call.receive<JoinRoomRequest>()
                when (val result = DuelManager.joinRoom(body.roomCode, body.playerName)) {
                    is JoinResult.Success -> call.respond(DuelManager.toState(result.room))
                    is JoinResult.RoomFull -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Bu oda dolu"))
                    is JoinResult.RoomNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                }
            }

            get("/duel/state") {
                val code = call.request.queryParameters["roomCode"] ?: ""
                val playerName = call.request.queryParameters["playerName"]
                val room = DuelManager.getRoom(code, playerName)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(DuelManager.toState(room))
                }
            }

            post("/duel/answer") {
                val body = call.receive<DuelAnswerRequest>()
                val result = DuelManager.submitAnswer(body.roomCode, body.playerName, body.guess)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(result)
                }
            }

            post("/duel/pass") {
                val body = call.receive<DuelPassRequest>()
                val state = DuelManager.submitPass(body.roomCode, body.playerName)
                if (state == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(state)
                }
            }

            post("/duel/next") {
                val body = call.receive<NextRoundRequest>()
                val room = DuelManager.nextRound(body.roomCode)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(DuelManager.toState(room))
                }
            }

            post("/duel/rematch") {
                val body = call.receive<NextRoundRequest>()
                val room = DuelManager.rematch(body.roomCode)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Oda bulunamadı"))
                } else {
                    call.respond(DuelManager.toState(room))
                }
            }
        }
    }.start(wait = true)
}
