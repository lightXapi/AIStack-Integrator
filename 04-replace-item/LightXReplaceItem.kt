/**
 * LightX AI Replace Item API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered item replacement functionality using text prompts and masks.
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
data class ReplaceItemUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: ReplaceItemUploadImageBody
)

@Serializable
data class ReplaceItemUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class ReplaceItemResponse(
    val statusCode: Int,
    val message: String,
    val body: ReplaceItemBody
)

@Serializable
data class ReplaceItemBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class ReplaceItemOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: ReplaceItemOrderStatusBody
)

@Serializable
data class ReplaceItemOrderStatusBody(
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

// MARK: - LightX Replace Item API Client

class LightXReplaceAPI(private val apiKey: String) {
    
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
            throw LightXReplaceException("Image size exceeds 5MB limit")
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
     * Replace item using AI and text prompt
     * @param imageUrl URL of the original image
     * @param maskedImageUrl URL of the mask image
     * @param textPrompt Text prompt describing what to replace with
     * @return Order ID for tracking
     */
    suspend fun replaceItem(imageUrl: String, maskedImageUrl: String, textPrompt: String): String {
        val endpoint = "$BASE_URL/v1/replace"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("maskedImageUrl", maskedImageUrl)
            put("textPrompt", textPrompt)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXReplaceException("Network error: ${response.statusCode()}")
        }
        
        val replaceResponse = json.decodeFromString<ReplaceItemResponse>(response.body())
        
        if (replaceResponse.statusCode != 2000) {
            throw LightXReplaceException("Replace item request failed: ${replaceResponse.message}")
        }
        
        val orderInfo = replaceResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("💬 Text prompt: \"$textPrompt\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): ReplaceItemOrderStatusBody {
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
            throw LightXReplaceException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<ReplaceItemOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXReplaceException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): ReplaceItemOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Item replacement completed successfully!")
                        status.output?.let { println("🖼️  Replaced image: $it") }
                        return status
                    }
                    "failed" -> throw LightXReplaceException("Item replacement failed")
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
        
        throw LightXReplaceException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and replace item
     * @param originalImageFile Original image file
     * @param maskImageFile Mask image file
     * @param textPrompt Text prompt for replacement
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processReplacement(
        originalImageFile: File, 
        maskImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): ReplaceItemOrderStatusBody {
        println("🚀 Starting LightX AI Replace API workflow...")
        
        // Step 1: Upload both images
        println("📤 Uploading images...")
        val (originalUrl, maskUrl) = uploadImages(originalImageFile, maskImageFile, contentType)
        println("✅ Original image uploaded: $originalUrl")
        println("✅ Mask image uploaded: $maskUrl")
        
        // Step 2: Replace item
        println("🔄 Replacing item with AI...")
        val orderId = replaceItem(originalUrl, maskUrl, textPrompt)
        
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
        
        // Draw white rectangles for areas to replace
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
    
    /**
     * Get common text prompts for different replacement scenarios
     * @param category Category of replacement
     * @return List of suggested prompts
     */
    fun getSuggestedPrompts(category: String): List<String> {
        val promptSuggestions = mapOf(
            "face" to listOf(
                "a young woman with blonde hair",
                "an elderly man with a beard",
                "a smiling child",
                "a professional businessman",
                "a person wearing glasses"
            ),
            "clothing" to listOf(
                "a red dress",
                "a blue suit",
                "a casual t-shirt",
                "a winter jacket",
                "a formal shirt"
            ),
            "objects" to listOf(
                "a modern smartphone",
                "a vintage car",
                "a beautiful flower",
                "a wooden chair",
                "a glass vase"
            ),
            "background" to listOf(
                "a beach scene",
                "a mountain landscape",
                "a modern office",
                "a cozy living room",
                "a garden setting"
            )
        )
        
        val prompts = promptSuggestions[category] ?: emptyList()
        println("💡 Suggested prompts for $category: $prompts")
        return prompts
    }
    
    /**
     * Validate text prompt (utility function)
     * @param textPrompt Text prompt to validate
     * @return Whether the prompt is valid
     */
    fun validateTextPrompt(textPrompt: String): Boolean {
        if (textPrompt.trim().isEmpty()) {
            println("❌ Text prompt cannot be empty")
            return false
        }
        
        if (textPrompt.length > 500) {
            println("❌ Text prompt is too long (max 500 characters)")
            return false
        }
        
        println("✅ Text prompt is valid")
        return true
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): ReplaceItemUploadImageBody {
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
            throw LightXReplaceException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<ReplaceItemUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXReplaceException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXReplaceException("Image upload failed: ${response.statusCode()}")
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

class LightXReplaceException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXReplaceExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXReplaceAPI("YOUR_API_KEY_HERE")
            
            val originalImageFile = File("path/to/original-image.jpg")
            val maskImageFile = File("path/to/mask-image.png")
            
            if (!originalImageFile.exists() || !maskImageFile.exists()) {
                println("❌ Image files not found")
                return
            }
            
            // Example 1: Replace a face
            val facePrompts = lightx.getSuggestedPrompts("face")
            val result1 = lightx.processReplacement(
                originalImageFile = originalImageFile,
                maskImageFile = maskImageFile,
                textPrompt = facePrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Face replacement result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Replace clothing
            val clothingPrompts = lightx.getSuggestedPrompts("clothing")
            val result2 = lightx.processReplacement(
                originalImageFile = originalImageFile,
                maskImageFile = maskImageFile,
                textPrompt = clothingPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Clothing replacement result:")
            println("Order ID: ${result2.orderId}")
            println("Status: ${result2.status}")
            result2.output?.let { println("Output: $it") }
            
            // Example 3: Replace background
            val backgroundPrompts = lightx.getSuggestedPrompts("background")
            val result3 = lightx.processReplacement(
                originalImageFile = originalImageFile,
                maskImageFile = maskImageFile,
                textPrompt = backgroundPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Background replacement result:")
            println("Order ID: ${result3.orderId}")
            println("Status: ${result3.status}")
            result3.output?.let { println("Output: $it") }
            
            // Example 4: Create mask from coordinates and process
            // val (width, height) = lightx.getImageDimensions(originalImageFile)
            // if (width > 0 && height > 0) {
            //     val maskFile = lightx.createMaskFromCoordinates(
            //         width = width, height = height,
            //         coordinates = listOf(
            //             MaskCoordinate(100, 100, 200, 150),  // Area to replace
            //             MaskCoordinate(400, 300, 100, 100)   // Another area to replace
            //         )
            //     )
            //     
            //     val result = lightx.processReplacement(
            //         originalImageFile = originalImageFile,
            //         maskImageFile = maskFile,
            //         textPrompt = "a beautiful sunset",
            //         contentType = "image/jpeg"
            //     )
            //     println("🎉 Replacement with generated mask:")
            //     println("Order ID: ${result.orderId}")
            //     println("Status: ${result.status}")
            //     result.output?.let { println("Output: $it") }
            // }
            
        } catch (e: Exception) {
            println("❌ Example failed: ${e.message}")
        }
    }
}

// MARK: - Coroutine Extension Functions

/**
 * Extension function to run the example in a coroutine scope
 */
fun runLightXReplaceExample() {
    runBlocking {
        LightXReplaceExample.main(emptyArray())
    }
}
