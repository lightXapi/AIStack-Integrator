/**
 * LightX Hair Color RGB API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hair color changing using hex color codes.
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
data class HairColorRGBUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: HairColorRGBUploadImageBody
)

@Serializable
data class HairColorRGBUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class HairColorRGBGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: HairColorRGBGenerationBody
)

@Serializable
data class HairColorRGBGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class HairColorRGBOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: HairColorRGBOrderStatusBody
)

@Serializable
data class HairColorRGBOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Hair Color RGB API Client

class LightXHairColorRGBAPI(private val apiKey: String) {
    
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
            throw LightXHairColorRGBException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Change hair color using hex color code
     * @param imageUrl URL of the input image
     * @param hairHexColor Hex color code (e.g., "#FF0000")
     * @param colorStrength Color strength between 0.1 to 1
     * @return Order ID for tracking
     */
    suspend fun changeHairColor(imageUrl: String, hairHexColor: String, colorStrength: Double = 0.5): String {
        // Validate hex color
        if (!isValidHexColor(hairHexColor)) {
            throw LightXHairColorRGBException("Invalid hex color format. Use format like #FF0000")
        }
        
        // Validate color strength
        if (colorStrength < 0.1 || colorStrength > 1.0) {
            throw LightXHairColorRGBException("Color strength must be between 0.1 and 1.0")
        }
        
        val endpoint = "$BASE_URL/v2/haircolor-rgb"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("hairHexColor", hairHexColor)
            put("colorStrength", colorStrength)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXHairColorRGBException("Network error: ${response.statusCode()}")
        }
        
        val hairColorResponse = json.decodeFromString<HairColorRGBGenerationResponse>(response.body())
        
        if (hairColorResponse.statusCode != 2000) {
            throw LightXHairColorRGBException("Hair color change request failed: ${hairColorResponse.message}")
        }
        
        val orderInfo = hairColorResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🎨 Hair color: $hairHexColor")
        println("💪 Color strength: $colorStrength")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): HairColorRGBOrderStatusBody {
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
            throw LightXHairColorRGBException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<HairColorRGBOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXHairColorRGBException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): HairColorRGBOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Hair color change completed successfully!")
                        status.output?.let { println("🎨 Hair color result: $it") }
                        return status
                    }
                    "failed" -> throw LightXHairColorRGBException("Hair color change failed")
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
        
        throw LightXHairColorRGBException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and change hair color
     * @param imageFile Image file
     * @param hairHexColor Hex color code
     * @param colorStrength Color strength between 0.1 to 1
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processHairColorChange(
        imageFile: File, 
        hairHexColor: String, 
        colorStrength: Double = 0.5, 
        contentType: String = "image/jpeg"
    ): HairColorRGBOrderStatusBody {
        println("🚀 Starting LightX Hair Color RGB API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Change hair color
        println("🎨 Changing hair color...")
        val orderId = changeHairColor(imageUrl, hairHexColor, colorStrength)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get hair color tips and best practices
     * @return Map of tips for better results
     */
    fun getHairColorTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good hair visibility",
                "Ensure the person's hair is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best hair color results",
                "Good lighting helps preserve hair texture and details"
            ),
            "hex_colors" to listOf(
                "Use valid hex color codes in format #RRGGBB",
                "Common hair colors: #000000 (black), #8B4513 (brown), #FFD700 (blonde)",
                "Experiment with different shades for natural-looking results",
                "Consider skin tone compatibility when choosing colors",
                "Use color strength to control intensity of the color change"
            ),
            "color_strength" to listOf(
                "Lower values (0.1-0.3) create subtle color changes",
                "Medium values (0.4-0.7) provide balanced color intensity",
                "Higher values (0.8-1.0) create bold, vibrant color changes",
                "Start with medium strength and adjust based on results",
                "Consider the original hair color when setting strength"
            ),
            "hair_visibility" to listOf(
                "Ensure the person's hair is clearly visible",
                "Avoid images where hair is heavily covered or obscured",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ),
            "general" to listOf(
                "AI hair color change works best with clear, detailed source images",
                "Results may vary based on input image quality and hair visibility",
                "Hair color change preserves hair texture while changing color",
                "Allow 15-30 seconds for processing",
                "Experiment with different colors and strengths for varied results"
            )
        )
        
        println("💡 Hair Color Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get popular hair color hex codes
     * @return Map of popular hair color hex codes
     */
    fun getPopularHairColors(): Map<String, List<Map<String, Any>>> {
        val hairColors = mapOf(
            "natural_blondes" to listOf(
                mapOf("name" to "Platinum Blonde", "hex" to "#F5F5DC", "strength" to 0.7),
                mapOf("name" to "Golden Blonde", "hex" to "#FFD700", "strength" to 0.6),
                mapOf("name" to "Honey Blonde", "hex" to "#DAA520", "strength" to 0.5),
                mapOf("name" to "Strawberry Blonde", "hex" to "#D2691E", "strength" to 0.6),
                mapOf("name" to "Ash Blonde", "hex" to "#C0C0C0", "strength" to 0.5)
            ),
            "natural_browns" to listOf(
                mapOf("name" to "Light Brown", "hex" to "#8B4513", "strength" to 0.5),
                mapOf("name" to "Medium Brown", "hex" to "#654321", "strength" to 0.6),
                mapOf("name" to "Dark Brown", "hex" to "#3C2414", "strength" to 0.7),
                mapOf("name" to "Chestnut Brown", "hex" to "#954535", "strength" to 0.5),
                mapOf("name" to "Auburn Brown", "hex" to "#A52A2A", "strength" to 0.6)
            ),
            "natural_blacks" to listOf(
                mapOf("name" to "Jet Black", "hex" to "#000000", "strength" to 0.8),
                mapOf("name" to "Soft Black", "hex" to "#1C1C1C", "strength" to 0.7),
                mapOf("name" to "Blue Black", "hex" to "#0A0A0A", "strength" to 0.8),
                mapOf("name" to "Brown Black", "hex" to "#2F1B14", "strength" to 0.6)
            ),
            "fashion_colors" to listOf(
                mapOf("name" to "Vibrant Red", "hex" to "#FF0000", "strength" to 0.8),
                mapOf("name" to "Purple", "hex" to "#800080", "strength" to 0.7),
                mapOf("name" to "Blue", "hex" to "#0000FF", "strength" to 0.7),
                mapOf("name" to "Pink", "hex" to "#FF69B4", "strength" to 0.6),
                mapOf("name" to "Green", "hex" to "#008000", "strength" to 0.6),
                mapOf("name" to "Orange", "hex" to "#FFA500", "strength" to 0.7)
            ),
            "highlights" to listOf(
                mapOf("name" to "Blonde Highlights", "hex" to "#FFD700", "strength" to 0.4),
                mapOf("name" to "Red Highlights", "hex" to "#FF4500", "strength" to 0.3),
                mapOf("name" to "Purple Highlights", "hex" to "#9370DB", "strength" to 0.3),
                mapOf("name" to "Blue Highlights", "hex" to "#4169E1", "strength" to 0.3)
            )
        )
        
        println("💡 Popular Hair Colors:")
        for ((category, colorList) in hairColors) {
            println("$category:")
            for (color in colorList) {
                val name = color["name"] as String
                val hex = color["hex"] as String
                val strength = color["strength"] as Double
                println("  $name: $hex (strength: $strength)")
            }
        }
        return hairColors
    }
    
    /**
     * Get hair color use cases and examples
     * @return Map of use case examples
     */
    fun getHairColorUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "virtual_makeovers" to listOf(
                "Virtual hair color try-on",
                "Makeover simulation apps",
                "Beauty consultation tools",
                "Personal styling experiments",
                "Virtual hair color previews"
            ),
            "beauty_platforms" to listOf(
                "Beauty app hair color features",
                "Salon consultation tools",
                "Hair color recommendation systems",
                "Beauty influencer content",
                "Hair color trend visualization"
            ),
            "personal_styling" to listOf(
                "Personal style exploration",
                "Hair color decision making",
                "Style consultation services",
                "Personal fashion experiments",
                "Individual styling assistance"
            ),
            "social_media" to listOf(
                "Social media hair color posts",
                "Beauty influencer content",
                "Hair color sharing platforms",
                "Beauty community features",
                "Social beauty experiences"
            ),
            "entertainment" to listOf(
                "Character hair color changes",
                "Costume design and visualization",
                "Creative hair color concepts",
                "Artistic hair color expressions",
                "Entertainment industry applications"
            )
        )
        
        println("💡 Hair Color Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
    }
    
    /**
     * Get hair color best practices
     * @return Map of best practices
     */
    fun getHairColorBestPractices(): Map<String, List<String>> {
        val bestPractices = mapOf(
            "image_preparation" to listOf(
                "Start with high-quality source images",
                "Ensure good lighting and contrast in the original image",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined hair details",
                "Ensure the person's hair is clearly visible and well-lit"
            ),
            "color_selection" to listOf(
                "Choose hex colors that complement skin tone",
                "Consider the original hair color when selecting new colors",
                "Use color strength to control the intensity of change",
                "Experiment with different shades for natural results",
                "Test multiple color options to find the best match"
            ),
            "strength_control" to listOf(
                "Start with medium strength (0.5) and adjust as needed",
                "Use lower strength for subtle, natural-looking changes",
                "Use higher strength for bold, dramatic color changes",
                "Consider the contrast with the original hair color",
                "Balance color intensity with natural appearance"
            ),
            "workflow_optimization" to listOf(
                "Batch process multiple color variations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            )
        )
        
        println("💡 Hair Color Best Practices:")
        for ((category, practiceList) in bestPractices) {
            println("$category: $practiceList")
        }
        return bestPractices
    }
    
    /**
     * Get hair color performance tips
     * @return Map of performance tips
     */
    fun getHairColorPerformanceTips(): Map<String, List<String>> {
        val performanceTips = mapOf(
            "optimization" to listOf(
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple color variations"
            ),
            "resource_management" to listOf(
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after processing",
                "Optimize network requests and retry logic"
            ),
            "user_experience" to listOf(
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer color previews when possible",
                "Provide tips for better input images"
            )
        )
        
        println("💡 Performance Tips:")
        for ((category, tipList) in performanceTips) {
            println("$category: $tipList")
        }
        return performanceTips
    }
    
    /**
     * Get hair color technical specifications
     * @return Map of technical specifications
     */
    fun getHairColorTechnicalSpecifications(): Map<String, Map<String, Any>> {
        val specifications = mapOf(
            "supported_formats" to mapOf(
                "input" to listOf("JPEG", "PNG"),
                "output" to listOf("JPEG"),
                "color_spaces" to listOf("RGB", "sRGB")
            ),
            "size_limits" to mapOf(
                "max_file_size" to "5MB",
                "max_dimension" to "No specific limit",
                "min_dimension" to "1px"
            ),
            "processing" to mapOf(
                "max_retries" to 5,
                "retry_interval" to "3 seconds",
                "avg_processing_time" to "15-30 seconds",
                "timeout" to "No timeout limit"
            ),
            "features" to mapOf(
                "hex_color_codes" to "Required for precise color specification",
                "color_strength" to "Controls intensity of color change (0.1 to 1.0)",
                "hair_detection" to "Automatic hair detection and segmentation",
                "texture_preservation" to "Preserves original hair texture and style",
                "output_quality" to "High-quality JPEG output"
            )
        )
        
        println("💡 Hair Color Technical Specifications:")
        for ((category, specs) in specifications) {
            println("$category: $specs")
        }
        return specifications
    }
    
    /**
     * Get hair color workflow examples
     * @return Map of workflow examples
     */
    fun getHairColorWorkflowExamples(): Map<String, List<String>> {
        val workflowExamples = mapOf(
            "basic_workflow" to listOf(
                "1. Prepare high-quality input image with clear hair visibility",
                "2. Choose desired hair color hex code",
                "3. Set appropriate color strength (0.1 to 1.0)",
                "4. Upload image to LightX servers",
                "5. Submit hair color change request",
                "6. Monitor order status until completion",
                "7. Download hair color result"
            ),
            "advanced_workflow" to listOf(
                "1. Prepare input image with clear hair definition",
                "2. Select multiple color options for comparison",
                "3. Set different strength values for each color",
                "4. Upload image to LightX servers",
                "5. Submit multiple hair color requests",
                "6. Monitor all orders with retry logic",
                "7. Compare and select best results",
                "8. Apply additional processing if needed"
            ),
            "batch_workflow" to listOf(
                "1. Prepare multiple input images",
                "2. Create color palette for batch processing",
                "3. Upload all images in parallel",
                "4. Submit multiple hair color requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            )
        )
        
        println("💡 Hair Color Workflow Examples:")
        for ((workflow, stepList) in workflowExamples) {
            println("$workflow:")
            for (step in stepList) {
                println("  $step")
            }
        }
        return workflowExamples
    }
    
    /**
     * Validate hex color format
     * @param hexColor Hex color to validate
     * @return Whether the hex color is valid
     */
    fun isValidHexColor(hexColor: String): Boolean {
        val hexPattern = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$".toRegex()
        return hexPattern.matches(hexColor)
    }
    
    /**
     * Convert RGB to hex color
     * @param r Red value (0-255)
     * @param g Green value (0-255)
     * @param b Blue value (0-255)
     * @return Hex color code
     */
    fun rgbToHex(r: Int, g: Int, b: Int): String {
        val toHex = { n: Int -> 
            val hex = maxOf(0, minOf(255, n)).toString(16)
            if (hex.length == 1) "0$hex" else hex
        }
        return "#${toHex(r)}${toHex(g)}${toHex(b)}".uppercase()
    }
    
    /**
     * Convert hex color to RGB
     * @param hex Hex color code
     * @return RGB values or null if invalid
     */
    fun hexToRgb(hex: String): Triple<Int, Int, Int>? {
        val cleanHex = hex.removePrefix("#")
        return when (cleanHex.length) {
            3 -> {
                val r = cleanHex.substring(0, 1).repeat(2).toInt(16)
                val g = cleanHex.substring(1, 2).repeat(2).toInt(16)
                val b = cleanHex.substring(2, 3).repeat(2).toInt(16)
                Triple(r, g, b)
            }
            6 -> {
                val r = cleanHex.substring(0, 2).toInt(16)
                val g = cleanHex.substring(2, 4).toInt(16)
                val b = cleanHex.substring(4, 6).toInt(16)
                Triple(r, g, b)
            }
            else -> null
        }
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
     * Change hair color with validation
     * @param imageFile Image file
     * @param hairHexColor Hex color code
     * @param colorStrength Color strength between 0.1 to 1
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun changeHairColorWithValidation(
        imageFile: File, 
        hairHexColor: String, 
        colorStrength: Double = 0.5, 
        contentType: String = "image/jpeg"
    ): HairColorRGBOrderStatusBody {
        if (!isValidHexColor(hairHexColor)) {
            throw LightXHairColorRGBException("Invalid hex color format. Use format like #FF0000")
        }
        
        if (colorStrength < 0.1 || colorStrength > 1.0) {
            throw LightXHairColorRGBException("Color strength must be between 0.1 and 1.0")
        }
        
        return processHairColorChange(imageFile, hairHexColor, colorStrength, contentType)
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): HairColorRGBUploadImageBody {
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
            throw LightXHairColorRGBException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<HairColorRGBUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXHairColorRGBException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXHairColorRGBException("Image upload failed: ${response.statusCode()}")
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

class LightXHairColorRGBException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXHairColorRGBExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXHairColorRGBAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getHairColorTips()
            lightx.getPopularHairColors()
            lightx.getHairColorUseCases()
            lightx.getHairColorBestPractices()
            lightx.getHairColorPerformanceTips()
            lightx.getHairColorTechnicalSpecifications()
            lightx.getHairColorWorkflowExamples()
            
            // Example 1: Natural blonde hair
            val result1 = lightx.changeHairColorWithValidation(
                imageFile = imageFile,
                hairHexColor = "#FFD700", // Golden blonde
                colorStrength = 0.6, // Medium strength
                contentType = "image/jpeg"
            )
            println("🎉 Golden blonde result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Fashion color - vibrant red
            val result2 = lightx.changeHairColorWithValidation(
                imageFile = imageFile,
                hairHexColor = "#FF0000", // Vibrant red
                colorStrength = 0.8, // High strength
                contentType = "image/jpeg"
            )
            println("🎉 Vibrant red result:")
            println("Order ID: ${result2.orderId}")
            println("Status: ${result2.status}")
            result2.output?.let { println("Output: $it") }
            
            // Example 3: Try different hair colors
            val hairColors = listOf(
                Triple("#000000", "Jet Black", 0.8),
                Triple("#8B4513", "Light Brown", 0.5),
                Triple("#800080", "Purple", 0.7),
                Triple("#FF69B4", "Pink", 0.6),
                Triple("#0000FF", "Blue", 0.7)
            )
            
            for ((color, name, strength) in hairColors) {
                val result = lightx.changeHairColorWithValidation(
                    imageFile = imageFile,
                    hairHexColor = color,
                    colorStrength = strength,
                    contentType = "image/jpeg"
                )
                println("🎉 $name result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 4: Color conversion utilities
            val rgb = lightx.hexToRgb("#FFD700")
            rgb?.let { (r, g, b) ->
                println("RGB values for #FFD700: r:$r, g:$g, b:$b")
            }
            
            val hex = lightx.rgbToHex(255, 215, 0)
            println("Hex for RGB(255, 215, 0): $hex")
            
            // Example 5: Get image dimensions
            val dimensions = lightx.getImageDimensions(imageFile)
            if (dimensions.first > 0 && dimensions.second > 0) {
                println("📏 Original image: ${dimensions.first}x${dimensions.second}")
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
fun runLightXHairColorRGBExample() {
    runBlocking {
        LightXHairColorRGBExample.main(emptyArray())
    }
}
