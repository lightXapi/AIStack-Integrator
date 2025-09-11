/**
 * LightX AI Portrait Generator API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered portrait generation functionality.
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
data class PortraitUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: PortraitUploadImageBody
)

@Serializable
data class PortraitUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class PortraitGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: PortraitGenerationBody
)

@Serializable
data class PortraitGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class PortraitOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: PortraitOrderStatusBody
)

@Serializable
data class PortraitOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Portrait Generator API Client

class LightXPortraitAPI(private val apiKey: String) {
    
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
            throw LightXPortraitException("Image size exceeds 5MB limit")
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
     * Generate portrait
     * @param imageUrl URL of the input image
     * @param styleImageUrl URL of the style image (optional)
     * @param textPrompt Text prompt for portrait style (optional)
     * @return Order ID for tracking
     */
    suspend fun generatePortrait(imageUrl: String, styleImageUrl: String? = null, textPrompt: String? = null): String {
        val endpoint = "$BASE_URL/v1/portrait"
        
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
            throw LightXPortraitException("Network error: ${response.statusCode()}")
        }
        
        val portraitResponse = json.decodeFromString<PortraitGenerationResponse>(response.body())
        
        if (portraitResponse.statusCode != 2000) {
            throw LightXPortraitException("Portrait generation request failed: ${portraitResponse.message}")
        }
        
        val orderInfo = portraitResponse.body
        
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
    suspend fun checkOrderStatus(orderId: String): PortraitOrderStatusBody {
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
            throw LightXPortraitException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<PortraitOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXPortraitException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): PortraitOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Portrait generation completed successfully!")
                        status.output?.let { println("🖼️ Portrait image: $it") }
                        return status
                    }
                    "failed" -> throw LightXPortraitException("Portrait generation failed")
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
        
        throw LightXPortraitException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate portrait
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file (optional)
     * @param textPrompt Text prompt for portrait style (optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processPortraitGeneration(
        inputImageFile: File, 
        styleImageFile: File? = null, 
        textPrompt: String? = null, 
        contentType: String = "image/jpeg"
    ): PortraitOrderStatusBody {
        println("🚀 Starting LightX AI Portrait Generator API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (inputUrl, styleUrl) = uploadImages(inputImageFile, styleImageFile, contentType)
        println("✅ Input image uploaded: $inputUrl")
        styleUrl?.let { println("✅ Style image uploaded: $it") }
        
        // Step 2: Generate portrait
        println("🖼️ Generating portrait...")
        val orderId = generatePortrait(inputUrl, styleUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get common text prompts for different portrait styles
     * @param category Category of portrait style
     * @return List of suggested prompts
     */
    fun getSuggestedPrompts(category: String): List<String> {
        val promptSuggestions = mapOf(
            "realistic" to listOf(
                "realistic portrait photography",
                "professional headshot style",
                "natural lighting portrait",
                "studio portrait photography",
                "high-quality realistic portrait"
            ),
            "artistic" to listOf(
                "artistic portrait style",
                "creative portrait photography",
                "artistic interpretation portrait",
                "creative portrait art",
                "artistic portrait rendering"
            ),
            "vintage" to listOf(
                "vintage portrait style",
                "retro portrait photography",
                "classic portrait style",
                "old school portrait",
                "vintage film portrait"
            ),
            "modern" to listOf(
                "modern portrait style",
                "contemporary portrait photography",
                "sleek modern portrait",
                "contemporary art portrait",
                "modern artistic portrait"
            ),
            "fantasy" to listOf(
                "fantasy portrait style",
                "magical portrait art",
                "fantasy character portrait",
                "mystical portrait style",
                "fantasy art portrait"
            ),
            "minimalist" to listOf(
                "minimalist portrait style",
                "clean simple portrait",
                "minimal portrait photography",
                "simple elegant portrait",
                "minimalist art portrait"
            ),
            "dramatic" to listOf(
                "dramatic portrait style",
                "high contrast portrait",
                "dramatic lighting portrait",
                "intense portrait photography",
                "dramatic artistic portrait"
            ),
            "soft" to listOf(
                "soft portrait style",
                "gentle portrait photography",
                "soft lighting portrait",
                "delicate portrait art",
                "soft artistic portrait"
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
     * Generate portrait with text prompt only
     * @param inputImageFile Input image file
     * @param textPrompt Text prompt for portrait style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generatePortraitWithPrompt(
        inputImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): PortraitOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXPortraitException("Invalid text prompt")
        }
        
        return processPortraitGeneration(inputImageFile, null, textPrompt, contentType)
    }
    
    /**
     * Generate portrait with style image only
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generatePortraitWithStyle(
        inputImageFile: File, 
        styleImageFile: File, 
        contentType: String = "image/jpeg"
    ): PortraitOrderStatusBody {
        return processPortraitGeneration(inputImageFile, styleImageFile, null, contentType)
    }
    
    /**
     * Generate portrait with both style image and text prompt
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param textPrompt Text prompt for portrait style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generatePortraitWithStyleAndPrompt(
        inputImageFile: File, 
        styleImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): PortraitOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXPortraitException("Invalid text prompt")
        }
        
        return processPortraitGeneration(inputImageFile, styleImageFile, textPrompt, contentType)
    }
    
    /**
     * Get portrait tips and best practices
     * @return Map of tips for better portrait results
     */
    fun getPortraitTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for portraits"
            ),
            "style_image" to listOf(
                "Choose style images with desired artistic style",
                "Use portrait examples as style references",
                "Ensure style image has good lighting and composition",
                "Match the artistic direction you want for your portrait",
                "Use high-quality style reference images"
            ),
            "text_prompts" to listOf(
                "Be specific about the portrait style you want",
                "Mention artistic preferences (realistic, artistic, vintage)",
                "Include lighting preferences (soft, dramatic, natural)",
                "Specify the mood (professional, creative, dramatic)",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Portraits work best with clear human faces",
                "Results may vary based on input image quality",
                "Style images influence both artistic style and composition",
                "Text prompts help guide the portrait generation",
                "Allow 15-30 seconds for processing"
            )
        )
        
        println("💡 Portrait Generation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get portrait style-specific tips
     * @return Map of style-specific tips
     */
    fun getPortraitStyleTips(): Map<String, List<String>> {
        val styleTips = mapOf(
            "realistic" to listOf(
                "Use natural lighting for best results",
                "Ensure good facial detail and clarity",
                "Consider professional headshot style",
                "Use neutral backgrounds for focus on subject"
            ),
            "artistic" to listOf(
                "Choose creative and expressive style images",
                "Consider artistic interpretation over realism",
                "Use bold colors and creative compositions",
                "Experiment with different artistic styles"
            ),
            "vintage" to listOf(
                "Use warm, nostalgic color tones",
                "Consider film photography aesthetics",
                "Use classic portrait compositions",
                "Apply vintage color grading effects"
            ),
            "modern" to listOf(
                "Use contemporary photography styles",
                "Consider clean, minimalist compositions",
                "Use modern lighting techniques",
                "Apply contemporary color palettes"
            ),
            "fantasy" to listOf(
                "Use magical or mystical style references",
                "Consider fantasy art aesthetics",
                "Use dramatic lighting and effects",
                "Apply fantasy color schemes"
            )
        )
        
        println("💡 Portrait Style Tips:")
        for ((style, tipList) in styleTips) {
            println("$style: $tipList")
        }
        return styleTips
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): PortraitUploadImageBody {
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
            throw LightXPortraitException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<PortraitUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXPortraitException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXPortraitException("Image upload failed: ${response.statusCode()}")
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

class LightXPortraitException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXPortraitExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXPortraitAPI("YOUR_API_KEY_HERE")
            
            val inputImageFile = File("path/to/input-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg")
            
            if (!inputImageFile.exists()) {
                println("❌ Input image file not found: ${inputImageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getPortraitTips()
            lightx.getPortraitStyleTips()
            
            // Example 1: Generate portrait with text prompt only
            val realisticPrompts = lightx.getSuggestedPrompts("realistic")
            val result1 = lightx.generatePortraitWithPrompt(
                inputImageFile = inputImageFile,
                textPrompt = realisticPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Realistic portrait result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Generate portrait with style image only
            if (styleImageFile.exists()) {
                val result2 = lightx.generatePortraitWithStyle(
                    inputImageFile = inputImageFile,
                    styleImageFile = styleImageFile,
                    contentType = "image/jpeg"
                )
                println("🎉 Style-based portrait result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
                
                // Example 3: Generate portrait with both style image and text prompt
                val artisticPrompts = lightx.getSuggestedPrompts("artistic")
                val result3 = lightx.generatePortraitWithStyleAndPrompt(
                    inputImageFile = inputImageFile,
                    styleImageFile = styleImageFile,
                    textPrompt = artisticPrompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 Combined style and prompt result:")
                println("Order ID: ${result3.orderId}")
                println("Status: ${result3.status}")
                result3.output?.let { println("Output: $it") }
            }
            
            // Example 4: Generate portraits for different styles
            val styles = listOf("realistic", "artistic", "vintage", "modern", "fantasy", "minimalist", "dramatic", "soft")
            for (style in styles) {
                val prompts = lightx.getSuggestedPrompts(style)
                val result = lightx.generatePortraitWithPrompt(
                    inputImageFile = inputImageFile,
                    textPrompt = prompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 $style portrait result:")
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
fun runLightXPortraitExample() {
    runBlocking {
        LightXPortraitExample.main(emptyArray())
    }
}
