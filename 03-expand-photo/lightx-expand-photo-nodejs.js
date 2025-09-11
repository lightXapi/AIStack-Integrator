/**
 * LightX AI Expand Photo (Outpainting) API Integration - JavaScript/Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered photo expansion functionality using padding-based outpainting.
 */

const axios = require('axios');
const fs = require('fs');
const FormData = require('form-data');

class LightXExpandPhotoAPI {
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
     * Expand photo using AI outpainting
     * @param {string} imageUrl - URL of the uploaded image
     * @param {Object} padding - Padding configuration
     * @param {number} padding.leftPadding - Left padding in pixels
     * @param {number} padding.rightPadding - Right padding in pixels
     * @param {number} padding.topPadding - Top padding in pixels
     * @param {number} padding.bottomPadding - Bottom padding in pixels
     * @returns {Promise<string>} - Order ID for tracking
     */
    async expandPhoto(imageUrl, padding) {
        try {
            const { leftPadding = 0, rightPadding = 0, topPadding = 0, bottomPadding = 0 } = padding;

            const response = await axios.post(
                `${this.baseUrl}/v1/expand-photo`,
                {
                    imageUrl: imageUrl,
                    leftPadding: leftPadding,
                    rightPadding: rightPadding,
                    topPadding: topPadding,
                    bottomPadding: bottomPadding
                },
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (response.data.statusCode !== 2000) {
                throw new Error(`Expand photo request failed: ${response.data.message}`);
            }

            const { orderId, maxRetriesAllowed, avgResponseTimeInSec, status } = response.data.body;
            
            console.log(`📋 Order created: ${orderId}`);
            console.log(`🔄 Max retries allowed: ${maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${status}`);
            console.log(`📐 Padding: L:${leftPadding} R:${rightPadding} T:${topPadding} B:${bottomPadding}`);

            return orderId;

        } catch (error) {
            console.error('Error expanding photo:', error.message);
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
                    console.log('✅ Photo expansion completed successfully!');
                    console.log(`🖼️  Expanded image: ${status.output}`);
                    return status;
                } else if (status.status === 'failed') {
                    throw new Error('Photo expansion failed');
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
     * Complete workflow: Upload image and expand photo
     * @param {string} imagePath - Path to the image file
     * @param {Object} padding - Padding configuration
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async processExpansion(imagePath, padding, contentType = 'image/jpeg') {
        try {
            console.log('🚀 Starting LightX AI Expand Photo API workflow...');
            
            // Step 1: Upload image
            console.log('📤 Uploading image...');
            const imageUrl = await this.uploadImage(imagePath, contentType);
            console.log(`✅ Image uploaded: ${imageUrl}`);
            
            // Step 2: Expand photo
            console.log('🖼️  Expanding photo with AI...');
            const orderId = await this.expandPhoto(imageUrl, padding);
            
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
     * Create padding configuration for common expansion scenarios
     * @param {string} direction - 'horizontal', 'vertical', 'all', or 'custom'
     * @param {number} amount - Padding amount in pixels
     * @param {Object} customPadding - Custom padding object (for 'custom' direction)
     * @returns {Object} - Padding configuration
     */
    createPaddingConfig(direction, amount = 100, customPadding = {}) {
        const paddingConfigs = {
            horizontal: {
                leftPadding: amount,
                rightPadding: amount,
                topPadding: 0,
                bottomPadding: 0
            },
            vertical: {
                leftPadding: 0,
                rightPadding: 0,
                topPadding: amount,
                bottomPadding: amount
            },
            all: {
                leftPadding: amount,
                rightPadding: amount,
                topPadding: amount,
                bottomPadding: amount
            },
            custom: customPadding
        };

        const config = paddingConfigs[direction];
        if (!config) {
            throw new Error(`Invalid direction: ${direction}. Use 'horizontal', 'vertical', 'all', or 'custom'`);
        }

        console.log(`📐 Created ${direction} padding config:`, config);
        return config;
    }

    /**
     * Expand photo to specific aspect ratio
     * @param {string} imagePath - Path to the image file
     * @param {number} targetWidth - Target width
     * @param {number} targetHeight - Target height
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async expandToAspectRatio(imagePath, targetWidth, targetHeight, contentType = 'image/jpeg') {
        try {
            // This is a utility function - in a real implementation, you'd need to
            // get the original image dimensions first to calculate the required padding
            console.log(`🎯 Expanding to aspect ratio: ${targetWidth}x${targetHeight}`);
            console.log('⚠️  Note: This requires original image dimensions to calculate padding');
            
            // For demonstration, we'll use equal padding
            const padding = this.createPaddingConfig('all', 100);
            return await this.processExpansion(imagePath, padding, contentType);
            
        } catch (error) {
            console.error('Error expanding to aspect ratio:', error.message);
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
        const lightx = new LightXExpandPhotoAPI('YOUR_API_KEY_HERE');
        
        // Example 1: Expand horizontally
        const horizontalPadding = lightx.createPaddingConfig('horizontal', 150);
        const result1 = await lightx.processExpansion(
            './path/to/your/image.jpg',
            horizontalPadding,
            'image/jpeg'
        );
        console.log('🎉 Horizontal expansion result:', result1);
        
        // Example 2: Expand vertically
        const verticalPadding = lightx.createPaddingConfig('vertical', 200);
        const result2 = await lightx.processExpansion(
            './path/to/your/image.jpg',
            verticalPadding,
            'image/jpeg'
        );
        console.log('🎉 Vertical expansion result:', result2);
        
        // Example 3: Expand all sides equally
        const allSidesPadding = lightx.createPaddingConfig('all', 100);
        const result3 = await lightx.processExpansion(
            './path/to/your/image.jpg',
            allSidesPadding,
            'image/jpeg'
        );
        console.log('🎉 All-sides expansion result:', result3);
        
        // Example 4: Custom padding
        const customPadding = {
            leftPadding: 50,
            rightPadding: 200,
            topPadding: 75,
            bottomPadding: 125
        };
        const result4 = await lightx.processExpansion(
            './path/to/your/image.jpg',
            customPadding,
            'image/jpeg'
        );
        console.log('🎉 Custom expansion result:', result4);
        
    } catch (error) {
        console.error('Example failed:', error.message);
    }
}

// Export for use in other modules
module.exports = LightXExpandPhotoAPI;

// Run example if this file is executed directly
if (require.main === module) {
    example();
}
