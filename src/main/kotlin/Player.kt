import kotlinx.serialization.Serializable

@Serializable
data class Player(
        val playerId: Int,          // Ayrı kolon: Sadece Oyuncu ID'si (Örn: 137610)
        val name: String,           // Ayrı kolon: Sadece Oyuncu Adı-Soyadı (Örn: Alperen Uysal)
        val position: String,
        val nationality: String,
        val team: String,
        val birthDate: String?,
        val season1: String?,
        val season2: String?,
        val transferId: Int         // Dahili takip için kalabilir
)