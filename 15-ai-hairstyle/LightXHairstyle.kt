/**
 * LightX AI Hairstyle API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hairstyle transformation functionality.
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
data class HairstyleUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: HairstyleUploadImageBody
)

@Serializable
data class HairstyleUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class HairstyleGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: HairstyleGenerationBody
)

@Serializable
data class HairstyleGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class HairstyleOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: HairstyleOrderStatusBody
)

@Serializable
data class HairstyleOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Hairstyle API Client

class LightXHairstyleAPI(private val apiKey: String) {
    
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
            throw LightXHairstyleException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Generate hairstyle transformation
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for hairstyle description
     * @return Order ID for tracking
     */
    suspend fun generateHairstyle(imageUrl: String, textPrompt: String): String {
        val endpoint = "$BASE_URL/v1/hairstyle"
        
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
            throw LightXHairstyleException("Network error: ${response.statusCode()}")
        }
        
        val hairstyleResponse = json.decodeFromString<HairstyleGenerationResponse>(response.body())
        
        if (hairstyleResponse.statusCode != 2000) {
            throw LightXHairstyleException("Hairstyle request failed: ${hairstyleResponse.message}")
        }
        
        val orderInfo = hairstyleResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("💇 Hairstyle prompt: \"$textPrompt\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): HairstyleOrderStatusBody {
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
            throw LightXHairstyleException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<HairstyleOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXHairstyleException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): HairstyleOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Hairstyle transformation completed successfully!")
                        status.output?.let { println("💇 New hairstyle: $it") }
                        return status
                    }
                    "failed" -> throw LightXHairstyleException("Hairstyle transformation failed")
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
        
        throw LightXHairstyleException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and generate hairstyle transformation
     * @param imageFile Image file
     * @param textPrompt Text prompt for hairstyle description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processHairstyleGeneration(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): HairstyleOrderStatusBody {
        println("🚀 Starting LightX AI Hairstyle API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Generate hairstyle transformation
        println("💇 Generating hairstyle transformation...")
        val orderId = generateHairstyle(imageUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get hairstyle transformation tips and best practices
     * @return Map of tips for better results
     */
    fun getHairstyleTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the person's face and current hair are clearly visible",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good face visibility improves hairstyle transformation results"
            ),
            "text_prompts" to listOf(
                "Be specific about the hairstyle you want to try",
                "Mention hair length, style, and characteristics",
                "Include details about hair color, texture, and cut",
                "Describe the overall look and feel you're going for",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Hairstyle transformation works best with clear face photos",
                "Results may vary based on input image quality",
                "Text prompts guide the hairstyle generation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different hairstyle descriptions"
            )
        )
        
        println("💡 Hairstyle Transformation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get hairstyle style suggestions
     * @return Map of hairstyle suggestions
     */
    fun getHairstyleSuggestions(): Map<String, List<String>> {
        val hairstyleSuggestions = mapOf(
            "short_styles" to listOf(
                "pixie cut with side-swept bangs",
                "short bob with layers",
                "buzz cut with fade",
                "short curly afro",
                "asymmetrical short cut"
            ),
            "medium_styles" to listOf(
                "shoulder-length layered cut",
                "medium bob with waves",
                "lob (long bob) with face-framing layers",
                "medium length with curtain bangs",
                "shoulder-length with subtle highlights"
            ),
            "long_styles" to listOf(
                "long flowing waves",
                "straight long hair with center part",
                "long layered cut with side bangs",
                "long hair with beachy waves",
                "long hair with balayage highlights"
            ),
            "curly_styles" to listOf(
                "natural curly afro",
                "loose beachy waves",
                "tight spiral curls",
                "wavy bob with natural texture",
                "curly hair with defined ringlets"
            ),
            "trendy_styles" to listOf(
                "modern shag cut with layers",
                "wolf cut with textured ends",
                "butterfly cut with face-framing layers",
                "mullet with modern styling",
                "bixie cut (bob-pixie hybrid)"
            )
        )
        
        println("💡 Hairstyle Style Suggestions:")
        for ((category, suggestionList) in hairstyleSuggestions) {
            println("$category: $suggestionList")
        }
        return hairstyleSuggestions
    }
    
    /**
     * Get hairstyle prompt examples
     * @return Map of prompt examples
     */
    fun getHairstylePromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "classic" to listOf(
                "Classic bob haircut with clean lines",
                "Traditional pixie cut with side part",
                "Classic long layers with subtle waves",
                "Timeless shoulder-length cut with bangs",
                "Classic short back and sides with longer top"
            ),
            "modern" to listOf(
                "Modern shag cut with textured layers",
                "Contemporary lob with face-framing highlights",
                "Trendy wolf cut with choppy ends",
                "Modern pixie with asymmetrical styling",
                "Contemporary long hair with curtain bangs"
            ),
            "casual" to listOf(
                "Casual beachy waves for everyday wear",
                "Relaxed shoulder-length cut with natural texture",
                "Easy-care short bob with minimal styling",
                "Casual long hair with loose waves",
                "Low-maintenance pixie with natural movement"
            ),
            "formal" to listOf(
                "Elegant updo with sophisticated styling",
                "Formal bob with sleek, polished finish",
                "Classic long hair styled for special occasions",
                "Professional short cut with refined styling",
                "Elegant shoulder-length cut with smooth finish"
            ),
            "creative" to listOf(
                "Bold asymmetrical cut with dramatic angles",
                "Creative color-blocked hairstyle",
                "Artistic pixie with unique styling",
                "Dramatic long layers with bold highlights",
                "Creative short cut with geometric styling"
            )
        )
        
        println("💡 Hairstyle Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
    }
    
    /**
     * Get face shape hairstyle recommendations
     * @return Map of face shape recommendations
     */
    fun getFaceShapeRecommendations(): Map<String, Map<String, Any>> {
        val faceShapeRecommendations = mapOf(
            "oval" to mapOf(
                "description" to "Most versatile face shape - can pull off most hairstyles",
                "recommended" to listOf("Long layers", "Pixie cuts", "Bob cuts", "Side-swept bangs", "Any length works well"),
                "avoid" to listOf("Heavy bangs that cover forehead", "Styles that add width to face")
            ),
            "round" to mapOf(
                "description" to "Face is as wide as it is long with soft, curved lines",
                "recommended" to listOf("Long layers", "Asymmetrical cuts", "Side parts", "Height at crown", "Angular cuts"),
                "avoid" to listOf("Short, rounded cuts", "Center parts", "Full bangs", "Styles that add width")
            ),
            "square" to mapOf(
                "description" to "Strong jawline with angular features",
                "recommended" to listOf("Soft layers", "Side-swept bangs", "Longer styles", "Rounded cuts", "Texture and movement"),
                "avoid" to listOf("Sharp, angular cuts", "Straight-across bangs", "Very short cuts")
            ),
            "heart" to mapOf(
                "description" to "Wider at forehead, narrower at chin",
                "recommended" to listOf("Chin-length cuts", "Side-swept bangs", "Layered styles", "Volume at chin level"),
                "avoid" to listOf("Very short cuts", "Heavy bangs", "Styles that add width at top")
            ),
            "long" to mapOf(
                "description" to "Face is longer than it is wide",
                "recommended" to listOf("Shorter cuts", "Side parts", "Layers", "Bangs", "Width-adding styles"),
                "avoid" to listOf("Very long, straight styles", "Center parts", "Height at crown")
            )
        )
        
        println("💡 Face Shape Hairstyle Recommendations:")
        for ((shape, info) in faceShapeRecommendations) {
            println("$shape: ${info["description"]}")
            val recommended = info["recommended"] as? List<String>
            recommended?.let { println("  Recommended: ${it.joinToString(", ")}") }
            val avoid = info["avoid"] as? List<String>
            avoid?.let { println("  Avoid: ${it.joinToString(", ")}") }
        }
        return faceShapeRecommendations
    }
    
    /**
     * Get hair type styling tips
     * @return Map of hair type tips
     */
    fun getHairTypeTips(): Map<String, Map<String, Any>> {
        val hairTypeTips = mapOf(
            "straight" to mapOf(
                "characteristics" to "Smooth, lacks natural curl or wave",
                "styling_tips" to listOf("Layers add movement", "Blunt cuts work well", "Texture can be added with styling"),
                "best_styles" to listOf("Blunt bob", "Long layers", "Pixie cuts", "Straight-across bangs")
            ),
            "wavy" to mapOf(
                "characteristics" to "Natural S-shaped waves",
                "styling_tips" to listOf("Enhance natural texture", "Layers work beautifully", "Avoid over-straightening"),
                "best_styles" to listOf("Layered cuts", "Beachy waves", "Shoulder-length styles", "Natural texture cuts")
            ),
            "curly" to mapOf(
                "characteristics" to "Natural spiral or ringlet formation",
                "styling_tips" to listOf("Work with natural curl pattern", "Avoid heavy layers", "Moisture is key"),
                "best_styles" to listOf("Curly bobs", "Natural afro", "Layered curls", "Curly pixie cuts")
            ),
            "coily" to mapOf(
                "characteristics" to "Tight, springy curls or coils",
                "styling_tips" to listOf("Embrace natural texture", "Regular moisture needed", "Protective styles work well"),
                "best_styles" to listOf("Natural afro", "Twist-outs", "Bantu knots", "Protective braided styles")
            )
        )
        
        println("💡 Hair Type Styling Tips:")
        for ((hairType, info) in hairTypeTips) {
            println("$hairType: ${info["characteristics"]}")
            val stylingTips = info["styling_tips"] as? List<String>
            stylingTips?.let { println("  Styling tips: ${it.joinToString(", ")}") }
            val bestStyles = info["best_styles"] as? List<String>
            bestStyles?.let { println("  Best styles: ${it.joinToString(", ")}") }
        }
        return hairTypeTips
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
     * Generate hairstyle with prompt validation
     * @param imageFile Image file
     * @param textPrompt Text prompt for hairstyle description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateHairstyleWithValidation(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): HairstyleOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXHairstyleException("Invalid text prompt")
        }
        
        return processHairstyleGeneration(imageFile, textPrompt, contentType)
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): HairstyleUploadImageBody {
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
            throw LightXHairstyleException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<HairstyleUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXHairstyleException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXHairstyleException("Image upload failed: ${response.statusCode()}")
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

class LightXHairstyleException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXHairstyleExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXHairstyleAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getHairstyleTips()
            lightx.getHairstyleSuggestions()
            lightx.getHairstylePromptExamples()
            lightx.getFaceShapeRecommendations()
            lightx.getHairTypeTips()
            
            // Example 1: Try a classic bob hairstyle
            val result1 = lightx.generateHairstyleWithValidation(
                imageFile = imageFile,
                textPrompt = "Classic bob haircut with clean lines and side part",
                contentType = "image/jpeg"
            )
            println("🎉 Classic bob result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Try a modern pixie cut
            val result2 = lightx.generateHairstyleWithValidation(
                imageFile = imageFile,
                textPrompt = "Modern pixie cut with asymmetrical styling and texture",
                contentType = "image/jpeg"
            )
            println("🎉 Modern pixie result:")
            println("Order ID: ${result2.orderId}")
            println("Status: ${result2.status}")
            result2.output?.let { println("Output: $it") }
            
            // Example 3: Try different hairstyles
            val hairstyles = listOf(
                "Long flowing waves with natural texture",
                "Shoulder-length layered cut with curtain bangs",
                "Short curly afro with natural texture",
                "Beachy waves with sun-kissed highlights"
            )
            
            for (hairstyle in hairstyles) {
                val result = lightx.generateHairstyleWithValidation(
                    imageFile = imageFile,
                    textPrompt = hairstyle,
                    contentType = "image/jpeg"
                )
                println("🎉 $hairstyle result:")
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
fun runLightXHairstyleExample() {
    runBlocking {
        LightXHairstyleExample.main(emptyArray())
    }
}
