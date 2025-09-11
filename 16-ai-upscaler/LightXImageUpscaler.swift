/**
 * LightX AI Image Upscaler API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image upscaling functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct UpscalerUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: UpscalerUploadImageBody
}

struct UpscalerUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct UpscalerGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: UpscalerGenerationBody
}

struct UpscalerGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct UpscalerOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: UpscalerOrderStatusBody
}

struct UpscalerOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX Image Upscaler API Client

@MainActor
class LightXImageUpscalerAPI: ObservableObject {
    
    // MARK: - Properties
    
    private let apiKey: String
    private let baseURL = "https://api.lightxeditor.com/external/api"
    private let maxRetries = 5
    private let retryInterval: TimeInterval = 3.0
    private let maxFileSize: Int = 5242880 // 5MB
    private let maxImageDimension: Int = 2048
    
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
            throw LightXUpscalerError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Generate image upscaling
     * @param imageUrl URL of the input image
     * @param quality Upscaling quality (2 or 4)
     * @return Order ID for tracking
     */
    func generateUpscale(imageUrl: String, quality: Int) async throws -> String {
        let endpoint = "\(baseURL)/v2/upscale/"
        
        let requestBody = [
            "imageUrl": imageUrl,
            "quality": quality
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXUpscalerError.networkError
        }
        
        let upscalerResponse = try JSONDecoder().decode(UpscalerGenerationResponse.self, from: data)
        
        if upscalerResponse.statusCode != 2000 {
            throw LightXUpscalerError.apiError(upscalerResponse.message)
        }
        
        let orderInfo = upscalerResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🔍 Upscale quality: \(quality)x")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> UpscalerOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXUpscalerError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(UpscalerOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXUpscalerError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> UpscalerOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Image upscaling completed successfully!")
                    if let output = status.output {
                        print("🔍 Upscaled image: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXUpscalerError.processingFailed
                    
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
        
        throw LightXUpscalerError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload image and generate upscaling
     * @param imageData Image data
     * @param quality Upscaling quality (2 or 4)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processUpscaling(imageData: Data, quality: Int, contentType: String = "image/jpeg") async throws -> UpscalerOrderStatusBody {
        print("🚀 Starting LightX AI Image Upscaler API workflow...")
        
        // Step 1: Upload image
        print("📤 Uploading image...")
        let imageUrl = try await uploadImage(imageData: imageData, contentType: contentType)
        print("✅ Image uploaded: \(imageUrl)")
        
        // Step 2: Generate upscaling
        print("🔍 Generating image upscaling...")
        let orderId = try await generateUpscale(imageUrl: imageUrl, quality: quality)
        
        // Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get image upscaling tips and best practices
     * @return Dictionary containing tips for better results
     */
    func getUpscalingTips() -> [String: [String]] {
        let tips = [
            "input_image": [
                "Use clear, well-lit images with good contrast",
                "Ensure the image is not already at maximum resolution",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best upscaling results",
                "Good source image quality improves upscaling results"
            ],
            "image_dimensions": [
                "Images 1024x1024 or smaller can be upscaled 2x or 4x",
                "Images larger than 1024x1024 but smaller than 2048x2048 can only be upscaled 2x",
                "Images larger than 2048x2048 cannot be upscaled",
                "Check image dimensions before attempting upscaling",
                "Resize large images before upscaling if needed"
            ],
            "quality_selection": [
                "Use 2x upscaling for moderate quality improvement",
                "Use 4x upscaling for maximum quality improvement",
                "4x upscaling works best on smaller images (1024x1024 or less)",
                "Consider file size increase with higher upscaling factors",
                "Choose quality based on your specific needs"
            ],
            "general": [
                "Image upscaling works best with clear, detailed source images",
                "Results may vary based on input image quality and content",
                "Upscaling preserves detail while enhancing resolution",
                "Allow 15-30 seconds for processing",
                "Experiment with different quality settings for optimal results"
            ]
        ]
        
        print("💡 Image Upscaling Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get upscaling quality suggestions
     * @return Dictionary containing quality suggestions
     */
    func getQualitySuggestions() -> [String: [String: Any]] {
        let qualitySuggestions = [
            "2x": [
                "description": "Moderate quality improvement with 2x resolution increase",
                "best_for": [
                    "General image enhancement",
                    "Social media images",
                    "Web display images",
                    "Moderate quality improvement needs",
                    "Balanced quality and file size"
                ],
                "use_cases": [
                    "Enhancing photos for social media",
                    "Improving web images",
                    "General image quality enhancement",
                    "Preparing images for print at moderate sizes"
                ]
            ],
            "4x": [
                "description": "Maximum quality improvement with 4x resolution increase",
                "best_for": [
                    "High-quality image enhancement",
                    "Print-ready images",
                    "Professional photography",
                    "Maximum detail preservation",
                    "Large format displays"
                ],
                "use_cases": [
                    "Professional photography enhancement",
                    "Print-ready image preparation",
                    "Large format display images",
                    "Maximum quality requirements",
                    "Archival image enhancement"
                ]
            ]
        ]
        
        print("💡 Upscaling Quality Suggestions:")
        for (quality, suggestion) in qualitySuggestions {
            print("\(quality): \(suggestion["description"] ?? "")")
            if let bestFor = suggestion["best_for"] as? [String] {
                print("  Best for: \(bestFor.joined(separator: ", "))")
            }
            if let useCases = suggestion["use_cases"] as? [String] {
                print("  Use cases: \(useCases.joined(separator: ", "))")
            }
        }
        return qualitySuggestions
    }
    
    /**
     * Get image dimension guidelines
     * @return Dictionary containing dimension guidelines
     */
    func getDimensionGuidelines() -> [String: [String: Any]] {
        let dimensionGuidelines = [
            "small_images": [
                "range": "Up to 1024x1024 pixels",
                "upscaling_options": ["2x upscaling", "4x upscaling"],
                "description": "Small images can be upscaled with both 2x and 4x quality",
                "examples": ["Profile pictures", "Thumbnails", "Small photos", "Icons"]
            ],
            "medium_images": [
                "range": "1024x1024 to 2048x2048 pixels",
                "upscaling_options": ["2x upscaling only"],
                "description": "Medium images can only be upscaled with 2x quality",
                "examples": ["Standard photos", "Web images", "Medium prints"]
            ],
            "large_images": [
                "range": "Larger than 2048x2048 pixels",
                "upscaling_options": ["Cannot be upscaled"],
                "description": "Large images cannot be upscaled and will show an error",
                "examples": ["High-resolution photos", "Large prints", "Professional images"]
            ]
        ]
        
        print("💡 Image Dimension Guidelines:")
        for (category, info) in dimensionGuidelines {
            print("\(category): \(info["range"] ?? "")")
            if let options = info["upscaling_options"] as? [String] {
                print("  Upscaling options: \(options.joined(separator: ", "))")
            }
            print("  Description: \(info["description"] ?? "")")
            if let examples = info["examples"] as? [String] {
                print("  Examples: \(examples.joined(separator: ", "))")
            }
        }
        return dimensionGuidelines
    }
    
    /**
     * Get upscaling use cases and examples
     * @return Dictionary containing use case examples
     */
    func getUpscalingUseCases() -> [String: [String]] {
        let useCases = [
            "photography": [
                "Enhance low-resolution photos",
                "Prepare images for large prints",
                "Improve vintage photo quality",
                "Enhance smartphone photos",
                "Professional photo enhancement"
            ],
            "web_design": [
                "Create high-DPI images for retina displays",
                "Enhance images for modern web standards",
                "Improve image quality for websites",
                "Create responsive image assets",
                "Enhance social media images"
            ],
            "print_media": [
                "Prepare images for large format printing",
                "Enhance images for magazine quality",
                "Improve poster and banner images",
                "Enhance images for professional printing",
                "Create high-resolution marketing materials"
            ],
            "archival": [
                "Enhance historical photographs",
                "Improve scanned document quality",
                "Restore old family photos",
                "Enhance archival images",
                "Preserve and enhance historical content"
            ],
            "creative": [
                "Enhance digital art and illustrations",
                "Improve concept art quality",
                "Enhance graphic design elements",
                "Improve texture and pattern quality",
                "Enhance creative project assets"
            ]
        ]
        
        print("💡 Upscaling Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
    }
    
    /**
     * Validate parameters
     * @param quality Quality parameter to validate
     * @param width Image width to validate
     * @param height Image height to validate
     * @return Whether the parameters are valid
     */
    func validateParameters(quality: Int, width: Int, height: Int) -> Bool {
        // Validate quality
        if quality != 2 && quality != 4 {
            print("❌ Quality must be 2 or 4")
            return false
        }
        
        // Validate image dimensions
        let maxDimension = max(width, height)
        
        if maxDimension > maxImageDimension {
            print("❌ Image dimension (\(maxDimension)px) exceeds maximum allowed (\(maxImageDimension)px)")
            return false
        }
        
        // Check quality vs dimension compatibility
        if maxDimension > 1024 && quality == 4 {
            print("❌ 4x upscaling is only available for images 1024x1024 or smaller")
            return false
        }
        
        print("✅ Parameters are valid")
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
     * Generate upscaling with parameter validation
     * @param imageData Image data
     * @param quality Upscaling quality (2 or 4)
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func generateUpscaleWithValidation(imageData: Data, quality: Int, contentType: String = "image/jpeg") async throws -> UpscalerOrderStatusBody {
        let dimensions = getImageDimensions(imageData: imageData)
        
        if !validateParameters(quality: quality, width: dimensions.width, height: dimensions.height) {
            throw LightXUpscalerError.invalidParameters
        }
        
        return try await processUpscaling(imageData: imageData, quality: quality, contentType: contentType)
    }
    
    /**
     * Get recommended quality based on image dimensions
     * @param width Image width
     * @param height Image height
     * @return Recommended quality options
     */
    func getRecommendedQuality(width: Int, height: Int) -> [String: Any] {
        let maxDimension = max(width, height)
        
        if maxDimension <= 1024 {
            return [
                "available": [2, 4],
                "recommended": 4,
                "reason": "Small images can use 4x upscaling for maximum quality"
            ]
        } else if maxDimension <= 2048 {
            return [
                "available": [2],
                "recommended": 2,
                "reason": "Medium images can only use 2x upscaling"
            ]
        } else {
            return [
                "available": [],
                "recommended": nil,
                "reason": "Large images cannot be upscaled"
            ]
        }
    }
    
    /**
     * Get quality comparison between 2x and 4x upscaling
     * @return Dictionary containing comparison information
     */
    func getQualityComparison() -> [String: [String: Any]] {
        let comparison = [
            "2x_upscaling": [
                "resolution_increase": "4x total pixels (2x width × 2x height)",
                "file_size_increase": "Approximately 4x larger",
                "processing_time": "Faster processing",
                "best_for": [
                    "General image enhancement",
                    "Web and social media use",
                    "Moderate quality improvement",
                    "Balanced quality and file size"
                ],
                "limitations": [
                    "Less dramatic quality improvement",
                    "May not be sufficient for large prints"
                ]
            ],
            "4x_upscaling": [
                "resolution_increase": "16x total pixels (4x width × 4x height)",
                "file_size_increase": "Approximately 16x larger",
                "processing_time": "Longer processing time",
                "best_for": [
                    "Maximum quality enhancement",
                    "Large format printing",
                    "Professional photography",
                    "Archival image enhancement"
                ],
                "limitations": [
                    "Only available for images ≤1024x1024",
                    "Much larger file sizes",
                    "Longer processing time"
                ]
            ]
        ]
        
        print("💡 Quality Comparison:")
        for (quality, info) in comparison {
            print("\(quality):")
            for (key, value) in info {
                if let list = value as? [String] {
                    print("  \(key): \(list.joined(separator: ", "))")
                } else {
                    print("  \(key): \(value)")
                }
            }
        }
        return comparison
    }
    
    /**
     * Get technical specifications for upscaling
     * @return Dictionary containing technical specifications
     */
    func getTechnicalSpecifications() -> [String: [String: Any]] {
        let specifications = [
            "supported_formats": [
                "input": ["JPEG", "PNG"],
                "output": ["JPEG"],
                "color_spaces": ["RGB", "sRGB"]
            ],
            "size_limits": [
                "max_file_size": "5MB",
                "max_dimension": "2048px",
                "min_dimension": "1px"
            ],
            "quality_options": [
                "2x": "Available for all supported image sizes",
                "4x": "Only available for images ≤1024x1024px"
            ],
            "processing": [
                "max_retries": 5,
                "retry_interval": "3 seconds",
                "avg_processing_time": "15-30 seconds",
                "timeout": "No timeout limit"
            ]
        ]
        
        print("💡 Technical Specifications:")
        for (category, specs) in specifications {
            print("\(category): \(specs)")
        }
        return specifications
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let requestURL = URL(string: url) else {
            throw LightXUpscalerError.invalidURL
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> UpscalerUploadImageBody {
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
            throw LightXUpscalerError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(UpscalerUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXUpscalerError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXUpscalerError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXUpscalerError.uploadFailed
        }
    }
}

// MARK: - Error Types

enum LightXUpscalerError: Error, LocalizedError {
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
            return "Image upscaling failed"
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

struct LightXUpscalerExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXImageUpscalerAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getUpscalingTips()
            lightx.getQualitySuggestions()
            lightx.getDimensionGuidelines()
            lightx.getUpscalingUseCases()
            lightx.getQualityComparison()
            lightx.getTechnicalSpecifications()
            
            // Load image (replace with your image loading logic)
            guard let imageData = loadImageData() else {
                print("❌ Failed to load image data")
                return
            }
            
            // Example 1: 2x upscaling
            let result1 = try await lightx.generateUpscaleWithValidation(
                imageData: imageData,
                quality: 2, // 2x upscaling
                contentType: "image/jpeg"
            )
            print("🎉 2x upscaling result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: 4x upscaling (if image is small enough)
            let dimensions = lightx.getImageDimensions(imageData: imageData)
            let qualityRecommendation = lightx.getRecommendedQuality(width: dimensions.width, height: dimensions.height)
            
            if let available = qualityRecommendation["available"] as? [Int], available.contains(4) {
                let result2 = try await lightx.generateUpscaleWithValidation(
                    imageData: imageData,
                    quality: 4, // 4x upscaling
                    contentType: "image/jpeg"
                )
                print("🎉 4x upscaling result:")
                print("Order ID: \(result2.orderId)")
                print("Status: \(result2.status)")
                if let output = result2.output {
                    print("Output: \(output)")
                }
            } else {
                if let reason = qualityRecommendation["reason"] as? String {
                    print("⚠️  4x upscaling not available for this image size: \(reason)")
                }
            }
            
            // Example 3: Try different quality settings
            let qualityOptions = [2, 4]
            for quality in qualityOptions {
                do {
                    let result = try await lightx.generateUpscaleWithValidation(
                        imageData: imageData,
                        quality: quality,
                        contentType: "image/jpeg"
                    )
                    print("🎉 \(quality)x upscaling result:")
                    print("Order ID: \(result.orderId)")
                    print("Status: \(result.status)")
                    if let output = result.output {
                        print("Output: \(output)")
                    }
                } catch {
                    print("❌ \(quality)x upscaling failed: \(error.localizedDescription)")
                }
            }
            
            // Example 4: Get image dimensions and recommendations
            let finalDimensions = lightx.getImageDimensions(imageData: imageData)
            if finalDimensions.width > 0 && finalDimensions.height > 0 {
                print("📏 Original image: \(finalDimensions.width)x\(finalDimensions.height)")
                let recommendation = lightx.getRecommendedQuality(width: finalDimensions.width, height: finalDimensions.height)
                if let recommended = recommendation["recommended"] as? Int,
                   let reason = recommendation["reason"] as? String {
                    print("💡 Recommended quality: \(recommended)x (\(reason))")
                }
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

struct UpscalerView: View {
    @StateObject private var upscalerAPI = LightXImageUpscalerAPI(apiKey: "YOUR_API_KEY_HERE")
    @State private var selectedImage: UIImage?
    @State private var selectedQuality: Int = 2
    @State private var isProcessing = false
    @State private var result: UpscalerOrderStatusBody?
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 20) {
            Text("LightX Image Upscaler")
                .font(.largeTitle)
                .fontWeight(.bold)
            
            if let image = selectedImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxHeight: 200)
                    .cornerRadius(10)
            }
            
            Picker("Quality", selection: $selectedQuality) {
                Text("2x").tag(2)
                Text("4x").tag(4)
            }
            .pickerStyle(SegmentedPickerStyle())
            
            Button("Select Image") {
                // Image picker logic here
            }
            .buttonStyle(.borderedProminent)
            
            Button("Upscale Image") {
                Task {
                    await upscaleImage()
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedImage == nil || isProcessing)
            
            if isProcessing {
                ProgressView("Processing...")
            }
            
            if let result = result {
                VStack {
                    Text("✅ Upscaling Complete!")
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
    
    private func upscaleImage() async {
        guard let image = selectedImage else { return }
        
        isProcessing = true
        errorMessage = nil
        result = nil
        
        do {
            let imageData = image.jpegData(compressionQuality: 0.8) ?? Data()
            let upscaleResult = try await upscalerAPI.generateUpscaleWithValidation(
                imageData: imageData,
                quality: selectedQuality,
                contentType: "image/jpeg"
            )
            result = upscaleResult
        } catch {
            errorMessage = error.localizedDescription
        }
        
        isProcessing = false
    }
}
