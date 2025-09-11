/**
 * LightX Cleanup Picture API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for image cleanup functionality using mask-based object removal.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct CleanupUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: CleanupUploadImageBody
}

struct CleanupUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct CleanupPictureResponse: Codable {
    let statusCode: Int
    let message: String
    let body: CleanupPictureBody
}

struct CleanupPictureBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct CleanupOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: CleanupOrderStatusBody
}

struct CleanupOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Cleanup API Client

class LightXCleanupAPI {
    
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
            throw LightXCleanupError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Upload multiple images (original and mask)
     * - Parameters:
     *   - originalImageData: Original image data
     *   - maskImageData: Mask image data
     *   - contentType: MIME type
     * - Returns: Tuple of (originalURL, maskURL)
     */
    func uploadImages(originalImageData: Data, maskImageData: Data, 
                     contentType: String = "image/jpeg") async throws -> (String, String) {
        print("📤 Uploading original image...")
        let originalURL = try await uploadImage(imageData: originalImageData, contentType: contentType)
        
        print("📤 Uploading mask image...")
        let maskURL = try await uploadImage(imageData: maskImageData, contentType: contentType)
        
        return (originalURL, maskURL)
    }
    
    /**
     * Cleanup picture using mask
     * - Parameters:
     *   - imageURL: URL of the original image
     *   - maskedImageURL: URL of the mask image
     * - Returns: Order ID for tracking
     */
    func cleanupPicture(imageURL: String, maskedImageURL: String) async throws -> String {
        let endpoint = "\(baseURL)/v1/cleanup-picture"
        
        guard let url = URL(string: endpoint) else {
            throw LightXCleanupError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        let payload = [
            "imageUrl": imageURL,
            "maskedImageUrl": maskedImageURL
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXCleanupError.networkError
        }
        
        let cleanupResponse = try JSONDecoder().decode(CleanupPictureResponse.self, from: data)
        
        guard cleanupResponse.statusCode == 2000 else {
            throw LightXCleanupError.apiError(cleanupResponse.message)
        }
        
        let orderInfo = cleanupResponse.body
        
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
    func checkOrderStatus(orderID: String) async throws -> CleanupOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXCleanupError.invalidURL
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
            throw LightXCleanupError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(CleanupOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXCleanupError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> CleanupOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Picture cleanup completed successfully!")
                    if let output = status.output {
                        print("🖼️  Output image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXCleanupError.processingFailed
                    
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
        
        throw LightXCleanupError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and cleanup picture
     * - Parameters:
     *   - originalImageData: Original image data
     *   - maskImageData: Mask image data
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processCleanup(originalImageData: Data, maskImageData: Data, 
                       contentType: String = "image/jpeg") async throws -> CleanupOrderStatusBody {
        print("🚀 Starting LightX Cleanup Picture API workflow...")
        
        // Step 1: Upload both images
        print("📤 Uploading images...")
        let (originalURL, maskURL) = try await uploadImages(
            originalImageData: originalImageData, 
            maskImageData: maskImageData, 
            contentType: contentType
        )
        print("✅ Original image uploaded: \(originalURL)")
        print("✅ Mask image uploaded: \(maskURL)")
        
        // Step 2: Cleanup picture
        print("🧹 Cleaning up picture...")
        let orderID = try await cleanupPicture(imageURL: originalURL, maskedImageURL: maskURL)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    /**
     * Create a simple mask from coordinates (utility function)
     * - Parameters:
     *   - width: Image width
     *   - height: Image height
     *   - coordinates: Array of CGRect for white areas
     * - Returns: Mask image data
     */
    func createMaskFromCoordinates(width: Int, height: Int, coordinates: [CGRect]) -> Data? {
        print("🎭 Creating mask from coordinates...")
        print("Image dimensions: \(width)x\(height)")
        print("White areas: \(coordinates)")
        
        let size = CGSize(width: width, height: height)
        let renderer = UIGraphicsImageRenderer(size: size)
        
        let maskImage = renderer.image { context in
            // Fill with black background
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            
            // Draw white rectangles for areas to remove
            UIColor.white.setFill()
            for rect in coordinates {
                context.fill(rect)
            }
        }
        
        return maskImage.jpegData(compressionQuality: 1.0)
    }
    
    // MARK: - Private Methods
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> CleanupUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXCleanupError.invalidURL
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
            throw LightXCleanupError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(CleanupUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXCleanupError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: CleanupUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXCleanupError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXCleanupError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXCleanupError: Error, LocalizedError {
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
            return "Picture cleanup processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXCleanupExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXCleanupAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Load original image data (replace with your image loading logic)
            guard let originalImage = UIImage(named: "original_image"),
                  let originalImageData = originalImage.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load original image")
                return
            }
            
            // Option 1: Use existing mask image
            guard let maskImage = UIImage(named: "mask_image"),
                  let maskImageData = maskImage.jpegData(compressionQuality: 1.0) else {
                print("❌ Failed to load mask image")
                return
            }
            
            // Process the cleanup
            let result = try await lightx.processCleanup(
                originalImageData: originalImageData,
                maskImageData: maskImageData,
                contentType: "image/jpeg"
            )
            
            print("🎉 Final result:")
            print("Order ID: \(result.orderId)")
            print("Status: \(result.status)")
            if let output = result.output {
                print("Output: \(output)")
            }
            
            // Option 2: Create mask from coordinates
            // let maskData = lightx.createMaskFromCoordinates(
            //     width: 800, height: 600,
            //     coordinates: [
            //         CGRect(x: 100, y: 100, width: 200, height: 150),  // Area to remove
            //         CGRect(x: 400, y: 300, width: 100, height: 100)   // Another area to remove
            //     ]
            // )
            // 
            // if let maskData = maskData {
            //     let result = try await lightx.processCleanup(
            //         originalImageData: originalImageData,
            //         maskImageData: maskData,
            //         contentType: "image/jpeg"
            //     )
            //     print("🎉 Final result with generated mask:")
            //     print("Order ID: \(result.orderId)")
            //     print("Status: \(result.status)")
            //     if let output = result.output {
            //         print("Output: \(output)")
            //     }
            // }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
