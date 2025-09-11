/**
 * LightX AI Product Photoshoot API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered product photoshoot functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXProductPhotoshootAPI {
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
     * Upload multiple images (product and optional style image)
     * @param {Buffer|string} productImageData - Product image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<{productURL: string, styleURL: string|null}>}
     */
    async uploadImages(productImageData, styleImageData = null, contentType = 'image/jpeg') {
        console.log('📤 Uploading product image...');
        const productURL = await this.uploadImage(productImageData, contentType);

        let styleURL = null;
        if (styleImageData) {
            console.log('📤 Uploading style image...');
            styleURL = await this.uploadImage(styleImageData, contentType);
        }

        return { productURL, styleURL };
    }

    /**
     * Generate product photoshoot
     * @param {string} imageUrl - URL of the product image
     * @param {string} styleImageUrl - URL of the style image (optional)
     * @param {string} textPrompt - Text prompt for photoshoot style (optional)
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateProductPhotoshoot(imageUrl, styleImageUrl = null, textPrompt = null) {
        const endpoint = `${this.baseURL}/v1/product-photoshoot`;

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
                throw new Error(`Product photoshoot request failed: ${response.data.message}`);
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
                        console.log('✅ Product photoshoot completed successfully!');
                        if (status.output) {
                            console.log(`📸 Product photoshoot image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Product photoshoot failed');

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
     * Complete workflow: Upload images and generate product photoshoot
     * @param {Buffer|string} productImageData - Product image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path (optional)
     * @param {string} textPrompt - Text prompt for photoshoot style (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processProductPhotoshoot(productImageData, styleImageData = null, textPrompt = null, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Product Photoshoot API workflow...');

        // Step 1: Upload images
        console.log('📤 Uploading images...');
        const { productURL, styleURL } = await this.uploadImages(productImageData, styleImageData, contentType);
        console.log(`✅ Product image uploaded: ${productURL}`);
        if (styleURL) {
            console.log(`✅ Style image uploaded: ${styleURL}`);
        }

        // Step 2: Generate product photoshoot
        console.log('📸 Generating product photoshoot...');
        const orderId = await this.generateProductPhotoshoot(productURL, styleURL, textPrompt);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get common text prompts for different product photoshoot styles
     * @param {string} category - Category of photoshoot style
     * @returns {Array<string>} Array of suggested prompts
     */
    getSuggestedPrompts(category) {
        const promptSuggestions = {
            'ecommerce': [
                'clean white background ecommerce',
                'professional product photography',
                'minimalist product shot',
                'studio lighting product photo',
                'commercial product photography'
            ],
            'lifestyle': [
                'lifestyle product photography',
                'natural environment product shot',
                'outdoor product photography',
                'casual lifestyle setting',
                'real-world product usage'
            ],
            'luxury': [
                'luxury product photography',
                'premium product presentation',
                'high-end product shot',
                'elegant product photography',
                'sophisticated product display'
            ],
            'tech': [
                'modern tech product photography',
                'sleek technology product shot',
                'contemporary tech presentation',
                'futuristic product photography',
                'digital product showcase'
            ],
            'fashion': [
                'fashion product photography',
                'stylish clothing presentation',
                'trendy fashion product shot',
                'modern fashion photography',
                'contemporary style product'
            ],
            'food': [
                'appetizing food photography',
                'delicious food presentation',
                'mouth-watering food shot',
                'professional food photography',
                'gourmet food styling'
            ],
            'beauty': [
                'beauty product photography',
                'cosmetic product presentation',
                'skincare product shot',
                'makeup product photography',
                'beauty brand styling'
            ],
            'home': [
                'home decor product photography',
                'interior design product shot',
                'home furnishing presentation',
                'decorative product photography',
                'lifestyle home product'
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
     * Generate product photoshoot with text prompt only
     * @param {Buffer|string} productImageData - Product image data or file path
     * @param {string} textPrompt - Text prompt for photoshoot style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateProductPhotoshootWithPrompt(productImageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processProductPhotoshoot(productImageData, null, textPrompt, contentType);
    }

    /**
     * Generate product photoshoot with style image only
     * @param {Buffer|string} productImageData - Product image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateProductPhotoshootWithStyle(productImageData, styleImageData, contentType = 'image/jpeg') {
        return await this.processProductPhotoshoot(productImageData, styleImageData, null, contentType);
    }

    /**
     * Generate product photoshoot with both style image and text prompt
     * @param {Buffer|string} productImageData - Product image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path
     * @param {string} textPrompt - Text prompt for photoshoot style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateProductPhotoshootWithStyleAndPrompt(productImageData, styleImageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processProductPhotoshoot(productImageData, styleImageData, textPrompt, contentType);
    }

    /**
     * Get product photoshoot tips and best practices
     * @returns {Object} Object containing tips for better photoshoot results
     */
    getProductPhotoshootTips() {
        const tips = {
            product_image: [
                'Use clear, well-lit product photos with good contrast',
                'Ensure the product is clearly visible and centered',
                'Avoid cluttered backgrounds in the original image',
                'Use high-resolution images for better results',
                'Product should be the main focus of the image'
            ],
            style_image: [
                'Choose style images with desired background or setting',
                'Use lifestyle or studio photography as style references',
                'Ensure style image has good lighting and composition',
                'Match the mood and aesthetic you want for your product',
                'Use high-quality style reference images'
            ],
            text_prompts: [
                'Be specific about the photoshoot style you want',
                'Mention background preferences (white, lifestyle, outdoor)',
                'Include lighting preferences (studio, natural, dramatic)',
                'Specify the mood (professional, casual, luxury, modern)',
                'Keep prompts concise but descriptive'
            ],
            general: [
                'Product photoshoots work best with clear product images',
                'Results may vary based on input image quality',
                'Style images influence both background and overall aesthetic',
                'Text prompts help guide the photoshoot style',
                'Allow 15-30 seconds for processing'
            ]
        };

        console.log('💡 Product Photoshoot Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
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
     * Get product categories and their specific tips
     * @returns {Object} Object containing category-specific tips
     */
    getProductCategoryTips() {
        const categoryTips = {
            electronics: [
                'Use clean, minimalist backgrounds',
                'Ensure good lighting to show product details',
                'Consider showing the product from multiple angles',
                'Use neutral colors to make the product stand out'
            ],
            clothing: [
                'Use mannequins or models for better presentation',
                'Consider lifestyle settings for fashion items',
                'Ensure good lighting to show fabric texture',
                'Use complementary background colors'
            ],
            jewelry: [
                'Use dark or neutral backgrounds for contrast',
                'Ensure excellent lighting to show sparkle and detail',
                'Consider close-up shots to show craftsmanship',
                'Use soft lighting to avoid harsh reflections'
            ],
            food: [
                'Use natural lighting when possible',
                'Consider lifestyle settings (kitchen, dining table)',
                'Ensure good color contrast and appetizing presentation',
                'Use props that complement the food item'
            ],
            beauty: [
                'Use clean, professional backgrounds',
                'Ensure good lighting to show product colors',
                'Consider showing the product in use',
                'Use soft, flattering lighting'
            ],
            home_decor: [
                'Use lifestyle settings to show context',
                'Consider room settings for larger items',
                'Ensure good lighting to show texture and materials',
                'Use complementary colors and styles'
            ]
        };

        console.log('💡 Product Category Tips:');
        for (const [category, tipList] of Object.entries(categoryTips)) {
            console.log(`${category}: ${tipList}`);
        }
        return categoryTips;
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
        const lightx = new LightXProductPhotoshootAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getProductPhotoshootTips();
        lightx.getProductCategoryTips();

        // Load product image (replace with your image loading logic)
        const productImagePath = 'path/to/product-image.jpg';
        const styleImagePath = 'path/to/style-image.jpg';

        // Example 1: Generate product photoshoot with text prompt only
        const ecommercePrompts = lightx.getSuggestedPrompts('ecommerce');
        const result1 = await lightx.generateProductPhotoshootWithPrompt(
            productImagePath,
            ecommercePrompts[0],
            'image/jpeg'
        );
        console.log('🎉 E-commerce photoshoot result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Generate product photoshoot with style image only
        const result2 = await lightx.generateProductPhotoshootWithStyle(
            productImagePath,
            styleImagePath,
            'image/jpeg'
        );
        console.log('🎉 Style-based photoshoot result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Generate product photoshoot with both style image and text prompt
        const luxuryPrompts = lightx.getSuggestedPrompts('luxury');
        const result3 = await lightx.generateProductPhotoshootWithStyleAndPrompt(
            productImagePath,
            styleImagePath,
            luxuryPrompts[0],
            'image/jpeg'
        );
        console.log('🎉 Combined style and prompt result:');
        console.log(`Order ID: ${result3.orderId}`);
        console.log(`Status: ${result3.status}`);
        if (result3.output) {
            console.log(`Output: ${result3.output}`);
        }

        // Example 4: Generate photoshoots for different categories
        const categories = ['ecommerce', 'lifestyle', 'luxury', 'tech', 'fashion', 'food', 'beauty', 'home'];
        for (const category of categories) {
            const prompts = lightx.getSuggestedPrompts(category);
            const result = await lightx.generateProductPhotoshootWithPrompt(
                productImagePath,
                prompts[0],
                'image/jpeg'
            );
            console.log(`🎉 ${category} photoshoot result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 5: Get image dimensions
        const dimensions = await lightx.getImageDimensions(productImagePath);
        if (dimensions.width > 0 && dimensions.height > 0) {
            console.log(`📏 Original image: ${dimensions.width}x${dimensions.height}`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXProductPhotoshootAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
