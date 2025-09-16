/**
 * LightX AI Filter API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image filtering functionality.
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
data class FilterUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: FilterUploadImageBody
)

@Serializable
data class FilterUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class FilterGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: FilterGenerationBody
)

@Serializable
data class FilterGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class FilterOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: FilterOrderStatusBody
)

@Serializable
data class FilterOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX AI Filter API Client

class LightXAIFilterAPI(private val apiKey: String) {
    
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
            throw LightXFilterException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Generate AI filter
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for filter description
     * @param filterReferenceUrl Optional filter reference image URL
     * @return Order ID for tracking
     */
    suspend fun generateFilter(imageUrl: String, textPrompt: String, filterReferenceUrl: String? = null): String {
        val endpoint = "$BASE_URL/v2/aifilter"
        
        val requestBody = buildJsonObject {
            put("imageUrl", imageUrl)
            put("textPrompt", textPrompt)
            filterReferenceUrl?.let { put("filterReferenceUrl", it) }
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw LightXFilterException("Network error: ${response.statusCode()}")
        }
        
        val filterResponse = json.decodeFromString<FilterGenerationResponse>(response.body())
        
        if (filterResponse.statusCode != 2000) {
            throw LightXFilterException("Filter request failed: ${filterResponse.message}")
        }
        
        val orderInfo = filterResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("🎨 Filter prompt: \"$textPrompt\"")
        filterReferenceUrl?.let { println("🎭 Filter reference: $it") }
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): FilterOrderStatusBody {
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
            throw LightXFilterException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<FilterOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXFilterException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): FilterOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ AI filter applied successfully!")
                        status.output?.let { println("🎨 Filtered image: $it") }
                        return status
                    }
                    "failed" -> throw LightXFilterException("AI filter application failed")
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
        
        throw LightXFilterException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and apply AI filter
     * @param imageFile Image file
     * @param textPrompt Text prompt for filter description
     * @param styleImageFile Optional style image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processFilter(
        imageFile: File, 
        textPrompt: String, 
        styleImageFile: File? = null, 
        contentType: String = "image/jpeg"
    ): FilterOrderStatusBody {
        println("🚀 Starting LightX AI Filter API workflow...")
        
        // Step 1: Upload main image
        println("📤 Uploading main image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Main image uploaded: $imageUrl")
        
        // Step 2: Upload style image if provided
        var styleImageUrl: String? = null
        styleImageFile?.let { styleFile ->
            println("📤 Uploading style image...")
            styleImageUrl = uploadImage(styleFile, contentType)
            println("✅ Style image uploaded: $styleImageUrl")
        }
        
        // Step 3: Generate filter
        println("🎨 Applying AI filter...")
        val orderId = generateFilter(imageUrl, textPrompt, styleImageUrl)
        
        // Step 4: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get AI filter tips and best practices
     * @return Map of tips for better results
     */
    fun getFilterTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit images with good contrast",
                "Ensure the image has good composition and framing",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best filter results",
                "Good source image quality improves filter application"
            ),
            "text_prompts" to listOf(
                "Be specific about the filter style you want to apply",
                "Describe the mood, atmosphere, or artistic style desired",
                "Include details about colors, lighting, and effects",
                "Mention specific artistic movements or styles",
                "Keep prompts descriptive but concise"
            ),
            "style_images" to listOf(
                "Use style images that match your desired aesthetic",
                "Ensure style images have good quality and clarity",
                "Choose style images with strong visual characteristics",
                "Style images work best when they complement the main image",
                "Experiment with different style images for varied results"
            ),
            "general" to listOf(
                "AI filters work best with clear, detailed source images",
                "Results may vary based on input image quality and content",
                "Filters can dramatically transform image appearance and mood",
                "Allow 15-30 seconds for processing",
                "Experiment with different prompts and style combinations"
            )
        )
        
        println("💡 AI Filter Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get filter style suggestions
     * @return Map of style suggestions
     */
    fun getFilterStyleSuggestions(): Map<String, List<String>> {
        val styleSuggestions = mapOf(
            "artistic" to listOf(
                "Oil painting style with rich textures",
                "Watercolor painting with soft edges",
                "Digital art with vibrant colors",
                "Sketch drawing with pencil strokes",
                "Abstract art with geometric shapes"
            ),
            "photography" to listOf(
                "Vintage film photography with grain",
                "Black and white with high contrast",
                "HDR photography with enhanced details",
                "Portrait photography with soft lighting",
                "Street photography with documentary style"
            ),
            "cinematic" to listOf(
                "Film noir with dramatic shadows",
                "Sci-fi with neon colors and effects",
                "Horror with dark, moody atmosphere",
                "Romance with warm, soft lighting",
                "Action with dynamic, high-contrast look"
            ),
            "vintage" to listOf(
                "Retro 80s with neon and synthwave",
                "Vintage 70s with warm, earthy tones",
                "Classic Hollywood glamour",
                "Victorian era with sepia tones",
                "Art Deco with geometric patterns"
            ),
            "modern" to listOf(
                "Minimalist with clean lines",
                "Contemporary with bold colors",
                "Urban with gritty textures",
                "Futuristic with metallic surfaces",
                "Instagram aesthetic with bright colors"
            )
        )
        
        println("💡 Filter Style Suggestions:")
        for ((category, suggestionList) in styleSuggestions) {
            println("$category: $suggestionList")
        }
        return styleSuggestions
    }
    
    /**
     * Get filter prompt examples
     * @return Map of prompt examples
     */
    fun getFilterPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "artistic" to listOf(
                "Transform into oil painting with rich textures and warm colors",
                "Apply watercolor effect with soft, flowing edges",
                "Create digital art style with vibrant, saturated colors",
                "Convert to pencil sketch with detailed line work",
                "Apply abstract art style with geometric patterns"
            ),
            "mood" to listOf(
                "Create mysterious atmosphere with dark shadows and blue tones",
                "Apply warm, romantic lighting with golden hour glow",
                "Transform to dramatic, high-contrast black and white",
                "Create dreamy, ethereal effect with soft pastels",
                "Apply energetic, vibrant style with bold colors"
            ),
            "vintage" to listOf(
                "Apply retro 80s synthwave style with neon colors",
                "Transform to vintage film photography with grain",
                "Create Victorian era aesthetic with sepia tones",
                "Apply Art Deco style with geometric patterns",
                "Transform to classic Hollywood glamour"
            ),
            "modern" to listOf(
                "Apply minimalist style with clean, simple composition",
                "Create contemporary look with bold, modern colors",
                "Transform to urban aesthetic with gritty textures",
                "Apply futuristic style with metallic surfaces",
                "Create Instagram-worthy aesthetic with bright colors"
            ),
            "cinematic" to listOf(
                "Apply film noir style with dramatic lighting",
                "Create sci-fi atmosphere with neon effects",
                "Transform to horror aesthetic with dark mood",
                "Apply romance style with soft, warm lighting",
                "Create action movie look with high contrast"
            )
        )
        
        println("💡 Filter Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
    }
    
    /**
     * Get filter use cases and examples
     * @return Map of use case examples
     */
    fun getFilterUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "social_media" to listOf(
                "Create Instagram-worthy aesthetic filters",
                "Apply trendy social media filters",
                "Transform photos for different platforms",
                "Create consistent brand aesthetic",
                "Enhance photos for social sharing"
            ),
            "marketing" to listOf(
                "Create branded filter effects",
                "Apply campaign-specific aesthetics",
                "Transform product photos with style",
                "Create cohesive visual identity",
                "Enhance marketing materials"
            ),
            "creative" to listOf(
                "Explore artistic styles and effects",
                "Create unique visual interpretations",
                "Experiment with different aesthetics",
                "Transform photos into art pieces",
                "Develop creative visual concepts"
            ),
            "photography" to listOf(
                "Apply professional photo filters",
                "Create consistent editing style",
                "Transform photos for different moods",
                "Apply vintage or retro effects",
                "Enhance photo aesthetics"
            ),
            "personal" to listOf(
                "Create personalized photo styles",
                "Apply favorite artistic effects",
                "Transform memories with filters",
                "Create unique photo collections",
                "Experiment with visual styles"
            )
        )
        
        println("💡 Filter Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
    }
    
    /**
     * Get filter combination suggestions
     * @return Map of combination suggestions
     */
    fun getFilterCombinations(): Map<String, List<String>> {
        val combinations = mapOf(
            "text_only" to listOf(
                "Use descriptive text prompts for specific effects",
                "Combine multiple style descriptions in one prompt",
                "Include mood, lighting, and color specifications",
                "Reference artistic movements or photographers",
                "Describe the desired emotional impact"
            ),
            "text_with_style" to listOf(
                "Use text prompt for overall direction",
                "Add style image for specific visual reference",
                "Combine text description with style image",
                "Use style image to guide color palette",
                "Apply text prompt with style image influence"
            ),
            "style_only" to listOf(
                "Use style image as primary reference",
                "Let style image guide the transformation",
                "Apply style image characteristics to main image",
                "Use style image for color and texture reference",
                "Transform based on style image aesthetic"
            )
        )
        
        println("💡 Filter Combination Suggestions:")
        for ((category, combinationList) in combinations) {
            println("$category: $combinationList")
        }
        return combinations
    }
    
    /**
     * Get filter intensity suggestions
     * @return Map of intensity suggestions
     */
    fun getFilterIntensitySuggestions(): Map<String, List<String>> {
        val intensitySuggestions = mapOf(
            "subtle" to listOf(
                "Apply gentle color adjustments",
                "Add subtle texture overlays",
                "Enhance existing colors slightly",
                "Apply soft lighting effects",
                "Create minimal artistic touches"
            ),
            "moderate" to listOf(
                "Apply noticeable style changes",
                "Transform colors and mood",
                "Add artistic texture effects",
                "Create distinct visual style",
                "Apply balanced filter effects"
            ),
            "dramatic" to listOf(
                "Apply bold, transformative effects",
                "Create dramatic color changes",
                "Add strong artistic elements",
                "Transform image completely",
                "Apply intense visual effects"
            )
        )
        
        println("💡 Filter Intensity Suggestions:")
        for ((intensity, suggestionList) in intensitySuggestions) {
            println("$intensity: $suggestionList")
        }
        return intensitySuggestions
    }
    
    /**
     * Get filter category recommendations
     * @return Map of category recommendations
     */
    fun getFilterCategories(): Map<String, Map<String, Any>> {
        val categories = mapOf(
            "artistic" to mapOf(
                "description" to "Transform images into various artistic styles",
                "examples" to listOf("Oil painting", "Watercolor", "Digital art", "Sketch", "Abstract"),
                "best_for" to listOf("Creative projects", "Artistic expression", "Unique visuals")
            ),
            "vintage" to mapOf(
                "description" to "Apply retro and vintage aesthetics",
                "examples" to listOf("80s synthwave", "Film photography", "Victorian", "Art Deco", "Classic Hollywood"),
                "best_for" to listOf("Nostalgic content", "Retro branding", "Historical themes")
            ),
            "modern" to mapOf(
                "description" to "Apply contemporary and modern styles",
                "examples" to listOf("Minimalist", "Contemporary", "Urban", "Futuristic", "Instagram aesthetic"),
                "best_for" to listOf("Modern branding", "Social media", "Contemporary design")
            ),
            "cinematic" to mapOf(
                "description" to "Create movie-like visual effects",
                "examples" to listOf("Film noir", "Sci-fi", "Horror", "Romance", "Action"),
                "best_for" to listOf("Video content", "Dramatic visuals", "Storytelling")
            ),
            "mood" to mapOf(
                "description" to "Set specific emotional atmospheres",
                "examples" to listOf("Mysterious", "Romantic", "Dramatic", "Dreamy", "Energetic"),
                "best_for" to listOf("Emotional content", "Mood setting", "Atmospheric visuals")
            )
        )
        
        println("💡 Filter Categories:")
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
     * Generate filter with prompt validation
     * @param imageFile Image file
     * @param textPrompt Text prompt for filter description
     * @param styleImageFile Optional style image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateFilterWithValidation(
        imageFile: File, 
        textPrompt: String, 
        styleImageFile: File? = null, 
        contentType: String = "image/jpeg"
    ): FilterOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXFilterException("Invalid text prompt")
        }
        
        return processFilter(imageFile, textPrompt, styleImageFile, contentType)
    }
    
    /**
     * Get filter best practices
     * @return Map of best practices
     */
    fun getFilterBestPractices(): Map<String, List<String>> {
        val bestPractices = mapOf(
            "prompt_writing" to listOf(
                "Be specific about the desired style or effect",
                "Include details about colors, lighting, and mood",
                "Reference specific artistic movements or photographers",
                "Describe the emotional impact you want to achieve",
                "Keep prompts concise but descriptive"
            ),
            "style_image_selection" to listOf(
                "Choose style images with strong visual characteristics",
                "Ensure style images complement your main image",
                "Use high-quality style images for better results",
                "Experiment with different style images",
                "Consider the color palette of style images"
            ),
            "image_preparation" to listOf(
                "Start with high-quality source images",
                "Ensure good lighting and contrast in originals",
                "Avoid heavily compressed or low-quality images",
                "Consider the composition and framing",
                "Use images with clear, well-defined details"
            ),
            "workflow_optimization" to listOf(
                "Batch process multiple images when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            )
        )
        
        println("💡 Filter Best Practices:")
        for ((category, practiceList) in bestPractices) {
            println("$category: $practiceList")
        }
        return bestPractices
    }
    
    /**
     * Get filter performance tips
     * @return Map of performance tips
     */
    fun getFilterPerformanceTips(): Map<String, List<String>> {
        val performanceTips = mapOf(
            "optimization" to listOf(
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before applying filters",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple images"
            ),
            "resource_management" to listOf(
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after filtering",
                "Optimize network requests and retry logic"
            ),
            "user_experience" to listOf(
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer filter previews when possible",
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
     * Get filter technical specifications
     * @return Map of technical specifications
     */
    fun getFilterTechnicalSpecifications(): Map<String, Map<String, Any>> {
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
                "text_prompts" to "Required for filter description",
                "style_images" to "Optional for visual reference",
                "filter_types" to "Artistic, Vintage, Modern, Cinematic, Mood",
                "output_quality" to "High-quality JPEG output"
            )
        )
        
        println("💡 Filter Technical Specifications:")
        for ((category, specs) in specifications) {
            println("$category: $specs")
        }
        return specifications
    }
    
    /**
     * Get filter workflow examples
     * @return Map of workflow examples
     */
    fun getFilterWorkflowExamples(): Map<String, List<String>> {
        val workflowExamples = mapOf(
            "basic_workflow" to listOf(
                "1. Prepare high-quality input image",
                "2. Write descriptive text prompt",
                "3. Upload image to LightX servers",
                "4. Submit filter generation request",
                "5. Monitor order status until completion",
                "6. Download filtered result"
            ),
            "advanced_workflow" to listOf(
                "1. Prepare input image and optional style image",
                "2. Create detailed text prompt with style description",
                "3. Upload both images to LightX servers",
                "4. Submit filter request with text and style",
                "5. Monitor processing with retry logic",
                "6. Validate and download result",
                "7. Apply additional processing if needed"
            ),
            "batch_workflow" to listOf(
                "1. Prepare multiple input images",
                "2. Create consistent text prompts for batch",
                "3. Upload all images in parallel",
                "4. Submit multiple filter requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            )
        )
        
        println("💡 Filter Workflow Examples:")
        for ((workflow, stepList) in workflowExamples) {
            println("$workflow:")
            stepList.forEach { step -> println("  $step") }
        }
        return workflowExamples
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): FilterUploadImageBody {
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
            throw LightXFilterException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<FilterUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXFilterException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXFilterException("Image upload failed: ${response.statusCode()}")
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

class LightXFilterException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXFilterExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXAIFilterAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg") // Optional
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getFilterTips()
            lightx.getFilterStyleSuggestions()
            lightx.getFilterPromptExamples()
            lightx.getFilterUseCases()
            lightx.getFilterCombinations()
            lightx.getFilterIntensitySuggestions()
            lightx.getFilterCategories()
            lightx.getFilterBestPractices()
            lightx.getFilterPerformanceTips()
            lightx.getFilterTechnicalSpecifications()
            lightx.getFilterWorkflowExamples()
            
            // Example 1: Text prompt only
            val result1 = lightx.generateFilterWithValidation(
                imageFile = imageFile,
                textPrompt = "Transform into oil painting with rich textures and warm colors",
                styleImageFile = null, // No style image
                contentType = "image/jpeg"
            )
            println("🎉 Oil painting filter result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Text prompt with style image
            if (styleImageFile.exists()) {
                val result2 = lightx.generateFilterWithValidation(
                    imageFile = imageFile,
                    textPrompt = "Apply vintage film photography style",
                    styleImageFile = styleImageFile, // With style image
                    contentType = "image/jpeg"
                )
                println("🎉 Vintage film filter result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
            }
            
            // Example 3: Try different filter styles
            val filterStyles = listOf(
                "Create watercolor effect with soft, flowing edges",
                "Apply retro 80s synthwave style with neon colors",
                "Transform to dramatic, high-contrast black and white",
                "Create dreamy, ethereal effect with soft pastels",
                "Apply minimalist style with clean, simple composition"
            )
            
            for (style in filterStyles) {
                val result = lightx.generateFilterWithValidation(
                    imageFile = imageFile,
                    textPrompt = style,
                    styleImageFile = null,
                    contentType = "image/jpeg"
                )
                println("🎉 $style result:")
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
fun runLightXFilterExample() {
    runBlocking {
        LightXFilterExample.main(emptyArray())
    }
}
