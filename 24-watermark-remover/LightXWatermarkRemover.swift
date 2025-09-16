/**
 * LightX Watermark Remover API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered watermark removal functionality.
 */

import Foundation

// MARK: - Data Models

struct WatermarkRemoverUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: WatermarkRemoverUploadImageBody
}

struct WatermarkRemoverUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct WatermarkRemoverGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: WatermarkRemoverGenerationBody
}

struct WatermarkRemoverGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct WatermarkRemoverOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: WatermarkRemoverOrderStatusBody
}

struct WatermarkRemoverOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Watermark Remover API Client

@MainActor
class LightXWatermarkRemoverAPI: ObservableObject {
    
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
            throw LightXWatermarkRemoverError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Remove watermark from image
     * @param imageUrl URL of the input image
     * @return Order ID for tracking
     */
    func removeWatermark(imageUrl: String) async throws -> String {
        let endpoint = "\(baseURL)/v2/watermark-remover/"
        
        let requestBody = [
            "imageUrl": imageUrl
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXWatermarkRemoverError.networkError
        }
        
        let watermarkRemoverResponse = try JSONDecoder().decode(WatermarkRemoverGenerationResponse.self, from: data)
        
        if watermarkRemoverResponse.statusCode != 2000 {
            throw LightXWatermarkRemoverError.apiError(watermarkRemoverResponse.message)
        }
        
        let orderInfo = watermarkRemoverResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🖼️  Input image: \(imageUrl)")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> WatermarkRemoverOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXWatermarkRemoverError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(WatermarkRemoverOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXWatermarkRemoverError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> WatermarkRemoverOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Watermark removed successfully!")
                    if let output = status.output {
                        print("🖼️  Clean image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXWatermarkRemoverError.generationFailed
                    
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
        
        throw LightXWatermarkRemoverError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and remove watermark
     * @param imageData Image data
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processWatermarkRemoval(imageData: Data, contentType: String = "image/jpeg") async throws -> WatermarkRemoverOrderStatusBody {
        print("🚀 Starting LightX Watermark Remover API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageUrl = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageUrl)")
        
        // Step 2: Remove watermark
        print("🧹 Removing watermark...")
        let orderId = try await removeWatermark(imageUrl: imageUrl)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get watermark removal tips and best practices
     * @return Object containing tips for better results
     */
    func getWatermarkRemovalTips() -> [String: [String]] {
        let tips = [
            "input_image": [
                "Use high-quality images with clear watermarks",
                "Ensure the image is at least 512x512 pixels",
                "Avoid heavily compressed or low-quality source images",
                "Use images with good contrast and lighting",
                "Ensure watermarks are clearly visible in the image"
            ],
            "watermark_types": [
                "Text watermarks: Works best with clear, readable text",
                "Logo watermarks: Effective with distinct logo shapes",
                "Pattern watermarks: Good for repetitive patterns",
                "Transparent watermarks: Handles semi-transparent overlays",
                "Complex watermarks: May require multiple processing attempts"
            ],
            "image_quality": [
                "Higher resolution images produce better results",
                "Good lighting and contrast improve watermark detection",
                "Avoid images with excessive noise or artifacts",
                "Clear, sharp images work better than blurry ones",
                "Well-exposed images provide better results"
            ],
            "general": [
                "AI watermark removal works best with clearly visible watermarks",
                "Results may vary based on watermark complexity and image quality",
                "Allow 15-30 seconds for processing",
                "Some watermarks may require multiple processing attempts",
                "The tool preserves image quality while removing watermarks"
            ]
        ]
        
        print("💡 Watermark Removal Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        
        return tips
    }
    
    /**
     * Get watermark removal use cases and examples
     * @return Object containing use case examples
     */
    func getWatermarkRemovalUseCases() -> [String: [String]] {
        let useCases = [
            "e_commerce": [
                "Remove watermarks from product photos",
                "Clean up stock images for online stores",
                "Prepare images for product catalogs",
                "Remove branding from supplier images",
                "Create clean product listings"
            ],
            "photo_editing": [
                "Remove watermarks from edited photos",
                "Clean up images for personal use",
                "Remove copyright watermarks",
                "Prepare images for printing",
                "Clean up stock photo watermarks"
            ],
            "news_publishing": [
                "Remove watermarks from news images",
                "Clean up press photos",
                "Remove agency watermarks",
                "Prepare images for articles",
                "Clean up editorial images"
            ],
            "social_media": [
                "Remove watermarks from social media images",
                "Clean up images for posts",
                "Remove branding from shared images",
                "Prepare images for profiles",
                "Clean up user-generated content"
            ],
            "creative_projects": [
                "Remove watermarks from design assets",
                "Clean up images for presentations",
                "Remove branding from templates",
                "Prepare images for portfolios",
                "Clean up creative resources"
            ]
        ]
        
        print("💡 Watermark Removal Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        
        return useCases
    }
    
    /**
     * Get supported image formats and requirements
     * @return Object containing format information
     */
    func getSupportedFormats() -> [String: [String: String]] {
        let formats = [
            "input_formats": [
                "JPEG": "Most common format, good for photos",
                "PNG": "Supports transparency, good for graphics",
                "WebP": "Modern format with good compression"
            ],
            "output_format": [
                "JPEG": "Standard output format for compatibility"
            ],
            "requirements": [
                "minimum_size": "512x512 pixels",
                "maximum_size": "5MB file size",
                "color_space": "RGB or sRGB",
                "compression": "Any standard compression level"
            ],
            "recommendations": [
                "resolution": "Higher resolution images produce better results",
                "quality": "Use high-quality source images",
                "format": "JPEG is recommended for photos",
                "size": "Larger images allow better watermark detection"
            ]
        ]
        
        print("📋 Supported Formats and Requirements:")
        for (category, info) in formats {
            print("\(category): \(info)")
        }
        
        return formats
    }
    
    /**
     * Get watermark detection capabilities
     * @return Object containing detection information
     */
    func getWatermarkDetectionCapabilities() -> [String: [String]] {
        let capabilities = [
            "detection_types": [
                "Text watermarks with various fonts and styles",
                "Logo watermarks with different shapes and colors",
                "Pattern watermarks with repetitive designs",
                "Transparent watermarks with varying opacity",
                "Complex watermarks with multiple elements"
            ],
            "coverage_areas": [
                "Full image watermarks covering the entire image",
                "Corner watermarks in specific image areas",
                "Center watermarks in the middle of images",
                "Scattered watermarks across multiple areas",
                "Border watermarks along image edges"
            ],
            "processing_features": [
                "Automatic watermark detection and removal",
                "Preserves original image quality and details",
                "Maintains image composition and structure",
                "Handles various watermark sizes and positions",
                "Works with different image backgrounds"
            ],
            "limitations": [
                "Very small or subtle watermarks may be challenging",
                "Watermarks that blend with image content",
                "Extremely complex or artistic watermarks",
                "Watermarks that are part of the main subject",
                "Very low resolution or poor quality images"
            ]
        ]
        
        print("🔍 Watermark Detection Capabilities:")
        for (category, capabilityList) in capabilities {
            print("\(category): \(capabilityList)")
        }
        
        return capabilities
    }
    
    /**
     * Validate image data (utility function)
     * @param imageData Image data to validate
     * @return Whether the image data is valid
     */
    func validateImageData(_ imageData: Data) -> Bool {
        if imageData.isEmpty {
            print("❌ Image data is empty")
            return false
        }
        
        if imageData.count > maxFileSize {
            print("❌ Image size exceeds 5MB limit")
            return false
        }
        
        print("✅ Image data is valid")
        return true
    }
    
    /**
     * Process watermark removal with validation
     * @param imageData Image data
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processWatermarkRemovalWithValidation(imageData: Data, contentType: String = "image/jpeg") async throws -> WatermarkRemoverOrderStatusBody {
        if !validateImageData(imageData) {
            throw LightXWatermarkRemoverError.invalidImageData
        }
        
        return try await processWatermarkRemoval(imageData: imageData, contentType: contentType)
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let url = URL(string: url) else {
            throw LightXWatermarkRemoverError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        if !body.isEmpty {
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        }
        
        return request
    }
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> WatermarkRemoverUploadImageBody {
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
            throw LightXWatermarkRemoverError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(WatermarkRemoverUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXWatermarkRemoverError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXWatermarkRemoverError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXWatermarkRemoverError.uploadFailed
        }
    }
}

// MARK: - Error Types

enum LightXWatermarkRemoverError: Error, LocalizedError {
    case networkError
    case apiError(String)
    case generationFailed
    case maxRetriesReached
    case invalidURL
    case invalidImageData
    case imageSizeExceeded
    case uploadFailed
    
    var errorDescription: String? {
        switch self {
        case .networkError:
            return "Network error occurred"
        case .apiError(let message):
            return "API error: \(message)"
        case .generationFailed:
            return "Watermark removal failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidURL:
            return "Invalid URL"
        case .invalidImageData:
            return "Invalid image data"
        case .imageSizeExceeded:
            return "Image size exceeds 5MB limit"
        case .uploadFailed:
            return "Image upload failed"
        }
    }
}

// MARK: - Example Usage

@main
struct WatermarkRemoverExample {
    static func main() async {
        do {
            // Initialize with your API key
            let lightx = LightXWatermarkRemoverAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips and information
            lightx.getWatermarkRemovalTips()
            lightx.getWatermarkRemovalUseCases()
            lightx.getSupportedFormats()
            lightx.getWatermarkDetectionCapabilities()
            
            // Example 1: Process image from data
            if let imageData = try? Data(contentsOf: URL(fileURLWithPath: "path/to/watermarked-image.jpg")) {
                let result1 = try await lightx.processWatermarkRemovalWithValidation(
                    imageData: imageData,
                    contentType: "image/jpeg"
                )
                print("🎉 Watermark removal result:")
                print("Order ID: \(result1.orderId)")
                print("Status: \(result1.status)")
                if let output = result1.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 2: Process another image
            if let imageData2 = try? Data(contentsOf: URL(fileURLWithPath: "path/to/another-image.png")) {
                let result2 = try await lightx.processWatermarkRemovalWithValidation(
                    imageData: imageData2,
                    contentType: "image/png"
                )
                print("🎉 Second watermark removal result:")
                print("Order ID: \(result2.orderId)")
                print("Status: \(result2.status)")
                if let output = result2.output {
                    print("Output: \(output)")
                }
            }
            
            // Example 3: Process multiple images
            let imagePaths = [
                "path/to/image1.jpg",
                "path/to/image2.png",
                "path/to/image3.jpg"
            ]
            
            for imagePath in imagePaths {
                if let imageData = try? Data(contentsOf: URL(fileURLWithPath: imagePath)) {
                    let result = try await lightx.processWatermarkRemovalWithValidation(
                        imageData: imageData,
                        contentType: "image/jpeg"
                    )
                    print("🎉 \(imagePath) watermark removal result:")
                    print("Order ID: \(result.orderId)")
                    print("Status: \(result.status)")
                    if let output = result.output {
                        print("Output: \(output)")
                    }
                }
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
