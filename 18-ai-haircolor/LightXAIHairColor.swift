/**
 * LightX AI Hair Color API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hair color changing functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct HairColorUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairColorUploadImageBody
}

struct HairColorUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct HairColorGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairColorGenerationBody
}

struct HairColorGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct HairColorOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairColorOrderStatusBody
}

struct HairColorOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX AI Hair Color API Client

@MainActor
class LightXAIHairColorAPI: ObservableObject {
    
    // MARK: - Properties
    
    private let apiKey: String
    private let baseURL = "https://api.lightxeditor.com/external/api"
    private let maxRetries = 5
    private let retryInterval: TimeInterval = 3.0
    private let maxFileSize: Int = 5242880 // 5MB
    
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
        let fileSize = imageData.count
        
        if fileSize > maxFileSize {
            throw LightXHairColorError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Change hair color using AI
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for hair color description
     * @return Order ID for tracking
     */
    func changeHairColor(imageUrl: String, textPrompt: String) async throws -> String {
        let endpoint = "\(baseURL)/v2/haircolor/"
        
        let requestBody = [
            "imageUrl": imageUrl,
            "textPrompt": textPrompt
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairColorError.networkError
        }
        
        let hairColorResponse = try JSONDecoder().decode(HairColorGenerationResponse.self, from: data)
        
        if hairColorResponse.statusCode != 2000 {
            throw LightXHairColorError.apiError(hairColorResponse.message)
        }
        
        let orderInfo = hairColorResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🎨 Hair color prompt: \"\(textPrompt)\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> HairColorOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairColorError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(HairColorOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXHairColorError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> HairColorOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Hair color changed successfully!")
                    if let output = status.output {
                        print("🎨 New hair color image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXHairColorError.processingFailed
                    
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
        
        throw LightXHairColorError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and change hair color
     * @param imageData Image data
     * @param textPrompt Text prompt for hair color description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processHairColorChange(imageData: Data, textPrompt: String, contentType: String = "image/jpeg") async throws -> HairColorOrderStatusBody {
        print("🚀 Starting LightX AI Hair Color API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageUrl = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageUrl)")
        
        // Step 2: Change hair color
        print("🎨 Changing hair color...")
        let orderId = try await changeHairColor(imageUrl: imageUrl, textPrompt: textPrompt)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get hair color tips and best practices
     * @return Dictionary containing tips for better results
     */
    func getHairColorTips() -> [String: [String]] {
        let tips = [
            "input_image": [
                "Use clear, well-lit photos with visible hair",
                "Ensure the person's hair is clearly visible in the image",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best hair color results",
                "Good lighting helps preserve hair texture and details"
            ],
            "text_prompts": [
                "Be specific about the hair color you want to achieve",
                "Include details about shade, tone, and intensity",
                "Mention specific hair color names or descriptions",
                "Describe the desired hair color effect clearly",
                "Keep prompts descriptive but concise"
            ],
            "hair_visibility": [
                "Ensure hair is clearly visible and not obscured",
                "Avoid images where hair is covered by hats or accessories",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "general": [
                "AI hair color works best with clear, detailed source images",
                "Results may vary based on input image quality and hair visibility",
                "Hair color changes preserve original texture and style",
                "Allow 15-30 seconds for processing",
                "Experiment with different color descriptions for varied results"
            ]
        ]
        
        print("💡 Hair Color Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get hair color suggestions
     * @return Dictionary containing hair color suggestions
     */
    func getHairColorSuggestions() -> [String: [String]] {
        let colorSuggestions = [
            "natural_colors": [
                "Natural black hair",
                "Dark brown hair",
                "Medium brown hair",
                "Light brown hair",
                "Natural blonde hair",
                "Strawberry blonde hair",
                "Auburn hair",
                "Red hair",
                "Gray hair",
                "Silver hair"
            ],
            "vibrant_colors": [
                "Bright red hair",
                "Vibrant orange hair",
                "Electric blue hair",
                "Purple hair",
                "Pink hair",
                "Green hair",
                "Yellow hair",
                "Turquoise hair",
                "Magenta hair",
                "Neon colors"
            ],
            "highlights_and_effects": [
                "Blonde highlights",
                "Brown highlights",
                "Red highlights",
                "Ombre hair effect",
                "Balayage hair effect",
                "Gradient hair colors",
                "Two-tone hair",
                "Color streaks",
                "Peekaboo highlights",
                "Money piece highlights"
            ],
            "trendy_colors": [
                "Rose gold hair",
                "Platinum blonde hair",
                "Ash blonde hair",
                "Chocolate brown hair",
                "Chestnut brown hair",
                "Copper hair",
                "Burgundy hair",
                "Mahogany hair",
                "Honey blonde hair",
                "Caramel highlights"
            ],
            "fantasy_colors": [
                "Unicorn hair colors",
                "Mermaid hair colors",
                "Galaxy hair colors",
                "Rainbow hair colors",
                "Pastel hair colors",
                "Metallic hair colors",
                "Holographic hair colors",
                "Chrome hair colors",
                "Iridescent hair colors",
                "Duochrome hair colors"
            ]
        ]
        
        print("💡 Hair Color Suggestions:")
        for (category, suggestionList) in colorSuggestions {
            print("\(category): \(suggestionList)")
        }
        return colorSuggestions
    }
    
    /**
     * Get hair color prompt examples
     * @return Dictionary containing prompt examples
     */
    func getHairColorPromptExamples() -> [String: [String]] {
        let promptExamples = [
            "natural_colors": [
                "Change hair to natural black color",
                "Transform hair to dark brown shade",
                "Apply medium brown hair color",
                "Change to light brown hair",
                "Transform to natural blonde hair",
                "Apply strawberry blonde hair color",
                "Change to auburn hair color",
                "Transform to natural red hair",
                "Apply gray hair color",
                "Change to silver hair color"
            ],
            "vibrant_colors": [
                "Change hair to bright red color",
                "Transform to vibrant orange hair",
                "Apply electric blue hair color",
                "Change to purple hair",
                "Transform to pink hair color",
                "Apply green hair color",
                "Change to yellow hair",
                "Transform to turquoise hair",
                "Apply magenta hair color",
                "Change to neon colors"
            ],
            "highlights_and_effects": [
                "Add blonde highlights to hair",
                "Apply brown highlights",
                "Add red highlights to hair",
                "Create ombre hair effect",
                "Apply balayage hair effect",
                "Create gradient hair colors",
                "Apply two-tone hair colors",
                "Add color streaks to hair",
                "Create peekaboo highlights",
                "Apply money piece highlights"
            ],
            "trendy_colors": [
                "Change hair to rose gold color",
                "Transform to platinum blonde hair",
                "Apply ash blonde hair color",
                "Change to chocolate brown hair",
                "Transform to chestnut brown hair",
                "Apply copper hair color",
                "Change to burgundy hair",
                "Transform to mahogany hair",
                "Apply honey blonde hair color",
                "Create caramel highlights"
            ],
            "fantasy_colors": [
                "Create unicorn hair colors",
                "Apply mermaid hair colors",
                "Create galaxy hair colors",
                "Apply rainbow hair colors",
                "Create pastel hair colors",
                "Apply metallic hair colors",
                "Create holographic hair colors",
                "Apply chrome hair colors",
                "Create iridescent hair colors",
                "Apply duochrome hair colors"
            ]
        ]
        
        print("💡 Hair Color Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
    }
    
    /**
     * Get hair color use cases and examples
     * @return Dictionary containing use case examples
     */
    func getHairColorUseCases() -> [String: [String]] {
        let useCases = [
            "virtual_try_on": [
                "Virtual hair color try-on for salons",
                "Hair color consultation tools",
                "Before and after hair color previews",
                "Hair color selection assistance",
                "Virtual hair color makeovers"
            ],
            "beauty_platforms": [
                "Beauty app hair color features",
                "Hair color recommendation systems",
                "Virtual hair color consultations",
                "Hair color trend exploration",
                "Beauty influencer content creation"
            ],
            "personal_styling": [
                "Personal hair color experimentation",
                "Hair color change visualization",
                "Style inspiration and exploration",
                "Hair color trend testing",
                "Personal beauty transformations"
            ],
            "marketing": [
                "Hair color product marketing",
                "Salon service promotion",
                "Hair color brand campaigns",
                "Beauty product demonstrations",
                "Hair color trend showcases"
            ],
            "entertainment": [
                "Character hair color changes",
                "Costume and makeup design",
                "Creative hair color concepts",
                "Artistic hair color expressions",
                "Fantasy hair color creations"
            ]
        ]
        
        print("💡 Hair Color Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
    }
    
    /**
     * Get hair color intensity suggestions
     * @return Dictionary containing intensity suggestions
     */
    func getHairColorIntensitySuggestions() -> [String: [String]] {
        let intensitySuggestions = [
            "subtle": [
                "Apply subtle hair color changes",
                "Add gentle color highlights",
                "Create natural-looking color variations",
                "Apply soft color transitions",
                "Create minimal color effects"
            ],
            "moderate": [
                "Apply noticeable hair color changes",
                "Create distinct color variations",
                "Add visible color highlights",
                "Apply balanced color effects",
                "Create moderate color transformations"
            ],
            "dramatic": [
                "Apply bold hair color changes",
                "Create dramatic color transformations",
                "Add vibrant color effects",
                "Apply intense color variations",
                "Create striking color changes"
            ]
        ]
        
        print("💡 Hair Color Intensity Suggestions:")
        for (intensity, suggestionList) in intensitySuggestions {
            print("\(intensity): \(suggestionList)")
        }
        return intensitySuggestions
    }
    
    /**
     * Get hair color category recommendations
     * @return Dictionary containing category recommendations
     */
    func getHairColorCategories() -> [String: [String: Any]] {
        let categories = [
            "natural": [
                "description": "Natural hair colors that look realistic",
                "examples": ["Black", "Brown", "Blonde", "Red", "Gray"],
                "best_for": ["Professional looks", "Natural appearances", "Everyday styling"]
            ],
            "vibrant": [
                "description": "Bright and bold hair colors",
                "examples": ["Electric blue", "Purple", "Pink", "Green", "Orange"],
                "best_for": ["Creative expression", "Bold statements", "Artistic looks"]
            ],
            "highlights": [
                "description": "Highlight and lowlight effects",
                "examples": ["Blonde highlights", "Ombre", "Balayage", "Streaks", "Peekaboo"],
                "best_for": ["Subtle changes", "Dimension", "Style enhancement"]
            ],
            "trendy": [
                "description": "Current popular hair color trends",
                "examples": ["Rose gold", "Platinum", "Ash blonde", "Copper", "Burgundy"],
                "best_for": ["Fashion-forward looks", "Trend following", "Modern styling"]
            ],
            "fantasy": [
                "description": "Creative and fantasy hair colors",
                "examples": ["Unicorn", "Mermaid", "Galaxy", "Rainbow", "Pastel"],
                "best_for": ["Creative projects", "Fantasy themes", "Artistic expression"]
            ]
        ]
        
        print("💡 Hair Color Categories:")
        for (category, info) in categories {
            print("\(category): \(info["description"] ?? "")")
            if let examples = info["examples"] as? [String] {
                print("  Examples: \(examples.joined(separator: ", "))")
            }
            if let bestFor = info["best_for"] as? [String] {
                print("  Best for: \(bestFor.joined(separator: ", "))")
            }
        }
        return categories
    }
    
    /**
     * Validate text prompt
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
     * Get image dimensions from UIImage
     * @param image UIImage to get dimensions from
     * @return Tuple of (width, height)
     */
    func getImageDimensions(image: UIImage) -> (width: Int, height: Int) {
        let width = Int(image.size.width * image.scale)
        let height = Int(image.size.height * image.scale)
        print("📏 Image dimensions: \(width)x\(height)")
        return (width, height)
    }
    
    /**
     * Get image dimensions from image data
     * @param imageData Image data
     * @return Tuple of (width, height)
     */
    func getImageDimensions(imageData: Data) -> (width: Int, height: Int) {
        guard let image = UIImage(data: imageData) else {
            print("❌ Error creating image from data")
            return (0, 0)
        }
        return getImageDimensions(image: image)
    }
    
    /**
     * Generate hair color change with prompt validation
     * @param imageData Image data
     * @param textPrompt Text prompt for hair color description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func generateHairColorChangeWithValidation(imageData: Data, textPrompt: String, contentType: String = "image/jpeg") async throws -> HairColorOrderStatusBody {
        if !validateTextPrompt(textPrompt: textPrompt) {
            throw LightXHairColorError.invalidParameters
        }
        
        return try await processHairColorChange(imageData: imageData, textPrompt: textPrompt, contentType: contentType)
    }
    
    /**
     * Get hair color best practices
     * @return Dictionary containing best practices
     */
    func getHairColorBestPractices() -> [String: [String]] {
        let bestPractices = [
            "prompt_writing": [
                "Be specific about the desired hair color",
                "Include details about shade, tone, and intensity",
                "Mention specific hair color names or descriptions",
                "Describe the desired hair color effect clearly",
                "Keep prompts descriptive but concise"
            ],
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure hair is clearly visible and well-lit",
                "Avoid heavily compressed or low-quality images",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible"
            ],
            "hair_visibility": [
                "Ensure hair is not covered by hats or accessories",
                "Use images with good hair definition",
                "Avoid images with extreme angles or poor lighting",
                "Ensure hair texture is visible",
                "Use images where hair is the main focus"
            ],
            "workflow_optimization": [
                "Batch process multiple images when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        ]
        
        print("💡 Hair Color Best Practices:")
        for (category, practiceList) in bestPractices {
            print("\(category): \(practiceList)")
        }
        return bestPractices
    }
    
    /**
     * Get hair color performance tips
     * @return Dictionary containing performance tips
     */
    func getHairColorPerformanceTips() -> [String: [String]] {
        let performanceTips = [
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple images"
            ],
            "resource_management": [
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after processing",
                "Optimize network requests and retry logic"
            ],
            "user_experience": [
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer hair color previews when possible",
                "Provide tips for better input images"
            ]
        ]
        
        print("💡 Performance Tips:")
        for (category, tipList) in performanceTips {
            print("\(category): \(tipList)")
        }
        return performanceTips
    }
    
    /**
     * Get hair color technical specifications
     * @return Dictionary containing technical specifications
     */
    func getHairColorTechnicalSpecifications() -> [String: [String: Any]] {
        let specifications = [
            "supported_formats": [
                "input": ["JPEG", "PNG"],
                "output": ["JPEG"],
                "color_spaces": ["RGB", "sRGB"]
            ],
            "size_limits": [
                "max_file_size": "5MB",
                "max_dimension": "No specific limit",
                "min_dimension": "1px"
            ],
            "processing": [
                "max_retries": 5,
                "retry_interval": "3 seconds",
                "avg_processing_time": "15-30 seconds",
                "timeout": "No timeout limit"
            ],
            "features": [
                "text_prompts": "Required for hair color description",
                "hair_detection": "Automatic hair detection and segmentation",
                "color_preservation": "Preserves hair texture and style",
                "facial_features": "Keeps facial features untouched",
                "output_quality": "High-quality JPEG output"
            ]
        ]
        
        print("💡 Hair Color Technical Specifications:")
        for (category, specs) in specifications {
            print("\(category): \(specs)")
        }
        return specifications
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let requestURL = URL(string: url) else {
            throw LightXHairColorError.invalidURL
        }
        
        var request = URLRequest(url: requestURL)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        if method == "POST" {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        
        return request
    }
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> HairColorUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        let requestBody = [
            "uploadType": "imageUrl",
            "size": fileSize,
            "contentType": contentType
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairColorError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(HairColorUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXHairColorError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXHairColorError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairColorError.uploadFailed
        }
    }
}

// MARK: - Error Types

enum LightXHairColorError: Error, LocalizedError {
    case invalidURL
    case networkError
    case apiError(String)
    case imageSizeExceeded
    case processingFailed
    case maxRetriesReached
    case uploadFailed
    case invalidParameters
    
    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid URL provided"
        case .networkError:
            return "Network error occurred"
        case .apiError(let message):
            return "API error: \(message)"
        case .imageSizeExceeded:
            return "Image size exceeds 5MB limit"
        case .processingFailed:
            return "Hair color change failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .uploadFailed:
            return "Image upload failed"
        case .invalidParameters:
            return "Invalid parameters provided"
        }
    }
}

// MARK: - Example Usage

struct LightXHairColorExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXAIHairColorAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getHairColorTips()
            lightx.getHairColorSuggestions()
            lightx.getHairColorPromptExamples()
            lightx.getHairColorUseCases()
            lightx.getHairColorIntensitySuggestions()
            lightx.getHairColorCategories()
            lightx.getHairColorBestPractices()
            lightx.getHairColorPerformanceTips()
            lightx.getHairColorTechnicalSpecifications()
            
            // Load image (replace with your image loading logic)
            guard let imageData = loadImageData() else {
                print("❌ Failed to load image data")
                return
            }
            
            // Example 1: Natural hair colors
            let naturalColors = [
                "Change hair to natural black color",
                "Transform hair to dark brown shade",
                "Apply medium brown hair color",
                "Change to light brown hair",
                "Transform to natural blonde hair"
            ]
            
            for color in naturalColors {
                let result = try await lightx.generateHairColorChangeWithValidation(
                    imageData: imageData,
                    textPrompt: color,
                    contentType: "image/jpeg"
                )
                print("🎉 \(color) result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 2: Vibrant hair colors
            let vibrantColors = [
                "Change hair to bright red color",
                "Transform to electric blue hair",
                "Apply purple hair color",
                "Change to pink hair",
                "Transform to green hair color"
            ]
            
            for color in vibrantColors {
                let result = try await lightx.generateHairColorChangeWithValidation(
                    imageData: imageData,
                    textPrompt: color,
                    contentType: "image/jpeg"
                )
                print("🎉 \(color) result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 3: Highlights and effects
            let highlights = [
                "Add blonde highlights to hair",
                "Create ombre hair effect",
                "Apply balayage hair effect",
                "Add color streaks to hair",
                "Create peekaboo highlights"
            ]
            
            for highlight in highlights {
                let result = try await lightx.generateHairColorChangeWithValidation(
                    imageData: imageData,
                    textPrompt: highlight,
                    contentType: "image/jpeg"
                )
                print("🎉 \(highlight) result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 4: Get image dimensions
            let dimensions = lightx.getImageDimensions(imageData: imageData)
            if dimensions.width > 0 && dimensions.height > 0 {
                print("📏 Original image: \(dimensions.width)x\(dimensions.height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
    
    private static func loadImageData() -> Data? {
        // Replace with your image loading logic
        // This is a placeholder - you would load from bundle, documents, or camera
        return nil
    }
}

// MARK: - SwiftUI Integration Example

import SwiftUI

struct HairColorView: View {
    @StateObject private var hairColorAPI = LightXAIHairColorAPI(apiKey: "YOUR_API_KEY_HERE")
    @State private var selectedImage: UIImage?
    @State private var textPrompt: String = ""
    @State private var isProcessing = false
    @State private var result: HairColorOrderStatusBody?
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 20) {
            Text("LightX AI Hair Color")
                .font(.largeTitle)
                .fontWeight(.bold)
            
            if let image = selectedImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxHeight: 200)
                    .cornerRadius(10)
            }
            
            TextField("Enter hair color prompt", text: $textPrompt, axis: .vertical)
                .textFieldStyle(RoundedBorderTextFieldStyle())
                .lineLimit(3...6)
            
            Button("Select Image") {
                // Image picker logic here
            }
            .buttonStyle(.borderedProminent)
            
            Button("Change Hair Color") {
                Task {
                    await changeHairColor()
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedImage == nil || textPrompt.isEmpty || isProcessing)
            
            if isProcessing {
                ProgressView("Processing...")
            }
            
            if let result = result {
                VStack {
                    Text("✅ Hair Color Changed!")
                        .foregroundColor(.green)
                    if let output = result.output {
                        Text("Output: \(output)")
                            .font(.caption)
                    }
                }
            }
            
            if let error = errorMessage {
                Text("❌ Error: \(error)")
                    .foregroundColor(.red)
            }
        }
        .padding()
    }
    
    private func changeHairColor() async {
        guard let image = selectedImage else { return }
        
        isProcessing = true
        errorMessage = nil
        result = nil
        
        do {
            let imageData = image.jpegData(compressionQuality: 0.8) ?? Data()
            
            let hairColorResult = try await hairColorAPI.generateHairColorChangeWithValidation(
                imageData: imageData,
                textPrompt: textPrompt,
                contentType: "image/jpeg"
            )
            result = hairColorResult
        } catch {
            errorMessage = error.localizedDescription
        }
        
        isProcessing = false
    }
}
