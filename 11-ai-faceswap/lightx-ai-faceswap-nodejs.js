/**
 * LightX AI Face Swap API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered face swap functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXFaceSwapAPI {
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
     * Upload multiple images (source and target images)
     * @param {Buffer|string} sourceImageData - Source image data or file path
     * @param {Buffer|string} targetImageData - Target image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<{sourceURL: string, targetURL: string}>}
     */
    async uploadImages(sourceImageData, targetImageData, contentType = 'image/jpeg') {
        console.log('📤 Uploading source image...');
        const sourceURL = await this.uploadImage(sourceImageData, contentType);

        console.log('📤 Uploading target image...');
        const targetURL = await this.uploadImage(targetImageData, contentType);

        return { sourceURL, targetURL };
    }

    /**
     * Generate face swap
     * @param {string} imageUrl - URL of the source image (face to be swapped)
     * @param {string} styleImageUrl - URL of the target image (face to be replaced)
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateFaceSwap(imageUrl, styleImageUrl) {
        const endpoint = `${this.baseURL}/v1/face-swap`;

        const payload = {
            imageUrl: imageUrl,
            styleImageUrl: styleImageUrl
        };

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Face swap request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`🎭 Source image: ${imageUrl}`);
            console.log(`🎯 Target image: ${styleImageUrl}`);

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
                        console.log('✅ Face swap completed successfully!');
                        if (status.output) {
                            console.log(`🔄 Face swap result: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Face swap failed');

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
     * Complete workflow: Upload images and generate face swap
     * @param {Buffer|string} sourceImageData - Source image data or file path
     * @param {Buffer|string} targetImageData - Target image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processFaceSwap(sourceImageData, targetImageData, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Face Swap API workflow...');

        // Step 1: Upload images
        console.log('📤 Uploading images...');
        const { sourceURL, targetURL } = await this.uploadImages(sourceImageData, targetImageData, contentType);
        console.log(`✅ Source image uploaded: ${sourceURL}`);
        console.log(`✅ Target image uploaded: ${targetURL}`);

        // Step 2: Generate face swap
        console.log('🔄 Generating face swap...');
        const orderId = await this.generateFaceSwap(sourceURL, targetURL);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get face swap tips and best practices
     * @returns {Object} Object containing tips for better face swap results
     */
    getFaceSwapTips() {
        const tips = {
            source_image: [
                'Use clear, well-lit photos with good contrast',
                'Ensure the face is clearly visible and centered',
                'Avoid photos with multiple people',
                'Use high-resolution images for better results',
                'Front-facing photos work best for face swapping'
            ],
            target_image: [
                'Choose target images with clear facial features',
                'Ensure target face is clearly visible and well-lit',
                'Use images with similar lighting conditions',
                'Avoid heavily edited or filtered images',
                'Use high-quality target reference images'
            ],
            general: [
                'Face swaps work best with clear human faces',
                'Results may vary based on input image quality',
                'Similar lighting conditions improve results',
                'Front-facing photos produce better face swaps',
                'Allow 15-30 seconds for processing'
            ]
        };

        console.log('💡 Face Swap Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get face swap use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getFaceSwapUseCases() {
        const useCases = {
            entertainment: [
                'Movie character face swaps',
                'Celebrity face swaps',
                'Historical figure face swaps',
                'Fantasy character face swaps',
                'Comedy and entertainment content'
            ],
            creative: [
                'Artistic face swap projects',
                'Creative photo manipulation',
                'Digital art creation',
                'Social media content',
                'Memes and viral content'
            ],
            professional: [
                'Film and video production',
                'Marketing and advertising',
                'Educational content',
                'Training materials',
                'Presentation graphics'
            ],
            personal: [
                'Fun personal photos',
                'Family photo editing',
                'Social media posts',
                'Party and event photos',
                'Creative selfies'
            ]
        };

        console.log('💡 Face Swap Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
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
     * Validate images for face swap (utility function)
     * @param {Buffer|string} sourceImageData - Source image data or file path
     * @param {Buffer|string} targetImageData - Target image data or file path
     * @returns {Promise<boolean>} Whether images are valid for face swap
     */
    async validateImagesForFaceSwap(sourceImageData, targetImageData) {
        try {
            // Check if both images exist and are valid
            const sourceDimensions = await this.getImageDimensions(sourceImageData);
            const targetDimensions = await this.getImageDimensions(targetImageData);

            if (sourceDimensions.width === 0 || targetDimensions.width === 0) {
                console.log('❌ Invalid image dimensions detected');
                return false;
            }

            // Additional validation could include:
            // - Face detection
            // - Image quality assessment
            // - Lighting condition analysis
            // - Resolution requirements

            console.log('✅ Images validated for face swap');
            return true;

        } catch (error) {
            console.log(`❌ Image validation failed: ${error.message}`);
            return false;
        }
    }

    /**
     * Get face swap quality tips
     * @returns {Object} Object containing quality improvement tips
     */
    getFaceSwapQualityTips() {
        const qualityTips = {
            lighting: [
                'Use similar lighting conditions in both images',
                'Avoid harsh shadows on faces',
                'Ensure even lighting across the face',
                'Natural lighting often produces better results',
                'Avoid backlit or silhouette images'
            ],
            angle: [
                'Use front-facing photos for best results',
                'Avoid extreme angles or tilted heads',
                'Keep faces centered in the frame',
                'Similar head angles improve face swap quality',
                'Avoid profile shots for optimal results'
            ],
            resolution: [
                'Use high-resolution images when possible',
                'Ensure clear facial features are visible',
                'Avoid heavily compressed images',
                'Good image quality improves face swap accuracy',
                'Minimum 512x512 pixels recommended'
            ],
            expression: [
                'Neutral expressions often work best',
                'Similar facial expressions improve results',
                'Avoid extreme expressions or emotions',
                'Natural expressions produce better face swaps',
                'Consider the context of the target image'
            ]
        };

        console.log('💡 Face Swap Quality Tips:');
        for (const [category, tipList] of Object.entries(qualityTips)) {
            console.log(`${category}: ${tipList}`);
        }
        return qualityTips;
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
        const lightx = new LightXFaceSwapAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getFaceSwapTips();
        lightx.getFaceSwapUseCases();
        lightx.getFaceSwapQualityTips();

        // Load images (replace with your image loading logic)
        const sourceImagePath = 'path/to/source-image.jpg';
        const targetImagePath = 'path/to/target-image.jpg';

        // Validate images before processing
        const isValid = await lightx.validateImagesForFaceSwap(sourceImagePath, targetImagePath);
        if (!isValid) {
            console.log('❌ Images are not suitable for face swap');
            return;
        }

        // Example 1: Basic face swap
        const result1 = await lightx.processFaceSwap(
            sourceImagePath,
            targetImagePath,
            'image/jpeg'
        );
        console.log('🎉 Face swap result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Get image dimensions
        const sourceDimensions = await lightx.getImageDimensions(sourceImagePath);
        const targetDimensions = await lightx.getImageDimensions(targetImagePath);
        
        if (sourceDimensions.width > 0 && sourceDimensions.height > 0) {
            console.log(`📏 Source image: ${sourceDimensions.width}x${sourceDimensions.height}`);
        }
        if (targetDimensions.width > 0 && targetDimensions.height > 0) {
            console.log(`📏 Target image: ${targetDimensions.width}x${targetDimensions.height}`);
        }

        // Example 3: Multiple face swaps with different image pairs
        const imagePairs = [
            { source: 'path/to/source1.jpg', target: 'path/to/target1.jpg' },
            { source: 'path/to/source2.jpg', target: 'path/to/target2.jpg' },
            { source: 'path/to/source3.jpg', target: 'path/to/target3.jpg' }
        ];

        for (let i = 0; i < imagePairs.length; i++) {
            const pair = imagePairs[i];
            console.log(`\n🎭 Processing face swap ${i + 1}...`);
            
            try {
                const result = await lightx.processFaceSwap(
                    pair.source,
                    pair.target,
                    'image/jpeg'
                );
                console.log(`✅ Face swap ${i + 1} completed:`);
                console.log(`Order ID: ${result.orderId}`);
                console.log(`Status: ${result.status}`);
                if (result.output) {
                    console.log(`Output: ${result.output}`);
                }
            } catch (error) {
                console.log(`❌ Face swap ${i + 1} failed: ${error.message}`);
            }
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXFaceSwapAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
