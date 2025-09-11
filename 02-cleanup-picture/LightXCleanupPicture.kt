/**
 * LightX Cleanup Picture API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for image cleanup functionality using mask-based object removal.
 */

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.*
import java.net.*
import java.net.http.*
import java.time.Duration
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.Graphics2D
import javax.imageio.ImageIO

// MARK: - Data Classes

@Serializable
data class CleanupUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: CleanupUploadImageBody
)

@Serializable
data class CleanupUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class CleanupPictureResponse(
    val statusCode: Int,
    val message: String,
    val body: CleanupPictureBody
)

@Serializable
data class CleanupPictureBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class CleanupOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: CleanupOrderStatusBody
)

@Serializable
data class CleanupOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - Coordinate Data Class

data class MaskCoordinate(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

// MARK: - LightX Cleanup API Client

class LightXCleanupAPI(private val apiKey: String) {
    
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
            throw LightXCleanupException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Upload multiple images (original and mask)
     * @param originalImageFile Original image file
     * @param maskImageFile Mask image file
     * @param contentType MIME type
     * @return Pair of (originalURL, maskURL)
     */
    suspend fun uploadImages(originalImageFile: File, maskImageFile: File, 
                           contentType: String = "image/jpeg"): Pair<String, String> {
        println("📤 Uploading original image...")
        val originalUrl = uploadImage(originalImageFile, contentType)
        
        println("📤 Uploading mask image...")
        val maskUrl = uploadImage(maskImageFile, contentType)
        
        return Pair(originalUrl, maskUrl)
    }
    
    /**
     * Cleanup picture using mask
     * @param imageUrl URL of the original image
     * @param maskedImageUrl URL of the mask image
     * @return Order ID for tracking
     */
    suspend fun cleanupPicture(imageUrl: String, maskedImageUrl: String): String {
        val endpoint = "$BASE_URL/v1/cleanup-picture"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("maskedImageUrl", maskedImageUrl)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXCleanupException("Network error: ${response.statusCode()}")
        }
        
        val cleanupResponse = json.decodeFromString<CleanupPictureResponse>(response.body())
        
        if (cleanupResponse.statusCode != 2000) {
            throw LightXCleanupException("Cleanup picture request failed: ${cleanupResponse.message}")
        }
        
        val orderInfo = cleanupResponse.body
        
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
    suspend fun checkOrderStatus(orderId: String): CleanupOrderStatusBody {
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
            throw LightXCleanupException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<CleanupOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXCleanupException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): CleanupOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Picture cleanup completed successfully!")
                        status.output?.let { println("🖼️  Output image: $it") }
                        return status
                    }
                    "failed" -> throw LightXCleanupException("Picture cleanup failed")
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
        
        throw LightXCleanupException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and cleanup picture
     * @param originalImageFile Original image file
     * @param maskImageFile Mask image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processCleanup(
        originalImageFile: File, 
        maskImageFile: File, 
        contentType: String = "image/jpeg"
    ): CleanupOrderStatusBody {
        println("🚀 Starting LightX Cleanup Picture API workflow...")
        
        // Step 1: Upload both images
        println("📤 Uploading images...")
        val (originalUrl, maskUrl) = uploadImages(originalImageFile, maskImageFile, contentType)
        println("✅ Original image uploaded: $originalUrl")
        println("✅ Mask image uploaded: $maskUrl")
        
        // Step 2: Cleanup picture
        println("🧹 Cleaning up picture...")
        val orderId = cleanupPicture(originalUrl, maskUrl)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Create a simple mask from coordinates (utility function)
     * @param width Image width
     * @param height Image height
     * @param coordinates List of MaskCoordinate for white areas
     * @return File containing the mask image
     */
    fun createMaskFromCoordinates(width: Int, height: Int, coordinates: List<MaskCoordinate>): File {
        println("🎭 Creating mask from coordinates...")
        println("Image dimensions: ${width}x${height}")
        println("White areas: $coordinates")
        
        // Create black background
        val maskImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = maskImage.createGraphics()
        
        // Fill with black
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        
        // Draw white rectangles for areas to remove
        graphics.color = Color.WHITE
        for (coord in coordinates) {
            graphics.fillRect(coord.x, coord.y, coord.width, coord.height)
        }
        
        graphics.dispose()
        
        // Save mask to file
        val maskFile = File("generated_mask.png")
        ImageIO.write(maskImage, "PNG", maskFile)
        println("✅ Mask created and saved: ${maskFile.absolutePath}")
        
        return maskFile
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): CleanupUploadImageBody {
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
            throw LightXCleanupException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<CleanupUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXCleanupException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXCleanupException("Image upload failed: ${response.statusCode()}")
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

class LightXCleanupException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXCleanupExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXCleanupAPI("YOUR_API_KEY_HERE")
            
            // Option 1: Process with existing images
            val originalImageFile = File("path/to/original-image.jpg")
            val maskImageFile = File("path/to/mask-image.png")
            
            if (!originalImageFile.exists() || !maskImageFile.exists()) {
                println("❌ Image files not found")
                return
            }
            
            val result = lightx.processCleanup(
                originalImageFile = originalImageFile,
                maskImageFile = maskImageFile,
                contentType = "image/jpeg"
            )
            
            println("🎉 Final result:")
            println("Order ID: ${result.orderId}")
            println("Status: ${result.status}")
            result.output?.let { println("Output: $it") }
            
            // Option 2: Create mask from coordinates and process
            // val maskFile = lightx.createMaskFromCoordinates(
            //     width = 800, height = 600,
            //     coordinates = listOf(
            //         MaskCoordinate(100, 100, 200, 150),  // Area to remove
            //         MaskCoordinate(400, 300, 100, 100)   // Another area to remove
            //     )
            // )
            // 
            // val resultWithGeneratedMask = lightx.processCleanup(
            //     originalImageFile = originalImageFile,
            //     maskImageFile = maskFile,
            //     contentType = "image/jpeg"
            // )
            // 
            // println("🎉 Final result with generated mask:")
            // println("Order ID: ${resultWithGeneratedMask.orderId}")
            // println("Status: ${resultWithGeneratedMask.status}")
            // resultWithGeneratedMask.output?.let { println("Output: $it") }
            
        } catch (e: Exception) {
            println("❌ Example failed: ${e.message}")
        }
    }
}

// MARK: - Coroutine Extension Functions

/**
 * Extension function to run the example in a coroutine scope
 */
fun runLightXCleanupExample() {
    runBlocking {
        LightXCleanupExample.main(emptyArray())
    }
}
