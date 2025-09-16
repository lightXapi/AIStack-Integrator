/**
 * LightX Watermark Remover API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered watermark removal functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXWatermarkRemoverAPI {
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
     * Remove watermark from image
     * @param {string} imageUrl - URL of the input image
     * @returns {Promise<string>} Order ID for tracking
     */
    async removeWatermark(imageUrl) {
        const endpoint = `${this.baseURL}/v2/watermark-remover/`;

        const payload = {
            imageUrl: imageUrl
        };

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Watermark removal request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`🖼️  Input image: ${imageUrl}`);

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
                        console.log('✅ Watermark removed successfully!');
                        if (status.output) {
                            console.log(`🖼️  Clean image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Watermark removal failed');

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
     * Complete workflow: Upload image and remove watermark
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processWatermarkRemoval(imageData, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX Watermark Remover API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Remove watermark
        console.log('🧹 Removing watermark...');
        const orderId = await this.removeWatermark(imageUrl);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get watermark removal tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getWatermarkRemovalTips() {
        const tips = {
            input_image: [
                'Use high-quality images with clear watermarks',
                'Ensure the image is at least 512x512 pixels',
                'Avoid heavily compressed or low-quality source images',
                'Use images with good contrast and lighting',
                'Ensure watermarks are clearly visible in the image'
            ],
            watermark_types: [
                'Text watermarks: Works best with clear, readable text',
                'Logo watermarks: Effective with distinct logo shapes',
                'Pattern watermarks: Good for repetitive patterns',
                'Transparent watermarks: Handles semi-transparent overlays',
                'Complex watermarks: May require multiple processing attempts'
            ],
            image_quality: [
                'Higher resolution images produce better results',
                'Good lighting and contrast improve watermark detection',
                'Avoid images with excessive noise or artifacts',
                'Clear, sharp images work better than blurry ones',
                'Well-exposed images provide better results'
            ],
            general: [
                'AI watermark removal works best with clearly visible watermarks',
                'Results may vary based on watermark complexity and image quality',
                'Allow 15-30 seconds for processing',
                'Some watermarks may require multiple processing attempts',
                'The tool preserves image quality while removing watermarks'
            ]
        };

        console.log('💡 Watermark Removal Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get watermark removal use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getWatermarkRemovalUseCases() {
        const useCases = {
            e_commerce: [
                'Remove watermarks from product photos',
                'Clean up stock images for online stores',
                'Prepare images for product catalogs',
                'Remove branding from supplier images',
                'Create clean product listings'
            ],
            photo_editing: [
                'Remove watermarks from edited photos',
                'Clean up images for personal use',
                'Remove copyright watermarks',
                'Prepare images for printing',
                'Clean up stock photo watermarks'
            ],
            news_publishing: [
                'Remove watermarks from news images',
                'Clean up press photos',
                'Remove agency watermarks',
                'Prepare images for articles',
                'Clean up editorial images'
            ],
            social_media: [
                'Remove watermarks from social media images',
                'Clean up images for posts',
                'Remove branding from shared images',
                'Prepare images for profiles',
                'Clean up user-generated content'
            ],
            creative_projects: [
                'Remove watermarks from design assets',
                'Clean up images for presentations',
                'Remove branding from templates',
                'Prepare images for portfolios',
                'Clean up creative resources'
            ]
        };

        console.log('💡 Watermark Removal Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Get supported image formats and requirements
     * @returns {Object} Object containing format information
     */
    getSupportedFormats() {
        const formats = {
            input_formats: {
                'JPEG': 'Most common format, good for photos',
                'PNG': 'Supports transparency, good for graphics',
                'WebP': 'Modern format with good compression'
            },
            output_format: {
                'JPEG': 'Standard output format for compatibility'
            },
            requirements: {
                'minimum_size': '512x512 pixels',
                'maximum_size': '5MB file size',
                'color_space': 'RGB or sRGB',
                'compression': 'Any standard compression level'
            },
            recommendations: {
                'resolution': 'Higher resolution images produce better results',
                'quality': 'Use high-quality source images',
                'format': 'JPEG is recommended for photos',
                'size': 'Larger images allow better watermark detection'
            }
        };

        console.log('📋 Supported Formats and Requirements:');
        for (const [category, info] of Object.entries(formats)) {
            console.log(`${category}: ${JSON.stringify(info, null, 2)}`);
        }
        return formats;
    }

    /**
     * Get watermark detection capabilities
     * @returns {Object} Object containing detection information
     */
    getWatermarkDetectionCapabilities() {
        const capabilities = {
            detection_types: [
                'Text watermarks with various fonts and styles',
                'Logo watermarks with different shapes and colors',
                'Pattern watermarks with repetitive designs',
                'Transparent watermarks with varying opacity',
                'Complex watermarks with multiple elements'
            ],
            coverage_areas: [
                'Full image watermarks covering the entire image',
                'Corner watermarks in specific image areas',
                'Center watermarks in the middle of images',
                'Scattered watermarks across multiple areas',
                'Border watermarks along image edges'
            ],
            processing_features: [
                'Automatic watermark detection and removal',
                'Preserves original image quality and details',
                'Maintains image composition and structure',
                'Handles various watermark sizes and positions',
                'Works with different image backgrounds'
            ],
            limitations: [
                'Very small or subtle watermarks may be challenging',
                'Watermarks that blend with image content',
                'Extremely complex or artistic watermarks',
                'Watermarks that are part of the main subject',
                'Very low resolution or poor quality images'
            ]
        };

        console.log('🔍 Watermark Detection Capabilities:');
        for (const [category, capabilityList] of Object.entries(capabilities)) {
            console.log(`${category}: ${capabilityList}`);
        }
        return capabilities;
    }

    /**
     * Validate image data (utility function)
     * @param {Buffer|string} imageData - Image data to validate
     * @returns {boolean} Whether the image data is valid
     */
    validateImageData(imageData) {
        if (!imageData) {
            console.log('❌ Image data cannot be empty');
            return false;
        }

        if (Buffer.isBuffer(imageData)) {
            if (imageData.length === 0) {
                console.log('❌ Image buffer is empty');
                return false;
            }
            if (imageData.length > this.maxFileSize) {
                console.log('❌ Image size exceeds 5MB limit');
                return false;
            }
        } else if (typeof imageData === 'string') {
            if (!fs.existsSync(imageData)) {
                console.log('❌ Image file does not exist');
                return false;
            }
            const stats = fs.statSync(imageData);
            if (stats.size > this.maxFileSize) {
                console.log('❌ Image file size exceeds 5MB limit');
                return false;
            }
        } else {
            console.log('❌ Invalid image data type');
            return false;
        }

        console.log('✅ Image data is valid');
        return true;
    }

    /**
     * Process watermark removal with validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processWatermarkRemovalWithValidation(imageData, contentType = 'image/jpeg') {
        if (!this.validateImageData(imageData)) {
            throw new Error('Invalid image data');
        }

        return this.processWatermarkRemoval(imageData, contentType);
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
        const lightx = new LightXWatermarkRemoverAPI('YOUR_API_KEY_HERE');

        // Get tips and information
        lightx.getWatermarkRemovalTips();
        lightx.getWatermarkRemovalUseCases();
        lightx.getSupportedFormats();
        lightx.getWatermarkDetectionCapabilities();

        // Example 1: Process image from file path
        const result1 = await lightx.processWatermarkRemovalWithValidation(
            'path/to/watermarked-image.jpg',
            'image/jpeg'
        );
        console.log('🎉 Watermark removal result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Process image from buffer
        const imageBuffer = fs.readFileSync('path/to/another-image.png');
        const result2 = await lightx.processWatermarkRemovalWithValidation(
            imageBuffer,
            'image/png'
        );
        console.log('🎉 Second watermark removal result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Process multiple images
        const imagePaths = [
            'path/to/image1.jpg',
            'path/to/image2.png',
            'path/to/image3.jpg'
        ];

        for (const imagePath of imagePaths) {
            const result = await lightx.processWatermarkRemovalWithValidation(
                imagePath,
                'image/jpeg'
            );
            console.log(`🎉 ${imagePath} watermark removal result:`);
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
module.exports = LightXWatermarkRemoverAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
