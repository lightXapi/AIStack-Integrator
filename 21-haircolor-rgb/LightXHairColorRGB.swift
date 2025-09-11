/**
 * LightX Hair Color RGB API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hair color changing using hex color codes.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct HairColorRGBUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairColorRGBUploadImageBody
}

struct HairColorRGBUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct HairColorRGBGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairColorRGBGenerationBody
}

struct HairColorRGBGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct HairColorRGBOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: HairColorRGBOrderStatusBody
}

struct HairColorRGBOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Hair Color RGB API Client

@MainActor
class LightXHairColorRGBAPI: ObservableObject {
    
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
            throw LightXHairColorRGBError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Change hair color using hex color code
     * @param imageUrl URL of the input image
     * @param hairHexColor Hex color code (e.g., "#FF0000")
     * @param colorStrength Color strength between 0.1 to 1
     * @return Order ID for tracking
     */
    func changeHairColor(imageUrl: String, hairHexColor: String, colorStrength: Double = 0.5) async throws -> String {
        // Validate hex color
        if !isValidHexColor(hairHexColor) {
            throw LightXHairColorRGBError.invalidHexColor
        }
        
        // Validate color strength
        if colorStrength < 0.1 || colorStrength > 1.0 {
            throw LightXHairColorRGBError.invalidColorStrength
        }
        
        let endpoint = "\(baseURL)/v2/haircolor-rgb"
        
        let requestBody = [
            "imageUrl": imageUrl,
            "hairHexColor": hairHexColor,
            "colorStrength": colorStrength
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairColorRGBError.networkError
        }
        
        let hairColorResponse = try JSONDecoder().decode(HairColorRGBGenerationResponse.self, from: data)
        
        if hairColorResponse.statusCode != 2000 {
            throw LightXHairColorRGBError.apiError(hairColorResponse.message)
        }
        
        let orderInfo = hairColorResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🎨 Hair color: \(hairHexColor)")
        print("💪 Color strength: \(colorStrength)")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> HairColorRGBOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairColorRGBError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(HairColorRGBOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXHairColorRGBError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> HairColorRGBOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Hair color change completed successfully!")
                    if let output = status.output {
                        print("🎨 Hair color result: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXHairColorRGBError.processingFailed
                    
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
        
        throw LightXHairColorRGBError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and change hair color
     * @param imageData Image data
     * @param hairHexColor Hex color code
     * @param colorStrength Color strength between 0.1 to 1
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processHairColorChange(imageData: Data, hairHexColor: String, colorStrength: Double = 0.5, contentType: String = "image/jpeg") async throws -> HairColorRGBOrderStatusBody {
        print("🚀 Starting LightX Hair Color RGB API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageUrl = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageUrl)")
        
        // Step 2: Change hair color
        print("🎨 Changing hair color...")
        let orderId = try await changeHairColor(imageUrl: imageUrl, hairHexColor: hairHexColor, colorStrength: colorStrength)
        
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
                "Use clear, well-lit photos with good hair visibility",
                "Ensure the person's hair is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best hair color results",
                "Good lighting helps preserve hair texture and details"
            ],
            "hex_colors": [
                "Use valid hex color codes in format #RRGGBB",
                "Common hair colors: #000000 (black), #8B4513 (brown), #FFD700 (blonde)",
                "Experiment with different shades for natural-looking results",
                "Consider skin tone compatibility when choosing colors",
                "Use color strength to control intensity of the color change"
            ],
            "color_strength": [
                "Lower values (0.1-0.3) create subtle color changes",
                "Medium values (0.4-0.7) provide balanced color intensity",
                "Higher values (0.8-1.0) create bold, vibrant color changes",
                "Start with medium strength and adjust based on results",
                "Consider the original hair color when setting strength"
            ],
            "hair_visibility": [
                "Ensure the person's hair is clearly visible",
                "Avoid images where hair is heavily covered or obscured",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "general": [
                "AI hair color change works best with clear, detailed source images",
                "Results may vary based on input image quality and hair visibility",
                "Hair color change preserves hair texture while changing color",
                "Allow 15-30 seconds for processing",
                "Experiment with different colors and strengths for varied results"
            ]
        ]
        
        print("💡 Hair Color Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get popular hair color hex codes
     * @return Dictionary containing popular hair color hex codes
     */
    func getPopularHairColors() -> [String: [[String: Any]]] {
        let hairColors = [
            "natural_blondes": [
                ["name": "Platinum Blonde", "hex": "#F5F5DC", "strength": 0.7],
                ["name": "Golden Blonde", "hex": "#FFD700", "strength": 0.6],
                ["name": "Honey Blonde", "hex": "#DAA520", "strength": 0.5],
                ["name": "Strawberry Blonde", "hex": "#D2691E", "strength": 0.6],
                ["name": "Ash Blonde", "hex": "#C0C0C0", "strength": 0.5]
            ],
            "natural_browns": [
                ["name": "Light Brown", "hex": "#8B4513", "strength": 0.5],
                ["name": "Medium Brown", "hex": "#654321", "strength": 0.6],
                ["name": "Dark Brown", "hex": "#3C2414", "strength": 0.7],
                ["name": "Chestnut Brown", "hex": "#954535", "strength": 0.5],
                ["name": "Auburn Brown", "hex": "#A52A2A", "strength": 0.6]
            ],
            "natural_blacks": [
                ["name": "Jet Black", "hex": "#000000", "strength": 0.8],
                ["name": "Soft Black", "hex": "#1C1C1C", "strength": 0.7],
                ["name": "Blue Black", "hex": "#0A0A0A", "strength": 0.8],
                ["name": "Brown Black", "hex": "#2F1B14", "strength": 0.6]
            ],
            "fashion_colors": [
                ["name": "Vibrant Red", "hex": "#FF0000", "strength": 0.8],
                ["name": "Purple", "hex": "#800080", "strength": 0.7],
                ["name": "Blue", "hex": "#0000FF", "strength": 0.7],
                ["name": "Pink", "hex": "#FF69B4", "strength": 0.6],
                ["name": "Green", "hex": "#008000", "strength": 0.6],
                ["name": "Orange", "hex": "#FFA500", "strength": 0.7]
            ],
            "highlights": [
                ["name": "Blonde Highlights", "hex": "#FFD700", "strength": 0.4],
                ["name": "Red Highlights", "hex": "#FF4500", "strength": 0.3],
                ["name": "Purple Highlights", "hex": "#9370DB", "strength": 0.3],
                ["name": "Blue Highlights", "hex": "#4169E1", "strength": 0.3]
            ]
        ]
        
        print("💡 Popular Hair Colors:")
        for (category, colorList) in hairColors {
            print("\(category):")
            for color in colorList {
                if let name = color["name"] as? String,
                   let hex = color["hex"] as? String,
                   let strength = color["strength"] as? Double {
                    print("  \(name): \(hex) (strength: \(strength))")
                }
            }
        }
        return hairColors
    }
    
    /**
     * Get hair color use cases and examples
     * @return Dictionary containing use case examples
     */
    func getHairColorUseCases() -> [String: [String]] {
        let useCases = [
            "virtual_makeovers": [
                "Virtual hair color try-on",
                "Makeover simulation apps",
                "Beauty consultation tools",
                "Personal styling experiments",
                "Virtual hair color previews"
            ],
            "beauty_platforms": [
                "Beauty app hair color features",
                "Salon consultation tools",
                "Hair color recommendation systems",
                "Beauty influencer content",
                "Hair color trend visualization"
            ],
            "personal_styling": [
                "Personal style exploration",
                "Hair color decision making",
                "Style consultation services",
                "Personal fashion experiments",
                "Individual styling assistance"
            ],
            "social_media": [
                "Social media hair color posts",
                "Beauty influencer content",
                "Hair color sharing platforms",
                "Beauty community features",
                "Social beauty experiences"
            ],
            "entertainment": [
                "Character hair color changes",
                "Costume design and visualization",
                "Creative hair color concepts",
                "Artistic hair color expressions",
                "Entertainment industry applications"
            ]
        ]
        
        print("💡 Hair Color Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
    }
    
    /**
     * Get hair color best practices
     * @return Dictionary containing best practices
     */
    func getHairColorBestPractices() -> [String: [String]] {
        let bestPractices = [
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in the original image",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined hair details",
                "Ensure the person's hair is clearly visible and well-lit"
            ],
            "color_selection": [
                "Choose hex colors that complement skin tone",
                "Consider the original hair color when selecting new colors",
                "Use color strength to control the intensity of change",
                "Experiment with different shades for natural results",
                "Test multiple color options to find the best match"
            ],
            "strength_control": [
                "Start with medium strength (0.5) and adjust as needed",
                "Use lower strength for subtle, natural-looking changes",
                "Use higher strength for bold, dramatic color changes",
                "Consider the contrast with the original hair color",
                "Balance color intensity with natural appearance"
            ],
            "workflow_optimization": [
                "Batch process multiple color variations when possible",
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
                "Use batch processing for multiple color variations"
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
                "Offer color previews when possible",
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
                "hex_color_codes": "Required for precise color specification",
                "color_strength": "Controls intensity of color change (0.1 to 1.0)",
                "hair_detection": "Automatic hair detection and segmentation",
                "texture_preservation": "Preserves original hair texture and style",
                "output_quality": "High-quality JPEG output"
            ]
        ]
        
        print("💡 Hair Color Technical Specifications:")
        for (category, specs) in specifications {
            print("\(category): \(specs)")
        }
        return specifications
    }
    
    /**
     * Get hair color workflow examples
     * @return Dictionary containing workflow examples
     */
    func getHairColorWorkflowExamples() -> [String: [String]] {
        let workflowExamples = [
            "basic_workflow": [
                "1. Prepare high-quality input image with clear hair visibility",
                "2. Choose desired hair color hex code",
                "3. Set appropriate color strength (0.1 to 1.0)",
                "4. Upload image to LightX servers",
                "5. Submit hair color change request",
                "6. Monitor order status until completion",
                "7. Download hair color result"
            ],
            "advanced_workflow": [
                "1. Prepare input image with clear hair definition",
                "2. Select multiple color options for comparison",
                "3. Set different strength values for each color",
                "4. Upload image to LightX servers",
                "5. Submit multiple hair color requests",
                "6. Monitor all orders with retry logic",
                "7. Compare and select best results",
                "8. Apply additional processing if needed"
            ],
            "batch_workflow": [
                "1. Prepare multiple input images",
                "2. Create color palette for batch processing",
                "3. Upload all images in parallel",
                "4. Submit multiple hair color requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            ]
        ]
        
        print("💡 Hair Color Workflow Examples:")
        for (workflow, stepList) in workflowExamples {
            print("\(workflow):")
            for step in stepList {
                print("  \(step)")
            }
        }
        return workflowExamples
    }
    
    /**
     * Validate hex color format
     * @param hexColor Hex color to validate
     * @return Whether the hex color is valid
     */
    func isValidHexColor(_ hexColor: String) -> Bool {
        let hexPattern = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$"
        let regex = try? NSRegularExpression(pattern: hexPattern)
        let range = NSRange(location: 0, length: hexColor.utf16.count)
        return regex?.firstMatch(in: hexColor, options: [], range: range) != nil
    }
    
    /**
     * Convert RGB to hex color
     * @param r Red value (0-255)
     * @param g Green value (0-255)
     * @param b Blue value (0-255)
     * @return Hex color code
     */
    func rgbToHex(r: Int, g: Int, b: Int) -> String {
        let toHex = { (n: Int) -> String in
            let hex = String(max(0, min(255, n)), radix: 16)
            return hex.count == 1 ? "0" + hex : hex
        }
        return "#\(toHex(r))\(toHex(g))\(toHex(b))".uppercased()
    }
    
    /**
     * Convert hex color to RGB
     * @param hex Hex color code
     * @return RGB values or nil if invalid
     */
    func hexToRgb(_ hex: String) -> (r: Int, g: Int, b: Int)? {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        default:
            return nil
        }
        return (r: Int(r), g: Int(g), b: Int(b))
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
     * Change hair color with validation
     * @param imageData Image data
     * @param hairHexColor Hex color code
     * @param colorStrength Color strength between 0.1 to 1
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func changeHairColorWithValidation(imageData: Data, hairHexColor: String, colorStrength: Double = 0.5, contentType: String = "image/jpeg") async throws -> HairColorRGBOrderStatusBody {
        if !isValidHexColor(hairHexColor) {
            throw LightXHairColorRGBError.invalidHexColor
        }
        
        if colorStrength < 0.1 || colorStrength > 1.0 {
            throw LightXHairColorRGBError.invalidColorStrength
        }
        
        return try await processHairColorChange(imageData: imageData, hairHexColor: hairHexColor, colorStrength: colorStrength, contentType: contentType)
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let requestURL = URL(string: url) else {
            throw LightXHairColorRGBError.invalidURL
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> HairColorRGBUploadImageBody {
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
            throw LightXHairColorRGBError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(HairColorRGBUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXHairColorRGBError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXHairColorRGBError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXHairColorRGBError.uploadFailed
        }
    }
}

// MARK: - Error Types

enum LightXHairColorRGBError: Error, LocalizedError {
    case invalidURL
    case networkError
    case apiError(String)
    case imageSizeExceeded
    case processingFailed
    case maxRetriesReached
    case uploadFailed
    case invalidHexColor
    case invalidColorStrength
    
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
        case .invalidHexColor:
            return "Invalid hex color format. Use format like #FF0000"
        case .invalidColorStrength:
            return "Color strength must be between 0.1 and 1.0"
        }
    }
}

// MARK: - Example Usage

struct LightXHairColorRGBExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXHairColorRGBAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getHairColorTips()
            lightx.getPopularHairColors()
            lightx.getHairColorUseCases()
            lightx.getHairColorBestPractices()
            lightx.getHairColorPerformanceTips()
            lightx.getHairColorTechnicalSpecifications()
            lightx.getHairColorWorkflowExamples()
            
            // Load image (replace with your image loading logic)
            guard let imageData = loadImageData() else {
                print("❌ Failed to load image data")
                return
            }
            
            // Example 1: Natural blonde hair
            let result1 = try await lightx.changeHairColorWithValidation(
                imageData: imageData,
                hairHexColor: "#FFD700", // Golden blonde
                colorStrength: 0.6, // Medium strength
                contentType: "image/jpeg"
            )
            print("🎉 Golden blonde result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Fashion color - vibrant red
            let result2 = try await lightx.changeHairColorWithValidation(
                imageData: imageData,
                hairHexColor: "#FF0000", // Vibrant red
                colorStrength: 0.8, // High strength
                contentType: "image/jpeg"
            )
            print("🎉 Vibrant red result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Try different hair colors
            let hairColors = [
                (color: "#000000", name: "Jet Black", strength: 0.8),
                (color: "#8B4513", name: "Light Brown", strength: 0.5),
                (color: "#800080", name: "Purple", strength: 0.7),
                (color: "#FF69B4", name: "Pink", strength: 0.6),
                (color: "#0000FF", name: "Blue", strength: 0.7)
            ]
            
            for hairColor in hairColors {
                let result = try await lightx.changeHairColorWithValidation(
                    imageData: imageData,
                    hairHexColor: hairColor.color,
                    colorStrength: hairColor.strength,
                    contentType: "image/jpeg"
                )
                print("🎉 \(hairColor.name) result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 4: Color conversion utilities
            if let rgb = lightx.hexToRgb("#FFD700") {
                print("RGB values for #FFD700: r:\(rgb.r), g:\(rgb.g), b:\(rgb.b)")
            }
            
            let hex = lightx.rgbToHex(r: 255, g: 215, b: 0)
            print("Hex for RGB(255, 215, 0): \(hex)")
            
            // Example 5: Get image dimensions
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

struct HairColorRGBView: View {
    @StateObject private var hairColorAPI = LightXHairColorRGBAPI(apiKey: "YOUR_API_KEY_HERE")
    @State private var selectedImage: UIImage?
    @State private var selectedHexColor = "#FFD700"
    @State private var colorStrength: Double = 0.5
    @State private var isProcessing = false
    @State private var result: HairColorRGBOrderStatusBody?
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 20) {
            Text("LightX Hair Color RGB")
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
                Text("Hair Color")
                    .font(.headline)
                
                HStack {
                    TextField("Hex Color", text: $selectedHexColor)
                        .textFieldStyle(RoundedBorderTextFieldStyle())
                        .autocapitalization(.allCharacters)
                    
                    Color(hex: selectedHexColor)
                        .frame(width: 40, height: 40)
                        .cornerRadius(8)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(Color.gray, lineWidth: 1)
                        )
                }
                
                Text("Color Strength: \(colorStrength, specifier: "%.1f")")
                    .font(.headline)
                
                Slider(value: $colorStrength, in: 0.1...1.0, step: 0.1)
                    .accentColor(.blue)
            }
            
            Button("Select Image") {
                // Image picker logic here
            }
            .buttonStyle(.bordered)
            
            Button("Change Hair Color") {
                Task {
                    await changeHairColor()
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedImage == nil || isProcessing)
            
            if isProcessing {
                ProgressView("Processing...")
            }
            
            if let result = result {
                VStack {
                    Text("✅ Hair Color Change Complete!")
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
            
            let hairColorResult = try await hairColorAPI.changeHairColorWithValidation(
                imageData: imageData,
                hairHexColor: selectedHexColor,
                colorStrength: colorStrength,
                contentType: "image/jpeg"
            )
            result = hairColorResult
        } catch {
            errorMessage = error.localizedDescription
        }
        
        isProcessing = false
    }
}

// MARK: - Color Extension for Hex Support

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (1, 1, 1, 0)
        }
        
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue:  Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}
