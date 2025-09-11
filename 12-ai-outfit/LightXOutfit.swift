/**
 * LightX AI Outfit API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered outfit changing functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct OutfitUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: OutfitUploadImageBody
}

struct OutfitUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct OutfitGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: OutfitGenerationBody
}

struct OutfitGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct OutfitOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: OutfitOrderStatusBody
}

struct OutfitOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Outfit API Client

class LightXOutfitAPI {
    
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
            throw LightXOutfitError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Generate outfit change
     * - Parameters:
     *   - imageURL: URL of the input image
     *   - textPrompt: Text prompt for outfit description
     * - Returns: Order ID for tracking
     */
    func generateOutfit(imageURL: String, textPrompt: String) async throws -> String {
        let endpoint = "\(baseURL)/v1/outfit"
        
        guard let url = URL(string: endpoint) else {
            throw LightXOutfitError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        let payload = [
            "imageUrl": imageURL,
            "textPrompt": textPrompt
        ] as [String: Any]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXOutfitError.networkError
        }
        
        let outfitResponse = try JSONDecoder().decode(OutfitGenerationResponse.self, from: data)
        
        guard outfitResponse.statusCode == 2000 else {
            throw LightXOutfitError.apiError(outfitResponse.message)
        }
        
        let orderInfo = outfitResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("👗 Outfit prompt: \"\(textPrompt)\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * - Parameter orderID: Order ID to check
     * - Returns: Order status and results
     */
    func checkOrderStatus(orderID: String) async throws -> OutfitOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXOutfitError.invalidURL
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
            throw LightXOutfitError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(OutfitOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXOutfitError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> OutfitOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Outfit generation completed successfully!")
                    if let output = status.output {
                        print("👗 Outfit result: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXOutfitError.processingFailed
                    
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
        
        throw LightXOutfitError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and generate outfit
     * - Parameters:
     *   - imageData: Image data
     *   - textPrompt: Text prompt for outfit description
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processOutfitGeneration(imageData: Data, textPrompt: String, 
                                contentType: String = "image/jpeg") async throws -> OutfitOrderStatusBody {
        print("🚀 Starting LightX AI Outfit API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageURL = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageURL)")
        
        // Step 2: Generate outfit
        print("👗 Generating outfit...")
        let orderID = try await generateOutfit(imageURL: imageURL, textPrompt: textPrompt)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    /**
     * Get outfit generation tips and best practices
     * - Returns: Dictionary of tips for better outfit results
     */
    func getOutfitTips() -> [String: [String]] {
        let tips: [String: [String]] = [
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the person is clearly visible and centered",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good body visibility improves outfit generation"
            ],
            "text_prompts": [
                "Be specific about the outfit style you want",
                "Mention clothing items (shirt, dress, jacket, etc.)",
                "Include color preferences and patterns",
                "Specify the occasion (casual, formal, party, etc.)",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Outfit generation works best with clear human subjects",
                "Results may vary based on input image quality",
                "Text prompts guide the outfit generation process",
                "Allow 15-30 seconds for processing",
                "Experiment with different outfit styles for variety"
            ]
        ]
        
        print("💡 Outfit Generation Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get outfit style suggestions
     * - Returns: Dictionary of outfit style suggestions
     */
    func getOutfitStyleSuggestions() -> [String: [String]] {
        let styleSuggestions: [String: [String]] = [
            "professional": [
                "professional business suit",
                "formal office attire",
                "corporate blazer and dress pants",
                "elegant business dress",
                "sophisticated work outfit"
            ],
            "casual": [
                "casual jeans and t-shirt",
                "relaxed weekend outfit",
                "comfortable everyday wear",
                "casual summer dress",
                "laid-back street style"
            ],
            "formal": [
                "elegant evening gown",
                "formal tuxedo",
                "cocktail party dress",
                "black tie attire",
                "sophisticated formal wear"
            ],
            "sporty": [
                "athletic workout outfit",
                "sporty casual wear",
                "gym attire",
                "active lifestyle clothing",
                "comfortable sports outfit"
            ],
            "trendy": [
                "fashionable street style",
                "trendy modern outfit",
                "stylish contemporary wear",
                "fashion-forward ensemble",
                "chic trendy clothing"
            ]
        ]
        
        print("💡 Outfit Style Suggestions:")
        for (category, suggestionList) in styleSuggestions {
            print("\(category): \(suggestionList)")
        }
        return styleSuggestions
    }
    
    /**
     * Get outfit prompt examples
     * - Returns: Dictionary of prompt examples
     */
    func getOutfitPromptExamples() -> [String: [String]] {
        let promptExamples: [String: [String]] = [
            "professional": [
                "Professional navy blue business suit with white shirt",
                "Elegant black blazer with matching dress pants",
                "Corporate dress in neutral colors",
                "Formal office attire with blouse and skirt",
                "Business casual outfit with cardigan and slacks"
            ],
            "casual": [
                "Casual blue jeans with white cotton t-shirt",
                "Relaxed summer dress in floral pattern",
                "Comfortable hoodie with denim jeans",
                "Casual weekend outfit with sneakers",
                "Lay-back style with comfortable clothing"
            ],
            "formal": [
                "Elegant black evening gown with accessories",
                "Formal tuxedo with bow tie",
                "Cocktail dress in deep red color",
                "Black tie formal wear",
                "Sophisticated formal attire for special occasion"
            ],
            "sporty": [
                "Athletic leggings with sports bra and sneakers",
                "Gym outfit with tank top and shorts",
                "Active wear for running and exercise",
                "Sporty casual outfit for outdoor activities",
                "Comfortable athletic clothing"
            ],
            "trendy": [
                "Fashionable street style with trendy accessories",
                "Modern outfit with contemporary fashion elements",
                "Stylish ensemble with current fashion trends",
                "Chic trendy clothing with fashionable details",
                "Fashion-forward outfit with modern styling"
            ]
        ]
        
        print("💡 Outfit Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
    }
    
    /**
     * Get outfit use cases and examples
     * - Returns: Dictionary of use case examples
     */
    func getOutfitUseCases() -> [String: [String]] {
        let useCases: [String: [String]] = [
            "fashion": [
                "Virtual try-on for e-commerce",
                "Fashion styling and recommendations",
                "Outfit planning and coordination",
                "Style inspiration and ideas",
                "Fashion trend visualization"
            ],
            "retail": [
                "Online shopping experience enhancement",
                "Product visualization and styling",
                "Customer engagement and interaction",
                "Virtual fitting room technology",
                "Personalized fashion recommendations"
            ],
            "social": [
                "Social media content creation",
                "Fashion blogging and influencers",
                "Style sharing and inspiration",
                "Outfit of the day posts",
                "Fashion community engagement"
            ],
            "personal": [
                "Personal style exploration",
                "Wardrobe planning and organization",
                "Outfit coordination and matching",
                "Style experimentation",
                "Fashion confidence building"
            ]
        ]
        
        print("💡 Outfit Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
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
     * Generate outfit with prompt validation
     * - Parameters:
     *   - imageData: Image data
     *   - textPrompt: Text prompt for outfit description
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generateOutfitWithPrompt(imageData: Data, textPrompt: String, 
                                 contentType: String = "image/jpeg") async throws -> OutfitOrderStatusBody {
        guard validateTextPrompt(textPrompt: textPrompt) else {
            throw LightXOutfitError.invalidPrompt
        }
        
        return try await processOutfitGeneration(imageData: imageData, textPrompt: textPrompt, contentType: contentType)
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> OutfitUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXOutfitError.invalidURL
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
            throw LightXOutfitError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(OutfitUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXOutfitError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: OutfitUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXOutfitError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXOutfitError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXOutfitError: Error, LocalizedError {
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
            return "Outfit generation processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidPrompt:
            return "Invalid text prompt"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXOutfitExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXOutfitAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getOutfitTips()
            lightx.getOutfitStyleSuggestions()
            lightx.getOutfitPromptExamples()
            lightx.getOutfitUseCases()
            
            // Load image data (replace with your image loading logic)
            guard let inputImage = UIImage(named: "input_image"),
                  let inputImageData = inputImage.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load input image")
                return
            }
            
            // Example 1: Generate professional outfit
            let professionalPrompts = lightx.getOutfitStyleSuggestions()["professional"] ?? []
            let result1 = try await lightx.generateOutfitWithPrompt(
                imageData: inputImageData,
                textPrompt: professionalPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Professional outfit result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Generate casual outfit
            let casualPrompts = lightx.getOutfitStyleSuggestions()["casual"] ?? []
            let result2 = try await lightx.generateOutfitWithPrompt(
                imageData: inputImageData,
                textPrompt: casualPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Casual outfit result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Generate outfits for different styles
            let styles = ["professional", "casual", "formal", "sporty", "trendy"]
            for style in styles {
                let prompts = lightx.getOutfitStyleSuggestions()[style] ?? []
                let result = try await lightx.generateOutfitWithPrompt(
                    imageData: inputImageData,
                    textPrompt: prompts[0],
                    contentType: "image/jpeg"
                )
                print("🎉 \(style) outfit result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 4: Get image dimensions
            let (width, height) = lightx.getImageDimensions(imageData: inputImageData)
            if width > 0 && height > 0 {
                print("📏 Original image: \(width)x\(height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
