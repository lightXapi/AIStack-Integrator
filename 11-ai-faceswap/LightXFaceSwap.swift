/**
 * LightX AI Face Swap API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered face swap functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct FaceSwapUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: FaceSwapUploadImageBody
}

struct FaceSwapUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct FaceSwapGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: FaceSwapGenerationBody
}

struct FaceSwapGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct FaceSwapOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: FaceSwapOrderStatusBody
}

struct FaceSwapOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Face Swap API Client

class LightXFaceSwapAPI {
    
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
            throw LightXFaceSwapError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Upload multiple images (source and target images)
     * - Parameters:
     *   - sourceImageData: Source image data
     *   - targetImageData: Target image data
     *   - contentType: MIME type
     * - Returns: Tuple of (sourceURL, targetURL)
     */
    func uploadImages(sourceImageData: Data, targetImageData: Data, 
                     contentType: String = "image/jpeg") async throws -> (String, String) {
        print("📤 Uploading source image...")
        let sourceURL = try await uploadImage(imageData: sourceImageData, contentType: contentType)
        
        print("📤 Uploading target image...")
        let targetURL = try await uploadImage(imageData: targetImageData, contentType: contentType)
        
        return (sourceURL, targetURL)
    }
    
    /**
     * Generate face swap
     * - Parameters:
     *   - imageURL: URL of the source image (face to be swapped)
     *   - styleImageURL: URL of the target image (face to be replaced)
     * - Returns: Order ID for tracking
     */
    func generateFaceSwap(imageURL: String, styleImageURL: String) async throws -> String {
        let endpoint = "\(baseURL)/v1/face-swap"
        
        guard let url = URL(string: endpoint) else {
            throw LightXFaceSwapError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        
        let payload = [
            "imageUrl": imageURL,
            "styleImageUrl": styleImageURL
        ] as [String: Any]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXFaceSwapError.networkError
        }
        
        let faceSwapResponse = try JSONDecoder().decode(FaceSwapGenerationResponse.self, from: data)
        
        guard faceSwapResponse.statusCode == 2000 else {
            throw LightXFaceSwapError.apiError(faceSwapResponse.message)
        }
        
        let orderInfo = faceSwapResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🎭 Source image: \(imageURL)")
        print("🎯 Target image: \(styleImageURL)")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * - Parameter orderID: Order ID to check
     * - Returns: Order status and results
     */
    func checkOrderStatus(orderID: String) async throws -> FaceSwapOrderStatusBody {
        let endpoint = "\(baseURL)/v1/order-status"
        
        guard let url = URL(string: endpoint) else {
            throw LightXFaceSwapError.invalidURL
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
            throw LightXFaceSwapError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(FaceSwapOrderStatusResponse.self, from: data)
        
        guard statusResponse.statusCode == 2000 else {
            throw LightXFaceSwapError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * - Parameter orderID: Order ID to monitor
     * - Returns: Final result with output URL
     */
    func waitForCompletion(orderID: String) async throws -> FaceSwapOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderID: orderID)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Face swap completed successfully!")
                    if let output = status.output {
                        print("🔄 Face swap result: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXFaceSwapError.processingFailed
                    
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
        
        throw LightXFaceSwapError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and generate face swap
     * - Parameters:
     *   - sourceImageData: Source image data
     *   - targetImageData: Target image data
     *   - contentType: MIME type
     * - Returns: Final result with output URL
     */
    func processFaceSwap(sourceImageData: Data, targetImageData: Data, 
                        contentType: String = "image/jpeg") async throws -> FaceSwapOrderStatusBody {
        print("🚀 Starting LightX AI Face Swap API workflow...")
        
        // Step 1: Upload images
        print("📤 Uploading images...")
        let (sourceURL, targetURL) = try await uploadImages(
            sourceImageData: sourceImageData, 
            targetImageData: targetImageData, 
            contentType: contentType
        )
        print("✅ Source image uploaded: \(sourceURL)")
        print("✅ Target image uploaded: \(targetURL)")
        
        // Step 2: Generate face swap
        print("🔄 Generating face swap...")
        let orderID = try await generateFaceSwap(imageURL: sourceURL, styleImageURL: targetURL)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderID: orderID)
        
        return result
    }
    
    /**
     * Get face swap tips and best practices
     * - Returns: Dictionary of tips for better face swap results
     */
    func getFaceSwapTips() -> [String: [String]] {
        let tips: [String: [String]] = [
            "source_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for face swapping"
            ],
            "target_image": [
                "Choose target images with clear facial features",
                "Ensure target face is clearly visible and well-lit",
                "Use images with similar lighting conditions",
                "Avoid heavily edited or filtered images",
                "Use high-quality target reference images"
            ],
            "general": [
                "Face swaps work best with clear human faces",
                "Results may vary based on input image quality",
                "Similar lighting conditions improve results",
                "Front-facing photos produce better face swaps",
                "Allow 15-30 seconds for processing"
            ]
        ]
        
        print("💡 Face Swap Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get face swap use cases and examples
     * - Returns: Dictionary of use case examples
     */
    func getFaceSwapUseCases() -> [String: [String]] {
        let useCases: [String: [String]] = [
            "entertainment": [
                "Movie character face swaps",
                "Celebrity face swaps",
                "Historical figure face swaps",
                "Fantasy character face swaps",
                "Comedy and entertainment content"
            ],
            "creative": [
                "Artistic face swap projects",
                "Creative photo manipulation",
                "Digital art creation",
                "Social media content",
                "Memes and viral content"
            ],
            "professional": [
                "Film and video production",
                "Marketing and advertising",
                "Educational content",
                "Training materials",
                "Presentation graphics"
            ],
            "personal": [
                "Fun personal photos",
                "Family photo editing",
                "Social media posts",
                "Party and event photos",
                "Creative selfies"
            ]
        ]
        
        print("💡 Face Swap Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
    }
    
    /**
     * Get face swap quality improvement tips
     * - Returns: Dictionary of quality improvement tips
     */
    func getFaceSwapQualityTips() -> [String: [String]] {
        let qualityTips: [String: [String]] = [
            "lighting": [
                "Use similar lighting conditions in both images",
                "Avoid harsh shadows on faces",
                "Ensure even lighting across the face",
                "Natural lighting often produces better results",
                "Avoid backlit or silhouette images"
            ],
            "angle": [
                "Use front-facing photos for best results",
                "Avoid extreme angles or tilted heads",
                "Keep faces centered in the frame",
                "Similar head angles improve face swap quality",
                "Avoid profile shots for optimal results"
            ],
            "resolution": [
                "Use high-resolution images when possible",
                "Ensure clear facial features are visible",
                "Avoid heavily compressed images",
                "Good image quality improves face swap accuracy",
                "Minimum 512x512 pixels recommended"
            ],
            "expression": [
                "Neutral expressions often work best",
                "Similar facial expressions improve results",
                "Avoid extreme expressions or emotions",
                "Natural expressions produce better face swaps",
                "Consider the context of the target image"
            ]
        ]
        
        print("💡 Face Swap Quality Tips:")
        for (category, tipList) in qualityTips {
            print("\(category): \(tipList)")
        }
        return qualityTips
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
    
    /**
     * Validate images for face swap (utility function)
     * - Parameters:
     *   - sourceImageData: Source image data
     *   - targetImageData: Target image data
     * - Returns: Whether images are valid for face swap
     */
    func validateImagesForFaceSwap(sourceImageData: Data, targetImageData: Data) -> Bool {
        let sourceDimensions = getImageDimensions(imageData: sourceImageData)
        let targetDimensions = getImageDimensions(imageData: targetImageData)
        
        if sourceDimensions.0 == 0 || targetDimensions.0 == 0 {
            print("❌ Invalid image dimensions detected")
            return false
        }
        
        // Additional validation could include:
        // - Face detection
        // - Image quality assessment
        // - Lighting condition analysis
        // - Resolution requirements
        
        print("✅ Images validated for face swap")
        return true
    }
    
    // MARK: - Private Methods
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> FaceSwapUploadImageBody {
        let endpoint = "\(baseURL)/v2/uploadImageUrl"
        
        guard let url = URL(string: endpoint) else {
            throw LightXFaceSwapError.invalidURL
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
            throw LightXFaceSwapError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(FaceSwapUploadImageResponse.self, from: data)
        
        guard uploadResponse.statusCode == 2000 else {
            throw LightXFaceSwapError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: FaceSwapUploadImageBody, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL.uploadImage) else {
            throw LightXFaceSwapError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXFaceSwapError.uploadFailed
        }
    }
}

// MARK: - Error Handling

enum LightXFaceSwapError: Error, LocalizedError {
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
            return "Face swap processing failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        }
    }
}

// MARK: - Example Usage

@MainActor
class LightXFaceSwapExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXFaceSwapAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getFaceSwapTips()
            lightx.getFaceSwapUseCases()
            lightx.getFaceSwapQualityTips()
            
            // Load image data (replace with your image loading logic)
            guard let sourceImage = UIImage(named: "source_image"),
                  let sourceImageData = sourceImage.jpegData(compressionQuality: 0.8),
                  let targetImage = UIImage(named: "target_image"),
                  let targetImageData = targetImage.jpegData(compressionQuality: 0.8) else {
                print("❌ Failed to load images")
                return
            }
            
            // Validate images before processing
            let isValid = lightx.validateImagesForFaceSwap(sourceImageData: sourceImageData, targetImageData: targetImageData)
            if !isValid {
                print("❌ Images are not suitable for face swap")
                return
            }
            
            // Example 1: Basic face swap
            let result1 = try await lightx.processFaceSwap(
                sourceImageData: sourceImageData,
                targetImageData: targetImageData,
                contentType: "image/jpeg"
            )
            print("🎉 Face swap result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Get image dimensions
            let sourceDimensions = lightx.getImageDimensions(imageData: sourceImageData)
            let targetDimensions = lightx.getImageDimensions(imageData: targetImageData)
            
            if sourceDimensions.0 > 0 && sourceDimensions.1 > 0 {
                print("📏 Source image: \(sourceDimensions.0)x\(sourceDimensions.1)")
            }
            if targetDimensions.0 > 0 && targetDimensions.1 > 0 {
                print("📏 Target image: \(targetDimensions.0)x\(targetDimensions.1)")
            }
            
            // Example 3: Multiple face swaps with different image pairs
            let imagePairs = [
                (source: sourceImageData, target: targetImageData),
                (source: sourceImageData, target: targetImageData),
                (source: sourceImageData, target: targetImageData)
            ]
            
            for (index, pair) in imagePairs.enumerated() {
                print("\n🎭 Processing face swap \(index + 1)...")
                
                do {
                    let result = try await lightx.processFaceSwap(
                        sourceImageData: pair.source,
                        targetImageData: pair.target,
                        contentType: "image/jpeg"
                    )
                    print("✅ Face swap \(index + 1) completed:")
                    print("Order ID: \(result.orderId)")
                    print("Status: \(result.status)")
                    if let output = result.output {
                        print("Output: \(output)")
                    }
                } catch {
                    print("❌ Face swap \(index + 1) failed: \(error.localizedDescription)")
                }
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
