/**
 * LightX AI Face Swap API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered face swap functionality.
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
data class FaceSwapUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: FaceSwapUploadImageBody
)

@Serializable
data class FaceSwapUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class FaceSwapGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: FaceSwapGenerationBody
)

@Serializable
data class FaceSwapGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class FaceSwapOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: FaceSwapOrderStatusBody
)

@Serializable
data class FaceSwapOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Face Swap API Client

class LightXFaceSwapAPI(private val apiKey: String) {
    
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
            throw LightXFaceSwapException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Upload multiple images (source and target images)
     * @param sourceImageFile Source image file
     * @param targetImageFile Target image file
     * @param contentType MIME type
     * @return Pair of (sourceURL, targetURL)
     */
    suspend fun uploadImages(sourceImageFile: File, targetImageFile: File, 
                           contentType: String = "image/jpeg"): Pair<String, String> {
        println("📤 Uploading source image...")
        val sourceUrl = uploadImage(sourceImageFile, contentType)
        
        println("📤 Uploading target image...")
        val targetUrl = uploadImage(targetImageFile, contentType)
        
        return Pair(sourceUrl, targetUrl)
    }
    
    /**
     * Generate face swap
     * @param imageUrl URL of the source image (face to be swapped)
     * @param styleImageUrl URL of the target image (face to be replaced)
     * @return Order ID for tracking
     */
    suspend fun generateFaceSwap(imageUrl: String, styleImageUrl: String): String {
        val endpoint = "$BASE_URL/v1/face-swap"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("styleImageUrl", styleImageUrl)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXFaceSwapException("Network error: ${response.statusCode()}")
        }
        
        val faceSwapResponse = json.decodeFromString<FaceSwapGenerationResponse>(response.body())
        
        if (faceSwapResponse.statusCode != 2000) {
            throw LightXFaceSwapException("Face swap request failed: ${faceSwapResponse.message}")
        }
        
        val orderInfo = faceSwapResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🎭 Source image: $imageUrl")
        println("🎯 Target image: $styleImageUrl")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): FaceSwapOrderStatusBody {
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
            throw LightXFaceSwapException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<FaceSwapOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXFaceSwapException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): FaceSwapOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Face swap completed successfully!")
                        status.output?.let { println("🔄 Face swap result: $it") }
                        return status
                    }
                    "failed" -> throw LightXFaceSwapException("Face swap failed")
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
        
        throw LightXFaceSwapException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate face swap
     * @param sourceImageFile Source image file
     * @param targetImageFile Target image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processFaceSwap(
        sourceImageFile: File, 
        targetImageFile: File, 
        contentType: String = "image/jpeg"
    ): FaceSwapOrderStatusBody {
        println("🚀 Starting LightX AI Face Swap API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (sourceUrl, targetUrl) = uploadImages(sourceImageFile, targetImageFile, contentType)
        println("✅ Source image uploaded: $sourceUrl")
        println("✅ Target image uploaded: $targetUrl")
        
        // Step 2: Generate face swap
        println("🔄 Generating face swap...")
        val orderId = generateFaceSwap(sourceUrl, targetUrl)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get face swap tips and best practices
     * @return Map of tips for better face swap results
     */
    fun getFaceSwapTips(): Map<String, List<String>> {
        val tips = mapOf(
            "source_image" to listOf(
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for face swapping"
            ),
            "target_image" to listOf(
                "Choose target images with clear facial features",
                "Ensure target face is clearly visible and well-lit",
                "Use images with similar lighting conditions",
                "Avoid heavily edited or filtered images",
                "Use high-quality target reference images"
            ),
            "general" to listOf(
                "Face swaps work best with clear human faces",
                "Results may vary based on input image quality",
                "Similar lighting conditions improve results",
                "Front-facing photos produce better face swaps",
                "Allow 15-30 seconds for processing"
            )
        )
        
        println("💡 Face Swap Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get face swap use cases and examples
     * @return Map of use case examples
     */
    fun getFaceSwapUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "entertainment" to listOf(
                "Movie character face swaps",
                "Celebrity face swaps",
                "Historical figure face swaps",
                "Fantasy character face swaps",
                "Comedy and entertainment content"
            ),
            "creative" to listOf(
                "Artistic face swap projects",
                "Creative photo manipulation",
                "Digital art creation",
                "Social media content",
                "Memes and viral content"
            ),
            "professional" to listOf(
                "Film and video production",
                "Marketing and advertising",
                "Educational content",
                "Training materials",
                "Presentation graphics"
            ),
            "personal" to listOf(
                "Fun personal photos",
                "Family photo editing",
                "Social media posts",
                "Party and event photos",
                "Creative selfies"
            )
        )
        
        println("💡 Face Swap Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
    }
    
    /**
     * Get face swap quality improvement tips
     * @return Map of quality improvement tips
     */
    fun getFaceSwapQualityTips(): Map<String, List<String>> {
        val qualityTips = mapOf(
            "lighting" to listOf(
                "Use similar lighting conditions in both images",
                "Avoid harsh shadows on faces",
                "Ensure even lighting across the face",
                "Natural lighting often produces better results",
                "Avoid backlit or silhouette images"
            ),
            "angle" to listOf(
                "Use front-facing photos for best results",
                "Avoid extreme angles or tilted heads",
                "Keep faces centered in the frame",
                "Similar head angles improve face swap quality",
                "Avoid profile shots for optimal results"
            ),
            "resolution" to listOf(
                "Use high-resolution images when possible",
                "Ensure clear facial features are visible",
                "Avoid heavily compressed images",
                "Good image quality improves face swap accuracy",
                "Minimum 512x512 pixels recommended"
            ),
            "expression" to listOf(
                "Neutral expressions often work best",
                "Similar facial expressions improve results",
                "Avoid extreme expressions or emotions",
                "Natural expressions produce better face swaps",
                "Consider the context of the target image"
            )
        )
        
        println("💡 Face Swap Quality Tips:")
        for ((category, tipList) in qualityTips) {
            println("$category: $tipList")
        }
        return qualityTips
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
    
    /**
     * Validate images for face swap (utility function)
     * @param sourceImageFile Source image file
     * @param targetImageFile Target image file
     * @return Whether images are valid for face swap
     */
    fun validateImagesForFaceSwap(sourceImageFile: File, targetImageFile: File): Boolean {
        val sourceDimensions = getImageDimensions(sourceImageFile)
        val targetDimensions = getImageDimensions(targetImageFile)
        
        if (sourceDimensions.first == 0 || targetDimensions.first == 0) {
            println("❌ Invalid image dimensions detected")
            return false
        }
        
        // Additional validation could include:
        // - Face detection
        // - Image quality assessment
        // - Lighting condition analysis
        // - Resolution requirements
        
        println("✅ Images validated for face swap")
        return true
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): FaceSwapUploadImageBody {
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
            throw LightXFaceSwapException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<FaceSwapUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXFaceSwapException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXFaceSwapException("Image upload failed: ${response.statusCode()}")
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

class LightXFaceSwapException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXFaceSwapExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXFaceSwapAPI("YOUR_API_KEY_HERE")
            
            val sourceImageFile = File("path/to/source-image.jpg")
            val targetImageFile = File("path/to/target-image.jpg")
            
            if (!sourceImageFile.exists() || !targetImageFile.exists()) {
                println("❌ Image files not found")
                return
            }
            
            // Get tips for better results
            lightx.getFaceSwapTips()
            lightx.getFaceSwapUseCases()
            lightx.getFaceSwapQualityTips()
            
            // Validate images before processing
            val isValid = lightx.validateImagesForFaceSwap(sourceImageFile, targetImageFile)
            if (!isValid) {
                println("❌ Images are not suitable for face swap")
                return
            }
            
            // Example 1: Basic face swap
            val result1 = lightx.processFaceSwap(
                sourceImageFile = sourceImageFile,
                targetImageFile = targetImageFile,
                contentType = "image/jpeg"
            )
            println("🎉 Face swap result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Get image dimensions
            val sourceDimensions = lightx.getImageDimensions(sourceImageFile)
            val targetDimensions = lightx.getImageDimensions(targetImageFile)
            
            if (sourceDimensions.first > 0 && sourceDimensions.second > 0) {
                println("📏 Source image: ${sourceDimensions.first}x${sourceDimensions.second}")
            }
            if (targetDimensions.first > 0 && targetDimensions.second > 0) {
                println("📏 Target image: ${targetDimensions.first}x${targetDimensions.second}")
            }
            
            // Example 3: Multiple face swaps with different image pairs
            val imagePairs = listOf(
                Pair(sourceImageFile, targetImageFile),
                Pair(sourceImageFile, targetImageFile),
                Pair(sourceImageFile, targetImageFile)
            )
            
            for ((index, pair) in imagePairs.withIndex()) {
                println("\n🎭 Processing face swap ${index + 1}...")
                
                try {
                    val result = lightx.processFaceSwap(
                        sourceImageFile = pair.first,
                        targetImageFile = pair.second,
                        contentType = "image/jpeg"
                    )
                    println("✅ Face swap ${index + 1} completed:")
                    println("Order ID: ${result.orderId}")
                    println("Status: ${result.status}")
                    result.output?.let { println("Output: $it") }
                } catch (e: Exception) {
                    println("❌ Face swap ${index + 1} failed: ${e.message}")
                }
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
fun runLightXFaceSwapExample() {
    runBlocking {
        LightXFaceSwapExample.main(emptyArray())
    }
}
