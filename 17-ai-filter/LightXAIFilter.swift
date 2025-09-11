/**
 * LightX AI Filter API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image filtering functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct FilterUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: FilterUploadImageBody
}

struct FilterUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct FilterGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: FilterGenerationBody
}

struct FilterGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct FilterOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: FilterOrderStatusBody
}

struct FilterOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX AI Filter API Client

@MainActor
class LightXAIFilterAPI: ObservableObject {
    
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
            throw LightXFilterError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Generate AI filter
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for filter description
     * @param styleImageUrl Optional style image URL
     * @return Order ID for tracking
     */
    func generateFilter(imageUrl: String, textPrompt: String, styleImageUrl: String? = nil) async throws -> String {
        let endpoint = "\(baseURL)/v2/aifilter"
        
        var requestBody = [
            "imageUrl": imageUrl,
            "textPrompt": textPrompt
        ] as [String: Any]
        
        // Add style image URL if provided
        if let styleImageUrl = styleImageUrl {
            requestBody["styleImageUrl"] = styleImageUrl
        }
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXFilterError.networkError
        }
        
        let filterResponse = try JSONDecoder().decode(FilterGenerationResponse.self, from: data)
        
        if filterResponse.statusCode != 2000 {
            throw LightXFilterError.apiError(filterResponse.message)
        }
        
        let orderInfo = filterResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🎨 Filter prompt: \"\(textPrompt)\"")
        if let styleImageUrl = styleImageUrl {
            print("🎭 Style image: \(styleImageUrl)")
        }
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> FilterOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXFilterError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(FilterOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXFilterError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> FilterOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ AI filter applied successfully!")
                    if let output = status.output {
                        print("🎨 Filtered image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXFilterError.processingFailed
                    
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
        
        throw LightXFilterError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and apply AI filter
     * @param imageData Image data
     * @param textPrompt Text prompt for filter description
     * @param styleImageData Optional style image data
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processFilter(imageData: Data, textPrompt: String, styleImageData: Data? = nil, contentType: String = "image/jpeg") async throws -> FilterOrderStatusBody {
        print("🚀 Starting LightX AI Filter API workflow...")
        
        // Step 1: Upload main image
        print("📤 Uploading main image...")
        let imageUrl = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Main image uploaded: \(imageUrl)")
        
        // Step 2: Upload style image if provided
        var styleImageUrl: String? = nil
        if let styleImageData = styleImageData {
            print("📤 Uploading style image...")
            styleImageUrl = try await uploadImage(imageData: styleImageData, contentType: contentType)
            print("✅ Style image uploaded: \(styleImageUrl!)")
        }
        
        // Step 3: Generate filter
        print("🎨 Applying AI filter...")
        let orderId = try await generateFilter(imageUrl: imageUrl, textPrompt: textPrompt, styleImageUrl: styleImageUrl)
        
        // Step 4: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get AI filter tips and best practices
     * @return Dictionary containing tips for better results
     */
    func getFilterTips() -> [String: [String]] {
        let tips = [
            "input_image": [
                "Use clear, well-lit images with good contrast",
                "Ensure the image has good composition and framing",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best filter results",
                "Good source image quality improves filter application"
            ],
            "text_prompts": [
                "Be specific about the filter style you want to apply",
                "Describe the mood, atmosphere, or artistic style desired",
                "Include details about colors, lighting, and effects",
                "Mention specific artistic movements or styles",
                "Keep prompts descriptive but concise"
            ],
            "style_images": [
                "Use style images that match your desired aesthetic",
                "Ensure style images have good quality and clarity",
                "Choose style images with strong visual characteristics",
                "Style images work best when they complement the main image",
                "Experiment with different style images for varied results"
            ],
            "general": [
                "AI filters work best with clear, detailed source images",
                "Results may vary based on input image quality and content",
                "Filters can dramatically transform image appearance and mood",
                "Allow 15-30 seconds for processing",
                "Experiment with different prompts and style combinations"
            ]
        ]
        
        print("💡 AI Filter Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get filter style suggestions
     * @return Dictionary containing style suggestions
     */
    func getFilterStyleSuggestions() -> [String: [String]] {
        let styleSuggestions = [
            "artistic": [
                "Oil painting style with rich textures",
                "Watercolor painting with soft edges",
                "Digital art with vibrant colors",
                "Sketch drawing with pencil strokes",
                "Abstract art with geometric shapes"
            ],
            "photography": [
                "Vintage film photography with grain",
                "Black and white with high contrast",
                "HDR photography with enhanced details",
                "Portrait photography with soft lighting",
                "Street photography with documentary style"
            ],
            "cinematic": [
                "Film noir with dramatic shadows",
                "Sci-fi with neon colors and effects",
                "Horror with dark, moody atmosphere",
                "Romance with warm, soft lighting",
                "Action with dynamic, high-contrast look"
            ],
            "vintage": [
                "Retro 80s with neon and synthwave",
                "Vintage 70s with warm, earthy tones",
                "Classic Hollywood glamour",
                "Victorian era with sepia tones",
                "Art Deco with geometric patterns"
            ],
            "modern": [
                "Minimalist with clean lines",
                "Contemporary with bold colors",
                "Urban with gritty textures",
                "Futuristic with metallic surfaces",
                "Instagram aesthetic with bright colors"
            ]
        ]
        
        print("💡 Filter Style Suggestions:")
        for (category, suggestionList) in styleSuggestions {
            print("\(category): \(suggestionList)")
        }
        return styleSuggestions
    }
    
    /**
     * Get filter prompt examples
     * @return Dictionary containing prompt examples
     */
    func getFilterPromptExamples() -> [String: [String]] {
        let promptExamples = [
            "artistic": [
                "Transform into oil painting with rich textures and warm colors",
                "Apply watercolor effect with soft, flowing edges",
                "Create digital art style with vibrant, saturated colors",
                "Convert to pencil sketch with detailed line work",
                "Apply abstract art style with geometric patterns"
            ],
            "mood": [
                "Create mysterious atmosphere with dark shadows and blue tones",
                "Apply warm, romantic lighting with golden hour glow",
                "Transform to dramatic, high-contrast black and white",
                "Create dreamy, ethereal effect with soft pastels",
                "Apply energetic, vibrant style with bold colors"
            ],
            "vintage": [
                "Apply retro 80s synthwave style with neon colors",
                "Transform to vintage film photography with grain",
                "Create Victorian era aesthetic with sepia tones",
                "Apply Art Deco style with geometric patterns",
                "Transform to classic Hollywood glamour"
            ],
            "modern": [
                "Apply minimalist style with clean, simple composition",
                "Create contemporary look with bold, modern colors",
                "Transform to urban aesthetic with gritty textures",
                "Apply futuristic style with metallic surfaces",
                "Create Instagram-worthy aesthetic with bright colors"
            ],
            "cinematic": [
                "Apply film noir style with dramatic lighting",
                "Create sci-fi atmosphere with neon effects",
                "Transform to horror aesthetic with dark mood",
                "Apply romance style with soft, warm lighting",
                "Create action movie look with high contrast"
            ]
        ]
        
        print("💡 Filter Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
    }
    
    /**
     * Get filter use cases and examples
     * @return Dictionary containing use case examples
     */
    func getFilterUseCases() -> [String: [String]] {
        let useCases = [
            "social_media": [
                "Create Instagram-worthy aesthetic filters",
                "Apply trendy social media filters",
                "Transform photos for different platforms",
                "Create consistent brand aesthetic",
                "Enhance photos for social sharing"
            ],
            "marketing": [
                "Create branded filter effects",
                "Apply campaign-specific aesthetics",
                "Transform product photos with style",
                "Create cohesive visual identity",
                "Enhance marketing materials"
            ],
            "creative": [
                "Explore artistic styles and effects",
                "Create unique visual interpretations",
                "Experiment with different aesthetics",
                "Transform photos into art pieces",
                "Develop creative visual concepts"
            ],
            "photography": [
                "Apply professional photo filters",
                "Create consistent editing style",
                "Transform photos for different moods",
                "Apply vintage or retro effects",
                "Enhance photo aesthetics"
            ],
            "personal": [
                "Create personalized photo styles",
                "Apply favorite artistic effects",
                "Transform memories with filters",
                "Create unique photo collections",
                "Experiment with visual styles"
            ]
        ]
        
        print("💡 Filter Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
    }
    
    /**
     * Get filter combination suggestions
     * @return Dictionary containing combination suggestions
     */
    func getFilterCombinations() -> [String: [String]] {
        let combinations = [
            "text_only": [
                "Use descriptive text prompts for specific effects",
                "Combine multiple style descriptions in one prompt",
                "Include mood, lighting, and color specifications",
                "Reference artistic movements or photographers",
                "Describe the desired emotional impact"
            ],
            "text_with_style": [
                "Use text prompt for overall direction",
                "Add style image for specific visual reference",
                "Combine text description with style image",
                "Use style image to guide color palette",
                "Apply text prompt with style image influence"
            ],
            "style_only": [
                "Use style image as primary reference",
                "Let style image guide the transformation",
                "Apply style image characteristics to main image",
                "Use style image for color and texture reference",
                "Transform based on style image aesthetic"
            ]
        ]
        
        print("💡 Filter Combination Suggestions:")
        for (category, combinationList) in combinations {
            print("\(category): \(combinationList)")
        }
        return combinations
    }
    
    /**
     * Get filter intensity suggestions
     * @return Dictionary containing intensity suggestions
     */
    func getFilterIntensitySuggestions() -> [String: [String]] {
        let intensitySuggestions = [
            "subtle": [
                "Apply gentle color adjustments",
                "Add subtle texture overlays",
                "Enhance existing colors slightly",
                "Apply soft lighting effects",
                "Create minimal artistic touches"
            ],
            "moderate": [
                "Apply noticeable style changes",
                "Transform colors and mood",
                "Add artistic texture effects",
                "Create distinct visual style",
                "Apply balanced filter effects"
            ],
            "dramatic": [
                "Apply bold, transformative effects",
                "Create dramatic color changes",
                "Add strong artistic elements",
                "Transform image completely",
                "Apply intense visual effects"
            ]
        ]
        
        print("💡 Filter Intensity Suggestions:")
        for (intensity, suggestionList) in intensitySuggestions {
            print("\(intensity): \(suggestionList)")
        }
        return intensitySuggestions
    }
    
    /**
     * Get filter category recommendations
     * @return Dictionary containing category recommendations
     */
    func getFilterCategories() -> [String: [String: Any]] {
        let categories = [
            "artistic": [
                "description": "Transform images into various artistic styles",
                "examples": ["Oil painting", "Watercolor", "Digital art", "Sketch", "Abstract"],
                "best_for": ["Creative projects", "Artistic expression", "Unique visuals"]
            ],
            "vintage": [
                "description": "Apply retro and vintage aesthetics",
                "examples": ["80s synthwave", "Film photography", "Victorian", "Art Deco", "Classic Hollywood"],
                "best_for": ["Nostalgic content", "Retro branding", "Historical themes"]
            ],
            "modern": [
                "description": "Apply contemporary and modern styles",
                "examples": ["Minimalist", "Contemporary", "Urban", "Futuristic", "Instagram aesthetic"],
                "best_for": ["Modern branding", "Social media", "Contemporary design"]
            ],
            "cinematic": [
                "description": "Create movie-like visual effects",
                "examples": ["Film noir", "Sci-fi", "Horror", "Romance", "Action"],
                "best_for": ["Video content", "Dramatic visuals", "Storytelling"]
            ],
            "mood": [
                "description": "Set specific emotional atmospheres",
                "examples": ["Mysterious", "Romantic", "Dramatic", "Dreamy", "Energetic"],
                "best_for": ["Emotional content", "Mood setting", "Atmospheric visuals"]
            ]
        ]
        
        print("💡 Filter Categories:")
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
     * Generate filter with prompt validation
     * @param imageData Image data
     * @param textPrompt Text prompt for filter description
     * @param styleImageData Optional style image data
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func generateFilterWithValidation(imageData: Data, textPrompt: String, styleImageData: Data? = nil, contentType: String = "image/jpeg") async throws -> FilterOrderStatusBody {
        if !validateTextPrompt(textPrompt: textPrompt) {
            throw LightXFilterError.invalidParameters
        }
        
        return try await processFilter(imageData: imageData, textPrompt: textPrompt, styleImageData: styleImageData, contentType: contentType)
    }
    
    /**
     * Get filter best practices
     * @return Dictionary containing best practices
     */
    func getFilterBestPractices() -> [String: [String]] {
        let bestPractices = [
            "prompt_writing": [
                "Be specific about the desired style or effect",
                "Include details about colors, lighting, and mood",
                "Reference specific artistic movements or photographers",
                "Describe the emotional impact you want to achieve",
                "Keep prompts concise but descriptive"
            ],
            "style_image_selection": [
                "Choose style images with strong visual characteristics",
                "Ensure style images complement your main image",
                "Use high-quality style images for better results",
                "Experiment with different style images",
                "Consider the color palette of style images"
            ],
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in originals",
                "Avoid heavily compressed or low-quality images",
                "Consider the composition and framing",
                "Use images with clear, well-defined details"
            ],
            "workflow_optimization": [
                "Batch process multiple images when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        ]
        
        print("💡 Filter Best Practices:")
        for (category, practiceList) in bestPractices {
            print("\(category): \(practiceList)")
        }
        return bestPractices
    }
    
    /**
     * Get filter performance tips
     * @return Dictionary containing performance tips
     */
    func getFilterPerformanceTips() -> [String: [String]] {
        let performanceTips = [
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before applying filters",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple images"
            ],
            "resource_management": [
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after filtering",
                "Optimize network requests and retry logic"
            ],
            "user_experience": [
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer filter previews when possible",
                "Provide tips for better input images"
            ]
        ]
        
        print("💡 Performance Tips:")
        for (category, tipList) in performanceTips {
            print("\(category): \(tipList)")
        }
        return performanceTips
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let requestURL = URL(string: url) else {
            throw LightXFilterError.invalidURL
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> FilterUploadImageBody {
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
            throw LightXFilterError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(FilterUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXFilterError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXFilterError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXFilterError.uploadFailed
        }
    }
}

// MARK: - Error Types

enum LightXFilterError: Error, LocalizedError {
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
            return "AI filter application failed"
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

struct LightXFilterExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXAIFilterAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getFilterTips()
            lightx.getFilterStyleSuggestions()
            lightx.getFilterPromptExamples()
            lightx.getFilterUseCases()
            lightx.getFilterCombinations()
            lightx.getFilterIntensitySuggestions()
            lightx.getFilterCategories()
            lightx.getFilterBestPractices()
            lightx.getFilterPerformanceTips()
            
            // Load images (replace with your image loading logic)
            guard let imageData = loadImageData() else {
                print("❌ Failed to load image data")
                return
            }
            
            let styleImageData = loadStyleImageData() // Optional
            
            // Example 1: Text prompt only
            let result1 = try await lightx.generateFilterWithValidation(
                imageData: imageData,
                textPrompt: "Transform into oil painting with rich textures and warm colors",
                styleImageData: nil, // No style image
                contentType: "image/jpeg"
            )
            print("🎉 Oil painting filter result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Text prompt with style image
            if let styleImageData = styleImageData {
                let result2 = try await lightx.generateFilterWithValidation(
                    imageData: imageData,
                    textPrompt: "Apply vintage film photography style",
                    styleImageData: styleImageData, // With style image
                    contentType: "image/jpeg"
                )
                print("🎉 Vintage film filter result:")
                print("Order ID: \(result2.orderId)")
                print("Status: \(result2.status)")
                if let output = result2.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 3: Try different filter styles
            let filterStyles = [
                "Create watercolor effect with soft, flowing edges",
                "Apply retro 80s synthwave style with neon colors",
                "Transform to dramatic, high-contrast black and white",
                "Create dreamy, ethereal effect with soft pastels",
                "Apply minimalist style with clean, simple composition"
            ]
            
            for style in filterStyles {
                let result = try await lightx.generateFilterWithValidation(
                    imageData: imageData,
                    textPrompt: style,
                    styleImageData: nil,
                    contentType: "image/jpeg"
                )
                print("🎉 \(style) result:")
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
    
    private static func loadStyleImageData() -> Data? {
        // Replace with your style image loading logic
        // This is a placeholder - you would load from bundle, documents, or camera
        return nil
    }
}

// MARK: - SwiftUI Integration Example

import SwiftUI

struct FilterView: View {
    @StateObject private var filterAPI = LightXAIFilterAPI(apiKey: "YOUR_API_KEY_HERE")
    @State private var selectedImage: UIImage?
    @State private var selectedStyleImage: UIImage?
    @State private var textPrompt: String = ""
    @State private var isProcessing = false
    @State private var result: FilterOrderStatusBody?
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 20) {
            Text("LightX AI Filter")
                .font(.largeTitle)
                .fontWeight(.bold)
            
            if let image = selectedImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxHeight: 200)
                    .cornerRadius(10)
            }
            
            if let styleImage = selectedStyleImage {
                Image(uiImage: styleImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxHeight: 100)
                    .cornerRadius(10)
                    .overlay(
                        Text("Style Image")
                            .font(.caption)
                            .padding(4)
                            .background(Color.black.opacity(0.7))
                            .foregroundColor(.white)
                            .cornerRadius(4),
                        alignment: .topLeading
                    )
            }
            
            TextField("Enter filter prompt", text: $textPrompt, axis: .vertical)
                .textFieldStyle(RoundedBorderTextFieldStyle())
                .lineLimit(3...6)
            
            HStack {
                Button("Select Image") {
                    // Image picker logic here
                }
                .buttonStyle(.borderedProminent)
                
                Button("Select Style") {
                    // Style image picker logic here
                }
                .buttonStyle(.bordered)
            }
            
            Button("Apply Filter") {
                Task {
                    await applyFilter()
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedImage == nil || textPrompt.isEmpty || isProcessing)
            
            if isProcessing {
                ProgressView("Processing...")
            }
            
            if let result = result {
                VStack {
                    Text("✅ Filter Applied!")
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
    
    private func applyFilter() async {
        guard let image = selectedImage else { return }
        
        isProcessing = true
        errorMessage = nil
        result = nil
        
        do {
            let imageData = image.jpegData(compressionQuality: 0.8) ?? Data()
            let styleImageData = selectedStyleImage?.jpegData(compressionQuality: 0.8)
            
            let filterResult = try await filterAPI.generateFilterWithValidation(
                imageData: imageData,
                textPrompt: textPrompt,
                styleImageData: styleImageData,
                contentType: "image/jpeg"
            )
            result = filterResult
        } catch {
            errorMessage = error.localizedDescription
        }
        
        isProcessing = false
    }
}
