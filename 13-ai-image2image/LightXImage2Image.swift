/**
 * LightX AI Image to Image API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image to image transformation functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct Image2ImageUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: Image2ImageUploadImageBody
}

struct Image2ImageUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct Image2ImageGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: Image2ImageGenerationBody
}

struct Image2ImageGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct Image2ImageOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: Image2ImageOrderStatusBody
}

struct Image2ImageOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Image to Image API Client

@MainActor
class LightXImage2ImageAPI: ObservableObject {
    
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
            throw LightXImage2ImageError.imageSizeExceedsLimit
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Upload multiple images (input and optional style image)
     * @param inputImageData Input image data
     * @param styleImageData Style image data (optional)
     * @param contentType MIME type
     * @return Tuple of (inputURL, styleURL)
     */
    func uploadImages(inputImageData: Data, styleImageData: Data? = nil, contentType: String = "image/jpeg") async throws -> (inputURL: String, styleURL: String?) {
        print("📤 Uploading input image...")
        let inputURL = try await uploadImage(imageData: inputImageData, contentType: contentType)
        
        var styleURL: String? = nil
        if let styleImageData = styleImageData {
            print("📤 Uploading style image...")
            styleURL = try await uploadImage(imageData: styleImageData, contentType: contentType)
        }
        
        return (inputURL, styleURL)
    }
    
    /**
     * Generate image to image transformation
     * @param imageUrl URL of the input image
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageUrl URL of the style image (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @return Order ID for tracking
     */
    func generateImage2Image(imageUrl: String, strength: Double, textPrompt: String, styleImageUrl: String? = nil, styleStrength: Double? = nil) async throws -> String {
        let endpoint = "\(baseURL)/v1/image2image"
        
        var payload: [String: Any] = [
            "imageUrl": imageUrl,
            "strength": strength,
            "textPrompt": textPrompt
        ]
        
        // Add optional parameters
        if let styleImageUrl = styleImageUrl {
            payload["styleImageUrl"] = styleImageUrl
        }
        if let styleStrength = styleStrength {
            payload["styleStrength"] = styleStrength
        }
        
        let request = try createRequest(endpoint: endpoint, payload: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXImage2ImageError.networkError
        }
        
        let image2ImageResponse = try JSONDecoder().decode(Image2ImageGenerationResponse.self, from: data)
        
        if image2ImageResponse.statusCode != 2000 {
            throw LightXImage2ImageError.apiError(image2ImageResponse.message)
        }
        
        let orderInfo = image2ImageResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("💬 Text prompt: \"\(textPrompt)\"")
        print("🎨 Strength: \(strength)")
        if let styleImageUrl = styleImageUrl {
            print("🎭 Style image: \(styleImageUrl)")
            print("🎨 Style strength: \(styleStrength ?? 0)")
        }
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> Image2ImageOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        let payload = ["orderId": orderId]
        let request = try createRequest(endpoint: endpoint, payload: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXImage2ImageError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(Image2ImageOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXImage2ImageError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> Image2ImageOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Image to image transformation completed successfully!")
                    if let output = status.output {
                        print("🖼️ Transformed image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXImage2ImageError.processingFailed
                    
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
        
        throw LightXImage2ImageError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and generate image to image transformation
     * @param inputImageData Input image data
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageData Style image data (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processImage2ImageGeneration(inputImageData: Data, strength: Double, textPrompt: String, styleImageData: Data? = nil, styleStrength: Double? = nil, contentType: String = "image/jpeg") async throws -> Image2ImageOrderStatusBody {
        print("🚀 Starting LightX AI Image to Image API workflow...")
        
        // Step 1: Upload images
        print("📤 Uploading images...")
        let (inputURL, styleURL) = try await uploadImages(inputImageData: inputImageData, styleImageData: styleImageData, contentType: contentType)
        print("✅ Input image uploaded: \(inputURL)")
        if let styleURL = styleURL {
            print("✅ Style image uploaded: \(styleURL)")
        }
        
        // Step 2: Generate image to image transformation
        print("🖼️ Generating image to image transformation...")
        let orderId = try await generateImage2Image(imageUrl: inputURL, strength: strength, textPrompt: textPrompt, styleImageUrl: styleURL, styleStrength: styleStrength)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get image to image transformation tips and best practices
     * @return Dictionary of tips for better results
     */
    func getImage2ImageTips() -> [String: [String]] {
        let tips = [
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the subject is clearly visible and well-composed",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good image quality improves transformation results"
            ],
            "strength_parameter": [
                "Higher strength (0.7-1.0) makes output more similar to input",
                "Lower strength (0.1-0.3) allows more creative transformation",
                "Medium strength (0.4-0.6) balances similarity and creativity",
                "Experiment with different strength values for desired results",
                "Strength affects how much the original image structure is preserved"
            ],
            "style_image": [
                "Choose style images with desired visual characteristics",
                "Ensure style image has good quality and clear features",
                "Style strength controls how much style is applied",
                "Higher style strength applies more style characteristics",
                "Use style images that complement your text prompt"
            ],
            "text_prompts": [
                "Be specific about the transformation you want",
                "Mention artistic styles, colors, and visual elements",
                "Include details about lighting, mood, and atmosphere",
                "Combine style descriptions with content descriptions",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Image to image works best with clear, well-composed photos",
                "Results may vary based on input image quality",
                "Text prompts guide the transformation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different strength and style combinations"
            ]
        ]
        
        print("💡 Image to Image Transformation Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get strength parameter suggestions
     * @return Dictionary of strength suggestions
     */
    func getStrengthSuggestions() -> [String: [String: Any]] {
        let strengthSuggestions = [
            "conservative": [
                "range": "0.7 - 1.0",
                "description": "Preserves most of the original image structure and content",
                "use_cases": ["Minor style adjustments", "Color corrections", "Light enhancement", "Subtle artistic effects"]
            ],
            "balanced": [
                "range": "0.4 - 0.6",
                "description": "Balances original content with creative transformation",
                "use_cases": ["Style transfer", "Artistic interpretation", "Medium-level changes", "Creative enhancement"]
            ],
            "creative": [
                "range": "0.1 - 0.3",
                "description": "Allows significant creative transformation while keeping basic structure",
                "use_cases": ["Major style changes", "Artistic reimagining", "Creative reinterpretation", "Dramatic transformation"]
            ]
        ]
        
        print("💡 Strength Parameter Suggestions:")
        for (category, suggestion) in strengthSuggestions {
            print("\(category): \(suggestion["range"] ?? "") - \(suggestion["description"] ?? "")")
            if let useCases = suggestion["use_cases"] as? [String] {
                print("  Use cases: \(useCases.joined(separator: ", "))")
            }
        }
        return strengthSuggestions
    }
    
    /**
     * Get style strength suggestions
     * @return Dictionary of style strength suggestions
     */
    func getStyleStrengthSuggestions() -> [String: [String: Any]] {
        let styleStrengthSuggestions = [
            "subtle": [
                "range": "0.1 - 0.3",
                "description": "Applies subtle style characteristics",
                "use_cases": ["Gentle style influence", "Color palette transfer", "Light texture changes"]
            ],
            "moderate": [
                "range": "0.4 - 0.6",
                "description": "Applies moderate style characteristics",
                "use_cases": ["Clear style transfer", "Artistic interpretation", "Medium style influence"]
            ],
            "strong": [
                "range": "0.7 - 1.0",
                "description": "Applies strong style characteristics",
                "use_cases": ["Dramatic style transfer", "Complete artistic transformation", "Strong visual influence"]
            ]
        ]
        
        print("💡 Style Strength Suggestions:")
        for (category, suggestion) in styleStrengthSuggestions {
            print("\(category): \(suggestion["range"] ?? "") - \(suggestion["description"] ?? "")")
            if let useCases = suggestion["use_cases"] as? [String] {
                print("  Use cases: \(useCases.joined(separator: ", "))")
            }
        }
        return styleStrengthSuggestions
    }
    
    /**
     * Get transformation prompt examples
     * @return Dictionary of prompt examples
     */
    func getTransformationPromptExamples() -> [String: [String]] {
        let promptExamples = [
            "artistic": [
                "Transform into oil painting style with rich colors",
                "Convert to watercolor painting with soft edges",
                "Create digital art with vibrant colors and smooth gradients",
                "Transform into pencil sketch with detailed shading",
                "Convert to pop art style with bold colors and contrast"
            ],
            "style_transfer": [
                "Apply Van Gogh painting style with swirling brushstrokes",
                "Transform into Picasso cubist style with geometric shapes",
                "Apply Monet impressionist style with soft, blurred edges",
                "Convert to Andy Warhol pop art with bright, flat colors",
                "Transform into Japanese ukiyo-e woodblock print style"
            ],
            "mood_atmosphere": [
                "Create warm, golden hour lighting with soft shadows",
                "Transform into dramatic, high-contrast black and white",
                "Apply dreamy, ethereal atmosphere with soft focus",
                "Create vintage, sepia-toned nostalgic look",
                "Transform into futuristic, cyberpunk aesthetic"
            ],
            "color_enhancement": [
                "Enhance colors with vibrant, saturated tones",
                "Apply cool, blue-toned color grading",
                "Transform into warm, orange and red color palette",
                "Create monochromatic look with single color accent",
                "Apply vintage film color grading with faded tones"
            ]
        ]
        
        print("💡 Transformation Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
    }
    
    /**
     * Validate parameters (utility function)
     * @param strength Strength parameter to validate
     * @param textPrompt Text prompt to validate
     * @param styleStrength Style strength parameter to validate (optional)
     * @return Whether the parameters are valid
     */
    func validateParameters(strength: Double, textPrompt: String, styleStrength: Double? = nil) -> Bool {
        // Validate strength
        if strength < 0 || strength > 1 {
            print("❌ Strength must be between 0.0 and 1.0")
            return false
        }
        
        // Validate text prompt
        if textPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            print("❌ Text prompt cannot be empty")
            return false
        }
        
        if textPrompt.count > 500 {
            print("❌ Text prompt is too long (max 500 characters)")
            return false
        }
        
        // Validate style strength if provided
        if let styleStrength = styleStrength, (styleStrength < 0 || styleStrength > 1) {
            print("❌ Style strength must be between 0.0 and 1.0")
            return false
        }
        
        print("✅ Parameters are valid")
        return true
    }
    
    /**
     * Generate image to image transformation with parameter validation
     * @param inputImageData Input image data
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageData Style image data (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func generateImage2ImageWithValidation(inputImageData: Data, strength: Double, textPrompt: String, styleImageData: Data? = nil, styleStrength: Double? = nil, contentType: String = "image/jpeg") async throws -> Image2ImageOrderStatusBody {
        if !validateParameters(strength: strength, textPrompt: textPrompt, styleStrength: styleStrength) {
            throw LightXImage2ImageError.invalidParameters
        }
        
        return try await processImage2ImageGeneration(inputImageData: inputImageData, strength: strength, textPrompt: textPrompt, styleImageData: styleImageData, styleStrength: styleStrength, contentType: contentType)
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
    
    private func getUploadURL(fileSize: Int64, contentType: String) async throws -> Image2ImageUploadImageBody {
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
            throw LightXImage2ImageError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(Image2ImageUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXImage2ImageError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXImage2ImageError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXImage2ImageError.uploadFailed
        }
    }
    
    private func createRequest(endpoint: String, payload: [String: Any]) throws -> URLRequest {
        guard let url = URL(string: endpoint) else {
            throw LightXImage2ImageError.invalidURL
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

enum LightXImage2ImageError: Error, LocalizedError {
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
            return "Image to image transformation failed"
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
class LightXImage2ImageExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXImage2ImageAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getImage2ImageTips()
            lightx.getStrengthSuggestions()
            lightx.getStyleStrengthSuggestions()
            lightx.getTransformationPromptExamples()
            
            // Load images (replace with your image loading logic)
            guard let inputImageData = UIImage(named: "input-image")?.jpegData(compressionQuality: 0.8),
                  let styleImageData = UIImage(named: "style-image")?.jpegData(compressionQuality: 0.8) else {
                print("❌ Could not load images")
                return
            }
            
            // Example 1: Conservative transformation with text prompt only
            let result1 = try await lightx.generateImage2ImageWithValidation(
                inputImageData: inputImageData,
                strength: 0.8, // High strength to preserve original
                textPrompt: "Transform into oil painting style with rich colors",
                styleImageData: nil, // No style image
                styleStrength: nil, // No style strength
                contentType: "image/jpeg"
            )
            print("🎉 Conservative transformation result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Balanced transformation with style image
            let result2 = try await lightx.generateImage2ImageWithValidation(
                inputImageData: inputImageData,
                strength: 0.5, // Balanced strength
                textPrompt: "Apply artistic style transformation",
                styleImageData: styleImageData, // Style image
                styleStrength: 0.7, // Strong style influence
                contentType: "image/jpeg"
            )
            print("🎉 Balanced transformation result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Creative transformation with different strength values
            let strengthValues: [Double] = [0.2, 0.5, 0.8]
            for strength in strengthValues {
                let result = try await lightx.generateImage2ImageWithValidation(
                    inputImageData: inputImageData,
                    strength: strength,
                    textPrompt: "Create artistic interpretation with vibrant colors",
                    styleImageData: nil,
                    styleStrength: nil,
                    contentType: "image/jpeg"
                )
                print("🎉 Creative transformation (strength: \(strength)) result:")
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
