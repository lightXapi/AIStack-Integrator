/**
 * LightX AI Hairstyle API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hairstyle transformation functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXHairstyleAPI {
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
     * Generate hairstyle transformation
     * @param {string} imageUrl - URL of the input image
     * @param {string} textPrompt - Text prompt for hairstyle description
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateHairstyle(imageUrl, textPrompt) {
        const endpoint = `${this.baseURL}/v1/hairstyle`;

        const payload = {
            imageUrl: imageUrl,
            textPrompt: textPrompt
        };

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Hairstyle request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`💇 Hairstyle prompt: "${textPrompt}"`);

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
                        console.log('✅ Hairstyle transformation completed successfully!');
                        if (status.output) {
                            console.log(`💇 New hairstyle: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Hairstyle transformation failed');

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
     * Complete workflow: Upload image and generate hairstyle transformation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for hairstyle description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processHairstyleGeneration(imageData, textPrompt, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Hairstyle API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Generate hairstyle transformation
        console.log('💇 Generating hairstyle transformation...');
        const orderId = await this.generateHairstyle(imageUrl, textPrompt);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get hairstyle transformation tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getHairstyleTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit photos with good contrast',
                'Ensure the person\'s face and current hair are clearly visible',
                'Avoid cluttered or busy backgrounds',
                'Use high-resolution images for better results',
                'Good face visibility improves hairstyle transformation results'
            ],
            text_prompts: [
                'Be specific about the hairstyle you want to try',
                'Mention hair length, style, and characteristics',
                'Include details about hair color, texture, and cut',
                'Describe the overall look and feel you\'re going for',
                'Keep prompts concise but descriptive'
            ],
            general: [
                'Hairstyle transformation works best with clear face photos',
                'Results may vary based on input image quality',
                'Text prompts guide the hairstyle generation direction',
                'Allow 15-30 seconds for processing',
                'Experiment with different hairstyle descriptions'
            ]
        };

        console.log('💡 Hairstyle Transformation Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get hairstyle style suggestions
     * @returns {Object} Object containing hairstyle suggestions
     */
    getHairstyleSuggestions() {
        const hairstyleSuggestions = {
            short_styles: [
                'pixie cut with side-swept bangs',
                'short bob with layers',
                'buzz cut with fade',
                'short curly afro',
                'asymmetrical short cut'
            ],
            medium_styles: [
                'shoulder-length layered cut',
                'medium bob with waves',
                'lob (long bob) with face-framing layers',
                'medium length with curtain bangs',
                'shoulder-length with subtle highlights'
            ],
            long_styles: [
                'long flowing waves',
                'straight long hair with center part',
                'long layered cut with side bangs',
                'long hair with beachy waves',
                'long hair with balayage highlights'
            ],
            curly_styles: [
                'natural curly afro',
                'loose beachy waves',
                'tight spiral curls',
                'wavy bob with natural texture',
                'curly hair with defined ringlets'
            ],
            trendy_styles: [
                'modern shag cut with layers',
                'wolf cut with textured ends',
                'butterfly cut with face-framing layers',
                'mullet with modern styling',
                'bixie cut (bob-pixie hybrid)'
            ]
        };

        console.log('💡 Hairstyle Style Suggestions:');
        for (const [category, suggestionList] of Object.entries(hairstyleSuggestions)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return hairstyleSuggestions;
    }

    /**
     * Get hairstyle prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getHairstylePromptExamples() {
        const promptExamples = {
            classic: [
                'Classic bob haircut with clean lines',
                'Traditional pixie cut with side part',
                'Classic long layers with subtle waves',
                'Timeless shoulder-length cut with bangs',
                'Classic short back and sides with longer top'
            ],
            modern: [
                'Modern shag cut with textured layers',
                'Contemporary lob with face-framing highlights',
                'Trendy wolf cut with choppy ends',
                'Modern pixie with asymmetrical styling',
                'Contemporary long hair with curtain bangs'
            ],
            casual: [
                'Casual beachy waves for everyday wear',
                'Relaxed shoulder-length cut with natural texture',
                'Easy-care short bob with minimal styling',
                'Casual long hair with loose waves',
                'Low-maintenance pixie with natural movement'
            ],
            formal: [
                'Elegant updo with sophisticated styling',
                'Formal bob with sleek, polished finish',
                'Classic long hair styled for special occasions',
                'Professional short cut with refined styling',
                'Elegant shoulder-length cut with smooth finish'
            ],
            creative: [
                'Bold asymmetrical cut with dramatic angles',
                'Creative color-blocked hairstyle',
                'Artistic pixie with unique styling',
                'Dramatic long layers with bold highlights',
                'Creative short cut with geometric styling'
            ]
        };

        console.log('💡 Hairstyle Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get face shape hairstyle recommendations
     * @returns {Object} Object containing face shape recommendations
     */
    getFaceShapeRecommendations() {
        const faceShapeRecommendations = {
            oval: {
                description: 'Most versatile face shape - can pull off most hairstyles',
                recommended: ['Long layers', 'Pixie cuts', 'Bob cuts', 'Side-swept bangs', 'Any length works well'],
                avoid: ['Heavy bangs that cover forehead', 'Styles that add width to face']
            },
            round: {
                description: 'Face is as wide as it is long with soft, curved lines',
                recommended: ['Long layers', 'Asymmetrical cuts', 'Side parts', 'Height at crown', 'Angular cuts'],
                avoid: ['Short, rounded cuts', 'Center parts', 'Full bangs', 'Styles that add width']
            },
            square: {
                description: 'Strong jawline with angular features',
                recommended: ['Soft layers', 'Side-swept bangs', 'Longer styles', 'Rounded cuts', 'Texture and movement'],
                avoid: ['Sharp, angular cuts', 'Straight-across bangs', 'Very short cuts']
            },
            heart: {
                description: 'Wider at forehead, narrower at chin',
                recommended: ['Chin-length cuts', 'Side-swept bangs', 'Layered styles', 'Volume at chin level'],
                avoid: ['Very short cuts', 'Heavy bangs', 'Styles that add width at top']
            },
            long: {
                description: 'Face is longer than it is wide',
                recommended: ['Shorter cuts', 'Side parts', 'Layers', 'Bangs', 'Width-adding styles'],
                avoid: ['Very long, straight styles', 'Center parts', 'Height at crown']
            }
        };

        console.log('💡 Face Shape Hairstyle Recommendations:');
        for (const [shape, info] of Object.entries(faceShapeRecommendations)) {
            console.log(`${shape}: ${info.description}`);
            console.log(`  Recommended: ${info.recommended.join(', ')}`);
            console.log(`  Avoid: ${info.avoid.join(', ')}`);
        }
        return faceShapeRecommendations;
    }

    /**
     * Get hair type styling tips
     * @returns {Object} Object containing hair type tips
     */
    getHairTypeTips() {
        const hairTypeTips = {
            straight: {
                characteristics: 'Smooth, lacks natural curl or wave',
                styling_tips: ['Layers add movement', 'Blunt cuts work well', 'Texture can be added with styling'],
                best_styles: ['Blunt bob', 'Long layers', 'Pixie cuts', 'Straight-across bangs']
            },
            wavy: {
                characteristics: 'Natural S-shaped waves',
                styling_tips: ['Enhance natural texture', 'Layers work beautifully', 'Avoid over-straightening'],
                best_styles: ['Layered cuts', 'Beachy waves', 'Shoulder-length styles', 'Natural texture cuts']
            },
            curly: {
                characteristics: 'Natural spiral or ringlet formation',
                styling_tips: ['Work with natural curl pattern', 'Avoid heavy layers', 'Moisture is key'],
                best_styles: ['Curly bobs', 'Natural afro', 'Layered curls', 'Curly pixie cuts']
            },
            coily: {
                characteristics: 'Tight, springy curls or coils',
                styling_tips: ['Embrace natural texture', 'Regular moisture needed', 'Protective styles work well'],
                best_styles: ['Natural afro', 'Twist-outs', 'Bantu knots', 'Protective braided styles']
            }
        };

        console.log('💡 Hair Type Styling Tips:');
        for (const [type, info] of Object.entries(hairTypeTips)) {
            console.log(`${type}: ${info.characteristics}`);
            console.log(`  Styling tips: ${info.styling_tips.join(', ')}`);
            console.log(`  Best styles: ${info.best_styles.join(', ')}`);
        }
        return hairTypeTips;
    }

    /**
     * Validate text prompt (utility function)
     * @param {string} textPrompt - Text prompt to validate
     * @returns {boolean} Whether the prompt is valid
     */
    validateTextPrompt(textPrompt) {
        if (!textPrompt || !textPrompt.trim()) {
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
     * Generate hairstyle with prompt validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for hairstyle description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateHairstyleWithValidation(imageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return this.processHairstyleGeneration(imageData, textPrompt, contentType);
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
        const lightx = new LightXHairstyleAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getHairstyleTips();
        lightx.getHairstyleSuggestions();
        lightx.getHairstylePromptExamples();
        lightx.getFaceShapeRecommendations();
        lightx.getHairTypeTips();

        // Load image (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';

        // Example 1: Try a classic bob hairstyle
        const result1 = await lightx.generateHairstyleWithValidation(
            imagePath,
            'Classic bob haircut with clean lines and side part',
            'image/jpeg'
        );
        console.log('🎉 Classic bob result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Try a modern pixie cut
        const result2 = await lightx.generateHairstyleWithValidation(
            imagePath,
            'Modern pixie cut with asymmetrical styling and texture',
            'image/jpeg'
        );
        console.log('🎉 Modern pixie result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Try different hairstyles
        const hairstyles = [
            'Long flowing waves with natural texture',
            'Shoulder-length layered cut with curtain bangs',
            'Short curly afro with natural texture',
            'Beachy waves with sun-kissed highlights'
        ];

        for (const hairstyle of hairstyles) {
            const result = await lightx.generateHairstyleWithValidation(
                imagePath,
                hairstyle,
                'image/jpeg'
            );
            console.log(`🎉 ${hairstyle} result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 4: Get image dimensions
        const dimensions = await lightx.getImageDimensions(imagePath);
        if (dimensions.width > 0 && dimensions.height > 0) {
            console.log(`📏 Original image: ${dimensions.width}x${dimensions.height}`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXHairstyleAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
