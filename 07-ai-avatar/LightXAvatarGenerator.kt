/**
 * LightX AI Avatar Generator API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered avatar generation functionality.
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
data class AvatarUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: AvatarUploadImageBody
)

@Serializable
data class AvatarUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class AvatarGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: AvatarGenerationBody
)

@Serializable
data class AvatarGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class AvatarOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: AvatarOrderStatusBody
)

@Serializable
data class AvatarOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Avatar Generator API Client

class LightXAvatarAPI(private val apiKey: String) {
    
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
            throw LightXAvatarException("Image size exceeds 5MB limit")
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
     * Generate avatar
     * @param imageUrl URL of the input image
     * @param styleImageUrl URL of the style image (optional)
     * @param textPrompt Text prompt for avatar style (optional)
     * @return Order ID for tracking
     */
    suspend fun generateAvatar(imageUrl: String, styleImageUrl: String? = null, textPrompt: String? = null): String {
        val endpoint = "$BASE_URL/v1/avatar"
        
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
            throw LightXAvatarException("Network error: ${response.statusCode()}")
        }
        
        val avatarResponse = json.decodeFromString<AvatarGenerationResponse>(response.body())
        
        if (avatarResponse.statusCode != 2000) {
            throw LightXAvatarException("Avatar generation request failed: ${avatarResponse.message}")
        }
        
        val orderInfo = avatarResponse.body
        
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
    suspend fun checkOrderStatus(orderId: String): AvatarOrderStatusBody {
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
            throw LightXAvatarException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<AvatarOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXAvatarException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): AvatarOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Avatar generation completed successfully!")
                        status.output?.let { println("👤 Avatar image: $it") }
                        return status
                    }
                    "failed" -> throw LightXAvatarException("Avatar generation failed")
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
        
        throw LightXAvatarException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate avatar
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file (optional)
     * @param textPrompt Text prompt for avatar style (optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processAvatarGeneration(
        inputImageFile: File, 
        styleImageFile: File? = null, 
        textPrompt: String? = null, 
        contentType: String = "image/jpeg"
    ): AvatarOrderStatusBody {
        println("🚀 Starting LightX AI Avatar Generator API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (inputUrl, styleUrl) = uploadImages(inputImageFile, styleImageFile, contentType)
        println("✅ Input image uploaded: $inputUrl")
        styleUrl?.let { println("✅ Style image uploaded: $it") }
        
        // Step 2: Generate avatar
        println("👤 Generating avatar...")
        val orderId = generateAvatar(inputUrl, styleUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get common text prompts for different avatar styles
     * @param category Category of avatar style
     * @return List of suggested prompts
     */
    fun getSuggestedPrompts(category: String): List<String> {
        val promptSuggestions = mapOf(
            "male" to listOf(
                "professional male avatar",
                "businessman avatar",
                "casual male portrait",
                "formal male avatar",
                "modern male character"
            ),
            "female" to listOf(
                "professional female avatar",
                "businesswoman avatar",
                "casual female portrait",
                "formal female avatar",
                "modern female character"
            ),
            "gaming" to listOf(
                "gaming avatar character",
                "esports player avatar",
                "gamer profile picture",
                "gaming character portrait",
                "pro gamer avatar"
            ),
            "social" to listOf(
                "social media avatar",
                "profile picture avatar",
                "social network avatar",
                "online avatar portrait",
                "digital identity avatar"
            ),
            "artistic" to listOf(
                "artistic avatar style",
                "creative avatar portrait",
                "artistic character design",
                "creative profile avatar",
                "artistic digital portrait"
            ),
            "corporate" to listOf(
                "corporate avatar",
                "professional business avatar",
                "executive avatar portrait",
                "business profile avatar",
                "corporate identity avatar"
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
     * Generate avatar with text prompt only
     * @param inputImageFile Input image file
     * @param textPrompt Text prompt for avatar style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateAvatarWithPrompt(
        inputImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): AvatarOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXAvatarException("Invalid text prompt")
        }
        
        return processAvatarGeneration(inputImageFile, null, textPrompt, contentType)
    }
    
    /**
     * Generate avatar with style image only
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateAvatarWithStyle(
        inputImageFile: File, 
        styleImageFile: File, 
        contentType: String = "image/jpeg"
    ): AvatarOrderStatusBody {
        return processAvatarGeneration(inputImageFile, styleImageFile, null, contentType)
    }
    
    /**
     * Generate avatar with both style image and text prompt
     * @param inputImageFile Input image file
     * @param styleImageFile Style image file
     * @param textPrompt Text prompt for avatar style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateAvatarWithStyleAndPrompt(
        inputImageFile: File, 
        styleImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): AvatarOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXAvatarException("Invalid text prompt")
        }
        
        return processAvatarGeneration(inputImageFile, styleImageFile, textPrompt, contentType)
    }
    
    /**
     * Get avatar tips and best practices
     * @return Map of tips for better avatar results
     */
    fun getAvatarTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for avatars"
            ),
            "style_image" to listOf(
                "Choose style images with clear facial features",
                "Use avatar examples as style references",
                "Ensure style image has good lighting",
                "Match the pose of your input image if possible",
                "Use high-quality style reference images"
            ),
            "text_prompts" to listOf(
                "Be specific about the avatar style you want",
                "Mention gender preference (male/female)",
                "Include professional or casual style preferences",
                "Specify if you want gaming, social, or corporate avatars",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Avatars work best with human faces",
                "Results may vary based on input image quality",
                "Style images influence both artistic style and pose",
                "Text prompts help guide the avatar generation",
                "Allow 15-30 seconds for processing"
            )
        )
        
        println("💡 Avatar Generation Tips:")
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): AvatarUploadImageBody {
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
            throw LightXAvatarException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<AvatarUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXAvatarException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXAvatarException("Image upload failed: ${response.statusCode()}")
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

class LightXAvatarException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXAvatarExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXAvatarAPI("YOUR_API_KEY_HERE")
            
            val inputImageFile = File("path/to/input-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg")
            
            if (!inputImageFile.exists()) {
                println("❌ Input image file not found: ${inputImageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getAvatarTips()
            
            // Example 1: Generate avatar with text prompt only
            val malePrompts = lightx.getSuggestedPrompts("male")
            val result1 = lightx.generateAvatarWithPrompt(
                inputImageFile = inputImageFile,
                textPrompt = malePrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 Male avatar result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Generate avatar with style image only
            if (styleImageFile.exists()) {
                val result2 = lightx.generateAvatarWithStyle(
                    inputImageFile = inputImageFile,
                    styleImageFile = styleImageFile,
                    contentType = "image/jpeg"
                )
                println("🎉 Style-based avatar result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
                
                // Example 3: Generate avatar with both style image and text prompt
                val femalePrompts = lightx.getSuggestedPrompts("female")
                val result3 = lightx.generateAvatarWithStyleAndPrompt(
                    inputImageFile = inputImageFile,
                    styleImageFile = styleImageFile,
                    textPrompt = femalePrompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 Combined style and prompt result:")
                println("Order ID: ${result3.orderId}")
                println("Status: ${result3.status}")
                result3.output?.let { println("Output: $it") }
            }
            
            // Example 4: Generate avatars for different categories
            val categories = listOf("male", "female", "gaming", "social", "artistic", "corporate")
            for (category in categories) {
                val prompts = lightx.getSuggestedPrompts(category)
                val result = lightx.generateAvatarWithPrompt(
                    inputImageFile = inputImageFile,
                    textPrompt = prompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 $category avatar result:")
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
fun runLightXAvatarExample() {
    runBlocking {
        LightXAvatarExample.main(emptyArray())
    }
}
