/**
 * LightX Remove Background API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for image upload and background removal functionality using Kotlin.
 */

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.*
import java.net.*
import java.net.http.*
import java.time.Duration
import java.util.concurrent.CompletableFuture

// MARK: - Data Classes

@Serializable
data class UploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: UploadImageBody
)

@Serializable
data class UploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class RemoveBackgroundResponse(
    val statusCode: Int,
    val message: String,
    val body: RemoveBackgroundBody
)

@Serializable
data class RemoveBackgroundBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class OrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: OrderStatusBody
)

@Serializable
data class OrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null,
    val mask: String? = null
)

// MARK: - LightX API Client

class LightXAPI(private val apiKey: String) {
    
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
            throw LightXException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Remove background from image
     * @param imageUrl URL of the uploaded image
     * @param background Background color, color code, or image URL
     * @return Order ID for tracking
     */
    suspend fun removeBackground(imageUrl: String, background: String = "transparent"): String {
        val endpoint = "$BASE_URL/v1/remove-background"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("background", background)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXException("Network error: ${response.statusCode()}")
        }
        
        val removeBackgroundResponse = json.decodeFromString<RemoveBackgroundResponse>(response.body())
        
        if (removeBackgroundResponse.statusCode != 2000) {
            throw LightXException("Background removal request failed: ${removeBackgroundResponse.message}")
        }
        
        val orderInfo = removeBackgroundResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): OrderStatusBody {
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
            throw LightXException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<OrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URLs
     */
    suspend fun waitForCompletion(orderId: String): OrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Background removal completed successfully!")
                        status.output?.let { println("🖼️  Output image: $it") }
                        status.mask?.let { println("🎭 Mask image: $it") }
                        return status
                    }
                    "failed" -> throw LightXException("Background removal failed")
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
        
        throw LightXException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and remove background
     * @param imageFile File containing the image data
     * @param background Background color/code/URL
     * @param contentType MIME type
     * @return Final result with output URLs
     */
    suspend fun processImage(
        imageFile: File, 
        background: String = "transparent", 
        contentType: String = "image/jpeg"
    ): OrderStatusBody {
        println("🚀 Starting LightX API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Remove background
        println("🎨 Removing background...")
        val orderId = removeBackground(imageUrl, background)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): UploadImageBody {
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
            throw LightXException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<UploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXException("Image upload failed: ${response.statusCode()}")
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

class LightXException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXAPI("YOUR_API_KEY_HERE")
            
            // Process an image file
            val imageFile = File("path/to/your/image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            val result = lightx.processImage(
                imageFile = imageFile,
                background = "white",
                contentType = "image/jpeg"
            )
            
            println("🎉 Final result:")
            println("Order ID: ${result.orderId}")
            println("Status: ${result.status}")
            result.output?.let { println("Output: $it") }
            result.mask?.let { println("Mask: $it") }
            
        } catch (e: Exception) {
            println("❌ Example failed: ${e.message}")
        }
    }
}

// MARK: - Coroutine Extension Functions

/**
 * Extension function to run the example in a coroutine scope
 */
fun runLightXExample() {
    runBlocking {
        LightXExample.main(emptyArray())
    }
}
