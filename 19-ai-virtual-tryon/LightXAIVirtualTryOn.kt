/**
 * LightX AI Virtual Outfit Try-On API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered virtual outfit try-on functionality.
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
data class VirtualTryOnUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: VirtualTryOnUploadImageBody
)

@Serializable
data class VirtualTryOnUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class VirtualTryOnGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: VirtualTryOnGenerationBody
)

@Serializable
data class VirtualTryOnGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class VirtualTryOnOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: VirtualTryOnOrderStatusBody
)

@Serializable
data class VirtualTryOnOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX AI Virtual Outfit Try-On API Client

class LightXAIVirtualTryOnAPI(private val apiKey: String) {
    
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
            throw LightXVirtualTryOnException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Try on virtual outfit using AI
     * @param imageUrl URL of the input image (person)
     * @param styleImageUrl URL of the outfit reference image
     * @return Order ID for tracking
     */
    suspend fun tryOnOutfit(imageUrl: String, styleImageUrl: String): String {
        val endpoint = "$BASE_URL/v2/aivirtualtryon"
        
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
            throw LightXVirtualTryOnException("Network error: ${response.statusCode()}")
        }
        
        val virtualTryOnResponse = json.decodeFromString<VirtualTryOnGenerationResponse>(response.body())
        
        if (virtualTryOnResponse.statusCode != 2000) {
            throw LightXVirtualTryOnException("Virtual try-on request failed: ${virtualTryOnResponse.message}")
        }
        
        val orderInfo = virtualTryOnResponse.body
        
        println("📋 Order created: ${orderInfo.orderId}")
        println("🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}")
        println("⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds")
        println("📊 Status: ${orderInfo.status}")
        println("👤 Person image: $imageUrl")
        println("👗 Outfit image: $styleImageUrl")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    suspend fun checkOrderStatus(orderId: String): VirtualTryOnOrderStatusBody {
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
            throw LightXVirtualTryOnException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<VirtualTryOnOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXVirtualTryOnException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): VirtualTryOnOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Virtual outfit try-on completed successfully!")
                        status.output?.let { println("👗 Virtual try-on result: $it") }
                        return status
                    }
                    "failed" -> throw LightXVirtualTryOnException("Virtual outfit try-on failed")
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
        
        throw LightXVirtualTryOnException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and try on virtual outfit
     * @param personImageFile Person image file
     * @param outfitImageFile Outfit reference image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processVirtualTryOn(
        personImageFile: File, 
        outfitImageFile: File, 
        contentType: String = "image/jpeg"
    ): VirtualTryOnOrderStatusBody {
        println("🚀 Starting LightX AI Virtual Outfit Try-On API workflow...")
        
        // Step 1: Upload person image
        println("📤 Uploading person image...")
        val personImageUrl = uploadImage(personImageFile, contentType)
        println("✅ Person image uploaded: $personImageUrl")
        
        // Step 2: Upload outfit image
        println("📤 Uploading outfit image...")
        val outfitImageUrl = uploadImage(outfitImageFile, contentType)
        println("✅ Outfit image uploaded: $outfitImageUrl")
        
        // Step 3: Try on virtual outfit
        println("👗 Trying on virtual outfit...")
        val orderId = tryOnOutfit(personImageUrl, outfitImageUrl)
        
        // Step 4: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get virtual try-on tips and best practices
     * @return Map of tips for better results
     */
    fun getVirtualTryOnTips(): Map<String, List<String>> {
        val tips = mapOf(
            "person_image" to listOf(
                "Use clear, well-lit photos with good body visibility",
                "Ensure the person is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best virtual try-on results",
                "Good lighting helps preserve body shape and details"
            ),
            "outfit_image" to listOf(
                "Use clear outfit reference images with good detail",
                "Ensure the outfit is clearly visible and well-lit",
                "Choose outfit images with good color and texture definition",
                "Use high-quality outfit images for better transfer results",
                "Good outfit image quality improves virtual try-on accuracy"
            ),
            "body_visibility" to listOf(
                "Ensure the person's body is clearly visible",
                "Avoid images where the person is heavily covered",
                "Use images with good body definition and posture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ),
            "outfit_selection" to listOf(
                "Choose outfit images that match the person's body type",
                "Select outfits with clear, well-defined shapes",
                "Use outfit images with good color contrast",
                "Ensure outfit images show the complete garment",
                "Choose outfits that complement the person's style"
            ),
            "general" to listOf(
                "AI virtual try-on works best with clear, detailed source images",
                "Results may vary based on input image quality and outfit visibility",
                "Virtual try-on preserves body shape and facial features",
                "Allow 15-30 seconds for processing",
                "Experiment with different outfit combinations for varied results"
            )
        )
        
        println("💡 Virtual Try-On Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get outfit category suggestions
     * @return Map of outfit category suggestions
     */
    fun getOutfitCategorySuggestions(): Map<String, List<String>> {
        val outfitCategories = mapOf(
            "casual" to listOf(
                "Casual t-shirts and jeans",
                "Comfortable hoodies and sweatpants",
                "Everyday dresses and skirts",
                "Casual blouses and trousers",
                "Relaxed shirts and shorts"
            ),
            "formal" to listOf(
                "Business suits and blazers",
                "Formal dresses and gowns",
                "Dress shirts and dress pants",
                "Professional blouses and skirts",
                "Elegant evening wear"
            ),
            "party" to listOf(
                "Party dresses and outfits",
                "Cocktail dresses and suits",
                "Festive clothing and accessories",
                "Celebration wear and costumes",
                "Special occasion outfits"
            ),
            "seasonal" to listOf(
                "Summer dresses and shorts",
                "Winter coats and sweaters",
                "Spring jackets and light layers",
                "Fall clothing and warm accessories",
                "Seasonal fashion trends"
            ),
            "sportswear" to listOf(
                "Athletic wear and gym clothes",
                "Sports jerseys and team wear",
                "Activewear and workout gear",
                "Running clothes and sneakers",
                "Fitness and sports apparel"
            )
        )
        
        println("💡 Outfit Category Suggestions:")
        for ((category, suggestionList) in outfitCategories) {
            println("$category: $suggestionList")
        }
        return outfitCategories
    }
    
    /**
     * Get virtual try-on use cases and examples
     * @return Map of use case examples
     */
    fun getVirtualTryOnUseCases(): Map<String, List<String>> {
        val useCases = mapOf(
            "e_commerce" to listOf(
                "Online shopping virtual try-on",
                "E-commerce product visualization",
                "Online store outfit previews",
                "Virtual fitting room experiences",
                "Online shopping assistance"
            ),
            "fashion_retail" to listOf(
                "Fashion store virtual try-on",
                "Retail outfit visualization",
                "In-store virtual fitting",
                "Fashion consultation tools",
                "Retail customer experience"
            ),
            "personal_styling" to listOf(
                "Personal style exploration",
                "Virtual wardrobe try-on",
                "Style consultation services",
                "Personal fashion experiments",
                "Individual styling assistance"
            ),
            "social_media" to listOf(
                "Social media outfit posts",
                "Fashion influencer content",
                "Style sharing platforms",
                "Fashion community features",
                "Social fashion experiences"
            ),
            "entertainment" to listOf(
                "Character outfit changes",
                "Costume design and visualization",
                "Creative outfit concepts",
                "Artistic fashion expressions",
                "Entertainment industry applications"
            )
        )
        
        println("💡 Virtual Try-On Use Cases:")
        for ((category, useCaseList) in useCases) {
            println("$category: $useCaseList")
        }
        return useCases
    }
    
    /**
     * Get outfit style suggestions
     * @return Map of style suggestions
     */
    fun getOutfitStyleSuggestions(): Map<String, List<String>> {
        val styleSuggestions = mapOf(
            "classic" to listOf(
                "Classic business attire",
                "Traditional formal wear",
                "Timeless casual outfits",
                "Classic evening wear",
                "Traditional professional dress"
            ),
            "modern" to listOf(
                "Contemporary fashion trends",
                "Modern casual wear",
                "Current style favorites",
                "Trendy outfit combinations",
                "Modern fashion statements"
            ),
            "vintage" to listOf(
                "Retro fashion styles",
                "Vintage clothing pieces",
                "Classic era outfits",
                "Nostalgic fashion trends",
                "Historical style references"
            ),
            "bohemian" to listOf(
                "Bohemian style outfits",
                "Free-spirited fashion",
                "Artistic clothing choices",
                "Creative style expressions",
                "Alternative fashion trends"
            ),
            "minimalist" to listOf(
                "Simple, clean outfits",
                "Minimalist fashion choices",
                "Understated style pieces",
                "Clean, modern aesthetics",
                "Simple fashion statements"
            )
        )
        
        println("💡 Outfit Style Suggestions:")
        for ((style, suggestionList) in styleSuggestions) {
            println("$style: $suggestionList")
        }
        return styleSuggestions
    }
    
    /**
     * Get virtual try-on best practices
     * @return Map of best practices
     */
    fun getVirtualTryOnBestPractices(): Map<String, List<String>> {
        val bestPractices = mapOf(
            "image_preparation" to listOf(
                "Start with high-quality source images",
                "Ensure good lighting and contrast in both images",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined details",
                "Ensure both person and outfit are clearly visible"
            ),
            "outfit_selection" to listOf(
                "Choose outfit images that complement the person",
                "Select outfits with clear, well-defined shapes",
                "Use outfit images with good color contrast",
                "Ensure outfit images show the complete garment",
                "Choose outfits that match the person's body type"
            ),
            "body_visibility" to listOf(
                "Ensure the person's body is clearly visible",
                "Avoid images where the person is heavily covered",
                "Use images with good body definition and posture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ),
            "workflow_optimization" to listOf(
                "Batch process multiple outfit combinations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            )
        )
        
        println("💡 Virtual Try-On Best Practices:")
        for ((category, practiceList) in bestPractices) {
            println("$category: $practiceList")
        }
        return bestPractices
    }
    
    /**
     * Get virtual try-on performance tips
     * @return Map of performance tips
     */
    fun getVirtualTryOnPerformanceTips(): Map<String, List<String>> {
        val performanceTips = mapOf(
            "optimization" to listOf(
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple outfit combinations"
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
                "Offer outfit previews when possible",
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
     * Get virtual try-on technical specifications
     * @return Map of technical specifications
     */
    fun getVirtualTryOnTechnicalSpecifications(): Map<String, Map<String, Any>> {
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
                "person_detection" to "Automatic person detection and body segmentation",
                "outfit_transfer" to "Seamless outfit transfer onto person",
                "body_preservation" to "Preserves body shape and facial features",
                "realistic_rendering" to "Realistic outfit fitting and appearance",
                "output_quality" to "High-quality JPEG output"
            )
        )
        
        println("💡 Virtual Try-On Technical Specifications:")
        for ((category, specs) in specifications) {
            println("$category: $specs")
        }
        return specifications
    }
    
    /**
     * Get outfit combination suggestions
     * @return Map of combination suggestions
     */
    fun getOutfitCombinationSuggestions(): Map<String, List<String>> {
        val combinations = mapOf(
            "casual_combinations" to listOf(
                "T-shirt with jeans and sneakers",
                "Hoodie with joggers and casual shoes",
                "Blouse with trousers and flats",
                "Sweater with skirt and boots",
                "Polo shirt with shorts and sandals"
            ),
            "formal_combinations" to listOf(
                "Blazer with dress pants and dress shoes",
                "Dress shirt with suit and formal shoes",
                "Blouse with pencil skirt and heels",
                "Dress with blazer and pumps",
                "Suit with dress shirt and oxfords"
            ),
            "party_combinations" to listOf(
                "Cocktail dress with heels and accessories",
                "Party top with skirt and party shoes",
                "Evening gown with elegant accessories",
                "Festive outfit with matching accessories",
                "Celebration wear with themed accessories"
            ),
            "seasonal_combinations" to listOf(
                "Summer dress with sandals and sun hat",
                "Winter coat with boots and scarf",
                "Spring jacket with light layers and sneakers",
                "Fall sweater with jeans and ankle boots",
                "Seasonal outfit with appropriate accessories"
            )
        )
        
        println("💡 Outfit Combination Suggestions:")
        for ((category, combinationList) in combinations) {
            println("$category: $combinationList")
        }
        return combinations
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
    
    // MARK: - Private Methods
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): VirtualTryOnUploadImageBody {
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
            throw LightXVirtualTryOnException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<VirtualTryOnUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXVirtualTryOnException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXVirtualTryOnException("Image upload failed: ${response.statusCode()}")
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

class LightXVirtualTryOnException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXVirtualTryOnExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXAIVirtualTryOnAPI("YOUR_API_KEY_HERE")
            
            val personImageFile = File("path/to/person-image.jpg")
            val outfitImageFile = File("path/to/outfit-image.jpg")
            
            if (!personImageFile.exists()) {
                println("❌ Person image file not found: ${personImageFile.absolutePath}")
                return
            }
            
            if (!outfitImageFile.exists()) {
                println("❌ Outfit image file not found: ${outfitImageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getVirtualTryOnTips()
            lightx.getOutfitCategorySuggestions()
            lightx.getVirtualTryOnUseCases()
            lightx.getOutfitStyleSuggestions()
            lightx.getVirtualTryOnBestPractices()
            lightx.getVirtualTryOnPerformanceTips()
            lightx.getVirtualTryOnTechnicalSpecifications()
            lightx.getOutfitCombinationSuggestions()
            
            // Example 1: Casual outfit try-on
            val result1 = lightx.processVirtualTryOn(
                personImageFile = personImageFile,
                outfitImageFile = outfitImageFile,
                contentType = "image/jpeg"
            )
            println("🎉 Casual outfit try-on result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Try different outfit combinations
            val outfitCombinations = listOf(
                "path/to/casual-outfit.jpg",
                "path/to/formal-outfit.jpg",
                "path/to/party-outfit.jpg",
                "path/to/sportswear-outfit.jpg",
                "path/to/seasonal-outfit.jpg"
            )
            
            for (outfitPath in outfitCombinations) {
                val outfitFile = File(outfitPath)
                if (outfitFile.exists()) {
                    val result = lightx.processVirtualTryOn(
                        personImageFile = personImageFile,
                        outfitImageFile = outfitFile,
                        contentType = "image/jpeg"
                    )
                    println("🎉 $outfitPath try-on result:")
                    println("Order ID: ${result.orderId}")
                    println("Status: ${result.status}")
                    result.output?.let { println("Output: $it") }
                }
            }
            
            // Example 3: Get image dimensions
            val personDimensions = lightx.getImageDimensions(personImageFile)
            val outfitDimensions = lightx.getImageDimensions(outfitImageFile)
            
            if (personDimensions.first > 0 && personDimensions.second > 0) {
                println("📏 Person image: ${personDimensions.first}x${personDimensions.second}")
            }
            if (outfitDimensions.first > 0 && outfitDimensions.second > 0) {
                println("📏 Outfit image: ${outfitDimensions.first}x${outfitDimensions.second}")
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
fun runLightXVirtualTryOnExample() {
    runBlocking {
        LightXVirtualTryOnExample.main(emptyArray())
    }
}
