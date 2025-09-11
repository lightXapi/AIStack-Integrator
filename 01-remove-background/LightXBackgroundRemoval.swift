/**
 * LightX Remove Background API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for image upload and background removal functionality using Swift.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct UploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: UploadImageBody
}

struct UploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct RemoveBackgroundResponse: Codable {
    let statusCode: Int
    let message: String
    let body: RemoveBackgroundBody
}

struct RemoveBackgroundBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct OrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: OrderStatusBody
}

struct OrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
    let mask: String?
}

// MARK: - LightX API Client

class LightXAPI {
    
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
            throw LightXError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Remove background from image
     * - Parameters:
     *   - imageURL: URL of the uploaded image
     *   - background: Background color, color code, or image URL
     * - Returns: Order ID for tracking
     */
    func removeBackground(imageURL: String, background: String = "transparent") async throws -> String {
        let endpoint = "\(baseURL)/v1/remove-background"
        
        guard let url = URL(string: endpoint) else {
            throw LightXError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        let payload = [
            "imageUrl": imageURL,
            "background": background
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXError.networkError
        }
        
        let removeBackgroundResponse = try JSONDecoder().decode(RemoveBackgroundResponse.self, from: data)
        
        guard removeBackgroundResponse.statusCode == 2000 else {
            throw LightXError.apiError(removeBackgroundResponse.message)
        }
        
        let orderInfo = removeBackgroundResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * - Parameter orderID: Order ID to check
     * - Returns: Order status and results
     */
    func checkOrderStatus(orderID: String) async throws -> OrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXError.invalidURL
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
            throw LightXError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(OrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URLs
     */
    func waitForCompletion(orderID: String) async throws -> OrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Background removal completed successfully!")
                    if let output = status.output {
                        print("🖼️  Output image: \(output)")
                    }
                    if let mask = status.mask {
                        print("🎭 Mask image: \(mask)")
                    }
                    return status
                    
                case "failed":
                    throw LightXError.processingFailed
                    
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
        
        throw LightXError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and remove background
     * - Parameters:
     *   - imageData: Image data to process
     *   - background: Background color/code/URL
     *   - contentType: MIME type
     * - Returns: Final result with output URLs
     */
    func processImage(imageData: Data, background: String = "transparent", 
                     contentType: String = "image/jpeg") async throws -> OrderStatusBody {
        print("🚀 Starting LightX API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageURL = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageURL)")
        
        // Step 2: Remove background
        print("🎨 Removing background...")
        let orderID = try await removeBackground(imageURL: imageURL, background: background)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    // MARK: - Private Methods
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> UploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXError.invalidURL
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
            throw LightXError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(UploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: UploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXError: Error, LocalizedError {
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
            return "Background removal processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Load image data (replace with your image loading logic)
            guard let image = UIImage(named: "your_image"),
                  let imageData = image.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load image")
                return
            }
            
            // Process the image
            let result = try await lightx.processImage(
                imageData: imageData,
                background: "white",
                contentType: "image/jpeg"
            )
            
            print("🎉 Final result:")
            print("Order ID: \(result.orderId)")
            print("Status: \(result.status)")
            if let output = result.output {
                print("Output: \(output)")
            }
            if let mask = result.mask {
                print("Mask: \(mask)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
