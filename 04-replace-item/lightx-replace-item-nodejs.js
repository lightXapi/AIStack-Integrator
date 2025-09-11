/**
 * LightX AI Replace Item API Integration - JavaScript/Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered item replacement functionality using text prompts and masks.
 */

const axios = require('axios');
const fs = require('fs');
const FormData = require('form-data');

class LightXReplaceAPI {
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
     * Upload multiple images (original and mask)
     * @param {string} originalImagePath - Path to the original image
     * @param {string} maskImagePath - Path to the mask image
     * @param {string} contentType - MIME type
     * @returns {Promise<{originalUrl: string, maskUrl: string}>} - URLs for both images
     */
    async uploadImages(originalImagePath, maskImagePath, contentType = 'image/jpeg') {
        try {
            console.log('📤 Uploading original image...');
            const originalUrl = await this.uploadImage(originalImagePath, contentType);
            
            console.log('📤 Uploading mask image...');
            const maskUrl = await this.uploadImage(maskImagePath, contentType);
            
            return { originalUrl, maskUrl };
        } catch (error) {
            console.error('Error uploading images:', error.message);
            throw error;
        }
    }

    /**
     * Replace item using AI and text prompt
     * @param {string} imageUrl - URL of the original image
     * @param {string} maskedImageUrl - URL of the mask image
     * @param {string} textPrompt - Text prompt describing what to replace with
     * @returns {Promise<string>} - Order ID for tracking
     */
    async replaceItem(imageUrl, maskedImageUrl, textPrompt) {
        try {
            const response = await axios.post(
                `${this.baseUrl}/v1/replace`,
                {
                    imageUrl: imageUrl,
                    maskedImageUrl: maskedImageUrl,
                    textPrompt: textPrompt
                },
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (response.data.statusCode !== 2000) {
                throw new Error(`Replace item request failed: ${response.data.message}`);
            }

            const { orderId, maxRetriesAllowed, avgResponseTimeInSec, status } = response.data.body;
            
            console.log(`📋 Order created: ${orderId}`);
            console.log(`🔄 Max retries allowed: ${maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${status}`);
            console.log(`💬 Text prompt: "${textPrompt}"`);

            return orderId;

        } catch (error) {
            console.error('Error replacing item:', error.message);
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
                    console.log('✅ Item replacement completed successfully!');
                    console.log(`🖼️  Replaced image: ${status.output}`);
                    return status;
                } else if (status.status === 'failed') {
                    throw new Error('Item replacement failed');
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
     * Complete workflow: Upload images and replace item
     * @param {string} originalImagePath - Path to the original image
     * @param {string} maskImagePath - Path to the mask image
     * @param {string} textPrompt - Text prompt for replacement
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async processReplacement(originalImagePath, maskImagePath, textPrompt, contentType = 'image/jpeg') {
        try {
            console.log('🚀 Starting LightX AI Replace API workflow...');
            
            // Step 1: Upload both images
            console.log('📤 Uploading images...');
            const { originalUrl, maskUrl } = await this.uploadImages(originalImagePath, maskImagePath, contentType);
            console.log(`✅ Original image uploaded: ${originalUrl}`);
            console.log(`✅ Mask image uploaded: ${maskUrl}`);
            
            // Step 2: Replace item
            console.log('🔄 Replacing item with AI...');
            const orderId = await this.replaceItem(originalUrl, maskUrl, textPrompt);
            
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
     * Create a simple mask from coordinates (utility function)
     * @param {number} width - Image width
     * @param {number} height - Image height
     * @param {Array} coordinates - Array of {x, y, width, height} objects for white areas
     * @returns {string} - Base64 encoded mask image
     */
    createMaskFromCoordinates(width, height, coordinates) {
        // This is a utility function to help create masks
        // In a real implementation, you'd use a canvas library to draw the mask
        console.log('🎭 Creating mask from coordinates...');
        console.log(`Image dimensions: ${width}x${height}`);
        console.log(`White areas:`, coordinates);
        
        // Note: This is a placeholder. You would need to implement actual mask creation
        // using a library like 'canvas' or 'jimp' to draw white rectangles on black background
        return 'mask-created-from-coordinates';
    }

    /**
     * Get common text prompts for different replacement scenarios
     * @param {string} category - Category of replacement
     * @returns {Array<string>} - Array of suggested prompts
     */
    getSuggestedPrompts(category) {
        const promptSuggestions = {
            face: [
                'a young woman with blonde hair',
                'an elderly man with a beard',
                'a smiling child',
                'a professional businessman',
                'a person wearing glasses'
            ],
            clothing: [
                'a red dress',
                'a blue suit',
                'a casual t-shirt',
                'a winter jacket',
                'a formal shirt'
            ],
            objects: [
                'a modern smartphone',
                'a vintage car',
                'a beautiful flower',
                'a wooden chair',
                'a glass vase'
            ],
            background: [
                'a beach scene',
                'a mountain landscape',
                'a modern office',
                'a cozy living room',
                'a garden setting'
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
        const lightx = new LightXReplaceAPI('YOUR_API_KEY_HERE');
        
        // Example 1: Replace a face
        const facePrompts = lightx.getSuggestedPrompts('face');
        const result1 = await lightx.processReplacement(
            './path/to/original-image.jpg',  // Original image path
            './path/to/mask-image.png',      // Mask image path (white areas = replace, black = keep)
            facePrompts[0],                  // Text prompt
            'image/jpeg'                     // Content type
        );
        console.log('🎉 Face replacement result:', result1);
        
        // Example 2: Replace clothing
        const clothingPrompts = lightx.getSuggestedPrompts('clothing');
        const result2 = await lightx.processReplacement(
            './path/to/original-image.jpg',
            './path/to/mask-image.png',
            clothingPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Clothing replacement result:', result2);
        
        // Example 3: Replace background
        const backgroundPrompts = lightx.getSuggestedPrompts('background');
        const result3 = await lightx.processReplacement(
            './path/to/original-image.jpg',
            './path/to/mask-image.png',
            backgroundPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Background replacement result:', result3);
        
    } catch (error) {
        console.error('Example failed:', error.message);
    }
}

// Export for use in other modules
module.exports = LightXReplaceAPI;

// Run example if this file is executed directly
if (require.main === module) {
    example();
}
