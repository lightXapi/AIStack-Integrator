/**
 * LightX AI Background Generator API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered background generation functionality.
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
data class BackgroundGeneratorUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: BackgroundGeneratorUploadImageBody
)

@Serializable
data class BackgroundGeneratorUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class BackgroundGeneratorGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: BackgroundGeneratorGenerationBody
)

@Serializable
data class BackgroundGeneratorGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class BackgroundGeneratorOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: BackgroundGeneratorOrderStatusBody
)

@Serializable
data class BackgroundGeneratorOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Background Generator API Client

class LightXBackgroundGeneratorAPI(private val apiKey: String) {
    
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
            throw LightXBackgroundGeneratorException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Generate background
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for background generation
     * @return Order ID for tracking
     */
    suspend fun generateBackground(imageUrl: String, textPrompt: String): String {
        val endpoint = "$BASE_URL/v1/background-generator"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
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
            throw LightXBackgroundGeneratorException("Network error: ${response.statusCode()}")
        }
        
        val backgroundResponse = json.decodeFromString<BackgroundGeneratorGenerationResponse>(response.body())
        
        if (backgroundResponse.statusCode != 2000) {
            throw LightXBackgroundGeneratorException("Background generation request failed: ${backgroundResponse.message}")
        }
        
        val orderInfo = backgroundResponse.body
        
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
    suspend fun checkOrderStatus(orderId: String): BackgroundGeneratorOrderStatusBody {
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
            throw LightXBackgroundGeneratorException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<BackgroundGeneratorOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXBackgroundGeneratorException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): BackgroundGeneratorOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Background generation completed successfully!")
                        status.output?.let { println("🖼️ Background image: $it") }
                        return status
                    }
                    "failed" -> throw LightXBackgroundGeneratorException("Background generation failed")
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
        
        throw LightXBackgroundGeneratorException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and generate background
     * @param imageFile Image file
     * @param textPrompt Text prompt for background generation
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processBackgroundGeneration(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): BackgroundGeneratorOrderStatusBody {
        println("🚀 Starting LightX AI Background Generator API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Generate background
        println("🖼️ Generating background...")
        val orderId = generateBackground(imageUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get background generation tips and best practices
     * @return Map of tips for better background results
     */
    fun getBackgroundTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the subject is clearly visible and centered",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good subject separation improves background generation"
            ),
            "text_prompts" to listOf(
                "Be specific about the background style you want",
                "Mention color schemes and mood preferences",
                "Include environmental details (indoor, outdoor, studio)",
                "Specify lighting preferences (natural, dramatic, soft)",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Background generation works best with clear subjects",
                "Results may vary based on input image quality",
                "Text prompts guide the background generation process",
                "Allow 15-30 seconds for processing",
                "Experiment with different prompt styles for variety"
            )
        )
        
        println("💡 Background Generation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get background style suggestions
     * @return Map of background style suggestions
     */
    fun getBackgroundStyleSuggestions(): Map<String, List<String>> {
        val styleSuggestions = mapOf(
            "professional" to listOf(
                "professional office background",
                "clean minimalist workspace",
                "modern corporate environment",
                "elegant business setting",
                "sophisticated professional space"
            ),
            "natural" to listOf(
                "natural outdoor landscape",
                "beautiful garden background",
                "scenic mountain view",
                "peaceful forest setting",
                "serene beach environment"
            ),
            "creative" to listOf(
                "artistic studio background",
                "creative workspace environment",
                "colorful abstract background",
                "modern art gallery setting",
                "inspiring creative space"
            ),
            "lifestyle" to listOf(
                "cozy home interior",
                "modern living room",
                "stylish bedroom setting",
                "contemporary kitchen",
                "elegant dining room"
            ),
            "outdoor" to listOf(
                "urban cityscape background",
                "park environment",
                "outdoor cafe setting",
                "street scene background",
                "architectural landmark view"
            )
        )
        
        println("💡 Background Style Suggestions:")
        for ((category, suggestionList) in styleSuggestions) {
            println("$category: $suggestionList")
        }
        return styleSuggestions
    }
    
    /**
     * Get background prompt examples
     * @return Map of prompt examples
     */
    fun getBackgroundPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "professional" to listOf(
                "Modern office with glass windows and city view",
                "Clean white studio background with soft lighting",
                "Professional conference room with wooden furniture",
                "Contemporary workspace with minimalist design",
                "Elegant business environment with neutral colors"
            ),
            "natural" to listOf(
                "Beautiful sunset over mountains in the background",
                "Lush green garden with blooming flowers",
                "Peaceful lake with reflection of trees",
                "Golden hour lighting in a forest setting",
                "Serene beach with gentle waves and palm trees"
            ),
            "creative" to listOf(
                "Artistic studio with colorful paint splashes",
                "Modern gallery with white walls and track lighting",
                "Creative workspace with vintage furniture",
                "Abstract colorful background with geometric shapes",
                "Bohemian style room with eclectic decorations"
            ),
            "lifestyle" to listOf(
                "Cozy living room with warm lighting and books",
                "Modern kitchen with marble countertops",
                "Stylish bedroom with soft natural light",
                "Contemporary dining room with elegant table",
                "Comfortable home office with plants and books"
            ),
            "outdoor" to listOf(
                "Urban cityscape with modern skyscrapers",
                "Charming street with cafes and shops",
                "Park setting with trees and walking paths",
                "Historic architecture with classical columns",
                "Modern outdoor space with contemporary design"
            )
        )
        
        println("💡 Background Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
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
     * Generate background with prompt validation
     * @param imageFile Image file
     * @param textPrompt Text prompt for background generation
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateBackgroundWithPrompt(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): BackgroundGeneratorOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXBackgroundGeneratorException("Invalid text prompt")
        }
        
        return processBackgroundGeneration(imageFile, textPrompt, contentType)
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): BackgroundGeneratorUploadImageBody {
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
            throw LightXBackgroundGeneratorException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<BackgroundGeneratorUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXBackgroundGeneratorException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXBackgroundGeneratorException("Image upload failed: ${response.statusCode()}")
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

class LightXBackgroundGeneratorException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXBackgroundGeneratorExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXBackgroundGeneratorAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getBackgroundTips()
            lightx.getBackgroundStyleSuggestions()
            lightx.getBackgroundPromptExamples()
            
            // Example 1: Generate background with professional style
            val professionalPrompts = lightx.getBackgroundStyleSuggestions()["professional"] ?: emptyList()
            val result1 = lightx.generateBackgroundWithPrompt(
                imageFile = imageFile,
                textPrompt = professionalPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Professional background result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Generate background with natural style
            val naturalPrompts = lightx.getBackgroundStyleSuggestions()["natural"] ?: emptyList()
            val result2 = lightx.generateBackgroundWithPrompt(
                imageFile = imageFile,
                textPrompt = naturalPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Natural background result:")
            println("Order ID: ${result2.orderId}")
            println("Status: ${result2.status}")
            result2.output?.let { println("Output: $it") }
            
            // Example 3: Generate backgrounds for different styles
            val styles = listOf("professional", "natural", "creative", "lifestyle", "outdoor")
            for (style in styles) {
                val prompts = lightx.getBackgroundStyleSuggestions()[style] ?: emptyList()
                val result = lightx.generateBackgroundWithPrompt(
                    imageFile = imageFile,
                    textPrompt = prompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 $style background result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 4: Get image dimensions
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
fun runLightXBackgroundGeneratorExample() {
    runBlocking {
        LightXBackgroundGeneratorExample.main(emptyArray())
    }
}
