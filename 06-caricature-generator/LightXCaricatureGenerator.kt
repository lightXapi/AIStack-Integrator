/**
 * LightX AI Caricature Generator API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered caricature generation functionality.
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
data class CaricatureUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: CaricatureUploadImageBody
)

@Serializable
data class CaricatureUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class CaricatureGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: CaricatureGenerationBody
)

@Serializable
data class CaricatureGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class CaricatureOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: CaricatureOrderStatusBody
)

@Serializable
data class CaricatureOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Caricature Generator API Client

class LightXCaricatureAPI(private val apiKey: String) {
    
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
            throw LightXCaricatureException("Image size exceeds 5MB limit")
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
     * Generate caricature
     * @param imageUrl URL of the input image
     * @param styleImageUrl URL of the style image (optional)
     * @param textPrompt Text prompt for caricature style (optional)
     * @return Order ID for tracking
     */
    suspend fun generateCaricature(imageUrl: String, styleImageUrl: String? = null, textPrompt: String? = null): String {
        val endpoint = "$BASE_URL/v1/caricature"
        
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
            throw LightXCaricatureException("Network error: ${response.statusCode()}")
        }
        
        val caricatureResponse = json.decodeFromString<CaricatureGenerationResponse>(response.body())
        
        if (caricatureResponse.statusCode != 2000) {
            throw LightXCaricatureException("Caricature generation request failed: ${caricatureResponse.message}")
        }
        
        val orderInfo = caricatureResponse.body
        
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
    suspend fun checkOrderStatus(orderId: String): CaricatureOrderStatusBody {
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
            throw LightXCaricatureException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<CaricatureOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXCaricatureException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): CaricatureOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Caricature generation completed successfully!")
                        status.output?.let { println("🎭 Caricature image: $it") }
                        return status
                    }
                    "failed" -> throw LightXCaricatureException("Caricature generation failed")
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
        
        throw LightXCaricatureException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate caricature
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file (optional)
     * @param textPrompt Text prompt for caricature style (optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processCaricatureGeneration(
        inputImageFile: File, 
        styleImageFile: File? = null, 
        textPrompt: String? = null, 
        contentType: String = "image/jpeg"
    ): CaricatureOrderStatusBody {
        println("🚀 Starting LightX AI Caricature Generator API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (inputUrl, styleUrl) = uploadImages(inputImageFile, styleImageFile, contentType)
        println("✅ Input image uploaded: $inputUrl")
        styleUrl?.let { println("✅ Style image uploaded: $it") }
        
        // Step 2: Generate caricature
        println("🎭 Generating caricature...")
        val orderId = generateCaricature(inputUrl, styleUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get common text prompts for different caricature styles
     * @param category Category of caricature style
     * @return List of suggested prompts
     */
    fun getSuggestedPrompts(category: String): List<String> {
        val promptSuggestions = mapOf(
            "funny" to listOf(
                "funny exaggerated caricature",
                "humorous cartoon caricature",
                "comic book style caricature",
                "funny face caricature",
                "hilarious cartoon portrait"
            ),
            "artistic" to listOf(
                "artistic caricature style",
                "classic caricature portrait",
                "fine art caricature",
                "elegant caricature style",
                "sophisticated caricature"
            ),
            "cartoon" to listOf(
                "cartoon caricature style",
                "animated caricature",
                "Disney style caricature",
                "cartoon network caricature",
                "animated character caricature"
            ),
            "vintage" to listOf(
                "vintage caricature style",
                "retro caricature portrait",
                "classic newspaper caricature",
                "old school caricature",
                "traditional caricature style"
            ),
            "modern" to listOf(
                "modern caricature style",
                "contemporary caricature",
                "digital art caricature",
                "modern illustration caricature",
                "stylized caricature portrait"
            ),
            "extreme" to listOf(
                "extremely exaggerated caricature",
                "wild caricature style",
                "over-the-top caricature",
                "dramatic caricature",
                "intense caricature portrait"
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
     * Generate caricature with text prompt only
     * @param inputImageFile Input image file
     * @param textPrompt Text prompt for caricature style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateCaricatureWithPrompt(
        inputImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): CaricatureOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXCaricatureException("Invalid text prompt")
        }
        
        return processCaricatureGeneration(inputImageFile, null, textPrompt, contentType)
    }
    
    /**
     * Generate caricature with style image only
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateCaricatureWithStyle(
        inputImageFile: File, 
        styleImageFile: File, 
        contentType: String = "image/jpeg"
    ): CaricatureOrderStatusBody {
        return processCaricatureGeneration(inputImageFile, styleImageFile, null, contentType)
    }
    
    /**
     * Generate caricature with both style image and text prompt
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param textPrompt Text prompt for caricature style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateCaricatureWithStyleAndPrompt(
        inputImageFile: File, 
        styleImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): CaricatureOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXCaricatureException("Invalid text prompt")
        }
        
        return processCaricatureGeneration(inputImageFile, styleImageFile, textPrompt, contentType)
    }
    
    /**
     * Get caricature tips and best practices
     * @return Map of tips for better caricature results
     */
    fun getCaricatureTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for caricatures"
            ),
            "style_image" to listOf(
                "Choose style images with clear facial features",
                "Use caricature examples as style references",
                "Ensure style image has good lighting",
                "Match the pose of your input image if possible",
                "Use high-quality style reference images"
            ),
            "text_prompts" to listOf(
                "Be specific about the caricature style you want",
                "Mention the level of exaggeration desired",
                "Include artistic style preferences",
                "Specify if you want funny, artistic, or dramatic results",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Caricatures work best with human faces",
                "Results may vary based on input image quality",
                "Style images influence both artistic style and pose",
                "Text prompts help guide the caricature style",
                "Allow 15-30 seconds for processing"
            )
        )
        
        println("💡 Caricature Generation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): CaricatureUploadImageBody {
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
            throw LightXCaricatureException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<CaricatureUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXCaricatureException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXCaricatureException("Image upload failed: ${response.statusCode()}")
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

class LightXCaricatureException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXCaricatureExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXCaricatureAPI("YOUR_API_KEY_HERE")
            
            val inputImageFile = File("path/to/input-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg")
            
            if (!inputImageFile.exists()) {
                println("❌ Input image file not found: ${inputImageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getCaricatureTips()
            
            // Example 1: Generate caricature with text prompt only
            val funnyPrompts = lightx.getSuggestedPrompts("funny")
            val result1 = lightx.generateCaricatureWithPrompt(
                inputImageFile = inputImageFile,
                textPrompt = funnyPrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Funny caricature result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Generate caricature with style image only
            if (styleImageFile.exists()) {
                val result2 = lightx.generateCaricatureWithStyle(
                    inputImageFile = inputImageFile,
                    styleImageFile = styleImageFile,
                    contentType = "image/jpeg"
                )
                println("🎉 Style-based caricature result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
                
                // Example 3: Generate caricature with both style image and text prompt
                val artisticPrompts = lightx.getSuggestedPrompts("artistic")
                val result3 = lightx.generateCaricatureWithStyleAndPrompt(
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
            
            // Example 4: Generate caricature with different style categories
            val categories = listOf("funny", "artistic", "cartoon", "vintage", "modern", "extreme")
            for (category in categories) {
                val prompts = lightx.getSuggestedPrompts(category)
                val result = lightx.generateCaricatureWithPrompt(
                    inputImageFile = inputImageFile,
                    textPrompt = prompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 $category caricature result:")
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
fun runLightXCaricatureExample() {
    runBlocking {
        LightXCaricatureExample.main(emptyArray())
    }
}
