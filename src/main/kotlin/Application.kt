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
@Serializable data class SolvableCheckResponse(val solvable: Boolean, val answer: String? = null)
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

    // 🏀 YENİ: futboldaki AYNI performans/güvenlik mimarisi artık basketbolda
    // da uygulanıyor — canlı REPLACE() zinciri yerine, sunucu başlarken BİR
    // KEZ hesaplanmış "name_std" sütunu kullanılıyor.
    DatabaseClient.ensureBasketballNameStdColumns()

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
                    <p>2015 yazına gelindiğinde David de Gea, sadece 24 yaşında olmasına rağmen dünyanın en iyi 
                    kalecilerinden biri hâline gelmişti. Manchester United'a 2011'de, henüz 20 yaşındayken, oldukça 
                    tartışmalı bir transferle katılmıştı — İngiliz basını onu "fazla ince, fazla genç" bularak 
                    eleştirmiş, ilk sezonu zorlu geçmişti. Ama sonraki yıllarda kendini kanıtladı; United'ın en zayıf 
                    olduğu dönemlerde bile kulübü tek başına maçlarda ayakta tutan performanslar sergiledi. Bu da 
                    onu, kariyerinin bir sonraki büyük adımını atmaya hazır bir yıldıza dönüştürmüştü. Real Madrid'in 
                    ilgisi yeni değildi — İspanyol kulübü yıllardır bir numara kaleci arayışındaydı ve kendi 
                    akademisinden yetişen, İspanyol millî takımının da geleceği sayılan de Gea'yı geri getirmek 
                    istiyordu.</p>
                    <p>2015 yaz transfer döneminin son gecesi, futbol tarihinin en garip anlarından birine sahne 
                    oldu. Real Madrid ve Manchester United, kaleci David de Gea'nın Madrid'e, Keylor Navas'ın ise 
                    karşılığında Manchester'a gitmesi için prensipte anlaşmaya varmıştı. Anlaşmanın şartları 
                    üzerinde haftalarca çalışılmış, iki taraf da imzaya hazır hale gelmişti. Ama İspanyol Ligi'nin 
                    transferleri kayıt altına aldığı resmi sistem (TMS — Transfer Matching System) üzerinden 
                    gönderilmesi gereken evraklar, gecenin geç saatlerinde bir türlü karşı tarafa ulaşmadı.</p>
                    <p>United, evrakları saat kapanmadan önce gönderdiğini iddia ederken, Real Madrid evrakların 
                    eksik ya da yanlış formatta geldiğini öne sürdü. Bazı kaynaklara göre belgeler tam da gece 
                    yarısından yaklaşık 2 dakika sonra sisteme düştü — yani transfer penceresi resmen kapandıktan 
                    hemen sonra. Bir başka iddiaya göre ise sorun, gönderilen belgenin dijital imza formatındaki 
                    teknik bir uyuşmazlıktan kaynaklandı. İki kulüp de kamuoyuna birbirini suçlayan resmi açıklamalar 
                    yaptı; Manchester United, gecikmenin tamamen Real Madrid'in ihmalinden kaynaklandığını söylerken, 
                    İspanyol kulübü kendi sorumluluklarını yerine getirdiğini savundu. Olay günlerce basında "fax 
                    makinesi skandalı" olarak konuşuldu — çünkü halk arasında bu tür resmi transfer belgelerinin 
                    faksla gönderildiği yönünde (kısmen doğru, kısmen abartılı) bir algı oluşmuştu.</p>
                    <p>Sonuç olarak De Gea, hayalindeki Real Madrid transferini son anda kaçırdı ve planlanmadık 
                    şekilde Manchester United'da kalmaya devam etti. İlk başta bu durumun kalecinin motivasyonunu 
                    olumsuz etkileyeceği düşünülüyordu — sonuçta bir oyuncunun, kulübünün onu satmaya razı olduğu 
                    ama teknik bir aksaklık yüzünden gidemediği bir sezona nasıl adapte olacağı belirsizdi. Ama 
                    olanlar tam tersi bir etki yarattı. De Gea o sezon kariyerinin en iyi performanslarından birini 
                    sergiledi, sezon sonunda kulübün yılın oyuncusu seçildi ve United taraftarları arasında adeta bir 
                    kült figüre dönüştü.</p>
                    <p>İlginç bir şekilde bu "kayıp" transfer, kaleci için kötü sonuçlanmadı — United'da sekiz sezon 
                    daha forma giyip kulübün tarihindeki en çok maça çıkan kalecilerden biri oldu, birçok sezon 
                    Premier Lig'in "Yılın Kalecisi" ödülünü kazandı. Real Madrid ise o dönem başka kaleci arayışlarına 
                    yöneldi ve de Gea'yı bir daha hiç bu denli ciddi şekilde transfer gündemine getirmedi. Geriye 
                    dönüp bakıldığında, o gece yaşanan teknik aksaklık, iki kulübün de kariyer planlarını kalıcı 
                    şekilde değiştirdi.</p>
                    <p>Bu olay aynı zamanda, transfer sistemlerinin teknik altyapısının futbolun ekonomik 
                    boyutuyla ne kadar iç içe geçtiğini de gözler önüne serdi. TMS sistemi, kulüpler arası her 
                    transferi kayıt altına alan, oyuncunun uluslararası transfer sertifikasının doğru şekilde 
                    işlenmesini sağlayan resmi bir altyapıdır — ve bu olay sonrasında birçok kulüp, son dakika 
                    transferlerinde evrak işlemlerini çok daha erken tamamlama alışkanlığı edindi. FIFA ve ulusal 
                    federasyonlar da, benzer aksaklıkların bir daha yaşanmaması için sistemin güvenilirliğini 
                    artırmaya yönelik iyileştirmeler üzerinde çalıştı.</p>
                                        <p>Yıllar sonra futbol basınında yapılan geriye dönük değerlendirmelerde, bu transferin 
                    gerçekleşmiş olması durumunda hem De Gea'nın hem de Real Madrid'in kariyer çizgisinin nasıl 
                    farklı şekilleneceği sıkça tartışma konusu oldu. Real Madrid, o dönemden sonra da uzun yıllar 
                    kaleci pozisyonunda istikrar arayışını sürdürdü, De Gea ise United'da inşa ettiği efsanevi 
                    statüsüyle bu "kayıp" transferin aslında kendisi için en doğru sonuç olduğunu kanıtladı.</p>
                    <p>Bazen milyonlarca euroluk bir transferi, teknolojik bir aksaklık ya da birkaç dakikalık bir 
                    gecikme belirleyebiliyor. TransferKolik'te "Manchester United" ve "Real Madrid" aratarak, 
                    bu iki kulüpte gerçekten forma giymiş oyuncuların tam listesini görebilirsiniz.</p>
                    """.trimIndent()
                ),
                "alexis-sanchez-mourinho-telefon-hamlesi" to Pair(
                    "Mourinho'nun Telefon Açıp Çaldığı Transfer",
                    """
                    <p>2018'e girerken Alexis Sanchez, Arsenal'in en büyük yıldızlarından biriydi ama kulüple 
                    ilişkisi giderek gerginleşiyordu. Sözleşmesi 2018 yazında sona erecekti ve taraflar arasındaki 
                    yenileme görüşmeleri tıkanmıştı. Şili'li forvet, kariyerinin geri kalanında şampiyonluk 
                    mücadelesi veren bir kulüpte oynamak istediğini açıkça belli ediyordu — Arsenal ise o dönem 
                    Premier Lig'in en üst sıralarından uzaklaşmış durumdaydı. Bu nedenle 2018 ocak transfer 
                    döneminde Sanchez'in ayrılığı neredeyse kaçınılmaz görülüyordu; tek soru "nereye" sorusuydu.</p>
                    <p>Ocak 2018'de Alexis Sanchez'in gözü kesin olarak Manchester City'deydi — eski hocası Pep 
                    Guardiola ile Barcelona günlerinden kalan güçlü bir bağı vardı ve City'nin o sezon oynadığı 
                    üstün futbol, oyuncuyu fazlasıyla cezbediyordu. Şili'li yıldız, City yönetimiyle neredeyse her 
                    gün iletişim hâlindeydi, kişisel şartlar üzerinde büyük ölçüde anlaşmışlardı. Basında transferin 
                    sadece resmi imza aşamasına geldiği, kulüpler arası bonservis pazarlığının tamamlanmak üzere 
                    olduğu yazılıyordu. Herkes bu transferin bir formaliteden ibaret olduğunu düşünüyordu.</p>
                    <p>Ama tam o sırada beklenmedik bir gelişme yaşandı: Manchester United teknik direktörü Jose 
                    Mourinho, doğrudan devreye girip Sanchez ile kişisel olarak iletişime geçti. Mourinho'nun 
                    oyuncuya, kulübün simgesel 7 numaralı formasını teklif ettiği yönünde haberler basına yansıdı — 
                    bu numara tarihsel olarak United'da Cristiano Ronaldo ve David Beckham gibi isimlerin taşıdığı, 
                    özel bir anlam ifade eden bir formaydı. Bu teklif, City ile neredeyse tamamlanmış olan anlaşmayı 
                    bir anda gölgede bıraktı ve Sanchez'in kararını sorgulamasına yol açtı.</p>
                    <p>Aynı dönemde City tarafında da bir gelişme yaşandı: kulüp, Sanchez'in menajerlik ekibinin son 
                    anda daha yüksek bir ücret talep etmesi üzerine görüşmelerden çekilmeye karar verdi — City 
                    yönetimi, oyuncu transfer politikasında belirli bir ücret tavanını aşmama konusunda katıydı. 
                    Bu gelişme, United'a hamlesini tamamlaması için gereken alanı tamamen açtı. United, anlaşmayı 
                    hızlandırmak için Henrikh Mkhitaryan'ı da işin içine katarak bir oyuncu takası şeklinde yapı 
                    kurdu — Mkhitaryan Arsenal'e giderken, Sanchez ters yönde Manchester'a gitti. Bu, Premier 
                    Lig'de o döneme kadar görülen en yüksek profilli oyuncu takaslarından biri olarak tarihe geçti.</p>
                    <p>Ama bu "zafer" United için kısa sürede büyük bir hayal kırıklığına dönüştü. Sanchez, 
                    Arsenal'deki döneminde gösterdiği patlayıcı formdan çok uzak bir performans sergiledi; kulüpte 
                    geçirdiği yaklaşık 19 ay boyunca sadece 5 lig golü atabildi ve kariyerinin en kötü döneminden 
                    birini yaşadı. Yüksek maaşına rağmen sahadaki katkısı beklentilerin çok altında kaldı, taraftar 
                    ile ilişkisi de giderek bozuldu. Sonunda United, 2019'da Sanchez'i İtalya'ya, İnter'e kiralık 
                    olarak gönderdi ve bu transfer kulübün tarihindeki en başarısız bonservis harcamalarından biri 
                    olarak anılır oldu.</p>
                    <p>Bu transfer, aynı zamanda Premier Lig'de o dönem giderek yaygınlaşan bir eğilimin de 
                    parçasıydı — büyük kulüpler, yıldız oyuncuları elde etmek için artık sadece bonservis parası 
                    değil, karşılıklı oyuncu takasları da kullanmaya başlamıştı. Bu yöntem, kulüplerin bütçe 
                    üzerindeki baskısını azaltırken, aynı zamanda iki takım arasında karmaşık müzakereler 
                    gerektiriyordu. Sanchez-Mkhitaryan takası, bu stratejinin en dikkat çekici örneklerinden biri 
                    olarak, sonraki yıllarda benzer takasların da önünü açtı.</p>
                                        <p>Bu transfer, günümüzde hâlâ Premier Lig tarihinin en çok konuşulan "yanlış transferleri" 
                    arasında sayılıyor. Futbol analistleri, Sanchez'in performans düşüşünü çeşitli faktörlere 
                    bağladı — yaşının ilerlemesi, oyun tarzının Premier Lig'in o dönemki temposuna uyum 
                    sağlayamaması ve United'daki takım kimyasının kendisine uygun olmaması gibi nedenler sıkça 
                    dile getirildi. Bu olay, bugün hâlâ genç scoutlar ve transfer analistleri için "yıldız 
                    isim her zaman doğru transfer anlamına gelmez" prensibinin öğretici bir örneği olarak 
                    kullanılıyor.</p>
                                        <p>Bu transfer aynı zamanda, futbolda maaş pazarlıklarının bir anlaşmayı nasıl son anda 
                    bozabileceğinin de öğretici bir örneğiydi. Menajerlerin son dakika taleplerinin, kulüpler 
                    arasındaki dengeyi nasıl aniden değiştirebileceği, sonraki yıllarda birçok transfer 
                    uzmanı tarafından incelenen bir konu hâline geldi.</p>
                                        <p>Sanchez'in Arsenal'deki önceki performansı göz önüne alındığında, bu düşüş daha da 
                    çarpıcı görünüyordu. Şili'li yıldız, İngiltere'ye geldiği ilk sezonlardan itibaren ligin en 
                    yaratıcı ve en üretken hücum oyuncularından biri olarak kabul ediliyor, tek başına maçları 
                    kazandırabilen nadir oyunculardan biri sayılıyordu. Bu bağlamda, United'daki düşüşü sadece 
                    kulüp değişikliğiyle değil, aynı zamanda oyuncunun kariyerinin doğal bir dönüm noktasına 
                    denk gelmesiyle de açıklanmaya çalışıldı.</p>
                    <p>Bazen bir transferi "kazanmak", o transferi başarılı kılmıyor. TransferKolik'te "Arsenal" 
                    ve "Manchester United" aratarak, bu iki kulüpte gerçekten forma giymiş oyuncuların tam listesini 
                    görebilirsiniz.</p>
                    """.trimIndent()
                ),
                "robinho-chelsea-tisortleri-iptal-transfer" to Pair(
                    "Satışa Çıkan Formalar Yüzünden İptal Olan Transfer",
                    """
                    <p>2008 yazına gelindiğinde Robinho, Real Madrid'de üç sezon geçirmiş, kulüple ilişkisi giderek 
                    gerginleşen bir yıldızdı. Brezilyalı kanat oyuncusu, teknik direktör değişiklikleriyle birlikte 
                    kadrodaki konumunun istikrarsızlaştığını hissediyor, İspanya'daki hayatından memnun olmadığını 
                    sık sık dile getiriyordu. Bu dönemde Chelsea, Premier Lig'de güçlü bir kadro kurma hedefindeydi 
                    ve Robinho'nun yaratıcılığı, kulübün transfer stratejisine tam olarak uyuyordu. Görüşmeler hızla 
                    ilerledi; oyuncu bizzat Londra'da yaşamak istediğini açıkça belirtti ve anlaşmanın neredeyse 
                    kesinleştiği izlenimi oluştu.</p>
                    <p>Chelsea o kadar emindi ki, kulübün resmi mağaza sitesinde Robinho isimli formalar, anlaşma 
                    henüz resmileşmeden satışa sunuldu — bu, İngiliz futbolunda alışılmadık derecede erken ve 
                    özgüvenli bir hamleydi. Kulüpler arası transferlerde, resmiyet kazanmamış bir anlaşmanın bu denli 
                    aleni şekilde kutlanması nadiren görülürdü.</p>
                    <p>Bu detay, Real Madrid Başkanı Ramón Calderón'u derinden rahatsız etti. Kulübe göre bu, 
                    saygısızca bir davranıştı — sanki transfer çoktan bitmiş, İspanyol kulübünün onayı formaliteden 
                    ibaretmiş gibi hareket ediliyordu. Calderón'un bakış açısına göre, oyuncu bonservisinin son sözü 
                    hâlâ Real Madrid'e aitti ve Chelsea'nin bu tavrı, kulübün itibarını zedeleyen bir mesaj 
                    veriyordu. Öfkelenen başkan, görüşmeleri tamamen kesme kararı aldı ve Chelsea ile devam eden 
                    anlaşmayı iptal etti — transfer, imzaya saatler kala tamamen çöktü.</p>
                    <p>Tam o sırada, transfer döneminin son günü, futbol dünyasını sarsan başka bir haber geldi: 
                    Manchester City'nin Abu Dabi Birleşik Grubu tarafından satın alındığı açıklandı. Kulüp, bir 
                    gecede dünyanın en zengin futbol takımlarından biri haline geldi ve yeni sahipleri, bu güçlü 
                    girişi kanıtlayacak yüksek profilli bir imza atmak istiyordu. Robinho, Real Madrid'den ayrılmaya 
                    zaten hazır olduğu ve piyasada aktif olarak konuşulduğu için, City'nin radarına anında girdi. 
                    Saatler içinde anlaşma tamamlandı — süreç o kadar hızlı ilerledi ki, standart sağlık kontrolü 
                    dahi normalden çok daha kısa sürede tamamlandı.</p>
                    <p>İşin ilginç tarafı, Robinho tanıtım basın toplantısında yanlışlıkla eski hedefi Chelsea'yi 
                    anarak konuştu — bir gazeteci ona, aslında imza attığı kulübün Manchester City olduğunu 
                    hatırlatmak zorunda kaldı. Bu küçük detay, transferin ne kadar hızlı ve beklenmedik şekilde 
                    şekillendiğinin sembolik bir göstergesi oldu. Robinho, City'de geçirdiği dönemde kulübün yeni 
                    döneminin ilk büyük yıldızı olarak tarihe geçti, ama kulübün asıl altın çağı, birkaç yıl sonra 
                    gelecek başka transferlerle şekillenecekti.</p>
                    <p>Bu olay, aynı zamanda futbol kulüplerinin resmi web sitelerinde ve mağazalarında, henüz 
                    kesinleşmemiş transferlerle ilgili içerik yayınlarken ne kadar dikkatli olmaları gerektiğinin 
                    de erken bir örneği oldu. Sonraki yıllarda birçok kulüp, benzer hatalara düşmemek için, bir 
                    transferi resmi olarak duyurmadan önce tüm evrak işlemlerinin tamamlanmasını beklemeyi 
                    standart bir uygulama hâline getirdi.</p>
                                        <p>Bu olay, aynı zamanda Manchester City'nin 2008 sonrası dönemde nasıl agresif bir transfer 
                    politikası izleyeceğinin de habercisiydi. Kulüp, sonraki yıllarda dünyanın en pahalı 
                    transferlerini gerçekleştirerek, önce Premier Lig'de sonra Avrupa'da rekabetçi bir güç hâline 
                    geldi. Robinho'nun transferi, bu dönüşümün sembolik başlangıç noktalarından biri olarak 
                    kabul ediliyor — kulübün yeni sahiplerinin, futbol piyasasında ne kadar hızlı ve kararlı 
                    hareket edebileceğinin ilk somut kanıtıydı.</p>
                                        <p>Bu hikaye, futbol taraftarları arasında da uzun süre efsaneleşerek anlatılmaya devam etti. 
                    Robinho'nun basın toplantısındaki o küçük dil sürçmesi, internette ve futbol belgesellerinde 
                    defalarca yeniden paylaşılan, sporun beklenmedik ve komik anlarından biri olarak hafızalarda 
                    yer etti.</p>
                                        <p>Robinho'nun futbol kariyeri, bu transferden sonra da birçok farklı ülke ve kulüpte devam etti 
                    — Brezilyalı oyuncu, Santos, Milan ve çeşitli başka kulüplerde forma giydi. Ama Manchester 
                    City'ye katıldığı o yaz, kariyerinin medya açısından en çok konuşulan dönemlerinden biri 
                    olarak akıllarda kaldı; bu dönem, kulübün yeni sahiplik yapısının futbol dünyasına verdiği 
                    ilk büyük mesaj olarak da tarihe geçti.</p>
                                        <p>Bu olay, futbol tarihinde kulüplerin transfer sürecinde ne kadar temkinli davranması 
                    gerektiğinin klasik bir örneği olarak ders kitaplarına girdi.</p>
                                        <p>Bugün futbol kulüpleri, resmi duyurulardan önce hukuki ve idari süreçlerin eksiksiz 
                    tamamlanmasına çok daha fazla özen gösteriyor — bu da kısmen bu tür geçmiş deneyimlerin 
                    getirdiği bir öğrenme sürecinin sonucu.</p>
                    <p>TransferKolik'te "Real Madrid" ve "Manchester City" aratarak, bu iki kulüpte de forma giymiş 
                    oyuncuların tam listesine ulaşabilirsiniz.</p>
                    """.trimIndent()
                ),
                "robben-united-tesislerini-gezdi-chelseaya-gitti" to Pair(
                    "United'ın Tesislerini Gezip Chelsea'ye İmza Atan Oyuncu",
                    """
                    <p>2003-2004 sezonunda Arjen Robben, PSV Eindhoven formasıyla Hollanda liginin en dikkat çeken 
                    isimlerinden biri hâline gelmişti. Hızı, çift ayağını da etkili kullanabilmesi ve bitiricilik 
                    yeteneğiyle, sadece 20 yaşında olmasına rağmen Avrupa'nın büyük kulüplerinin radarına girmişti. 
                    Genç kanat oyuncusu, kariyerinde bir sonraki adımı büyük bir ligde atmaya hazırdı ve bu dönemde 
                    birçok İngiliz kulübü onunla ilgileniyordu. Manchester United, o yaz kadrosunu güçlendirmek 
                    isteyen kulüplerin başında geliyordu ve Robben, bu listenin en üst sıralarındaydı.</p>
                    <p>2004 yazında Manchester United, transferi neredeyse bitirmişti — Robben, kulübün Carrington 
                    antrenman tesislerini bizzat ziyaret etmiş, oradaki altyapıyı ve çalışma ortamını incelemiş, 
                    kişisel şartlar üzerinde de anlaşmaya varmıştı. Kulüp çevrelerinde bu, sadece resmi imza 
                    aşamasının kalması gereken, neredeyse tamamlanmış bir transfer olarak konuşuluyordu.</p>
                    <p>Ama Chelsea, sahneye son anda girdi. Roman Abramovich'in devraldığı büyük finansal güçle 
                    desteklenen Londra kulübü, hem Robben'a hem de PSV'ye çok daha cazip bir teklif sundu — 
                    oyuncuya United'ın önerdiğinden daha yüksek bir maaş, kulübe ise daha yüksek bir bonservis 
                    bedeli. O dönemde Chelsea, Abramovich'in devralmasının ardından transfer piyasasında agresif bir 
                    şekilde hareket ediyor, Avrupa'nın en iyi genç yeteneklerini toplamaya çalışıyordu. Bu teklif 
                    karşısında Hollandalı yıldız, United'a verdiği sözlü sözden vazgeçip yönünü Stamford Bridge'e 
                    çevirdi.</p>
                    <p>Manchester United için bu, gerçek bir hayal kırıklığıydı — kulüp yönetimi, transferin 
                    neredeyse kesinleştiğini düşünüyor, oyuncunun tesisleri gezmesini de bunun bir teyidi olarak 
                    görüyordu. Ama son anda gelen daha yüksek teklif karşısında, United rekabet etmemeyi tercih etti 
                    ve transfer United'a hiç gerçekleşmeden sona erdi.</p>
                    <p>Robben'ın kararı zamanla doğru çıktı: Chelsea'de geçirdiği üç sezonda iki kez Premier Lig 
                    şampiyonluğu yaşadı, kulübün o dönemki başarılı döneminin önemli bir parçası oldu. Ardından 
                    2007'de Real Madrid'e transfer oldu, orada da önemli bir rol üstlendi. Kariyerinin gerçek 
                    zirvesine ise 2009'da katıldığı Bayern Münih'te ulaştı — Bavyera kulübüyle birlikte çok sayıda 
                    Bundesliga şampiyonluğu kazandı ve 2013 Şampiyonlar Ligi finalinde attığı son dakika golüyle 
                    kulübe tarihi bir kupa kazandırdı. Robben, bugün hâlâ Bayern Münih tarihinin en sevilen 
                    yabancı oyuncularından biri olarak anılıyor.</p>
                                        <p>Bu transfer aynı zamanda, 2000'lerin ortasında Chelsea'nin Abramovich döneminde 
                    izlediği agresif transfer politikasının erken bir örneğiydi — kulüp, sadece oyuncuların 
                    yeteneğine değil, aynı zamanda rakip kulüplerin elindeki anlaşmaları "kırma" kapasitesine de 
                    güveniyordu. Bu strateji, sonraki yıllarda Premier Lig'de transfer piyasasının dinamiklerini 
                    kalıcı olarak değiştiren bir dönemin habercisi oldu.</p>
                                        <p>Robben'ın kariyeri boyunca gösterdiği bu tür ani yön değişiklikleri, aslında onun futbol 
                    hayatının genel bir teması hâline geldi — birçok kez, en son anda gelen tekliflerin 
                    kariyerinin gidişatını değiştirdiği görüldü. Bu durum, onu sadece sahadaki performansıyla 
                    değil, kariyer kararlarındaki öngörülemezliğiyle de futbol tarihinde akılda kalan bir isim 
                    hâline getirdi.</p>
                                        <p>Bu transfer süreci, aynı zamanda 2000'lerin ortasında futbol piyasasının nasıl hızla 
                    değiştiğinin de bir göstergesiydi. Yeni sahiplik yapıları ve artan finansal güçler, kulüpler 
                    arası rekabeti tamamen yeni bir boyuta taşıyordu — Robben'ın hikayesi, bu dönüşümün erken ve 
                    somut örneklerinden biri oldu.</p>
                                        <p>Bu tesis ziyareti anekdotu, futbol camiasında yıllarca "neredeyse olan transferler" listesinde 
                    özel bir yere sahip oldu. Manchester United taraftarları arasında, "keşke Robben'ı kaçırmasaydık" 
                    şeklinde nostaljik yorumlar hâlâ zaman zaman gündeme geliyor — özellikle kulübün sonraki 
                    yıllarda kanat oyuncusu arayışında yaşadığı zorluklar düşünüldüğünde, bu kayıp transfer daha 
                    da anlamlı hâle geliyor.</p>
                                        <p>Bu tür son dakika değişiklikleri, futbolun ne kadar öngörülemez bir dünya olduğunu her 
                    seferinde yeniden hatırlatıyor.</p>
                                        <p>Robben'ın kariyeri, genç bir yeteneğin doğru zamanlama ve doğru fırsatlarla ne kadar 
                    yükseğe çıkabileceğinin de kanıtı oldu — Chelsea'deki başlangıcı, onu sonunda dünyanın en 
                    saygın kulüplerinden birkaçında oynamaya taşıyan uzun bir yolculuğun ilk adımıydı.</p>
                                        <p>Sonuç olarak bu hikaye, futbolun para gücüyle şekillenen modern döneminin başlangıcında 
                    yaşanan en çarpıcı örneklerden biri olarak kalıcı bir yer edindi.</p>
                    <p>Bu hikaye, bir oyuncunun sözlü anlaşmasının bile son ana kadar garanti olmadığını gösteriyor 
                    — özellikle parasal gücü yüksek bir rakip devreye girdiğinde. TransferKolik'te "PSV" ve 
                    "Chelsea" aratarak, bu iki kulüpte de forma giymiş oyuncuları bulabilirsiniz.</p>
                    """.trimIndent()
                ),
                "ray-allen-kevin-garnett-telefon-numarasi" to Pair(
                    "Silinen Bir Telefon Numarası: Ray Allen'ın Rakip Takıma Geçişi",
                    """
                    <p>2007-2012 yılları arasında Boston Celtics, NBA'in en güçlü kadrolarından birine sahipti. 
                    Kevin Garnett, Paul Pierce ve Ray Allen'dan oluşan üçlü — basın ve taraftarlar tarafından "Big 
                    Three" (Büyük Üçlü) olarak anılıyordu — 2008'de kulübe uzun yıllar sonra ilk şampiyonluğunu 
                    kazandırmıştı. Bu üç oyuncu, sahada gösterdikleri uyumun ötesinde, birbirlerine karşı derin bir 
                    kişisel bağ ve saygı geliştirmişti; birçok röportajda kariyerlerinin geri kalanını birlikte 
                    tamamlamak istediklerini dile getiriyorlardı. Ancak 2012 sezonunun ardından, kadronun yaşlanan 
                    yapısı ve rekabetçiliğini koruma çabası, kulübü zor kararlar almaya itti.</p>
                    <p>2012 yazında Ray Allen serbest kaldığında, NBA tarihinin en dramatik takım değiştirme 
                    hikayelerinden birine imza attı. Boston, oyuncuya yeniden sözleşme teklif etmiş olsa da, Allen 
                    kariyerinin bu aşamasında farklı bir meydan okuma arıyordu. Herkesi şok eden karar geldi: Ray 
                    Allen, Boston'un o dönemki en büyük rakibi olan Miami Heat'e transfer oldu — LeBron James ve 
                    Dwyane Wade'in yıldızlaştığı, şampiyonluğa en yakın adaylardan biri olan bir kadroya katıldı.</p>
                    <p>Bu karar, eski takım arkadaşlarını derinden yaraladı. Kevin Garnett'in bu transfer sonrası 
                    Allen ile iletişimini büyük ölçüde kestiği, basın tarafından defalarca haber yapıldı; ikilinin 
                    yıllarca konuşmadığı, aralarındaki soğukluğun uzun süre devam ettiği yönünde çok sayıda haber 
                    çıktı. Paul Pierce da benzer şekilde, üçlünün kariyerlerini birlikte bitireceğine dair beklentisi 
                    olduğunu, bu ayrılığın kendisi için sürpriz ve hayal kırıklığı yarattığını kamuoyu önünde ifade 
                    etti.</p>
                    <p>Gerginlik sadece sözlü açıklamalarla sınırlı kalmadı — Allen'ın Miami formasıyla Boston'a ilk 
                    dönüşünde, eski takım arkadaşlarıyla arasındaki soğukluk sahada da gözle görülür hâldeydi. Bu 
                    atmosfer, NBA basınında uzun süre "Big Three'nin sessiz savaşı" olarak konuşuldu. Allen, 
                    sonraki yıllarda yaptığı röportajlarda bu ayrılığın kendisi için de kolay olmadığını, ama 
                    kariyerinin bu noktasında şampiyonluk şansını en yükseğe çıkarmak istediğini defalarca dile 
                    getirdi.</p>
                                        <p>Bu hikaye aynı zamanda, profesyonel sporda "takım arkadaşlığı" ile "kariyer çıkarları" 
                    arasındaki dengenin ne kadar hassas olabileceğinin de bir göstergesiydi. Spor psikologları ve 
                    yorumcular, yıllar sonra bu olayı, uzun süreli takım arkadaşlıklarının bile bir oyuncunun 
                    bireysel kariyer hedefleri karşısında ne kadar kırılgan olabileceğinin klasik bir örneği 
                    olarak incelemeye devam etti.</p>
                                        <p>Bu üçlünün (Garnett, Pierce, Allen) daha sonraki yıllarda basketbol camiasında yeniden bir 
                    araya gelme girişimleri de oldu, ancak eski yakınlıklarının tam olarak geri gelip gelmediği 
                    hâlâ tartışma konusu. Basketbol tarihçileri, bu hikayeyi genellikle NBA'de "kariyerin sonuna 
                    doğru alınan kararların, en güçlü dostlukları bile nasıl sınayabileceğinin" bir örneği olarak 
                    anlatmaya devam ediyor.</p>
                                        <p>Allen'ın kararı, aynı zamanda NBA'de "kariyerin son evresinde şampiyonluk arayışı" 
                    kavramının ne kadar güçlü bir motivasyon kaynağı olabileceğini de gösterdi. Yaşı ilerleyen 
                    birçok oyuncu, benzer şekilde kariyerlerinin son yıllarında şampiyonluk şansını en 
                    yükseğe çıkaracak takımlara yönelmeye başladı.</p>
                                        <p>Bu üçlünün 2008 şampiyonluğu, Boston Celtics tarihinin en unutulmaz sezonlarından biri olarak 
                    kayıtlara geçmişti — kulüp, yirmi iki yıl aradan sonra yeniden şampiyon olmuştu. Bu ortak 
                    başarının ardından yaşanan ayrılık, taraftarlar için de duygusal açıdan zor bir süreç oldu; 
                    birçok Celtics taraftarı, Allen'ın ayrılışını sadece sportif değil, kişisel bir kayıp olarak 
                    da yaşadı.</p>
                                        <p>Bu hikaye, spor tarihinde dostluk ile rekabetin nasıl iç içe geçebileceğinin unutulmaz bir 
                    örneği olarak kalmaya devam ediyor.</p>
                                        <p>Bu olay, spor gazeteciliğinde de uzun süre gündemde kaldı — birçok yazar, Allen'ın kararını 
                    "profesyonellik" ile "sadakat" arasındaki gerilim üzerinden değerlendiren makaleler kaleme 
                    aldı, bu da konunun basketbol kültüründe ne kadar derin bir etki bıraktığını gösterdi.</p>
                    <p>Sportif açıdan ise Allen'ın kararı isabetliydi — Miami Heat formasıyla 2013'te bir şampiyonluk 
                    daha kazandı; üstelik o serinin finalinde, seriyi uzatmaya taşıyan kritik bir son saniye 
                    üçlüğüyle basketbol tarihine geçen anlardan birine imza attı. Bu başarı, kararının sportif 
                    açıdan ne kadar doğru olduğunu kanıtlar nitelikteydi. Ama bu transfer, basketbolda "takım 
                    sadakati" kavramının ne kadar hassas bir konu olduğunu gösteren, yıllarca konuşulan bir örnek 
                    olarak basketbol tarihine kazındı — hatta Garnett ile Allen arasındaki soğukluğun, ikisinin 
                    kariyerleri bittikten sonra bile uzun süre devam ettiği biliniyor.</p>
                    """.trimIndent()
                ),
                "kevin-durant-yenildigi-takima-katildi" to Pair(
                    "Kaybettiği Seride Rakibine Katılan Yıldız: Kevin Durant'ın Tartışmalı Kararı",
                    """
                    <p>2016 sezonuna kadar Kevin Durant, Oklahoma City Thunder'ın kurucu yıldızı olarak kabul 
                    ediliyordu. Kulübe 2007'de katılmış, Russell Westbrook ile birlikte takımı yıllar içinde 
                    NBA'in en tehlikeli genç kadrolarından birine dönüştürmüştü. Thunder, 2012'de bir kez finale 
                    çıkmış ama şampiyonluğu kazanamamıştı; sonraki sezonlarda da sürekli olarak play-off'ların ileri 
                    turlarına ulaşan ama son adımı atamayan bir takım profili çizmişti. 2016 sezonu, bu açığı 
                    kapatmak için büyük bir fırsat olarak görülüyordu.</p>
                    <p>2016 yazı, NBA tarihinin en çok tartışılan transfer kararlarından birine sahne oldu. Kevin 
                    Durant'ın takımı Oklahoma City Thunder, o sezonki Batı Konferansı finalinde Golden State 
                    Warriors'a karşı 3-1 öne geçmişti — şampiyonluğa adeta bir adım kalmıştı, seriyi kapatmaları 
                    için tek bir galibiyet yeterliydi. Ama Warriors, NBA tarihinde nadir görülen bir geri dönüşle 
                    seriyi 4-3 çevirdi ve finale kendisi gitti; Thunder ise şampiyonluğa bu kadar yaklaşmışken elini 
                    boş bıraktı.</p>
                    <p>Herkesin beklediği, Durant'ın bu acı yenilginin ardından takımını daha da güçlendirip, 
                    Warriors'tan bu rövanşı almak için sabırla çalışmasıydı. Ama yaz transfer döneminde Durant, tam 
                    da kendisini eleyen o Warriors kadrosuna katılma kararı aldı — o dönemde NBA'in en güçlü 
                    kadrosu, kendisini az farkla elemiş olan takımın bizzat parçası hâline geldi. Bu haber, sosyal 
                    medyada adeta bir kaosa yol açtı — bazı Thunder taraftarları, tepkilerini göstermek için Durant 
                    formalarını sosyal medyada paylaşarak yaktı, bazı eski takım arkadaşları ise kararı doğrudan 
                    eleştiren açıklamalar yaptı.</p>
                    <p>Eleştirmenler, Durant'ı "kendi gücüyle şampiyonluk kazanmak yerine, kendisini yenen takıma 
                    sığınmakla" suçladı — bu eleştiri, NBA tarihinde bir yıldızın "kolay yol" seçtiği yönündeki en 
                    yaygın anlatılardan birine dönüştü. Durant'ın bu kararı, basketbol camiasında "süper takım" 
                    kavramının aşırıya kaçtığına dair geniş bir tartışmayı da beraberinde getirdi; birçok yorumcu, 
                    zaten güçlü bir kadroya bir başka MVP adayının katılmasının lig dengesini bozduğunu savundu.</p>
                    <p>Ama Durant, kararının arkasında durdu ve Golden State'te geçirdiği üç sezon boyunca üst üste 
                    iki şampiyonluk kazandı, her iki finalde de Final MVP'si seçildi — bu, kararının en azından 
                    sportif başarı açısından tartışmasız şekilde doğru çıktığını gösterdi. Ancak eleştiriler hiç 
                    dinmedi; birçok basketbol otoritesi bu iki şampiyonluğu, Durant'ın kariyerinin en büyük 
                    başarıları arasında saymakla birlikte, "en zor yoldan kazanılmış" şampiyonluklar olarak 
                    görmedi.</p>
                                        <p>Bu olay aynı zamanda, NBA'de "süper takım" kavramının 2010'ların ikinci yarısında nasıl 
                    normalleşmeye başladığının da bir göstergesiydi. Durant'ın kararının ardından, lig genelinde 
                    yıldız oyuncuların güçlü kadrolara katılma eğilimi arttı — bu durum, hem lig yönetiminin hem 
                    de taraftarların, rekabet dengesi konusunda uzun süreli tartışmalar yürütmesine neden oldu.</p>
                                        <p>Bugün geriye dönüp bakıldığında, Durant'ın bu kararı, kariyerinin en tartışmalı ama aynı 
                    zamanda en başarılı dönemlerinden birinin başlangıcı olarak görülüyor. Golden State'teki 
                    üç sezonu, bireysel istatistikleri açısından da kariyerinin zirvesi sayılıyor — bu da 
                    eleştirilerin sportif başarıyı gölgeleyip gölgelemediği konusunda basketbol camiasında 
                    hâlâ süregelen bir tartışmayı besliyor.</p>
                                        <p>Bu tartışma, günümüzde bile NBA analistleri arasında canlılığını koruyor. Durant'ın 
                    kararı, sporcuların kariyer özgürlüğü ile taraftar beklentileri arasındaki dengeyi 
                    sorgulayan geniş bir tartışmanın parçası olmaya devam ediyor.</p>
                                        <p>Durant'ın bu kararı, aynı zamanda NBA'de "MVP seviyesindeki oyuncuların" kariyer risklerini 
                    nasıl değerlendirdiğine dair de önemli bir örnek sundu. Sporcu, bireysel kariyer istatistikleri 
                    yerine takım başarısını önceliklendirdiğini defalarca vurguladı — bu yaklaşım, kariyerinin 
                    ilerleyen dönemlerinde de benzer büyük kararlar almasında belirleyici bir felsefe olarak 
                    kaldı.</p>
                                        <p>Bu tartışma, sporda başarı ile kamuoyu algısının her zaman örtüşmeyebileceğinin de bir 
                    kanıtı oldu.</p>
                                        <p>Bu olay, aynı zamanda sporda "adil rekabet" kavramının nasıl öznel bir algı meselesi 
                    olabileceğini de gösterdi — bazıları için tamamen yasal ve mantıklı bir kariyer hamlesi olan 
                    bu karar, bazıları için sporun ruhuna aykırı bir davranış olarak değerlendirildi.</p>
                                        <p>Sonuç olarak Durant'ın hikayesi, basketbolda "başarı" tanımının kişiden kişiye ne kadar 
                    farklı algılanabileceğinin de bir kanıtı olarak spor tarihine geçti.</p>
                    <p>Bu hikaye, bugün hâlâ "bir yıldızın kariyer kararı ile sportmenlik anlayışı çatışabilir mi" 
                    tartışmasının en somut örneği olarak basketbol dünyasında konuşulmaya devam ediyor.</p>
                    """.trimIndent()
                ),
                "hamilton-ferrari-1-nisan-mi-bu" to Pair(
                    "'1 Nisan mı Bu?': Hamilton'ın Ferrari Şoku",
                    """
                    <p>Lewis Hamilton ve Mercedes'in ortaklığı, Formula 1 tarihinin en başarılı pilot-takım 
                    birlikteliklerinden biriydi. 2013'ten itibaren birlikte çalışan ikili, altı dünya şampiyonluğu 
                    kazanmış, F1'in modern çağının hâkim gücü haline gelmişti. Hamilton, kariyerinin büyük 
                    bölümünü bu takımda geçirmiş, Mercedes'in fabrika renklerini adeta kendi kimliğinin bir parçası 
                    hâline getirmişti. Bu nedenle 2023 sonunda kulüple imzaladığı yeni sözleşme, çoğu kişi 
                    tarafından kariyerinin geri kalanının da Mercedes'te geçeceğinin bir teyidi olarak 
                    yorumlanmıştı.</p>
                    <p>Şubat 2024'te Formula 1 dünyası, sporun tarihindeki en büyük sürpriz transferlerinden birine 
                    sahne oldu. Lewis Hamilton, Mercedes ile sadece birkaç ay önce yeni bir sözleşme imzalamıştı — 
                    kariyerinin tamamının Mercedes'te geçeceği düşünülüyordu, kulüple tam 11 yıllık bir ortaklığı 
                    vardı ve bu süre zarfında yedi kez dünya şampiyonu olmuştu. Bu rekor, F1 tarihinde Michael 
                    Schumacher ile paylaştığı, sporun en üst noktasını temsil eden bir başarıydı.</p>
                    <p>Ama sözleşmesindeki gizli bir madde, belirli koşullar altında erken ayrılma hakkı 
                    tanıyordu — ve Hamilton, kimse beklemezken bu maddeyi kullanmaya karar verdi. Haberin 
                    duyulduğu anda, Mercedes takımı içinde büyük bir şaşkınlık yaşandığı, hatta bazı ekip 
                    üyelerinin ilk başta bu haberin bir 1 Nisan şakası olabileceğini düşündüğü basına yansıdı — 
                    haber o kadar beklenmedik ve inanılmazdı ki, F1 camiasının büyük bölümü ilk tepkisinde 
                    şüpheyle yaklaştı.</p>
                    <p>Ferrari tarafında ise gelişmeler hızlı ilerledi. İtalyan takımı, o dönem mevcut pilotu 
                    Carlos Sainz ile sözleşme yenileme görüşmelerini sürdürüyordu ve resmi bir yenileme beklentisi 
                    vardı. Ancak Ferrari yönetimi, Hamilton'ın piyasada müsait hâle gelebileceğine dair sinyalleri 
                    aldığında stratejisini hızla değiştirdi ve İngiliz pilotu kadroya katmak için harekete geçti. 
                    Bu gelişme sonucunda Sainz'e veda edilme kararı alındı — İspanyol pilot, kariyerinin bu 
                    noktasında beklenmedik şekilde takım değiştirmek zorunda kaldı ve sonraki sezon için başka bir 
                    takıma yöneldi. Öte yandan 39 yaşındaki Hamilton, 2025 sezonundan itibaren F1'in en tarihi ve 
                    en çok takip edilen takımlarından biri olan Ferrari'nin kırmızılarını giymeye başladı.</p>
                    <p>Bu transfer, Formula 1'de sadece sportif değil, aynı zamanda ticari ve duygusal açıdan da 
                    büyük yankı uyandırdı — Hamilton'ın Ferrari'ye geçişi, markanın küresel hayran kitlesini 
                    genişletme potansiyeli açısından da geniş çapta tartışıldı. Aynı zamanda, kariyerinin sonlarına 
                    doğru bir pilotun hâlâ bu denli büyük bir kariyer riski alabilmesi, sporun içindeki 
                    beklenmedik dinamiklerin en güçlü kanıtlarından biri olarak görüldü.</p>
                                        <p>Bu transfer aynı zamanda, F1'de pilot sözleşmelerindeki gizli maddelerin ne kadar 
                    belirleyici olabileceğinin de çarpıcı bir örneğiydi. Sonraki dönemde birçok takım, benzer 
                    sürprizlerle karşılaşmamak için sözleşme şartlarını daha sıkı ve daha az esnek hale getirme 
                    yoluna gitti — bu da F1'de pilot transferlerinin gelecekte daha öngörülebilir bir sürece 
                    dönüşmesine katkıda bulundu.</p>
                                        <p>Bu transfer, F1'in ticari tarafında da büyük bir etki yarattı — Ferrari'nin sosyal medya 
                    takipçi sayısı ve marka görünürlüğü, Hamilton'ın katılım duyurusunun ardından belirgin şekilde 
                    arttı. Formula 1 yönetimi de bu transferi, sporun küresel popülerliğini artıran önemli 
                    pazarlama anları arasında değerlendirdi; birçok yeni takipçi, bu haber sayesinde F1'i takip 
                    etmeye başladığını belirtti.</p>
                                        <p>Bu transfer, aynı zamanda F1'de yaş sınırlarının ne kadar esnek olabileceğinin de bir 
                    kanıtıydı. Kariyerinin geç döneminde bile büyük bir risk alabilen Hamilton'ın hikayesi, 
                    sporcuların "doğru zaman" algısının kişiden kişiye ne kadar farklı olabileceğini gösterdi.</p>
                                        <p>Hamilton'ın Mercedes'teki on bir yılı, sporcu-takım ortaklıklarının en uzun ve en başarılı 
                    örneklerinden biriydi. Bu süre zarfında ikili, F1 tarihinde eşi benzeri görülmemiş bir 
                    başarı serisine imza atmıştı. Bu nedenle ayrılık kararı, sadece bir transfer değil, sporun 
                    bir döneminin sembolik olarak kapanması olarak da yorumlandı — birçok yorumcu bunu "bir 
                    çağın sonu" olarak nitelendirdi.</p>
                                        <p>Bu geçiş, F1 tarihinde efsanevi bir pilotun son büyük meydan okumasını simgeleyen bir 
                    an olarak hatırlanacak.</p>
                                        <p>Bu transfer haberinin yayılma hızı da dikkat çekiciydi — sosyal medyada dakikalar içinde 
                    milyonlarca kez paylaşılan haber, F1'in dijital çağda ne kadar geniş bir küresel kitleye 
                    ulaştığının da güçlü bir göstergesi oldu.</p>
                    <p>Bu transfer, "kariyerinin sonuna kadar aynı takımda kalacağı" düşünülen bir efsanenin bile, 
                    doğru fırsat karşısında köklü bir değişiklik yapabileceğinin en çarpıcı kanıtı oldu.</p>
                    """.trimIndent()
                ),
                "verstappen-bir-haftada-sampiyon-koltuguna" to Pair(
                    "Bir Haftada Yedek Pilottan Yarış Galibiyetine: Verstappen'in Şok Terfisi",
                    """
                    <p>Max Verstappen, henüz on yedi yaşındayken 2015'te Toro Rosso ile F1'e giriş yaparak, sporun 
                    tarihindeki en genç pilot unvanını almıştı. Red Bull'un genç pilot geliştirme programının en 
                    parlak ismi olarak görülen Verstappen, ilk sezonunda beklenenin üzerinde bir performans 
                    sergilemiş, deneyimli pilotlara karşı bile rekabetçi mücadeleler vermişti. Bu erken başarı, onu 
                    Red Bull'un uzun vadeli planlarının merkezine yerleştirmişti — ama kimse bu terfinin bu kadar 
                    erken ve bu kadar ani gerçekleşeceğini tahmin etmiyordu.</p>
                    <p>Mayıs 2016'da Formula 1'de, sporun tarihinde eşi benzeri az görülen bir karar alındı. 
                    Red Bull, o ana kadar küçük kardeş takımı Toro Rosso'da yarışan 18 yaşındaki Max Verstappen'i, 
                    ana takıma terfi ettirmeye karar verdi — mevcut pilotu Daniil Kvyat'ın yerine, üstelik sezonun 
                    ortasında. Bu tür ani ve sezon-içi pilot değişiklikleri F1'de son derece nadirdi; genelde 
                    kararlar sezon başında ya da sonunda alınırdı.</p>
                    <p>Bu karar, çoğu kişiye acımasız göründü — Kvyat, sadece birkaç hafta önce Çin Grand Prix'sinde 
                    podyuma çıkmış, iyi bir form yakalamış gibi görünüyordu. Ama Rusya Grand Prix'sinde art arda 
                    yaşadığı iki çarpışma, Red Bull yönetiminin güvenini sarstı ve kulüp kararını hızla verdi. 
                    Değişiklik, İspanya Grand Prix'sinden sadece dört gün önce resmi olarak açıklandı — Verstappen'in 
                    yeni takımıyla ilk yarışına, motoru ve aracı tanıması için son derece kısıtlı bir süresi 
                    kalmıştı.</p>
                    <p>Sonuç, kimsenin tahmin edemeyeceği kadar muhteşemdi: Verstappen, Red Bull'daki ilk 
                    yarışında — kariyerinin sadece 24. Grand Prix'sinde — birinci sırada bitirdi ve F1 tarihinin en 
                    genç yarış galibi unvanını kazandı. Bu zafer, üstelik yarış içinde yaşanan bir çarpışma 
                    zincirinden sonra takım arkadaşı ve rakiplerinin elenmesiyle ortaya çıkan bir fırsatı 
                    değerlendirerek geldi — Verstappen, önündeki iki güçlü pilotun yarış dışı kalmasının ardından 
                    liderliği ele geçirdi ve baskı altında soğukkanlılığını koruyarak yarışı tamamladı.</p>
                    <p>Bu sonuç, F1 camiasında büyük yankı uyandırdı ve genç pilotun sadece bir "gelecek vaadi" 
                    değil, o anda bile rekabetçi bir şampiyonluk adayı olduğunu kanıtladı. Red Bull'un riskli terfi 
                    kararı, bir haftadan kısa sürede tamamen haklı çıkmış oldu — kulüp yönetimi, bu kararın 
                    doğruluğunu kamuoyu önünde memnuniyetle dile getirdi.</p>
                                        <p>Bu olay aynı zamanda, F1 takımlarının genç yetenek geliştirme programlarının önemini de 
                    gözler önüne serdi. Red Bull'un yıllardır sürdürdüğü junior pilot sistemi, bu sayede sporun en 
                    başarılı yetenek yetiştirme modellerinden biri olarak kabul edilmeye başlandı — birçok rakip 
                    takım, sonraki yıllarda benzer genç pilot geliştirme yapıları kurmaya yöneldi.</p>
                                        <p>Bu olay, F1 tarihinde "hazır olmak" kavramının ne kadar önemli olduğunu da gösterdi — 
                    Verstappen, önceki sezonlardaki performansıyla zaten kendini kanıtlamış olmasaydı, bu ani 
                    fırsat ona bu kadar hızlı sunulmayabilirdi. Bu hikaye, genç pilotlara ilham kaynağı olmaya 
                    devam ediyor; birçok junior kategori pilotu, kendi kariyer planlamalarında Verstappen'in bu 
                    beklenmedik terfisini bir referans noktası olarak görüyor.</p>
                                        <p>Bu terfi kararı, F1 takımlarının risk yönetimi stratejilerinde de kalıcı bir iz bıraktı. 
                    Sonraki yıllarda birçok takım, genç pilotları ana kadroya taşırken benzer cesur adımlar 
                    atmaktan çekinmedi — bu da sporda genç yeteneklerin daha hızlı fırsat bulmasına katkıda 
                    bulundu.</p>
                                        <p>Bu terfi kararının arkasındaki hızlı karar alma süreci, Red Bull'un F1'deki genel yönetim 
                    felsefesinin de bir yansımasıydı — takım, uzun tartışmalar yerine cesur ve hızlı kararlar 
                    almasıyla biliniyordu. Bu yaklaşım, sonraki yıllarda da takımın pilot rotasyonu politikalarında 
                    sıkça karşımıza çıkan bir örüntü hâline geldi.</p>
                                        <p>Bu terfi, genç yeteneklere fırsat verildiğinde nelerin başarılabileceğinin akıllarda kalan 
                    bir kanıtı oldu.</p>
                                        <p>Bu olay, aynı zamanda F1'de "baskı altında performans" kavramının da en güçlü örneklerinden 
                    biri oldu — 18 yaşındaki bir pilotun, hazırlıksız bir şekilde girdiği bir yarışta bu denli 
                    soğukkanlı kalabilmesi, uzmanlar tarafından uzun süre incelenen bir konu oldu.</p>
                                        <p>Sonuç olarak bu terfi, F1 tarihinde "fırsat anında hazır olmanın" ne kadar belirleyici 
                    olabileceğinin en güçlü kanıtlarından biri olarak hatırlanıyor.</p>
                    <p>Bu zafer, Red Bull'un riskli kararını anında haklı çıkardı ve Verstappen'in, yıllar sonra 
                    dünya şampiyonluğuna uzanacak kariyerinin ilk büyük dönüm noktası oldu — Hollandalı pilot, 
                    sonraki yıllarda Red Bull ile birlikte F1'in en dominant isimlerinden biri hâline geldi.</p>
                    """.trimIndent()
                ),
                "schumacher-zayif-ferrariyi-secti" to Pair(
                    "Şampiyon Takımı Bırakıp Zayıf Ferrari'ye İmza Atan Pilot",
                    """
                    <p>1990'ların ilk yarısında Michael Schumacher, Benetton takımıyla F1'in en dominant genç 
                    pilotu hâline gelmişti. 1994 ve 1995'te üst üste iki dünya şampiyonluğu kazanan Alman pilot, 
                    hem hızı hem de yarış zekasıyla kendisini sporun geleceğinin en büyük ismi olarak konumlandırmış, 
                    Benetton'la birlikte güçlü bir kadro ve mühendislik ekibi kurmuştu. Bu başarı zinciri devam 
                    etseydi, çok daha fazla şampiyonluk kazanması olağan görülüyordu.</p>
                    <p>1996 yılında Michael Schumacher, F1 tarihinin en akılda kalıcı kararlarından birini aldı. 
                    Güçlü ve şampiyonluğa aday bir takım olan Benetton'dan ayrılıp, o dönem yıllardır şampiyonluk 
                    kazanamayan, sportif açıdan oldukça zayıf durumda olan Ferrari'ye transfer oldu. Ferrari, o 
                    dönemde son dünya şampiyonluğunu 1979'da kazanmış, aradan geçen on yedi yılda hem pilot hem 
                    de yapıcı şampiyonluğunda İtalyan ekibin ismi anılmaz olmuştu.</p>
                    <p>Karar, birçok kişiyi şaşırttı — neden şampiyon bir pilot, kazanan bir takımı bırakıp uzun 
                    süredir mücadele eden bir takıma gitsin ki? Bu, F1 tarihinde alışılmış transfer mantığının 
                    tam tersiydi; genelde pilotlar başarılı takımlardan daha başarılı takımlara geçerdi, tersi 
                    değil. Ama Schumacher'in vizyonu farklıydı: sadece yarışmak değil, tarihi bir markayı yeniden 
                    inşa etmek istiyordu. Ferrari yönetimi de ona bu vizyonu gerçekleştirmesi için geniş yetki ve 
                    kaynak sözü vermişti.</p>
                    <p>İlk sezonlarında sonuçlar beklenildiği gibi zorlu geçti — takım hâlâ rakiplerinin gerisindeydi, 
                    araç güvenilirlik sorunları yaşıyordu ve şampiyonluk mücadelesinden uzaktı. Bu dönemde 
                    Schumacher'in kararının yanlış olduğunu düşünenler de vardı; bazı yorumcular, en iyi 
                    yıllarını "batık bir gemi"yi kurtarmaya harcadığını yazdı.</p>
                    <p>Ama Schumacher sabırla çalışmaya devam etti ve Ferrari'ye katılmasının hemen ardından, 
                    Benetton'daki teknik ekibinden Ross Brawn (yarış stratejisti) ve Rory Byrne (araç tasarımcısı) 
                    gibi isimleri de kendisiyle birlikte Ferrari'ye getirilmesini sağladı. Bu üçlünün oluşturduğu 
                    güçlü ortaklık, kulübün teknik altyapısını sistemli bir şekilde yeniden inşa etti. Yıllar süren 
                    bu inşa süreci sonunda büyük bir meyve verdi: 2000-2004 yılları arasında Ferrari ile üst üste 
                    beş dünya şampiyonluğu kazanarak, takımı F1 tarihinin en başarılı ve en dominant dönemlerinden 
                    birine taşıdı. Bu dönemde Ferrari, hem pilot hem yapıcı şampiyonluklarında adeta rakipsiz hale 
                    geldi.</p>
                                        <p>Bu dönüşüm aynı zamanda, F1 tarihinde bir pilotun sadece sürüş yeteneğiyle değil, aynı 
                    zamanda bir organizasyonu yönetme ve doğru insanları bir araya getirme becerisiyle de fark 
                    yaratabileceğinin kanıtı oldu. Schumacher'in Ferrari'deki liderlik rolü, sonraki nesil 
                    pilotlar için de bir örnek teşkil etti — artık büyük pilotlardan sadece hızlı olmaları değil, 
                    takımı da bir arada tutmaları bekleniyordu.</p>
                                        <p>Bu dönüşüm hikayesi, bugün spor yönetimi okullarında bile "uzun vadeli vizyon" örneği olarak 
                    incelenmeye devam ediyor. Schumacher'in Ferrari'yi yeniden inşa etme süreci, sadece bir 
                    sporcunun değil, bir organizasyonun nasıl sistemli bir şekilde dönüştürülebileceğinin de 
                    örneği olarak spor yönetimi literatüründe sıkça referans gösteriliyor.</p>
                                        <p>Bu hikaye, aynı zamanda sporcu sadakati kavramının nasıl çift yönlü işleyebileceğinin de bir 
                    örneğiydi — Schumacher, sadece Ferrari'ye değil, kendi teknik ekibine de sadık kalarak, 
                    bu ekiple birlikte yıllar süren bir başarı hikayesi yazdı.</p>
                                        <p>Schumacher'in Ferrari dönemi, aynı zamanda F1'de "takım kültürü" kavramının önemini de ortaya 
                    koydu. Alman pilotun getirdiği disiplin ve profesyonellik anlayışı, sadece teknik ekibi değil, 
                    kulübün genel çalışma kültürünü de dönüştürdü — bu miras, Schumacher ayrıldıktan sonra bile 
                    Ferrari'nin yapısında uzun süre etkisini sürdürdü.</p>
                                        <p>Bu hikaye, sabır ve vizyonun spor tarihinde nasıl büyük dönüşümlere yol açabileceğini 
                    gösteren en iyi örneklerden biri.</p>
                                        <p>Bu dönüşüm süreci, İtalyan otomotiv endüstrisi için de büyük bir gurur kaynağı oldu — 
                    Ferrari'nin yeniden zirveye taşınması, sadece spor değil, ulusal bir başarı hikayesi olarak 
                    da kutlandı.</p>
                                        <p>Sonuç olarak bu dönüşüm hikayesi, sporda sabrın ve uzun vadeli planlamanın ne kadar 
                    değerli olabileceğinin en iyi örneklerinden biri olarak öğretiliyor.</p>
                                        <p>Bu miras, günümüz F1 pilotlarına da ilham vermeye devam ediyor.</p>
                                        <p>Bu vizyon, tarihe altın harflerle yazıldı.</p>
                                        <p>Bu miras, bugün hâlâ konuşulmaya devam ediyor.</p>
                    <p>Bu hikaye, bazen "geriye gitmiş" görünen bir transferin, aslında uzun vadeli bir vizyonun 
                    parçası olabileceğini gösteren, F1 tarihinin en öğretici örneklerinden biri olarak hâlâ 
                    anlatılıyor.</p>
                    """.trimIndent()
                ),
                "vettel-alonsonun-ayrildigi-koltuga-oturdu" to Pair(
                    "Alonso'nun Terk Ettiği Koltuğa Oturan Şampiyon: Vettel'in Ferrari Dönemi",
                    """
                    <p>Fernando Alonso, 2010'da Ferrari'ye katıldığında, İtalyan takımı yıllardır aradığı 
                    şampiyonluk şansını yeniden yakalamak için büyük umutlar besliyordu. İspanyol pilot, kariyeri 
                    boyunca gösterdiği agresif yarış tarzı ve teknik geri bildirim yeteneğiyle biliniyordu; Ferrari 
                    ile birlikte beş sezon boyunca şampiyonluk mücadelesi verdi, birkaç kez son yarışlara kadar 
                    şampiyonluk yarışında kaldı ama hiçbirini tamamlayamadı. Bu yakın kaçırışlar, hem pilot hem de 
                    takım için giderek artan bir hayal kırıklığına dönüştü.</p>
                    <p>2014 sezonu sonunda Fernando Alonso, yıllarca şampiyonluk için mücadele ettiği ama bir türlü 
                    kazanamadığı Ferrari'den ayrılma kararı aldı — hayal kırıklığı doluydu, takımın o dönemki 
                    rekabetçi olamamasından bıkmıştı. Bu ayrılık, F1 dünyasında büyük yankı uyandırdı; İspanyol 
                    pilot, kariyerinin en parlak yıllarını kırmızılarla mücadele ederek geçirmişti ve bu ayrılık, 
                    kulübün son yıllardaki en büyük pilot değişikliklerinden biri olarak görüldü.</p>
                    <p>Tam bu sırada, dört kez üst üste dünya şampiyonu olmuş Sebastian Vettel, Red Bull'daki 
                    başarılı döneminin ardından sürpriz bir şekilde Ferrari'ye transfer oldu — adeta Alonso'nun 
                    bıraktığı koltuğa oturdu. Vettel, Red Bull ile 2010-2013 arasında dört kez üst üste dünya 
                    şampiyonu olmuş, sporun o dönemki en dominant ismi hâline gelmişti. Ferrari'ye geçişi, Alman 
                    pilot için kariyerinin bir sonraki büyük meydan okuması olarak görülüyordu — Vettel için bu, 
                    aynı zamanda çocukluk hayaliydi; Ferrari'de yarışmak, kendisi de belirttiği gibi birçok pilotun 
                    içinde taşıdığı derin bir arzuydu, çünkü marka F1'in en tarihi ve en sembolik takımıydı.</p>
                    <p>İlk sezonunda Vettel, Ferrari'ye 2008'den beri ilk kez bir Grand Prix galibiyeti yaşattı ve 
                    şampiyonada üçüncü sırada bitirdi — bu sonuç, takımın yeniden rekabetçi olabileceğinin güçlü 
                    bir sinyaliydi ve Ferrari taraftarları arasında büyük bir umut yarattı. Sonraki sezonlarda 
                    Vettel, birkaç kez şampiyonluk mücadelesinin merkezinde yer aldı; özellikle 2017 ve 2018 
                    sezonlarında uzun süre şampiyonluk yarışının içinde kaldı, ama kritik anlarda yaşanan teknik 
                    sorunlar ve pilot hataları, şampiyonluğu elinden kaçırmasına neden oldu.</p>
                    <p>Ne yazık ki beklenen dünya şampiyonluğu bir türlü gelmedi — Vettel, Ferrari'de geçirdiği altı 
                    sezon boyunca birkaç kez şampiyonluğa çok yaklaşsa da bir türlü kazanamadı. 2020 sonunda takımdan 
                    ayrılan Vettel, kariyerinin geri kalanını Aston Martin'de sürdürdü, ama Ferrari'deki dönemi hem 
                    kendisi hem de kulüp için "neredeyse başarılmış ama bir türlü tamamlanamamış" bir dönem olarak 
                    hatırlanıyor.</p>
                                        <p>Bu dönem aynı zamanda, F1'de takım içi teknik gelişmelerin şampiyonluk şansını ne kadar 
                    doğrudan etkileyebileceğinin de bir göstergesiydi. Vettel'in Ferrari'deki altı sezonu boyunca, 
                    rakip takımların (özellikle Mercedes'in) teknik üstünlüğü, İtalyan ekibin şampiyonluğa 
                    ulaşmasının önündeki en büyük engel olarak kaldı — bu da sporun sadece pilot yeteneğine değil, 
                    mühendislik gücüne de ne kadar bağlı olduğunu gösterdi.</p>
                                        <p>Vettel'in Ferrari serüveni, aynı zamanda F1'de "doğru zamanda doğru takımda olmanın" 
                    şampiyonluk için tek başına yeterli olmadığının da bir kanıtı oldu. Dört kez şampiyon olmuş 
                    bir pilotun bile, takımın teknik gücü yeterli olmadan başarıya ulaşamayacağı gerçeği, bu 
                    dönem boyunca defalarca gözler önüne serildi.</p>
                                        <p>Bu dönem, aynı zamanda F1 taraftarlarının bir pilotun performansını değerlendirirken, sadece 
                    yarış sonuçlarına değil, aynı zamanda takımın genel rekabetçiliğine de dikkat etmesi 
                    gerektiğinin bir hatırlatıcısı oldu.</p>
                                        <p>Vettel'in Red Bull'daki dört şampiyonluk sezonu, F1 tarihinin en dominant dönemlerinden biri 
                    olarak kabul ediliyordu. Bu üstünlüğün ardından Ferrari'ye geçişi, birçok kişi tarafından 
                    "kariyerinin ikinci büyük fetih hikayesi" olarak bekleniyordu — ama F1'in değişken doğası, 
                    bu beklentinin gerçekleşmesini engelledi.</p>
                                        <p>Bu dönem, F1'de bireysel yeteneğin, takım gücü olmadan tek başına yeterli olmadığını bir 
                    kez daha kanıtladı.</p>
                                        <p>Vettel'in bu dönemi, aynı zamanda genç yaşta çok başarı elde eden sporcuların, kariyerlerinin 
                    ilerleyen döneminde yeni zorluklarla nasıl baş ettiğinin de ilgi çekici bir örneği oldu.</p>
                                        <p>Sonuç olarak Vettel'in bu dönemi, F1'de bireysel yeteneğin takım gücüyle birleşmediğinde 
                    ne kadar sınırlı kalabileceğinin çarpıcı bir hatırlatıcısı oldu.</p>
                                        <p>Bu dönem, F1 severlerin hafızasında hâlâ canlı bir şekilde yer ediyor.</p>
                                        <p>Bu mücadele, F1 tarihine kazındı.</p>
                    <p>Bu hikaye, bir pilotun çocukluk hayalini gerçekleştirmesinin bile, garantili bir başarı 
                    anlamına gelmediğinin çarpıcı bir örneği olarak F1 tarihinde yerini aldı.</p>
                    """.trimIndent()
                ),
                "lebron-karari-canli-yayinda-acikladi" to Pair(
                    "'Yeteneklerimi Miami'ye Taşıyorum': Canlı Yayında Açıklanan Karar",
                    """
                    <p>LeBron James, 2003'te Cleveland Cavaliers tarafından draft edildiğinde, kendi memleketi 
                    Ohio'nun yıldızı olarak büyük bir beklentiyle karşılanmıştı. Yedi sezon boyunca kulübün tek 
                    başına omuzlarında taşıdığı bir takım hâline geldi; iki kez lig MVP'si seçildi, Cavaliers'ı 
                    2007'de ilk kez NBA Finali'ne taşıdı. Ama kadronun etrafındaki oyuncu kalitesi, şampiyonluk 
                    mücadelesi vermek için yeterli değildi ve James, kariyerinin bu noktasında büyük bir karar 
                    vermek zorunda kaldı: kendi şehrinde kalıp mücadele etmeye devam mı edecekti, yoksa başka bir 
                    yerde şampiyonluk şansını mı arayacaktı?</p>
                    <p>2010 yazında LeBron James, NBA tarihinin en tartışmalı ve en özgün transfer duyurusuna imza 
                    attı. Cleveland Cavaliers'te geçirdiği yedi sezonun ardından serbest kalan James, kararını 
                    açıklamak için sıradan bir basın toplantısı yerine, ESPN'de canlı yayınlanan özel bir televizyon 
                    programı düzenledi — adı basitçe "The Decision" (Karar) olacaktı. Bu format, o güne kadar hiçbir 
                    sporcunun bir kariyer kararını duyurmak için kullanmadığı, benzeri görülmemiş bir yaklaşımdı.</p>
                    <p>Milyonlarca izleyicinin ekran başında beklediği o an geldiğinde James, tarihe geçecek 
                    cümleyi kurdu ve yeteneklerini Miami'ye taşıyacağını açıkladı. Bu sözler, Cleveland 
                    taraftarlarını derinden yaraladı — kulübün sahibi, James'e açık bir mektup yazarak sert bir 
                    tepki verdi; bu mektup, oyuncuyu doğrudan eleştiren, kamuoyu önünde yapılan nadir görülen bir 
                    sahip-oyuncu çatışması örneği olarak basketbol tarihine geçti. Cleveland sokaklarında James 
                    formaları toplu şekilde yakıldı, kulübün kendisi de James'in formasını asılı tuttuğu 
                    duvarlardan kaldırdı.</p>
                    <p>Miami'de Dwyane Wade ve Chris Bosh ile bir araya gelen James, basın tarafından "Big Three" 
                    olarak anılan bu kadroyla dört yıl üst üste NBA Finali'ne çıktı ve iki şampiyonluk kazandı. 
                    Bu süreçte James, bireysel olarak da kariyerinin en verimli döneminden birini yaşadı; MVP 
                    ödülleri kazandı, finallerde en değerli oyuncu seçildi. Ama "Decision" programı, sportif 
                    başarısından bağımsız olarak, bir oyuncunun takım değiştirme kararını nasıl "aşırı gösterişli" 
                    bir şekilde sunabileceğinin sembolü haline geldi ve yıllarca eleştiri konusu oldu.</p>
                                        <p>Bu olay aynı zamanda, sosyal medyanın henüz bugünkü kadar yaygın olmadığı bir dönemde, 
                    geleneksel medyanın büyük spor haberlerini nasıl şekillendirebileceğinin de bir örneğiydi. 
                    "The Decision" programı, spor pazarlamacılığı açısından da uzun süre incelenen bir vaka 
                    olarak kaldı — hem olumlu hem olumsuz yönleriyle, sporcu markalaşmasının sınırlarını zorlayan 
                    bir deneyim olarak spor tarihi kitaplarına geçti.</p>
                                        <p>James'in kariyeri boyunca aldığı bu tür büyük kararlar, onu basketbolun sadece sahadaki değil, 
                    aynı zamanda iş dünyası ve medya alanındaki en etkili isimlerinden biri hâline getirdi. "The 
                    Decision" programı, günümüzde spor pazarlama derslerinde hem yapılması hem de yapılmaması 
                    gerekenler açısından incelenen bir vaka çalışması olarak öğretiliyor.</p>
                                        <p>Bu olay, aynı zamanda sporcuların kendi anlatılarını kontrol etme isteğinin de bir 
                    yansımasıydı — James, kararını geleneksel medya filtresinden geçirmeden, doğrudan kendi 
                    formatında sunmayı tercih etmişti; bu yaklaşım, sonraki yıllarda birçok sporcunun kendi 
                    medya kanallarını kurmasına ilham verdi.</p>
                                        <p>James'in bu kararının ardından yaşanan tartışmalar, NBA'in medya ile ilişkisini de kalıcı 
                    olarak değiştirdi. Lig yönetimi ve takımlar, sonraki yıllarda büyük oyuncu duyurularının nasıl 
                    yönetileceği konusunda çok daha dikkatli protokoller geliştirmeye başladı — bu da "The Decision" 
                    olayının basketbol endüstrisi üzerindeki kalıcı etkilerinden biri oldu.</p>
                                        <p>Bu olay, spor medyası tarihinde bugün hâlâ referans gösterilen bir dönüm noktası olarak 
                    kalmaya devam ediyor.</p>
                                        <p>Bu olay, sporcu markalaşması kavramının akademik çalışmalarda da sıkça referans gösterildiği 
                    bir dönüm noktası oldu — James'in bu deneyimden çıkardığı dersler, kariyerinin geri kalanında 
                    medya stratejisini şekillendirmesinde belirleyici oldu.</p>
                                        <p>Sonuç olarak bu olay, sporcu-medya ilişkisinin evrimi açısından bugün hâlâ ders 
                    çıkarılan, kalıcı bir dönüm noktası olarak spor tarihine kazındı.</p>
                    <p>James, yıllar sonra bu duyuru şeklinin bir hata olduğunu kabul etti ve 2014'te Cleveland'a 
                    geri dönerken, kararını çok daha sade bir şekilde, yazılı bir açıklamayla duyurdu — bu geri 
                    dönüş, 2016'da kulübe tarihi ilk NBA şampiyonluğunu kazandırmasıyla sonuçlandı ve James'i 
                    Cleveland tarihinin en efsanevi ismi hâline getirdi. Ama "The Decision" olayı, basketbol 
                    tarihinde "star oyuncu transferi" kavramını, medya ve kamuoyu ilişkileri açısından sonsuza 
                    dek değiştirdi — o günden sonra büyük yıldızların serbest kalma süreçleri, çok daha büyük bir 
                    medya olayına dönüştü.</p>
                    """.trimIndent()
                ),
                "carmelo-anthony-knicksi-zorla-kazandi" to Pair(
                    "Gitmek İstediği Takımı Zorla Elde Eden Yıldız: Carmelo Anthony",
                    """
                    <p>Carmelo Anthony, 2003'te Denver Nuggets tarafından draft edildikten sonra, kulübün 
                    tarihindeki en önemli oyunculardan biri hâline gelmişti. Yedi buçuk sezon boyunca Nuggets'ı 
                    her yıl play-off'lara taşıdı, 2009'da takımı konferans finaline kadar götürdü. Ama sözleşmesinin 
                    sona ermesine yaklaşırken, kariyerinin geri kalanını nerede geçireceğine dair düşünceleri 
                    netleşmeye başlamıştı — New York'ta doğmuş ve büyümüş olan Anthony, kariyerinin bir noktasında 
                    kendi köklerine yakın, büyük bir pazarda oynamak istediğini hissediyordu.</p>
                    <p>2011 yılı başında Carmelo Anthony, Denver Nuggets'teki geleceğinin belirsiz olduğunu 
                    açıkça belirtti — sözleşmesi bitmek üzereydi ve New York Knicks'e gitmek istediğini kamuoyuna 
                    duyurdu. Bu, basketbolda nadir görülen bir stratejiydi: bir oyuncu, sadece "istediği" için bir 
                    kulübü, kendisini istemediği bir takasa fiilen zorluyordu — çünkü Anthony, sezon sonunda serbest 
                    kalacak olsa bile, Nuggets bonservis bedeli almadan onu kaybetmek istemiyordu, bu da kulübü 
                    Anthony'nin istediği takasa razı olmaya iten bir baskı unsuru yarattı.</p>
                    <p>Nuggets yönetimi başta direndi, farklı takımlarla alternatif görüşmeler yürütmeye çalıştı ve 
                    Anthony'yi başka bir yöne yönlendirmeyi denedi. Ama Anthony'nin kararlılığı ve New York'a gitme 
                    konusundaki ısrarı, aylar süren müzakerelerin ardından sonunda kulübü zorladı. Şubat 2011'de dev 
                    bir çoklu takas gerçekleşti — Knicks, Anthony'yi elde etmek için kadrosunun büyük bir kısmını, 
                    aralarında Danilo Gallinari ve Wilson Chandler'ın da bulunduğu birçok oyuncuyu ve gelecek draft 
                    haklarını Denver'a gönderdi. Bu, o döneme kadar NBA tarihinde görülen en büyük ve en karmaşık 
                    takaslardan biri olarak kayıtlara geçti.</p>
                    <p>Bu transfer, NBA'de "oyuncu gücü" kavramının ne kadar etkili olabileceğinin erken ve önemli 
                    bir örneğiydi — yıldız oyuncular, artık sadece kulüplerin kararlarına bağlı kalmıyor, kendi 
                    gelecekleri üzerinde doğrudan söz sahibi olabiliyordu. Bu olay, sonraki yıllarda başka yıldız 
                    oyuncuların da benzer stratejiler izlemesine ilham kaynağı oldu; birçok analist, Anthony'nin bu 
                    hamlesinin, modern NBA'de oyuncuların kulüpler üzerindeki etkisini artıran bir dönüm noktası 
                    olduğunu savundu.</p>
                                        <p>Bu olay aynı zamanda, NBA yönetiminin gelecekte oyuncu-talepli takasları nasıl 
                    düzenleyeceği konusunda da tartışmalara yol açtı. Sonraki yıllarda lig, bu tür durumların 
                    kulüpler arasında adil bir denge içinde yönetilmesi için çeşitli kurallar ve yönergeler 
                    geliştirdi — Anthony'nin hikayesi, bu düzenlemelerin arka planında sıkça referans gösterilen 
                    örneklerden biri oldu.</p>
                                        <p>Anthony'nin bu hamlesi, kendisinden sonra gelen birçok NBA yıldızının kariyer planlamasını da 
                    etkiledi. Oyuncuların kendi gelecekleri üzerinde daha fazla söz sahibi olma isteği, 2010'lu 
                    yılların ikinci yarısında NBA'de giderek daha sık görülen bir eğilim hâline geldi — bu 
                    değişimin kök nedenlerinden biri olarak Anthony'nin bu erken örneği sıkça anılıyor.</p>
                                        <p>Bu takas süreci, aynı zamanda NBA'de spor menajerliğinin ne kadar stratejik bir rol 
                    oynadığının da bir kanıtıydı. Anthony'nin ekibi, oyuncunun tercihini kamuoyuna etkili bir 
                    şekilde ileterek, kulüpler arası dengeyi kendi lehlerine çevirmeyi başardı.</p>
                                        <p>Bu takas, aynı zamanda New York Knicks'in uzun yıllar süren "büyük pazar, büyük yıldız" 
                    stratejisinin de bir parçasıydı. Kulüp, tarihsel olarak New York'un medya gücünü kullanarak 
                    yıldız oyuncuları çekmeye çalışmış, Anthony transferi de bu stratejinin en somut 
                    örneklerinden biri olarak değerlendirilmişti.</p>
                                        <p>Bu hikaye, NBA'de oyuncu iradesinin kulüp kararlarını nasıl şekillendirebileceğinin erken 
                    bir kanıtı oldu.</p>
                                        <p>Bu takas, aynı zamanda NBA'de takas müzakerelerinin ne kadar karmaşık ve çok boyutlu 
                    olabileceğinin de bir göstergesiydi — birden fazla oyuncu, draft hakkı ve gelecekteki 
                    seçenekleri içeren bu tür anlaşmalar, kulüp yöneticileri için gerçek bir strateji sınavı 
                    oluşturuyor.</p>
                                        <p>Sonuç olarak Anthony'nin bu hikayesi, oyuncu gücünün NBA'de nasıl giderek daha etkili 
                    hâle geldiğinin erken ve önemli bir kanıtı olarak akıllarda kaldı.</p>
                                        <p>Bu hikaye, NBA tarihinin en çok tartışılan takaslarından biri olmaya devam ediyor.</p>
                    <p>Anthony, Knicks'te geçirdiği yaklaşık yedi sezon boyunca takımın kesintisiz yıldızı oldu, 
                    kulübün en çok sayı üreten oyuncularından biri hâline geldi ve bir kez konferansın en skorer 
                    ismi seçildi. Ama beklenen şampiyonluk bir türlü gelmedi — Knicks, o dönemde derin bir play-off 
                    koşusu yapamadı ve kulüp uzun süre rekabetçi bir kadro kuramadı. Yine de bu hikaye, modern 
                    NBA'de oyuncu-kulüp ilişkisinin nasıl değiştiğinin önemli bir dönüm noktası olarak basketbol 
                    tarihine geçti.</p>
                    """.trimIndent()
                ),
                "toprak-kawasakiden-motogpye-yolculuk" to Pair(
                    "Kawasaki'den MotoGP'ye: Toprak Razgatlıoğlu'nun İnanılmaz Yolculuğu",
                    """
                    <p>Toprak Razgatlıoğlu, motosiklet sporuna Türkiye'nin ulusal şampiyonalarında başlamış, genç 
                    yaşta Avrupa'nın alt kategori yarışlarına adım atmıştı. Agresif ama kontrollü sürüş tarzı, 
                    özellikle viraj girişlerindeki fren noktalarını geciktirme yeteneğiyle dikkat çekiyordu — bu 
                    stil, onu kısa sürede uluslararası takımların radarına soktu. 2018'de Toprak, Dünya Superbike 
                    Şampiyonası'na (WSBK) Kawasaki'nin uydu takımı Puccetti Racing ile giriş yaptığında, kimse onun 
                    birkaç yıl içinde dört farklı üreticiyle yarışıp iki dünya şampiyonluğu kazanacağını tahmin 
                    edemezdi. Bu, motosiklet sporunda oldukça nadir görülen bir kariyer profiliydi; çoğu pilot bir 
                    ya da iki markayla kariyerinin büyük bölümünü geçirirken, Toprak'ın hikayesi tam tersi bir yön 
                    izleyecekti.</p>
                    <p>2019'da, Suzuka 8 Saat dayanıklılık yarışında fabrika Kawasaki pilotları Jonathan Rea ve 
                    Leon Haslam'la aynı motosikleti paylaşmasına izin verilmeyince, Toprak'ın Kawasaki'den yolu 
                    ayrılmaya başladı. Bu küçük ama sembolik gelişme, genç pilotun kulüp içindeki konumuna dair 
                    belirsizliği ortaya koydu ve kariyerinin bir sonraki adımını farklı bir yerde aramasına zemin 
                    hazırladı.</p>
                    <p>2020'de fabrika Yamaha pilotu olan Toprak, 2021'de tarih yazdı — Jonathan Rea'nın altı yıllık 
                    şampiyonluk hakimiyetini kırıp, WSBK'de şampiyon olan ilk Türk pilot oldu. Bu başarı, Türk 
                    motorsporları için tarihi bir dönüm noktasıydı; Toprak, bu şampiyonlukla birlikte Türkiye'de 
                    milyonlarca yeni motosiklet sporu takipçisi kazandı ve ülkesinde adeta bir spor kahramanına 
                    dönüştü. Ama 2023 sezonunda, Ducati'nin gerisinde kalınca beklenmedik bir karar aldı: 
                    şampiyonluk kazandığı Yamaha'dan ayrılıp, o dönem WSBK'de neredeyse son sırada olan BMW'ye 
                    transfer oldu. MotoGP'ye geçiş için Yamaha ile test bile yapmıştı, ama sonuçlar karışıktı — 
                    kariyerinde yeni bir meydan okumaya ihtiyacı olduğunu hissederek riskli bir bahse girdi.</p>
                    <p>Bu karar, o dönem birçok uzman tarafından şaşkınlıkla karşılandı — şampiyon olduğu bir 
                    markadan ayrılıp, rekabetçilikten uzak bir markaya geçmek, sportif açıdan mantıksız görünüyordu. 
                    Ama Toprak, bahis fazlasıyla karşılığını aldı: BMW'deki daha dördüncü yarışında kazandı ve 
                    2024'te markanın 100 yılı aşkın tarihinde tek motosikletli dünya şampiyonasındaki ilk 
                    şampiyonluğunu getirdi. Bu, BMW'nin motosiklet bölümü için tarihi bir andı ve Toprak'ı markanın 
                    tarihine adını yazdıran isim hâline getirdi.</p>
                    <p>2025'te ikinci BMW şampiyonluğunu da kazandıktan sonra, kariyerinin belki de en büyük adımını 
                    attı: 2026 sezonunda, Prima Pramac Yamaha takımıyla MotoGP'ye — motosiklet sporunun en üst 
                    kademesine — çıkacağını duyurdu. WSBK'de art arda şampiyonluklar kazanmış bir pilotun MotoGP'ye 
                    geçişi, sporun tarihinde nadiren görülen ve genelde büyük risk taşıyan bir adımdır, çünkü iki 
                    kategori arasındaki teknik ve rekabet farkı oldukça büyüktür.</p>
                                        <p>Toprak'ın hikayesi, Türkiye'de motorsporlarına olan ilgiyi de büyük ölçüde artırdı. Onun 
                    başarıları sayesinde, birçok genç sporcu motosiklet yarışçılığına yönelmeye başladı ve 
                    Türkiye'de bu alanda yeni yetenek geliştirme programları kurulması için de bir ivme 
                    oluşturdu. MotoGP'deki performansı, hem kendisi hem de Türk motorsporları için yeni bir 
                    dönemin başlangıcı olarak görülüyor.</p>
                                        <p>Bu hikaye, aynı zamanda sporcuların marka sadakati yerine kişisel gelişimi önceliklendirmesi 
                    gerektiğinde neler başarılabileceğinin de bir kanıtı. Toprak'ın her yeni markada gösterdiği 
                    hızlı adaptasyon yeteneği, motosiklet sporunda nadiren görülen bir esneklik örneği olarak 
                    değerlendiriliyor.</p>
                                        <p>Toprak'ın kariyeri boyunca gösterdiği bu marka değiştirme cesareti, motosiklet sporunda 
                    "konfor bölgesinden çıkma" kavramının en somut örneklerinden biri olarak spor otoriteleri 
                    tarafından sıkça anılıyor. Her yeni markada kısa sürede rekabetçi hâle gelme yeteneği, onu 
                    akranlarından ayıran en belirgin özelliklerden biri olarak kabul ediliyor.</p>
                                        <p>Toprak'ın hikayesi, Türk sporunun dünya sahnesinde nasıl güçlü bir yer edinebileceğinin 
                    ilham verici bir örneği.</p>
                                        <p>Toprak'ın markalar arası bu geçişleri, aynı zamanda motosiklet üreticilerinin pilot 
                    transferlerinde nasıl rekabet ettiğinin de ilginç bir örneği oldu — her marka, onun 
                    yeteneğinden faydalanmak için önemli kaynaklar ayırmaya istekliydi.</p>
                                        <p>Sonuç olarak Toprak'ın bu yolculuğu, sporcuların cesaretle attığı adımların nasıl 
                    tarihi başarılara dönüşebileceğinin ilham verici bir örneği olarak hatırlanacak.</p>
                                        <p>Bu yolculuk, Türk spor tarihinin en gurur verici sayfalarından biri olarak kalacak.</p>
                    <p>Kawasaki'den Yamaha'ya, oradan BMW'ye, ve şimdi tekrar Yamaha ile MotoGP'ye — Toprak'ın 
                    kariyeri, bir sporcunun konfor alanından defalarca çıkıp her seferinde daha büyük bir meydan 
                    okumayı seçmesinin nadir görülen bir örneği.</p>
                    """.trimIndent()
                ),
                "rossi-honda-yamaha-riskli-kumar" to Pair(
                    "Şampiyon Motosikleti Bırakıp Kaybeden Markaya İmza Atan Pilot: Rossi'nin Yamaha Kumarı",
                    """
                    <p>Valentino Rossi, 2000'de Honda'nın fabrika takımına katıldığında, İtalyan pilot zaten alt 
                    kategorilerde şampiyonluklar kazanmış, büyük bir yetenek olarak görülüyordu. Honda ile birlikte 
                    geçirdiği dört sezon boyunca Rossi, sporun o dönemki en teknik ve en güvenilir motosikletiyle 
                    adeta rakipsiz hale geldi; üst üste şampiyonluklar kazanarak, MotoGP'nin en baskın ismi olarak 
                    kabul edildi. Bu başarı zinciri, çoğu kişiye göre Rossi'nin kariyerinin geri kalanını da Honda 
                    ile geçireceğinin bir garantisi gibi görünüyordu.</p>
                    <p>2003 sonunda Valentino Rossi, Honda ile üst üste şampiyonluklar kazanmış, sporun en baskın 
                    motosikletinde oturan üç kez dünya şampiyonuydu. Tam bu noktada, kariyerinin en akıl almaz 
                    kararını aldı: 1992'den beri hiç şampiyonluk kazanamamış Yamaha'ya transfer olmaya karar verdi. 
                    Bu karar, sadece sportif değil, aynı zamanda kişisel bir meydan okumaydı — Rossi, başarısının 
                    ne kadarının kendi yeteneğinden, ne kadarının motosikletin üstünlüğünden kaynaklandığını 
                    kanıtlamak istiyordu. Honda, bu karara o kadar kızmıştı ki, Rossi'yi sözleşmesinin resmi bitiş 
                    tarihine kadar bağlı tuttu — yeni sezona kadar Yamaha'sını test etmesine bile izin vermedi, bu 
                    da Rossi'nin yeni motosikletiyle hazırlık süresini önemli ölçüde kısalttı.</p>
                    <p>Yamaha'daki ekibiyle ilk kez karşılaştığında motosikletin rakiplerine göre ne kadar geride 
                    olduğunu gören Rossi, yine de pes etmedi — efsanevi şef mekanisyeni Jeremy Burgess'i de 
                    beraberinde Yamaha'ya getirerek, motosikleti aylar içinde neredeyse baştan tasarladılar. Bu 
                    süreçte teknik ekip, motorun güç aktarımından şasi sertliğine kadar birçok unsuru Rossi'nin 
                    sürüş tarzına göre yeniden şekillendirdi. 2004 sezonunun ilk yarışında, Güney Afrika'da, eski 
                    Honda'lı rakibi Max Biaggi'ye karşı çekişmeli bir mücadele vererek kazandı — bu, MotoGP 
                    tarihinde bir pilotun bir markayla sezonu şampiyon bitirip, hemen ertesi sezon farklı bir 
                    markayla ilk yarışını da kazandığı tek örnekti ve sporun tarihine geçen bir başarı oldu.</p>
                    <p>Rossi, o sezonu şampiyon olarak bitirdi — Honda'daki dönemine yakın sayıda yarış kazanarak, 
                    herkesi "asıl yetenek pilotta mı motosiklette mi" sorusuna net bir cevap vermeye zorladı. Bu 
                    sonuç, motosiklet sporunda pilotun katkısının makine kalitesinden bağımsız olarak ne kadar 
                    belirleyici olabileceğinin en güçlü kanıtlarından biri hâline geldi. Yamaha ile başlayan bu 
                    ortaklık, iki ayrı döneme (2004-2010 ve 2013-2021) yayılan toplam 16 sezon sürdü ve dört dünya 
                    şampiyonluğu daha getirdi — Rossi, bu süre zarfında Yamaha'yı MotoGP'nin en rekabetçi 
                    markalarından biri hâline getirmeye yardımcı oldu.</p>
                                        <p>Bu geçiş aynı zamanda, motosiklet mühendisliğinde pilot geri bildiriminin ne kadar 
                    belirleyici olabileceğinin de güçlü bir kanıtıydı. Rossi'nin teknik ekibiyle kurduğu yakın 
                    işbirliği, sonraki yıllarda diğer üreticilerin de pilot-mühendis ilişkisine çok daha fazla 
                    önem vermesine yol açtı — bugün MotoGP'de pilotların motosiklet geliştirme sürecindeki rolü, 
                    büyük ölçüde bu döneme dayanan bir anlayışla şekilleniyor.</p>
                                        <p>Rossi'nin bu kariyer kararı, sonraki yıllarda "GOAT" (tüm zamanların en iyisi) tartışmalarında 
                    da sıkça referans gösterildi — farklı markalarla şampiyonluk kazanabilme yeteneği, onu diğer 
                    büyük pilotlardan ayıran en önemli özelliklerden biri olarak kabul ediliyor. Bu çok yönlülük, 
                    Rossi'yi motosiklet sporunun en saygın isimlerinden biri hâline getirdi.</p>
                                        <p>Bu dönüşüm hikayesi, günümüzde genç pilotlara motivasyon kaynağı olarak sıkça anlatılıyor. 
                    Rossi'nin "konforlu" bir kariyer yerine zorlu bir mücadeleyi seçmesi, sporun en değerli 
                    derslerinden biri olarak MotoGP akademilerinde referans gösteriliyor.</p>
                                        <p>Rossi'nin Yamaha'daki uzun kariyeri boyunca elde ettiği başarılar, markanın MotoGP'deki 
                    konumunu da kalıcı olarak güçlendirdi. Bu ortaklık, sadece bireysel şampiyonluklarla değil, 
                    Yamaha'nın motosiklet geliştirme felsefesinin şekillenmesinde de belirleyici bir rol 
                    oynadı.</p>
                                        <p>Bu karar, sporda "en iyi" olmanın bazen en güvenli yolu değil, en cesur yolu seçmek 
                    anlamına geldiğini gösterdi.</p>
                                        <p>Bu hikaye, aynı zamanda spor pazarlaması açısından da incelenen bir örnek oldu — Rossi'nin 
                    kişisel markası, hangi motosikleti sürdüğünden bağımsız olarak, sporun en değerli 
                    varlıklarından biri hâline gelmişti.</p>
                                        <p>Sonuç olarak Rossi'nin bu kumarı, motosiklet sporunun en akılda kalıcı ve en öğretici 
                    hikayelerinden biri olarak nesiller boyu anlatılmaya devam edecek.</p>
                                        <p>Bu karar, motosiklet sporunun en efsanevi anlarından biri olarak hâlâ konuşuluyor.</p>
                    <p>Bu hikaye, bugün hâlâ "kazanan takımı bırakıp riske girmenin" motosiklet sporundaki en 
                    çarpıcı örneği olarak anlatılıyor.</p>
                    """.trimIndent()
                ),
                "stoner-ducati-ilk-sampiyonluk" to Pair(
                    "Kimsenin Beklemediği Şampiyon: Stoner'ın Ducati'yle Yazdığı Tarih",
                    """
                    <p>Casey Stoner, MotoGP'ye 2006'da düşük bütçeli LCR Honda takımıyla giriş yapmıştı. Genç 
                    Avustralyalı pilot, alt kategorilerde göze çarpan bir potansiyel sergilemiş olsa da, ilk MotoGP 
                    sezonunda rekabetçi bir motosikletten yoksun bir takımda mücadele ediyordu ve üst sıralara pek 
                    yaklaşamıyordu. Bu koşullarda bile Stoner'ın hız potansiyeli fark edilebiliyordu — sürüş 
                    tarzı, özellikle motosikletin arka tekerleğini kaydırarak virajları alma konusunda diğer 
                    pilotlardan belirgin şekilde farklıydı.</p>
                    <p>2006 sonunda Ducati, 2007 sezonu için Stoner'ı fabrika takımına alma kararı verdi — bu karar, 
                    çoğu kişiye riskli göründü. İtalyan marka, o zamana kadar MotoGP'de hiç şampiyonluk kazanmamıştı 
                    ve motosikleti "zor kullanılır" olarak biliniyordu; Ducati'nin Desmosedici modeli, güçlü motoru 
                    sayesinde düz yollarda hızlıydı ama viraj davranışı kararsız bulunuyor, birçok deneyimli pilot 
                    bu motosikletle mücadele ediyordu. Bu nedenle, henüz kendini tam olarak kanıtlamamış genç bir 
                    pilotun bu zorlu motosikletle başarılı olması pek beklenmiyordu.</p>
                    <p>Ama Stoner ile Ducati'nin Desmosedici motosikleti arasında beklenmedik bir uyum oluştu. 
                    Stoner'ın agresif ve kayma-tabanlı sürüş tarzı, motosikletin diğer pilotlar için zorluk yaratan 
                    özelliklerini tam tersine bir avantaja dönüştürdü — motosikletin arka lastik kaymasına yatkın 
                    yapısı, Stoner'ın doğal sürüş stiline neredeyse mükemmel şekilde uyuyordu. Sezon boyunca art 
                    arda yarışlar kazanan Stoner, deneyimli rakiplerini geride bırakarak şampiyonluğa ulaştı — 
                    Ducati'ye markanın MotoGP tarihindeki ilk (ve uzun yıllar tek) dünya şampiyonluğunu 
                    kazandırdı. Bu başarı, İtalyan motosiklet endüstrisi için de büyük bir gurur kaynağı oldu.</p>
                    <p>Bu başarı, "zor" bir motosikletin doğru pilotla buluştuğunda ne kadar güçlü olabileceğinin 
                    kanıtıydı — Stoner, bu motosikleti başka hiçbir pilotun tam olarak süremediği bir tarzda 
                    kullanmayı başarmıştı. Nitekim Stoner'ın Ducati'den ayrılmasının ardından, motosikletin 
                    performansı diğer pilotların elinde belirgin şekilde geriledi; bu durum, başarının büyük 
                    ölçüde Stoner'ın kişisel yeteneğinden kaynaklandığını gösteren dolaylı bir kanıt oldu.</p>
                                        <p>Bu başarı aynı zamanda, MotoGP'de "pilot-motosiklet uyumu" kavramının ne kadar özel ve 
                    tekrarlanması zor bir olgu olduğunu da gösterdi. Ducati, sonraki yıllarda motosikletini daha 
                    geniş bir pilot kitlesine uygun hale getirmek için önemli tasarım değişiklikleri yaptı — bu 
                    süreç, markanın gelecekteki şampiyonluklarının temelini oluşturan uzun bir mühendislik 
                    yolculuğunun başlangıcı oldu.</p>
                                        <p>Stoner'ın bu başarısı, aynı zamanda Ducati'nin MotoGP'deki uzun vadeli yatırımının da meyvesini 
                    verdiğinin bir göstergesiydi. Marka, bu şampiyonluktan sonra motosiklet geliştirme programına 
                    daha fazla kaynak ayırdı ve sonraki yıllarda hem Stoner'ın hem de gelecekteki pilotların 
                    başarısı için daha rekabetçi bir platform oluşturmaya odaklandı.</p>
                                        <p>Bu başarı hikayesi, motosiklet mühendisliği öğrencileri arasında da sıkça tartışılan bir konu 
                    hâline geldi — "zor" bir makinenin doğru kullanıcıyla nasıl rekabetçi hâle gelebileceği, 
                    tasarım felsefesi derslerinde örnek olay olarak inceleniyor.</p>
                                        <p>Stoner'ın bu erken kariyer başarısı, onu MotoGP tarihinin en genç şampiyonlarından biri hâline 
                    getirdi. Bu başarı, Avustralya'da motorsporlarına olan ilgiyi de artırdı ve ülkede yeni bir 
                    nesil pilot yetişmesine katkıda bulundu.</p>
                                        <p>Bu şampiyonluk, motosiklet sporunda "imkansız" görünen eşleşmelerin bazen en güçlü sonuçları 
                    doğurabileceğini kanıtladı.</p>
                                        <p>Bu dönem, aynı zamanda MotoGP'de İtalyan ve Avustralyalı mühendislik-pilot işbirliğinin ne 
                    kadar verimli olabileceğinin de bir kanıtıydı — iki farklı kültürden gelen ekip, ortak bir 
                    hedef etrafında birleşerek tarihi bir başarıya imza attı.</p>
                                        <p>Sonuç olarak Stoner'ın bu başarısı, motosiklet sporunda beklenmedik eşleşmelerin bazen en 
                    unutulmaz sonuçları doğurabileceğinin kalıcı bir kanıtı olarak spor tarihine geçti. Bu 
                    şampiyonluk, aynı zamanda genç ve deneyimsiz görünen bir pilotun, doğru koşullar altında 
                    sporun zirvesine ne kadar hızlı ulaşabileceğinin de güçlü bir hatırlatıcısıydı.</p>
                                        <p>Bu şampiyonluk, MotoGP tarihinin en akılda kalıcı başarı hikayelerinden biri olarak 
                    kalmaya devam ediyor ve genç pilotlara ilham vermeye devam ediyor.</p>
                                        <p>Bu zafer, sporun altın anlarından biri olarak MotoGP'nin resmi tarihinde özel bir yere 
                    sahip ve genç pilotlara ilham vermeye devam ediyor.</p>
                                        <p>Bu başarı, sporun en özel hikayelerinden biri olarak kalıcı yerini koruyor.</p>
                    <p>Yıllar sonra Honda'ya geçip 2011'de ikinci dünya şampiyonluğunu kazanacak olan Stoner, 
                    Ducati'deki bu ilk imzası sayesinde MotoGP tarihine adını yazdırdı. Bu hikaye, MotoGP 
                    tarihinin en beklenmedik şampiyonluk hikayelerinden biri olarak hâlâ hatırlanıyor.</p>
                    """.trimIndent()
                ),
                "marquez-honda-ducati-sok-transfer" to Pair(
                    "Yıllarca Sadakatten Sonra Şok Ayrılık: Marquez'in Ducati'ye Geçişi",
                    """
                    <p>Marc Marquez, MotoGP'ye 2013'te şaşırtıcı bir başarıyla giriş yapmış, o sezon hem rookie 
                    hem de genel şampiyon olarak tarihe geçmişti. Honda ile kurduğu ortaklık, sonraki yıllarda 
                    sporun en dominant birlikteliklerinden birine dönüştü — Marquez, 2013'ten itibaren Honda ile 
                    adeta özdeşleşmiş, markayla altı MotoGP dünya şampiyonluğu kazanmış bir isimdi. Bu dönemde 
                    Marquez, agresif viraj girişleri ve sınırları zorlayan sürüş tarzıyla, sporun en heyecan 
                    verici ve en başarılı pilotu olarak kabul ediliyordu.</p>
                    <p>Ama 2020'de Jerez pistinde yaşadığı ciddi kol sakatlığı, kariyerinin gidişatını tamamen 
                    değiştirdi. Bu sakatlık, birden fazla ameliyat gerektirdi ve iyileşme süreci beklenenden çok 
                    daha uzun sürdü — Marquez, sonraki sezonlarda hem bu fiziksel zorlukla hem de Honda'nın 
                    giderek rekabetçiliğini kaybeden motosikletiyle aynı anda mücadele etmek zorunda kaldı. Bir 
                    zamanlar sporun en baskın motosikleti olan Honda RC213V, bu dönemde teknik sorunlar yaşamaya 
                    başlamış, diğer markaların gerisinde kalmıştı. Bu durum, bir zamanlar yarış kazanmayı 
                    alışkanlık hâline getirmiş bir pilot için son derece sıkıntılı bir dönemdi.</p>
                    <p>Yıllarca süren sadakatin ardından, Marquez sonunda zor bir karara vardı: 2024 sezonu için 
                    Honda'dan ayrılıp, üstelik fabrika takımı bile değil, uydu takımı Gresini Ducati'ye transfer 
                    oldu. Bu, sporun en büyük isimlerinden birinin, gururunu bir kenara bırakıp rekabetçi bir 
                    motosiklete geri dönmeyi tercih ettiği bir hamleydi — genelde bu denli başarılı bir pilotun 
                    fabrika takımından bir uydu takımına geçmesi, kariyerinde bir gerileme olarak yorumlanabilirdi, 
                    ama Marquez için öncelik artık unvan değil, tekrar rekabetçi olabilmekti.</p>
                    <p>Karar hızla meyvesini verdi — Marquez, Ducati'nin motosikletiyle yıllardır göremediği 
                    performansına yeniden kavuştu, sezon boyunca ön sıralarda mücadele etti ve podyum 
                    yarışmalarına geri döndü. Bu performans, kulüpler arasında da büyük ilgi uyandırdı; Ducati'nin 
                    fabrika takımı, sonraki sezon için Marquez'e resmi bir koltuk teklif etme kararı aldı — bu, 
                    onun uydu takımından fabrika takımına terfi ettiği, kariyerinin yeniden yükselişe geçtiği 
                    anlamına geliyordu.</p>
                                        <p>Bu geçiş aynı zamanda, MotoGP'de sakatlık sonrası toparlanma sürecinin ne kadar karmaşık ve 
                    uzun olabileceğinin de bir örneğiydi. Marquez'in hikayesi, sporcuların sadece fiziksel değil, 
                    aynı zamanda doğru ekipman ve ortamı bulma konusunda da büyük kararlar almak zorunda 
                    kalabileceğini gösterdi — bu durum, MotoGP camiasında sakatlık sonrası kariyer yönetimi 
                    konusundaki tartışmalara da katkıda bulundu.</p>
                                        <p>Marquez'in hikayesi, MotoGP camiasında "ikinci şans" kavramının ne kadar değerli olabileceğinin 
                    de bir örneği oldu. Sporun en büyük isimlerinden birinin bile, kariyerinin belirli bir 
                    noktasında tekrar sıfırdan başlama cesaretini göstermesi, genç pilotlar için de önemli bir 
                    ders niteliği taşıyor.</p>
                                        <p>Bu geçiş, aynı zamanda spor psikolojisi açısından da ilgi çekici bir vaka oldu — bir 
                    şampiyonun, statü kaybı riskini göze alarak yeniden rekabetçi olmayı tercih etmesi, 
                    sporcu motivasyonu üzerine yapılan araştırmalarda sıkça referans gösteriliyor.</p>
                                        <p>Bu geçiş süreci, MotoGP'de takım değişikliklerinin finansal boyutunu da gözler önüne serdi 
                    — bir şampiyonun, daha düşük bir maaşla ve daha az garantiyle bir uydu takımına geçmeyi kabul 
                    etmesi, sporcu motivasyonunun her zaman parasal faktörlerle sınırlı olmadığını gösterdi.</p>
                                        <p>Bu dönüşüm, sporun en büyük isimlerinin bile alçakgönüllülükle yeniden başlayabileceğinin 
                    güçlü bir hatırlatıcısı oldu.</p>
                                        <p>Bu geçiş, aynı zamanda MotoGP'de takım sadakatinin sınırlarının nerede bittiğini de gözler 
                    önüne serdi — bir pilotun, uzun yıllar sürdürdüğü bir ortaklığı geride bırakıp yeni bir 
                    başlangıç yapması, sporun rekabetçi doğasının kaçınılmaz bir parçası olarak görüldü.</p>
                                        <p>Sonuç olarak Marquez'in bu geçişi, MotoGP tarihinde bir şampiyonun kariyerini yeniden 
                    inşa etme cesaretinin en güçlü örneklerinden biri olarak hatırlanacak. Bu hikaye, sporcuların 
                    zorluklar karşısında pes etmek yerine, alçakgönüllülükle yeniden başlamayı seçtiklerinde 
                    nelerin mümkün olabileceğini gösteren kalıcı bir ders niteliği taşıyor.</p>
                                        <p>Bu geçiş, MotoGP camiasında hâlâ sıkça referans gösterilen bir cesaret örneği olarak 
                    anılıyor ve sporun en öğretici hikayelerinden biri sayılıyor.</p>
                                        <p>Bu yeniden doğuş, sporcuların en zor anlarda bile pes etmemesi gerektiğinin canlı bir 
                    kanıtı olarak MotoGP tarihinin en öğretici hikayelerinden biri sayılıyor.</p>
                    <p>Bu geçiş, bir pilotun bile bazen "en iyi bilinen" markadan ayrılıp, rekabetçiliği yeniden 
                    bulmak için cesur bir adım atması gerektiğinin çarpıcı bir örneği olarak MotoGP tarihine 
                    geçti.</p>
                    """.trimIndent()
                ),
                "figo-barcelona-real-madrid-ihanet" to Pair(
                    "Kulüp Efsanesinden 'Hain'e: Figo'nun Real Madrid'e Geçişi",
                    """
                    <p>Luis Figo, 1995'te Barcelona'ya katıldığından beri kulübün en önemli oyuncularından biri 
                    hâline gelmişti. Portekizli kanat oyuncusu, yaratıcılığı ve teknik yeteneğiyle Barcelona'nın 
                    hücum oyununun merkezinde yer alıyor, taraftarlar tarafından adeta bir kulüp sembolü olarak 
                    görülüyordu. 2000 yazına gelindiğinde Figo, Barcelona'nın kalbinde özel bir yere sahipti — 
                    taraftarların gözdesi, takım kaptanı adayı bir yıldızdı. Hatta o dönem, Real Madrid'e asla 
                    transfer olmayacağına dair kamuoyu önünde neredeyse söz vermişti; bu açıklama, taraftarların 
                    ona olan güvenini daha da pekiştirmişti.</p>
                    <p>Bu yüzden, o yazın sonunda gelen haber Barcelona taraftarlarını derinden sarstı: Figo, 
                    dönemin dünya rekoru transfer ücretiyle, ezeli rakip Real Madrid'e imza atmıştı. Bu bonservis 
                    bedeli, futbol tarihinde o zamana kadar görülen en yüksek rakamlardan biriydi ve transferin 
                    büyüklüğünü daha da öne çıkardı.</p>
                    <p>Transferin arkasındaki hikaye de en az sonucu kadar dramatikti — Real Madrid Başkan adayı 
                    Florentino Pérez, seçim kampanyası sırasında taraftarlarına verdiği bir vaat olarak Figo'yu 
                    kulübe getireceğini açıklamış, seçildikten sonra da bu sözü tuttu. Bu, Pérez'in başkanlık 
                    döneminde uygulayacağı "Galácticos" (Süper Yıldızlar) politikasının ilk büyük adımı oldu — 
                    kulübün, dünyanın en iyi oyuncularını rakip takımlardan bile olsa transfer ederek bir araya 
                    getirme stratejisinin başlangıcı. Barcelona taraftarları için bu, sadece bir oyuncu kaybı 
                    değil, kulübün en güvenilir isimlerinden birinin, kişisel bir vaadi çiğneyerek ezeli rakibe 
                    gitmesi anlamına gelen derin bir ihanet olarak görüldü.</p>
                    <p>Figo'nun Camp Nou'ya Real Madrid formasıyla ilk dönüşü, futbol tarihinin en gergin 
                    sahnelerinden birine dönüştü — taraftarlar sahaya çeşitli nesneler fırlattı, karşılaşma 
                    defalarca durduruldu. Bir sonraki sezonki ziyaretinde ise olaylar daha da tırmandı; sahaya 
                    atılan nesneler arasında güvenlik açısından ciddi endişe yaratan objeler de vardı, bu da 
                    maçın güvenlik gerekçesiyle uzun süre durdurulmasına neden oldu. Bu olaylar, günümüzde bile 
                    "El Clásico" rekabetinin en yoğun anları arasında hatırlanıyor.</p>
                    <p>Figo, Real Madrid'de geçirdiği yıllar boyunca kulübün "Galácticos" döneminin önemli bir 
                    parçası oldu, şampiyonluklar kazandı ve kariyerinin zirvesini bu dönemde yaşadı. Ama Barcelona 
                    taraftarlarıyla ilişkisi bir daha hiç düzelmedi; yıllar sonra bile Camp Nou'ya her ziyaretinde 
                    benzer gerilim yaşandı. Bu olay, bugün hâlâ "büyük bir transferin bir kulüp-taraftar ilişkisini 
                    nasıl derinden yaralayabileceğinin" en bilinen örneği olarak anlatılıyor.</p>
                                        <p>Bu transfer aynı zamanda, Florentino Pérez'in başkanlık dönemi boyunca sürdüreceği 
                    "Galácticos" politikasının temellerini attı — bu strateji, sonraki yıllarda Zinedine Zidane, 
                    Ronaldo (Brezilyalı) ve David Beckham gibi isimlerin de Real Madrid'e katılmasıyla devam etti. 
                    Figo'nun transferi, bu açıdan sadece bir oyuncu hareketi değil, modern futbolda "yıldız 
                    toplama" stratejisinin başlangıç noktalarından biri olarak da hatırlanıyor.</p>
                                        <p>Bu transfer, günümüzde bile İspanyol futbolunda "El Clásico" rekabetinin en yoğun anları 
                    arasında hatırlanıyor. Figo'nun hikayesi, futbol tarihçileri tarafından sadece bir oyuncu 
                    transferi değil, iki kulüp arasındaki rekabetin yeni bir boyut kazandığı tarihi bir dönüm 
                    noktası olarak da değerlendiriliyor.</p>
                                        <p>Bu transfer, aynı zamanda futbolda "taraftar bağlılığı" kavramının ne kadar derin ve uzun 
                    süreli olabileceğinin de bir kanıtıydı. Yıllar geçse de, bu tür büyük rakip-geçişleri, 
                    taraftar hafızasında kalıcı izler bırakmaya devam ediyor.</p>
                                        <p>Figo'nun bu transferi, aynı zamanda futbolda taraftar-oyuncu ilişkisinin ne kadar duygusal 
                    bir boyut taşıyabileceğinin de bir kanıtıydı. Bu olay, sonraki yıllarda benzer rakip-kulüp 
                    transferlerinde taraftar tepkilerinin nasıl yönetileceği konusunda kulüpler için de önemli 
                    bir ders niteliği taşıdı.</p>
                                        <p>Bu hikaye, futbolda rekabetin sadece sahada değil, taraftarların kalbinde de yaşandığının 
                    en canlı kanıtlarından biri.</p>
                                        <p>Bu transfer, aynı zamanda İspanyol futbolunun küresel popülerliğinin artmasına da katkıda 
                    bulundu — bu tür yüksek profilli ve dramatik transferler, La Liga'nın dünya çapındaki 
                    izleyici kitlesini genişletmesine yardımcı oldu.</p>
                                        <p>Sonuç olarak Figo'nun bu transferi, futbolda rekabetin sınırlarını zorlayan, kulüpler 
                    arası ilişkileri kalıcı olarak değiştiren tarihi anlardan biri olarak anılmaya devam 
                    ediyor.</p>
                                        <p>Bu transfer, futbol tarihinin en dramatik ve en çok hatırlanan anlarından biri olmaya 
                    devam ediyor.</p>
                                        <p>Bu olay, futbolun duygusal gücünün en somut kanıtlarından biri olarak spor tarihinde 
                    kalıcı yerini korumaya devam ediyor.</p>
                    <p>TransferKolik'te "Barcelona" ve "Real Madrid" aratarak, bu iki ezeli rakipte de forma 
                    giymiş diğer oyuncuları keşfedebilirsiniz.</p>
                    """.trimIndent()
                ),
                "shaq-lakers-miami-kobe-catismasi" to Pair(
                    "Üç Şampiyonluğun Ardından Ayrılık: Shaq'ın Lakers'tan Miami'ye Gönderilişi",
                    """
                    <p>Shaquille O'Neal, 1996'da Los Angeles Lakers'a katıldığında, kulüp uzun süredir 
                    şampiyonluk arayışındaydı. O'Neal'ın fiziksel gücü ve içeride gösterdiği baskınlık, kısa 
                    sürede kulübün en önemli silahı hâline geldi. 1996'da genç bir yetenek olarak draft edilen 
                    Kobe Bryant'ın da kadroya katılmasıyla birlikte, Lakers gelecek vadeden bir ikiliye sahip 
                    oldu — ama bu ikilinin gerçek potansiyeline ulaşması birkaç sezon sürdü.</p>
                    <p>Shaquille O'Neal ve Kobe Bryant, 2000-2002 arasında Los Angeles Lakers'a üst üste üç NBA 
                    şampiyonluğu kazandırmış, basketbol tarihinin en güçlü ikililerinden biriydi. Bu dönemde 
                    Lakers, lig genelinde adeta rakipsiz bir güç hâline gelmiş, "üç peş peşe şampiyonluk" 
                    (three-peat) başarısını elde ederek NBA tarihine geçmişti. Ama sahadaki bu başarı, kulisteki 
                    gerilimi gizleyemedi — iki yıldız arasındaki liderlik çatışması, yıllar içinde giderek büyüdü 
                    ve kamuoyuna yansıyan bir rekabete dönüştü. O'Neal, kendisini takımın deneyimli lideri olarak 
                    görürken, Bryant kariyerinin yükselen bir aşamasında kendi liderlik rolünü de talep ediyordu 
                    — bu çatışma, giderek soyunma odasının dışına, basına yansıyan açıklamalara kadar taştı.</p>
                    <p>2004 yazında Lakers yönetimi, ikisinden birini seçmek zorunda kaldı — ve genç, uzun vadeli 
                    potansiyele sahip Kobe'yi tercih etti. Bu karar, kulübün gelecek stratejisini şekillendiren, 
                    basketbol tarihinde nadiren görülen türden bir "ya biri ya diğeri" seçimiydi. Otuz iki 
                    yaşındaki Shaq, kulübün merkez oyuncusu ve üç şampiyonluğun mimarlarından biri olmasına 
                    rağmen, Miami Heat'e takas edildi — bu, o dönemin en yüksek profilli oyuncu takaslarından 
                    biriydi ve NBA camiasını derinden şaşırttı.</p>
                    <p>Karar, o dönem NBA çevrelerinde büyük tartışma yarattı — bazıları Lakers'ın bir efsaneyi 
                    çok erken gönderdiğini, kulübün üç şampiyonluğa öncülük eden bir oyuncuyu bu şekilde takas 
                    etmesinin nankörlük olduğunu düşünürken, bazıları kulübün geleceği için doğru bir hamle 
                    olduğunu, genç bir yıldıza yatırım yapmanın uzun vadede daha akıllıca olduğunu savundu.</p>
                    <p>Shaq, Miami'de daha ilk sezonunda dördüncü kariyer şampiyonluğuna ulaşarak kararının 
                    isabetli olduğunu kanıtladı — bu, hem kendisi hem de Miami Heat için tarihi bir başarıydı, 
                    kulübün ilk NBA şampiyonluğuydu. Lakers ise Kobe ile yeniden şampiyonluğa ulaşmak için birkaç 
                    yıl daha beklemek zorunda kaldı; kulüp, 2008-2010 arasında yeni bir kadro etrafında yeniden 
                    şampiyonluk mücadelesine girip iki şampiyonluk daha kazandı, ama bu, Shaq'ın ayrılışından 
                    sonra dört-beş yıl sürecek bir yeniden inşa sürecinin ardından gerçekleşti.</p>
                                        <p>Bu olay aynı zamanda, NBA kulüplerinin uzun vadeli kadro planlaması yaparken, mevcut 
                    başarıyı mı yoksa gelecek potansiyelini mi önceliklendirmesi gerektiği konusundaki klasik 
                    tartışmanın da en bilinen örneklerinden biri hâline geldi. Bu karar, sonraki yıllarda birçok 
                    NBA yöneticisinin benzer "yıldız oyuncu ayrılığı" kararları alırken referans gösterdiği bir 
                    vaka olarak spor yönetimi literatüründe yerini korudu.</p>
                                        <p>Bu hikaye, NBA'de "kimin takımı" tartışmalarının ne kadar köklü olabileceğinin de bir 
                    göstergesiydi. O'Neal ve Bryant arasındaki bu rekabet, basketbol tarihinde iki büyük 
                    yıldızın aynı kadroda bir arada bulunmasının getirdiği zorlukların en çok analiz edilen 
                    örneklerinden biri olarak spor psikolojisi literatüründe de yerini aldı.</p>
                                        <p>Bu ayrılık, aynı zamanda spor yönetiminde "zamanlaması doğru karar" kavramının önemini de 
                    gösterdi — Lakers yönetiminin bu zor kararı doğru zamanda alması, kulübün hem kısa hem 
                    uzun vadeli başarısını şekillendiren kritik bir dönemeç oldu.</p>
                                        <p>O'Neal'ın Miami'deki başarısı, kariyerinin sonraki döneminde de devam etti — farklı 
                    kulüplerde oynamaya devam eden merkez oyuncusu, basketbol tarihinin en dominant içeri 
                    oyuncularından biri olarak kariyerini tamamladı ve Basketbol Onur Listesi'ne seçildi.</p>
                                        <p>Bu ayrılık, basketbol tarihinde "doğru zamanda ayrılmanın" bazen en büyük başarı olduğunu 
                    gösteren bir örnek oldu.</p>
                                        <p>Bu takas, aynı zamanda NBA'de merkez oyuncuların piyasa değerinin nasıl değerlendirildiğinin 
                    de ilginç bir örneği oldu — otuz iki yaşında bile şampiyonluk mimarı olan bir oyuncunun takas 
                    edilmesi, kulüplerin yaş ve gelecek potansiyeli arasında nasıl zor kararlar aldığını 
                    gösterdi.</p>
                                        <p>Sonuç olarak bu ayrılık, NBA tarihinde iki büyük yıldızın bir arada var olma zorluğunun 
                    en çok analiz edilen ve en çok öğretici örneklerinden biri olarak kalıcı yerini korudu.</p>
                    <p>Bu ayrılık, bugün hâlâ "iki yıldızın bir arada var olamaması" durumunun NBA tarihindeki en 
                    bilinen örneklerinden biri olarak anlatılıyor.</p>
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
                "vettel-alonsonun-ayrildigi-koltuga-oturdu" to "f1",
                "toprak-kawasakiden-motogpye-yolculuk" to "motogp",
                "rossi-honda-yamaha-riskli-kumar" to "motogp",
                "stoner-ducati-ilk-sampiyonluk" to "motogp",
                "marquez-honda-ducati-sok-transfer" to "motogp",
                "figo-barcelona-real-madrid-ihanet" to "futbol",
                "shaq-lakers-miami-kobe-catismasi" to "basketbol"
            )

            // 🎨 Ana sayfadaki modern çizim ikonlarıyla AYNI SVG'ler — blog
            // sayfasındaki eski emoji ikonların yerine geçiyor.
            fun svgFootball(): String = """<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" style="vertical-align:-2px; margin-right:5px;"><circle cx="12" cy="12" r="10"/><path d="M12 7l3.5 2.5-1.3 4.1H9.8L8.5 9.5z" fill="currentColor" stroke="none"/><path d="M12 2v5M12 17v5M2.5 9.5l4.5 1.5M17 11l4.5-1.5M6 19.5l1.8-4M16.2 15.5L18 19.5"/></svg>"""
            fun svgBasketball(): String = """<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" style="vertical-align:-2px; margin-right:5px;"><circle cx="12" cy="12" r="10"/><path d="M12 2v20M2 12h20M4.2 5.5c2.5 2.5 3.8 5.5 3.8 6.5s-1.3 4-3.8 6.5M19.8 5.5c-2.5 2.5-3.8 5.5-3.8 6.5s1.3 4 3.8 6.5"/></svg>"""
            fun svgF1(): String = """<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" style="vertical-align:-2px; margin-right:5px;"><rect x="3" y="4" width="7" height="7"/><rect x="14" y="4" width="7" height="7"/><rect x="3" y="13" width="7" height="7"/><rect x="14" y="13" width="7" height="7"/></svg>"""
            fun svgMotoGp(): String = """<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" style="vertical-align:-2px; margin-right:5px;"><circle cx="5" cy="17" r="3"/><circle cx="19" cy="17" r="3"/><path d="M8 17h5l3-7h3M13 10l-2-3H7l-2 5"/></svg>"""

            fun blogPageHtml(title: String, bodyHtml: String, description: String, showAllArticlesLink: Boolean = true): String = """
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
                        ${if (showAllArticlesLink) "<a href=\"/blog\" class=\"back-link\">📰 Tüm Yazılar</a>" else ""}
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
                val motogpHtml = blogArticles.entries
                    .filter { articleSport[it.key] == "motogp" }
                    .joinToString("") { (slug, pair) -> "<a href=\"/blog/$slug\">${pair.first}</a>" }
                val html = blogPageHtml(
                    "Transfer Hikayeleri",
                    """
                    <h1>Bonservissiz Yazarın Kaleminden — Haftanın Blogları</h1>
                    <p class="subtitle">Futbol, basketbol, Formula 1 ve MotoGP tarihinin en dramatik, en şaşırtıcı transfer anları.</p>
                    <div class="sport-filter-bar">
                        <button class="sport-filter-btn active" data-filter="all" onclick="filterBlogBySport('all', this)">Tümü</button>
                        <button class="sport-filter-btn" data-filter="futbol" onclick="filterBlogBySport('futbol', this)">${svgFootball()} Futbol</button>
                        <button class="sport-filter-btn" data-filter="basketbol" onclick="filterBlogBySport('basketbol', this)">${svgBasketball()} Basketbol</button>
                        <button class="sport-filter-btn" data-filter="f1" onclick="filterBlogBySport('f1', this)">${svgF1()} Formula 1</button>
                        <button class="sport-filter-btn" data-filter="motogp" onclick="filterBlogBySport('motogp', this)">${svgMotoGp()} MotoGP</button>
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
                    <div class="sport-section" data-sport="motogp">
                        <h2>${svgMotoGp()} MotoGP</h2>
                        <div class="article-list">$motogpHtml</div>
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
                    "Futbol, basketbol ve Formula 1 dünyasının en dramatik ve şaşırtıcı transfer hikayeleri — TransferKolik Blog.",
                    showAllArticlesLink = false
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

            // 🏀 Basketbol Günün Oyuncusu — futboldakiyle AYNI mimari: sadece
            // isim havuzu sabit, kariyer verisi gerçek veritabanından geliyor.
            get("/basketball/dailyPlayerBio") {
                val dateSeed = call.request.queryParameters["seed"]?.toIntOrNull() ?: 0
                val bio = DatabaseClient.fetchDailyBasketballPlayerBio(dateSeed)
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

            // 🏀 Basketbol köprü doğrulama — futboldakiyle aynı desen, ama uyruk
            // şartı YOK (veri kaynağında bulunmuyor). "league" parametresi
            // europe/nba tablosundan hangisine bakılacağını belirtiyor.
            get("/basketball/verifyBridge") {
                val name = call.request.queryParameters["name"]
                val team = call.request.queryParameters["team"]
                if (name.isNullOrBlank() || team.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val league = call.request.queryParameters["league"] ?: "europe"
                val startYear = call.request.queryParameters["startYear"]?.toIntOrNull()
                val endYear = call.request.queryParameters["endYear"]?.toIntOrNull()
                val valid = DatabaseClient.verifyBasketballPlayerPlayedForTeam(name, team, league, startYear, endYear)
                call.respond(mapOf("valid" to valid))
            }

            // 🎯 YENİ: soru göstermeden ÖNCE bu sorunun GERÇEKTEN çözülebilir
            // olduğunu (en az bir köprü adayı bulunduğunu) doğrulamak için.
            get("/basketball/checkSolvable") {
                val team = call.request.queryParameters["team"]
                val startYear = call.request.queryParameters["startYear"]?.toIntOrNull()
                val endYear = call.request.queryParameters["endYear"]?.toIntOrNull()
                val exclude = call.request.queryParameters["exclude"]?.split(",") ?: emptyList()
                if (team.isNullOrBlank() || startYear == null || endYear == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val league = call.request.queryParameters["league"] ?: "europe"
                val minYear = minOf(startYear, endYear)
                val maxYear = maxOf(startYear, endYear)
                val candidate = DatabaseClient.findAnyBridgeCandidate(team, league, minYear, maxYear, exclude)
                // 🎯 YENİ: sadece çözülebilir mi diye değil, BULDUĞUMUZ ismi de
                // döndürüyoruz — böylece manuel araştırmaya gerek kalmadan,
                // kullanıcı 5 hakkını harcarsa GERÇEK cevabı gösterebiliriz.
                call.respond(SolvableCheckResponse(solvable = candidate != null, answer = candidate))
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
