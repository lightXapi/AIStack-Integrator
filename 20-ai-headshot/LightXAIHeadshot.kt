/**
 * LightX AI Headshot Generator API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered professional headshot generation functionality.
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
data class HeadshotUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: HeadshotUploadImageBody
)

@Serializable
data class HeadshotUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class HeadshotGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: HeadshotGenerationBody
)

@Serializable
data class HeadshotGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class HeadshotOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: HeadshotOrderStatusBody
)

@Serializable
data class HeadshotOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX AI Headshot Generator API Client

class LightXAIHeadshotAPI(private val apiKey: String) {
    
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
            throw LightXHeadshotException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Generate professional headshot using AI
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for professional outfit description
     * @return Order ID for tracking
     */
    suspend fun generateHeadshot(imageUrl: String, textPrompt: String): String {
        val endpoint = "$BASE_URL/v2/headshot/"
        
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
            throw LightXHeadshotException("Network error: ${response.statusCode()}")
        }
        
        val headshotResponse = json.decodeFromString<HeadshotGenerationResponse>(response.body())
        
        if (headshotResponse.statusCode != 2000) {
            throw LightXHeadshotException("Headshot generation request failed: ${headshotResponse.message}")
        }
        
        val orderInfo = headshotResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("👔 Professional prompt: \"$textPrompt\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): HeadshotOrderStatusBody {
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
            throw LightXHeadshotException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<HeadshotOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXHeadshotException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): HeadshotOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Professional headshot generated successfully!")
                        status.output?.let { println("👔 Professional headshot: $it") }
                        return status
                    }
                    "failed" -> throw LightXHeadshotException("Professional headshot generation failed")
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
        
        throw LightXHeadshotException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload image and generate professional headshot
     * @param imageFile Image file
     * @param textPrompt Text prompt for professional outfit description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processHeadshotGeneration(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): HeadshotOrderStatusBody {
        println("🚀 Starting LightX AI Headshot Generator API workflow...")
        
        // Step 1: Upload image
        println("📤 Uploading image...")
        val imageUrl = uploadImage(imageFile, contentType)
        println("✅ Image uploaded: $imageUrl")
        
        // Step 2: Generate professional headshot
        println("👔 Generating professional headshot...")
        val orderId = generateHeadshot(imageUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get headshot generation tips and best practices
     * @return Map of tips for better results
     */
    fun getHeadshotTips(): Map<String, List<String>> {
        val tips = mapOf(
            "input_image" to listOf(
                "Use clear, well-lit photos with good face visibility",
                "Ensure the person's face is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best headshot results",
                "Good lighting helps preserve facial features and details"
            ),
            "text_prompts" to listOf(
                "Be specific about the professional look you want to achieve",
                "Include details about outfit style, background, and setting",
                "Mention specific professional attire or business wear",
                "Describe the desired professional appearance clearly",
                "Keep prompts descriptive but concise"
            ),
            "professional_setting" to listOf(
                "Specify professional background settings",
                "Mention corporate or business environments",
                "Include details about lighting and atmosphere",
                "Describe professional color schemes",
                "Specify formal or business-casual settings"
            ),
            "outfit_selection" to listOf(
                "Choose professional attire descriptions",
                "Select business-appropriate clothing",
                "Use formal or business-casual outfit descriptions",
                "Ensure outfit descriptions match professional standards",
                "Choose outfits that complement the person's appearance"
            ),
            "general" to listOf(
                "AI headshot generation works best with clear, detailed source images",
                "Results may vary based on input image quality and prompt clarity",
                "Headshots preserve facial features while enhancing professional appearance",
                "Allow 15-30 seconds for processing",
                "Experiment with different professional prompts for varied results"
            )
        )
        
        println("💡 Headshot Generation Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get professional outfit suggestions
     * @return Map of professional outfit suggestions
     */
    fun getProfessionalOutfitSuggestions(): Map<String, List<String>> {
        val outfitSuggestions = mapOf(
            "business_formal" to listOf(
                "Dark business suit with white dress shirt",
                "Professional blazer with dress pants",
                "Formal business attire with tie",
                "Corporate suit with dress shoes",
                "Executive business wear"
            ),
            "business_casual" to listOf(
                "Blazer with dress shirt and chinos",
                "Professional sweater with dress pants",
                "Business casual blouse with skirt",
                "Smart casual outfit with dress shoes",
                "Professional casual attire"
            ),
            "corporate" to listOf(
                "Corporate dress with blazer",
                "Professional blouse with pencil skirt",
                "Business dress with heels",
                "Corporate suit with accessories",
                "Professional corporate wear"
            ),
            "executive" to listOf(
                "Executive suit with power tie",
                "Professional dress with statement jewelry",
                "Executive blazer with dress pants",
                "Power suit with professional accessories",
                "Executive business attire"
            ),
            "professional" to listOf(
                "Professional blouse with dress pants",
                "Business dress with cardigan",
                "Professional shirt with blazer",
                "Business casual with professional accessories",
                "Professional work attire"
            )
        )
        
        println("💡 Professional Outfit Suggestions:")
        for ((category, suggestionList) in outfitSuggestions) {
            println("$category: $suggestionList")
        }
        return outfitSuggestions
    }
    
    /**
     * Get professional background suggestions
     * @return Map of background suggestions
     */
    fun getProfessionalBackgroundSuggestions(): Map<String, List<String>> {
        val backgroundSuggestions = mapOf(
            "office_settings" to listOf(
                "Modern office background",
                "Corporate office environment",
                "Professional office setting",
                "Business office backdrop",
                "Executive office background"
            ),
            "studio_settings" to listOf(
                "Professional studio background",
                "Clean studio backdrop",
                "Professional photography studio",
                "Studio lighting setup",
                "Professional portrait studio"
            ),
            "neutral_backgrounds" to listOf(
                "Neutral professional background",
                "Clean white background",
                "Professional gray backdrop",
                "Subtle professional background",
                "Minimalist professional setting"
            ),
            "corporate_backgrounds" to listOf(
                "Corporate building background",
                "Business environment backdrop",
                "Professional corporate setting",
                "Executive office background",
                "Corporate headquarters setting"
            ),
            "modern_backgrounds" to listOf(
                "Modern professional background",
                "Contemporary office setting",
                "Sleek professional backdrop",
                "Modern business environment",
                "Contemporary corporate setting"
            )
        )
        
        println("💡 Professional Background Suggestions:")
        for ((category, suggestionList) in backgroundSuggestions) {
            println("$category: $suggestionList")
        }
        return backgroundSuggestions
    }
    
    /**
     * Get headshot prompt examples
     * @return Map of prompt examples
     */
    fun getHeadshotPromptExamples(): Map<String, List<String>> {
        val promptExamples = mapOf(
            "business_formal" to listOf(
                "Create professional headshot with dark business suit and white dress shirt",
                "Generate corporate headshot with formal business attire",
                "Professional headshot with business suit and professional background",
                "Executive headshot with formal business wear and office setting",
                "Corporate headshot with professional suit and business environment"
            ),
            "business_casual" to listOf(
                "Create professional headshot with blazer and dress shirt",
                "Generate business casual headshot with professional attire",
                "Professional headshot with smart casual outfit and office background",
                "Business headshot with professional blouse and corporate setting",
                "Professional headshot with business casual wear and modern office"
            ),
            "corporate" to listOf(
                "Create corporate headshot with professional dress and blazer",
                "Generate executive headshot with corporate attire",
                "Professional headshot with business dress and office environment",
                "Corporate headshot with professional blouse and corporate background",
                "Executive headshot with corporate wear and business setting"
            ),
            "executive" to listOf(
                "Create executive headshot with power suit and professional accessories",
                "Generate leadership headshot with executive attire",
                "Professional headshot with executive suit and corporate office",
                "Executive headshot with professional dress and executive background",
                "Leadership headshot with executive wear and business environment"
            ),
            "professional" to listOf(
                "Create professional headshot with business attire and clean background",
                "Generate professional headshot with corporate wear and office setting",
                "Professional headshot with business casual outfit and professional backdrop",
                "Business headshot with professional attire and modern office background",
                "Professional headshot with corporate wear and business environment"
            )
        )
        
        println("💡 Headshot Prompt Examples:")
        for ((category, exampleList) in promptExamples) {
            println("$category: $exampleList")
        }
        return promptExamples
    }
    
    /**
     * Get headshot use cases and examples
     * @return Map of use case examples
     */
    fun getHeadshotUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "business_profiles" to listOf(
                "LinkedIn professional headshots",
                "Business profile photos",
                "Corporate directory photos",
                "Professional networking photos",
                "Business card headshots"
            ),
            "resumes" to listOf(
                "Resume profile photos",
                "CV headshot photos",
                "Job application photos",
                "Professional resume images",
                "Career profile photos"
            ),
            "corporate" to listOf(
                "Corporate website photos",
                "Company directory headshots",
                "Executive team photos",
                "Corporate communications",
                "Business presentation photos"
            ),
            "professional_networking" to listOf(
                "Professional networking profiles",
                "Business conference photos",
                "Professional association photos",
                "Industry networking photos",
                "Professional community photos"
            ),
            "marketing" to listOf(
                "Professional marketing materials",
                "Business promotional photos",
                "Corporate marketing campaigns",
                "Professional advertising photos",
                "Business marketing content"
            )
        )
        
        println("💡 Headshot Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
    }
    
    /**
     * Get professional style suggestions
     * @return Map of style suggestions
     */
    fun getProfessionalStyleSuggestions(): Map<String, List<String>> {
        val styleSuggestions = mapOf(
            "conservative" to listOf(
                "Traditional business attire",
                "Classic professional wear",
                "Conservative corporate dress",
                "Traditional business suit",
                "Classic professional appearance"
            ),
            "modern" to listOf(
                "Contemporary business attire",
                "Modern professional wear",
                "Current business fashion",
                "Modern corporate dress",
                "Contemporary professional style"
            ),
            "executive" to listOf(
                "Executive business attire",
                "Leadership professional wear",
                "Senior management dress",
                "Executive corporate attire",
                "Leadership professional style"
            ),
            "creative_professional" to listOf(
                "Creative professional attire",
                "Modern creative business wear",
                "Contemporary professional dress",
                "Creative corporate attire",
                "Modern professional creative style"
            ),
            "tech_professional" to listOf(
                "Tech industry professional attire",
                "Modern tech business wear",
                "Contemporary tech professional dress",
                "Tech corporate attire",
                "Modern tech professional style"
            )
        )
        
        println("💡 Professional Style Suggestions:")
        for ((style, suggestionList) in styleSuggestions) {
            println("$style: $suggestionList")
        }
        return styleSuggestions
    }
    
    /**
     * Get headshot best practices
     * @return Map of best practices
     */
    fun getHeadshotBestPractices(): Map<String, List<String>> {
        val bestPractices = mapOf(
            "image_preparation" to listOf(
                "Start with high-quality source images",
                "Ensure good lighting and contrast in the original image",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined facial features",
                "Ensure the person's face is clearly visible and well-lit"
            ),
            "prompt_writing" to listOf(
                "Be specific about the professional look you want to achieve",
                "Include details about outfit style, background, and setting",
                "Mention specific professional attire or business wear",
                "Describe the desired professional appearance clearly",
                "Keep prompts descriptive but concise"
            ),
            "professional_setting" to listOf(
                "Specify professional background settings",
                "Mention corporate or business environments",
                "Include details about lighting and atmosphere",
                "Describe professional color schemes",
                "Specify formal or business-casual settings"
            ),
            "workflow_optimization" to listOf(
                "Batch process multiple headshot variations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            )
        )
        
        println("💡 Headshot Best Practices:")
        for ((category, practiceList) in bestPractices) {
            println("$category: $practiceList")
        }
        return bestPractices
    }
    
    /**
     * Get headshot performance tips
     * @return Map of performance tips
     */
    fun getHeadshotPerformanceTips(): Map<String, List<String>> {
        val performanceTips = mapOf(
            "optimization" to listOf(
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple headshot variations"
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
                "Offer headshot previews when possible",
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
     * Get headshot technical specifications
     * @return Map of technical specifications
     */
    fun getHeadshotTechnicalSpecifications(): Map<String, Map<String, Any>> {
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
                "text_prompts" to "Required for professional outfit description",
                "face_detection" to "Automatic face detection and enhancement",
                "professional_transformation" to "Transforms casual photos into professional headshots",
                "background_enhancement" to "Enhances or changes background to professional setting",
                "output_quality" to "High-quality JPEG output"
            )
        )
        
        println("💡 Headshot Technical Specifications:")
        for ((category, specs) in specifications) {
            println("$category: $specs")
        }
        return specifications
    }
    
    /**
     * Get headshot workflow examples
     * @return Map of workflow examples
     */
    fun getHeadshotWorkflowExamples(): Map<String, List<String>> {
        val workflowExamples = mapOf(
            "basic_workflow" to listOf(
                "1. Prepare high-quality input image with clear face visibility",
                "2. Write descriptive professional outfit prompt",
                "3. Upload image to LightX servers",
                "4. Submit headshot generation request",
                "5. Monitor order status until completion",
                "6. Download professional headshot result"
            ),
            "advanced_workflow" to listOf(
                "1. Prepare input image with clear facial features",
                "2. Create detailed professional prompt with specific attire and background",
                "3. Upload image to LightX servers",
                "4. Submit headshot request with validation",
                "5. Monitor processing with retry logic",
                "6. Validate and download result",
                "7. Apply additional processing if needed"
            ),
            "batch_workflow" to listOf(
                "1. Prepare multiple input images",
                "2. Create consistent professional prompts for batch",
                "3. Upload all images in parallel",
                "4. Submit multiple headshot requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            )
        )
        
        println("💡 Headshot Workflow Examples:")
        for ((workflow, stepList) in workflowExamples) {
            println("$workflow:")
            for (step in stepList) {
                println("  $step")
            }
        }
        return workflowExamples
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
     * Generate headshot with prompt validation
     * @param imageFile Image file
     * @param textPrompt Text prompt for professional outfit description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateHeadshotWithValidation(
        imageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): HeadshotOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXHeadshotException("Invalid text prompt")
        }
        
        return processHeadshotGeneration(imageFile, textPrompt, contentType)
    }
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): HeadshotUploadImageBody {
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
            throw LightXHeadshotException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<HeadshotUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXHeadshotException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXHeadshotException("Image upload failed: ${response.statusCode()}")
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

class LightXHeadshotException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXHeadshotExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXAIHeadshotAPI("YOUR_API_KEY_HERE")
            
            val imageFile = File("path/to/input-image.jpg")
            
            if (!imageFile.exists()) {
                println("❌ Image file not found: ${imageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getHeadshotTips()
            lightx.getProfessionalOutfitSuggestions()
            lightx.getProfessionalBackgroundSuggestions()
            lightx.getHeadshotPromptExamples()
            lightx.getHeadshotUseCases()
            lightx.getProfessionalStyleSuggestions()
            lightx.getHeadshotBestPractices()
            lightx.getHeadshotPerformanceTips()
            lightx.getHeadshotTechnicalSpecifications()
            lightx.getHeadshotWorkflowExamples()
            
            // Example 1: Business formal headshot
            val result1 = lightx.generateHeadshotWithValidation(
                imageFile = imageFile,
                textPrompt = "Create professional headshot with dark business suit and white dress shirt",
                contentType = "image/jpeg"
            )
            println("🎉 Business formal headshot result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Business casual headshot
            val result2 = lightx.generateHeadshotWithValidation(
                imageFile = imageFile,
                textPrompt = "Generate business casual headshot with professional attire",
                contentType = "image/jpeg"
            )
            println("🎉 Business casual headshot result:")
            println("Order ID: ${result2.orderId}")
            println("Status: ${result2.status}")
            result2.output?.let { println("Output: $it") }
            
            // Example 3: Try different professional styles
            val professionalStyles = listOf(
                "Create corporate headshot with professional dress and blazer",
                "Generate executive headshot with corporate attire",
                "Professional headshot with business dress and office environment",
                "Create executive headshot with power suit and professional accessories",
                "Generate leadership headshot with executive attire"
            )
            
            for (style in professionalStyles) {
                val result = lightx.generateHeadshotWithValidation(
                    imageFile = imageFile,
                    textPrompt = style,
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
fun runLightXHeadshotExample() {
    runBlocking {
        LightXHeadshotExample.main(emptyArray())
    }
}
