/**
 * LightX Watermark Remover API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered watermark removal functionality.
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
data class WatermarkRemoverUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: WatermarkRemoverUploadImageBody
)

@Serializable
data class WatermarkRemoverUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class WatermarkRemoverGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: WatermarkRemoverGenerationBody
)

@Serializable
data class WatermarkRemoverGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class WatermarkRemoverOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: WatermarkRemoverOrderStatusBody
)

@Serializable
data class WatermarkRemoverOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Watermark Remover API Client

class LightXWatermarkRemoverAPI(private val apiKey: String) {
    
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
            throw LightXWatermarkRemoverException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Remove watermark from image
     * @param imageUrl URL of the input image
     * @return Order ID for tracking
     */
    suspend fun removeWatermark(imageUrl: String): String {
        val endpoint = "$BASE_URL/v2/watermark-remover/"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXWatermarkRemoverException("Network error: ${response.statusCode()}")
        }
        
        val watermarkRemoverResponse = json.decodeFromString<WatermarkRemoverGenerationResponse>(response.body())
        
        if (watermarkRemoverResponse.statusCode != 2000) {
            throw LightXWatermarkRemoverException("Watermark removal request failed: ${watermarkRemoverResponse.message}")
        }
        
        val orderInfo = watermarkRemoverResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🖼️  Input image: $imageUrl")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): WatermarkRemoverOrderStatusBody {
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
            throw LightXWatermarkRemoverException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<WatermarkRemoverOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXWatermarkRemoverException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): WatermarkRemoverOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Watermark removed successfully!")
                        if (status.output != null) {
                            println("🖼️  Clean image: ${status.output}")
                        }
                        return status
                    }
                    "failed" -> {
                        throw LightXWatermarkRemoverException("Watermark removal failed")
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
                
            } catch (e: LightXWatermarkRemoverException) {
                throw e
            } catch (e: Exception) {
                attempts++
                if (attempts >= MAX_RETRIES) {
                    throw LightXWatermarkRemoverException("Maximum retry attempts reached: ${e.message}")
                }
                println("⚠️  Error on attempt $attempts, retrying...")
                delay(RETRY_INTERVAL)
            }
        }
        
        throw LightXWatermarkRemoverException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and remove watermark
     * @param imageFile File containing the image data
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processWatermarkRemoval(imageFile: File, contentType: String = "image/jpeg"): WatermarkRemoverOrderStatusBody {
        println("🚀 Starting LightX Watermark Remover API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Remove watermark
        println("🧹 Removing watermark...")
        val orderId = removeWatermark(imageUrl)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get watermark removal tips and best practices
     * @return Object containing tips for better results
     */
    fun getWatermarkRemovalTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use high-quality images with clear watermarks",
                "Ensure the image is at least 512x512 pixels",
                "Avoid heavily compressed or low-quality source images",
                "Use images with good contrast and lighting",
                "Ensure watermarks are clearly visible in the image"
            ),
            "watermark_types" to listOf(
                "Text watermarks: Works best with clear, readable text",
                "Logo watermarks: Effective with distinct logo shapes",
                "Pattern watermarks: Good for repetitive patterns",
                "Transparent watermarks: Handles semi-transparent overlays",
                "Complex watermarks: May require multiple processing attempts"
            ),
            "image_quality" to listOf(
                "Higher resolution images produce better results",
                "Good lighting and contrast improve watermark detection",
                "Avoid images with excessive noise or artifacts",
                "Clear, sharp images work better than blurry ones",
                "Well-exposed images provide better results"
            ),
            "general" to listOf(
                "AI watermark removal works best with clearly visible watermarks",
                "Results may vary based on watermark complexity and image quality",
                "Allow 15-30 seconds for processing",
                "Some watermarks may require multiple processing attempts",
                "The tool preserves image quality while removing watermarks"
            )
        )
        
        println("💡 Watermark Removal Tips:")
        tips.forEach { (category, tipList) ->
            println("$category: $tipList")
        }
        
        return tips
    }
    
    /**
     * Get watermark removal use cases and examples
     * @return Object containing use case examples
     */
    fun getWatermarkRemovalUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "e_commerce" to listOf(
                "Remove watermarks from product photos",
                "Clean up stock images for online stores",
                "Prepare images for product catalogs",
                "Remove branding from supplier images",
                "Create clean product listings"
            ),
            "photo_editing" to listOf(
                "Remove watermarks from edited photos",
                "Clean up images for personal use",
                "Remove copyright watermarks",
                "Prepare images for printing",
                "Clean up stock photo watermarks"
            ),
            "news_publishing" to listOf(
                "Remove watermarks from news images",
                "Clean up press photos",
                "Remove agency watermarks",
                "Prepare images for articles",
                "Clean up editorial images"
            ),
            "social_media" to listOf(
                "Remove watermarks from social media images",
                "Clean up images for posts",
                "Remove branding from shared images",
                "Prepare images for profiles",
                "Clean up user-generated content"
            ),
            "creative_projects" to listOf(
                "Remove watermarks from design assets",
                "Clean up images for presentations",
                "Remove branding from templates",
                "Prepare images for portfolios",
                "Clean up creative resources"
            )
        )
        
        println("💡 Watermark Removal Use Cases:")
        useCases.forEach { (category, useCaseList) ->
            println("$category: $useCaseList")
        }
        
        return useCases
    }
    
    /**
     * Get supported image formats and requirements
     * @return Object containing format information
     */
    fun getSupportedFormats(): Map<String, Map<String, String>> {
        val formats = mapOf(
            "input_formats" to mapOf(
                "JPEG" to "Most common format, good for photos",
                "PNG" to "Supports transparency, good for graphics",
                "WebP" to "Modern format with good compression"
            ),
            "output_format" to mapOf(
                "JPEG" to "Standard output format for compatibility"
            ),
            "requirements" to mapOf(
                "minimum_size" to "512x512 pixels",
                "maximum_size" to "5MB file size",
                "color_space" to "RGB or sRGB",
                "compression" to "Any standard compression level"
            ),
            "recommendations" to mapOf(
                "resolution" to "Higher resolution images produce better results",
                "quality" to "Use high-quality source images",
                "format" to "JPEG is recommended for photos",
                "size" to "Larger images allow better watermark detection"
            )
        )
        
        println("📋 Supported Formats and Requirements:")
        formats.forEach { (category, info) ->
            println("$category: $info")
        }
        
        return formats
    }
    
    /**
     * Get watermark detection capabilities
     * @return Object containing detection information
     */
    fun getWatermarkDetectionCapabilities(): Map<String, List<String>> {
        val capabilities = mapOf(
            "detection_types" to listOf(
                "Text watermarks with various fonts and styles",
                "Logo watermarks with different shapes and colors",
                "Pattern watermarks with repetitive designs",
                "Transparent watermarks with varying opacity",
                "Complex watermarks with multiple elements"
            ),
            "coverage_areas" to listOf(
                "Full image watermarks covering the entire image",
                "Corner watermarks in specific image areas",
                "Center watermarks in the middle of images",
                "Scattered watermarks across multiple areas",
                "Border watermarks along image edges"
            ),
            "processing_features" to listOf(
                "Automatic watermark detection and removal",
                "Preserves original image quality and details",
                "Maintains image composition and structure",
                "Handles various watermark sizes and positions",
                "Works with different image backgrounds"
            ),
            "limitations" to listOf(
                "Very small or subtle watermarks may be challenging",
                "Watermarks that blend with image content",
                "Extremely complex or artistic watermarks",
                "Watermarks that are part of the main subject",
                "Very low resolution or poor quality images"
            )
        )
        
        println("🔍 Watermark Detection Capabilities:")
        capabilities.forEach { (category, capabilityList) ->
            println("$category: $capabilityList")
        }
        
        return capabilities
    }
    
    /**
     * Validate image file (utility function)
     * @param imageFile Image file to validate
     * @return Whether the image file is valid
     */
    fun validateImageFile(imageFile: File): Boolean {
        if (!imageFile.exists()) {
            println("❌ Image file does not exist")
            return false
        }
        
        if (imageFile.length() == 0L) {
            println("❌ Image file is empty")
            return false
        }
        
        if (imageFile.length() > MAX_FILE_SIZE) {
            println("❌ Image file size exceeds 5MB limit")
            return false
        }
        
        println("✅ Image file is valid")
        return true
    }
    
    /**
     * Process watermark removal with validation
     * @param imageFile File containing the image data
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processWatermarkRemovalWithValidation(imageFile: File, contentType: String = "image/jpeg"): WatermarkRemoverOrderStatusBody {
        if (!validateImageFile(imageFile)) {
            throw LightXWatermarkRemoverException("Invalid image file")
        }
        
        return processWatermarkRemoval(imageFile, contentType)
    }
    
    // Private methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): WatermarkRemoverUploadImageBody {
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
            throw LightXWatermarkRemoverException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<WatermarkRemoverUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXWatermarkRemoverException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXWatermarkRemoverException("Image upload failed: ${response.statusCode()}")
        }
    }
}

// MARK: - Exception Classes

class LightXWatermarkRemoverException(message: String) : Exception(message)

// MARK: - Example Usage

suspend fun runExample() {
    try {
        // Initialize with your API key
        val lightx = LightXWatermarkRemoverAPI("YOUR_API_KEY_HERE")
        
        // Get tips and information
        lightx.getWatermarkRemovalTips()
        lightx.getWatermarkRemovalUseCases()
        lightx.getSupportedFormats()
        lightx.getWatermarkDetectionCapabilities()
        
        // Example 1: Process image from file
        val imageFile = File("path/to/watermarked-image.jpg")
        val result1 = lightx.processWatermarkRemovalWithValidation(
            imageFile,
            "image/jpeg"
        )
        println("🎉 Watermark removal result:")
        println("Order ID: ${result1.orderId}")
        println("Status: ${result1.status}")
        result1.output?.let { println("Output: $it") }
        
        // Example 2: Process another image
        val imageFile2 = File("path/to/another-image.png")
        val result2 = lightx.processWatermarkRemovalWithValidation(
            imageFile2,
            "image/png"
        )
        println("🎉 Second watermark removal result:")
        println("Order ID: ${result2.orderId}")
        println("Status: ${result2.status}")
        result2.output?.let { println("Output: $it") }
        
        // Example 3: Process multiple images
        val imagePaths = listOf(
            "path/to/image1.jpg",
            "path/to/image2.png",
            "path/to/image3.jpg"
        )
        
        imagePaths.forEach { imagePath ->
            val imageFile = File(imagePath)
            val result = lightx.processWatermarkRemovalWithValidation(
                imageFile,
                "image/jpeg"
            )
            println("🎉 $imagePath watermark removal result:")
            println("Order ID: ${result.orderId}")
            println("Status: ${result.status}")
            result.output?.let { println("Output: $it") }
        }
        
    } catch (e: LightXWatermarkRemoverException) {
        println("❌ Example failed: ${e.message}")
    }
}

// Run example if this file is executed directly
fun main() = runBlocking {
    runExample()
}
