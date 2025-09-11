/**
 * LightX AI Replace Item API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered item replacement functionality using text prompts and masks.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct ReplaceItemUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ReplaceItemUploadImageBody
}

struct ReplaceItemUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct ReplaceItemResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ReplaceItemBody
}

struct ReplaceItemBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct ReplaceItemOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: ReplaceItemOrderStatusBody
}

struct ReplaceItemOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Replace Item API Client

class LightXReplaceAPI {
    
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
            throw LightXReplaceError.imageSizeExceeded
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
     * Replace item using AI and text prompt
     * - Parameters:
     *   - imageURL: URL of the original image
     *   - maskedImageURL: URL of the mask image
     *   - textPrompt: Text prompt describing what to replace with
     * - Returns: Order ID for tracking
     */
    func replaceItem(imageURL: String, maskedImageURL: String, textPrompt: String) async throws -> String {
        let endpoint = "\(baseURL)/v1/replace"
        
        guard let url = URL(string: endpoint) else {
            throw LightXReplaceError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        let payload = [
            "imageUrl": imageURL,
            "maskedImageUrl": maskedImageURL,
            "textPrompt": textPrompt
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXReplaceError.networkError
        }
        
        let replaceResponse = try JSONDecoder().decode(ReplaceItemResponse.self, from: data)
        
        guard replaceResponse.statusCode == 2000 else {
            throw LightXReplaceError.apiError(replaceResponse.message)
        }
        
        let orderInfo = replaceResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("💬 Text prompt: \"\(textPrompt)\"")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * - Parameter orderID: Order ID to check
     * - Returns: Order status and results
     */
    func checkOrderStatus(orderID: String) async throws -> ReplaceItemOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXReplaceError.invalidURL
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
            throw LightXReplaceError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(ReplaceItemOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXReplaceError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> ReplaceItemOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Item replacement completed successfully!")
                    if let output = status.output {
                        print("🖼️  Replaced image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXReplaceError.processingFailed
                    
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
        
        throw LightXReplaceError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and replace item
     * - Parameters:
     *   - originalImageData: Original image data
     *   - maskImageData: Mask image data
     *   - textPrompt: Text prompt for replacement
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processReplacement(originalImageData: Data, maskImageData: Data, 
                           textPrompt: String, contentType: String = "image/jpeg") async throws -> ReplaceItemOrderStatusBody {
        print("🚀 Starting LightX AI Replace API workflow...")
        
        // Step 1: Upload both images
        print("📤 Uploading images...")
        let (originalURL, maskURL) = try await uploadImages(
            originalImageData: originalImageData, 
            maskImageData: maskImageData, 
            contentType: contentType
        )
        print("✅ Original image uploaded: \(originalURL)")
        print("✅ Mask image uploaded: \(maskURL)")
        
        // Step 2: Replace item
        print("🔄 Replacing item with AI...")
        let orderID = try await replaceItem(imageURL: originalURL, maskedImageURL: maskURL, textPrompt: textPrompt)
        
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
            
            // Draw white rectangles for areas to replace
            UIColor.white.setFill()
            for rect in coordinates {
                context.fill(rect)
            }
        }
        
        return maskImage.jpegData(compressionQuality: 1.0)
    }
    
    /**
     * Get common text prompts for different replacement scenarios
     * - Parameter category: Category of replacement
     * - Returns: Array of suggested prompts
     */
    func getSuggestedPrompts(category: String) -> [String] {
        let promptSuggestions: [String: [String]] = [
            "face": [
                "a young woman with blonde hair",
                "an elderly man with a beard",
                "a smiling child",
                "a professional businessman",
                "a person wearing glasses"
            ],
            "clothing": [
                "a red dress",
                "a blue suit",
                "a casual t-shirt",
                "a winter jacket",
                "a formal shirt"
            ],
            "objects": [
                "a modern smartphone",
                "a vintage car",
                "a beautiful flower",
                "a wooden chair",
                "a glass vase"
            ],
            "background": [
                "a beach scene",
                "a mountain landscape",
                "a modern office",
                "a cozy living room",
                "a garden setting"
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> ReplaceItemUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXReplaceError.invalidURL
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
            throw LightXReplaceError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(ReplaceItemUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXReplaceError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: ReplaceItemUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXReplaceError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXReplaceError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXReplaceError: Error, LocalizedError {
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
            return "Item replacement processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXReplaceExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXReplaceAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Load original image data (replace with your image loading logic)
            guard let originalImage = UIImage(named: "original_image"),
                  let originalImageData = originalImage.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load original image")
                return
            }
            
            // Load mask image data (replace with your image loading logic)
            guard let maskImage = UIImage(named: "mask_image"),
                  let maskImageData = maskImage.jpegData(compressionQuality: 1.0) else {
                print("❌ Failed to load mask image")
                return
            }
            
            // Example 1: Replace a face
            let facePrompts = lightx.getSuggestedPrompts(category: "face")
            let result1 = try await lightx.processReplacement(
                originalImageData: originalImageData,
                maskImageData: maskImageData,
                textPrompt: facePrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Face replacement result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Replace clothing
            let clothingPrompts = lightx.getSuggestedPrompts(category: "clothing")
            let result2 = try await lightx.processReplacement(
                originalImageData: originalImageData,
                maskImageData: maskImageData,
                textPrompt: clothingPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Clothing replacement result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Replace background
            let backgroundPrompts = lightx.getSuggestedPrompts(category: "background")
            let result3 = try await lightx.processReplacement(
                originalImageData: originalImageData,
                maskImageData: maskImageData,
                textPrompt: backgroundPrompts[0],
                contentType: "image/jpeg"
            )
            print("🎉 Background replacement result:")
            print("Order ID: \(result3.orderId)")
            print("Status: \(result3.status)")
            if let output = result3.output {
                print("Output: \(output)")
            }
            
            // Example 4: Create mask from coordinates and process
            // let (width, height) = lightx.getImageDimensions(imageData: originalImageData)
            // if width > 0 && height > 0 {
            //     if let maskData = lightx.createMaskFromCoordinates(
            //         width: width, height: height,
            //         coordinates: [
            //             CGRect(x: 100, y: 100, width: 200, height: 150),  // Area to replace
            //             CGRect(x: 400, y: 300, width: 100, height: 100)   // Another area to replace
            //         ]
            //     ) {
            //         let result = try await lightx.processReplacement(
            //             originalImageData: originalImageData,
            //             maskImageData: maskData,
            //             textPrompt: "a beautiful sunset",
            //             contentType: "image/jpeg"
            //         )
            //         print("🎉 Replacement with generated mask:")
            //         print("Order ID: \(result.orderId)")
            //         print("Status: \(result.status)")
            //         if let output = result.output {
            //             print("Output: \(output)")
            //         }
            //     }
            // }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
