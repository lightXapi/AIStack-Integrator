/**
 * LightX AI Cartoon Character Generator API Integration - JavaScript/Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered cartoon character generation functionality.
 */

const axios = require('axios');
const fs = require('fs');
const FormData = require('form-data');

class LightXCartoonAPI {
    constructor(apiKey) {
        this.apiKey = apiKey;
        this.baseUrl = 'https://api.lightxeditor.com/external/api';
        this.maxRetries = 5;
        this.retryInterval = 3000; // 3 seconds
    }

    /**
     * Upload image to LightX servers
     * @param {string} imagePath - Path to the image file
     * @param {string} contentType - MIME type (image/jpeg or image/png)
     * @returns {Promise<string>} - Final image URL
     */
    async uploadImage(imagePath, contentType = 'image/jpeg') {
        try {
            // Get file stats
            const stats = fs.statSync(imagePath);
            const fileSize = stats.size;

            if (fileSize > 5242880) { // 5MB limit
                throw new Error('Image size exceeds 5MB limit');
            }

            // Step 1: Get upload URL
            const uploadUrlResponse = await axios.post(
                `${this.baseUrl}/v2/uploadImageUrl`,
                {
                    uploadType: 'imageUrl',
                    size: fileSize,
                    contentType: contentType
                },
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (uploadUrlResponse.data.statusCode !== 2000) {
                throw new Error(`Upload URL request failed: ${uploadUrlResponse.data.message}`);
            }

            const { uploadImage, imageUrl } = uploadUrlResponse.data.body;

            // Step 2: Upload image to S3
            const imageBuffer = fs.readFileSync(imagePath);
            await axios.put(uploadImage, imageBuffer, {
                headers: {
                    'Content-Type': contentType
                }
            });

            console.log('✅ Image uploaded successfully');
            return imageUrl;

        } catch (error) {
            console.error('Error uploading image:', error.message);
            throw error;
        }
    }

    /**
     * Upload multiple images (input and optional style image)
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<{inputUrl: string, styleUrl?: string}>} - URLs for images
     */
    async uploadImages(inputImagePath, styleImagePath = null, contentType = 'image/jpeg') {
        try {
            console.log('📤 Uploading input image...');
            const inputUrl = await this.uploadImage(inputImagePath, contentType);
            
            let styleUrl = null;
            if (styleImagePath) {
                console.log('📤 Uploading style image...');
                styleUrl = await this.uploadImage(styleImagePath, contentType);
            }
            
            return { inputUrl, styleUrl };
        } catch (error) {
            console.error('Error uploading images:', error.message);
            throw error;
        }
    }

    /**
     * Generate cartoon character
     * @param {string} imageUrl - URL of the input image
     * @param {string} styleImageUrl - URL of the style image (optional)
     * @param {string} textPrompt - Text prompt for cartoon style (optional)
     * @returns {Promise<string>} - Order ID for tracking
     */
    async generateCartoon(imageUrl, styleImageUrl = null, textPrompt = null) {
        try {
            const payload = {
                imageUrl: imageUrl
            };

            // Add optional parameters
            if (styleImageUrl) {
                payload.styleImageUrl = styleImageUrl;
            }
            if (textPrompt) {
                payload.textPrompt = textPrompt;
            }

            const response = await axios.post(
                `${this.baseUrl}/v1/cartoon`,
                payload,
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (response.data.statusCode !== 2000) {
                throw new Error(`Cartoon generation request failed: ${response.data.message}`);
            }

            const { orderId, maxRetriesAllowed, avgResponseTimeInSec, status } = response.data.body;
            
            console.log(`📋 Order created: ${orderId}`);
            console.log(`🔄 Max retries allowed: ${maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${status}`);
            if (textPrompt) {
                console.log(`💬 Text prompt: "${textPrompt}"`);
            }
            if (styleImageUrl) {
                console.log(`🎨 Style image: ${styleImageUrl}`);
            }

            return orderId;

        } catch (error) {
            console.error('Error generating cartoon:', error.message);
            throw error;
        }
    }

    /**
     * Check order status
     * @param {string} orderId - Order ID to check
     * @returns {Promise<Object>} - Order status and results
     */
    async checkOrderStatus(orderId) {
        try {
            const response = await axios.post(
                `${this.baseUrl}/v1/order-status`,
                {
                    orderId: orderId
                },
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (response.data.statusCode !== 2000) {
                throw new Error(`Status check failed: ${response.data.message}`);
            }

            return response.data.body;

        } catch (error) {
            console.error('Error checking order status:', error.message);
            throw error;
        }
    }

    /**
     * Wait for order completion with automatic retries
     * @param {string} orderId - Order ID to monitor
     * @returns {Promise<Object>} - Final result with output URL
     */
    async waitForCompletion(orderId) {
        let attempts = 0;
        
        while (attempts < this.maxRetries) {
            try {
                const status = await this.checkOrderStatus(orderId);
                
                console.log(`🔄 Attempt ${attempts + 1}: Status - ${status.status}`);
                
                if (status.status === 'active') {
                    console.log('✅ Cartoon generation completed successfully!');
                    console.log(`🎨 Cartoon image: ${status.output}`);
                    return status;
                } else if (status.status === 'failed') {
                    throw new Error('Cartoon generation failed');
                } else if (status.status === 'init') {
                    // Still processing, wait and retry
                    attempts++;
                    if (attempts < this.maxRetries) {
                        console.log(`⏳ Waiting ${this.retryInterval/1000} seconds before next check...`);
                        await this.sleep(this.retryInterval);
                    }
                }
            } catch (error) {
                attempts++;
                if (attempts >= this.maxRetries) {
                    throw error;
                }
                console.log(`Error on attempt ${attempts}, retrying...`);
                await this.sleep(this.retryInterval);
            }
        }
        
        throw new Error('Maximum retry attempts reached');
    }

    /**
     * Complete workflow: Upload images and generate cartoon
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image (optional)
     * @param {string} textPrompt - Text prompt for cartoon style (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async processCartoonGeneration(inputImagePath, styleImagePath = null, textPrompt = null, contentType = 'image/jpeg') {
        try {
            console.log('🚀 Starting LightX AI Cartoon Character Generator API workflow...');
            
            // Step 1: Upload images
            console.log('📤 Uploading images...');
            const { inputUrl, styleUrl } = await this.uploadImages(inputImagePath, styleImagePath, contentType);
            console.log(`✅ Input image uploaded: ${inputUrl}`);
            if (styleUrl) {
                console.log(`✅ Style image uploaded: ${styleUrl}`);
            }
            
            // Step 2: Generate cartoon
            console.log('🎨 Generating cartoon character...');
            const orderId = await this.generateCartoon(inputUrl, styleUrl, textPrompt);
            
            // Step 3: Wait for completion
            console.log('⏳ Waiting for processing to complete...');
            const result = await this.waitForCompletion(orderId);
            
            return result;
            
        } catch (error) {
            console.error('❌ Workflow failed:', error.message);
            throw error;
        }
    }

    /**
     * Get common text prompts for different cartoon styles
     * @param {string} category - Category of cartoon style
     * @returns {Array<string>} - Array of suggested prompts
     */
    getSuggestedPrompts(category) {
        const promptSuggestions = {
            classic: [
                'classic Disney style cartoon',
                'vintage cartoon character',
                'traditional animation style',
                'classic comic book style',
                'retro cartoon character'
            ],
            modern: [
                'modern anime style',
                'contemporary cartoon character',
                'digital art style',
                'modern illustration style',
                'stylized cartoon character'
            ],
            artistic: [
                'watercolor cartoon style',
                'oil painting cartoon',
                'sketch cartoon style',
                'artistic cartoon character',
                'painterly cartoon style'
            ],
            fun: [
                'cute and adorable cartoon',
                'funny cartoon character',
                'playful cartoon style',
                'whimsical cartoon character',
                'cheerful cartoon style'
            ],
            professional: [
                'professional cartoon portrait',
                'business cartoon style',
                'corporate cartoon character',
                'formal cartoon style',
                'professional illustration'
            ]
        };

        const prompts = promptSuggestions[category] || [];
        console.log(`💡 Suggested prompts for ${category}:`, prompts);
        return prompts;
    }

    /**
     * Validate text prompt (utility function)
     * @param {string} textPrompt - Text prompt to validate
     * @returns {boolean} - Whether the prompt is valid
     */
    validateTextPrompt(textPrompt) {
        if (!textPrompt || textPrompt.trim().length === 0) {
            console.error('❌ Text prompt cannot be empty');
            return false;
        }

        if (textPrompt.length > 500) {
            console.error('❌ Text prompt is too long (max 500 characters)');
            return false;
        }

        console.log('✅ Text prompt is valid');
        return true;
    }

    /**
     * Generate cartoon with text prompt only
     * @param {string} inputImagePath - Path to the input image
     * @param {string} textPrompt - Text prompt for cartoon style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async generateCartoonWithPrompt(inputImagePath, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processCartoonGeneration(inputImagePath, null, textPrompt, contentType);
    }

    /**
     * Generate cartoon with style image only
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async generateCartoonWithStyle(inputImagePath, styleImagePath, contentType = 'image/jpeg') {
        return await this.processCartoonGeneration(inputImagePath, styleImagePath, null, contentType);
    }

    /**
     * Generate cartoon with both style image and text prompt
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image
     * @param {string} textPrompt - Text prompt for cartoon style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async generateCartoonWithStyleAndPrompt(inputImagePath, styleImagePath, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processCartoonGeneration(inputImagePath, styleImagePath, textPrompt, contentType);
    }

    /**
     * Utility function for sleep/delay
     * @param {number} ms - Milliseconds to sleep
     */
    sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
}

// Example usage
async function example() {
    try {
        // Initialize with your API key
        const lightx = new LightXCartoonAPI('YOUR_API_KEY_HERE');
        
        // Example 1: Generate cartoon with text prompt only
        const classicPrompts = lightx.getSuggestedPrompts('classic');
        const result1 = await lightx.generateCartoonWithPrompt(
            './path/to/your/image.jpg',  // Input image path
            classicPrompts[0],          // Text prompt
            'image/jpeg'                // Content type
        );
        console.log('🎉 Classic cartoon result:', result1);
        
        // Example 2: Generate cartoon with style image only
        const result2 = await lightx.generateCartoonWithStyle(
            './path/to/your/image.jpg',     // Input image path
            './path/to/style-image.jpg',    // Style image path
            'image/jpeg'                    // Content type
        );
        console.log('🎉 Style-based cartoon result:', result2);
        
        // Example 3: Generate cartoon with both style image and text prompt
        const modernPrompts = lightx.getSuggestedPrompts('modern');
        const result3 = await lightx.generateCartoonWithStyleAndPrompt(
            './path/to/your/image.jpg',     // Input image path
            './path/to/style-image.jpg',    // Style image path
            modernPrompts[0],              // Text prompt
            'image/jpeg'                   // Content type
        );
        console.log('🎉 Combined style and prompt result:', result3);
        
        // Example 4: Generate cartoon with different style categories
        const categories = ['classic', 'modern', 'artistic', 'fun', 'professional'];
        for (const category of categories) {
            const prompts = lightx.getSuggestedPrompts(category);
            const result = await lightx.generateCartoonWithPrompt(
                './path/to/your/image.jpg',
                prompts[0],
                'image/jpeg'
            );
            console.log(`🎉 ${category} cartoon result:`, result);
        }
        
    } catch (error) {
        console.error('Example failed:', error.message);
    }
}

// Export for use in other modules
module.exports = LightXCartoonAPI;

// Run example if this file is executed directly
if (require.main === module) {
    example();
}
