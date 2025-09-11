/**
 * LightX AI Outfit API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered outfit changing functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXOutfitAPI {
    constructor(apiKey) {
        this.apiKey = apiKey;
        this.baseURL = 'https://api.lightxeditor.com/external/api';
        this.maxRetries = 5;
        this.retryInterval = 3000; // 3 seconds
        this.maxFileSize = 5242880; // 5MB
    }

    /**
     * Upload image to LightX servers
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} contentType - MIME type (image/jpeg or image/png)
     * @returns {Promise<string>} Final image URL
     */
    async uploadImage(imageData, contentType = 'image/jpeg') {
        let fileSize;
        let imageBuffer;

        if (Buffer.isBuffer(imageData)) {
            imageBuffer = imageData;
            fileSize = imageData.length;
        } else if (typeof imageData === 'string') {
            // Assume it's a file path
            imageBuffer = fs.readFileSync(imageData);
            fileSize = imageBuffer.length;
        } else {
            throw new Error('Invalid image data provided');
        }

        if (fileSize > this.maxFileSize) {
            throw new Error('Image size exceeds 5MB limit');
        }

        // Step 1: Get upload URL
        const uploadURL = await this.getUploadURL(fileSize, contentType);

        // Step 2: Upload image to S3
        await this.uploadToS3(uploadURL.uploadImage, imageBuffer, contentType);

        console.log('✅ Image uploaded successfully');
        return uploadURL.imageUrl;
    }

    /**
     * Generate outfit change
     * @param {string} imageUrl - URL of the input image
     * @param {string} textPrompt - Text prompt for outfit description
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateOutfit(imageUrl, textPrompt) {
        const endpoint = `${this.baseURL}/v1/outfit`;

        const payload = {
            imageUrl: imageUrl,
            textPrompt: textPrompt
        };

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Outfit generation request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`👗 Outfit prompt: "${textPrompt}"`);

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
        const endpoint = `${this.baseURL}/v1/order-status`;

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
                        console.log('✅ Outfit generation completed successfully!');
                        if (status.output) {
                            console.log(`👗 Outfit result: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Outfit generation failed');

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
     * Complete workflow: Upload image and generate outfit
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for outfit description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processOutfitGeneration(imageData, textPrompt, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Outfit API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Generate outfit
        console.log('👗 Generating outfit...');
        const orderId = await this.generateOutfit(imageUrl, textPrompt);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get outfit generation tips and best practices
     * @returns {Object} Object containing tips for better outfit results
     */
    getOutfitTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit photos with good contrast',
                'Ensure the person is clearly visible and centered',
                'Avoid cluttered or busy backgrounds',
                'Use high-resolution images for better results',
                'Good body visibility improves outfit generation'
            ],
            text_prompts: [
                'Be specific about the outfit style you want',
                'Mention clothing items (shirt, dress, jacket, etc.)',
                'Include color preferences and patterns',
                'Specify the occasion (casual, formal, party, etc.)',
                'Keep prompts concise but descriptive'
            ],
            general: [
                'Outfit generation works best with clear human subjects',
                'Results may vary based on input image quality',
                'Text prompts guide the outfit generation process',
                'Allow 15-30 seconds for processing',
                'Experiment with different outfit styles for variety'
            ]
        };

        console.log('💡 Outfit Generation Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get outfit style suggestions
     * @returns {Object} Object containing outfit style suggestions
     */
    getOutfitStyleSuggestions() {
        const styleSuggestions = {
            professional: [
                'professional business suit',
                'formal office attire',
                'corporate blazer and dress pants',
                'elegant business dress',
                'sophisticated work outfit'
            ],
            casual: [
                'casual jeans and t-shirt',
                'relaxed weekend outfit',
                'comfortable everyday wear',
                'casual summer dress',
                'laid-back street style'
            ],
            formal: [
                'elegant evening gown',
                'formal tuxedo',
                'cocktail party dress',
                'black tie attire',
                'sophisticated formal wear'
            ],
            sporty: [
                'athletic workout outfit',
                'sporty casual wear',
                'gym attire',
                'active lifestyle clothing',
                'comfortable sports outfit'
            ],
            trendy: [
                'fashionable street style',
                'trendy modern outfit',
                'stylish contemporary wear',
                'fashion-forward ensemble',
                'chic trendy clothing'
            ]
        };

        console.log('💡 Outfit Style Suggestions:');
        for (const [category, suggestionList] of Object.entries(styleSuggestions)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return styleSuggestions;
    }

    /**
     * Get outfit prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getOutfitPromptExamples() {
        const promptExamples = {
            professional: [
                'Professional navy blue business suit with white shirt',
                'Elegant black blazer with matching dress pants',
                'Corporate dress in neutral colors',
                'Formal office attire with blouse and skirt',
                'Business casual outfit with cardigan and slacks'
            ],
            casual: [
                'Casual blue jeans with white cotton t-shirt',
                'Relaxed summer dress in floral pattern',
                'Comfortable hoodie with denim jeans',
                'Casual weekend outfit with sneakers',
                'Lay-back style with comfortable clothing'
            ],
            formal: [
                'Elegant black evening gown with accessories',
                'Formal tuxedo with bow tie',
                'Cocktail dress in deep red color',
                'Black tie formal wear',
                'Sophisticated formal attire for special occasion'
            ],
            sporty: [
                'Athletic leggings with sports bra and sneakers',
                'Gym outfit with tank top and shorts',
                'Active wear for running and exercise',
                'Sporty casual outfit for outdoor activities',
                'Comfortable athletic clothing'
            ],
            trendy: [
                'Fashionable street style with trendy accessories',
                'Modern outfit with contemporary fashion elements',
                'Stylish ensemble with current fashion trends',
                'Chic trendy clothing with fashionable details',
                'Fashion-forward outfit with modern styling'
            ]
        };

        console.log('💡 Outfit Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get outfit use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getOutfitUseCases() {
        const useCases = {
            fashion: [
                'Virtual try-on for e-commerce',
                'Fashion styling and recommendations',
                'Outfit planning and coordination',
                'Style inspiration and ideas',
                'Fashion trend visualization'
            ],
            retail: [
                'Online shopping experience enhancement',
                'Product visualization and styling',
                'Customer engagement and interaction',
                'Virtual fitting room technology',
                'Personalized fashion recommendations'
            ],
            social: [
                'Social media content creation',
                'Fashion blogging and influencers',
                'Style sharing and inspiration',
                'Outfit of the day posts',
                'Fashion community engagement'
            ],
            personal: [
                'Personal style exploration',
                'Wardrobe planning and organization',
                'Outfit coordination and matching',
                'Style experimentation',
                'Fashion confidence building'
            ]
        };

        console.log('💡 Outfit Use Cases:');
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
        if (!textPrompt || !textPrompt.trim()) {
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
     * Get image dimensions (utility function)
     * @param {Buffer|string} imageData - Image data or file path
     * @returns {Promise<{width: number, height: number}>} Image dimensions
     */
    async getImageDimensions(imageData) {
        // This is a simplified version - in a real implementation, you'd use a library like 'sharp' or 'jimp'
        try {
            let imageBuffer;
            if (Buffer.isBuffer(imageData)) {
                imageBuffer = imageData;
            } else if (typeof imageData === 'string') {
                imageBuffer = fs.readFileSync(imageData);
            } else {
                throw new Error('Invalid image data provided');
            }

            // Basic JPEG/PNG header parsing (simplified)
            // In production, use a proper image processing library
            const size = imageBuffer.length;
            console.log(`📏 Image size: ${size} bytes`);
            
            // Return placeholder dimensions - replace with actual image dimension extraction
            return { width: 1024, height: 1024 };
        } catch (error) {
            console.log(`❌ Error getting image dimensions: ${error.message}`);
            return { width: 0, height: 0 };
        }
    }

    /**
     * Generate outfit with prompt validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for outfit description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateOutfitWithPrompt(imageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return this.processOutfitGeneration(imageData, textPrompt, contentType);
    }

    // Private methods

    async getUploadURL(fileSize, contentType) {
        const endpoint = `${this.baseURL}/v2/uploadImageUrl`;

        try {
            const response = await axios.post(endpoint, {
                uploadType: 'imageUrl',
                size: fileSize,
                contentType: contentType
            }, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Upload URL request failed: ${response.data.message}`);
            }

            return response.data.body;

        } catch (error) {
            if (error.response) {
                throw new Error(`Network error: ${error.response.status} - ${error.response.statusText}`);
            }
            throw error;
        }
    }

    async uploadToS3(uploadUrl, imageBuffer, contentType) {
        try {
            const response = await axios.put(uploadUrl, imageBuffer, {
                headers: {
                    'Content-Type': contentType
                }
            });

            if (response.status !== 200) {
                throw new Error(`Image upload failed: ${response.status}`);
            }

        } catch (error) {
            if (error.response) {
                throw new Error(`Upload error: ${error.response.status} - ${error.response.statusText}`);
            }
            throw error;
        }
    }

    sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
}

// Example usage
async function runExample() {
    try {
        // Initialize with your API key
        const lightx = new LightXOutfitAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getOutfitTips();
        lightx.getOutfitStyleSuggestions();
        lightx.getOutfitPromptExamples();
        lightx.getOutfitUseCases();

        // Load image (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';

        // Example 1: Generate professional outfit
        const professionalPrompts = lightx.getOutfitStyleSuggestions().professional;
        const result1 = await lightx.generateOutfitWithPrompt(
            imagePath,
            professionalPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Professional outfit result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Generate casual outfit
        const casualPrompts = lightx.getOutfitStyleSuggestions().casual;
        const result2 = await lightx.generateOutfitWithPrompt(
            imagePath,
            casualPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Casual outfit result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Generate outfits for different styles
        const styles = ['professional', 'casual', 'formal', 'sporty', 'trendy'];
        for (const style of styles) {
            const prompts = lightx.getOutfitStyleSuggestions()[style];
            const result = await lightx.generateOutfitWithPrompt(
                imagePath,
                prompts[0],
                'image/jpeg'
            );
            console.log(`🎉 ${style} outfit result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 4: Get image dimensions
        const dimensions = await lightx.getImageDimensions(imagePath);
        if (dimensions.width > 0 && dimensions.height > 0) {
            console.log(`📏 Original image: ${dimensions.width}x${dimensions.height}`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXOutfitAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
