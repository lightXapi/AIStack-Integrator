/**
 * LightX AI Portrait API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered portrait generation functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXPortraitAPI {
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
     * Upload multiple images (input and optional style image)
     * @param {Buffer|string} inputImageData - Input image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<{inputURL: string, styleURL: string|null}>}
     */
    async uploadImages(inputImageData, styleImageData = null, contentType = 'image/jpeg') {
        console.log('📤 Uploading input image...');
        const inputURL = await this.uploadImage(inputImageData, contentType);

        let styleURL = null;
        if (styleImageData) {
            console.log('📤 Uploading style image...');
            styleURL = await this.uploadImage(styleImageData, contentType);
        }

        return { inputURL, styleURL };
    }

    /**
     * Generate portrait
     * @param {string} imageUrl - URL of the input image
     * @param {string} styleImageUrl - URL of the style image (optional)
     * @param {string} textPrompt - Text prompt for portrait style (optional)
     * @returns {Promise<string>} Order ID for tracking
     */
    async generatePortrait(imageUrl, styleImageUrl = null, textPrompt = null) {
        const endpoint = `${this.baseURL}/v1/portrait`;

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

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Portrait generation request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            if (textPrompt) {
                console.log(`💬 Text prompt: "${textPrompt}"`);
            }
            if (styleImageUrl) {
                console.log(`🎨 Style image: ${styleImageUrl}`);
            }

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
                        console.log('✅ Portrait generation completed successfully!');
                        if (status.output) {
                            console.log(`🖼️ Portrait image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Portrait generation failed');

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
     * Complete workflow: Upload images and generate portrait
     * @param {Buffer|string} inputImageData - Input image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path (optional)
     * @param {string} textPrompt - Text prompt for portrait style (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processPortraitGeneration(inputImageData, styleImageData = null, textPrompt = null, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Portrait API workflow...');

        // Step 1: Upload images
        console.log('📤 Uploading images...');
        const { inputURL, styleURL } = await this.uploadImages(inputImageData, styleImageData, contentType);
        console.log(`✅ Input image uploaded: ${inputURL}`);
        if (styleURL) {
            console.log(`✅ Style image uploaded: ${styleURL}`);
        }

        // Step 2: Generate portrait
        console.log('🖼️ Generating portrait...');
        const orderId = await this.generatePortrait(inputURL, styleURL, textPrompt);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get common text prompts for different portrait styles
     * @param {string} category - Category of portrait style
     * @returns {Array<string>} Array of suggested prompts
     */
    getSuggestedPrompts(category) {
        const promptSuggestions = {
            'realistic': [
                'realistic portrait photography',
                'professional headshot style',
                'natural lighting portrait',
                'studio portrait photography',
                'high-quality realistic portrait'
            ],
            'artistic': [
                'artistic portrait style',
                'creative portrait photography',
                'artistic interpretation portrait',
                'creative portrait art',
                'artistic portrait rendering'
            ],
            'vintage': [
                'vintage portrait style',
                'retro portrait photography',
                'classic portrait style',
                'old school portrait',
                'vintage film portrait'
            ],
            'modern': [
                'modern portrait style',
                'contemporary portrait photography',
                'sleek modern portrait',
                'contemporary art portrait',
                'modern artistic portrait'
            ],
            'fantasy': [
                'fantasy portrait style',
                'magical portrait art',
                'fantasy character portrait',
                'mystical portrait style',
                'fantasy art portrait'
            ],
            'minimalist': [
                'minimalist portrait style',
                'clean simple portrait',
                'minimal portrait photography',
                'simple elegant portrait',
                'minimalist art portrait'
            ],
            'dramatic': [
                'dramatic portrait style',
                'high contrast portrait',
                'dramatic lighting portrait',
                'intense portrait photography',
                'dramatic artistic portrait'
            ],
            'soft': [
                'soft portrait style',
                'gentle portrait photography',
                'soft lighting portrait',
                'delicate portrait art',
                'soft artistic portrait'
            ]
        };

        const prompts = promptSuggestions[category] || [];
        console.log(`💡 Suggested prompts for ${category}: ${prompts}`);
        return prompts;
    }

    /**
     * Validate text prompt (utility function)
     * @param {string} textPrompt - Text prompt to validate
     * @returns {boolean} Whether the prompt is valid
     */
    validateTextPrompt(textPrompt) {
        if (!textPrompt || textPrompt.trim() === '') {
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
     * Generate portrait with text prompt only
     * @param {Buffer|string} inputImageData - Input image data or file path
     * @param {string} textPrompt - Text prompt for portrait style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generatePortraitWithPrompt(inputImageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processPortraitGeneration(inputImageData, null, textPrompt, contentType);
    }

    /**
     * Generate portrait with style image only
     * @param {Buffer|string} inputImageData - Input image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generatePortraitWithStyle(inputImageData, styleImageData, contentType = 'image/jpeg') {
        return await this.processPortraitGeneration(inputImageData, styleImageData, null, contentType);
    }

    /**
     * Generate portrait with both style image and text prompt
     * @param {Buffer|string} inputImageData - Input image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path
     * @param {string} textPrompt - Text prompt for portrait style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generatePortraitWithStyleAndPrompt(inputImageData, styleImageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processPortraitGeneration(inputImageData, styleImageData, textPrompt, contentType);
    }

    /**
     * Get portrait tips and best practices
     * @returns {Object} Object containing tips for better portrait results
     */
    getPortraitTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit photos with good contrast',
                'Ensure the face is clearly visible and centered',
                'Avoid photos with multiple people',
                'Use high-resolution images for better results',
                'Front-facing photos work best for portraits'
            ],
            style_image: [
                'Choose style images with desired artistic style',
                'Use portrait examples as style references',
                'Ensure style image has good lighting and composition',
                'Match the artistic direction you want for your portrait',
                'Use high-quality style reference images'
            ],
            text_prompts: [
                'Be specific about the portrait style you want',
                'Mention artistic preferences (realistic, artistic, vintage)',
                'Include lighting preferences (soft, dramatic, natural)',
                'Specify the mood (professional, creative, dramatic)',
                'Keep prompts concise but descriptive'
            ],
            general: [
                'Portraits work best with clear human faces',
                'Results may vary based on input image quality',
                'Style images influence both artistic style and composition',
                'Text prompts help guide the portrait generation',
                'Allow 15-30 seconds for processing'
            ]
        };

        console.log('💡 Portrait Generation Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get portrait style categories and their specific tips
     * @returns {Object} Object containing style-specific tips
     */
    getPortraitStyleTips() {
        const styleTips = {
            realistic: [
                'Use natural lighting for best results',
                'Ensure good facial detail and clarity',
                'Consider professional headshot style',
                'Use neutral backgrounds for focus on subject'
            ],
            artistic: [
                'Choose creative and expressive style images',
                'Consider artistic interpretation over realism',
                'Use bold colors and creative compositions',
                'Experiment with different artistic styles'
            ],
            vintage: [
                'Use warm, nostalgic color tones',
                'Consider film photography aesthetics',
                'Use classic portrait compositions',
                'Apply vintage color grading effects'
            ],
            modern: [
                'Use contemporary photography styles',
                'Consider clean, minimalist compositions',
                'Use modern lighting techniques',
                'Apply contemporary color palettes'
            ],
            fantasy: [
                'Use magical or mystical style references',
                'Consider fantasy art aesthetics',
                'Use dramatic lighting and effects',
                'Apply fantasy color schemes'
            ]
        };

        console.log('💡 Portrait Style Tips:');
        for (const [style, tipList] of Object.entries(styleTips)) {
            console.log(`${style}: ${tipList}`);
        }
        return styleTips;
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
        const lightx = new LightXPortraitAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getPortraitTips();
        lightx.getPortraitStyleTips();

        // Load input image (replace with your image loading logic)
        const inputImagePath = 'path/to/input-image.jpg';
        const styleImagePath = 'path/to/style-image.jpg';

        // Example 1: Generate portrait with text prompt only
        const realisticPrompts = lightx.getSuggestedPrompts('realistic');
        const result1 = await lightx.generatePortraitWithPrompt(
            inputImagePath,
            realisticPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Realistic portrait result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Generate portrait with style image only
        const result2 = await lightx.generatePortraitWithStyle(
            inputImagePath,
            styleImagePath,
            'image/jpeg'
        );
        console.log('🎉 Style-based portrait result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Generate portrait with both style image and text prompt
        const artisticPrompts = lightx.getSuggestedPrompts('artistic');
        const result3 = await lightx.generatePortraitWithStyleAndPrompt(
            inputImagePath,
            styleImagePath,
            artisticPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Combined style and prompt result:');
        console.log(`Order ID: ${result3.orderId}`);
        console.log(`Status: ${result3.status}`);
        if (result3.output) {
            console.log(`Output: ${result3.output}`);
        }

        // Example 4: Generate portraits for different styles
        const styles = ['realistic', 'artistic', 'vintage', 'modern', 'fantasy', 'minimalist', 'dramatic', 'soft'];
        for (const style of styles) {
            const prompts = lightx.getSuggestedPrompts(style);
            const result = await lightx.generatePortraitWithPrompt(
                inputImagePath,
                prompts[0],
                'image/jpeg'
            );
            console.log(`🎉 ${style} portrait result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 5: Get image dimensions
        const dimensions = await lightx.getImageDimensions(inputImagePath);
        if (dimensions.width > 0 && dimensions.height > 0) {
            console.log(`📏 Original image: ${dimensions.width}x${dimensions.height}`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXPortraitAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
