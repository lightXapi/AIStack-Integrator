/**
 * LightX AI Design API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered design generation functionality.
 */

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.*
import java.net.*
import java.net.http.*
import java.time.Duration

// MARK: - Data Classes

@Serializable
data class DesignGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: DesignGenerationBody
)

@Serializable
data class DesignGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class DesignOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: DesignOrderStatusBody
)

@Serializable
data class DesignOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX AI Design API Client

class LightXAIDesignAPI(private val apiKey: String) {
    
    companion object {
        private const val BASE_URL = "https://api.lightxeditor.com/external/api"
        private const val MAX_RETRIES = 5
        private const val RETRY_INTERVAL = 3000L // milliseconds
    }
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Generate AI design
     * @param textPrompt Text prompt for design description
     * @param resolution Design resolution (1:1, 9:16, 3:4, 2:3, 16:9, 4:3)
     * @param enhancePrompt Whether to enhance the prompt (default: true)
     * @return Order ID for tracking
     */
    suspend fun generateDesign(textPrompt: String, resolution: String = "1:1", enhancePrompt: Boolean = true): String {
        val endpoint = "$BASE_URL/v2/ai-design"
        
        val requestBody = buildJsonObject {
            put("textPrompt", textPrompt)
            put("resolution", resolution)
            put("enhancePrompt", enhancePrompt)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXDesignException("Network error: ${response.statusCode()}")
        }
        
        val designResponse = json.decodeFromString<DesignGenerationResponse>(response.body())
        
        if (designResponse.statusCode != 2000) {
            throw LightXDesignException("Design request failed: ${designResponse.message}")
        }
        
        val orderInfo = designResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🎨 Design prompt: \"$textPrompt\"")
        println("📐 Resolution: $resolution")
        println("✨ Enhanced prompt: ${if (enhancePrompt) "Yes" else "No"}")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): DesignOrderStatusBody {
        val endpoint = "$BASE_URL/v2/order-status"
        
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
            throw LightXDesignException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<DesignOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXDesignException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): DesignOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ AI design generated successfully!")
                        if (status.output != null) {
                            println("🎨 Design output: ${status.output}")
                        }
                        return status
                    }
                    "failed" -> {
                        throw LightXDesignException("AI design generation failed")
                    }
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
                
            } catch (e: LightXDesignException) {
                throw e
            } catch (e: Exception) {
                attempts++
                if (attempts >= MAX_RETRIES) {
                    throw LightXDesignException("Maximum retry attempts reached: ${e.message}")
                }
                println("⚠️  Error on attempt $attempts, retrying...")
                delay(RETRY_INTERVAL)
            }
        }
        
        throw LightXDesignException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Generate AI design and wait for completion
     * @param textPrompt Text prompt for design description
     * @param resolution Design resolution
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    suspend fun processDesign(textPrompt: String, resolution: String = "1:1", enhancePrompt: Boolean = true): DesignOrderStatusBody {
        println("🚀 Starting LightX AI Design API workflow...")
        
        // Step 1: Generate design
        println("🎨 Generating AI design...")
        val orderId = generateDesign(textPrompt, resolution, enhancePrompt)
        
        // Step 2: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get supported resolutions
     * @return Object containing supported resolutions
     */
    fun getSupportedResolutions(): Map<String, Map<String, String>> {
        val resolutions = mapOf(
            "1:1" to mapOf(
                "name" to "Square",
                "dimensions" to "512 × 512",
                "description" to "Perfect for social media posts, profile pictures, and square designs"
            ),
            "9:16" to mapOf(
                "name" to "Portrait (9:16)",
                "dimensions" to "289 × 512",
                "description" to "Ideal for mobile stories, vertical videos, and tall designs"
            ),
            "3:4" to mapOf(
                "name" to "Portrait (3:4)",
                "dimensions" to "386 × 512",
                "description" to "Great for portrait photos, magazine covers, and vertical layouts"
            ),
            "2:3" to mapOf(
                "name" to "Portrait (2:3)",
                "dimensions" to "341 × 512",
                "description" to "Perfect for posters, flyers, and portrait-oriented designs"
            ),
            "16:9" to mapOf(
                "name" to "Landscape (16:9)",
                "dimensions" to "512 × 289",
                "description" to "Ideal for banners, presentations, and widescreen designs"
            ),
            "4:3" to mapOf(
                "name" to "Landscape (4:3)",
                "dimensions" to "512 × 386",
                "description" to "Great for traditional photos, presentations, and landscape layouts"
            )
        )
        
        println("📐 Supported Resolutions:")
        resolutions.forEach { (ratio, info) ->
            println("$ratio: ${info["name"]} (${info["dimensions"]}) - ${info["description"]}")
        }
        
        return resolutions
    }
    
    /**
     * Get design prompt examples
     * @return Object containing prompt examples
     */
    fun getDesignPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "birthday_cards" to listOf(
                "BIRTHDAY CARD INVITATION with balloons and confetti",
                "Elegant birthday card with cake and candles",
                "Fun birthday invitation with party decorations",
                "Modern birthday card with geometric patterns",
                "Vintage birthday invitation with floral design"
            ),
            "posters" to listOf(
                "CONCERT POSTER with bold typography and neon colors",
                "Movie poster with dramatic lighting and action",
                "Event poster with modern minimalist design",
                "Festival poster with vibrant colors and patterns",
                "Art exhibition poster with creative typography"
            ),
            "flyers" to listOf(
                "RESTAURANT FLYER with appetizing food photos",
                "Gym membership flyer with fitness motivation",
                "Sale flyer with discount offers and prices",
                "Workshop flyer with educational theme",
                "Product launch flyer with modern design"
            ),
            "banners" to listOf(
                "WEBSITE BANNER with call-to-action button",
                "Social media banner with brand colors",
                "Advertisement banner with product showcase",
                "Event banner with date and location",
                "Promotional banner with special offers"
            ),
            "invitations" to listOf(
                "WEDDING INVITATION with elegant typography",
                "Party invitation with fun graphics",
                "Corporate event invitation with professional design",
                "Holiday party invitation with festive theme",
                "Anniversary invitation with romantic elements"
            ),
            "packaging" to listOf(
                "PRODUCT PACKAGING with modern minimalist design",
                "Food packaging with appetizing visuals",
                "Cosmetic packaging with luxury aesthetic",
                "Tech product packaging with sleek design",
                "Gift box packaging with premium feel"
            )
        )
        
        println("💡 Design Prompt Examples:")
        promptExamples.forEach { (category, exampleList) ->
            println("$category: $exampleList")
        }
        
        return promptExamples
    }
    
    /**
     * Get design tips and best practices
     * @return Object containing tips for better results
     */
    fun getDesignTips(): Map<String, List<String>> {
        val tips = mapOf(
            "text_prompts" to listOf(
                "Be specific about the design type (poster, card, banner, etc.)",
                "Include style preferences (modern, vintage, minimalist, etc.)",
                "Mention color schemes or mood you want to achieve",
                "Specify any text or typography requirements",
                "Include target audience or purpose of the design"
            ),
            "resolution_selection" to listOf(
                "Choose 1:1 for social media posts and profile pictures",
                "Use 9:16 for mobile stories and vertical content",
                "Select 2:3 for posters, flyers, and print materials",
                "Pick 16:9 for banners, presentations, and web headers",
                "Consider 4:3 for traditional photos and documents"
            ),
            "prompt_enhancement" to listOf(
                "Enable enhancePrompt for better, more detailed results",
                "Use enhancePrompt when you want richer visual elements",
                "Disable enhancePrompt for exact prompt interpretation",
                "Enhanced prompts work well for creative designs",
                "Basic prompts are better for simple, clean designs"
            ),
            "general" to listOf(
                "AI design works best with clear, descriptive prompts",
                "Results may vary based on prompt complexity and style",
                "Allow 15-30 seconds for processing",
                "Experiment with different resolutions for various use cases",
                "Combine text prompts with resolution for optimal results"
            )
        )
        
        println("💡 AI Design Tips:")
        tips.forEach { (category, tipList) ->
            println("$category: $tipList")
        }
        
        return tips
    }
    
    /**
     * Get design use cases and examples
     * @return Object containing use case examples
     */
    fun getDesignUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "marketing" to listOf(
                "Create promotional posters and banners",
                "Generate social media content and ads",
                "Design product packaging and labels",
                "Create event flyers and invitations",
                "Generate website headers and graphics"
            ),
            "personal" to listOf(
                "Design birthday cards and invitations",
                "Create holiday greetings and cards",
                "Generate party decorations and themes",
                "Design personal branding materials",
                "Create custom artwork and prints"
            ),
            "business" to listOf(
                "Generate corporate presentation slides",
                "Create business cards and letterheads",
                "Design product catalogs and brochures",
                "Generate trade show materials",
                "Create company newsletters and reports"
            ),
            "creative" to listOf(
                "Explore artistic design concepts",
                "Generate creative project ideas",
                "Create portfolio pieces and samples",
                "Design book covers and illustrations",
                "Generate art prints and posters"
            ),
            "education" to listOf(
                "Create educational posters and charts",
                "Design course materials and handouts",
                "Generate presentation slides and graphics",
                "Create learning aids and visual guides",
                "Design school event materials"
            )
        )
        
        println("💡 Design Use Cases:")
        useCases.forEach { (category, useCaseList) ->
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
        if (textPrompt.isBlank()) {
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
     * Validate resolution (utility function)
     * @param resolution Resolution to validate
     * @return Whether the resolution is valid
     */
    fun validateResolution(resolution: String): Boolean {
        val validResolutions = listOf("1:1", "9:16", "3:4", "2:3", "16:9", "4:3")
        
        if (resolution !in validResolutions) {
            println("❌ Invalid resolution. Valid options: ${validResolutions.joinToString(", ")}")
            return false
        }
        
        println("✅ Resolution is valid")
        return true
    }
    
    /**
     * Generate design with validation
     * @param textPrompt Text prompt for design description
     * @param resolution Design resolution
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    suspend fun generateDesignWithValidation(textPrompt: String, resolution: String = "1:1", enhancePrompt: Boolean = true): DesignOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXDesignException("Invalid text prompt")
        }
        
        if (!validateResolution(resolution)) {
            throw LightXDesignException("Invalid resolution")
        }
        
        return processDesign(textPrompt, resolution, enhancePrompt)
    }
}

// MARK: - Exception Classes

class LightXDesignException(message: String) : Exception(message)

// MARK: - Example Usage

suspend fun runExample() {
    try {
        // Initialize with your API key
        val lightx = LightXAIDesignAPI("YOUR_API_KEY_HERE")
        
        // Get tips and examples
        lightx.getSupportedResolutions()
        lightx.getDesignPromptExamples()
        lightx.getDesignTips()
        lightx.getDesignUseCases()
        
        // Example 1: Birthday card design
        val result1 = lightx.generateDesignWithValidation(
            "BIRTHDAY CARD INVITATION with balloons and confetti",
            "2:3",
            true
        )
        println("🎉 Birthday card design result:")
        println("Order ID: ${result1.orderId}")
        println("Status: ${result1.status}")
        result1.output?.let { println("Output: $it") }
        
        // Example 2: Poster design
        val result2 = lightx.generateDesignWithValidation(
            "CONCERT POSTER with bold typography and neon colors",
            "16:9",
            true
        )
        println("🎉 Concert poster design result:")
        println("Order ID: ${result2.orderId}")
        println("Status: ${result2.status}")
        result2.output?.let { println("Output: $it") }
        
        // Example 3: Try different design types
        val designTypes = listOf(
            mapOf("prompt" to "RESTAURANT FLYER with appetizing food photos", "resolution" to "2:3"),
            mapOf("prompt" to "WEBSITE BANNER with call-to-action button", "resolution" to "16:9"),
            mapOf("prompt" to "WEDDING INVITATION with elegant typography", "resolution" to "3:4"),
            mapOf("prompt" to "PRODUCT PACKAGING with modern minimalist design", "resolution" to "1:1"),
            mapOf("prompt" to "SOCIAL MEDIA POST with vibrant colors", "resolution" to "1:1")
        )
        
        designTypes.forEach { design ->
            val result = lightx.generateDesignWithValidation(
                design["prompt"] as String,
                design["resolution"] as String,
                true
            )
            println("🎉 ${design["prompt"]} result:")
            println("Order ID: ${result.orderId}")
            println("Status: ${result.status}")
            result.output?.let { println("Output: $it") }
        }
        
    } catch (e: LightXDesignException) {
        println("❌ Example failed: ${e.message}")
    }
}

// Run example if this file is executed directly
fun main() = runBlocking {
    runExample()
}
