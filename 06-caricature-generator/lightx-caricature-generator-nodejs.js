/**
 * LightX AI Caricature Generator API Integration - JavaScript/Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered caricature generation functionality.
 */

const axios = require('axios');
const fs = require('fs');
const FormData = require('form-data');

class LightXCaricatureAPI {
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
     * Upload multiple images (input and optional style image)
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<{inputUrl: string, styleUrl?: string}>} - URLs for images
     */
    async uploadImages(inputImagePath, styleImagePath = null, contentType = 'image/jpeg') {
        try {
            console.log('📤 Uploading input image...');
            const inputUrl = await this.uploadImage(inputImagePath, contentType);
            
            let styleUrl = null;
            if (styleImagePath) {
                console.log('📤 Uploading style image...');
                styleUrl = await this.uploadImage(styleImagePath, contentType);
            }
            
            return { inputUrl, styleUrl };
        } catch (error) {
            console.error('Error uploading images:', error.message);
            throw error;
        }
    }

    /**
     * Generate caricature
     * @param {string} imageUrl - URL of the input image
     * @param {string} styleImageUrl - URL of the style image (optional)
     * @param {string} textPrompt - Text prompt for caricature style (optional)
     * @returns {Promise<string>} - Order ID for tracking
     */
    async generateCaricature(imageUrl, styleImageUrl = null, textPrompt = null) {
        try {
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

            const response = await axios.post(
                `${this.baseUrl}/v1/caricature`,
                payload,
                {
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': this.apiKey
                    }
                }
            );

            if (response.data.statusCode !== 2000) {
                throw new Error(`Caricature generation request failed: ${response.data.message}`);
            }

            const { orderId, maxRetriesAllowed, avgResponseTimeInSec, status } = response.data.body;
            
            console.log(`📋 Order created: ${orderId}`);
            console.log(`🔄 Max retries allowed: ${maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${status}`);
            if (textPrompt) {
                console.log(`💬 Text prompt: "${textPrompt}"`);
            }
            if (styleImageUrl) {
                console.log(`🎨 Style image: ${styleImageUrl}`);
            }

            return orderId;

        } catch (error) {
            console.error('Error generating caricature:', error.message);
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
                    console.log('✅ Caricature generation completed successfully!');
                    console.log(`🎭 Caricature image: ${status.output}`);
                    return status;
                } else if (status.status === 'failed') {
                    throw new Error('Caricature generation failed');
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
     * Complete workflow: Upload images and generate caricature
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image (optional)
     * @param {string} textPrompt - Text prompt for caricature style (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async processCaricatureGeneration(inputImagePath, styleImagePath = null, textPrompt = null, contentType = 'image/jpeg') {
        try {
            console.log('🚀 Starting LightX AI Caricature Generator API workflow...');
            
            // Step 1: Upload images
            console.log('📤 Uploading images...');
            const { inputUrl, styleUrl } = await this.uploadImages(inputImagePath, styleImagePath, contentType);
            console.log(`✅ Input image uploaded: ${inputUrl}`);
            if (styleUrl) {
                console.log(`✅ Style image uploaded: ${styleUrl}`);
            }
            
            // Step 2: Generate caricature
            console.log('🎭 Generating caricature...');
            const orderId = await this.generateCaricature(inputUrl, styleUrl, textPrompt);
            
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
     * Get common text prompts for different caricature styles
     * @param {string} category - Category of caricature style
     * @returns {Array<string>} - Array of suggested prompts
     */
    getSuggestedPrompts(category) {
        const promptSuggestions = {
            funny: [
                'funny exaggerated caricature',
                'humorous cartoon caricature',
                'comic book style caricature',
                'funny face caricature',
                'hilarious cartoon portrait'
            ],
            artistic: [
                'artistic caricature style',
                'classic caricature portrait',
                'fine art caricature',
                'elegant caricature style',
                'sophisticated caricature'
            ],
            cartoon: [
                'cartoon caricature style',
                'animated caricature',
                'Disney style caricature',
                'cartoon network caricature',
                'animated character caricature'
            ],
            vintage: [
                'vintage caricature style',
                'retro caricature portrait',
                'classic newspaper caricature',
                'old school caricature',
                'traditional caricature style'
            ],
            modern: [
                'modern caricature style',
                'contemporary caricature',
                'digital art caricature',
                'modern illustration caricature',
                'stylized caricature portrait'
            ],
            extreme: [
                'extremely exaggerated caricature',
                'wild caricature style',
                'over-the-top caricature',
                'dramatic caricature',
                'intense caricature portrait'
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
     * Generate caricature with text prompt only
     * @param {string} inputImagePath - Path to the input image
     * @param {string} textPrompt - Text prompt for caricature style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async generateCaricatureWithPrompt(inputImagePath, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processCaricatureGeneration(inputImagePath, null, textPrompt, contentType);
    }

    /**
     * Generate caricature with style image only
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async generateCaricatureWithStyle(inputImagePath, styleImagePath, contentType = 'image/jpeg') {
        return await this.processCaricatureGeneration(inputImagePath, styleImagePath, null, contentType);
    }

    /**
     * Generate caricature with both style image and text prompt
     * @param {string} inputImagePath - Path to the input image
     * @param {string} styleImagePath - Path to the style image
     * @param {string} textPrompt - Text prompt for caricature style
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} - Final result with output URL
     */
    async generateCaricatureWithStyleAndPrompt(inputImagePath, styleImagePath, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return await this.processCaricatureGeneration(inputImagePath, styleImagePath, textPrompt, contentType);
    }

    /**
     * Get caricature tips and best practices
     * @returns {Object} - Tips for better caricature results
     */
    getCaricatureTips() {
        const tips = {
            inputImage: [
                'Use clear, well-lit photos with good contrast',
                'Ensure the face is clearly visible and centered',
                'Avoid photos with multiple people',
                'Use high-resolution images for better results',
                'Front-facing photos work best for caricatures'
            ],
            styleImage: [
                'Choose style images with clear facial features',
                'Use caricature examples as style references',
                'Ensure style image has good lighting',
                'Match the pose of your input image if possible',
                'Use high-quality style reference images'
            ],
            textPrompts: [
                'Be specific about the caricature style you want',
                'Mention the level of exaggeration desired',
                'Include artistic style preferences',
                'Specify if you want funny, artistic, or dramatic results',
                'Keep prompts concise but descriptive'
            ],
            general: [
                'Caricatures work best with human faces',
                'Results may vary based on input image quality',
                'Style images influence both artistic style and pose',
                'Text prompts help guide the caricature style',
                'Allow 15-30 seconds for processing'
            ]
        };

        console.log('💡 Caricature Generation Tips:', tips);
        return tips;
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
        const lightx = new LightXCaricatureAPI('YOUR_API_KEY_HERE');
        
        // Get tips for better results
        lightx.getCaricatureTips();
        
        // Example 1: Generate caricature with text prompt only
        const funnyPrompts = lightx.getSuggestedPrompts('funny');
        const result1 = await lightx.generateCaricatureWithPrompt(
            './path/to/your/image.jpg',  // Input image path
            funnyPrompts[0],            // Text prompt
            'image/jpeg'                // Content type
        );
        console.log('🎉 Funny caricature result:', result1);
        
        // Example 2: Generate caricature with style image only
        const result2 = await lightx.generateCaricatureWithStyle(
            './path/to/your/image.jpg',     // Input image path
            './path/to/style-image.jpg',    // Style image path
            'image/jpeg'                    // Content type
        );
        console.log('🎉 Style-based caricature result:', result2);
        
        // Example 3: Generate caricature with both style image and text prompt
        const artisticPrompts = lightx.getSuggestedPrompts('artistic');
        const result3 = await lightx.generateCaricatureWithStyleAndPrompt(
            './path/to/your/image.jpg',     // Input image path
            './path/to/style-image.jpg',    // Style image path
            artisticPrompts[0],            // Text prompt
            'image/jpeg'                   // Content type
        );
        console.log('🎉 Combined style and prompt result:', result3);
        
        // Example 4: Generate caricature with different style categories
        const categories = ['funny', 'artistic', 'cartoon', 'vintage', 'modern', 'extreme'];
        for (const category of categories) {
            const prompts = lightx.getSuggestedPrompts(category);
            const result = await lightx.generateCaricatureWithPrompt(
                './path/to/your/image.jpg',
                prompts[0],
                'image/jpeg'
            );
            console.log(`🎉 ${category} caricature result:`, result);
        }
        
    } catch (error) {
        console.error('Example failed:', error.message);
    }
}

// Export for use in other modules
module.exports = LightXCaricatureAPI;

// Run example if this file is executed directly
if (require.main === module) {
    example();
}
