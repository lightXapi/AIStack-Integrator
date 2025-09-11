/**
 * LightX AI Headshot Generator API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered professional headshot generation functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct HeadshotUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HeadshotUploadImageBody
}

struct HeadshotUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct HeadshotGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HeadshotGenerationBody
}

struct HeadshotGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct HeadshotOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HeadshotOrderStatusBody
}

struct HeadshotOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX AI Headshot Generator API Client

@MainActor
class LightXAIHeadshotAPI: ObservableObject {
    
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
            throw LightXHeadshotError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Generate professional headshot using AI
     * @param imageUrl URL of the input image
     * @param textPrompt Text prompt for professional outfit description
     * @return Order ID for tracking
     */
    func generateHeadshot(imageUrl: String, textPrompt: String) async throws -> String {
        let endpoint = "\(baseURL)/v2/headshot/"
        
        let requestBody = [
            "imageUrl": imageUrl,
            "textPrompt": textPrompt
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHeadshotError.networkError
        }
        
        let headshotResponse = try JSONDecoder().decode(HeadshotGenerationResponse.self, from: data)
        
        if headshotResponse.statusCode != 2000 {
            throw LightXHeadshotError.apiError(headshotResponse.message)
        }
        
        let orderInfo = headshotResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("👔 Professional prompt: \"\(textPrompt)\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> HeadshotOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHeadshotError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(HeadshotOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXHeadshotError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> HeadshotOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Professional headshot generated successfully!")
                    if let output = status.output {
                        print("👔 Professional headshot: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXHeadshotError.processingFailed
                    
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
        
        throw LightXHeadshotError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and generate professional headshot
     * @param imageData Image data
     * @param textPrompt Text prompt for professional outfit description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processHeadshotGeneration(imageData: Data, textPrompt: String, contentType: String = "image/jpeg") async throws -> HeadshotOrderStatusBody {
        print("🚀 Starting LightX AI Headshot Generator API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageUrl = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageUrl)")
        
        // Step 2: Generate professional headshot
        print("👔 Generating professional headshot...")
        let orderId = try await generateHeadshot(imageUrl: imageUrl, textPrompt: textPrompt)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get headshot generation tips and best practices
     * @return Dictionary containing tips for better results
     */
    func getHeadshotTips() -> [String: [String]] {
        let tips = [
            "input_image": [
                "Use clear, well-lit photos with good face visibility",
                "Ensure the person's face is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best headshot results",
                "Good lighting helps preserve facial features and details"
            ],
            "text_prompts": [
                "Be specific about the professional look you want to achieve",
                "Include details about outfit style, background, and setting",
                "Mention specific professional attire or business wear",
                "Describe the desired professional appearance clearly",
                "Keep prompts descriptive but concise"
            ],
            "professional_setting": [
                "Specify professional background settings",
                "Mention corporate or business environments",
                "Include details about lighting and atmosphere",
                "Describe professional color schemes",
                "Specify formal or business-casual settings"
            ],
            "outfit_selection": [
                "Choose professional attire descriptions",
                "Select business-appropriate clothing",
                "Use formal or business-casual outfit descriptions",
                "Ensure outfit descriptions match professional standards",
                "Choose outfits that complement the person's appearance"
            ],
            "general": [
                "AI headshot generation works best with clear, detailed source images",
                "Results may vary based on input image quality and prompt clarity",
                "Headshots preserve facial features while enhancing professional appearance",
                "Allow 15-30 seconds for processing",
                "Experiment with different professional prompts for varied results"
            ]
        ]
        
        print("💡 Headshot Generation Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get professional outfit suggestions
     * @return Dictionary containing professional outfit suggestions
     */
    func getProfessionalOutfitSuggestions() -> [String: [String]] {
        let outfitSuggestions = [
            "business_formal": [
                "Dark business suit with white dress shirt",
                "Professional blazer with dress pants",
                "Formal business attire with tie",
                "Corporate suit with dress shoes",
                "Executive business wear"
            ],
            "business_casual": [
                "Blazer with dress shirt and chinos",
                "Professional sweater with dress pants",
                "Business casual blouse with skirt",
                "Smart casual outfit with dress shoes",
                "Professional casual attire"
            ],
            "corporate": [
                "Corporate dress with blazer",
                "Professional blouse with pencil skirt",
                "Business dress with heels",
                "Corporate suit with accessories",
                "Professional corporate wear"
            ],
            "executive": [
                "Executive suit with power tie",
                "Professional dress with statement jewelry",
                "Executive blazer with dress pants",
                "Power suit with professional accessories",
                "Executive business attire"
            ],
            "professional": [
                "Professional blouse with dress pants",
                "Business dress with cardigan",
                "Professional shirt with blazer",
                "Business casual with professional accessories",
                "Professional work attire"
            ]
        ]
        
        print("💡 Professional Outfit Suggestions:")
        for (category, suggestionList) in outfitSuggestions {
            print("\(category): \(suggestionList)")
        }
        return outfitSuggestions
    }
    
    /**
     * Get professional background suggestions
     * @return Dictionary containing background suggestions
     */
    func getProfessionalBackgroundSuggestions() -> [String: [String]] {
        let backgroundSuggestions = [
            "office_settings": [
                "Modern office background",
                "Corporate office environment",
                "Professional office setting",
                "Business office backdrop",
                "Executive office background"
            ],
            "studio_settings": [
                "Professional studio background",
                "Clean studio backdrop",
                "Professional photography studio",
                "Studio lighting setup",
                "Professional portrait studio"
            ],
            "neutral_backgrounds": [
                "Neutral professional background",
                "Clean white background",
                "Professional gray backdrop",
                "Subtle professional background",
                "Minimalist professional setting"
            ],
            "corporate_backgrounds": [
                "Corporate building background",
                "Business environment backdrop",
                "Professional corporate setting",
                "Executive office background",
                "Corporate headquarters setting"
            ],
            "modern_backgrounds": [
                "Modern professional background",
                "Contemporary office setting",
                "Sleek professional backdrop",
                "Modern business environment",
                "Contemporary corporate setting"
            ]
        ]
        
        print("💡 Professional Background Suggestions:")
        for (category, suggestionList) in backgroundSuggestions {
            print("\(category): \(suggestionList)")
        }
        return backgroundSuggestions
    }
    
    /**
     * Get headshot prompt examples
     * @return Dictionary containing prompt examples
     */
    func getHeadshotPromptExamples() -> [String: [String]] {
        let promptExamples = [
            "business_formal": [
                "Create professional headshot with dark business suit and white dress shirt",
                "Generate corporate headshot with formal business attire",
                "Professional headshot with business suit and professional background",
                "Executive headshot with formal business wear and office setting",
                "Corporate headshot with professional suit and business environment"
            ],
            "business_casual": [
                "Create professional headshot with blazer and dress shirt",
                "Generate business casual headshot with professional attire",
                "Professional headshot with smart casual outfit and office background",
                "Business headshot with professional blouse and corporate setting",
                "Professional headshot with business casual wear and modern office"
            ],
            "corporate": [
                "Create corporate headshot with professional dress and blazer",
                "Generate executive headshot with corporate attire",
                "Professional headshot with business dress and office environment",
                "Corporate headshot with professional blouse and corporate background",
                "Executive headshot with corporate wear and business setting"
            ],
            "executive": [
                "Create executive headshot with power suit and professional accessories",
                "Generate leadership headshot with executive attire",
                "Professional headshot with executive suit and corporate office",
                "Executive headshot with professional dress and executive background",
                "Leadership headshot with executive wear and business environment"
            ],
            "professional": [
                "Create professional headshot with business attire and clean background",
                "Generate professional headshot with corporate wear and office setting",
                "Professional headshot with business casual outfit and professional backdrop",
                "Business headshot with professional attire and modern office background",
                "Professional headshot with corporate wear and business environment"
            ]
        ]
        
        print("💡 Headshot Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        return promptExamples
    }
    
    /**
     * Get headshot use cases and examples
     * @return Dictionary containing use case examples
     */
    func getHeadshotUseCases() -> [String: [String]] {
        let useCases = [
            "business_profiles": [
                "LinkedIn professional headshots",
                "Business profile photos",
                "Corporate directory photos",
                "Professional networking photos",
                "Business card headshots"
            ],
            "resumes": [
                "Resume profile photos",
                "CV headshot photos",
                "Job application photos",
                "Professional resume images",
                "Career profile photos"
            ],
            "corporate": [
                "Corporate website photos",
                "Company directory headshots",
                "Executive team photos",
                "Corporate communications",
                "Business presentation photos"
            ],
            "professional_networking": [
                "Professional networking profiles",
                "Business conference photos",
                "Professional association photos",
                "Industry networking photos",
                "Professional community photos"
            ],
            "marketing": [
                "Professional marketing materials",
                "Business promotional photos",
                "Corporate marketing campaigns",
                "Professional advertising photos",
                "Business marketing content"
            ]
        ]
        
        print("💡 Headshot Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
    }
    
    /**
     * Get professional style suggestions
     * @return Dictionary containing style suggestions
     */
    func getProfessionalStyleSuggestions() -> [String: [String]] {
        let styleSuggestions = [
            "conservative": [
                "Traditional business attire",
                "Classic professional wear",
                "Conservative corporate dress",
                "Traditional business suit",
                "Classic professional appearance"
            ],
            "modern": [
                "Contemporary business attire",
                "Modern professional wear",
                "Current business fashion",
                "Modern corporate dress",
                "Contemporary professional style"
            ],
            "executive": [
                "Executive business attire",
                "Leadership professional wear",
                "Senior management dress",
                "Executive corporate attire",
                "Leadership professional style"
            ],
            "creative_professional": [
                "Creative professional attire",
                "Modern creative business wear",
                "Contemporary professional dress",
                "Creative corporate attire",
                "Modern professional creative style"
            ],
            "tech_professional": [
                "Tech industry professional attire",
                "Modern tech business wear",
                "Contemporary tech professional dress",
                "Tech corporate attire",
                "Modern tech professional style"
            ]
        ]
        
        print("💡 Professional Style Suggestions:")
        for (style, suggestionList) in styleSuggestions {
            print("\(style): \(suggestionList)")
        }
        return styleSuggestions
    }
    
    /**
     * Get headshot best practices
     * @return Dictionary containing best practices
     */
    func getHeadshotBestPractices() -> [String: [String]] {
        let bestPractices = [
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in the original image",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined facial features",
                "Ensure the person's face is clearly visible and well-lit"
            ],
            "prompt_writing": [
                "Be specific about the professional look you want to achieve",
                "Include details about outfit style, background, and setting",
                "Mention specific professional attire or business wear",
                "Describe the desired professional appearance clearly",
                "Keep prompts descriptive but concise"
            ],
            "professional_setting": [
                "Specify professional background settings",
                "Mention corporate or business environments",
                "Include details about lighting and atmosphere",
                "Describe professional color schemes",
                "Specify formal or business-casual settings"
            ],
            "workflow_optimization": [
                "Batch process multiple headshot variations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        ]
        
        print("💡 Headshot Best Practices:")
        for (category, practiceList) in bestPractices {
            print("\(category): \(practiceList)")
        }
        return bestPractices
    }
    
    /**
     * Get headshot performance tips
     * @return Dictionary containing performance tips
     */
    func getHeadshotPerformanceTips() -> [String: [String]] {
        let performanceTips = [
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple headshot variations"
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
                "Offer headshot previews when possible",
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
     * Get headshot technical specifications
     * @return Dictionary containing technical specifications
     */
    func getHeadshotTechnicalSpecifications() -> [String: [String: Any]] {
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
                "text_prompts": "Required for professional outfit description",
                "face_detection": "Automatic face detection and enhancement",
                "professional_transformation": "Transforms casual photos into professional headshots",
                "background_enhancement": "Enhances or changes background to professional setting",
                "output_quality": "High-quality JPEG output"
            ]
        ]
        
        print("💡 Headshot Technical Specifications:")
        for (category, specs) in specifications {
            print("\(category): \(specs)")
        }
        return specifications
    }
    
    /**
     * Get headshot workflow examples
     * @return Dictionary containing workflow examples
     */
    func getHeadshotWorkflowExamples() -> [String: [String]] {
        let workflowExamples = [
            "basic_workflow": [
                "1. Prepare high-quality input image with clear face visibility",
                "2. Write descriptive professional outfit prompt",
                "3. Upload image to LightX servers",
                "4. Submit headshot generation request",
                "5. Monitor order status until completion",
                "6. Download professional headshot result"
            ],
            "advanced_workflow": [
                "1. Prepare input image with clear facial features",
                "2. Create detailed professional prompt with specific attire and background",
                "3. Upload image to LightX servers",
                "4. Submit headshot request with validation",
                "5. Monitor processing with retry logic",
                "6. Validate and download result",
                "7. Apply additional processing if needed"
            ],
            "batch_workflow": [
                "1. Prepare multiple input images",
                "2. Create consistent professional prompts for batch",
                "3. Upload all images in parallel",
                "4. Submit multiple headshot requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            ]
        ]
        
        print("💡 Headshot Workflow Examples:")
        for (workflow, stepList) in workflowExamples {
            print("\(workflow):")
            for step in stepList {
                print("  \(step)")
            }
        }
        return workflowExamples
    }
    
    /**
     * Validate text prompt
     * @param textPrompt Text prompt to validate
     * @return Whether the prompt is valid
     */
    func validateTextPrompt(_ textPrompt: String) -> Bool {
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
     * Generate headshot with prompt validation
     * @param imageData Image data
     * @param textPrompt Text prompt for professional outfit description
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func generateHeadshotWithValidation(imageData: Data, textPrompt: String, contentType: String = "image/jpeg") async throws -> HeadshotOrderStatusBody {
        if !validateTextPrompt(textPrompt) {
            throw LightXHeadshotError.invalidPrompt
        }
        
        return try await processHeadshotGeneration(imageData: imageData, textPrompt: textPrompt, contentType: contentType)
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let requestURL = URL(string: url) else {
            throw LightXHeadshotError.invalidURL
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> HeadshotUploadImageBody {
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
            throw LightXHeadshotError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(HeadshotUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXHeadshotError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXHeadshotError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHeadshotError.uploadFailed
        }
    }
}

// MARK: - Error Types

enum LightXHeadshotError: Error, LocalizedError {
    case invalidURL
    case networkError
    case apiError(String)
    case imageSizeExceeded
    case processingFailed
    case maxRetriesReached
    case uploadFailed
    case invalidPrompt
    
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
            return "Professional headshot generation failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .uploadFailed:
            return "Image upload failed"
        case .invalidPrompt:
            return "Invalid text prompt provided"
        }
    }
}

// MARK: - Example Usage

struct LightXHeadshotExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXAIHeadshotAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getHeadshotTips()
            lightx.getProfessionalOutfitSuggestions()
            lightx.getProfessionalBackgroundSuggestions()
            lightx.getHeadshotPromptExamples()
            lightx.getHeadshotUseCases()
            lightx.getProfessionalStyleSuggestions()
            lightx.getHeadshotBestPractices()
            lightx.getHeadshotPerformanceTips()
            lightx.getHeadshotTechnicalSpecifications()
            lightx.getHeadshotWorkflowExamples()
            
            // Load image (replace with your image loading logic)
            guard let imageData = loadImageData() else {
                print("❌ Failed to load image data")
                return
            }
            
            // Example 1: Business formal headshot
            let result1 = try await lightx.generateHeadshotWithValidation(
                imageData: imageData,
                textPrompt: "Create professional headshot with dark business suit and white dress shirt",
                contentType: "image/jpeg"
            )
            print("🎉 Business formal headshot result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Business casual headshot
            let result2 = try await lightx.generateHeadshotWithValidation(
                imageData: imageData,
                textPrompt: "Generate business casual headshot with professional attire",
                contentType: "image/jpeg"
            )
            print("🎉 Business casual headshot result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Try different professional styles
            let professionalStyles = [
                "Create corporate headshot with professional dress and blazer",
                "Generate executive headshot with corporate attire",
                "Professional headshot with business dress and office environment",
                "Create executive headshot with power suit and professional accessories",
                "Generate leadership headshot with executive attire"
            ]
            
            for style in professionalStyles {
                let result = try await lightx.generateHeadshotWithValidation(
                    imageData: imageData,
                    textPrompt: style,
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
}

// MARK: - SwiftUI Integration Example

import SwiftUI

struct HeadshotView: View {
    @StateObject private var headshotAPI = LightXAIHeadshotAPI(apiKey: "YOUR_API_KEY_HERE")
    @State private var selectedImage: UIImage?
    @State private var textPrompt = "Create professional headshot with business attire"
    @State private var isProcessing = false
    @State private var result: HeadshotOrderStatusBody?
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 20) {
            Text("LightX AI Headshot Generator")
                .font(.largeTitle)
                .fontWeight(.bold)
            
            if let image = selectedImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxHeight: 200)
                    .cornerRadius(10)
            }
            
            VStack(alignment: .leading, spacing: 10) {
                Text("Professional Prompt")
                    .font(.headline)
                
                TextField("Enter professional outfit description", text: $textPrompt, axis: .vertical)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .lineLimit(3...6)
            }
            
            Button("Select Image") {
                // Image picker logic here
            }
            .buttonStyle(.bordered)
            
            Button("Generate Headshot") {
                Task {
                    await generateHeadshot()
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedImage == nil || isProcessing)
            
            if isProcessing {
                ProgressView("Processing...")
            }
            
            if let result = result {
                VStack {
                    Text("✅ Headshot Generated!")
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
    
    private func generateHeadshot() async {
        guard let image = selectedImage else { return }
        
        isProcessing = true
        errorMessage = nil
        result = nil
        
        do {
            let imageData = image.jpegData(compressionQuality: 0.8) ?? Data()
            
            let headshotResult = try await headshotAPI.generateHeadshotWithValidation(
                imageData: imageData,
                textPrompt: textPrompt,
                contentType: "image/jpeg"
            )
            result = headshotResult
        } catch {
            errorMessage = error.localizedDescription
        }
        
        isProcessing = false
    }
}
