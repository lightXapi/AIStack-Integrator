/**
 * LightX Remove Background API Integration - JavaScript/Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for image upload and background removal functionality.
 */

const axios = require('axios');
const fs = require('fs');
const FormData = require('form-data');

class LightXAPI {
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

            console.log('Image uploaded successfully');
            return imageUrl;

        } catch (error) {
            console.error('Error uploading image:', error.message);
            throw error;
        }
    }

    /**
     * Remove background from image
     * @param {string} imageUrl - URL of the uploaded image
     * @param {string} background - Background color, color code, or image URL
     * @returns {Promise<string>} - Order ID for tracking
     */
    async removeBackground(imageUrl, background = 'transparent') {
        try {
            const response = await axios.post(
                `${this.baseUrl}/v1/remove-background`,
                {
                    imageUrl: imageUrl,
                    background: background
                },
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (response.data.statusCode !== 2000) {
                throw new Error(`Background removal request failed: ${response.data.message}`);
            }

            const { orderId, maxRetriesAllowed, avgResponseTimeInSec, status } = response.data.body;
            
            console.log(`Order created: ${orderId}`);
            console.log(`Max retries allowed: ${maxRetriesAllowed}`);
            console.log(`Average response time: ${avgResponseTimeInSec} seconds`);
            console.log(`Status: ${status}`);

            return orderId;

        } catch (error) {
            console.error('Error removing background:', error.message);
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
     * @returns {Promise<Object>} - Final result with output URLs
     */
    async waitForCompletion(orderId) {
        let attempts = 0;
        
        while (attempts < this.maxRetries) {
            try {
                const status = await this.checkOrderStatus(orderId);
                
                console.log(`Attempt ${attempts + 1}: Status - ${status.status}`);
                
                if (status.status === 'active') {
                    console.log('✅ Background removal completed successfully!');
                    console.log(`Output image: ${status.output}`);
                    console.log(`Mask image: ${status.mask}`);
                    return status;
                } else if (status.status === 'failed') {
                    throw new Error('Background removal failed');
                } else if (status.status === 'init') {
                    // Still processing, wait and retry
                    attempts++;
                    if (attempts < this.maxRetries) {
                        console.log(`Waiting ${this.retryInterval/1000} seconds before next check...`);
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
     * Complete workflow: Upload image and remove background
     * @param {string} imagePath - Path to the image file
     * @param {string} background - Background color/code/URL
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URLs
     */
    async processImage(imagePath, background = 'transparent', contentType = 'image/jpeg') {
        try {
            console.log('🚀 Starting LightX API workflow...');
            
            // Step 1: Upload image
            console.log('📤 Uploading image...');
            const imageUrl = await this.uploadImage(imagePath, contentType);
            console.log(`✅ Image uploaded: ${imageUrl}`);
            
            // Step 2: Remove background
            console.log('🎨 Removing background...');
            const orderId = await this.removeBackground(imageUrl, background);
            
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
        const lightx = new LightXAPI('YOUR_API_KEY_HERE');
        
        // Process an image
        const result = await lightx.processImage(
            './path/to/your/image.jpg',  // Image path
            'white',                     // Background color
            'image/jpeg'                 // Content type
        );
        
        console.log('🎉 Final result:', result);
        
    } catch (error) {
        console.error('Example failed:', error.message);
    }
}

// Export for use in other modules
module.exports = LightXAPI;

// Run example if this file is executed directly
if (require.main === module) {
    example();
}
