/**
 * LightX AI Product Photoshoot API Integration - Kotlin
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered product photoshoot functionality.
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
data class ProductPhotoshootUploadImageResponse(
    val statusCode: Int,
    val message: String,
    val body: ProductPhotoshootUploadImageBody
)

@Serializable
data class ProductPhotoshootUploadImageBody(
    val uploadImage: String,
    val imageUrl: String,
    val size: Int
)

@Serializable
data class ProductPhotoshootGenerationResponse(
    val statusCode: Int,
    val message: String,
    val body: ProductPhotoshootGenerationBody
)

@Serializable
data class ProductPhotoshootGenerationBody(
    val orderId: String,
    val maxRetriesAllowed: Int,
    val avgResponseTimeInSec: Int,
    val status: String
)

@Serializable
data class ProductPhotoshootOrderStatusResponse(
    val statusCode: Int,
    val message: String,
    val body: ProductPhotoshootOrderStatusBody
)

@Serializable
data class ProductPhotoshootOrderStatusBody(
    val orderId: String,
    val status: String,
    val output: String? = null
)

// MARK: - LightX Product Photoshoot API Client

class LightXProductPhotoshootAPI(private val apiKey: String) {
    
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
            throw LightXProductPhotoshootException("Image size exceeds 5MB limit")
        }
        
        // Step 1: Get upload URL
        val uploadUrl = getUploadURL(fileSize, contentType)
        
        // Step 2: Upload image to S3
        uploadToS3(uploadUrl.uploadImage, imageFile, contentType)
        
        println("✅ Image uploaded successfully")
        return uploadUrl.imageUrl
    }
    
    /**
     * Upload multiple images (product and optional style image)
     * @param productImageFile Product image file
     * @param styleImageFile Style image file (optional)
     * @param contentType MIME type
     * @return Pair of (productURL, styleURL?)
     */
    suspend fun uploadImages(productImageFile: File, styleImageFile: File? = null, 
                           contentType: String = "image/jpeg"): Pair<String, String?> {
        println("📤 Uploading product image...")
        val productUrl = uploadImage(productImageFile, contentType)
        
        val styleUrl = styleImageFile?.let { file ->
            println("📤 Uploading style image...")
            uploadImage(file, contentType)
        }
        
        return Pair(productUrl, styleUrl)
    }
    
    /**
     * Generate product photoshoot
     * @param imageUrl URL of the product image
     * @param styleImageUrl URL of the style image (optional)
     * @param textPrompt Text prompt for photoshoot style (optional)
     * @return Order ID for tracking
     */
    suspend fun generateProductPhotoshoot(imageUrl: String, styleImageUrl: String? = null, textPrompt: String? = null): String {
        val endpoint = "$BASE_URL/v1/product-photoshoot"
        
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
            throw LightXProductPhotoshootException("Network error: ${response.statusCode()}")
        }
        
        val photoshootResponse = json.decodeFromString<ProductPhotoshootGenerationResponse>(response.body())
        
        if (photoshootResponse.statusCode != 2000) {
            throw LightXProductPhotoshootException("Product photoshoot request failed: ${photoshootResponse.message}")
        }
        
        val orderInfo = photoshootResponse.body
        
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
    suspend fun checkOrderStatus(orderId: String): ProductPhotoshootOrderStatusBody {
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
            throw LightXProductPhotoshootException("Network error: ${response.statusCode()}")
        }
        
        val statusResponse = json.decodeFromString<ProductPhotoshootOrderStatusResponse>(response.body())
        
        if (statusResponse.statusCode != 2000) {
            throw LightXProductPhotoshootException("Status check failed: ${statusResponse.message}")
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    suspend fun waitForCompletion(orderId: String): ProductPhotoshootOrderStatusBody {
        var attempts = 0
        
        while (attempts < MAX_RETRIES) {
            try {
                val status = checkOrderStatus(orderId)
                
                println("🔄 Attempt ${attempts + 1}: Status - ${status.status}")
                
                when (status.status) {
                    "active" -> {
                        println("✅ Product photoshoot completed successfully!")
                        status.output?.let { println("📸 Product photoshoot image: $it") }
                        return status
                    }
                    "failed" -> throw LightXProductPhotoshootException("Product photoshoot failed")
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
        
        throw LightXProductPhotoshootException("Maximum retry attempts reached")
    }
    
    /**
     * Complete workflow: Upload images and generate product photoshoot
     * @param productImageFile Product image file
     * @param styleImageFile Style image file (optional)
     * @param textPrompt Text prompt for photoshoot style (optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun processProductPhotoshoot(
        productImageFile: File, 
        styleImageFile: File? = null, 
        textPrompt: String? = null, 
        contentType: String = "image/jpeg"
    ): ProductPhotoshootOrderStatusBody {
        println("🚀 Starting LightX AI Product Photoshoot API workflow...")
        
        // Step 1: Upload images
        println("📤 Uploading images...")
        val (productUrl, styleUrl) = uploadImages(productImageFile, styleImageFile, contentType)
        println("✅ Product image uploaded: $productUrl")
        styleUrl?.let { println("✅ Style image uploaded: $it") }
        
        // Step 2: Generate product photoshoot
        println("📸 Generating product photoshoot...")
        val orderId = generateProductPhotoshoot(productUrl, styleUrl, textPrompt)
        
        // Step 3: Wait for completion
        println("⏳ Waiting for processing to complete...")
        val result = waitForCompletion(orderId)
        
        return result
    }
    
    /**
     * Get common text prompts for different product photoshoot styles
     * @param category Category of photoshoot style
     * @return List of suggested prompts
     */
    fun getSuggestedPrompts(category: String): List<String> {
        val promptSuggestions = mapOf(
            "ecommerce" to listOf(
                "clean white background ecommerce",
                "professional product photography",
                "minimalist product shot",
                "studio lighting product photo",
                "commercial product photography"
            ),
            "lifestyle" to listOf(
                "lifestyle product photography",
                "natural environment product shot",
                "outdoor product photography",
                "casual lifestyle setting",
                "real-world product usage"
            ),
            "luxury" to listOf(
                "luxury product photography",
                "premium product presentation",
                "high-end product shot",
                "elegant product photography",
                "sophisticated product display"
            ),
            "tech" to listOf(
                "modern tech product photography",
                "sleek technology product shot",
                "contemporary tech presentation",
                "futuristic product photography",
                "digital product showcase"
            ),
            "fashion" to listOf(
                "fashion product photography",
                "stylish clothing presentation",
                "trendy fashion product shot",
                "modern fashion photography",
                "contemporary style product"
            ),
            "food" to listOf(
                "appetizing food photography",
                "delicious food presentation",
                "mouth-watering food shot",
                "professional food photography",
                "gourmet food styling"
            ),
            "beauty" to listOf(
                "beauty product photography",
                "cosmetic product presentation",
                "skincare product shot",
                "makeup product photography",
                "beauty brand styling"
            ),
            "home" to listOf(
                "home decor product photography",
                "interior design product shot",
                "home furnishing presentation",
                "decorative product photography",
                "lifestyle home product"
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
     * Generate product photoshoot with text prompt only
     * @param productImageFile Product image file
     * @param textPrompt Text prompt for photoshoot style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateProductPhotoshootWithPrompt(
        productImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): ProductPhotoshootOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXProductPhotoshootException("Invalid text prompt")
        }
        
        return processProductPhotoshoot(productImageFile, null, textPrompt, contentType)
    }
    
    /**
     * Generate product photoshoot with style image only
     * @param productImageFile Product image file
     * @param styleImageFile Style image file
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateProductPhotoshootWithStyle(
        productImageFile: File, 
        styleImageFile: File, 
        contentType: String = "image/jpeg"
    ): ProductPhotoshootOrderStatusBody {
        return processProductPhotoshoot(productImageFile, styleImageFile, null, contentType)
    }
    
    /**
     * Generate product photoshoot with both style image and text prompt
     * @param productImageFile Product image file
     * @param styleImageFile Style image file
     * @param textPrompt Text prompt for photoshoot style
     * @param contentType MIME type
     * @return Final result with output URL
     */
    suspend fun generateProductPhotoshootWithStyleAndPrompt(
        productImageFile: File, 
        styleImageFile: File, 
        textPrompt: String, 
        contentType: String = "image/jpeg"
    ): ProductPhotoshootOrderStatusBody {
        if (!validateTextPrompt(textPrompt)) {
            throw LightXProductPhotoshootException("Invalid text prompt")
        }
        
        return processProductPhotoshoot(productImageFile, styleImageFile, textPrompt, contentType)
    }
    
    /**
     * Get product photoshoot tips and best practices
     * @return Map of tips for better photoshoot results
     */
    fun getProductPhotoshootTips(): Map<String, List<String>> {
        val tips = mapOf(
            "product_image" to listOf(
                "Use clear, well-lit product photos with good contrast",
                "Ensure the product is clearly visible and centered",
                "Avoid cluttered backgrounds in the original image",
                "Use high-resolution images for better results",
                "Product should be the main focus of the image"
            ),
            "style_image" to listOf(
                "Choose style images with desired background or setting",
                "Use lifestyle or studio photography as style references",
                "Ensure style image has good lighting and composition",
                "Match the mood and aesthetic you want for your product",
                "Use high-quality style reference images"
            ),
            "text_prompts" to listOf(
                "Be specific about the photoshoot style you want",
                "Mention background preferences (white, lifestyle, outdoor)",
                "Include lighting preferences (studio, natural, dramatic)",
                "Specify the mood (professional, casual, luxury, modern)",
                "Keep prompts concise but descriptive"
            ),
            "general" to listOf(
                "Product photoshoots work best with clear product images",
                "Results may vary based on input image quality",
                "Style images influence both background and overall aesthetic",
                "Text prompts help guide the photoshoot style",
                "Allow 15-30 seconds for processing"
            )
        )
        
        println("💡 Product Photoshoot Tips:")
        for ((category, tipList) in tips) {
            println("$category: $tipList")
        }
        return tips
    }
    
    /**
     * Get product category-specific tips
     * @return Map of category-specific tips
     */
    fun getProductCategoryTips(): Map<String, List<String>> {
        val categoryTips = mapOf(
            "electronics" to listOf(
                "Use clean, minimalist backgrounds",
                "Ensure good lighting to show product details",
                "Consider showing the product from multiple angles",
                "Use neutral colors to make the product stand out"
            ),
            "clothing" to listOf(
                "Use mannequins or models for better presentation",
                "Consider lifestyle settings for fashion items",
                "Ensure good lighting to show fabric texture",
                "Use complementary background colors"
            ),
            "jewelry" to listOf(
                "Use dark or neutral backgrounds for contrast",
                "Ensure excellent lighting to show sparkle and detail",
                "Consider close-up shots to show craftsmanship",
                "Use soft lighting to avoid harsh reflections"
            ),
            "food" to listOf(
                "Use natural lighting when possible",
                "Consider lifestyle settings (kitchen, dining table)",
                "Ensure good color contrast and appetizing presentation",
                "Use props that complement the food item"
            ),
            "beauty" to listOf(
                "Use clean, professional backgrounds",
                "Ensure good lighting to show product colors",
                "Consider showing the product in use",
                "Use soft, flattering lighting"
            ),
            "home_decor" to listOf(
                "Use lifestyle settings to show context",
                "Consider room settings for larger items",
                "Ensure good lighting to show texture and materials",
                "Use complementary colors and styles"
            )
        )
        
        println("💡 Product Category Tips:")
        for ((category, tipList) in categoryTips) {
            println("$category: $tipList")
        }
        return categoryTips
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
    
    private suspend fun getUploadURL(fileSize: Long, contentType: String): ProductPhotoshootUploadImageBody {
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
            throw LightXProductPhotoshootException("Network error: ${response.statusCode()}")
        }
        
        val uploadResponse = json.decodeFromString<ProductPhotoshootUploadImageResponse>(response.body())
        
        if (uploadResponse.statusCode != 2000) {
            throw LightXProductPhotoshootException("Upload URL request failed: ${uploadResponse.message}")
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
            throw LightXProductPhotoshootException("Image upload failed: ${response.statusCode()}")
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

class LightXProductPhotoshootException(message: String) : Exception(message)

// MARK: - Example Usage

object LightXProductPhotoshootExample {
    
    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            // Initialize with your API key
            val lightx = LightXProductPhotoshootAPI("YOUR_API_KEY_HERE")
            
            val productImageFile = File("path/to/product-image.jpg")
            val styleImageFile = File("path/to/style-image.jpg")
            
            if (!productImageFile.exists()) {
                println("❌ Product image file not found: ${productImageFile.absolutePath}")
                return
            }
            
            // Get tips for better results
            lightx.getProductPhotoshootTips()
            lightx.getProductCategoryTips()
            
            // Example 1: Generate product photoshoot with text prompt only
            val ecommercePrompts = lightx.getSuggestedPrompts("ecommerce")
            val result1 = lightx.generateProductPhotoshootWithPrompt(
                productImageFile = productImageFile,
                textPrompt = ecommercePrompts[0],
                contentType = "image/jpeg"
            )
            println("🎉 E-commerce photoshoot result:")
            println("Order ID: ${result1.orderId}")
            println("Status: ${result1.status}")
            result1.output?.let { println("Output: $it") }
            
            // Example 2: Generate product photoshoot with style image only
            if (styleImageFile.exists()) {
                val result2 = lightx.generateProductPhotoshootWithStyle(
                    productImageFile = productImageFile,
                    styleImageFile = styleImageFile,
                    contentType = "image/jpeg"
                )
                println("🎉 Style-based photoshoot result:")
                println("Order ID: ${result2.orderId}")
                println("Status: ${result2.status}")
                result2.output?.let { println("Output: $it") }
                
                // Example 3: Generate product photoshoot with both style image and text prompt
                val luxuryPrompts = lightx.getSuggestedPrompts("luxury")
                val result3 = lightx.generateProductPhotoshootWithStyleAndPrompt(
                    productImageFile = productImageFile,
                    styleImageFile = styleImageFile,
                    textPrompt = luxuryPrompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 Combined style and prompt result:")
                println("Order ID: ${result3.orderId}")
                println("Status: ${result3.status}")
                result3.output?.let { println("Output: $it") }
            }
            
            // Example 4: Generate photoshoots for different categories
            val categories = listOf("ecommerce", "lifestyle", "luxury", "tech", "fashion", "food", "beauty", "home")
            for (category in categories) {
                val prompts = lightx.getSuggestedPrompts(category)
                val result = lightx.generateProductPhotoshootWithPrompt(
                    productImageFile = productImageFile,
                    textPrompt = prompts[0],
                    contentType = "image/jpeg"
                )
                println("🎉 $category photoshoot result:")
                println("Order ID: ${result.orderId}")
                println("Status: ${result.status}")
                result.output?.let { println("Output: $it") }
            }
            
            // Example 5: Get image dimensions
            val (width, height) = lightx.getImageDimensions(productImageFile)
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
fun runLightXProductPhotoshootExample() {
    runBlocking {
        LightXProductPhotoshootExample.main(emptyArray())
    }
}
