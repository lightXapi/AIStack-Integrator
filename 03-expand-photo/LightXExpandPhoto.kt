/**
 * LightX AI Expand Photo (Outpainting) API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered photo expansion functionality using padding-based outpainting.
 */

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.*
import java.net.*
import java.net.http.*
import java.time.Duration
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

// MARK: - Data Classes

@Serializable
data class ExpandPhotoUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: ExpandPhotoUploadImageBody
)

@Serializable
data class ExpandPhotoUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class ExpandPhotoResponse(
    val statusCode: Int,
    val message: String,
    val body: ExpandPhotoBody
)

@Serializable
data class ExpandPhotoBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class ExpandPhotoOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: ExpandPhotoOrderStatusBody
)

@Serializable
data class ExpandPhotoOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - Padding Configuration

data class PaddingConfig(
    val leftPadding: Int = 0,
    val rightPadding: Int = 0,
    val topPadding: Int = 0,
    val bottomPadding: Int = 0
) {
    fun toMap(): Map<String, Int> {
        return mapOf(
            "leftPadding" to leftPadding,
            "rightPadding" to rightPadding,
            "topPadding" to topPadding,
            "bottomPadding" to bottomPadding
        )
    }
    
    companion object {
        fun horizontal(amount: Int) = PaddingConfig(
            leftPadding = amount,
            rightPadding = amount
        )
        
        fun vertical(amount: Int) = PaddingConfig(
            topPadding = amount,
            bottomPadding = amount
        )
        
        fun all(amount: Int) = PaddingConfig(
            leftPadding = amount,
            rightPadding = amount,
            topPadding = amount,
            bottomPadding = amount
        )
    }
}

// MARK: - LightX Expand Photo API Client

class LightXExpandPhotoAPI(private val apiKey: String) {
    
    companion object {
        private const val BASE_URL = "https://api.lightxeditor.com/external/api"
        private const val MAX_RETRIES = 5
        private const val RETRY_INTERVAL = 3000L // milliseconds
        private const val MAX_FILE_SIZE = 5242880L // 5MB
    }
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Upload image to LightX servers
     * @param imageFile File containing the image data
     * @param contentType MIME type (image/jpeg or image/png)
     * @return Final image URL
     */
    suspend fun uploadImage(imageFile: File, contentType: String = "image/jpeg"): String {
        val fileSize = imageFile.length()
        
        if (fileSize > MAX_FILE_SIZE) {
            throw LightXExpandPhotoException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Expand photo using AI outpainting
     * @param imageUrl URL of the uploaded image
     * @param padding Padding configuration
     * @return Order ID for tracking
     */
    suspend fun expandPhoto(imageUrl: String, padding: PaddingConfig): String {
        val endpoint = "$BASE_URL/v1/expand-photo"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("leftPadding", padding.leftPadding)
            put("rightPadding", padding.rightPadding)
            put("topPadding", padding.topPadding)
            put("bottomPadding", padding.bottomPadding)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXExpandPhotoException("Network error: ${response.statusCode()}")
        }
        
        val expandResponse = json.decodeFromString<ExpandPhotoResponse>(response.body())
        
        if (expandResponse.statusCode != 2000) {
            throw LightXExpandPhotoException("Expand photo request failed: ${expandResponse.message}")
        }
        
        val orderInfo = expandResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("📐 Padding: L:${padding.leftPadding} R:${padding.rightPadding} T:${padding.topPadding} B:${padding.bottomPadding}")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): ExpandPhotoOrderStatusBody {
        val endpoint = "$BASE_URL/v1/order-status"
        
        val requestBody = buildJsonObject {
            put("orderId", orderId)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXExpandPhotoException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<ExpandPhotoOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXExpandPhotoException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): ExpandPhotoOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Photo expansion completed successfully!")
                        status.output?.let { println("🖼️  Expanded image: $it") }
                        return status
                    }
                    "failed" -> throw LightXExpandPhotoException("Photo expansion failed")
                    "init" -> {
                        attempts++
                        if (attempts < MAX_RETRIES) {
                            println("⏳ Waiting ${RETRY_INTERVAL / 1000} seconds before next check...")
                            delay(RETRY_INTERVAL)
                        }
                    }
                    else -> {
                        attempts++
                        if (attempts < MAX_RETRIES) {
                            delay(RETRY_INTERVAL)
                        }
                    }
                }
                
            } catch (e: Exception) {
                attempts++
                if (attempts >= MAX_RETRIES) {
                    throw e
                }
                println("⚠️  Error on attempt $attempts, retrying...")
                delay(RETRY_INTERVAL)
            }
        }
        
        throw LightXExpandPhotoException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and expand photo
     * @param imageFile File containing the image data
     * @param padding Padding configuration
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processExpansion(
        imageFile: File, 
        padding: PaddingConfig, 
        contentType: String = "image/jpeg"
    ): ExpandPhotoOrderStatusBody {
        println("🚀 Starting LightX AI Expand Photo API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Expand photo
        println("🖼️  Expanding photo with AI...")
        val orderId = expandPhoto(imageUrl, padding)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Create padding configuration for common expansion scenarios
     * @param direction 'horizontal', 'vertical', 'all', or 'custom'
     * @param amount Padding amount in pixels
     * @param customPadding Custom padding object (for 'custom' direction)
     * @return Padding configuration
     */
    fun createPaddingConfig(direction: String, amount: Int = 100, 
                          customPadding: PaddingConfig? = null): PaddingConfig {
        val config = when (direction) {
            "horizontal" -> PaddingConfig.horizontal(amount)
            "vertical" -> PaddingConfig.vertical(amount)
            "all" -> PaddingConfig.all(amount)
            "custom" -> customPadding ?: throw IllegalArgumentException("customPadding must be provided for 'custom' direction")
            else -> throw IllegalArgumentException("Invalid direction: $direction. Use 'horizontal', 'vertical', 'all', or 'custom'")
        }
        
        println("📐 Created $direction padding config: $config")
        return config
    }
    
    /**
     * Expand photo to specific aspect ratio
     * @param imageFile File containing the image data
     * @param targetWidth Target width
     * @param targetHeight Target height
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun expandToAspectRatio(
        imageFile: File, 
        targetWidth: Int, 
        targetHeight: Int, 
        contentType: String = "image/jpeg"
    ): ExpandPhotoOrderStatusBody {
        println("🎯 Expanding to aspect ratio: ${targetWidth}x${targetHeight}")
        println("⚠️  Note: This requires original image dimensions to calculate padding")
        
        // For demonstration, we'll use equal padding
        // In a real implementation, you'd calculate the required padding based on
        // the original image dimensions and target aspect ratio
        val padding = createPaddingConfig("all", 100)
        return processExpansion(imageFile, padding, contentType)
    }
    
    /**
     * Get image dimensions (utility function)
     * @param imageFile File containing the image data
     * @return Pair of (width, height)
     */
    fun getImageDimensions(imageFile: File): Pair<Int, Int> {
        return try {
            val image = ImageIO.read(imageFile)
            val width = image.width
            val height = image.height
            println("📏 Image dimensions: ${width}x${height}")
            Pair(width, height)
        } catch (e: Exception) {
            println("❌ Error getting image dimensions: ${e.message}")
            Pair(0, 0)
        }
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): ExpandPhotoUploadImageBody {
        val endpoint = "$BASE_URL/v2/uploadImageUrl"
        
        val requestBody = buildJsonObject {
            put("uploadType", "imageUrl")
            put("size", fileSize)
            put("contentType", contentType)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXExpandPhotoException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<ExpandPhotoUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXExpandPhotoException("Upload URL request failed: ${uploadResponse.message}")
        }
        
        return uploadResponse.body
    }
    
    private suspend fun uploadToS3(uploadUrl: String, imageFile: File, contentType: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl))
            .header("Content-Type", contentType)
            .PUT(HttpRequest.BodyPublishers.ofFile(imageFile.toPath()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXExpandPhotoException("Image upload failed: ${response.statusCode()}")
        }
    }
    
    /**
     * Close the HTTP client
     */
    fun close() {
        httpClient.close()
    }
}

// MARK: - Exception Classes

class LightXExpandPhotoException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXExpandPhotoExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXExpandPhotoAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/your/image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Example 1: Expand horizontally
            val horizontalPadding = lightx.createPaddingConfig("horizontal", 150)
            val result1 = lightx.processExpansion(
                imageFile = imageFile,
                padding = horizontalPadding,
                contentType = "image/jpeg"
            )
            println("🎉 Horizontal expansion result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Expand vertically
            val verticalPadding = lightx.createPaddingConfig("vertical", 200)
            val result2 = lightx.processExpansion(
                imageFile = imageFile,
                padding = verticalPadding,
                contentType = "image/jpeg"
            )
            println("🎉 Vertical expansion result:")
            println("Order ID: ${result2.orderId}")
            println("Status: ${result2.status}")
            result2.output?.let { println("Output: $it") }
            
            // Example 3: Expand all sides equally
            val allSidesPadding = lightx.createPaddingConfig("all", 100)
            val result3 = lightx.processExpansion(
                imageFile = imageFile,
                padding = allSidesPadding,
                contentType = "image/jpeg"
            )
            println("🎉 All-sides expansion result:")
            println("Order ID: ${result3.orderId}")
            println("Status: ${result3.status}")
            result3.output?.let { println("Output: $it") }
            
            // Example 4: Custom padding
            val customPadding = PaddingConfig(
                leftPadding = 50,
                rightPadding = 200,
                topPadding = 75,
                bottomPadding = 125
            )
            val result4 = lightx.processExpansion(
                imageFile = imageFile,
                padding = customPadding,
                contentType = "image/jpeg"
            )
            println("🎉 Custom expansion result:")
            println("Order ID: ${result4.orderId}")
            println("Status: ${result4.status}")
            result4.output?.let { println("Output: $it") }
            
            // Example 5: Get image dimensions
            val (width, height) = lightx.getImageDimensions(imageFile)
            if (width > 0 && height > 0) {
                println("📏 Original image: ${width}x${height}")
            }
            
        } catch (e: Exception) {
            println("❌ Example failed: ${e.message}")
        }
    }
}

// MARK: - Coroutine Extension Functions

/**
 * Extension function to run the example in a coroutine scope
 */
fun runLightXExpandPhotoExample() {
    runBlocking {
        LightXExpandPhotoExample.main(emptyArray())
    }
}
