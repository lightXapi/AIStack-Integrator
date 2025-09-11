/**
 * LightX AI Hairstyle API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hairstyle transformation functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct HairstyleUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairstyleUploadImageBody
}

struct HairstyleUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct HairstyleGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairstyleGenerationBody
}

struct HairstyleGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct HairstyleOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairstyleOrderStatusBody
}

struct HairstyleOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Hairstyle API Client

@MainActor
class LightXHairstyleAPI: ObservableObject {
    
    // MARK: - Properties
    
    private let apiKey: String
    private let baseURL = "https://api.lightxeditor.com/external/api"
    private let maxRetries = 5
    private let retryInterval: TimeInterval = 3.0 // seconds
    private let maxFileSize: Int64 = 5242880 // 5MB
    
    // MARK: - Initialization
    
    init(apiKey: String) {
        self.apiKey = apiKey
    }
    
    // MARK: - Public Methods
    
    /**
     * Upload image to LightX servers
     * @param imageData Image data
     * @param contentType MIME type (image/jpeg or image/png)
     * @return Final image URL
     */
    func uploadImage(imageData: Data, contentType: String = "image/jpeg") async throws -> String {
        let fileSize = Int64(imageData.count)
        
        if fileSize > maxFileSize {
            throw LightXHairstyleError.imageSizeExceedsLimit
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Generate hairstyle transformation
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for hairstyle description
     * @return Order ID for tracking
     */
    func generateHairstyle(imageUrl: String, textPrompt: String) async throws -> String {
        let endpoint = "\(baseURL)/v1/hairstyle"
        
        let payload = [
            "imageUrl": imageUrl,
            "textPrompt": textPrompt
        ]
        
        let request = try createRequest(endpoint: endpoint, payload: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairstyleError.networkError
        }
        
        let hairstyleResponse = try JSONDecoder().decode(HairstyleGenerationResponse.self, from: data)
        
        if hairstyleResponse.statusCode != 2000 {
            throw LightXHairstyleError.apiError(hairstyleResponse.message)
        }
        
        let orderInfo = hairstyleResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("💇 Hairstyle prompt: \"\(textPrompt)\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> HairstyleOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        let payload = ["orderId": orderId]
        let request = try createRequest(endpoint: endpoint, payload: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairstyleError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(HairstyleOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXHairstyleError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> HairstyleOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Hairstyle transformation completed successfully!")
                    if let output = status.output {
                        print("💇 New hairstyle: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXHairstyleError.processingFailed
                    
                case "init":
                    attempts += 1
                    if attempts < maxRetries {
                        print("⏳ Waiting \(Int(retryInterval)) seconds before next check...")
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
        
        throw LightXHairstyleError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and generate hairstyle transformation
     * @param imageData Image data
     * @param textPrompt Text prompt for hairstyle description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processHairstyleGeneration(imageData: Data, textPrompt: String, contentType: String = "image/jpeg") async throws -> HairstyleOrderStatusBody {
        print("🚀 Starting LightX AI Hairstyle API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageUrl = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageUrl)")
        
        // Step 2: Generate hairstyle transformation
        print("💇 Generating hairstyle transformation...")
        let orderId = try await generateHairstyle(imageUrl: imageUrl, textPrompt: textPrompt)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get hairstyle transformation tips and best practices
     * @return Dictionary of tips for better results
     */
    func getHairstyleTips() -> [String: [String]] {
        let tips = [
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the person's face and current hair are clearly visible",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good face visibility improves hairstyle transformation results"
            ],
            "text_prompts": [
                "Be specific about the hairstyle you want to try",
                "Mention hair length, style, and characteristics",
                "Include details about hair color, texture, and cut",
                "Describe the overall look and feel you're going for",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Hairstyle transformation works best with clear face photos",
                "Results may vary based on input image quality",
                "Text prompts guide the hairstyle generation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different hairstyle descriptions"
            ]
        ]
        
        print("💡 Hairstyle Transformation Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get hairstyle style suggestions
     * @return Dictionary of hairstyle suggestions
     */
    func getHairstyleSuggestions() -> [String: [String]] {
        let hairstyleSuggestions = [
            "short_styles": [
                "pixie cut with side-swept bangs",
                "short bob with layers",
                "buzz cut with fade",
                "short curly afro",
                "asymmetrical short cut"
            ],
            "medium_styles": [
                "shoulder-length layered cut",
                "medium bob with waves",
                "lob (long bob) with face-framing layers",
                "medium length with curtain bangs",
                "shoulder-length with subtle highlights"
            ],
            "long_styles": [
                "long flowing waves",
                "straight long hair with center part",
                "long layered cut with side bangs",
                "long hair with beachy waves",
                "long hair with balayage highlights"
            ],
            "curly_styles": [
                "natural curly afro",
                "loose beachy waves",
                "tight spiral curls",
                "wavy bob with natural texture",
                "curly hair with defined ringlets"
            ],
            "trendy_styles": [
                "modern shag cut with layers",
                "wolf cut with textured ends",
                "butterfly cut with face-framing layers",
                "mullet with modern styling",
                "bixie cut (bob-pixie hybrid)"
            ]
        ]
        
        print("💡 Hairstyle Style Suggestions:")
        for (category, suggestionList) in hairstyleSuggestions {
            print("\(category): \(suggestionList)")
        }
        return hairstyleSuggestions
    }
    
    /**
     * Get hairstyle prompt examples
     * @return Dictionary of prompt examples
     */
    func getHairstylePromptExamples() -> [String: [String]] {
        let promptExamples = [
            "classic": [
                "Classic bob haircut with clean lines",
                "Traditional pixie cut with side part",
                "Classic long layers with subtle waves",
                "Timeless shoulder-length cut with bangs",
                "Classic short back and sides with longer top"
            ],
            "modern": [
                "Modern shag cut with textured layers",
                "Contemporary lob with face-framing highlights",
                "Trendy wolf cut with choppy ends",
                "Modern pixie with asymmetrical styling",
                "Contemporary long hair with curtain bangs"
            ],
            "casual": [
                "Casual beachy waves for everyday wear",
                "Relaxed shoulder-length cut with natural texture",
                "Easy-care short bob with minimal styling",
                "Casual long hair with loose waves",
                "Low-maintenance pixie with natural movement"
            ],
            "formal": [
                "Elegant updo with sophisticated styling",
                "Formal bob with sleek, polished finish",
                "Classic long hair styled for special occasions",
                "Professional short cut with refined styling",
                "Elegant shoulder-length cut with smooth finish"
            ],
            "creative": [
                "Bold asymmetrical cut with dramatic angles",
                "Creative color-blocked hairstyle",
                "Artistic pixie with unique styling",
                "Dramatic long layers with bold highlights",
                "Creative short cut with geometric styling"
            ]
        ]
        
        print("💡 Hairstyle Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
    }
    
    /**
     * Get face shape hairstyle recommendations
     * @return Dictionary of face shape recommendations
     */
    func getFaceShapeRecommendations() -> [String: [String: Any]] {
        let faceShapeRecommendations = [
            "oval": [
                "description": "Most versatile face shape - can pull off most hairstyles",
                "recommended": ["Long layers", "Pixie cuts", "Bob cuts", "Side-swept bangs", "Any length works well"],
                "avoid": ["Heavy bangs that cover forehead", "Styles that add width to face"]
            ],
            "round": [
                "description": "Face is as wide as it is long with soft, curved lines",
                "recommended": ["Long layers", "Asymmetrical cuts", "Side parts", "Height at crown", "Angular cuts"],
                "avoid": ["Short, rounded cuts", "Center parts", "Full bangs", "Styles that add width"]
            ],
            "square": [
                "description": "Strong jawline with angular features",
                "recommended": ["Soft layers", "Side-swept bangs", "Longer styles", "Rounded cuts", "Texture and movement"],
                "avoid": ["Sharp, angular cuts", "Straight-across bangs", "Very short cuts"]
            ],
            "heart": [
                "description": "Wider at forehead, narrower at chin",
                "recommended": ["Chin-length cuts", "Side-swept bangs", "Layered styles", "Volume at chin level"],
                "avoid": ["Very short cuts", "Heavy bangs", "Styles that add width at top"]
            ],
            "long": [
                "description": "Face is longer than it is wide",
                "recommended": ["Shorter cuts", "Side parts", "Layers", "Bangs", "Width-adding styles"],
                "avoid": ["Very long, straight styles", "Center parts", "Height at crown"]
            ]
        ]
        
        print("💡 Face Shape Hairstyle Recommendations:")
        for (shape, info) in faceShapeRecommendations {
            print("\(shape): \(info["description"] ?? "")")
            if let recommended = info["recommended"] as? [String] {
                print("  Recommended: \(recommended.joined(separator: ", "))")
            }
            if let avoid = info["avoid"] as? [String] {
                print("  Avoid: \(avoid.joined(separator: ", "))")
            }
        }
        return faceShapeRecommendations
    }
    
    /**
     * Get hair type styling tips
     * @return Dictionary of hair type tips
     */
    func getHairTypeTips() -> [String: [String: Any]] {
        let hairTypeTips = [
            "straight": [
                "characteristics": "Smooth, lacks natural curl or wave",
                "styling_tips": ["Layers add movement", "Blunt cuts work well", "Texture can be added with styling"],
                "best_styles": ["Blunt bob", "Long layers", "Pixie cuts", "Straight-across bangs"]
            ],
            "wavy": [
                "characteristics": "Natural S-shaped waves",
                "styling_tips": ["Enhance natural texture", "Layers work beautifully", "Avoid over-straightening"],
                "best_styles": ["Layered cuts", "Beachy waves", "Shoulder-length styles", "Natural texture cuts"]
            ],
            "curly": [
                "characteristics": "Natural spiral or ringlet formation",
                "styling_tips": ["Work with natural curl pattern", "Avoid heavy layers", "Moisture is key"],
                "best_styles": ["Curly bobs", "Natural afro", "Layered curls", "Curly pixie cuts"]
            ],
            "coily": [
                "characteristics": "Tight, springy curls or coils",
                "styling_tips": ["Embrace natural texture", "Regular moisture needed", "Protective styles work well"],
                "best_styles": ["Natural afro", "Twist-outs", "Bantu knots", "Protective braided styles"]
            ]
        ]
        
        print("💡 Hair Type Styling Tips:")
        for (hairType, info) in hairTypeTips {
            print("\(hairType): \(info["characteristics"] ?? "")")
            if let stylingTips = info["styling_tips"] as? [String] {
                print("  Styling tips: \(stylingTips.joined(separator: ", "))")
            }
            if let bestStyles = info["best_styles"] as? [String] {
                print("  Best styles: \(bestStyles.joined(separator: ", "))")
            }
        }
        return hairTypeTips
    }
    
    /**
     * Validate text prompt (utility function)
     * @param textPrompt Text prompt to validate
     * @return Whether the prompt is valid
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
     * Generate hairstyle with prompt validation
     * @param imageData Image data
     * @param textPrompt Text prompt for hairstyle description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func generateHairstyleWithValidation(imageData: Data, textPrompt: String, contentType: String = "image/jpeg") async throws -> HairstyleOrderStatusBody {
        if !validateTextPrompt(textPrompt: textPrompt) {
            throw LightXHairstyleError.invalidParameters
        }
        
        return try await processHairstyleGeneration(imageData: imageData, textPrompt: textPrompt, contentType: contentType)
    }
    
    /**
     * Get image dimensions (utility function)
     * @param imageData Image data
     * @return Tuple of (width, height)
     */
    func getImageDimensions(imageData: Data) -> (width: Int, height: Int) {
        guard let image = UIImage(data: imageData) else {
            print("❌ Error getting image dimensions: Invalid image data")
            return (0, 0)
        }
        
        let width = Int(image.size.width)
        let height = Int(image.size.height)
        print("📏 Image dimensions: \(width)x\(height)")
        return (width, height)
    }
    
    // MARK: - Private Methods
    
    private func getUploadURL(fileSize: Int64, contentType: String) async throws -> HairstyleUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        let payload = [
            "uploadType": "imageUrl",
            "size": fileSize,
            "contentType": contentType
        ]
        
        let request = try createRequest(endpoint: endpoint, payload: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairstyleError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(HairstyleUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXHairstyleError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXHairstyleError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairstyleError.uploadFailed
        }
    }
    
    private func createRequest(endpoint: String, payload: [String: Any]) throws -> URLRequest {
        guard let url = URL(string: endpoint) else {
            throw LightXHairstyleError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        return request
    }
}

// MARK: - Error Types

enum LightXHairstyleError: Error, LocalizedError {
    case networkError
    case apiError(String)
    case imageSizeExceedsLimit
    case processingFailed
    case maxRetriesReached
    case invalidURL
    case uploadFailed
    case invalidParameters
    
    var errorDescription: String? {
        switch self {
        case .networkError:
            return "Network error occurred"
        case .apiError(let message):
            return "API error: \(message)"
        case .imageSizeExceedsLimit:
            return "Image size exceeds 5MB limit"
        case .processingFailed:
            return "Hairstyle transformation failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidURL:
            return "Invalid URL"
        case .uploadFailed:
            return "Image upload failed"
        case .invalidParameters:
            return "Invalid parameters provided"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXHairstyleExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXHairstyleAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getHairstyleTips()
            lightx.getHairstyleSuggestions()
            lightx.getHairstylePromptExamples()
            lightx.getFaceShapeRecommendations()
            lightx.getHairTypeTips()
            
            // Load image (replace with your image loading logic)
            guard let imageData = UIImage(named: "input-image")?.jpegData(compressionQuality: 0.8) else {
                print("❌ Could not load image")
                return
            }
            
            // Example 1: Try a classic bob hairstyle
            let result1 = try await lightx.generateHairstyleWithValidation(
                imageData: imageData,
                textPrompt: "Classic bob haircut with clean lines and side part",
                contentType: "image/jpeg"
            )
            print("🎉 Classic bob result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Try a modern pixie cut
            let result2 = try await lightx.generateHairstyleWithValidation(
                imageData: imageData,
                textPrompt: "Modern pixie cut with asymmetrical styling and texture",
                contentType: "image/jpeg"
            )
            print("🎉 Modern pixie result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Try different hairstyles
            let hairstyles = [
                "Long flowing waves with natural texture",
                "Shoulder-length layered cut with curtain bangs",
                "Short curly afro with natural texture",
                "Beachy waves with sun-kissed highlights"
            ]
            
            for hairstyle in hairstyles {
                let result = try await lightx.generateHairstyleWithValidation(
                    imageData: imageData,
                    textPrompt: hairstyle,
                    contentType: "image/jpeg"
                )
                print("🎉 \(hairstyle) result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 4: Get image dimensions
            let (width, height) = lightx.getImageDimensions(imageData: imageData)
            if width > 0 && height > 0 {
                print("📏 Original image: \(width)x\(height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
