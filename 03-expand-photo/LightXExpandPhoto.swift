/**
 * LightX AI Expand Photo (Outpainting) API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered photo expansion functionality using padding-based outpainting.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct ExpandPhotoUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ExpandPhotoUploadImageBody
}

struct ExpandPhotoUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct ExpandPhotoResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ExpandPhotoBody
}

struct ExpandPhotoBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct ExpandPhotoOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ExpandPhotoOrderStatusBody
}

struct ExpandPhotoOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - Padding Configuration

struct PaddingConfig {
    let leftPadding: Int
    let rightPadding: Int
    let topPadding: Int
    let bottomPadding: Int
    
    init(leftPadding: Int = 0, rightPadding: Int = 0, topPadding: Int = 0, bottomPadding: Int = 0) {
        self.leftPadding = leftPadding
        self.rightPadding = rightPadding
        self.topPadding = topPadding
        self.bottomPadding = bottomPadding
    }
    
    func toDictionary() -> [String: Int] {
        return [
            "leftPadding": leftPadding,
            "rightPadding": rightPadding,
            "topPadding": topPadding,
            "bottomPadding": bottomPadding
        ]
    }
    
    static func horizontal(amount: Int) -> PaddingConfig {
        return PaddingConfig(leftPadding: amount, rightPadding: amount)
    }
    
    static func vertical(amount: Int) -> PaddingConfig {
        return PaddingConfig(topPadding: amount, bottomPadding: amount)
    }
    
    static func all(amount: Int) -> PaddingConfig {
        return PaddingConfig(leftPadding: amount, rightPadding: amount, topPadding: amount, bottomPadding: amount)
    }
}

// MARK: - LightX Expand Photo API Client

class LightXExpandPhotoAPI {
    
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
            throw LightXExpandPhotoError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Expand photo using AI outpainting
     * - Parameters:
     *   - imageURL: URL of the uploaded image
     *   - padding: Padding configuration
     * - Returns: Order ID for tracking
     */
    func expandPhoto(imageURL: String, padding: PaddingConfig) async throws -> String {
        let endpoint = "\(baseURL)/v1/expand-photo"
        
        guard let url = URL(string: endpoint) else {
            throw LightXExpandPhotoError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        var payload = ["imageUrl": imageURL]
        payload.merge(padding.toDictionary()) { _, new in new }
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXExpandPhotoError.networkError
        }
        
        let expandResponse = try JSONDecoder().decode(ExpandPhotoResponse.self, from: data)
        
        guard expandResponse.statusCode == 2000 else {
            throw LightXExpandPhotoError.apiError(expandResponse.message)
        }
        
        let orderInfo = expandResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("📐 Padding: L:\(padding.leftPadding) R:\(padding.rightPadding) T:\(padding.topPadding) B:\(padding.bottomPadding)")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * - Parameter orderID: Order ID to check
     * - Returns: Order status and results
     */
    func checkOrderStatus(orderID: String) async throws -> ExpandPhotoOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXExpandPhotoError.invalidURL
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
            throw LightXExpandPhotoError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(ExpandPhotoOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXExpandPhotoError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> ExpandPhotoOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Photo expansion completed successfully!")
                    if let output = status.output {
                        print("🖼️  Expanded image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXExpandPhotoError.processingFailed
                    
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
        
        throw LightXExpandPhotoError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and expand photo
     * - Parameters:
     *   - imageData: Image data to process
     *   - padding: Padding configuration
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processExpansion(imageData: Data, padding: PaddingConfig, 
                         contentType: String = "image/jpeg") async throws -> ExpandPhotoOrderStatusBody {
        print("🚀 Starting LightX AI Expand Photo API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageURL = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageURL)")
        
        // Step 2: Expand photo
        print("🖼️  Expanding photo with AI...")
        let orderID = try await expandPhoto(imageURL: imageURL, padding: padding)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    /**
     * Create padding configuration for common expansion scenarios
     * - Parameters:
     *   - direction: 'horizontal', 'vertical', 'all', or 'custom'
     *   - amount: Padding amount in pixels
     *   - customPadding: Custom padding object (for 'custom' direction)
     * - Returns: Padding configuration
     */
    func createPaddingConfig(direction: String, amount: Int = 100, 
                           customPadding: PaddingConfig? = nil) -> PaddingConfig {
        let config: PaddingConfig
        
        switch direction {
        case "horizontal":
            config = PaddingConfig.horizontal(amount: amount)
        case "vertical":
            config = PaddingConfig.vertical(amount: amount)
        case "all":
            config = PaddingConfig.all(amount: amount)
        case "custom":
            guard let custom = customPadding else {
                fatalError("customPadding must be provided for 'custom' direction")
            }
            config = custom
        default:
            fatalError("Invalid direction: \(direction). Use 'horizontal', 'vertical', 'all', or 'custom'")
        }
        
        print("📐 Created \(direction) padding config: \(config)")
        return config
    }
    
    /**
     * Expand photo to specific aspect ratio
     * - Parameters:
     *   - imageData: Image data to process
     *   - targetWidth: Target width
     *   - targetHeight: Target height
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func expandToAspectRatio(imageData: Data, targetWidth: Int, targetHeight: Int, 
                           contentType: String = "image/jpeg") async throws -> ExpandPhotoOrderStatusBody {
        print("🎯 Expanding to aspect ratio: \(targetWidth)x\(targetHeight)")
        print("⚠️  Note: This requires original image dimensions to calculate padding")
        
        // For demonstration, we'll use equal padding
        // In a real implementation, you'd calculate the required padding based on
        // the original image dimensions and target aspect ratio
        let padding = createPaddingConfig(direction: "all", amount: 100)
        return try await processExpansion(imageData: imageData, padding: padding, contentType: contentType)
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> ExpandPhotoUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXExpandPhotoError.invalidURL
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
            throw LightXExpandPhotoError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(ExpandPhotoUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXExpandPhotoError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: ExpandPhotoUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXExpandPhotoError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXExpandPhotoError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXExpandPhotoError: Error, LocalizedError {
    case invalidURL
    case networkError
    case apiError(String)
    case imageSizeExceeded
    case uploadFailed
    case processingFailed
    case maxRetriesReached
    
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
            return "Photo expansion processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXExpandPhotoExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXExpandPhotoAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Load image data (replace with your image loading logic)
            guard let image = UIImage(named: "your_image"),
                  let imageData = image.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load image")
                return
            }
            
            // Example 1: Expand horizontally
            let horizontalPadding = lightx.createPaddingConfig(direction: "horizontal", amount: 150)
            let result1 = try await lightx.processExpansion(
                imageData: imageData,
                padding: horizontalPadding,
                contentType: "image/jpeg"
            )
            print("🎉 Horizontal expansion result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Expand vertically
            let verticalPadding = lightx.createPaddingConfig(direction: "vertical", amount: 200)
            let result2 = try await lightx.processExpansion(
                imageData: imageData,
                padding: verticalPadding,
                contentType: "image/jpeg"
            )
            print("🎉 Vertical expansion result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Expand all sides equally
            let allSidesPadding = lightx.createPaddingConfig(direction: "all", amount: 100)
            let result3 = try await lightx.processExpansion(
                imageData: imageData,
                padding: allSidesPadding,
                contentType: "image/jpeg"
            )
            print("🎉 All-sides expansion result:")
            print("Order ID: \(result3.orderId)")
            print("Status: \(result3.status)")
            if let output = result3.output {
                print("Output: \(output)")
            }
            
            // Example 4: Custom padding
            let customPadding = PaddingConfig(
                leftPadding: 50,
                rightPadding: 200,
                topPadding: 75,
                bottomPadding: 125
            )
            let result4 = try await lightx.processExpansion(
                imageData: imageData,
                padding: customPadding,
                contentType: "image/jpeg"
            )
            print("🎉 Custom expansion result:")
            print("Order ID: \(result4.orderId)")
            print("Status: \(result4.status)")
            if let output = result4.output {
                print("Output: \(output)")
            }
            
            // Example 5: Get image dimensions
            let (width, height) = lightx.getImageDimensions(imageData: imageData)
            if width > 0 && height > 0 {
                print("📏 Original image: \(width)x\(height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
