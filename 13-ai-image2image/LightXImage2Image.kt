/**
 * LightX AI Image to Image API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image to image transformation functionality.
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
data class Image2ImageUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: Image2ImageUploadImageBody
)

@Serializable
data class Image2ImageUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class Image2ImageGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: Image2ImageGenerationBody
)

@Serializable
data class Image2ImageGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class Image2ImageOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: Image2ImageOrderStatusBody
)

@Serializable
data class Image2ImageOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Image to Image API Client

class LightXImage2ImageAPI(private val apiKey: String) {
    
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
            throw LightXImage2ImageException("Image size exceeds 5MB limit")
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
     * @return Pair of (inputURL, styleURL)
     */
    suspend fun uploadImages(inputImageFile: File, styleImageFile: File? = null, contentType: String = "image/jpeg"): Pair<String, String?> {
        println("📤 Uploading input image...")
        val inputURL = uploadImage(inputImageFile, contentType)
        
        val styleURL = styleImageFile?.let {
            println("📤 Uploading style image...")
            uploadImage(it, contentType)
        }
        
        return Pair(inputURL, styleURL)
    }
    
    /**
     * Generate image to image transformation
     * @param imageUrl URL of the input image
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageUrl URL of the style image (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @return Order ID for tracking
     */
    suspend fun generateImage2Image(imageUrl: String, strength: Double, textPrompt: String, styleImageUrl: String? = null, styleStrength: Double? = null): String {
        val endpoint = "$BASE_URL/v1/image2image"
        
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
            throw LightXImage2ImageException("Network error: ${response.statusCode()}")
        }
        
        val image2ImageResponse = json.decodeFromString<Image2ImageGenerationResponse>(response.body())
        
        if (image2ImageResponse.statusCode != 2000) {
            throw LightXImage2ImageException("Image to image request failed: ${image2ImageResponse.message}")
        }
        
        val orderInfo = image2ImageResponse.body
        
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
    suspend fun checkOrderStatus(orderId: String): Image2ImageOrderStatusBody {
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
            throw LightXImage2ImageException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<Image2ImageOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXImage2ImageException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): Image2ImageOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Image to image transformation completed successfully!")
                        status.output?.let { println("🖼️ Transformed image: $it") }
                        return status
                    }
                    "failed" -> throw LightXImage2ImageException("Image to image transformation failed")
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
        
        throw LightXImage2ImageException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate image to image transformation
     * @param inputImageFile Input image file
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageFile Style image file (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processImage2ImageGeneration(
        inputImageFile: File, 
        strength: Double, 
        textPrompt: String, 
        styleImageFile: File? = null, 
        styleStrength: Double? = null, 
        contentType: String = "image/jpeg"
    ): Image2ImageOrderStatusBody {
        println("🚀 Starting LightX AI Image to Image API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (inputURL, styleURL) = uploadImages(inputImageFile, styleImageFile, contentType)
        println("✅ Input image uploaded: $inputURL")
        styleURL?.let { println("✅ Style image uploaded: $it") }
        
        // Step 2: Generate image to image transformation
        println("🖼️ Generating image to image transformation...")
        val orderId = generateImage2Image(inputURL, strength, textPrompt, styleURL, styleStrength)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get image to image transformation tips and best practices
     * @return Map of tips for better results
     */
    fun getImage2ImageTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the subject is clearly visible and well-composed",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good image quality improves transformation results"
            ),
            "strength_parameter" to listOf(
                "Higher strength (0.7-1.0) makes output more similar to input",
                "Lower strength (0.1-0.3) allows more creative transformation",
                "Medium strength (0.4-0.6) balances similarity and creativity",
                "Experiment with different strength values for desired results",
                "Strength affects how much the original image structure is preserved"
            ),
            "style_image" to listOf(
                "Choose style images with desired visual characteristics",
                "Ensure style image has good quality and clear features",
                "Style strength controls how much style is applied",
                "Higher style strength applies more style characteristics",
                "Use style images that complement your text prompt"
            ),
            "text_prompts" to listOf(
                "Be specific about the transformation you want",
                "Mention artistic styles, colors, and visual elements",
                "Include details about lighting, mood, and atmosphere",
                "Combine style descriptions with content descriptions",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Image to image works best with clear, well-composed photos",
                "Results may vary based on input image quality",
                "Text prompts guide the transformation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different strength and style combinations"
            )
        )
        
        println("💡 Image to Image Transformation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get strength parameter suggestions
     * @return Map of strength suggestions
     */
    fun getStrengthSuggestions(): Map<String, Map<String, Any>> {
        val strengthSuggestions = mapOf(
            "conservative" to mapOf(
                "range" to "0.7 - 1.0",
                "description" to "Preserves most of the original image structure and content",
                "use_cases" to listOf("Minor style adjustments", "Color corrections", "Light enhancement", "Subtle artistic effects")
            ),
            "balanced" to mapOf(
                "range" to "0.4 - 0.6",
                "description" to "Balances original content with creative transformation",
                "use_cases" to listOf("Style transfer", "Artistic interpretation", "Medium-level changes", "Creative enhancement")
            ),
            "creative" to mapOf(
                "range" to "0.1 - 0.3",
                "description" to "Allows significant creative transformation while keeping basic structure",
                "use_cases" to listOf("Major style changes", "Artistic reimagining", "Creative reinterpretation", "Dramatic transformation")
            )
        )
        
        println("💡 Strength Parameter Suggestions:")
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
     * Get transformation prompt examples
     * @return Map of prompt examples
     */
    fun getTransformationPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "artistic" to listOf(
                "Transform into oil painting style with rich colors",
                "Convert to watercolor painting with soft edges",
                "Create digital art with vibrant colors and smooth gradients",
                "Transform into pencil sketch with detailed shading",
                "Convert to pop art style with bold colors and contrast"
            ),
            "style_transfer" to listOf(
                "Apply Van Gogh painting style with swirling brushstrokes",
                "Transform into Picasso cubist style with geometric shapes",
                "Apply Monet impressionist style with soft, blurred edges",
                "Convert to Andy Warhol pop art with bright, flat colors",
                "Transform into Japanese ukiyo-e woodblock print style"
            ),
            "mood_atmosphere" to listOf(
                "Create warm, golden hour lighting with soft shadows",
                "Transform into dramatic, high-contrast black and white",
                "Apply dreamy, ethereal atmosphere with soft focus",
                "Create vintage, sepia-toned nostalgic look",
                "Transform into futuristic, cyberpunk aesthetic"
            ),
            "color_enhancement" to listOf(
                "Enhance colors with vibrant, saturated tones",
                "Apply cool, blue-toned color grading",
                "Transform into warm, orange and red color palette",
                "Create monochromatic look with single color accent",
                "Apply vintage film color grading with faded tones"
            )
        )
        
        println("💡 Transformation Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
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
     * Generate image to image transformation with parameter validation
     * @param inputImageFile Input image file
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageFile Style image file (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateImage2ImageWithValidation(
        inputImageFile: File, 
        strength: Double, 
        textPrompt: String, 
        styleImageFile: File? = null, 
        styleStrength: Double? = null, 
        contentType: String = "image/jpeg"
    ): Image2ImageOrderStatusBody {
        if (!validateParameters(strength, textPrompt, styleStrength)) {
            throw LightXImage2ImageException("Invalid parameters")
        }
        
        return processImage2ImageGeneration(inputImageFile, strength, textPrompt, styleImageFile, styleStrength, contentType)
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): Image2ImageUploadImageBody {
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
            throw LightXImage2ImageException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<Image2ImageUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXImage2ImageException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXImage2ImageException("Image upload failed: ${response.statusCode()}")
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

class LightXImage2ImageException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXImage2ImageExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXImage2ImageAPI("YOUR_API_KEY_HERE")
            
            val inputImageFile = File("path/to/input-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg")
            
            if (!inputImageFile.exists()) {
                println("❌ Input image file not found: ${inputImageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getImage2ImageTips()
            lightx.getStrengthSuggestions()
            lightx.getStyleStrengthSuggestions()
            lightx.getTransformationPromptExamples()
            
            // Example 1: Conservative transformation with text prompt only
            val result1 = lightx.generateImage2ImageWithValidation(
                inputImageFile = inputImageFile,
                strength = 0.8, // High strength to preserve original
                textPrompt = "Transform into oil painting style with rich colors",
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
                val result2 = lightx.generateImage2ImageWithValidation(
                    inputImageFile = inputImageFile,
                    strength = 0.5, // Balanced strength
                    textPrompt = "Apply artistic style transformation",
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
                val result = lightx.generateImage2ImageWithValidation(
                    inputImageFile = inputImageFile,
                    strength = strength,
                    textPrompt = "Create artistic interpretation with vibrant colors",
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
fun runLightXImage2ImageExample() {
    runBlocking {
        LightXImage2ImageExample.main(emptyArray())
    }
}
