/**
 * LightX AI Cartoon Character Generator API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered cartoon character generation functionality.
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
data class CartoonUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: CartoonUploadImageBody
)

@Serializable
data class CartoonUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class CartoonGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: CartoonGenerationBody
)

@Serializable
data class CartoonGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class CartoonOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: CartoonOrderStatusBody
)

@Serializable
data class CartoonOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Cartoon Character API Client

class LightXCartoonAPI(private val apiKey: String) {
    
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
            throw LightXCartoonException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Upload multiple images (input and optional style image)
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file (optional)
     * @param contentType MIME type
     * @return Pair of (inputURL, styleURL?)
     */
    suspend fun uploadImages(inputImageFile: File, styleImageFile: File? = null, 
                           contentType: String = "image/jpeg"): Pair<String, String?> {
        println("📤 Uploading input image...")
        val inputUrl = uploadImage(inputImageFile, contentType)
        
        val styleUrl = styleImageFile?.let { file ->
            println("📤 Uploading style image...")
            uploadImage(file, contentType)
        }
        
        return Pair(inputUrl, styleUrl)
    }
    
    /**
     * Generate cartoon character
     * @param imageUrl URL of the input image
     * @param styleImageUrl URL of the style image (optional)
     * @param textPrompt Text prompt for cartoon style (optional)
     * @return Order ID for tracking
     */
    suspend fun generateCartoon(imageUrl: String, styleImageUrl: String? = null, textPrompt: String? = null): String {
        val endpoint = "$BASE_URL/v1/cartoon"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            styleImageUrl?.let { put("styleImageUrl", it) }
            textPrompt?.let { put("textPrompt", it) }
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXCartoonException("Network error: ${response.statusCode()}")
        }
        
        val cartoonResponse = json.decodeFromString<CartoonGenerationResponse>(response.body())
        
        if (cartoonResponse.statusCode != 2000) {
            throw LightXCartoonException("Cartoon generation request failed: ${cartoonResponse.message}")
        }
        
        val orderInfo = cartoonResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        textPrompt?.let { println("💬 Text prompt: \"$it\"") }
        styleImageUrl?.let { println("🎨 Style image: $it") }
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): CartoonOrderStatusBody {
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
            throw LightXCartoonException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<CartoonOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXCartoonException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): CartoonOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Cartoon generation completed successfully!")
                        status.output?.let { println("🎨 Cartoon image: $it") }
                        return status
                    }
                    "failed" -> throw LightXCartoonException("Cartoon generation failed")
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
        
        throw LightXCartoonException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate cartoon
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file (optional)
     * @param textPrompt Text prompt for cartoon style (optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processCartoonGeneration(
        inputImageFile: File, 
        styleImageFile: File? = null, 
        textPrompt: String? = null, 
        contentType: String = "image/jpeg"
    ): CartoonOrderStatusBody {
        println("🚀 Starting LightX AI Cartoon Character Generator API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (inputUrl, styleUrl) = uploadImages(inputImageFile, styleImageFile, contentType)
        println("✅ Input image uploaded: $inputUrl")
        styleUrl?.let { println("✅ Style image uploaded: $it") }
        
        // Step 2: Generate cartoon
        println("🎨 Generating cartoon character...")
        val orderId = generateCartoon(inputUrl, styleUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get common text prompts for different cartoon styles
     * @param category Category of cartoon style
     * @return List of suggested prompts
     */
    fun getSuggestedPrompts(category: String): List<String> {
        val promptSuggestions = mapOf(
            "classic" to listOf(
                "classic Disney style cartoon",
                "vintage cartoon character",
                "traditional animation style",
                "classic comic book style",
                "retro cartoon character"
            ),
            "modern" to listOf(
                "modern anime style",
                "contemporary cartoon character",
                "digital art style",
                "modern illustration style",
                "stylized cartoon character"
            ),
            "artistic" to listOf(
                "watercolor cartoon style",
                "oil painting cartoon",
                "sketch cartoon style",
                "artistic cartoon character",
                "painterly cartoon style"
            ),
            "fun" to listOf(
                "cute and adorable cartoon",
                "funny cartoon character",
                "playful cartoon style",
                "whimsical cartoon character",
                "cheerful cartoon style"
            ),
            "professional" to listOf(
                "professional cartoon portrait",
                "business cartoon style",
                "corporate cartoon character",
                "formal cartoon style",
                "professional illustration"
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
     * Generate cartoon with text prompt only
     * @param inputImageFile Input image file
     * @param textPrompt Text prompt for cartoon style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateCartoonWithPrompt(
        inputImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): CartoonOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXCartoonException("Invalid text prompt")
        }
        
        return processCartoonGeneration(inputImageFile, null, textPrompt, contentType)
    }
    
    /**
     * Generate cartoon with style image only
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateCartoonWithStyle(
        inputImageFile: File, 
        styleImageFile: File, 
        contentType: String = "image/jpeg"
    ): CartoonOrderStatusBody {
        return processCartoonGeneration(inputImageFile, styleImageFile, null, contentType)
    }
    
    /**
     * Generate cartoon with both style image and text prompt
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param textPrompt Text prompt for cartoon style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateCartoonWithStyleAndPrompt(
        inputImageFile: File, 
        styleImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): CartoonOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXCartoonException("Invalid text prompt")
        }
        
        return processCartoonGeneration(inputImageFile, styleImageFile, textPrompt, contentType)
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): CartoonUploadImageBody {
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
            throw LightXCartoonException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<CartoonUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXCartoonException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXCartoonException("Image upload failed: ${response.statusCode()}")
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

class LightXCartoonException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXCartoonExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXCartoonAPI("YOUR_API_KEY_HERE")
            
            val inputImageFile = File("path/to/input-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg")
            
            if (!inputImageFile.exists()) {
                println("❌ Input image file not found: ${inputImageFile.absolutePath}")
                return
            }
            
            // Example 1: Generate cartoon with text prompt only
            val classicPrompts = lightx.getSuggestedPrompts("classic")
            val result1 = lightx.generateCartoonWithPrompt(
                inputImageFile = inputImageFile,
                textPrompt = classicPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Classic cartoon result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Generate cartoon with style image only
            if (styleImageFile.exists()) {
                val result2 = lightx.generateCartoonWithStyle(
                    inputImageFile = inputImageFile,
                    styleImageFile = styleImageFile,
                    contentType = "image/jpeg"
                )
                println("🎉 Style-based cartoon result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
                
                // Example 3: Generate cartoon with both style image and text prompt
                val modernPrompts = lightx.getSuggestedPrompts("modern")
                val result3 = lightx.generateCartoonWithStyleAndPrompt(
                    inputImageFile = inputImageFile,
                    styleImageFile = styleImageFile,
                    textPrompt = modernPrompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 Combined style and prompt result:")
                println("Order ID: ${result3.orderId}")
                println("Status: ${result3.status}")
                result3.output?.let { println("Output: $it") }
            }
            
            // Example 4: Generate cartoon with different style categories
            val categories = listOf("classic", "modern", "artistic", "fun", "professional")
            for (category in categories) {
                val prompts = lightx.getSuggestedPrompts(category)
                val result = lightx.generateCartoonWithPrompt(
                    inputImageFile = inputImageFile,
                    textPrompt = prompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 $category cartoon result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 5: Get image dimensions
            val (width, height) = lightx.getImageDimensions(inputImageFile)
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
fun runLightXCartoonExample() {
    runBlocking {
        LightXCartoonExample.main(emptyArray())
    }
}
