import kotlinx.serialization.Serializable

@Serializable
data class Player(
        val id: Int,
        val name: String,
        val position: String,
        val nationality: String,
        val team: String,
        val transferId: Int? = null
)