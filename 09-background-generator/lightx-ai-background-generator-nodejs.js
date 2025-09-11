/**
 * LightX AI Background Generator API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered background generation functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXBackgroundGeneratorAPI {
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
     * Generate background
     * @param {string} imageUrl - URL of the input image
     * @param {string} textPrompt - Text prompt for background generation
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateBackground(imageUrl, textPrompt) {
        const endpoint = `${this.baseURL}/v1/background-generator`;

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
                throw new Error(`Background generation request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`💬 Text prompt: "${textPrompt}"`);

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
                        console.log('✅ Background generation completed successfully!');
                        if (status.output) {
                            console.log(`🖼️ Background image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Background generation failed');

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
     * Complete workflow: Upload image and generate background
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for background generation
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processBackgroundGeneration(imageData, textPrompt, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Background Generator API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Generate background
        console.log('🖼️ Generating background...');
        const orderId = await this.generateBackground(imageUrl, textPrompt);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get background generation tips and best practices
     * @returns {Object} Object containing tips for better background results
     */
    getBackgroundTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit photos with good contrast',
                'Ensure the subject is clearly visible and centered',
                'Avoid cluttered or busy backgrounds',
                'Use high-resolution images for better results',
                'Good subject separation improves background generation'
            ],
            text_prompts: [
                'Be specific about the background style you want',
                'Mention color schemes and mood preferences',
                'Include environmental details (indoor, outdoor, studio)',
                'Specify lighting preferences (natural, dramatic, soft)',
                'Keep prompts concise but descriptive'
            ],
            general: [
                'Background generation works best with clear subjects',
                'Results may vary based on input image quality',
                'Text prompts guide the background generation process',
                'Allow 15-30 seconds for processing',
                'Experiment with different prompt styles for variety'
            ]
        };

        console.log('💡 Background Generation Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get background style suggestions
     * @returns {Object} Object containing background style suggestions
     */
    getBackgroundStyleSuggestions() {
        const styleSuggestions = {
            professional: [
                'professional office background',
                'clean minimalist workspace',
                'modern corporate environment',
                'elegant business setting',
                'sophisticated professional space'
            ],
            natural: [
                'natural outdoor landscape',
                'beautiful garden background',
                'scenic mountain view',
                'peaceful forest setting',
                'serene beach environment'
            ],
            creative: [
                'artistic studio background',
                'creative workspace environment',
                'colorful abstract background',
                'modern art gallery setting',
                'inspiring creative space'
            ],
            lifestyle: [
                'cozy home interior',
                'modern living room',
                'stylish bedroom setting',
                'contemporary kitchen',
                'elegant dining room'
            ],
            outdoor: [
                'urban cityscape background',
                'park environment',
                'outdoor cafe setting',
                'street scene background',
                'architectural landmark view'
            ]
        };

        console.log('💡 Background Style Suggestions:');
        for (const [category, suggestionList] of Object.entries(styleSuggestions)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return styleSuggestions;
    }

    /**
     * Get background prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getBackgroundPromptExamples() {
        const promptExamples = {
            professional: [
                'Modern office with glass windows and city view',
                'Clean white studio background with soft lighting',
                'Professional conference room with wooden furniture',
                'Contemporary workspace with minimalist design',
                'Elegant business environment with neutral colors'
            ],
            natural: [
                'Beautiful sunset over mountains in the background',
                'Lush green garden with blooming flowers',
                'Peaceful lake with reflection of trees',
                'Golden hour lighting in a forest setting',
                'Serene beach with gentle waves and palm trees'
            ],
            creative: [
                'Artistic studio with colorful paint splashes',
                'Modern gallery with white walls and track lighting',
                'Creative workspace with vintage furniture',
                'Abstract colorful background with geometric shapes',
                'Bohemian style room with eclectic decorations'
            ],
            lifestyle: [
                'Cozy living room with warm lighting and books',
                'Modern kitchen with marble countertops',
                'Stylish bedroom with soft natural light',
                'Contemporary dining room with elegant table',
                'Comfortable home office with plants and books'
            ],
            outdoor: [
                'Urban cityscape with modern skyscrapers',
                'Charming street with cafes and shops',
                'Park setting with trees and walking paths',
                'Historic architecture with classical columns',
                'Modern outdoor space with contemporary design'
            ]
        };

        console.log('💡 Background Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
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
     * Generate background with prompt validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for background generation
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateBackgroundWithPrompt(imageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return this.processBackgroundGeneration(imageData, textPrompt, contentType);
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
        const lightx = new LightXBackgroundGeneratorAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getBackgroundTips();
        lightx.getBackgroundStyleSuggestions();
        lightx.getBackgroundPromptExamples();

        // Load image (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';

        // Example 1: Generate background with professional style
        const professionalPrompts = lightx.getBackgroundStyleSuggestions().professional;
        const result1 = await lightx.generateBackgroundWithPrompt(
            imagePath,
            professionalPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Professional background result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Generate background with natural style
        const naturalPrompts = lightx.getBackgroundStyleSuggestions().natural;
        const result2 = await lightx.generateBackgroundWithPrompt(
            imagePath,
            naturalPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Natural background result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Generate backgrounds for different styles
        const styles = ['professional', 'natural', 'creative', 'lifestyle', 'outdoor'];
        for (const style of styles) {
            const prompts = lightx.getBackgroundStyleSuggestions()[style];
            const result = await lightx.generateBackgroundWithPrompt(
                imagePath,
                prompts[0],
                'image/jpeg'
            );
            console.log(`🎉 ${style} background result:`);
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
module.exports = LightXBackgroundGeneratorAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
