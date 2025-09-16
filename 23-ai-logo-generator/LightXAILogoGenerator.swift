/**
 * LightX AI Logo Generator API Integration - Swift
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered logo generation functionality.
 */

import Foundation

// MARK: - Data Models

struct LogoGenerationResponse: Codable {
    let statusCode: Int
    let message: String
    let body: LogoGenerationBody
}

struct LogoGenerationBody: Codable {
    let orderId: String
    let maxRetriesAllowed: Int
    let avgResponseTimeInSec: Int
    let status: String
}

struct LogoOrderStatusResponse: Codable {
    let statusCode: Int
    let message: String
    let body: LogoOrderStatusBody
}

struct LogoOrderStatusBody: Codable {
    let orderId: String
    let status: String
    let output: String?
}

// MARK: - LightX AI Logo Generator API Client

@MainActor
class LightXAILogoGeneratorAPI: ObservableObject {
    
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
     * Generate AI logo
     * @param textPrompt Text prompt for logo description
     * @param enhancePrompt Whether to enhance the prompt (default: true)
     * @return Order ID for tracking
     */
    func generateLogo(textPrompt: String, enhancePrompt: Bool = true) async throws -> String {
        let endpoint = "\(baseURL)/v2/logo-generator"
        
        let requestBody = [
            "textPrompt": textPrompt,
            "enhancePrompt": enhancePrompt
        ] as [String: Any]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXLogoGeneratorError.networkError
        }
        
        let logoResponse = try JSONDecoder().decode(LogoGenerationResponse.self, from: data)
        
        if logoResponse.statusCode != 2000 {
            throw LightXLogoGeneratorError.apiError(logoResponse.message)
        }
        
        let orderInfo = logoResponse.body
        
        print("📋 Order created: \(orderInfo.orderId)")
        print("🔄 Max retries allowed: \(orderInfo.maxRetriesAllowed)")
        print("⏱️  Average response time: \(orderInfo.avgResponseTimeInSec) seconds")
        print("📊 Status: \(orderInfo.status)")
        print("🎨 Logo prompt: \"\(textPrompt)\"")
        print("✨ Enhanced prompt: \(enhancePrompt ? "Yes" : "No")")
        
        return orderInfo.orderId
    }
    
    /**
     * Check order status
     * @param orderId Order ID to check
     * @return Order status and results
     */
    func checkOrderStatus(orderId: String) async throws -> LogoOrderStatusBody {
        let endpoint = "\(baseURL)/v2/order-status"
        
        let requestBody = ["orderId": orderId]
        
        let request = try createRequest(url: endpoint, method: "POST", body: requestBody)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw LightXLogoGeneratorError.networkError
        }
        
        let statusResponse = try JSONDecoder().decode(LogoOrderStatusResponse.self, from: data)
        
        if statusResponse.statusCode != 2000 {
            throw LightXLogoGeneratorError.apiError(statusResponse.message)
        }
        
        return statusResponse.body
    }
    
    /**
     * Wait for order completion with automatic retries
     * @param orderId Order ID to monitor
     * @return Final result with output URL
     */
    func waitForCompletion(orderId: String) async throws -> LogoOrderStatusBody {
        var attempts = 0
        
        while attempts < maxRetries {
            do {
                let status = try await checkOrderStatus(orderId: orderId)
                
                print("🔄 Attempt \(attempts + 1): Status - \(status.status)")
                
                switch status.status {
                case "active":
                    print("✅ AI logo generated successfully!")
                    if let output = status.output {
                        print("🎨 Logo output: \(output)")
                    }
                    return status
                    
                case "failed":
                    throw LightXLogoGeneratorError.generationFailed
                    
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
        
        throw LightXLogoGeneratorError.maxRetriesReached
    }
    
    /**
     * Complete workflow: Generate AI logo and wait for completion
     * @param textPrompt Text prompt for logo description
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    func processLogo(textPrompt: String, enhancePrompt: Bool = true) async throws -> LogoOrderStatusBody {
        print("🚀 Starting LightX AI Logo Generator API workflow...")
        
        // Step 1: Generate logo
        print("🎨 Generating AI logo...")
        let orderId = try await generateLogo(textPrompt: textPrompt, enhancePrompt: enhancePrompt)
        
        // Step 2: Wait for completion
        print("⏳ Waiting for processing to complete...")
        let result = try await waitForCompletion(orderId: orderId)
        
        return result
    }
    
    /**
     * Get logo prompt examples
     * @return Object containing prompt examples
     */
    func getLogoPromptExamples() -> [String: [String]] {
        let promptExamples = [
            "gaming": [
                "Minimal monoline fox logo, vector, flat",
                "Gaming channel logo with controller and neon effects",
                "Retro gaming logo with pixel art style",
                "Esports team logo with bold typography",
                "Gaming studio logo with futuristic design"
            ],
            "business": [
                "Professional tech company logo, modern, clean",
                "Corporate law firm logo with elegant typography",
                "Financial services logo with trust and stability",
                "Consulting firm logo with professional appearance",
                "Real estate company logo with building elements"
            ],
            "food": [
                "Restaurant logo with chef hat and elegant typography",
                "Coffee shop logo with coffee bean and warm colors",
                "Bakery logo with bread and vintage style",
                "Pizza place logo with pizza slice and fun design",
                "Food truck logo with bold, appetizing colors"
            ],
            "fashion": [
                "Fashion brand logo with elegant, minimalist design",
                "Luxury clothing logo with sophisticated typography",
                "Streetwear brand logo with urban, edgy style",
                "Jewelry brand logo with elegant, refined design",
                "Beauty brand logo with modern, clean aesthetics"
            ],
            "tech": [
                "Tech startup logo with modern, innovative design",
                "Software company logo with code and digital elements",
                "AI company logo with futuristic, smart design",
                "Mobile app logo with clean, user-friendly style",
                "Cybersecurity logo with shield and protection theme"
            ],
            "creative": [
                "Design agency logo with creative, artistic elements",
                "Photography studio logo with camera and lens",
                "Art gallery logo with brush strokes and colors",
                "Music label logo with sound waves and rhythm",
                "Film production logo with cinematic elements"
            ]
        ]
        
        print("💡 Logo Prompt Examples:")
        for (category, exampleList) in promptExamples {
            print("\(category): \(exampleList)")
        }
        
        return promptExamples
    }
    
    /**
     * Get logo design tips and best practices
     * @return Object containing tips for better results
     */
    func getLogoDesignTips() -> [String: [String]] {
        let tips = [
            "text_prompts": [
                "Be specific about the industry or business type",
                "Include style preferences (minimal, modern, vintage, etc.)",
                "Mention color schemes or mood you want to achieve",
                "Specify any symbols or elements you want included",
                "Include target audience or brand personality"
            ],
            "logo_types": [
                "Wordmark: Focus on typography and text styling",
                "Symbol: Emphasize icon or graphic elements",
                "Combination: Include both text and symbol elements",
                "Emblem: Create a badge or seal-style design",
                "Abstract: Use geometric shapes and modern forms"
            ],
            "industry_specific": [
                "Tech: Use modern, clean, and innovative elements",
                "Healthcare: Focus on trust, care, and professionalism",
                "Finance: Emphasize stability, security, and reliability",
                "Food: Use appetizing colors and food-related elements",
                "Creative: Show artistic flair and unique personality"
            ],
            "general": [
                "AI logo generation works best with clear, descriptive prompts",
                "Results are delivered in 1024x1024 JPEG format",
                "Allow 15-30 seconds for processing",
                "Enhanced prompts provide richer, more detailed results",
                "Experiment with different styles for various applications"
            ]
        ]
        
        print("💡 Logo Design Tips:")
        for (category, tipList) in tips {
            print("\(category): \(tipList)")
        }
        
        return tips
    }
    
    /**
     * Get logo use cases and examples
     * @return Object containing use case examples
     */
    func getLogoUseCases() -> [String: [String]] {
        let useCases = [
            "business": [
                "Create company logos for startups and enterprises",
                "Design brand identities for new businesses",
                "Generate logos for product launches",
                "Create professional business cards and letterheads",
                "Design trade show and marketing materials"
            ],
            "personal": [
                "Create personal branding logos",
                "Design logos for freelance services",
                "Generate logos for personal projects",
                "Create logos for social media profiles",
                "Design logos for personal websites"
            ],
            "creative": [
                "Generate logos for creative agencies",
                "Design logos for artists and designers",
                "Create logos for events and festivals",
                "Generate logos for publications and blogs",
                "Design logos for creative projects"
            ],
            "gaming": [
                "Create gaming channel logos",
                "Design esports team logos",
                "Generate gaming studio logos",
                "Create tournament and event logos",
                "Design gaming merchandise logos"
            ],
            "education": [
                "Create logos for educational institutions",
                "Design logos for online courses",
                "Generate logos for training programs",
                "Create logos for educational events",
                "Design logos for student organizations"
            ]
        ]
        
        print("💡 Logo Use Cases:")
        for (category, useCaseList) in useCases {
            print("\(category): \(useCaseList)")
        }
        
        return useCases
    }
    
    /**
     * Get logo style suggestions
     * @return Object containing style suggestions
     */
    func getLogoStyleSuggestions() -> [String: [String]] {
        let styleSuggestions = [
            "minimal": [
                "Clean, simple designs with minimal elements",
                "Focus on typography and negative space",
                "Use limited color palettes",
                "Emphasize clarity and readability",
                "Perfect for modern, professional brands"
            ],
            "vintage": [
                "Classic, timeless designs with retro elements",
                "Use traditional typography and classic symbols",
                "Incorporate aged or weathered effects",
                "Focus on heritage and tradition",
                "Great for established, traditional businesses"
            ],
            "modern": [
                "Contemporary designs with current trends",
                "Use bold typography and geometric shapes",
                "Incorporate technology and innovation",
                "Focus on forward-thinking and progress",
                "Perfect for tech and startup companies"
            ],
            "playful": [
                "Fun, energetic designs with personality",
                "Use bright colors and creative elements",
                "Incorporate humor and whimsy",
                "Focus on approachability and friendliness",
                "Great for entertainment and creative brands"
            ],
            "elegant": [
                "Sophisticated designs with refined aesthetics",
                "Use premium typography and luxury elements",
                "Incorporate subtle details and craftsmanship",
                "Focus on quality and exclusivity",
                "Perfect for luxury and high-end brands"
            ]
        ]
        
        print("💡 Logo Style Suggestions:")
        for (style, suggestionList) in styleSuggestions {
            print("\(style): \(suggestionList)")
        }
        
        return styleSuggestions
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
     * Generate logo with validation
     * @param textPrompt Text prompt for logo description
     * @param enhancePrompt Whether to enhance the prompt
     * @return Final result with output URL
     */
    func generateLogoWithValidation(textPrompt: String, enhancePrompt: Bool = true) async throws -> LogoOrderStatusBody {
        if !validateTextPrompt(textPrompt) {
            throw LightXLogoGeneratorError.invalidPrompt
        }
        
        return try await processLogo(textPrompt: textPrompt, enhancePrompt: enhancePrompt)
    }
    
    // MARK: - Private Methods
    
    private func createRequest(url: String, method: String, body: [String: Any]) throws -> URLRequest {
        guard let url = URL(string: url) else {
            throw LightXLogoGeneratorError.invalidURL
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

enum LightXLogoGeneratorError: Error, LocalizedError {
    case networkError
    case apiError(String)
    case generationFailed
    case maxRetriesReached
    case invalidURL
    case invalidPrompt
    
    var errorDescription: String? {
        switch self {
        case .networkError:
            return "Network error occurred"
        case .apiError(let message):
            return "API error: \(message)"
        case .generationFailed:
            return "AI logo generation failed"
        case .maxRetriesReached:
            return "Maximum retry attempts reached"
        case .invalidURL:
            return "Invalid URL"
        case .invalidPrompt:
            return "Invalid text prompt"
        }
    }
}

// MARK: - Example Usage

@main
struct AILogoGeneratorExample {
    static func main() async {
        do {
            // Initialize with your API key
            let lightx = LightXAILogoGeneratorAPI(apiKey: "YOUR_API_KEY_HERE")
            
            // Get tips and examples
            lightx.getLogoPromptExamples()
            lightx.getLogoDesignTips()
            lightx.getLogoUseCases()
            lightx.getLogoStyleSuggestions()
            
            // Example 1: Gaming logo
            let result1 = try await lightx.generateLogoWithValidation(
                textPrompt: "Minimal monoline fox logo, vector, flat",
                enhancePrompt: true
            )
            print("🎉 Gaming logo result:")
            print("Order ID: \(result1.orderId)")
            print("Status: \(result1.status)")
            if let output = result1.output {
                print("Output: \(output)")
            }
            
            // Example 2: Business logo
            let result2 = try await lightx.generateLogoWithValidation(
                textPrompt: "Professional tech company logo, modern, clean",
                enhancePrompt: true
            )
            print("🎉 Business logo result:")
            print("Order ID: \(result2.orderId)")
            print("Status: \(result2.status)")
            if let output = result2.output {
                print("Output: \(output)")
            }
            
            // Example 3: Try different logo types
            let logoTypes = [
                "Restaurant logo with chef hat and elegant typography",
                "Fashion brand logo with elegant, minimalist design",
                "Tech startup logo with modern, innovative design",
                "Coffee shop logo with coffee bean and warm colors",
                "Design agency logo with creative, artistic elements"
            ]
            
            for logoPrompt in logoTypes {
                let result = try await lightx.generateLogoWithValidation(
                    textPrompt: logoPrompt,
                    enhancePrompt: true
                )
                print("🎉 \(logoPrompt) result:")
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
