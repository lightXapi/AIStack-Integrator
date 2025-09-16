/**
 * LightX AI Logo Generator API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered logo generation functionality.
 */

const axios = require('axios');

class LightXAILogoGeneratorAPI {
    constructor(apiKey) {
        this.apiKey = apiKey;
        this.baseURL = 'https://api.lightxeditor.com/external/api';
        this.maxRetries = 5;
        this.retryInterval = 3000; // 3 seconds
    }

    /**
     * Generate AI logo
     * @param {string} textPrompt - Text prompt for logo description
     * @param {boolean} enhancePrompt - Whether to enhance the prompt (default: true)
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateLogo(textPrompt, enhancePrompt = true) {
        const endpoint = `${this.baseURL}/v2/logo-generator`;

        const payload = {
            textPrompt: textPrompt,
            enhancePrompt: enhancePrompt
        };

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Logo request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`🎨 Logo prompt: "${textPrompt}"`);
            console.log(`✨ Enhanced prompt: ${enhancePrompt ? 'Yes' : 'No'}`);

            return orderInfo.orderId;

        } catch (error) {
            if (error.response) {
                throw new Error(`Network error: ${error.response.status} - ${error.response.statusText}`);
            }
            throw error;
        }
    }

    /**
     * Check order status
     * @param {string} orderId - Order ID to check
     * @returns {Promise<Object>} Order status and results
     */
    async checkOrderStatus(orderId) {
        const endpoint = `${this.baseURL}/v2/order-status`;

        try {
            const response = await axios.post(endpoint, { orderId }, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Status check failed: ${response.data.message}`);
            }

            return response.data.body;

        } catch (error) {
            if (error.response) {
                throw new Error(`Network error: ${error.response.status} - ${error.response.statusText}`);
            }
            throw error;
        }
    }

    /**
     * Wait for order completion with automatic retries
     * @param {string} orderId - Order ID to monitor
     * @returns {Promise<Object>} Final result with output URL
     */
    async waitForCompletion(orderId) {
        let attempts = 0;

        while (attempts < this.maxRetries) {
            try {
                const status = await this.checkOrderStatus(orderId);

                console.log(`🔄 Attempt ${attempts + 1}: Status - ${status.status}`);

                switch (status.status) {
                    case 'active':
                        console.log('✅ AI logo generated successfully!');
                        if (status.output) {
                            console.log(`🎨 Logo output: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('AI logo generation failed');

                    case 'init':
                        attempts++;
                        if (attempts < this.maxRetries) {
                            console.log(`⏳ Waiting ${this.retryInterval / 1000} seconds before next check...`);
                            await this.sleep(this.retryInterval);
                        }
                        break;

                    default:
                        attempts++;
                        if (attempts < this.maxRetries) {
                            await this.sleep(this.retryInterval);
                        }
                        break;
                }

            } catch (error) {
                attempts++;
                if (attempts >= this.maxRetries) {
                    throw error;
                }
                console.log(`⚠️  Error on attempt ${attempts}, retrying...`);
                await this.sleep(this.retryInterval);
            }
        }

        throw new Error('Maximum retry attempts reached');
    }

    /**
     * Complete workflow: Generate AI logo and wait for completion
     * @param {string} textPrompt - Text prompt for logo description
     * @param {boolean} enhancePrompt - Whether to enhance the prompt
     * @returns {Promise<Object>} Final result with output URL
     */
    async processLogo(textPrompt, enhancePrompt = true) {
        console.log('🚀 Starting LightX AI Logo Generator API workflow...');

        // Step 1: Generate logo
        console.log('🎨 Generating AI logo...');
        const orderId = await this.generateLogo(textPrompt, enhancePrompt);

        // Step 2: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get logo prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getLogoPromptExamples() {
        const promptExamples = {
            gaming: [
                'Minimal monoline fox logo, vector, flat',
                'Gaming channel logo with controller and neon effects',
                'Retro gaming logo with pixel art style',
                'Esports team logo with bold typography',
                'Gaming studio logo with futuristic design'
            ],
            business: [
                'Professional tech company logo, modern, clean',
                'Corporate law firm logo with elegant typography',
                'Financial services logo with trust and stability',
                'Consulting firm logo with professional appearance',
                'Real estate company logo with building elements'
            ],
            food: [
                'Restaurant logo with chef hat and elegant typography',
                'Coffee shop logo with coffee bean and warm colors',
                'Bakery logo with bread and vintage style',
                'Pizza place logo with pizza slice and fun design',
                'Food truck logo with bold, appetizing colors'
            ],
            fashion: [
                'Fashion brand logo with elegant, minimalist design',
                'Luxury clothing logo with sophisticated typography',
                'Streetwear brand logo with urban, edgy style',
                'Jewelry brand logo with elegant, refined design',
                'Beauty brand logo with modern, clean aesthetics'
            ],
            tech: [
                'Tech startup logo with modern, innovative design',
                'Software company logo with code and digital elements',
                'AI company logo with futuristic, smart design',
                'Mobile app logo with clean, user-friendly style',
                'Cybersecurity logo with shield and protection theme'
            ],
            creative: [
                'Design agency logo with creative, artistic elements',
                'Photography studio logo with camera and lens',
                'Art gallery logo with brush strokes and colors',
                'Music label logo with sound waves and rhythm',
                'Film production logo with cinematic elements'
            ]
        };

        console.log('💡 Logo Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get logo design tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getLogoDesignTips() {
        const tips = {
            text_prompts: [
                'Be specific about the industry or business type',
                'Include style preferences (minimal, modern, vintage, etc.)',
                'Mention color schemes or mood you want to achieve',
                'Specify any symbols or elements you want included',
                'Include target audience or brand personality'
            ],
            logo_types: [
                'Wordmark: Focus on typography and text styling',
                'Symbol: Emphasize icon or graphic elements',
                'Combination: Include both text and symbol elements',
                'Emblem: Create a badge or seal-style design',
                'Abstract: Use geometric shapes and modern forms'
            ],
            industry_specific: [
                'Tech: Use modern, clean, and innovative elements',
                'Healthcare: Focus on trust, care, and professionalism',
                'Finance: Emphasize stability, security, and reliability',
                'Food: Use appetizing colors and food-related elements',
                'Creative: Show artistic flair and unique personality'
            ],
            general: [
                'AI logo generation works best with clear, descriptive prompts',
                'Results are delivered in 1024x1024 JPEG format',
                'Allow 15-30 seconds for processing',
                'Enhanced prompts provide richer, more detailed results',
                'Experiment with different styles for various applications'
            ]
        };

        console.log('💡 Logo Design Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get logo use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getLogoUseCases() {
        const useCases = {
            business: [
                'Create company logos for startups and enterprises',
                'Design brand identities for new businesses',
                'Generate logos for product launches',
                'Create professional business cards and letterheads',
                'Design trade show and marketing materials'
            ],
            personal: [
                'Create personal branding logos',
                'Design logos for freelance services',
                'Generate logos for personal projects',
                'Create logos for social media profiles',
                'Design logos for personal websites'
            ],
            creative: [
                'Generate logos for creative agencies',
                'Design logos for artists and designers',
                'Create logos for events and festivals',
                'Generate logos for publications and blogs',
                'Design logos for creative projects'
            ],
            gaming: [
                'Create gaming channel logos',
                'Design esports team logos',
                'Generate gaming studio logos',
                'Create tournament and event logos',
                'Design gaming merchandise logos'
            ],
            education: [
                'Create logos for educational institutions',
                'Design logos for online courses',
                'Generate logos for training programs',
                'Create logos for educational events',
                'Design logos for student organizations'
            ]
        };

        console.log('💡 Logo Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Get logo style suggestions
     * @returns {Object} Object containing style suggestions
     */
    getLogoStyleSuggestions() {
        const styleSuggestions = {
            minimal: [
                'Clean, simple designs with minimal elements',
                'Focus on typography and negative space',
                'Use limited color palettes',
                'Emphasize clarity and readability',
                'Perfect for modern, professional brands'
            ],
            vintage: [
                'Classic, timeless designs with retro elements',
                'Use traditional typography and classic symbols',
                'Incorporate aged or weathered effects',
                'Focus on heritage and tradition',
                'Great for established, traditional businesses'
            ],
            modern: [
                'Contemporary designs with current trends',
                'Use bold typography and geometric shapes',
                'Incorporate technology and innovation',
                'Focus on forward-thinking and progress',
                'Perfect for tech and startup companies'
            ],
            playful: [
                'Fun, energetic designs with personality',
                'Use bright colors and creative elements',
                'Incorporate humor and whimsy',
                'Focus on approachability and friendliness',
                'Great for entertainment and creative brands'
            ],
            elegant: [
                'Sophisticated designs with refined aesthetics',
                'Use premium typography and luxury elements',
                'Incorporate subtle details and craftsmanship',
                'Focus on quality and exclusivity',
                'Perfect for luxury and high-end brands'
            ]
        };

        console.log('💡 Logo Style Suggestions:');
        for (const [style, suggestionList] of Object.entries(styleSuggestions)) {
            console.log(`${style}: ${suggestionList}`);
        }
        return styleSuggestions;
    }

    /**
     * Validate text prompt (utility function)
     * @param {string} textPrompt - Text prompt to validate
     * @returns {boolean} Whether the prompt is valid
     */
    validateTextPrompt(textPrompt) {
        if (!textPrompt || textPrompt.trim().length === 0) {
            console.log('❌ Text prompt cannot be empty');
            return false;
        }

        if (textPrompt.length > 500) {
            console.log('❌ Text prompt is too long (max 500 characters)');
            return false;
        }

        console.log('✅ Text prompt is valid');
        return true;
    }

    /**
     * Generate logo with validation
     * @param {string} textPrompt - Text prompt for logo description
     * @param {boolean} enhancePrompt - Whether to enhance the prompt
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateLogoWithValidation(textPrompt, enhancePrompt = true) {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return this.processLogo(textPrompt, enhancePrompt);
    }

    // Private methods

    sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
}

// Example usage
async function runExample() {
    try {
        // Initialize with your API key
        const lightx = new LightXAILogoGeneratorAPI('YOUR_API_KEY_HERE');

        // Get tips and examples
        lightx.getLogoPromptExamples();
        lightx.getLogoDesignTips();
        lightx.getLogoUseCases();
        lightx.getLogoStyleSuggestions();

        // Example 1: Gaming logo
        const result1 = await lightx.generateLogoWithValidation(
            'Minimal monoline fox logo, vector, flat',
            true
        );
        console.log('🎉 Gaming logo result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Business logo
        const result2 = await lightx.generateLogoWithValidation(
            'Professional tech company logo, modern, clean',
            true
        );
        console.log('🎉 Business logo result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Try different logo types
        const logoTypes = [
            'Restaurant logo with chef hat and elegant typography',
            'Fashion brand logo with elegant, minimalist design',
            'Tech startup logo with modern, innovative design',
            'Coffee shop logo with coffee bean and warm colors',
            'Design agency logo with creative, artistic elements'
        ];

        for (const logoPrompt of logoTypes) {
            const result = await lightx.generateLogoWithValidation(
                logoPrompt,
                true
            );
            console.log(`🎉 ${logoPrompt} result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXAILogoGeneratorAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
