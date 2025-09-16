/**
 * LightX AI Logo Generator API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered logo generation functionality.
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
data class LogoGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: LogoGenerationBody
)

@Serializable
data class LogoGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class LogoOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: LogoOrderStatusBody
)

@Serializable
data class LogoOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX AI Logo Generator API Client

class LightXAILogoGeneratorAPI(private val apiKey: String) {
    
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
     * Generate AI logo
     * @param textPrompt Text prompt for logo description
     * @param enhancePrompt Whether to enhance the prompt (default: true)
     * @return Order ID for tracking
     */
    suspend fun generateLogo(textPrompt: String, enhancePrompt: Boolean = true): String {
        val endpoint = "$BASE_URL/v2/logo-generator"
        
        val requestBody = buildJsonObject {
            put("textPrompt", textPrompt)
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
            throw LightXLogoGeneratorException("Network error: ${response.statusCode()}")
        }
        
        val logoResponse = json.decodeFromString<LogoGenerationResponse>(response.body())
        
        if (logoResponse.statusCode != 2000) {
            throw LightXLogoGeneratorException("Logo request failed: ${logoResponse.message}")
        }
        
        val orderInfo = logoResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🎨 Logo prompt: \"$textPrompt\"")
        println("✨ Enhanced prompt: ${if (enhancePrompt) "Yes" else "No"}")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): LogoOrderStatusBody {
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
            throw LightXLogoGeneratorException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<LogoOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXLogoGeneratorException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): LogoOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ AI logo generated successfully!")
                        if (status.output != null) {
                            println("🎨 Logo output: ${status.output}")
                        }
                        return status
                    }
                    "failed" -> {
                        throw LightXLogoGeneratorException("AI logo generation failed")
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
                
            } catch (e: LightXLogoGeneratorException) {
                throw e
            } catch (e: Exception) {
                attempts++
                if (attempts >= MAX_RETRIES) {
                    throw LightXLogoGeneratorException("Maximum retry attempts reached: ${e.message}")
                }
                println("⚠️  Error on attempt $attempts, retrying...")
                delay(RETRY_INTERVAL)
            }
        }
        
        throw LightXLogoGeneratorException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Generate AI logo and wait for completion
     * @param textPrompt Text prompt for logo description
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    suspend fun processLogo(textPrompt: String, enhancePrompt: Boolean = true): LogoOrderStatusBody {
        println("🚀 Starting LightX AI Logo Generator API workflow...")
        
        // Step 1: Generate logo
        println("🎨 Generating AI logo...")
        val orderId = generateLogo(textPrompt, enhancePrompt)
        
        // Step 2: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get logo prompt examples
     * @return Object containing prompt examples
     */
    fun getLogoPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "gaming" to listOf(
                "Minimal monoline fox logo, vector, flat",
                "Gaming channel logo with controller and neon effects",
                "Retro gaming logo with pixel art style",
                "Esports team logo with bold typography",
                "Gaming studio logo with futuristic design"
            ),
            "business" to listOf(
                "Professional tech company logo, modern, clean",
                "Corporate law firm logo with elegant typography",
                "Financial services logo with trust and stability",
                "Consulting firm logo with professional appearance",
                "Real estate company logo with building elements"
            ),
            "food" to listOf(
                "Restaurant logo with chef hat and elegant typography",
                "Coffee shop logo with coffee bean and warm colors",
                "Bakery logo with bread and vintage style",
                "Pizza place logo with pizza slice and fun design",
                "Food truck logo with bold, appetizing colors"
            ),
            "fashion" to listOf(
                "Fashion brand logo with elegant, minimalist design",
                "Luxury clothing logo with sophisticated typography",
                "Streetwear brand logo with urban, edgy style",
                "Jewelry brand logo with elegant, refined design",
                "Beauty brand logo with modern, clean aesthetics"
            ),
            "tech" to listOf(
                "Tech startup logo with modern, innovative design",
                "Software company logo with code and digital elements",
                "AI company logo with futuristic, smart design",
                "Mobile app logo with clean, user-friendly style",
                "Cybersecurity logo with shield and protection theme"
            ),
            "creative" to listOf(
                "Design agency logo with creative, artistic elements",
                "Photography studio logo with camera and lens",
                "Art gallery logo with brush strokes and colors",
                "Music label logo with sound waves and rhythm",
                "Film production logo with cinematic elements"
            )
        )
        
        println("💡 Logo Prompt Examples:")
        promptExamples.forEach { (category, exampleList) ->
            println("$category: $exampleList")
        }
        
        return promptExamples
    }
    
    /**
     * Get logo design tips and best practices
     * @return Object containing tips for better results
     */
    fun getLogoDesignTips(): Map<String, List<String>> {
        val tips = mapOf(
            "text_prompts" to listOf(
                "Be specific about the industry or business type",
                "Include style preferences (minimal, modern, vintage, etc.)",
                "Mention color schemes or mood you want to achieve",
                "Specify any symbols or elements you want included",
                "Include target audience or brand personality"
            ),
            "logo_types" to listOf(
                "Wordmark: Focus on typography and text styling",
                "Symbol: Emphasize icon or graphic elements",
                "Combination: Include both text and symbol elements",
                "Emblem: Create a badge or seal-style design",
                "Abstract: Use geometric shapes and modern forms"
            ),
            "industry_specific" to listOf(
                "Tech: Use modern, clean, and innovative elements",
                "Healthcare: Focus on trust, care, and professionalism",
                "Finance: Emphasize stability, security, and reliability",
                "Food: Use appetizing colors and food-related elements",
                "Creative: Show artistic flair and unique personality"
            ),
            "general" to listOf(
                "AI logo generation works best with clear, descriptive prompts",
                "Results are delivered in 1024x1024 JPEG format",
                "Allow 15-30 seconds for processing",
                "Enhanced prompts provide richer, more detailed results",
                "Experiment with different styles for various applications"
            )
        )
        
        println("💡 Logo Design Tips:")
        tips.forEach { (category, tipList) ->
            println("$category: $tipList")
        }
        
        return tips
    }
    
    /**
     * Get logo use cases and examples
     * @return Object containing use case examples
     */
    fun getLogoUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "business" to listOf(
                "Create company logos for startups and enterprises",
                "Design brand identities for new businesses",
                "Generate logos for product launches",
                "Create professional business cards and letterheads",
                "Design trade show and marketing materials"
            ),
            "personal" to listOf(
                "Create personal branding logos",
                "Design logos for freelance services",
                "Generate logos for personal projects",
                "Create logos for social media profiles",
                "Design logos for personal websites"
            ),
            "creative" to listOf(
                "Generate logos for creative agencies",
                "Design logos for artists and designers",
                "Create logos for events and festivals",
                "Generate logos for publications and blogs",
                "Design logos for creative projects"
            ),
            "gaming" to listOf(
                "Create gaming channel logos",
                "Design esports team logos",
                "Generate gaming studio logos",
                "Create tournament and event logos",
                "Design gaming merchandise logos"
            ),
            "education" to listOf(
                "Create logos for educational institutions",
                "Design logos for online courses",
                "Generate logos for training programs",
                "Create logos for educational events",
                "Design logos for student organizations"
            )
        )
        
        println("💡 Logo Use Cases:")
        useCases.forEach { (category, useCaseList) ->
            println("$category: $useCaseList")
        }
        
        return useCases
    }
    
    /**
     * Get logo style suggestions
     * @return Object containing style suggestions
     */
    fun getLogoStyleSuggestions(): Map<String, List<String>> {
        val styleSuggestions = mapOf(
            "minimal" to listOf(
                "Clean, simple designs with minimal elements",
                "Focus on typography and negative space",
                "Use limited color palettes",
                "Emphasize clarity and readability",
                "Perfect for modern, professional brands"
            ),
            "vintage" to listOf(
                "Classic, timeless designs with retro elements",
                "Use traditional typography and classic symbols",
                "Incorporate aged or weathered effects",
                "Focus on heritage and tradition",
                "Great for established, traditional businesses"
            ),
            "modern" to listOf(
                "Contemporary designs with current trends",
                "Use bold typography and geometric shapes",
                "Incorporate technology and innovation",
                "Focus on forward-thinking and progress",
                "Perfect for tech and startup companies"
            ),
            "playful" to listOf(
                "Fun, energetic designs with personality",
                "Use bright colors and creative elements",
                "Incorporate humor and whimsy",
                "Focus on approachability and friendliness",
                "Great for entertainment and creative brands"
            ),
            "elegant" to listOf(
                "Sophisticated designs with refined aesthetics",
                "Use premium typography and luxury elements",
                "Incorporate subtle details and craftsmanship",
                "Focus on quality and exclusivity",
                "Perfect for luxury and high-end brands"
            )
        )
        
        println("💡 Logo Style Suggestions:")
        styleSuggestions.forEach { (style, suggestionList) ->
            println("$style: $suggestionList")
        }
        
        return styleSuggestions
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
     * Generate logo with validation
     * @param textPrompt Text prompt for logo description
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    suspend fun generateLogoWithValidation(textPrompt: String, enhancePrompt: Boolean = true): LogoOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXLogoGeneratorException("Invalid text prompt")
        }
        
        return processLogo(textPrompt, enhancePrompt)
    }
}

// MARK: - Exception Classes

class LightXLogoGeneratorException(message: String) : Exception(message)

// MARK: - Example Usage

suspend fun runExample() {
    try {
        // Initialize with your API key
        val lightx = LightXAILogoGeneratorAPI("YOUR_API_KEY_HERE")
        
        // Get tips and examples
        lightx.getLogoPromptExamples()
        lightx.getLogoDesignTips()
        lightx.getLogoUseCases()
        lightx.getLogoStyleSuggestions()
        
        // Example 1: Gaming logo
        val result1 = lightx.generateLogoWithValidation(
            "Minimal monoline fox logo, vector, flat",
            true
        )
        println("🎉 Gaming logo result:")
        println("Order ID: ${result1.orderId}")
        println("Status: ${result1.status}")
        result1.output?.let { println("Output: $it") }
        
        // Example 2: Business logo
        val result2 = lightx.generateLogoWithValidation(
            "Professional tech company logo, modern, clean",
            true
        )
        println("🎉 Business logo result:")
        println("Order ID: ${result2.orderId}")
        println("Status: ${result2.status}")
        result2.output?.let { println("Output: $it") }
        
        // Example 3: Try different logo types
        val logoTypes = listOf(
            "Restaurant logo with chef hat and elegant typography",
            "Fashion brand logo with elegant, minimalist design",
            "Tech startup logo with modern, innovative design",
            "Coffee shop logo with coffee bean and warm colors",
            "Design agency logo with creative, artistic elements"
        )
        
        logoTypes.forEach { logoPrompt ->
            val result = lightx.generateLogoWithValidation(
                logoPrompt,
                true
            )
            println("🎉 $logoPrompt result:")
            println("Order ID: ${result.orderId}")
            println("Status: ${result.status}")
            result.output?.let { println("Output: $it") }
        }
        
    } catch (e: LightXLogoGeneratorException) {
        println("❌ Example failed: ${e.message}")
    }
}

// Run example if this file is executed directly
fun main() = runBlocking {
    runExample()
}
