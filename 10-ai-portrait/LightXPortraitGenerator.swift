/**
 * LightX AI Portrait Generator API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered portrait generation functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct PortraitUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: PortraitUploadImageBody
}

struct PortraitUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct PortraitGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: PortraitGenerationBody
}

struct PortraitGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct PortraitOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: PortraitOrderStatusBody
}

struct PortraitOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Portrait Generator API Client

class LightXPortraitAPI {
    
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
            throw LightXPortraitError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Upload multiple images (input and optional style image)
     * - Parameters:
     *   - inputImageData: Input image data
     *   - styleImageData: Style image data (optional)
     *   - contentType: MIME type
     * - Returns: Tuple of (inputURL, styleURL?)
     */
    func uploadImages(inputImageData: Data, styleImageData: Data? = nil, 
                     contentType: String = "image/jpeg") async throws -> (String, String?) {
        print("📤 Uploading input image...")
        let inputURL = try await uploadImage(imageData: inputImageData, contentType: contentType)
        
        var styleURL: String? = nil
        if let styleData = styleImageData {
            print("📤 Uploading style image...")
            styleURL = try await uploadImage(imageData: styleData, contentType: contentType)
        }
        
        return (inputURL, styleURL)
    }
    
    /**
     * Generate portrait
     * - Parameters:
     *   - imageURL: URL of the input image
     *   - styleImageURL: URL of the style image (optional)
     *   - textPrompt: Text prompt for portrait style (optional)
     * - Returns: Order ID for tracking
     */
    func generatePortrait(imageURL: String, styleImageURL: String? = nil, textPrompt: String? = nil) async throws -> String {
        let endpoint = "\(baseURL)/v1/portrait"
        
        guard let url = URL(string: endpoint) else {
            throw LightXPortraitError.invalidURL
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
            throw LightXPortraitError.networkError
        }
        
        let portraitResponse = try JSONDecoder().decode(PortraitGenerationResponse.self, from: data)
        
        guard portraitResponse.statusCode == 2000 else {
            throw LightXPortraitError.apiError(portraitResponse.message)
        }
        
        let orderInfo = portraitResponse.body
        
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
    func checkOrderStatus(orderID: String) async throws -> PortraitOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXPortraitError.invalidURL
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
            throw LightXPortraitError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(PortraitOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXPortraitError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> PortraitOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Portrait generation completed successfully!")
                    if let output = status.output {
                        print("🖼️ Portrait image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXPortraitError.processingFailed
                    
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
        
        throw LightXPortraitError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and generate portrait
     * - Parameters:
     *   - inputImageData: Input image data
     *   - styleImageData: Style image data (optional)
     *   - textPrompt: Text prompt for portrait style (optional)
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processPortraitGeneration(inputImageData: Data, styleImageData: Data? = nil, 
                                  textPrompt: String? = nil, contentType: String = "image/jpeg") async throws -> PortraitOrderStatusBody {
        print("🚀 Starting LightX AI Portrait Generator API workflow...")
        
        // Step 1: Upload images
        print("📤 Uploading images...")
        let (inputURL, styleURL) = try await uploadImages(
            inputImageData: inputImageData, 
            styleImageData: styleImageData, 
            contentType: contentType
        )
        print("✅ Input image uploaded: \(inputURL)")
        if let styleURL = styleURL {
            print("✅ Style image uploaded: \(styleURL)")
        }
        
        // Step 2: Generate portrait
        print("🖼️ Generating portrait...")
        let orderID = try await generatePortrait(imageURL: inputURL, styleImageURL: styleURL, textPrompt: textPrompt)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    /**
     * Get common text prompts for different portrait styles
     * - Parameter category: Category of portrait style
     * - Returns: Array of suggested prompts
     */
    func getSuggestedPrompts(category: String) -> [String] {
        let promptSuggestions: [String: [String]] = [
            "realistic": [
                "realistic portrait photography",
                "professional headshot style",
                "natural lighting portrait",
                "studio portrait photography",
                "high-quality realistic portrait"
            ],
            "artistic": [
                "artistic portrait style",
                "creative portrait photography",
                "artistic interpretation portrait",
                "creative portrait art",
                "artistic portrait rendering"
            ],
            "vintage": [
                "vintage portrait style",
                "retro portrait photography",
                "classic portrait style",
                "old school portrait",
                "vintage film portrait"
            ],
            "modern": [
                "modern portrait style",
                "contemporary portrait photography",
                "sleek modern portrait",
                "contemporary art portrait",
                "modern artistic portrait"
            ],
            "fantasy": [
                "fantasy portrait style",
                "magical portrait art",
                "fantasy character portrait",
                "mystical portrait style",
                "fantasy art portrait"
            ],
            "minimalist": [
                "minimalist portrait style",
                "clean simple portrait",
                "minimal portrait photography",
                "simple elegant portrait",
                "minimalist art portrait"
            ],
            "dramatic": [
                "dramatic portrait style",
                "high contrast portrait",
                "dramatic lighting portrait",
                "intense portrait photography",
                "dramatic artistic portrait"
            ],
            "soft": [
                "soft portrait style",
                "gentle portrait photography",
                "soft lighting portrait",
                "delicate portrait art",
                "soft artistic portrait"
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
     * Generate portrait with text prompt only
     * - Parameters:
     *   - inputImageData: Input image data
     *   - textPrompt: Text prompt for portrait style
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generatePortraitWithPrompt(inputImageData: Data, textPrompt: String, 
                                   contentType: String = "image/jpeg") async throws -> PortraitOrderStatusBody {
        guard validateTextPrompt(textPrompt: textPrompt) else {
            throw LightXPortraitError.invalidPrompt
        }
        
        return try await processPortraitGeneration(
            inputImageData: inputImageData, 
            styleImageData: nil, 
            textPrompt: textPrompt, 
            contentType: contentType
        )
    }
    
    /**
     * Generate portrait with style image only
     * - Parameters:
     *   - inputImageData: Input image data
     *   - styleImageData: Style image data
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generatePortraitWithStyle(inputImageData: Data, styleImageData: Data, 
                                  contentType: String = "image/jpeg") async throws -> PortraitOrderStatusBody {
        return try await processPortraitGeneration(
            inputImageData: inputImageData, 
            styleImageData: styleImageData, 
            textPrompt: nil, 
            contentType: contentType
        )
    }
    
    /**
     * Generate portrait with both style image and text prompt
     * - Parameters:
     *   - inputImageData: Input image data
     *   - styleImageData: Style image data
     *   - textPrompt: Text prompt for portrait style
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func generatePortraitWithStyleAndPrompt(inputImageData: Data, styleImageData: Data, 
                                           textPrompt: String, contentType: String = "image/jpeg") async throws -> PortraitOrderStatusBody {
        guard validateTextPrompt(textPrompt: textPrompt) else {
            throw LightXPortraitError.invalidPrompt
        }
        
        return try await processPortraitGeneration(
            inputImageData: inputImageData, 
            styleImageData: styleImageData, 
            textPrompt: textPrompt, 
            contentType: contentType
        )
    }
    
    /**
     * Get portrait tips and best practices
     * - Returns: Dictionary of tips for better portrait results
     */
    func getPortraitTips() -> [String: [String]] {
        let tips: [String: [String]] = [
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for portraits"
            ],
            "style_image": [
                "Choose style images with desired artistic style",
                "Use portrait examples as style references",
                "Ensure style image has good lighting and composition",
                "Match the artistic direction you want for your portrait",
                "Use high-quality style reference images"
            ],
            "text_prompts": [
                "Be specific about the portrait style you want",
                "Mention artistic preferences (realistic, artistic, vintage)",
                "Include lighting preferences (soft, dramatic, natural)",
                "Specify the mood (professional, creative, dramatic)",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Portraits work best with clear human faces",
                "Results may vary based on input image quality",
                "Style images influence both artistic style and composition",
                "Text prompts help guide the portrait generation",
                "Allow 15-30 seconds for processing"
            ]
        ]
        
        print("💡 Portrait Generation Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get portrait style-specific tips
     * - Returns: Dictionary of style-specific tips
     */
    func getPortraitStyleTips() -> [String: [String]] {
        let styleTips: [String: [String]] = [
            "realistic": [
                "Use natural lighting for best results",
                "Ensure good facial detail and clarity",
                "Consider professional headshot style",
                "Use neutral backgrounds for focus on subject"
            ],
            "artistic": [
                "Choose creative and expressive style images",
                "Consider artistic interpretation over realism",
                "Use bold colors and creative compositions",
                "Experiment with different artistic styles"
            ],
            "vintage": [
                "Use warm, nostalgic color tones",
                "Consider film photography aesthetics",
                "Use classic portrait compositions",
                "Apply vintage color grading effects"
            ],
            "modern": [
                "Use contemporary photography styles",
                "Consider clean, minimalist compositions",
                "Use modern lighting techniques",
                "Apply contemporary color palettes"
            ],
            "fantasy": [
                "Use magical or mystical style references",
                "Consider fantasy art aesthetics",
                "Use dramatic lighting and effects",
                "Apply fantasy color schemes"
            ]
        ]
        
        print("💡 Portrait Style Tips:")
        for (style, tipList) in styleTips {
            print("\(style): \(tipList)")
        }
        return styleTips
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> PortraitUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXPortraitError.invalidURL
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
            throw LightXPortraitError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(PortraitUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXPortraitError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: PortraitUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXPortraitError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXPortraitError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXPortraitError: Error, LocalizedError {
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
            return "Portrait generation processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidPrompt:
            return "Invalid text prompt"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXPortraitExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXPortraitAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getPortraitTips()
            lightx.getPortraitStyleTips()
            
            // Load input image data (replace with your image loading logic)
            guard let inputImage = UIImage(named: "input_image"),
                  let inputImageData = inputImage.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load input image")
                return
            }
            
            // Example 1: Generate portrait with text prompt only
            let realisticPrompts = lightx.getSuggestedPrompts(category: "realistic")
            let result1 = try await lightx.generatePortraitWithPrompt(
                inputImageData: inputImageData,
                textPrompt: realisticPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Realistic portrait result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Generate portrait with style image only
            guard let styleImage = UIImage(named: "style_image"),
                  let styleImageData = styleImage.jpegData(compressionQuality: 1.0) else {
                print("❌ Failed to load style image")
                return
            }
            
            let result2 = try await lightx.generatePortraitWithStyle(
                inputImageData: inputImageData,
                styleImageData: styleImageData,
                contentType: "image/jpeg"
            )
            print("🎉 Style-based portrait result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Generate portrait with both style image and text prompt
            let artisticPrompts = lightx.getSuggestedPrompts(category: "artistic")
            let result3 = try await lightx.generatePortraitWithStyleAndPrompt(
                inputImageData: inputImageData,
                styleImageData: styleImageData,
                textPrompt: artisticPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Combined style and prompt result:")
            print("Order ID: \(result3.orderId)")
            print("Status: \(result3.status)")
            if let output = result3.output {
                print("Output: \(output)")
            }
            
            // Example 4: Generate portraits for different styles
            let styles = ["realistic", "artistic", "vintage", "modern", "fantasy", "minimalist", "dramatic", "soft"]
            for style in styles {
                let prompts = lightx.getSuggestedPrompts(category: style)
                let result = try await lightx.generatePortraitWithPrompt(
                    inputImageData: inputImageData,
                    textPrompt: prompts[0],
                    contentType: "image/jpeg"
                )
                print("🎉 \(style) portrait result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 5: Get image dimensions
            let (width, height) = lightx.getImageDimensions(imageData: inputImageData)
            if width > 0 && height > 0 {
                print("📏 Original image: \(width)x\(height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
