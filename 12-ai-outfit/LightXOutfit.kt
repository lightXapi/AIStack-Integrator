/**
 * LightX AI Outfit API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered outfit changing functionality.
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
data class OutfitUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: OutfitUploadImageBody
)

@Serializable
data class OutfitUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class OutfitGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: OutfitGenerationBody
)

@Serializable
data class OutfitGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class OutfitOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: OutfitOrderStatusBody
)

@Serializable
data class OutfitOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Outfit API Client

class LightXOutfitAPI(private val apiKey: String) {
    
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
            throw LightXOutfitException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Generate outfit change
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for outfit description
     * @return Order ID for tracking
     */
    suspend fun generateOutfit(imageUrl: String, textPrompt: String): String {
        val endpoint = "$BASE_URL/v1/outfit"
        
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
            throw LightXOutfitException("Network error: ${response.statusCode()}")
        }
        
        val outfitResponse = json.decodeFromString<OutfitGenerationResponse>(response.body())
        
        if (outfitResponse.statusCode != 2000) {
            throw LightXOutfitException("Outfit generation request failed: ${outfitResponse.message}")
        }
        
        val orderInfo = outfitResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("👗 Outfit prompt: \"$textPrompt\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): OutfitOrderStatusBody {
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
            throw LightXOutfitException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<OutfitOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXOutfitException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): OutfitOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Outfit generation completed successfully!")
                        status.output?.let { println("👗 Outfit result: $it") }
                        return status
                    }
                    "failed" -> throw LightXOutfitException("Outfit generation failed")
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
        
        throw LightXOutfitException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and generate outfit
     * @param imageFile Image file
     * @param textPrompt Text prompt for outfit description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processOutfitGeneration(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): OutfitOrderStatusBody {
        println("🚀 Starting LightX AI Outfit API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Generate outfit
        println("👗 Generating outfit...")
        val orderId = generateOutfit(imageUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get outfit generation tips and best practices
     * @return Map of tips for better outfit results
     */
    fun getOutfitTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the person is clearly visible and centered",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good body visibility improves outfit generation"
            ),
            "text_prompts" to listOf(
                "Be specific about the outfit style you want",
                "Mention clothing items (shirt, dress, jacket, etc.)",
                "Include color preferences and patterns",
                "Specify the occasion (casual, formal, party, etc.)",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Outfit generation works best with clear human subjects",
                "Results may vary based on input image quality",
                "Text prompts guide the outfit generation process",
                "Allow 15-30 seconds for processing",
                "Experiment with different outfit styles for variety"
            )
        )
        
        println("💡 Outfit Generation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get outfit style suggestions
     * @return Map of outfit style suggestions
     */
    fun getOutfitStyleSuggestions(): Map<String, List<String>> {
        val styleSuggestions = mapOf(
            "professional" to listOf(
                "professional business suit",
                "formal office attire",
                "corporate blazer and dress pants",
                "elegant business dress",
                "sophisticated work outfit"
            ),
            "casual" to listOf(
                "casual jeans and t-shirt",
                "relaxed weekend outfit",
                "comfortable everyday wear",
                "casual summer dress",
                "laid-back street style"
            ),
            "formal" to listOf(
                "elegant evening gown",
                "formal tuxedo",
                "cocktail party dress",
                "black tie attire",
                "sophisticated formal wear"
            ),
            "sporty" to listOf(
                "athletic workout outfit",
                "sporty casual wear",
                "gym attire",
                "active lifestyle clothing",
                "comfortable sports outfit"
            ),
            "trendy" to listOf(
                "fashionable street style",
                "trendy modern outfit",
                "stylish contemporary wear",
                "fashion-forward ensemble",
                "chic trendy clothing"
            )
        )
        
        println("💡 Outfit Style Suggestions:")
        for ((category, suggestionList) in styleSuggestions) {
            println("$category: $suggestionList")
        }
        return styleSuggestions
    }
    
    /**
     * Get outfit prompt examples
     * @return Map of prompt examples
     */
    fun getOutfitPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "professional" to listOf(
                "Professional navy blue business suit with white shirt",
                "Elegant black blazer with matching dress pants",
                "Corporate dress in neutral colors",
                "Formal office attire with blouse and skirt",
                "Business casual outfit with cardigan and slacks"
            ),
            "casual" to listOf(
                "Casual blue jeans with white cotton t-shirt",
                "Relaxed summer dress in floral pattern",
                "Comfortable hoodie with denim jeans",
                "Casual weekend outfit with sneakers",
                "Lay-back style with comfortable clothing"
            ),
            "formal" to listOf(
                "Elegant black evening gown with accessories",
                "Formal tuxedo with bow tie",
                "Cocktail dress in deep red color",
                "Black tie formal wear",
                "Sophisticated formal attire for special occasion"
            ),
            "sporty" to listOf(
                "Athletic leggings with sports bra and sneakers",
                "Gym outfit with tank top and shorts",
                "Active wear for running and exercise",
                "Sporty casual outfit for outdoor activities",
                "Comfortable athletic clothing"
            ),
            "trendy" to listOf(
                "Fashionable street style with trendy accessories",
                "Modern outfit with contemporary fashion elements",
                "Stylish ensemble with current fashion trends",
                "Chic trendy clothing with fashionable details",
                "Fashion-forward outfit with modern styling"
            )
        )
        
        println("💡 Outfit Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
    }
    
    /**
     * Get outfit use cases and examples
     * @return Map of use case examples
     */
    fun getOutfitUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "fashion" to listOf(
                "Virtual try-on for e-commerce",
                "Fashion styling and recommendations",
                "Outfit planning and coordination",
                "Style inspiration and ideas",
                "Fashion trend visualization"
            ),
            "retail" to listOf(
                "Online shopping experience enhancement",
                "Product visualization and styling",
                "Customer engagement and interaction",
                "Virtual fitting room technology",
                "Personalized fashion recommendations"
            ),
            "social" to listOf(
                "Social media content creation",
                "Fashion blogging and influencers",
                "Style sharing and inspiration",
                "Outfit of the day posts",
                "Fashion community engagement"
            ),
            "personal" to listOf(
                "Personal style exploration",
                "Wardrobe planning and organization",
                "Outfit coordination and matching",
                "Style experimentation",
                "Fashion confidence building"
            )
        )
        
        println("💡 Outfit Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
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
     * Generate outfit with prompt validation
     * @param imageFile Image file
     * @param textPrompt Text prompt for outfit description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateOutfitWithPrompt(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): OutfitOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXOutfitException("Invalid text prompt")
        }
        
        return processOutfitGeneration(imageFile, textPrompt, contentType)
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): OutfitUploadImageBody {
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
            throw LightXOutfitException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<OutfitUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXOutfitException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXOutfitException("Image upload failed: ${response.statusCode()}")
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

class LightXOutfitException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXOutfitExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXOutfitAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getOutfitTips()
            lightx.getOutfitStyleSuggestions()
            lightx.getOutfitPromptExamples()
            lightx.getOutfitUseCases()
            
            // Example 1: Generate professional outfit
            val professionalPrompts = lightx.getOutfitStyleSuggestions()["professional"] ?: emptyList()
            val result1 = lightx.generateOutfitWithPrompt(
                imageFile = imageFile,
                textPrompt = professionalPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Professional outfit result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Generate casual outfit
            val casualPrompts = lightx.getOutfitStyleSuggestions()["casual"] ?: emptyList()
            val result2 = lightx.generateOutfitWithPrompt(
                imageFile = imageFile,
                textPrompt = casualPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Casual outfit result:")
            println("Order ID: ${result2.orderId}")
            println("Status: ${result2.status}")
            result2.output?.let { println("Output: $it") }
            
            // Example 3: Generate outfits for different styles
            val styles = listOf("professional", "casual", "formal", "sporty", "trendy")
            for (style in styles) {
                val prompts = lightx.getOutfitStyleSuggestions()[style] ?: emptyList()
                val result = lightx.generateOutfitWithPrompt(
                    imageFile = imageFile,
                    textPrompt = prompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 $style outfit result:")
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
fun runLightXOutfitExample() {
    runBlocking {
        LightXOutfitExample.main(emptyArray())
    }
}
