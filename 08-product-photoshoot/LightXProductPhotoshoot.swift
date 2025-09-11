/**
 * LightX AI Product Photoshoot API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered product photoshoot functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct ProductPhotoshootUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ProductPhotoshootUploadImageBody
}

struct ProductPhotoshootUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct ProductPhotoshootGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ProductPhotoshootGenerationBody
}

struct ProductPhotoshootGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct ProductPhotoshootOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ProductPhotoshootOrderStatusBody
}

struct ProductPhotoshootOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Product Photoshoot API Client

class LightXProductPhotoshootAPI {
    
    // MARK: - Properties
    
    private let apiKey: String
    private let baseURL = "https://api.lightxeditor.com/external/api"
    private let maxRetries = 5
    private let retryInterval: TimeInterval = 3.0 // seconds
    
    // MARK: - Initialization
    
    init(apiKey: String) {
        self.apiKey = apiKey
    }
    
    // MARK: - Public Methods
    
    /**
     * Upload image to LightX servers
     * - Parameters:
     *   - imageData: Image data to upload
     *   - contentType: MIME type (image/jpeg or image/png)
     * - Returns: Final image URL
     */
    func uploadImage(imageData: Data, contentType: String = "image/jpeg") async throws -> String {
        let fileSize = imageData.count
        
        guard fileSize <= 5242880 else { // 5MB limit
            throw LightXProductPhotoshootError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Upload multiple images (product and optional style image)
     * - Parameters:
     *   - productImageData: Product image data
     *   - styleImageData: Style image data (optional)
     *   - contentType: MIME type
     * - Returns: Tuple of (productURL, styleURL?)
     */
    func uploadImages(productImageData: Data, styleImageData: Data? = nil, 
                     contentType: String = "image/jpeg") async throws -> (String, String?) {
        print("📤 Uploading product image...")
        let productURL = try await uploadImage(imageData: productImageData, contentType: contentType)
        
        var styleURL: String? = nil
        if let styleData = styleImageData {
            print("📤 Uploading style image...")
            styleURL = try await uploadImage(imageData: styleData, contentType: contentType)
        }
        
        return (productURL, styleURL)
    }
    
    /**
     * Generate product photoshoot
     * - Parameters:
     *   - imageURL: URL of the product image
     *   - styleImageURL: URL of the style image (optional)
     *   - textPrompt: Text prompt for photoshoot style (optional)
     * - Returns: Order ID for tracking
     */
    func generateProductPhotoshoot(imageURL: String, styleImageURL: String? = nil, textPrompt: String? = nil) async throws -> String {
        let endpoint = "\(baseURL)/v1/product-photoshoot"
        
        guard let url = URL(string: endpoint) else {
            throw LightXProductPhotoshootError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        var payload: [String: Any] = ["imageUrl": imageURL]
        
        // Add optional parameters
        if let styleURL = styleImageURL {
            payload["styleImageUrl"] = styleURL
        }
        if let prompt = textPrompt {
            payload["textPrompt"] = prompt
        }
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXProductPhotoshootError.networkError
        }
        
        let photoshootResponse = try JSONDecoder().decode(ProductPhotoshootGenerationResponse.self, from: data)
        
        guard photoshootResponse.statusCode == 2000 else {
            throw LightXProductPhotoshootError.apiError(photoshootResponse.message)
        }
        
        let orderInfo = photoshootResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        if let prompt = textPrompt {
            print("💬 Text prompt: \"\(prompt)\"")
        }
        if let styleURL = styleImageURL {
            print("🎨 Style image: \(styleURL)")
        }
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * - Parameter orderID: Order ID to check
     * - Returns: Order status and results
     */
    func checkOrderStatus(orderID: String) async throws -> ProductPhotoshootOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXProductPhotoshootError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        let payload = ["orderId": orderID]
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXProductPhotoshootError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(ProductPhotoshootOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXProductPhotoshootError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> ProductPhotoshootOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Product photoshoot completed successfully!")
                    if let output = status.output {
                        print("📸 Product photoshoot image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXProductPhotoshootError.processingFailed
                    
                case "init":
                    attempts += 1
                    if attempts < maxRetries {
                        print("⏳ Waiting \(retryInterval) seconds before next check...")
                        try await Task.sleep(nanoseconds: UInt64(retryInterval * 1_000_000_000))
                    }
                    
                default:
                    attempts += 1
                    if attempts < maxRetries {
                        try await Task.sleep(nanoseconds: UInt64(retryInterval * 1_000_000_000))
                    }
                }
                
            } catch {
                attempts += 1
                if attempts >= maxRetries {
                    throw error
                }
                print("⚠️  Error on attempt \(attempts), retrying...")
                try await Task.sleep(nanoseconds: UInt64(retryInterval * 1_000_000_000))
            }
        }
        
        throw LightXProductPhotoshootError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and generate product photoshoot
     * - Parameters:
     *   - productImageData: Product image data
     *   - styleImageData: Style image data (optional)
     *   - textPrompt: Text prompt for photoshoot style (optional)
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processProductPhotoshoot(productImageData: Data, styleImageData: Data? = nil, 
                                 textPrompt: String? = nil, contentType: String = "image/jpeg") async throws -> ProductPhotoshootOrderStatusBody {
        print("🚀 Starting LightX AI Product Photoshoot API workflow...")
        
        // Step 1: Upload images
        print("📤 Uploading images...")
        let (productURL, styleURL) = try await uploadImages(
            productImageData: productImageData, 
            styleImageData: styleImageData, 
            contentType: contentType
        )
        print("✅ Product image uploaded: \(productURL)")
        if let styleURL = styleURL {
            print("✅ Style image uploaded: \(styleURL)")
        }
        
        // Step 2: Generate product photoshoot
        print("📸 Generating product photoshoot...")
        let orderID = try await generateProductPhotoshoot(imageURL: productURL, styleImageURL: styleURL, textPrompt: textPrompt)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    /**
     * Get common text prompts for different product photoshoot styles
     * - Parameter category: Category of photoshoot style
     * - Returns: Array of suggested prompts
     */
    func getSuggestedPrompts(category: String) -> [String] {
        let promptSuggestions: [String: [String]] = [
            "ecommerce": [
                "clean white background ecommerce",
                "professional product photography",
                "minimalist product shot",
                "studio lighting product photo",
                "commercial product photography"
            ],
            "lifestyle": [
                "lifestyle product photography",
                "natural environment product shot",
                "outdoor product photography",
                "casual lifestyle setting",
                "real-world product usage"
            ],
            "luxury": [
                "luxury product photography",
                "premium product presentation",
                "high-end product shot",
                "elegant product photography",
                "sophisticated product display"
            ],
            "tech": [
                "modern tech product photography",
                "sleek technology product shot",
                "contemporary tech presentation",
                "futuristic product photography",
                "digital product showcase"
            ],
            "fashion": [
                "fashion product photography",
                "stylish clothing presentation",
                "trendy fashion product shot",
                "modern fashion photography",
                "contemporary style product"
            ],
            "food": [
                "appetizing food photography",
                "delicious food presentation",
                "mouth-watering food shot",
                "professional food photography",
                "gourmet food styling"
            ],
            "beauty": [
                "beauty product photography",
                "cosmetic product presentation",
                "skincare product shot",
                "makeup product photography",
                "beauty brand styling"
            ],
            "home": [
                "home decor product photography",
                "interior design product shot",
                "home furnishing presentation",
                "decorative product photography",
                "lifestyle home product"
            ]
        ]
        
        let prompts = promptSuggestions[category] ?? []
        print("💡 Suggested prompts for \(category): \(prompts)")
        return prompts
    }
    
    /**
     * Validate text prompt (utility function)
     * - Parameter textPrompt: Text prompt to validate
     * - Returns: Whether the prompt is valid
     */
    func validateTextPrompt(textPrompt: String) -> Bool {
        if textPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            print("❌ Text prompt cannot be empty")
            return false
        }
        
        if textPrompt.count > 500 {
            print("❌ Text prompt is too long (max 500 characters)")
            return false
        }
        
        print("✅ Text prompt is valid")
        return true
    }
    
    /**
     * Generate product photoshoot with text prompt only
     * - Parameters:
     *   - productImageData: Product image data
     *   - textPrompt: Text prompt for photoshoot style
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generateProductPhotoshootWithPrompt(productImageData: Data, textPrompt: String, 
                                            contentType: String = "image/jpeg") async throws -> ProductPhotoshootOrderStatusBody {
        guard validateTextPrompt(textPrompt: textPrompt) else {
            throw LightXProductPhotoshootError.invalidPrompt
        }
        
        return try await processProductPhotoshoot(
            productImageData: productImageData, 
            styleImageData: nil, 
            textPrompt: textPrompt, 
            contentType: contentType
        )
    }
    
    /**
     * Generate product photoshoot with style image only
     * - Parameters:
     *   - productImageData: Product image data
     *   - styleImageData: Style image data
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generateProductPhotoshootWithStyle(productImageData: Data, styleImageData: Data, 
                                           contentType: String = "image/jpeg") async throws -> ProductPhotoshootOrderStatusBody {
        return try await processProductPhotoshoot(
            productImageData: productImageData, 
            styleImageData: styleImageData, 
            textPrompt: nil, 
            contentType: contentType
        )
    }
    
    /**
     * Generate product photoshoot with both style image and text prompt
     * - Parameters:
     *   - productImageData: Product image data
     *   - styleImageData: Style image data
     *   - textPrompt: Text prompt for photoshoot style
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generateProductPhotoshootWithStyleAndPrompt(productImageData: Data, styleImageData: Data, 
                                                    textPrompt: String, contentType: String = "image/jpeg") async throws -> ProductPhotoshootOrderStatusBody {
        guard validateTextPrompt(textPrompt: textPrompt) else {
            throw LightXProductPhotoshootError.invalidPrompt
        }
        
        return try await processProductPhotoshoot(
            productImageData: productImageData, 
            styleImageData: styleImageData, 
            textPrompt: textPrompt, 
            contentType: contentType
        )
    }
    
    /**
     * Get product photoshoot tips and best practices
     * - Returns: Dictionary of tips for better photoshoot results
     */
    func getProductPhotoshootTips() -> [String: [String]] {
        let tips: [String: [String]] = [
            "product_image": [
                "Use clear, well-lit product photos with good contrast",
                "Ensure the product is clearly visible and centered",
                "Avoid cluttered backgrounds in the original image",
                "Use high-resolution images for better results",
                "Product should be the main focus of the image"
            ],
            "style_image": [
                "Choose style images with desired background or setting",
                "Use lifestyle or studio photography as style references",
                "Ensure style image has good lighting and composition",
                "Match the mood and aesthetic you want for your product",
                "Use high-quality style reference images"
            ],
            "text_prompts": [
                "Be specific about the photoshoot style you want",
                "Mention background preferences (white, lifestyle, outdoor)",
                "Include lighting preferences (studio, natural, dramatic)",
                "Specify the mood (professional, casual, luxury, modern)",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Product photoshoots work best with clear product images",
                "Results may vary based on input image quality",
                "Style images influence both background and overall aesthetic",
                "Text prompts help guide the photoshoot style",
                "Allow 15-30 seconds for processing"
            ]
        ]
        
        print("💡 Product Photoshoot Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get product category-specific tips
     * - Returns: Dictionary of category-specific tips
     */
    func getProductCategoryTips() -> [String: [String]] {
        let categoryTips: [String: [String]] = [
            "electronics": [
                "Use clean, minimalist backgrounds",
                "Ensure good lighting to show product details",
                "Consider showing the product from multiple angles",
                "Use neutral colors to make the product stand out"
            ],
            "clothing": [
                "Use mannequins or models for better presentation",
                "Consider lifestyle settings for fashion items",
                "Ensure good lighting to show fabric texture",
                "Use complementary background colors"
            ],
            "jewelry": [
                "Use dark or neutral backgrounds for contrast",
                "Ensure excellent lighting to show sparkle and detail",
                "Consider close-up shots to show craftsmanship",
                "Use soft lighting to avoid harsh reflections"
            ],
            "food": [
                "Use natural lighting when possible",
                "Consider lifestyle settings (kitchen, dining table)",
                "Ensure good color contrast and appetizing presentation",
                "Use props that complement the food item"
            ],
            "beauty": [
                "Use clean, professional backgrounds",
                "Ensure good lighting to show product colors",
                "Consider showing the product in use",
                "Use soft, flattering lighting"
            ],
            "home_decor": [
                "Use lifestyle settings to show context",
                "Consider room settings for larger items",
                "Ensure good lighting to show texture and materials",
                "Use complementary colors and styles"
            ]
        ]
        
        print("💡 Product Category Tips:")
        for (category, tipList) in categoryTips {
            print("\(category): \(tipList)")
        }
        return categoryTips
    }
    
    /**
     * Get image dimensions (utility function)
     * - Parameter imageData: Image data
     * - Returns: (width, height) tuple
     */
    func getImageDimensions(imageData: Data) -> (Int, Int) {
        guard let image = UIImage(data: imageData) else {
            print("❌ Failed to create image from data")
            return (0, 0)
        }
        
        let width = Int(image.size.width)
        let height = Int(image.size.height)
        print("📏 Image dimensions: \(width)x\(height)")
        return (width, height)
    }
    
    // MARK: - Private Methods
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> ProductPhotoshootUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXProductPhotoshootError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        let payload = [
            "uploadType": "imageUrl",
            "size": fileSize,
            "contentType": contentType
        ] as [String: Any]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXProductPhotoshootError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(ProductPhotoshootUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXProductPhotoshootError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: ProductPhotoshootUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXProductPhotoshootError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXProductPhotoshootError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXProductPhotoshootError: Error, LocalizedError {
    case invalidURL
    case networkError
    case apiError(String)
    case imageSizeExceeded
    case uploadFailed
    case processingFailed
    case maxRetriesReached
    case invalidPrompt
    
    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid URL"
        case .networkError:
            return "Network error occurred"
        case .apiError(let message):
            return "API Error: \(message)"
        case .imageSizeExceeded:
            return "Image size exceeds 5MB limit"
        case .uploadFailed:
            return "Image upload failed"
        case .processingFailed:
            return "Product photoshoot processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidPrompt:
            return "Invalid text prompt"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXProductPhotoshootExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXProductPhotoshootAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getProductPhotoshootTips()
            lightx.getProductCategoryTips()
            
            // Load product image data (replace with your image loading logic)
            guard let productImage = UIImage(named: "product_image"),
                  let productImageData = productImage.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load product image")
                return
            }
            
            // Example 1: Generate product photoshoot with text prompt only
            let ecommercePrompts = lightx.getSuggestedPrompts(category: "ecommerce")
            let result1 = try await lightx.generateProductPhotoshootWithPrompt(
                productImageData: productImageData,
                textPrompt: ecommercePrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 E-commerce photoshoot result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Generate product photoshoot with style image only
            guard let styleImage = UIImage(named: "style_image"),
                  let styleImageData = styleImage.jpegData(compressionQuality: 1.0) else {
                print("❌ Failed to load style image")
                return
            }
            
            let result2 = try await lightx.generateProductPhotoshootWithStyle(
                productImageData: productImageData,
                styleImageData: styleImageData,
                contentType: "image/jpeg"
            )
            print("🎉 Style-based photoshoot result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Generate product photoshoot with both style image and text prompt
            let luxuryPrompts = lightx.getSuggestedPrompts(category: "luxury")
            let result3 = try await lightx.generateProductPhotoshootWithStyleAndPrompt(
                productImageData: productImageData,
                styleImageData: styleImageData,
                textPrompt: luxuryPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Combined style and prompt result:")
            print("Order ID: \(result3.orderId)")
            print("Status: \(result3.status)")
            if let output = result3.output {
                print("Output: \(output)")
            }
            
            // Example 4: Generate photoshoots for different categories
            let categories = ["ecommerce", "lifestyle", "luxury", "tech", "fashion", "food", "beauty", "home"]
            for category in categories {
                let prompts = lightx.getSuggestedPrompts(category: category)
                let result = try await lightx.generateProductPhotoshootWithPrompt(
                    productImageData: productImageData,
                    textPrompt: prompts[0],
                    contentType: "image/jpeg"
                )
                print("🎉 \(category) photoshoot result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 5: Get image dimensions
            let (width, height) = lightx.getImageDimensions(imageData: productImageData)
            if width > 0 && height > 0 {
                print("📏 Original image: \(width)x\(height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
