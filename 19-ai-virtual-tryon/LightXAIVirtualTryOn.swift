/**
 * LightX AI Virtual Outfit Try-On API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered virtual outfit try-on functionality.
 */

import Foundation
import UIKit

// MARK: - Data Models

struct VirtualTryOnUploadImageResponse: Codable {
    let statusCode: Int
    let message: String
    let body: VirtualTryOnUploadImageBody
}

struct VirtualTryOnUploadImageBody: Codable {
    let uploadImage: String
    let imageUrl: String
    let size: Int
}

struct VirtualTryOnGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: VirtualTryOnGenerationBody
}

struct VirtualTryOnGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct VirtualTryOnOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: VirtualTryOnOrderStatusBody
}

struct VirtualTryOnOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX AI Virtual Outfit Try-On API Client

@MainActor
class LightXAIVirtualTryOnAPI: ObservableObject {
    
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
            throw LightXVirtualTryOnError.imageSizeExceeded
        }
        
        // Step 1: Get upload URL
        let uploadURL = try await getUploadURL(fileSize: fileSize, contentType: contentType)
        
        // Step 2: Upload image to S3
        try await uploadToS3(uploadURL: uploadURL.uploadImage, imageData: imageData, contentType: contentType)
        
        print("✅ Image uploaded successfully")
        return uploadURL.imageUrl
    }
    
    /**
     * Try on virtual outfit using AI
     * @param imageUrl URL of the input image (person)
     * @param styleImageUrl URL of the outfit reference image
     * @return Order ID for tracking
     */
    func tryOnOutfit(imageUrl: String, styleImageUrl: String) async throws -> String {
        let endpoint = "\(baseURL)/v2/aivirtualtryon"
        
        let requestBody = [
            "imageUrl": imageUrl,
            "styleImageUrl": styleImageUrl
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXVirtualTryOnError.networkError
        }
        
        let virtualTryOnResponse = try JSONDecoder().decode(VirtualTryOnGenerationResponse.self, from: data)
        
        if virtualTryOnResponse.statusCode != 2000 {
            throw LightXVirtualTryOnError.apiError(virtualTryOnResponse.message)
        }
        
        let orderInfo = virtualTryOnResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("👤 Person image: \(imageUrl)")
        print("👗 Outfit image: \(styleImageUrl)")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> VirtualTryOnOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXVirtualTryOnError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(VirtualTryOnOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXVirtualTryOnError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> VirtualTryOnOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ Virtual outfit try-on completed successfully!")
                    if let output = status.output {
                        print("👗 Virtual try-on result: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXVirtualTryOnError.processingFailed
                    
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
        
        throw LightXVirtualTryOnError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Upload images and try on virtual outfit
     * @param personImageData Person image data
     * @param outfitImageData Outfit reference image data
     * @param contentType MIME type
     * @return Final result with output URL
     */
    func processVirtualTryOn(personImageData: Data, outfitImageData: Data, contentType: String = "image/jpeg") async throws -> VirtualTryOnOrderStatusBody {
        print("🚀 Starting LightX AI Virtual Outfit Try-On API workflow...")
        
        // Step 1: Upload person image
        print("📤 Uploading person image...")
        let personImageUrl = try await uploadImage(imageData: personImageData, contentType: contentType)
        print("✅ Person image uploaded: \(personImageUrl)")
        
        // Step 2: Upload outfit image
        print("📤 Uploading outfit image...")
        let outfitImageUrl = try await uploadImage(imageData: outfitImageData, contentType: contentType)
        print("✅ Outfit image uploaded: \(outfitImageUrl)")
        
        // Step 3: Try on virtual outfit
        print("👗 Trying on virtual outfit...")
        let orderId = try await tryOnOutfit(imageUrl: personImageUrl, styleImageUrl: outfitImageUrl)
        
        // Step 4: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get virtual try-on tips and best practices
     * @return Dictionary containing tips for better results
     */
    func getVirtualTryOnTips() -> [String: [String]] {
        let tips = [
            "person_image": [
                "Use clear, well-lit photos with good body visibility",
                "Ensure the person is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best virtual try-on results",
                "Good lighting helps preserve body shape and details"
            ],
            "outfit_image": [
                "Use clear outfit reference images with good detail",
                "Ensure the outfit is clearly visible and well-lit",
                "Choose outfit images with good color and texture definition",
                "Use high-quality outfit images for better transfer results",
                "Good outfit image quality improves virtual try-on accuracy"
            ],
            "body_visibility": [
                "Ensure the person's body is clearly visible",
                "Avoid images where the person is heavily covered",
                "Use images with good body definition and posture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "outfit_selection": [
                "Choose outfit images that match the person's body type",
                "Select outfits with clear, well-defined shapes",
                "Use outfit images with good color contrast",
                "Ensure outfit images show the complete garment",
                "Choose outfits that complement the person's style"
            ],
            "general": [
                "AI virtual try-on works best with clear, detailed source images",
                "Results may vary based on input image quality and outfit visibility",
                "Virtual try-on preserves body shape and facial features",
                "Allow 15-30 seconds for processing",
                "Experiment with different outfit combinations for varied results"
            ]
        ]
        
        print("💡 Virtual Try-On Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        return tips
    }
    
    /**
     * Get outfit category suggestions
     * @return Dictionary containing outfit category suggestions
     */
    func getOutfitCategorySuggestions() -> [String: [String]] {
        let outfitCategories = [
            "casual": [
                "Casual t-shirts and jeans",
                "Comfortable hoodies and sweatpants",
                "Everyday dresses and skirts",
                "Casual blouses and trousers",
                "Relaxed shirts and shorts"
            ],
            "formal": [
                "Business suits and blazers",
                "Formal dresses and gowns",
                "Dress shirts and dress pants",
                "Professional blouses and skirts",
                "Elegant evening wear"
            ],
            "party": [
                "Party dresses and outfits",
                "Cocktail dresses and suits",
                "Festive clothing and accessories",
                "Celebration wear and costumes",
                "Special occasion outfits"
            ],
            "seasonal": [
                "Summer dresses and shorts",
                "Winter coats and sweaters",
                "Spring jackets and light layers",
                "Fall clothing and warm accessories",
                "Seasonal fashion trends"
            ],
            "sportswear": [
                "Athletic wear and gym clothes",
                "Sports jerseys and team wear",
                "Activewear and workout gear",
                "Running clothes and sneakers",
                "Fitness and sports apparel"
            ]
        ]
        
        print("💡 Outfit Category Suggestions:")
        for (category, suggestionList) in outfitCategories {
            print("\(category): \(suggestionList)")
        }
        return outfitCategories
    }
    
    /**
     * Get virtual try-on use cases and examples
     * @return Dictionary containing use case examples
     */
    func getVirtualTryOnUseCases() -> [String: [String]] {
        let useCases = [
            "e_commerce": [
                "Online shopping virtual try-on",
                "E-commerce product visualization",
                "Online store outfit previews",
                "Virtual fitting room experiences",
                "Online shopping assistance"
            ],
            "fashion_retail": [
                "Fashion store virtual try-on",
                "Retail outfit visualization",
                "In-store virtual fitting",
                "Fashion consultation tools",
                "Retail customer experience"
            ],
            "personal_styling": [
                "Personal style exploration",
                "Virtual wardrobe try-on",
                "Style consultation services",
                "Personal fashion experiments",
                "Individual styling assistance"
            ],
            "social_media": [
                "Social media outfit posts",
                "Fashion influencer content",
                "Style sharing platforms",
                "Fashion community features",
                "Social fashion experiences"
            ],
            "entertainment": [
                "Character outfit changes",
                "Costume design and visualization",
                "Creative outfit concepts",
                "Artistic fashion expressions",
                "Entertainment industry applications"
            ]
        ]
        
        print("💡 Virtual Try-On Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        return useCases
    }
    
    /**
     * Get outfit style suggestions
     * @return Dictionary containing style suggestions
     */
    func getOutfitStyleSuggestions() -> [String: [String]] {
        let styleSuggestions = [
            "classic": [
                "Classic business attire",
                "Traditional formal wear",
                "Timeless casual outfits",
                "Classic evening wear",
                "Traditional professional dress"
            ],
            "modern": [
                "Contemporary fashion trends",
                "Modern casual wear",
                "Current style favorites",
                "Trendy outfit combinations",
                "Modern fashion statements"
            ],
            "vintage": [
                "Retro fashion styles",
                "Vintage clothing pieces",
                "Classic era outfits",
                "Nostalgic fashion trends",
                "Historical style references"
            ],
            "bohemian": [
                "Bohemian style outfits",
                "Free-spirited fashion",
                "Artistic clothing choices",
                "Creative style expressions",
                "Alternative fashion trends"
            ],
            "minimalist": [
                "Simple, clean outfits",
                "Minimalist fashion choices",
                "Understated style pieces",
                "Clean, modern aesthetics",
                "Simple fashion statements"
            ]
        ]
        
        print("💡 Outfit Style Suggestions:")
        for (style, suggestionList) in styleSuggestions {
            print("\(style): \(suggestionList)")
        }
        return styleSuggestions
    }
    
    /**
     * Get virtual try-on best practices
     * @return Dictionary containing best practices
     */
    func getVirtualTryOnBestPractices() -> [String: [String]] {
        let bestPractices = [
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in both images",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined details",
                "Ensure both person and outfit are clearly visible"
            ],
            "outfit_selection": [
                "Choose outfit images that complement the person",
                "Select outfits with clear, well-defined shapes",
                "Use outfit images with good color contrast",
                "Ensure outfit images show the complete garment",
                "Choose outfits that match the person's body type"
            ],
            "body_visibility": [
                "Ensure the person's body is clearly visible",
                "Avoid images where the person is heavily covered",
                "Use images with good body definition and posture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "workflow_optimization": [
                "Batch process multiple outfit combinations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        ]
        
        print("💡 Virtual Try-On Best Practices:")
        for (category, practiceList) in bestPractices {
            print("\(category): \(practiceList)")
        }
        return bestPractices
    }
    
    /**
     * Get virtual try-on performance tips
     * @return Dictionary containing performance tips
     */
    func getVirtualTryOnPerformanceTips() -> [String: [String]] {
        let performanceTips = [
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple outfit combinations"
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
                "Offer outfit previews when possible",
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
     * Get virtual try-on technical specifications
     * @return Dictionary containing technical specifications
     */
    func getVirtualTryOnTechnicalSpecifications() -> [String: [String: Any]] {
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
                "person_detection": "Automatic person detection and body segmentation",
                "outfit_transfer": "Seamless outfit transfer onto person",
                "body_preservation": "Preserves body shape and facial features",
                "realistic_rendering": "Realistic outfit fitting and appearance",
                "output_quality": "High-quality JPEG output"
            ]
        ]
        
        print("💡 Virtual Try-On Technical Specifications:")
        for (category, specs) in specifications {
            print("\(category): \(specs)")
        }
        return specifications
    }
    
    /**
     * Get outfit combination suggestions
     * @return Dictionary containing combination suggestions
     */
    func getOutfitCombinationSuggestions() -> [String: [String]] {
        let combinations = [
            "casual_combinations": [
                "T-shirt with jeans and sneakers",
                "Hoodie with joggers and casual shoes",
                "Blouse with trousers and flats",
                "Sweater with skirt and boots",
                "Polo shirt with shorts and sandals"
            ],
            "formal_combinations": [
                "Blazer with dress pants and dress shoes",
                "Dress shirt with suit and formal shoes",
                "Blouse with pencil skirt and heels",
                "Dress with blazer and pumps",
                "Suit with dress shirt and oxfords"
            ],
            "party_combinations": [
                "Cocktail dress with heels and accessories",
                "Party top with skirt and party shoes",
                "Evening gown with elegant accessories",
                "Festive outfit with matching accessories",
                "Celebration wear with themed accessories"
            ],
            "seasonal_combinations": [
                "Summer dress with sandals and sun hat",
                "Winter coat with boots and scarf",
                "Spring jacket with light layers and sneakers",
                "Fall sweater with jeans and ankle boots",
                "Seasonal outfit with appropriate accessories"
            ]
        ]
        
        print("💡 Outfit Combination Suggestions:")
        for (category, combinationList) in combinations {
            print("\(category): \(combinationList)")
        }
        return combinations
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
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let requestURL = URL(string: url) else {
            throw LightXVirtualTryOnError.invalidURL
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
    
    private func getUploadURL(fileSize: Int, contentType: String) async throws -> VirtualTryOnUploadImageBody {
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
            throw LightXVirtualTryOnError.networkError
        }
        
        let uploadResponse = try JSONDecoder().decode(VirtualTryOnUploadImageResponse.self, from: data)
        
        if uploadResponse.statusCode != 2000 {
            throw LightXVirtualTryOnError.apiError(uploadResponse.message)
        }
        
        return uploadResponse.body
    }
    
    private func uploadToS3(uploadURL: String, imageData: Data, contentType: String) async throws {
        guard let url = URL(string: uploadURL) else {
            throw LightXVirtualTryOnError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = imageData
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXVirtualTryOnError.uploadFailed
        }
    }
}

// MARK: - Error Types

enum LightXVirtualTryOnError: Error, LocalizedError {
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
            return "Virtual outfit try-on failed"
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

struct LightXVirtualTryOnExample {
    
    static func runExample() async {
        do {
            // Initialize with your API key
            let lightx = LightXAIVirtualTryOnAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips for better results
            lightx.getVirtualTryOnTips()
            lightx.getOutfitCategorySuggestions()
            lightx.getVirtualTryOnUseCases()
            lightx.getOutfitStyleSuggestions()
            lightx.getVirtualTryOnBestPractices()
            lightx.getVirtualTryOnPerformanceTips()
            lightx.getVirtualTryOnTechnicalSpecifications()
            lightx.getOutfitCombinationSuggestions()
            
            // Load images (replace with your image loading logic)
            guard let personImageData = loadPersonImageData() else {
                print("❌ Failed to load person image data")
                return
            }
            
            guard let outfitImageData = loadOutfitImageData() else {
                print("❌ Failed to load outfit image data")
                return
            }
            
            // Example 1: Casual outfit try-on
            let result1 = try await lightx.processVirtualTryOn(
                personImageData: personImageData,
                outfitImageData: outfitImageData,
                contentType: "image/jpeg"
            )
            print("🎉 Casual outfit try-on result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Try different outfit combinations
            let outfitCombinations = [
                "casual-outfit.jpg",
                "formal-outfit.jpg",
                "party-outfit.jpg",
                "sportswear-outfit.jpg",
                "seasonal-outfit.jpg"
            ]
            
            for outfitName in outfitCombinations {
                if let outfitData = loadOutfitImageData(named: outfitName) {
                    let result = try await lightx.processVirtualTryOn(
                        personImageData: personImageData,
                        outfitImageData: outfitData,
                        contentType: "image/jpeg"
                    )
                    print("🎉 \(outfitName) try-on result:")
                    print("Order ID: \(result.orderId)")
                    print("Status: \(result.status)")
                    if let output = result.output {
                        print("Output: \(output)")
                    }
                }
            }
            
            // Example 3: Get image dimensions
            let personDimensions = lightx.getImageDimensions(imageData: personImageData)
            let outfitDimensions = lightx.getImageDimensions(imageData: outfitImageData)
            
            if personDimensions.width > 0 && personDimensions.height > 0 {
                print("📏 Person image: \(personDimensions.width)x\(personDimensions.height)")
            }
            if outfitDimensions.width > 0 && outfitDimensions.height > 0 {
                print("📏 Outfit image: \(outfitDimensions.width)x\(outfitDimensions.height)")
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
    
    private static func loadPersonImageData() -> Data? {
        // Replace with your image loading logic
        // This is a placeholder - you would load from bundle, documents, or camera
        return nil
    }
    
    private static func loadOutfitImageData() -> Data? {
        // Replace with your outfit image loading logic
        // This is a placeholder - you would load from bundle, documents, or camera
        return nil
    }
    
    private static func loadOutfitImageData(named: String) -> Data? {
        // Replace with your outfit image loading logic
        // This is a placeholder - you would load from bundle, documents, or camera
        return nil
    }
}

// MARK: - SwiftUI Integration Example

import SwiftUI

struct VirtualTryOnView: View {
    @StateObject private var virtualTryOnAPI = LightXAIVirtualTryOnAPI(apiKey: "YOUR_API_KEY_HERE")
    @State private var selectedPersonImage: UIImage?
    @State private var selectedOutfitImage: UIImage?
    @State private var isProcessing = false
    @State private var result: VirtualTryOnOrderStatusBody?
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 20) {
            Text("LightX AI Virtual Outfit Try-On")
                .font(.largeTitle)
                .fontWeight(.bold)
            
            if let personImage = selectedPersonImage {
                Image(uiImage: personImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxHeight: 200)
                    .cornerRadius(10)
                    .overlay(
                        Text("Person")
                            .font(.caption)
                            .padding(4)
                            .background(Color.black.opacity(0.7))
                            .foregroundColor(.white)
                            .cornerRadius(4),
                        alignment: .topLeading
                    )
            }
            
            if let outfitImage = selectedOutfitImage {
                Image(uiImage: outfitImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxHeight: 200)
                    .cornerRadius(10)
                    .overlay(
                        Text("Outfit")
                            .font(.caption)
                            .padding(4)
                            .background(Color.black.opacity(0.7))
                            .foregroundColor(.white)
                            .cornerRadius(4),
                        alignment: .topLeading
                    )
            }
            
            HStack {
                Button("Select Person Image") {
                    // Person image picker logic here
                }
                .buttonStyle(.borderedProminent)
                
                Button("Select Outfit Image") {
                    // Outfit image picker logic here
                }
                .buttonStyle(.bordered)
            }
            
            Button("Try On Outfit") {
                Task {
                    await tryOnOutfit()
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedPersonImage == nil || selectedOutfitImage == nil || isProcessing)
            
            if isProcessing {
                ProgressView("Processing...")
            }
            
            if let result = result {
                VStack {
                    Text("✅ Virtual Try-On Complete!")
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
    
    private func tryOnOutfit() async {
        guard let personImage = selectedPersonImage,
              let outfitImage = selectedOutfitImage else { return }
        
        isProcessing = true
        errorMessage = nil
        result = nil
        
        do {
            let personImageData = personImage.jpegData(compressionQuality: 0.8) ?? Data()
            let outfitImageData = outfitImage.jpegData(compressionQuality: 0.8) ?? Data()
            
            let tryOnResult = try await virtualTryOnAPI.processVirtualTryOn(
                personImageData: personImageData,
                outfitImageData: outfitImageData,
                contentType: "image/jpeg"
            )
            result = tryOnResult
        } catch {
            errorMessage = error.localizedDescription
        }
        
        isProcessing = false
    }
}
