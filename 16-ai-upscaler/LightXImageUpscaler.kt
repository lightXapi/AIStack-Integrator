/**
 * LightX AI Image Upscaler API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image upscaling functionality.
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
data class UpscalerUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: UpscalerUploadImageBody
)

@Serializable
data class UpscalerUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class UpscalerGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: UpscalerGenerationBody
)

@Serializable
data class UpscalerGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class UpscalerOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: UpscalerOrderStatusBody
)

@Serializable
data class UpscalerOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Image Upscaler API Client

class LightXImageUpscalerAPI(private val apiKey: String) {
    
    companion object {
        private const val BASE_URL = "https://api.lightxeditor.com/external/api"
        private const val MAX_RETRIES = 5
        private const val RETRY_INTERVAL = 3000L // milliseconds
        private const val MAX_FILE_SIZE = 5242880L // 5MB
        private const val MAX_IMAGE_DIMENSION = 2048
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
            throw LightXUpscalerException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Generate image upscaling
     * @param imageUrl URL of the input image
     * @param quality Upscaling quality (2 or 4)
     * @return Order ID for tracking
     */
    suspend fun generateUpscale(imageUrl: String, quality: Int): String {
        val endpoint = "$BASE_URL/v2/upscale/"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("quality", quality)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXUpscalerException("Network error: ${response.statusCode()}")
        }
        
        val upscalerResponse = json.decodeFromString<UpscalerGenerationResponse>(response.body())
        
        if (upscalerResponse.statusCode != 2000) {
            throw LightXUpscalerException("Upscale request failed: ${upscalerResponse.message}")
        }
        
        val orderInfo = upscalerResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🔍 Upscale quality: ${quality}x")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): UpscalerOrderStatusBody {
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
            throw LightXUpscalerException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<UpscalerOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXUpscalerException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): UpscalerOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Image upscaling completed successfully!")
                        status.output?.let { println("🔍 Upscaled image: $it") }
                        return status
                    }
                    "failed" -> throw LightXUpscalerException("Image upscaling failed")
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
        
        throw LightXUpscalerException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and generate upscaling
     * @param imageFile Image file
     * @param quality Upscaling quality (2 or 4)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processUpscaling(
        imageFile: File, 
        quality: Int, 
        contentType: String = "image/jpeg"
    ): UpscalerOrderStatusBody {
        println("🚀 Starting LightX AI Image Upscaler API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Generate upscaling
        println("🔍 Generating image upscaling...")
        val orderId = generateUpscale(imageUrl, quality)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get image upscaling tips and best practices
     * @return Map of tips for better results
     */
    fun getUpscalingTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit images with good contrast",
                "Ensure the image is not already at maximum resolution",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best upscaling results",
                "Good source image quality improves upscaling results"
            ),
            "image_dimensions" to listOf(
                "Images 1024x1024 or smaller can be upscaled 2x or 4x",
                "Images larger than 1024x1024 but smaller than 2048x2048 can only be upscaled 2x",
                "Images larger than 2048x2048 cannot be upscaled",
                "Check image dimensions before attempting upscaling",
                "Resize large images before upscaling if needed"
            ),
            "quality_selection" to listOf(
                "Use 2x upscaling for moderate quality improvement",
                "Use 4x upscaling for maximum quality improvement",
                "4x upscaling works best on smaller images (1024x1024 or less)",
                "Consider file size increase with higher upscaling factors",
                "Choose quality based on your specific needs"
            ),
            "general" to listOf(
                "Image upscaling works best with clear, detailed source images",
                "Results may vary based on input image quality and content",
                "Upscaling preserves detail while enhancing resolution",
                "Allow 15-30 seconds for processing",
                "Experiment with different quality settings for optimal results"
            )
        )
        
        println("💡 Image Upscaling Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get upscaling quality suggestions
     * @return Map of quality suggestions
     */
    fun getQualitySuggestions(): Map<String, Map<String, Any>> {
        val qualitySuggestions = mapOf(
            "2x" to mapOf(
                "description" to "Moderate quality improvement with 2x resolution increase",
                "best_for" to listOf(
                    "General image enhancement",
                    "Social media images",
                    "Web display images",
                    "Moderate quality improvement needs",
                    "Balanced quality and file size"
                ),
                "use_cases" to listOf(
                    "Enhancing photos for social media",
                    "Improving web images",
                    "General image quality enhancement",
                    "Preparing images for print at moderate sizes"
                )
            ),
            "4x" to mapOf(
                "description" to "Maximum quality improvement with 4x resolution increase",
                "best_for" to listOf(
                    "High-quality image enhancement",
                    "Print-ready images",
                    "Professional photography",
                    "Maximum detail preservation",
                    "Large format displays"
                ),
                "use_cases" to listOf(
                    "Professional photography enhancement",
                    "Print-ready image preparation",
                    "Large format display images",
                    "Maximum quality requirements",
                    "Archival image enhancement"
                )
            )
        )
        
        println("💡 Upscaling Quality Suggestions:")
        for ((quality, suggestion) in qualitySuggestions) {
            println("$quality: ${suggestion["description"]}")
            val bestFor = suggestion["best_for"] as? List<String>
            bestFor?.let { println("  Best for: ${it.joinToString(", ")}") }
            val useCases = suggestion["use_cases"] as? List<String>
            useCases?.let { println("  Use cases: ${it.joinToString(", ")}") }
        }
        return qualitySuggestions
    }
    
    /**
     * Get image dimension guidelines
     * @return Map of dimension guidelines
     */
    fun getDimensionGuidelines(): Map<String, Map<String, Any>> {
        val dimensionGuidelines = mapOf(
            "small_images" to mapOf(
                "range" to "Up to 1024x1024 pixels",
                "upscaling_options" to listOf("2x upscaling", "4x upscaling"),
                "description" to "Small images can be upscaled with both 2x and 4x quality",
                "examples" to listOf("Profile pictures", "Thumbnails", "Small photos", "Icons")
            ),
            "medium_images" to mapOf(
                "range" to "1024x1024 to 2048x2048 pixels",
                "upscaling_options" to listOf("2x upscaling only"),
                "description" to "Medium images can only be upscaled with 2x quality",
                "examples" to listOf("Standard photos", "Web images", "Medium prints")
            ),
            "large_images" to mapOf(
                "range" to "Larger than 2048x2048 pixels",
                "upscaling_options" to listOf("Cannot be upscaled"),
                "description" to "Large images cannot be upscaled and will show an error",
                "examples" to listOf("High-resolution photos", "Large prints", "Professional images")
            )
        )
        
        println("💡 Image Dimension Guidelines:")
        for ((category, info) in dimensionGuidelines) {
            println("$category: ${info["range"]}")
            val options = info["upscaling_options"] as? List<String>
            options?.let { println("  Upscaling options: ${it.joinToString(", ")}") }
            println("  Description: ${info["description"]}")
            val examples = info["examples"] as? List<String>
            examples?.let { println("  Examples: ${it.joinToString(", ")}") }
        }
        return dimensionGuidelines
    }
    
    /**
     * Get upscaling use cases and examples
     * @return Map of use case examples
     */
    fun getUpscalingUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "photography" to listOf(
                "Enhance low-resolution photos",
                "Prepare images for large prints",
                "Improve vintage photo quality",
                "Enhance smartphone photos",
                "Professional photo enhancement"
            ),
            "web_design" to listOf(
                "Create high-DPI images for retina displays",
                "Enhance images for modern web standards",
                "Improve image quality for websites",
                "Create responsive image assets",
                "Enhance social media images"
            ),
            "print_media" to listOf(
                "Prepare images for large format printing",
                "Enhance images for magazine quality",
                "Improve poster and banner images",
                "Enhance images for professional printing",
                "Create high-resolution marketing materials"
            ),
            "archival" to listOf(
                "Enhance historical photographs",
                "Improve scanned document quality",
                "Restore old family photos",
                "Enhance archival images",
                "Preserve and enhance historical content"
            ),
            "creative" to listOf(
                "Enhance digital art and illustrations",
                "Improve concept art quality",
                "Enhance graphic design elements",
                "Improve texture and pattern quality",
                "Enhance creative project assets"
            )
        )
        
        println("💡 Upscaling Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
    }
    
    /**
     * Validate parameters
     * @param quality Quality parameter to validate
     * @param width Image width to validate
     * @param height Image height to validate
     * @return Whether the parameters are valid
     */
    fun validateParameters(quality: Int, width: Int, height: Int): Boolean {
        // Validate quality
        if (quality != 2 && quality != 4) {
            println("❌ Quality must be 2 or 4")
            return false
        }
        
        // Validate image dimensions
        val maxDimension = maxOf(width, height)
        
        if (maxDimension > MAX_IMAGE_DIMENSION) {
            println("❌ Image dimension (${maxDimension}px) exceeds maximum allowed (${MAX_IMAGE_DIMENSION}px)")
            return false
        }
        
        // Check quality vs dimension compatibility
        if (maxDimension > 1024 && quality == 4) {
            println("❌ 4x upscaling is only available for images 1024x1024 or smaller")
            return false
        }
        
        println("✅ Parameters are valid")
        return true
    }
    
    /**
     * Get image dimensions
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
     * Generate upscaling with parameter validation
     * @param imageFile Image file
     * @param quality Upscaling quality (2 or 4)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateUpscaleWithValidation(
        imageFile: File, 
        quality: Int, 
        contentType: String = "image/jpeg"
    ): UpscalerOrderStatusBody {
        val dimensions = getImageDimensions(imageFile)
        
        if (!validateParameters(quality, dimensions.first, dimensions.second)) {
            throw LightXUpscalerException("Invalid parameters")
        }
        
        return processUpscaling(imageFile, quality, contentType)
    }
    
    /**
     * Get recommended quality based on image dimensions
     * @param width Image width
     * @param height Image height
     * @return Recommended quality options
     */
    fun getRecommendedQuality(width: Int, height: Int): Map<String, Any> {
        val maxDimension = maxOf(width, height)
        
        return when {
            maxDimension <= 1024 -> mapOf(
                "available" to listOf(2, 4),
                "recommended" to 4,
                "reason" to "Small images can use 4x upscaling for maximum quality"
            )
            maxDimension <= 2048 -> mapOf(
                "available" to listOf(2),
                "recommended" to 2,
                "reason" to "Medium images can only use 2x upscaling"
            )
            else -> mapOf(
                "available" to emptyList<Int>(),
                "recommended" to null,
                "reason" to "Large images cannot be upscaled"
            )
        }
    }
    
    /**
     * Get quality comparison between 2x and 4x upscaling
     * @return Map of comparison information
     */
    fun getQualityComparison(): Map<String, Map<String, Any>> {
        val comparison = mapOf(
            "2x_upscaling" to mapOf(
                "resolution_increase" to "4x total pixels (2x width × 2x height)",
                "file_size_increase" to "Approximately 4x larger",
                "processing_time" to "Faster processing",
                "best_for" to listOf(
                    "General image enhancement",
                    "Web and social media use",
                    "Moderate quality improvement",
                    "Balanced quality and file size"
                ),
                "limitations" to listOf(
                    "Less dramatic quality improvement",
                    "May not be sufficient for large prints"
                )
            ),
            "4x_upscaling" to mapOf(
                "resolution_increase" to "16x total pixels (4x width × 4x height)",
                "file_size_increase" to "Approximately 16x larger",
                "processing_time" to "Longer processing time",
                "best_for" to listOf(
                    "Maximum quality enhancement",
                    "Large format printing",
                    "Professional photography",
                    "Archival image enhancement"
                ),
                "limitations" to listOf(
                    "Only available for images ≤1024x1024",
                    "Much larger file sizes",
                    "Longer processing time"
                )
            )
        )
        
        println("💡 Quality Comparison:")
        for ((quality, info) in comparison) {
            println("$quality:")
            for ((key, value) in info) {
                when (value) {
                    is List<*> -> println("  $key: ${(value as List<String>).joinToString(", ")}")
                    else -> println("  $key: $value")
                }
            }
        }
        return comparison
    }
    
    /**
     * Get technical specifications for upscaling
     * @return Map of technical specifications
     */
    fun getTechnicalSpecifications(): Map<String, Map<String, Any>> {
        val specifications = mapOf(
            "supported_formats" to mapOf(
                "input" to listOf("JPEG", "PNG"),
                "output" to listOf("JPEG"),
                "color_spaces" to listOf("RGB", "sRGB")
            ),
            "size_limits" to mapOf(
                "max_file_size" to "5MB",
                "max_dimension" to "2048px",
                "min_dimension" to "1px"
            ),
            "quality_options" to mapOf(
                "2x" to "Available for all supported image sizes",
                "4x" to "Only available for images ≤1024x1024px"
            ),
            "processing" to mapOf(
                "max_retries" to 5,
                "retry_interval" to "3 seconds",
                "avg_processing_time" to "15-30 seconds",
                "timeout" to "No timeout limit"
            )
        )
        
        println("💡 Technical Specifications:")
        for ((category, specs) in specifications) {
            println("$category: $specs")
        }
        return specifications
    }
    
    /**
     * Get upscaling best practices
     * @return Map of best practices
     */
    fun getBestPractices(): Map<String, List<String>> {
        val bestPractices = mapOf(
            "image_preparation" to listOf(
                "Start with the highest quality source image available",
                "Ensure good lighting and contrast in the original image",
                "Avoid images with heavy compression artifacts",
                "Use images with clear, well-defined details",
                "Consider the final use case when choosing upscaling quality"
            ),
            "quality_selection" to listOf(
                "Use 2x for general enhancement and web use",
                "Use 4x for print and professional applications",
                "Consider file size implications of higher quality settings",
                "Test different quality settings to find the optimal balance",
                "Match quality selection to your specific use case"
            ),
            "workflow_optimization" to listOf(
                "Batch process multiple images when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations accordingly",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ),
            "output_handling" to listOf(
                "Verify output quality meets your requirements",
                "Consider implementing quality comparison tools",
                "Store both original and upscaled versions when needed",
                "Implement proper file naming conventions",
                "Consider compression settings for final output"
            )
        )
        
        println("💡 Upscaling Best Practices:")
        for ((category, practiceList) in bestPractices) {
            println("$category: $practiceList")
        }
        return bestPractices
    }
    
    /**
     * Get upscaling performance tips
     * @return Map of performance tips
     */
    fun getPerformanceTips(): Map<String, List<String>> {
        val performanceTips = mapOf(
            "optimization" to listOf(
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before upscaling",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple images"
            ),
            "resource_management" to listOf(
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after upscaling",
                "Optimize network requests and retry logic"
            ),
            "user_experience" to listOf(
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer quality previews when possible",
                "Provide tips for better input images"
            )
        )
        
        println("💡 Performance Tips:")
        for ((category, tipList) in performanceTips) {
            println("$category: $tipList")
        }
        return performanceTips
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): UpscalerUploadImageBody {
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
            throw LightXUpscalerException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<UpscalerUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXUpscalerException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXUpscalerException("Image upload failed: ${response.statusCode()}")
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

class LightXUpscalerException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXUpscalerExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXImageUpscalerAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getUpscalingTips()
            lightx.getQualitySuggestions()
            lightx.getDimensionGuidelines()
            lightx.getUpscalingUseCases()
            lightx.getQualityComparison()
            lightx.getTechnicalSpecifications()
            lightx.getBestPractices()
            lightx.getPerformanceTips()
            
            // Example 1: 2x upscaling
            val result1 = lightx.generateUpscaleWithValidation(
                imageFile = imageFile,
                quality = 2, // 2x upscaling
                contentType = "image/jpeg"
            )
            println("🎉 2x upscaling result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: 4x upscaling (if image is small enough)
            val dimensions = lightx.getImageDimensions(imageFile)
            val qualityRecommendation = lightx.getRecommendedQuality(dimensions.first, dimensions.second)
            
            val available = qualityRecommendation["available"] as? List<Int>
            if (available?.contains(4) == true) {
                val result2 = lightx.generateUpscaleWithValidation(
                    imageFile = imageFile,
                    quality = 4, // 4x upscaling
                    contentType = "image/jpeg"
                )
                println("🎉 4x upscaling result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
            } else {
                val reason = qualityRecommendation["reason"] as? String
                println("⚠️  4x upscaling not available for this image size: $reason")
            }
            
            // Example 3: Try different quality settings
            val qualityOptions = listOf(2, 4)
            for (quality in qualityOptions) {
                try {
                    val result = lightx.generateUpscaleWithValidation(
                        imageFile = imageFile,
                        quality = quality,
                        contentType = "image/jpeg"
                    )
                    println("🎉 ${quality}x upscaling result:")
                    println("Order ID: ${result.orderId}")
                    println("Status: ${result.status}")
                    result.output?.let { println("Output: $it") }
                } catch (e: Exception) {
                    println("❌ ${quality}x upscaling failed: ${e.message}")
                }
            }
            
            // Example 4: Get image dimensions and recommendations
            val finalDimensions = lightx.getImageDimensions(imageFile)
            if (finalDimensions.first > 0 && finalDimensions.second > 0) {
                println("📏 Original image: ${finalDimensions.first}x${finalDimensions.second}")
                val recommendation = lightx.getRecommendedQuality(finalDimensions.first, finalDimensions.second)
                val recommended = recommendation["recommended"] as? Int
                val reason = recommendation["reason"] as? String
                println("💡 Recommended quality: ${recommended}x ($reason)")
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
fun runLightXUpscalerExample() {
    runBlocking {
        LightXUpscalerExample.main(emptyArray())
    }
}
