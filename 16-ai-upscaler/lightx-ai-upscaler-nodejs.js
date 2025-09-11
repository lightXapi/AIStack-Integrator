/**
 * LightX AI Image Upscaler API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image upscaling functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXImageUpscalerAPI {
    constructor(apiKey) {
        this.apiKey = apiKey;
        this.baseURL = 'https://api.lightxeditor.com/external/api';
        this.maxRetries = 5;
        this.retryInterval = 3000; // 3 seconds
        this.maxFileSize = 5242880; // 5MB
        this.maxImageDimension = 2048; // Maximum dimension for upscaling
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
     * Generate image upscaling
     * @param {string} imageUrl - URL of the input image
     * @param {number} quality - Upscaling quality (2 or 4)
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateUpscale(imageUrl, quality) {
        const endpoint = `${this.baseURL}/v2/upscale/`;

        const payload = {
            imageUrl: imageUrl,
            quality: quality
        };

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Upscale request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`🔍 Upscale quality: ${quality}x`);

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
                        console.log('✅ Image upscaling completed successfully!');
                        if (status.output) {
                            console.log(`🔍 Upscaled image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Image upscaling failed');

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
     * Complete workflow: Upload image and generate upscaling
     * @param {Buffer|string} imageData - Image data or file path
     * @param {number} quality - Upscaling quality (2 or 4)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processUpscaling(imageData, quality, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Image Upscaler API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Generate upscaling
        console.log('🔍 Generating image upscaling...');
        const orderId = await this.generateUpscale(imageUrl, quality);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get image upscaling tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getUpscalingTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit images with good contrast',
                'Ensure the image is not already at maximum resolution',
                'Avoid heavily compressed or low-quality source images',
                'Use high-quality source images for best upscaling results',
                'Good source image quality improves upscaling results'
            ],
            image_dimensions: [
                'Images 1024x1024 or smaller can be upscaled 2x or 4x',
                'Images larger than 1024x1024 but smaller than 2048x2048 can only be upscaled 2x',
                'Images larger than 2048x2048 cannot be upscaled',
                'Check image dimensions before attempting upscaling',
                'Resize large images before upscaling if needed'
            ],
            quality_selection: [
                'Use 2x upscaling for moderate quality improvement',
                'Use 4x upscaling for maximum quality improvement',
                '4x upscaling works best on smaller images (1024x1024 or less)',
                'Consider file size increase with higher upscaling factors',
                'Choose quality based on your specific needs'
            ],
            general: [
                'Image upscaling works best with clear, detailed source images',
                'Results may vary based on input image quality and content',
                'Upscaling preserves detail while enhancing resolution',
                'Allow 15-30 seconds for processing',
                'Experiment with different quality settings for optimal results'
            ]
        };

        console.log('💡 Image Upscaling Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get upscaling quality suggestions
     * @returns {Object} Object containing quality suggestions
     */
    getQualitySuggestions() {
        const qualitySuggestions = {
            '2x': {
                description: 'Moderate quality improvement with 2x resolution increase',
                best_for: [
                    'General image enhancement',
                    'Social media images',
                    'Web display images',
                    'Moderate quality improvement needs',
                    'Balanced quality and file size'
                ],
                use_cases: [
                    'Enhancing photos for social media',
                    'Improving web images',
                    'General image quality enhancement',
                    'Preparing images for print at moderate sizes'
                ]
            },
            '4x': {
                description: 'Maximum quality improvement with 4x resolution increase',
                best_for: [
                    'High-quality image enhancement',
                    'Print-ready images',
                    'Professional photography',
                    'Maximum detail preservation',
                    'Large format displays'
                ],
                use_cases: [
                    'Professional photography enhancement',
                    'Print-ready image preparation',
                    'Large format display images',
                    'Maximum quality requirements',
                    'Archival image enhancement'
                ]
            }
        };

        console.log('💡 Upscaling Quality Suggestions:');
        for (const [quality, suggestion] of Object.entries(qualitySuggestions)) {
            console.log(`${quality}: ${suggestion.description}`);
            console.log(`  Best for: ${suggestion.best_for.join(', ')}`);
            console.log(`  Use cases: ${suggestion.use_cases.join(', ')}`);
        }
        return qualitySuggestions;
    }

    /**
     * Get image dimension guidelines
     * @returns {Object} Object containing dimension guidelines
     */
    getDimensionGuidelines() {
        const dimensionGuidelines = {
            'small_images': {
                range: 'Up to 1024x1024 pixels',
                upscaling_options: ['2x upscaling', '4x upscaling'],
                description: 'Small images can be upscaled with both 2x and 4x quality',
                examples: ['Profile pictures', 'Thumbnails', 'Small photos', 'Icons']
            },
            'medium_images': {
                range: '1024x1024 to 2048x2048 pixels',
                upscaling_options: ['2x upscaling only'],
                description: 'Medium images can only be upscaled with 2x quality',
                examples: ['Standard photos', 'Web images', 'Medium prints']
            },
            'large_images': {
                range: 'Larger than 2048x2048 pixels',
                upscaling_options: ['Cannot be upscaled'],
                description: 'Large images cannot be upscaled and will show an error',
                examples: ['High-resolution photos', 'Large prints', 'Professional images']
            }
        };

        console.log('💡 Image Dimension Guidelines:');
        for (const [category, info] of Object.entries(dimensionGuidelines)) {
            console.log(`${category}: ${info.range}`);
            console.log(`  Upscaling options: ${info.upscaling_options.join(', ')}`);
            console.log(`  Description: ${info.description}`);
            console.log(`  Examples: ${info.examples.join(', ')}`);
        }
        return dimensionGuidelines;
    }

    /**
     * Get upscaling use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getUpscalingUseCases() {
        const useCases = {
            'photography': [
                'Enhance low-resolution photos',
                'Prepare images for large prints',
                'Improve vintage photo quality',
                'Enhance smartphone photos',
                'Professional photo enhancement'
            ],
            'web_design': [
                'Create high-DPI images for retina displays',
                'Enhance images for modern web standards',
                'Improve image quality for websites',
                'Create responsive image assets',
                'Enhance social media images'
            ],
            'print_media': [
                'Prepare images for large format printing',
                'Enhance images for magazine quality',
                'Improve poster and banner images',
                'Enhance images for professional printing',
                'Create high-resolution marketing materials'
            ],
            'archival': [
                'Enhance historical photographs',
                'Improve scanned document quality',
                'Restore old family photos',
                'Enhance archival images',
                'Preserve and enhance historical content'
            ],
            'creative': [
                'Enhance digital art and illustrations',
                'Improve concept art quality',
                'Enhance graphic design elements',
                'Improve texture and pattern quality',
                'Enhance creative project assets'
            ]
        };

        console.log('💡 Upscaling Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Validate parameters (utility function)
     * @param {number} quality - Quality parameter to validate
     * @param {number} width - Image width to validate
     * @param {number} height - Image height to validate
     * @returns {boolean} Whether the parameters are valid
     */
    validateParameters(quality, width, height) {
        // Validate quality
        if (quality !== 2 && quality !== 4) {
            console.log('❌ Quality must be 2 or 4');
            return false;
        }

        // Validate image dimensions
        const maxDimension = Math.max(width, height);
        
        if (maxDimension > this.maxImageDimension) {
            console.log(`❌ Image dimension (${maxDimension}px) exceeds maximum allowed (${this.maxImageDimension}px)`);
            return false;
        }

        // Check quality vs dimension compatibility
        if (maxDimension > 1024 && quality === 4) {
            console.log('❌ 4x upscaling is only available for images 1024x1024 or smaller');
            return false;
        }

        console.log('✅ Parameters are valid');
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
     * Generate upscaling with parameter validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {number} quality - Upscaling quality (2 or 4)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateUpscaleWithValidation(imageData, quality, contentType = 'image/jpeg') {
        // Get image dimensions for validation
        const dimensions = await this.getImageDimensions(imageData);
        
        if (!this.validateParameters(quality, dimensions.width, dimensions.height)) {
            throw new Error('Invalid parameters');
        }

        return this.processUpscaling(imageData, quality, contentType);
    }

    /**
     * Get recommended quality based on image dimensions
     * @param {number} width - Image width
     * @param {number} height - Image height
     * @returns {Object} Recommended quality options
     */
    getRecommendedQuality(width, height) {
        const maxDimension = Math.max(width, height);
        
        if (maxDimension <= 1024) {
            return {
                available: [2, 4],
                recommended: 4,
                reason: 'Small images can use 4x upscaling for maximum quality'
            };
        } else if (maxDimension <= 2048) {
            return {
                available: [2],
                recommended: 2,
                reason: 'Medium images can only use 2x upscaling'
            };
        } else {
            return {
                available: [],
                recommended: null,
                reason: 'Large images cannot be upscaled'
            };
        }
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
        const lightx = new LightXImageUpscalerAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getUpscalingTips();
        lightx.getQualitySuggestions();
        lightx.getDimensionGuidelines();
        lightx.getUpscalingUseCases();

        // Load image (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';

        // Example 1: 2x upscaling
        const result1 = await lightx.generateUpscaleWithValidation(
            imagePath,
            2, // 2x upscaling
            'image/jpeg'
        );
        console.log('🎉 2x upscaling result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: 4x upscaling (if image is small enough)
        const dimensions = await lightx.getImageDimensions(imagePath);
        const qualityRecommendation = lightx.getRecommendedQuality(dimensions.width, dimensions.height);
        
        if (qualityRecommendation.available.includes(4)) {
            const result2 = await lightx.generateUpscaleWithValidation(
                imagePath,
                4, // 4x upscaling
                'image/jpeg'
            );
            console.log('🎉 4x upscaling result:');
            console.log(`Order ID: ${result2.orderId}`);
            console.log(`Status: ${result2.status}`);
            if (result2.output) {
                console.log(`Output: ${result2.output}`);
            }
        } else {
            console.log(`⚠️  4x upscaling not available for this image size: ${qualityRecommendation.reason}`);
        }

        // Example 3: Try different quality settings
        const qualityOptions = [2, 4];
        for (const quality of qualityOptions) {
            try {
                const result = await lightx.generateUpscaleWithValidation(
                    imagePath,
                    quality,
                    'image/jpeg'
                );
                console.log(`🎉 ${quality}x upscaling result:`);
                console.log(`Order ID: ${result.orderId}`);
                console.log(`Status: ${result.status}`);
                if (result.output) {
                    console.log(`Output: ${result.output}`);
                }
            } catch (error) {
                console.log(`❌ ${quality}x upscaling failed: ${error.message}`);
            }
        }

        // Example 4: Get image dimensions and recommendations
        const finalDimensions = await lightx.getImageDimensions(imagePath);
        if (finalDimensions.width > 0 && finalDimensions.height > 0) {
            console.log(`📏 Original image: ${finalDimensions.width}x${finalDimensions.height}`);
            const recommendation = lightx.getRecommendedQuality(finalDimensions.width, finalDimensions.height);
            console.log(`💡 Recommended quality: ${recommendation.recommended}x (${recommendation.reason})`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXImageUpscalerAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
