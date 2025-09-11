/**
 * LightX AI Sketch to Image API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered sketch to image transformation functionality.
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
data class Sketch2ImageUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: Sketch2ImageUploadImageBody
)

@Serializable
data class Sketch2ImageUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class Sketch2ImageGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: Sketch2ImageGenerationBody
)

@Serializable
data class Sketch2ImageGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class Sketch2ImageOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: Sketch2ImageOrderStatusBody
)

@Serializable
data class Sketch2ImageOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Sketch to Image API Client

class LightXSketch2ImageAPI(private val apiKey: String) {
    
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
            throw LightXSketch2ImageException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Upload multiple images (sketch and optional style image)
     * @param sketchImageFile Sketch image file
     * @param styleImageFile Style image file (optional)
     * @param contentType MIME type
     * @return Pair of (sketchURL, styleURL)
     */
    suspend fun uploadImages(sketchImageFile: File, styleImageFile: File? = null, contentType: String = "image/jpeg"): Pair<String, String?> {
        println("📤 Uploading sketch image...")
        val sketchURL = uploadImage(sketchImageFile, contentType)
        
        val styleURL = styleImageFile?.let {
            println("📤 Uploading style image...")
            uploadImage(it, contentType)
        }
        
        return Pair(sketchURL, styleURL)
    }
    
    /**
     * Generate sketch to image transformation
     * @param imageUrl URL of the sketch image
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageUrl URL of the style image (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @return Order ID for tracking
     */
    suspend fun generateSketch2Image(imageUrl: String, strength: Double, textPrompt: String, styleImageUrl: String? = null, styleStrength: Double? = null): String {
        val endpoint = "$BASE_URL/v1/sketch2image"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("strength", strength)
            put("textPrompt", textPrompt)
            
            // Add optional parameters
            styleImageUrl?.let { put("styleImageUrl", it) }
            styleStrength?.let { put("styleStrength", it) }
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXSketch2ImageException("Network error: ${response.statusCode()}")
        }
        
        val sketch2ImageResponse = json.decodeFromString<Sketch2ImageGenerationResponse>(response.body())
        
        if (sketch2ImageResponse.statusCode != 2000) {
            throw LightXSketch2ImageException("Sketch to image request failed: ${sketch2ImageResponse.message}")
        }
        
        val orderInfo = sketch2ImageResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("💬 Text prompt: \"$textPrompt\"")
        println("🎨 Strength: $strength")
        styleImageUrl?.let { 
            println("🎭 Style image: $it")
            println("🎨 Style strength: $styleStrength")
        }
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): Sketch2ImageOrderStatusBody {
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
            throw LightXSketch2ImageException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<Sketch2ImageOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXSketch2ImageException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): Sketch2ImageOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Sketch to image transformation completed successfully!")
                        status.output?.let { println("🖼️ Generated image: $it") }
                        return status
                    }
                    "failed" -> throw LightXSketch2ImageException("Sketch to image transformation failed")
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
        
        throw LightXSketch2ImageException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate sketch to image transformation
     * @param sketchImageFile Sketch image file
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageFile Style image file (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processSketch2ImageGeneration(
        sketchImageFile: File, 
        strength: Double, 
        textPrompt: String, 
        styleImageFile: File? = null, 
        styleStrength: Double? = null, 
        contentType: String = "image/jpeg"
    ): Sketch2ImageOrderStatusBody {
        println("🚀 Starting LightX AI Sketch to Image API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (sketchURL, styleURL) = uploadImages(sketchImageFile, styleImageFile, contentType)
        println("✅ Sketch image uploaded: $sketchURL")
        styleURL?.let { println("✅ Style image uploaded: $it") }
        
        // Step 2: Generate sketch to image transformation
        println("🎨 Generating sketch to image transformation...")
        val orderId = generateSketch2Image(sketchURL, strength, textPrompt, styleURL, styleStrength)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get sketch to image transformation tips and best practices
     * @return Map of tips for better results
     */
    fun getSketch2ImageTips(): Map<String, List<String>> {
        val tips = mapOf(
            "sketch_quality" to listOf(
                "Use clear, well-defined sketches with good contrast",
                "Ensure sketch lines are visible and not too faint",
                "Avoid overly complex or cluttered sketches",
                "Use high-resolution sketches for better results",
                "Good sketch quality improves transformation results"
            ),
            "strength_parameter" to listOf(
                "Higher strength (0.7-1.0) makes output more similar to sketch",
                "Lower strength (0.1-0.3) allows more creative interpretation",
                "Medium strength (0.4-0.6) balances sketch structure and creativity",
                "Experiment with different strength values for desired results",
                "Strength affects how much the original sketch structure is preserved"
            ),
            "style_image" to listOf(
                "Choose style images with desired visual characteristics",
                "Ensure style image has good quality and clear features",
                "Style strength controls how much style is applied",
                "Higher style strength applies more style characteristics",
                "Use style images that complement your text prompt"
            ),
            "text_prompts" to listOf(
                "Be specific about the final image you want to create",
                "Mention colors, lighting, mood, and visual style",
                "Include details about the subject matter and composition",
                "Combine style descriptions with content descriptions",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Sketch to image works best with clear, well-composed sketches",
                "Results may vary based on sketch quality and complexity",
                "Text prompts guide the image generation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different strength and style combinations"
            )
        )
        
        println("💡 Sketch to Image Transformation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get strength parameter suggestions for sketch to image
     * @return Map of strength suggestions
     */
    fun getStrengthSuggestions(): Map<String, Map<String, Any>> {
        val strengthSuggestions = mapOf(
            "conservative" to mapOf(
                "range" to "0.7 - 1.0",
                "description" to "Preserves most of the original sketch structure and composition",
                "use_cases" to listOf("Detailed sketch interpretation", "Architectural drawings", "Technical illustrations", "Precise sketch rendering")
            ),
            "balanced" to mapOf(
                "range" to "0.4 - 0.6",
                "description" to "Balances sketch structure with creative interpretation",
                "use_cases" to listOf("Artistic sketch rendering", "Creative interpretation", "Style application", "Balanced transformation")
            ),
            "creative" to mapOf(
                "range" to "0.1 - 0.3",
                "description" to "Allows significant creative interpretation while keeping basic sketch elements",
                "use_cases" to listOf("Artistic reimagining", "Creative reinterpretation", "Style-heavy transformation", "Dramatic interpretation")
            )
        )
        
        println("💡 Strength Parameter Suggestions for Sketch to Image:")
        for ((category, suggestion) in strengthSuggestions) {
            println("$category: ${suggestion["range"]} - ${suggestion["description"]}")
            val useCases = suggestion["use_cases"] as? List<String>
            useCases?.let { println("  Use cases: ${it.joinToString(", ")}") }
        }
        return strengthSuggestions
    }
    
    /**
     * Get style strength suggestions
     * @return Map of style strength suggestions
     */
    fun getStyleStrengthSuggestions(): Map<String, Map<String, Any>> {
        val styleStrengthSuggestions = mapOf(
            "subtle" to mapOf(
                "range" to "0.1 - 0.3",
                "description" to "Applies subtle style characteristics",
                "use_cases" to listOf("Gentle style influence", "Color palette transfer", "Light texture changes")
            ),
            "moderate" to mapOf(
                "range" to "0.4 - 0.6",
                "description" to "Applies moderate style characteristics",
                "use_cases" to listOf("Clear style transfer", "Artistic interpretation", "Medium style influence")
            ),
            "strong" to mapOf(
                "range" to "0.7 - 1.0",
                "description" to "Applies strong style characteristics",
                "use_cases" to listOf("Dramatic style transfer", "Complete artistic transformation", "Strong visual influence")
            )
        )
        
        println("💡 Style Strength Suggestions:")
        for ((category, suggestion) in styleStrengthSuggestions) {
            println("$category: ${suggestion["range"]} - ${suggestion["description"]}")
            val useCases = suggestion["use_cases"] as? List<String>
            useCases?.let { println("  Use cases: ${it.joinToString(", ")}") }
        }
        return styleStrengthSuggestions
    }
    
    /**
     * Get sketch to image prompt examples
     * @return Map of prompt examples
     */
    fun getSketch2ImagePromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "realistic" to listOf(
                "Create a realistic photograph with natural lighting and colors",
                "Generate a photorealistic image with detailed textures and shadows",
                "Transform into a high-quality photograph with professional lighting",
                "Create a realistic portrait with natural skin tones and expressions",
                "Generate a realistic landscape with natural colors and atmosphere"
            ),
            "artistic" to listOf(
                "Transform into oil painting style with rich colors and brushstrokes",
                "Convert to watercolor painting with soft edges and flowing colors",
                "Create digital art with vibrant colors and smooth gradients",
                "Transform into pencil sketch with detailed shading and textures",
                "Convert to pop art style with bold colors and contrast"
            ),
            "fantasy" to listOf(
                "Create a fantasy illustration with magical elements and vibrant colors",
                "Generate a sci-fi scene with futuristic technology and lighting",
                "Transform into a fantasy landscape with mystical atmosphere",
                "Create a fantasy character with magical powers and detailed costume",
                "Generate a fantasy creature with unique features and colors"
            ),
            "architectural" to listOf(
                "Create a realistic architectural visualization with proper lighting",
                "Generate a modern building design with clean lines and materials",
                "Transform into an interior design with proper perspective and lighting",
                "Create a landscape architecture with natural elements",
                "Generate a futuristic building with innovative design elements"
            )
        )
        
        println("💡 Sketch to Image Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
    }
    
    /**
     * Get sketch types and their characteristics
     * @return Map of sketch type information
     */
    fun getSketchTypes(): Map<String, Map<String, Any>> {
        val sketchTypes = mapOf(
            "line_art" to mapOf(
                "description" to "Simple line drawings with clear outlines",
                "best_for" to listOf("Character design", "Logo concepts", "Simple illustrations"),
                "tips" to listOf("Use clear, bold lines", "Avoid too many details", "Keep composition simple")
            ),
            "architectural" to mapOf(
                "description" to "Technical drawings and architectural sketches",
                "best_for" to listOf("Building designs", "Interior layouts", "Urban planning"),
                "tips" to listOf("Use proper perspective", "Include scale references", "Keep lines precise")
            ),
            "character" to mapOf(
                "description" to "Character and figure sketches",
                "best_for" to listOf("Character design", "Portrait concepts", "Fashion design"),
                "tips" to listOf("Focus on proportions", "Include facial features", "Consider pose and expression")
            ),
            "landscape" to mapOf(
                "description" to "Nature and landscape sketches",
                "best_for" to listOf("Environment design", "Nature scenes", "Outdoor settings"),
                "tips" to listOf("Include horizon line", "Show depth and perspective", "Consider lighting direction")
            ),
            "concept" to mapOf(
                "description" to "Conceptual and idea sketches",
                "best_for" to listOf("Product design", "Creative concepts", "Abstract ideas"),
                "tips" to listOf("Focus on main concept", "Use simple shapes", "Include key elements")
            )
        )
        
        println("💡 Sketch Types and Characteristics:")
        for ((sketchType, info) in sketchTypes) {
            println("$sketchType: ${info["description"]}")
            val bestFor = info["best_for"] as? List<String>
            bestFor?.let { println("  Best for: ${it.joinToString(", ")}") }
            val tips = info["tips"] as? List<String>
            tips?.let { println("  Tips: ${it.joinToString(", ")}") }
        }
        return sketchTypes
    }
    
    /**
     * Validate parameters (utility function)
     * @param strength Strength parameter to validate
     * @param textPrompt Text prompt to validate
     * @param styleStrength Style strength parameter to validate (optional)
     * @return Whether the parameters are valid
     */
    fun validateParameters(strength: Double, textPrompt: String, styleStrength: Double? = null): Boolean {
        // Validate strength
        if (strength < 0 || strength > 1) {
            println("❌ Strength must be between 0.0 and 1.0")
            return false
        }
        
        // Validate text prompt
        if (textPrompt.trim().isEmpty()) {
            println("❌ Text prompt cannot be empty")
            return false
        }
        
        if (textPrompt.length > 500) {
            println("❌ Text prompt is too long (max 500 characters)")
            return false
        }
        
        // Validate style strength if provided
        styleStrength?.let {
            if (it < 0 || it > 1) {
                println("❌ Style strength must be between 0.0 and 1.0")
                return false
            }
        }
        
        println("✅ Parameters are valid")
        return true
    }
    
    /**
     * Generate sketch to image transformation with parameter validation
     * @param sketchImageFile Sketch image file
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageFile Style image file (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateSketch2ImageWithValidation(
        sketchImageFile: File, 
        strength: Double, 
        textPrompt: String, 
        styleImageFile: File? = null, 
        styleStrength: Double? = null, 
        contentType: String = "image/jpeg"
    ): Sketch2ImageOrderStatusBody {
        if (!validateParameters(strength, textPrompt, styleStrength)) {
            throw LightXSketch2ImageException("Invalid parameters")
        }
        
        return processSketch2ImageGeneration(sketchImageFile, strength, textPrompt, styleImageFile, styleStrength, contentType)
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): Sketch2ImageUploadImageBody {
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
            throw LightXSketch2ImageException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<Sketch2ImageUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXSketch2ImageException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXSketch2ImageException("Image upload failed: ${response.statusCode()}")
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

class LightXSketch2ImageException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXSketch2ImageExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXSketch2ImageAPI("YOUR_API_KEY_HERE")
            
            val sketchImageFile = File("path/to/sketch-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg")
            
            if (!sketchImageFile.exists()) {
                println("❌ Sketch image file not found: ${sketchImageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getSketch2ImageTips()
            lightx.getStrengthSuggestions()
            lightx.getStyleStrengthSuggestions()
            lightx.getSketch2ImagePromptExamples()
            lightx.getSketchTypes()
            
            // Example 1: Conservative sketch to image transformation
            val result1 = lightx.generateSketch2ImageWithValidation(
                sketchImageFile = sketchImageFile,
                strength = 0.8, // High strength to preserve sketch structure
                textPrompt = "Create a realistic photograph with natural lighting and colors",
                styleImageFile = null, // No style image
                styleStrength = null, // No style strength
                contentType = "image/jpeg"
            )
            println("🎉 Conservative transformation result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Balanced transformation with style image
            if (styleImageFile.exists()) {
                val result2 = lightx.generateSketch2ImageWithValidation(
                    sketchImageFile = sketchImageFile,
                    strength = 0.5, // Balanced strength
                    textPrompt = "Transform into oil painting style with rich colors",
                    styleImageFile = styleImageFile, // Style image
                    styleStrength = 0.7, // Strong style influence
                    contentType = "image/jpeg"
                )
                println("🎉 Balanced transformation result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
            }
            
            // Example 3: Creative transformation with different strength values
            val strengthValues = listOf(0.2, 0.5, 0.8)
            for (strength in strengthValues) {
                val result = lightx.generateSketch2ImageWithValidation(
                    sketchImageFile = sketchImageFile,
                    strength = strength,
                    textPrompt = "Create a fantasy illustration with magical elements and vibrant colors",
                    styleImageFile = null,
                    styleStrength = null,
                    contentType = "image/jpeg"
                )
                println("🎉 Creative transformation (strength: $strength) result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 4: Get image dimensions
            val (width, height) = lightx.getImageDimensions(sketchImageFile)
            if (width > 0 && height > 0) {
                println("📏 Original sketch: ${width}x${height}")
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
fun runLightXSketch2ImageExample() {
    runBlocking {
        LightXSketch2ImageExample.main(emptyArray())
    }
}
