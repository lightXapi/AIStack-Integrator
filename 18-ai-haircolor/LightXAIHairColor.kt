/**
 * LightX AI Hair Color API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hair color changing functionality.
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
data class HairColorUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: HairColorUploadImageBody
)

@Serializable
data class HairColorUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class HairColorGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: HairColorGenerationBody
)

@Serializable
data class HairColorGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class HairColorOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: HairColorOrderStatusBody
)

@Serializable
data class HairColorOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX AI Hair Color API Client

class LightXAIHairColorAPI(private val apiKey: String) {
    
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
            throw LightXHairColorException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Change hair color using AI
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for hair color description
     * @return Order ID for tracking
     */
    suspend fun changeHairColor(imageUrl: String, textPrompt: String): String {
        val endpoint = "$BASE_URL/v2/haircolor/"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("textPrompt", textPrompt)
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXHairColorException("Network error: ${response.statusCode()}")
        }
        
        val hairColorResponse = json.decodeFromString<HairColorGenerationResponse>(response.body())
        
        if (hairColorResponse.statusCode != 2000) {
            throw LightXHairColorException("Hair color change request failed: ${hairColorResponse.message}")
        }
        
        val orderInfo = hairColorResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🎨 Hair color prompt: \"$textPrompt\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): HairColorOrderStatusBody {
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
            throw LightXHairColorException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<HairColorOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXHairColorException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): HairColorOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Hair color changed successfully!")
                        status.output?.let { println("🎨 New hair color image: $it") }
                        return status
                    }
                    "failed" -> throw LightXHairColorException("Hair color change failed")
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
        
        throw LightXHairColorException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and change hair color
     * @param imageFile Image file
     * @param textPrompt Text prompt for hair color description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processHairColorChange(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): HairColorOrderStatusBody {
        println("🚀 Starting LightX AI Hair Color API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Change hair color
        println("🎨 Changing hair color...")
        val orderId = changeHairColor(imageUrl, textPrompt)
        
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
                "Use clear, well-lit photos with visible hair",
                "Ensure the person's hair is clearly visible in the image",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best hair color results",
                "Good lighting helps preserve hair texture and details"
            ),
            "text_prompts" to listOf(
                "Be specific about the hair color you want to achieve",
                "Include details about shade, tone, and intensity",
                "Mention specific hair color names or descriptions",
                "Describe the desired hair color effect clearly",
                "Keep prompts descriptive but concise"
            ),
            "hair_visibility" to listOf(
                "Ensure hair is clearly visible and not obscured",
                "Avoid images where hair is covered by hats or accessories",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ),
            "general" to listOf(
                "AI hair color works best with clear, detailed source images",
                "Results may vary based on input image quality and hair visibility",
                "Hair color changes preserve original texture and style",
                "Allow 15-30 seconds for processing",
                "Experiment with different color descriptions for varied results"
            )
        )
        
        println("💡 Hair Color Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get hair color suggestions
     * @return Map of hair color suggestions
     */
    fun getHairColorSuggestions(): Map<String, List<String>> {
        val colorSuggestions = mapOf(
            "natural_colors" to listOf(
                "Natural black hair",
                "Dark brown hair",
                "Medium brown hair",
                "Light brown hair",
                "Natural blonde hair",
                "Strawberry blonde hair",
                "Auburn hair",
                "Red hair",
                "Gray hair",
                "Silver hair"
            ),
            "vibrant_colors" to listOf(
                "Bright red hair",
                "Vibrant orange hair",
                "Electric blue hair",
                "Purple hair",
                "Pink hair",
                "Green hair",
                "Yellow hair",
                "Turquoise hair",
                "Magenta hair",
                "Neon colors"
            ),
            "highlights_and_effects" to listOf(
                "Blonde highlights",
                "Brown highlights",
                "Red highlights",
                "Ombre hair effect",
                "Balayage hair effect",
                "Gradient hair colors",
                "Two-tone hair",
                "Color streaks",
                "Peekaboo highlights",
                "Money piece highlights"
            ),
            "trendy_colors" to listOf(
                "Rose gold hair",
                "Platinum blonde hair",
                "Ash blonde hair",
                "Chocolate brown hair",
                "Chestnut brown hair",
                "Copper hair",
                "Burgundy hair",
                "Mahogany hair",
                "Honey blonde hair",
                "Caramel highlights"
            ),
            "fantasy_colors" to listOf(
                "Unicorn hair colors",
                "Mermaid hair colors",
                "Galaxy hair colors",
                "Rainbow hair colors",
                "Pastel hair colors",
                "Metallic hair colors",
                "Holographic hair colors",
                "Chrome hair colors",
                "Iridescent hair colors",
                "Duochrome hair colors"
            )
        )
        
        println("💡 Hair Color Suggestions:")
        for ((category, suggestionList) in colorSuggestions) {
            println("$category: $suggestionList")
        }
        return colorSuggestions
    }
    
    /**
     * Get hair color prompt examples
     * @return Map of prompt examples
     */
    fun getHairColorPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "natural_colors" to listOf(
                "Change hair to natural black color",
                "Transform hair to dark brown shade",
                "Apply medium brown hair color",
                "Change to light brown hair",
                "Transform to natural blonde hair",
                "Apply strawberry blonde hair color",
                "Change to auburn hair color",
                "Transform to natural red hair",
                "Apply gray hair color",
                "Change to silver hair color"
            ),
            "vibrant_colors" to listOf(
                "Change hair to bright red color",
                "Transform to vibrant orange hair",
                "Apply electric blue hair color",
                "Change to purple hair",
                "Transform to pink hair color",
                "Apply green hair color",
                "Change to yellow hair",
                "Transform to turquoise hair",
                "Apply magenta hair color",
                "Change to neon colors"
            ),
            "highlights_and_effects" to listOf(
                "Add blonde highlights to hair",
                "Apply brown highlights",
                "Add red highlights to hair",
                "Create ombre hair effect",
                "Apply balayage hair effect",
                "Create gradient hair colors",
                "Apply two-tone hair colors",
                "Add color streaks to hair",
                "Create peekaboo highlights",
                "Apply money piece highlights"
            ),
            "trendy_colors" to listOf(
                "Change hair to rose gold color",
                "Transform to platinum blonde hair",
                "Apply ash blonde hair color",
                "Change to chocolate brown hair",
                "Transform to chestnut brown hair",
                "Apply copper hair color",
                "Change to burgundy hair",
                "Transform to mahogany hair",
                "Apply honey blonde hair color",
                "Create caramel highlights"
            ),
            "fantasy_colors" to listOf(
                "Create unicorn hair colors",
                "Apply mermaid hair colors",
                "Create galaxy hair colors",
                "Apply rainbow hair colors",
                "Create pastel hair colors",
                "Apply metallic hair colors",
                "Create holographic hair colors",
                "Apply chrome hair colors",
                "Create iridescent hair colors",
                "Apply duochrome hair colors"
            )
        )
        
        println("💡 Hair Color Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
    }
    
    /**
     * Get hair color use cases and examples
     * @return Map of use case examples
     */
    fun getHairColorUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "virtual_try_on" to listOf(
                "Virtual hair color try-on for salons",
                "Hair color consultation tools",
                "Before and after hair color previews",
                "Hair color selection assistance",
                "Virtual hair color makeovers"
            ),
            "beauty_platforms" to listOf(
                "Beauty app hair color features",
                "Hair color recommendation systems",
                "Virtual hair color consultations",
                "Hair color trend exploration",
                "Beauty influencer content creation"
            ),
            "personal_styling" to listOf(
                "Personal hair color experimentation",
                "Hair color change visualization",
                "Style inspiration and exploration",
                "Hair color trend testing",
                "Personal beauty transformations"
            ),
            "marketing" to listOf(
                "Hair color product marketing",
                "Salon service promotion",
                "Hair color brand campaigns",
                "Beauty product demonstrations",
                "Hair color trend showcases"
            ),
            "entertainment" to listOf(
                "Character hair color changes",
                "Costume and makeup design",
                "Creative hair color concepts",
                "Artistic hair color expressions",
                "Fantasy hair color creations"
            )
        )
        
        println("💡 Hair Color Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
    }
    
    /**
     * Get hair color intensity suggestions
     * @return Map of intensity suggestions
     */
    fun getHairColorIntensitySuggestions(): Map<String, List<String>> {
        val intensitySuggestions = mapOf(
            "subtle" to listOf(
                "Apply subtle hair color changes",
                "Add gentle color highlights",
                "Create natural-looking color variations",
                "Apply soft color transitions",
                "Create minimal color effects"
            ),
            "moderate" to listOf(
                "Apply noticeable hair color changes",
                "Create distinct color variations",
                "Add visible color highlights",
                "Apply balanced color effects",
                "Create moderate color transformations"
            ),
            "dramatic" to listOf(
                "Apply bold hair color changes",
                "Create dramatic color transformations",
                "Add vibrant color effects",
                "Apply intense color variations",
                "Create striking color changes"
            )
        )
        
        println("💡 Hair Color Intensity Suggestions:")
        for ((intensity, suggestionList) in intensitySuggestions) {
            println("$intensity: $suggestionList")
        }
        return intensitySuggestions
    }
    
    /**
     * Get hair color category recommendations
     * @return Map of category recommendations
     */
    fun getHairColorCategories(): Map<String, Map<String, Any>> {
        val categories = mapOf(
            "natural" to mapOf(
                "description" to "Natural hair colors that look realistic",
                "examples" to listOf("Black", "Brown", "Blonde", "Red", "Gray"),
                "best_for" to listOf("Professional looks", "Natural appearances", "Everyday styling")
            ),
            "vibrant" to mapOf(
                "description" to "Bright and bold hair colors",
                "examples" to listOf("Electric blue", "Purple", "Pink", "Green", "Orange"),
                "best_for" to listOf("Creative expression", "Bold statements", "Artistic looks")
            ),
            "highlights" to mapOf(
                "description" to "Highlight and lowlight effects",
                "examples" to listOf("Blonde highlights", "Ombre", "Balayage", "Streaks", "Peekaboo"),
                "best_for" to listOf("Subtle changes", "Dimension", "Style enhancement")
            ),
            "trendy" to mapOf(
                "description" to "Current popular hair color trends",
                "examples" to listOf("Rose gold", "Platinum", "Ash blonde", "Copper", "Burgundy"),
                "best_for" to listOf("Fashion-forward looks", "Trend following", "Modern styling")
            ),
            "fantasy" to mapOf(
                "description" to "Creative and fantasy hair colors",
                "examples" to listOf("Unicorn", "Mermaid", "Galaxy", "Rainbow", "Pastel"),
                "best_for" to listOf("Creative projects", "Fantasy themes", "Artistic expression")
            )
        )
        
        println("💡 Hair Color Categories:")
        for ((category, info) in categories) {
            println("$category: ${info["description"]}")
            val examples = info["examples"] as? List<String>
            examples?.let { println("  Examples: ${it.joinToString(", ")}") }
            val bestFor = info["best_for"] as? List<String>
            bestFor?.let { println("  Best for: ${it.joinToString(", ")}") }
        }
        return categories
    }
    
    /**
     * Validate text prompt
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
     * Generate hair color change with prompt validation
     * @param imageFile Image file
     * @param textPrompt Text prompt for hair color description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateHairColorChangeWithValidation(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): HairColorOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXHairColorException("Invalid text prompt")
        }
        
        return processHairColorChange(imageFile, textPrompt, contentType)
    }
    
    /**
     * Get hair color best practices
     * @return Map of best practices
     */
    fun getHairColorBestPractices(): Map<String, List<String>> {
        val bestPractices = mapOf(
            "prompt_writing" to listOf(
                "Be specific about the desired hair color",
                "Include details about shade, tone, and intensity",
                "Mention specific hair color names or descriptions",
                "Describe the desired hair color effect clearly",
                "Keep prompts descriptive but concise"
            ),
            "image_preparation" to listOf(
                "Start with high-quality source images",
                "Ensure hair is clearly visible and well-lit",
                "Avoid heavily compressed or low-quality images",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible"
            ),
            "hair_visibility" to listOf(
                "Ensure hair is not covered by hats or accessories",
                "Use images with good hair definition",
                "Avoid images with extreme angles or poor lighting",
                "Ensure hair texture is visible",
                "Use images where hair is the main focus"
            ),
            "workflow_optimization" to listOf(
                "Batch process multiple images when possible",
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
                "Use batch processing for multiple images"
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
                "Offer hair color previews when possible",
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
                "text_prompts" to "Required for hair color description",
                "hair_detection" to "Automatic hair detection and segmentation",
                "color_preservation" to "Preserves hair texture and style",
                "facial_features" to "Keeps facial features untouched",
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
                "1. Prepare high-quality input image with visible hair",
                "2. Write descriptive hair color prompt",
                "3. Upload image to LightX servers",
                "4. Submit hair color change request",
                "5. Monitor order status until completion",
                "6. Download hair color result"
            ),
            "advanced_workflow" to listOf(
                "1. Prepare input image with clear hair visibility",
                "2. Create detailed hair color prompt with specific details",
                "3. Upload image to LightX servers",
                "4. Submit hair color request with validation",
                "5. Monitor processing with retry logic",
                "6. Validate and download result",
                "7. Apply additional processing if needed"
            ),
            "batch_workflow" to listOf(
                "1. Prepare multiple input images",
                "2. Create consistent hair color prompts for batch",
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
            stepList.forEach { step -> println("  $step") }
        }
        return workflowExamples
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): HairColorUploadImageBody {
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
            throw LightXHairColorException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<HairColorUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXHairColorException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXHairColorException("Image upload failed: ${response.statusCode()}")
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

class LightXHairColorException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXHairColorExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXAIHairColorAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getHairColorTips()
            lightx.getHairColorSuggestions()
            lightx.getHairColorPromptExamples()
            lightx.getHairColorUseCases()
            lightx.getHairColorIntensitySuggestions()
            lightx.getHairColorCategories()
            lightx.getHairColorBestPractices()
            lightx.getHairColorPerformanceTips()
            lightx.getHairColorTechnicalSpecifications()
            lightx.getHairColorWorkflowExamples()
            
            // Example 1: Natural hair colors
            val naturalColors = listOf(
                "Change hair to natural black color",
                "Transform hair to dark brown shade",
                "Apply medium brown hair color",
                "Change to light brown hair",
                "Transform to natural blonde hair"
            )
            
            for (color in naturalColors) {
                val result = lightx.generateHairColorChangeWithValidation(
                    imageFile = imageFile,
                    textPrompt = color,
                    contentType = "image/jpeg"
                )
                println("🎉 $color result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 2: Vibrant hair colors
            val vibrantColors = listOf(
                "Change hair to bright red color",
                "Transform to electric blue hair",
                "Apply purple hair color",
                "Change to pink hair",
                "Transform to green hair color"
            )
            
            for (color in vibrantColors) {
                val result = lightx.generateHairColorChangeWithValidation(
                    imageFile = imageFile,
                    textPrompt = color,
                    contentType = "image/jpeg"
                )
                println("🎉 $color result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 3: Highlights and effects
            val highlights = listOf(
                "Add blonde highlights to hair",
                "Create ombre hair effect",
                "Apply balayage hair effect",
                "Add color streaks to hair",
                "Create peekaboo highlights"
            )
            
            for (highlight in highlights) {
                val result = lightx.generateHairColorChangeWithValidation(
                    imageFile = imageFile,
                    textPrompt = highlight,
                    contentType = "image/jpeg"
                )
                println("🎉 $highlight result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 4: Get image dimensions
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
fun runLightXHairColorExample() {
    runBlocking {
        LightXHairColorExample.main(emptyArray())
    }
}
