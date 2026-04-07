@Entity(tableName = "location_history")
data class LocationHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    var synced: Boolean = false
)