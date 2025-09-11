/**
 * LightX AI Sketch to Image API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered sketch to image transformation functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct Sketch2ImageUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: Sketch2ImageUploadImageBody
}

struct Sketch2ImageUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct Sketch2ImageGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: Sketch2ImageGenerationBody
}

struct Sketch2ImageGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct Sketch2ImageOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: Sketch2ImageOrderStatusBody
}

struct Sketch2ImageOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Sketch to Image API Client

@MainActor
class LightXSketch2ImageAPI: ObservableObject {
    
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
            throw LightXSketch2ImageError.imageSizeExceedsLimit
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Upload multiple images (sketch and optional style image)
     * @param sketchImageData Sketch image data
     * @param styleImageData Style image data (optional)
     * @param contentType MIME type
     * @return Tuple of (sketchURL, styleURL)
     */
    func uploadImages(sketchImageData: Data, styleImageData: Data? = nil, contentType: String = "image/jpeg") async throws -> (sketchURL: String, styleURL: String?) {
        print("📤 Uploading sketch image...")
        let sketchURL = try await uploadImage(imageData: sketchImageData, contentType: contentType)
        
        var styleURL: String? = nil
        if let styleImageData = styleImageData {
            print("📤 Uploading style image...")
            styleURL = try await uploadImage(imageData: styleImageData, contentType: contentType)
        }
        
        return (sketchURL, styleURL)
    }
    
    /**
     * Generate sketch to image transformation
     * @param imageUrl URL of the sketch image
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageUrl URL of the style image (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @return Order ID for tracking
     */
    func generateSketch2Image(imageUrl: String, strength: Double, textPrompt: String, styleImageUrl: String? = nil, styleStrength: Double? = nil) async throws -> String {
        let endpoint = "\(baseURL)/v1/sketch2image"
        
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
            throw LightXSketch2ImageError.networkError
        }
        
        let sketch2ImageResponse = try JSONDecoder().decode(Sketch2ImageGenerationResponse.self, from: data)
        
        if sketch2ImageResponse.statusCode != 2000 {
            throw LightXSketch2ImageError.apiError(sketch2ImageResponse.message)
        }
        
        let orderInfo = sketch2ImageResponse.body
        
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
    func checkOrderStatus(orderId: String) async throws -> Sketch2ImageOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        let payload = ["orderId": orderId]
        let request = try createRequest(endpoint: endpoint, payload: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXSketch2ImageError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(Sketch2ImageOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXSketch2ImageError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> Sketch2ImageOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Sketch to image transformation completed successfully!")
                    if let output = status.output {
                        print("🖼️ Generated image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXSketch2ImageError.processingFailed
                    
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
        
        throw LightXSketch2ImageError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and generate sketch to image transformation
     * @param sketchImageData Sketch image data
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageData Style image data (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processSketch2ImageGeneration(sketchImageData: Data, strength: Double, textPrompt: String, styleImageData: Data? = nil, styleStrength: Double? = nil, contentType: String = "image/jpeg") async throws -> Sketch2ImageOrderStatusBody {
        print("🚀 Starting LightX AI Sketch to Image API workflow...")
        
        // Step 1: Upload images
        print("📤 Uploading images...")
        let (sketchURL, styleURL) = try await uploadImages(sketchImageData: sketchImageData, styleImageData: styleImageData, contentType: contentType)
        print("✅ Sketch image uploaded: \(sketchURL)")
        if let styleURL = styleURL {
            print("✅ Style image uploaded: \(styleURL)")
        }
        
        // Step 2: Generate sketch to image transformation
        print("🎨 Generating sketch to image transformation...")
        let orderId = try await generateSketch2Image(imageUrl: sketchURL, strength: strength, textPrompt: textPrompt, styleImageUrl: styleURL, styleStrength: styleStrength)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get sketch to image transformation tips and best practices
     * @return Dictionary of tips for better results
     */
    func getSketch2ImageTips() -> [String: [String]] {
        let tips = [
            "sketch_quality": [
                "Use clear, well-defined sketches with good contrast",
                "Ensure sketch lines are visible and not too faint",
                "Avoid overly complex or cluttered sketches",
                "Use high-resolution sketches for better results",
                "Good sketch quality improves transformation results"
            ],
            "strength_parameter": [
                "Higher strength (0.7-1.0) makes output more similar to sketch",
                "Lower strength (0.1-0.3) allows more creative interpretation",
                "Medium strength (0.4-0.6) balances sketch structure and creativity",
                "Experiment with different strength values for desired results",
                "Strength affects how much the original sketch structure is preserved"
            ],
            "style_image": [
                "Choose style images with desired visual characteristics",
                "Ensure style image has good quality and clear features",
                "Style strength controls how much style is applied",
                "Higher style strength applies more style characteristics",
                "Use style images that complement your text prompt"
            ],
            "text_prompts": [
                "Be specific about the final image you want to create",
                "Mention colors, lighting, mood, and visual style",
                "Include details about the subject matter and composition",
                "Combine style descriptions with content descriptions",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Sketch to image works best with clear, well-composed sketches",
                "Results may vary based on sketch quality and complexity",
                "Text prompts guide the image generation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different strength and style combinations"
            ]
        ]
        
        print("💡 Sketch to Image Transformation Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get strength parameter suggestions for sketch to image
     * @return Dictionary of strength suggestions
     */
    func getStrengthSuggestions() -> [String: [String: Any]] {
        let strengthSuggestions = [
            "conservative": [
                "range": "0.7 - 1.0",
                "description": "Preserves most of the original sketch structure and composition",
                "use_cases": ["Detailed sketch interpretation", "Architectural drawings", "Technical illustrations", "Precise sketch rendering"]
            ],
            "balanced": [
                "range": "0.4 - 0.6",
                "description": "Balances sketch structure with creative interpretation",
                "use_cases": ["Artistic sketch rendering", "Creative interpretation", "Style application", "Balanced transformation"]
            ],
            "creative": [
                "range": "0.1 - 0.3",
                "description": "Allows significant creative interpretation while keeping basic sketch elements",
                "use_cases": ["Artistic reimagining", "Creative reinterpretation", "Style-heavy transformation", "Dramatic interpretation"]
            ]
        ]
        
        print("💡 Strength Parameter Suggestions for Sketch to Image:")
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
     * Get sketch to image prompt examples
     * @return Dictionary of prompt examples
     */
    func getSketch2ImagePromptExamples() -> [String: [String]] {
        let promptExamples = [
            "realistic": [
                "Create a realistic photograph with natural lighting and colors",
                "Generate a photorealistic image with detailed textures and shadows",
                "Transform into a high-quality photograph with professional lighting",
                "Create a realistic portrait with natural skin tones and expressions",
                "Generate a realistic landscape with natural colors and atmosphere"
            ],
            "artistic": [
                "Transform into oil painting style with rich colors and brushstrokes",
                "Convert to watercolor painting with soft edges and flowing colors",
                "Create digital art with vibrant colors and smooth gradients",
                "Transform into pencil sketch with detailed shading and textures",
                "Convert to pop art style with bold colors and contrast"
            ],
            "fantasy": [
                "Create a fantasy illustration with magical elements and vibrant colors",
                "Generate a sci-fi scene with futuristic technology and lighting",
                "Transform into a fantasy landscape with mystical atmosphere",
                "Create a fantasy character with magical powers and detailed costume",
                "Generate a fantasy creature with unique features and colors"
            ],
            "architectural": [
                "Create a realistic architectural visualization with proper lighting",
                "Generate a modern building design with clean lines and materials",
                "Transform into an interior design with proper perspective and lighting",
                "Create a landscape architecture with natural elements",
                "Generate a futuristic building with innovative design elements"
            ]
        ]
        
        print("💡 Sketch to Image Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
    }
    
    /**
     * Get sketch types and their characteristics
     * @return Dictionary of sketch type information
     */
    func getSketchTypes() -> [String: [String: Any]] {
        let sketchTypes = [
            "line_art": [
                "description": "Simple line drawings with clear outlines",
                "best_for": ["Character design", "Logo concepts", "Simple illustrations"],
                "tips": ["Use clear, bold lines", "Avoid too many details", "Keep composition simple"]
            ],
            "architectural": [
                "description": "Technical drawings and architectural sketches",
                "best_for": ["Building designs", "Interior layouts", "Urban planning"],
                "tips": ["Use proper perspective", "Include scale references", "Keep lines precise"]
            ],
            "character": [
                "description": "Character and figure sketches",
                "best_for": ["Character design", "Portrait concepts", "Fashion design"],
                "tips": ["Focus on proportions", "Include facial features", "Consider pose and expression"]
            ],
            "landscape": [
                "description": "Nature and landscape sketches",
                "best_for": ["Environment design", "Nature scenes", "Outdoor settings"],
                "tips": ["Include horizon line", "Show depth and perspective", "Consider lighting direction"]
            ],
            "concept": [
                "description": "Conceptual and idea sketches",
                "best_for": ["Product design", "Creative concepts", "Abstract ideas"],
                "tips": ["Focus on main concept", "Use simple shapes", "Include key elements"]
            ]
        ]
        
        print("💡 Sketch Types and Characteristics:")
        for (sketchType, info) in sketchTypes {
            print("\(sketchType): \(info["description"] ?? "")")
            if let bestFor = info["best_for"] as? [String] {
                print("  Best for: \(bestFor.joined(separator: ", "))")
            }
            if let tips = info["tips"] as? [String] {
                print("  Tips: \(tips.joined(separator: ", "))")
            }
        }
        return sketchTypes
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
     * Generate sketch to image transformation with parameter validation
     * @param sketchImageData Sketch image data
     * @param strength Strength parameter (0.0 to 1.0)
     * @param textPrompt Text prompt for transformation
     * @param styleImageData Style image data (optional)
     * @param styleStrength Style strength parameter (0.0 to 1.0, optional)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func generateSketch2ImageWithValidation(sketchImageData: Data, strength: Double, textPrompt: String, styleImageData: Data? = nil, styleStrength: Double? = nil, contentType: String = "image/jpeg") async throws -> Sketch2ImageOrderStatusBody {
        if !validateParameters(strength: strength, textPrompt: textPrompt, styleStrength: styleStrength) {
            throw LightXSketch2ImageError.invalidParameters
        }
        
        return try await processSketch2ImageGeneration(sketchImageData: sketchImageData, strength: strength, textPrompt: textPrompt, styleImageData: styleImageData, styleStrength: styleStrength, contentType: contentType)
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
    
    private func getUploadURL(fileSize: Int64, contentType: String) async throws -> Sketch2ImageUploadImageBody {
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
            throw LightXSketch2ImageError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(Sketch2ImageUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXSketch2ImageError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXSketch2ImageError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXSketch2ImageError.uploadFailed
        }
    }
    
    private func createRequest(endpoint: String, payload: [String: Any]) throws -> URLRequest {
        guard let url = URL(string: endpoint) else {
            throw LightXSketch2ImageError.invalidURL
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

enum LightXSketch2ImageError: Error, LocalizedError {
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
            return "Sketch to image transformation failed"
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
class LightXSketch2ImageExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXSketch2ImageAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getSketch2ImageTips()
            lightx.getStrengthSuggestions()
            lightx.getStyleStrengthSuggestions()
            lightx.getSketch2ImagePromptExamples()
            lightx.getSketchTypes()
            
            // Load images (replace with your image loading logic)
            guard let sketchImageData = UIImage(named: "sketch-image")?.jpegData(compressionQuality: 0.8),
                  let styleImageData = UIImage(named: "style-image")?.jpegData(compressionQuality: 0.8) else {
                print("❌ Could not load images")
                return
            }
            
            // Example 1: Conservative sketch to image transformation
            let result1 = try await lightx.generateSketch2ImageWithValidation(
                sketchImageData: sketchImageData,
                strength: 0.8, // High strength to preserve sketch structure
                textPrompt: "Create a realistic photograph with natural lighting and colors",
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
            let result2 = try await lightx.generateSketch2ImageWithValidation(
                sketchImageData: sketchImageData,
                strength: 0.5, // Balanced strength
                textPrompt: "Transform into oil painting style with rich colors",
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
                let result = try await lightx.generateSketch2ImageWithValidation(
                    sketchImageData: sketchImageData,
                    strength: strength,
                    textPrompt: "Create a fantasy illustration with magical elements and vibrant colors",
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
            let (width, height) = lightx.getImageDimensions(imageData: sketchImageData)
            if width > 0 && height > 0 {
                print("📏 Original sketch: \(width)x\(height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
