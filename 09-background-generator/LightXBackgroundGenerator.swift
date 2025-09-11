/**
 * LightX AI Background Generator API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered background generation functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct BackgroundGeneratorUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: BackgroundGeneratorUploadImageBody
}

struct BackgroundGeneratorUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct BackgroundGeneratorGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: BackgroundGeneratorGenerationBody
}

struct BackgroundGeneratorGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct BackgroundGeneratorOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: BackgroundGeneratorOrderStatusBody
}

struct BackgroundGeneratorOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Background Generator API Client

class LightXBackgroundGeneratorAPI {
    
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
            throw LightXBackgroundGeneratorError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Generate background
     * - Parameters:
     *   - imageURL: URL of the input image
     *   - textPrompt: Text prompt for background generation
     * - Returns: Order ID for tracking
     */
    func generateBackground(imageURL: String, textPrompt: String) async throws -> String {
        let endpoint = "\(baseURL)/v1/background-generator"
        
        guard let url = URL(string: endpoint) else {
            throw LightXBackgroundGeneratorError.invalidURL
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
            throw LightXBackgroundGeneratorError.networkError
        }
        
        let backgroundResponse = try JSONDecoder().decode(BackgroundGeneratorGenerationResponse.self, from: data)
        
        guard backgroundResponse.statusCode == 2000 else {
            throw LightXBackgroundGeneratorError.apiError(backgroundResponse.message)
        }
        
        let orderInfo = backgroundResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("💬 Text prompt: \"\(textPrompt)\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * - Parameter orderID: Order ID to check
     * - Returns: Order status and results
     */
    func checkOrderStatus(orderID: String) async throws -> BackgroundGeneratorOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXBackgroundGeneratorError.invalidURL
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
            throw LightXBackgroundGeneratorError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(BackgroundGeneratorOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXBackgroundGeneratorError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> BackgroundGeneratorOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Background generation completed successfully!")
                    if let output = status.output {
                        print("🖼️ Background image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXBackgroundGeneratorError.processingFailed
                    
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
        
        throw LightXBackgroundGeneratorError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and generate background
     * - Parameters:
     *   - imageData: Image data
     *   - textPrompt: Text prompt for background generation
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processBackgroundGeneration(imageData: Data, textPrompt: String, 
                                    contentType: String = "image/jpeg") async throws -> BackgroundGeneratorOrderStatusBody {
        print("🚀 Starting LightX AI Background Generator API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageURL = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageURL)")
        
        // Step 2: Generate background
        print("🖼️ Generating background...")
        let orderID = try await generateBackground(imageURL: imageURL, textPrompt: textPrompt)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    /**
     * Get background generation tips and best practices
     * - Returns: Dictionary of tips for better background results
     */
    func getBackgroundTips() -> [String: [String]] {
        let tips: [String: [String]] = [
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the subject is clearly visible and centered",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good subject separation improves background generation"
            ],
            "text_prompts": [
                "Be specific about the background style you want",
                "Mention color schemes and mood preferences",
                "Include environmental details (indoor, outdoor, studio)",
                "Specify lighting preferences (natural, dramatic, soft)",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Background generation works best with clear subjects",
                "Results may vary based on input image quality",
                "Text prompts guide the background generation process",
                "Allow 15-30 seconds for processing",
                "Experiment with different prompt styles for variety"
            ]
        ]
        
        print("💡 Background Generation Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get background style suggestions
     * - Returns: Dictionary of background style suggestions
     */
    func getBackgroundStyleSuggestions() -> [String: [String]] {
        let styleSuggestions: [String: [String]] = [
            "professional": [
                "professional office background",
                "clean minimalist workspace",
                "modern corporate environment",
                "elegant business setting",
                "sophisticated professional space"
            ],
            "natural": [
                "natural outdoor landscape",
                "beautiful garden background",
                "scenic mountain view",
                "peaceful forest setting",
                "serene beach environment"
            ],
            "creative": [
                "artistic studio background",
                "creative workspace environment",
                "colorful abstract background",
                "modern art gallery setting",
                "inspiring creative space"
            ],
            "lifestyle": [
                "cozy home interior",
                "modern living room",
                "stylish bedroom setting",
                "contemporary kitchen",
                "elegant dining room"
            ],
            "outdoor": [
                "urban cityscape background",
                "park environment",
                "outdoor cafe setting",
                "street scene background",
                "architectural landmark view"
            ]
        ]
        
        print("💡 Background Style Suggestions:")
        for (category, suggestionList) in styleSuggestions {
            print("\(category): \(suggestionList)")
        }
        return styleSuggestions
    }
    
    /**
     * Get background prompt examples
     * - Returns: Dictionary of prompt examples
     */
    func getBackgroundPromptExamples() -> [String: [String]] {
        let promptExamples: [String: [String]] = [
            "professional": [
                "Modern office with glass windows and city view",
                "Clean white studio background with soft lighting",
                "Professional conference room with wooden furniture",
                "Contemporary workspace with minimalist design",
                "Elegant business environment with neutral colors"
            ],
            "natural": [
                "Beautiful sunset over mountains in the background",
                "Lush green garden with blooming flowers",
                "Peaceful lake with reflection of trees",
                "Golden hour lighting in a forest setting",
                "Serene beach with gentle waves and palm trees"
            ],
            "creative": [
                "Artistic studio with colorful paint splashes",
                "Modern gallery with white walls and track lighting",
                "Creative workspace with vintage furniture",
                "Abstract colorful background with geometric shapes",
                "Bohemian style room with eclectic decorations"
            ],
            "lifestyle": [
                "Cozy living room with warm lighting and books",
                "Modern kitchen with marble countertops",
                "Stylish bedroom with soft natural light",
                "Contemporary dining room with elegant table",
                "Comfortable home office with plants and books"
            ],
            "outdoor": [
                "Urban cityscape with modern skyscrapers",
                "Charming street with cafes and shops",
                "Park setting with trees and walking paths",
                "Historic architecture with classical columns",
                "Modern outdoor space with contemporary design"
            ]
        ]
        
        print("💡 Background Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
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
     * Generate background with prompt validation
     * - Parameters:
     *   - imageData: Image data
     *   - textPrompt: Text prompt for background generation
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generateBackgroundWithPrompt(imageData: Data, textPrompt: String, 
                                     contentType: String = "image/jpeg") async throws -> BackgroundGeneratorOrderStatusBody {
        guard validateTextPrompt(textPrompt: textPrompt) else {
            throw LightXBackgroundGeneratorError.invalidPrompt
        }
        
        return try await processBackgroundGeneration(imageData: imageData, textPrompt: textPrompt, contentType: contentType)
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> BackgroundGeneratorUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXBackgroundGeneratorError.invalidURL
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
            throw LightXBackgroundGeneratorError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(BackgroundGeneratorUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXBackgroundGeneratorError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: BackgroundGeneratorUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXBackgroundGeneratorError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXBackgroundGeneratorError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXBackgroundGeneratorError: Error, LocalizedError {
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
            return "Background generation processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidPrompt:
            return "Invalid text prompt"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXBackgroundGeneratorExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXBackgroundGeneratorAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getBackgroundTips()
            lightx.getBackgroundStyleSuggestions()
            lightx.getBackgroundPromptExamples()
            
            // Load image data (replace with your image loading logic)
            guard let inputImage = UIImage(named: "input_image"),
                  let inputImageData = inputImage.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load input image")
                return
            }
            
            // Example 1: Generate background with professional style
            let professionalPrompts = lightx.getBackgroundStyleSuggestions()["professional"] ?? []
            let result1 = try await lightx.generateBackgroundWithPrompt(
                imageData: inputImageData,
                textPrompt: professionalPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Professional background result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Generate background with natural style
            let naturalPrompts = lightx.getBackgroundStyleSuggestions()["natural"] ?? []
            let result2 = try await lightx.generateBackgroundWithPrompt(
                imageData: inputImageData,
                textPrompt: naturalPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Natural background result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Generate backgrounds for different styles
            let styles = ["professional", "natural", "creative", "lifestyle", "outdoor"]
            for style in styles {
                let prompts = lightx.getBackgroundStyleSuggestions()[style] ?? []
                let result = try await lightx.generateBackgroundWithPrompt(
                    imageData: inputImageData,
                    textPrompt: prompts[0],
                    contentType: "image/jpeg"
                )
                print("🎉 \(style) background result:")
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
