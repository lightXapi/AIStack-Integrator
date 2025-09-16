/**
 * LightX AI Design API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered design generation functionality.
 */

import Foundation

// MARK: - Data Models

struct DesignGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: DesignGenerationBody
}

struct DesignGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct DesignOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: DesignOrderStatusBody
}

struct DesignOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX AI Design API Client

@MainActor
class LightXAIDesignAPI: ObservableObject {
    
    // MARK: - Properties
    
    private let apiKey: String
    private let baseURL = "https://api.lightxeditor.com/external/api"
    private let maxRetries = 5
    private let retryInterval: TimeInterval = 3.0
    
    // MARK: - Initialization
    
    init(apiKey: String) {
        self.apiKey = apiKey
    }
    
    // MARK: - Public Methods
    
    /**
     * Generate AI design
     * @param textPrompt Text prompt for design description
     * @param resolution Design resolution (1:1, 9:16, 3:4, 2:3, 16:9, 4:3)
     * @param enhancePrompt Whether to enhance the prompt (default: true)
     * @return Order ID for tracking
     */
    func generateDesign(textPrompt: String, resolution: String = "1:1", enhancePrompt: Bool = true) async throws -> String {
        let endpoint = "\(baseURL)/v2/ai-design"
        
        let requestBody = [
            "textPrompt": textPrompt,
            "resolution": resolution,
            "enhancePrompt": enhancePrompt
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXDesignError.networkError
        }
        
        let designResponse = try JSONDecoder().decode(DesignGenerationResponse.self, from: data)
        
        if designResponse.statusCode != 2000 {
            throw LightXDesignError.apiError(designResponse.message)
        }
        
        let orderInfo = designResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🎨 Design prompt: \"\(textPrompt)\"")
        print("📐 Resolution: \(resolution)")
        print("✨ Enhanced prompt: \(enhancePrompt ? "Yes" : "No")")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> DesignOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXDesignError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(DesignOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXDesignError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> DesignOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ AI design generated successfully!")
                    if let output = status.output {
                        print("🎨 Design output: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXDesignError.generationFailed
                    
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
        
        throw LightXDesignError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Generate AI design and wait for completion
     * @param textPrompt Text prompt for design description
     * @param resolution Design resolution
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    func processDesign(textPrompt: String, resolution: String = "1:1", enhancePrompt: Bool = true) async throws -> DesignOrderStatusBody {
        print("🚀 Starting LightX AI Design API workflow...")
        
        // Step 1: Generate design
        print("🎨 Generating AI design...")
        let orderId = try await generateDesign(textPrompt: textPrompt, resolution: resolution, enhancePrompt: enhancePrompt)
        
        // Step 2: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get supported resolutions
     * @return Object containing supported resolutions
     */
    func getSupportedResolutions() -> [String: [String: String]] {
        let resolutions = [
            "1:1": [
                "name": "Square",
                "dimensions": "512 × 512",
                "description": "Perfect for social media posts, profile pictures, and square designs"
            ],
            "9:16": [
                "name": "Portrait (9:16)",
                "dimensions": "289 × 512",
                "description": "Ideal for mobile stories, vertical videos, and tall designs"
            ],
            "3:4": [
                "name": "Portrait (3:4)",
                "dimensions": "386 × 512",
                "description": "Great for portrait photos, magazine covers, and vertical layouts"
            ],
            "2:3": [
                "name": "Portrait (2:3)",
                "dimensions": "341 × 512",
                "description": "Perfect for posters, flyers, and portrait-oriented designs"
            ],
            "16:9": [
                "name": "Landscape (16:9)",
                "dimensions": "512 × 289",
                "description": "Ideal for banners, presentations, and widescreen designs"
            ],
            "4:3": [
                "name": "Landscape (4:3)",
                "dimensions": "512 × 386",
                "description": "Great for traditional photos, presentations, and landscape layouts"
            ]
        ]
        
        print("📐 Supported Resolutions:")
        for (ratio, info) in resolutions {
            print("\(ratio): \(info["name"]!) (\(info["dimensions"]!)) - \(info["description"]!)")
        }
        
        return resolutions
    }
    
    /**
     * Get design prompt examples
     * @return Object containing prompt examples
     */
    func getDesignPromptExamples() -> [String: [String]] {
        let promptExamples = [
            "birthday_cards": [
                "BIRTHDAY CARD INVITATION with balloons and confetti",
                "Elegant birthday card with cake and candles",
                "Fun birthday invitation with party decorations",
                "Modern birthday card with geometric patterns",
                "Vintage birthday invitation with floral design"
            ],
            "posters": [
                "CONCERT POSTER with bold typography and neon colors",
                "Movie poster with dramatic lighting and action",
                "Event poster with modern minimalist design",
                "Festival poster with vibrant colors and patterns",
                "Art exhibition poster with creative typography"
            ],
            "flyers": [
                "RESTAURANT FLYER with appetizing food photos",
                "Gym membership flyer with fitness motivation",
                "Sale flyer with discount offers and prices",
                "Workshop flyer with educational theme",
                "Product launch flyer with modern design"
            ],
            "banners": [
                "WEBSITE BANNER with call-to-action button",
                "Social media banner with brand colors",
                "Advertisement banner with product showcase",
                "Event banner with date and location",
                "Promotional banner with special offers"
            ],
            "invitations": [
                "WEDDING INVITATION with elegant typography",
                "Party invitation with fun graphics",
                "Corporate event invitation with professional design",
                "Holiday party invitation with festive theme",
                "Anniversary invitation with romantic elements"
            ],
            "packaging": [
                "PRODUCT PACKAGING with modern minimalist design",
                "Food packaging with appetizing visuals",
                "Cosmetic packaging with luxury aesthetic",
                "Tech product packaging with sleek design",
                "Gift box packaging with premium feel"
            ]
        ]
        
        print("💡 Design Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        
        return promptExamples
    }
    
    /**
     * Get design tips and best practices
     * @return Object containing tips for better results
     */
    func getDesignTips() -> [String: [String]] {
        let tips = [
            "text_prompts": [
                "Be specific about the design type (poster, card, banner, etc.)",
                "Include style preferences (modern, vintage, minimalist, etc.)",
                "Mention color schemes or mood you want to achieve",
                "Specify any text or typography requirements",
                "Include target audience or purpose of the design"
            ],
            "resolution_selection": [
                "Choose 1:1 for social media posts and profile pictures",
                "Use 9:16 for mobile stories and vertical content",
                "Select 2:3 for posters, flyers, and print materials",
                "Pick 16:9 for banners, presentations, and web headers",
                "Consider 4:3 for traditional photos and documents"
            ],
            "prompt_enhancement": [
                "Enable enhancePrompt for better, more detailed results",
                "Use enhancePrompt when you want richer visual elements",
                "Disable enhancePrompt for exact prompt interpretation",
                "Enhanced prompts work well for creative designs",
                "Basic prompts are better for simple, clean designs"
            ],
            "general": [
                "AI design works best with clear, descriptive prompts",
                "Results may vary based on prompt complexity and style",
                "Allow 15-30 seconds for processing",
                "Experiment with different resolutions for various use cases",
                "Combine text prompts with resolution for optimal results"
            ]
        ]
        
        print("💡 AI Design Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        
        return tips
    }
    
    /**
     * Get design use cases and examples
     * @return Object containing use case examples
     */
    func getDesignUseCases() -> [String: [String]] {
        let useCases = [
            "marketing": [
                "Create promotional posters and banners",
                "Generate social media content and ads",
                "Design product packaging and labels",
                "Create event flyers and invitations",
                "Generate website headers and graphics"
            ],
            "personal": [
                "Design birthday cards and invitations",
                "Create holiday greetings and cards",
                "Generate party decorations and themes",
                "Design personal branding materials",
                "Create custom artwork and prints"
            ],
            "business": [
                "Generate corporate presentation slides",
                "Create business cards and letterheads",
                "Design product catalogs and brochures",
                "Generate trade show materials",
                "Create company newsletters and reports"
            ],
            "creative": [
                "Explore artistic design concepts",
                "Generate creative project ideas",
                "Create portfolio pieces and samples",
                "Design book covers and illustrations",
                "Generate art prints and posters"
            ],
            "education": [
                "Create educational posters and charts",
                "Design course materials and handouts",
                "Generate presentation slides and graphics",
                "Create learning aids and visual guides",
                "Design school event materials"
            ]
        ]
        
        print("💡 Design Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        
        return useCases
    }
    
    /**
     * Validate text prompt (utility function)
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
     * Validate resolution (utility function)
     * @param resolution Resolution to validate
     * @return Whether the resolution is valid
     */
    func validateResolution(_ resolution: String) -> Bool {
        let validResolutions = ["1:1", "9:16", "3:4", "2:3", "16:9", "4:3"]
        
        if !validResolutions.contains(resolution) {
            print("❌ Invalid resolution. Valid options: \(validResolutions.joined(separator: ", "))")
            return false
        }
        
        print("✅ Resolution is valid")
        return true
    }
    
    /**
     * Generate design with validation
     * @param textPrompt Text prompt for design description
     * @param resolution Design resolution
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    func generateDesignWithValidation(textPrompt: String, resolution: String = "1:1", enhancePrompt: Bool = true) async throws -> DesignOrderStatusBody {
        if !validateTextPrompt(textPrompt) {
            throw LightXDesignError.invalidPrompt
        }
        
        if !validateResolution(resolution) {
            throw LightXDesignError.invalidResolution
        }
        
        return try await processDesign(textPrompt: textPrompt, resolution: resolution, enhancePrompt: enhancePrompt)
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let url = URL(string: url) else {
            throw LightXDesignError.invalidURL
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
}

// MARK: - Error Types

enum LightXDesignError: Error, LocalizedError {
    case networkError
    case apiError(String)
    case generationFailed
    case maxRetriesReached
    case invalidURL
    case invalidPrompt
    case invalidResolution
    
    var errorDescription: String? {
        switch self {
        case .networkError:
            return "Network error occurred"
        case .apiError(let message):
            return "API error: \(message)"
        case .generationFailed:
            return "AI design generation failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidURL:
            return "Invalid URL"
        case .invalidPrompt:
            return "Invalid text prompt"
        case .invalidResolution:
            return "Invalid resolution"
        }
    }
}

// MARK: - Example Usage

@main
struct AIDesignExample {
    static func main() async {
        do {
            // Initialize with your API key
            let lightx = LightXAIDesignAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips and examples
            lightx.getSupportedResolutions()
            lightx.getDesignPromptExamples()
            lightx.getDesignTips()
            lightx.getDesignUseCases()
            
            // Example 1: Birthday card design
            let result1 = try await lightx.generateDesignWithValidation(
                textPrompt: "BIRTHDAY CARD INVITATION with balloons and confetti",
                resolution: "2:3",
                enhancePrompt: true
            )
            print("🎉 Birthday card design result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Poster design
            let result2 = try await lightx.generateDesignWithValidation(
                textPrompt: "CONCERT POSTER with bold typography and neon colors",
                resolution: "16:9",
                enhancePrompt: true
            )
            print("🎉 Concert poster design result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Try different design types
            let designTypes = [
                ["prompt": "RESTAURANT FLYER with appetizing food photos", "resolution": "2:3"],
                ["prompt": "WEBSITE BANNER with call-to-action button", "resolution": "16:9"],
                ["prompt": "WEDDING INVITATION with elegant typography", "resolution": "3:4"],
                ["prompt": "PRODUCT PACKAGING with modern minimalist design", "resolution": "1:1"],
                ["prompt": "SOCIAL MEDIA POST with vibrant colors", "resolution": "1:1"]
            ]
            
            for design in designTypes {
                let result = try await lightx.generateDesignWithValidation(
                    textPrompt: design["prompt"]!,
                    resolution: design["resolution"]!,
                    enhancePrompt: true
                )
                print("🎉 \(design["prompt"]!) result:")
                print("Order ID: \(result.orderId)")
                print("Status: \(result.status)")
                if let output = result.output {
                    print("Output: \(output)")
                }
            }
            
        } catch {
            print("❌ Example failed: \(error.localizedDescription)")
        }
    }
}
