/**
 * LightX AI Design API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered design generation functionality.
 */

const axios = require('axios');

class LightXAIDesignAPI {
    constructor(apiKey) {
        this.apiKey = apiKey;
        this.baseURL = 'https://api.lightxeditor.com/external/api';
        this.maxRetries = 5;
        this.retryInterval = 3000; // 3 seconds
    }

    /**
     * Generate AI design
     * @param {string} textPrompt - Text prompt for design description
     * @param {string} resolution - Design resolution (1:1, 9:16, 3:4, 2:3, 16:9, 4:3)
     * @param {boolean} enhancePrompt - Whether to enhance the prompt (default: true)
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateDesign(textPrompt, resolution = '1:1', enhancePrompt = true) {
        const endpoint = `${this.baseURL}/v2/ai-design`;

        const payload = {
            textPrompt: textPrompt,
            resolution: resolution,
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
                throw new Error(`Design request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`🎨 Design prompt: "${textPrompt}"`);
            console.log(`📐 Resolution: ${resolution}`);
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
                        console.log('✅ AI design generated successfully!');
                        if (status.output) {
                            console.log(`🎨 Design output: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('AI design generation failed');

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
     * Complete workflow: Generate AI design and wait for completion
     * @param {string} textPrompt - Text prompt for design description
     * @param {string} resolution - Design resolution
     * @param {boolean} enhancePrompt - Whether to enhance the prompt
     * @returns {Promise<Object>} Final result with output URL
     */
    async processDesign(textPrompt, resolution = '1:1', enhancePrompt = true) {
        console.log('🚀 Starting LightX AI Design API workflow...');

        // Step 1: Generate design
        console.log('🎨 Generating AI design...');
        const orderId = await this.generateDesign(textPrompt, resolution, enhancePrompt);

        // Step 2: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get supported resolutions
     * @returns {Object} Object containing supported resolutions
     */
    getSupportedResolutions() {
        const resolutions = {
            '1:1': {
                name: 'Square',
                dimensions: '512 × 512',
                description: 'Perfect for social media posts, profile pictures, and square designs'
            },
            '9:16': {
                name: 'Portrait (9:16)',
                dimensions: '289 × 512',
                description: 'Ideal for mobile stories, vertical videos, and tall designs'
            },
            '3:4': {
                name: 'Portrait (3:4)',
                dimensions: '386 × 512',
                description: 'Great for portrait photos, magazine covers, and vertical layouts'
            },
            '2:3': {
                name: 'Portrait (2:3)',
                dimensions: '341 × 512',
                description: 'Perfect for posters, flyers, and portrait-oriented designs'
            },
            '16:9': {
                name: 'Landscape (16:9)',
                dimensions: '512 × 289',
                description: 'Ideal for banners, presentations, and widescreen designs'
            },
            '4:3': {
                name: 'Landscape (4:3)',
                dimensions: '512 × 386',
                description: 'Great for traditional photos, presentations, and landscape layouts'
            }
        };

        console.log('📐 Supported Resolutions:');
        for (const [ratio, info] of Object.entries(resolutions)) {
            console.log(`${ratio}: ${info.name} (${info.dimensions}) - ${info.description}`);
        }
        return resolutions;
    }

    /**
     * Get design prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getDesignPromptExamples() {
        const promptExamples = {
            birthday_cards: [
                'BIRTHDAY CARD INVITATION with balloons and confetti',
                'Elegant birthday card with cake and candles',
                'Fun birthday invitation with party decorations',
                'Modern birthday card with geometric patterns',
                'Vintage birthday invitation with floral design'
            ],
            posters: [
                'CONCERT POSTER with bold typography and neon colors',
                'Movie poster with dramatic lighting and action',
                'Event poster with modern minimalist design',
                'Festival poster with vibrant colors and patterns',
                'Art exhibition poster with creative typography'
            ],
            flyers: [
                'RESTAURANT FLYER with appetizing food photos',
                'Gym membership flyer with fitness motivation',
                'Sale flyer with discount offers and prices',
                'Workshop flyer with educational theme',
                'Product launch flyer with modern design'
            ],
            banners: [
                'WEBSITE BANNER with call-to-action button',
                'Social media banner with brand colors',
                'Advertisement banner with product showcase',
                'Event banner with date and location',
                'Promotional banner with special offers'
            ],
            invitations: [
                'WEDDING INVITATION with elegant typography',
                'Party invitation with fun graphics',
                'Corporate event invitation with professional design',
                'Holiday party invitation with festive theme',
                'Anniversary invitation with romantic elements'
            ],
            packaging: [
                'PRODUCT PACKAGING with modern minimalist design',
                'Food packaging with appetizing visuals',
                'Cosmetic packaging with luxury aesthetic',
                'Tech product packaging with sleek design',
                'Gift box packaging with premium feel'
            ]
        };

        console.log('💡 Design Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get design tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getDesignTips() {
        const tips = {
            text_prompts: [
                'Be specific about the design type (poster, card, banner, etc.)',
                'Include style preferences (modern, vintage, minimalist, etc.)',
                'Mention color schemes or mood you want to achieve',
                'Specify any text or typography requirements',
                'Include target audience or purpose of the design'
            ],
            resolution_selection: [
                'Choose 1:1 for social media posts and profile pictures',
                'Use 9:16 for mobile stories and vertical content',
                'Select 2:3 for posters, flyers, and print materials',
                'Pick 16:9 for banners, presentations, and web headers',
                'Consider 4:3 for traditional photos and documents'
            ],
            prompt_enhancement: [
                'Enable enhancePrompt for better, more detailed results',
                'Use enhancePrompt when you want richer visual elements',
                'Disable enhancePrompt for exact prompt interpretation',
                'Enhanced prompts work well for creative designs',
                'Basic prompts are better for simple, clean designs'
            ],
            general: [
                'AI design works best with clear, descriptive prompts',
                'Results may vary based on prompt complexity and style',
                'Allow 15-30 seconds for processing',
                'Experiment with different resolutions for various use cases',
                'Combine text prompts with resolution for optimal results'
            ]
        };

        console.log('💡 AI Design Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get design use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getDesignUseCases() {
        const useCases = {
            marketing: [
                'Create promotional posters and banners',
                'Generate social media content and ads',
                'Design product packaging and labels',
                'Create event flyers and invitations',
                'Generate website headers and graphics'
            ],
            personal: [
                'Design birthday cards and invitations',
                'Create holiday greetings and cards',
                'Generate party decorations and themes',
                'Design personal branding materials',
                'Create custom artwork and prints'
            ],
            business: [
                'Generate corporate presentation slides',
                'Create business cards and letterheads',
                'Design product catalogs and brochures',
                'Generate trade show materials',
                'Create company newsletters and reports'
            ],
            creative: [
                'Explore artistic design concepts',
                'Generate creative project ideas',
                'Create portfolio pieces and samples',
                'Design book covers and illustrations',
                'Generate art prints and posters'
            ],
            education: [
                'Create educational posters and charts',
                'Design course materials and handouts',
                'Generate presentation slides and graphics',
                'Create learning aids and visual guides',
                'Design school event materials'
            ]
        };

        console.log('💡 Design Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
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
     * Validate resolution (utility function)
     * @param {string} resolution - Resolution to validate
     * @returns {boolean} Whether the resolution is valid
     */
    validateResolution(resolution) {
        const validResolutions = ['1:1', '9:16', '3:4', '2:3', '16:9', '4:3'];
        
        if (!validResolutions.includes(resolution)) {
            console.log(`❌ Invalid resolution. Valid options: ${validResolutions.join(', ')}`);
            return false;
        }

        console.log('✅ Resolution is valid');
        return true;
    }

    /**
     * Generate design with validation
     * @param {string} textPrompt - Text prompt for design description
     * @param {string} resolution - Design resolution
     * @param {boolean} enhancePrompt - Whether to enhance the prompt
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateDesignWithValidation(textPrompt, resolution = '1:1', enhancePrompt = true) {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        if (!this.validateResolution(resolution)) {
            throw new Error('Invalid resolution');
        }

        return this.processDesign(textPrompt, resolution, enhancePrompt);
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
        const lightx = new LightXAIDesignAPI('YOUR_API_KEY_HERE');

        // Get tips and examples
        lightx.getSupportedResolutions();
        lightx.getDesignPromptExamples();
        lightx.getDesignTips();
        lightx.getDesignUseCases();

        // Example 1: Birthday card design
        const result1 = await lightx.generateDesignWithValidation(
            'BIRTHDAY CARD INVITATION with balloons and confetti',
            '2:3',
            true
        );
        console.log('🎉 Birthday card design result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Poster design
        const result2 = await lightx.generateDesignWithValidation(
            'CONCERT POSTER with bold typography and neon colors',
            '16:9',
            true
        );
        console.log('🎉 Concert poster design result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Try different design types
        const designTypes = [
            { prompt: 'RESTAURANT FLYER with appetizing food photos', resolution: '2:3' },
            { prompt: 'WEBSITE BANNER with call-to-action button', resolution: '16:9' },
            { prompt: 'WEDDING INVITATION with elegant typography', resolution: '3:4' },
            { prompt: 'PRODUCT PACKAGING with modern minimalist design', resolution: '1:1' },
            { prompt: 'SOCIAL MEDIA POST with vibrant colors', resolution: '1:1' }
        ];

        for (const design of designTypes) {
            const result = await lightx.generateDesignWithValidation(
                design.prompt,
                design.resolution,
                true
            );
            console.log(`🎉 ${design.prompt} result:`);
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
module.exports = LightXAIDesignAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
