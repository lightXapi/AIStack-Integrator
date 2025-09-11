/**
 * LightX Cleanup Picture API Integration - JavaScript/Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for image cleanup functionality using mask-based object removal.
 */

const axios = require('axios');
const fs = require('fs');
const FormData = require('form-data');

class LightXCleanupAPI {
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
     * Cleanup picture using mask
     * @param {string} imageUrl - URL of the original image
     * @param {string} maskedImageUrl - URL of the mask image
     * @returns {Promise<string>} - Order ID for tracking
     */
    async cleanupPicture(imageUrl, maskedImageUrl) {
        try {
            const response = await axios.post(
                `${this.baseUrl}/v1/cleanup-picture`,
                {
                    imageUrl: imageUrl,
                    maskedImageUrl: maskedImageUrl
                },
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (response.data.statusCode !== 2000) {
                throw new Error(`Cleanup picture request failed: ${response.data.message}`);
            }

            const { orderId, maxRetriesAllowed, avgResponseTimeInSec, status } = response.data.body;
            
            console.log(`📋 Order created: ${orderId}`);
            console.log(`🔄 Max retries allowed: ${maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${status}`);

            return orderId;

        } catch (error) {
            console.error('Error cleaning up picture:', error.message);
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
                    console.log('✅ Picture cleanup completed successfully!');
                    console.log(`🖼️  Output image: ${status.output}`);
                    return status;
                } else if (status.status === 'failed') {
                    throw new Error('Picture cleanup failed');
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
     * Complete workflow: Upload images and cleanup picture
     * @param {string} originalImagePath - Path to the original image
     * @param {string} maskImagePath - Path to the mask image
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async processCleanup(originalImagePath, maskImagePath, contentType = 'image/jpeg') {
        try {
            console.log('🚀 Starting LightX Cleanup Picture API workflow...');
            
            // Step 1: Upload both images
            console.log('📤 Uploading images...');
            const { originalUrl, maskUrl } = await this.uploadImages(originalImagePath, maskImagePath, contentType);
            console.log(`✅ Original image uploaded: ${originalUrl}`);
            console.log(`✅ Mask image uploaded: ${maskUrl}`);
            
            // Step 2: Cleanup picture
            console.log('🧹 Cleaning up picture...');
            const orderId = await this.cleanupPicture(originalUrl, maskUrl);
            
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
        const lightx = new LightXCleanupAPI('YOUR_API_KEY_HERE');
        
        // Process cleanup with original image and mask
        const result = await lightx.processCleanup(
            './path/to/original-image.jpg',  // Original image path
            './path/to/mask-image.png',      // Mask image path (white areas = remove, black = keep)
            'image/jpeg'                     // Content type
        );
        
        console.log('🎉 Final result:', result);
        
    } catch (error) {
        console.error('Example failed:', error.message);
    }
}

// Export for use in other modules
module.exports = LightXCleanupAPI;

// Run example if this file is executed directly
if (require.main === module) {
    example();
}
