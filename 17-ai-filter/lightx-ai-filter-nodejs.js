/**
 * LightX AI Filter API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered image filtering functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXAIFilterAPI {
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
     * Generate AI filter
     * @param {string} imageUrl - URL of the input image
     * @param {string} textPrompt - Text prompt for filter description
     * @param {string} filterReferenceUrl - Optional filter reference image URL
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateFilter(imageUrl, textPrompt, filterReferenceUrl = null) {
        const endpoint = `${this.baseURL}/v2/aifilter`;

        const payload = {
            imageUrl: imageUrl,
            textPrompt: textPrompt
        };

        // Add filter reference URL if provided
        if (filterReferenceUrl) {
            payload.filterReferenceUrl = filterReferenceUrl;
        }

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Filter request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`🎨 Filter prompt: "${textPrompt}"`);
            if (filterReferenceUrl) {
                console.log(`🎭 Filter reference: ${filterReferenceUrl}`);
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
                        console.log('✅ AI filter applied successfully!');
                        if (status.output) {
                            console.log(`🎨 Filtered image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('AI filter application failed');

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
     * Complete workflow: Upload image and apply AI filter
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for filter description
     * @param {Buffer|string} filterReferenceData - Optional filter reference image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processFilter(imageData, textPrompt, filterReferenceData = null, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Filter API workflow...');

        // Step 1: Upload main image
        console.log('📤 Uploading main image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Main image uploaded: ${imageUrl}`);

        // Step 2: Upload filter reference image if provided
        let filterReferenceUrl = null;
        if (filterReferenceData) {
            console.log('📤 Uploading filter reference image...');
            filterReferenceUrl = await this.uploadImage(filterReferenceData, contentType);
            console.log(`✅ Filter reference image uploaded: ${filterReferenceUrl}`);
        }

        // Step 3: Generate filter
        console.log('🎨 Applying AI filter...');
        const orderId = await this.generateFilter(imageUrl, textPrompt, filterReferenceUrl);

        // Step 4: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get AI filter tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getFilterTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit images with good contrast',
                'Ensure the image has good composition and framing',
                'Avoid heavily compressed or low-quality source images',
                'Use high-quality source images for best filter results',
                'Good source image quality improves filter application'
            ],
            text_prompts: [
                'Be specific about the filter style you want to apply',
                'Describe the mood, atmosphere, or artistic style desired',
                'Include details about colors, lighting, and effects',
                'Mention specific artistic movements or styles',
                'Keep prompts descriptive but concise'
            ],
            style_images: [
                'Use style images that match your desired aesthetic',
                'Ensure style images have good quality and clarity',
                'Choose style images with strong visual characteristics',
                'Style images work best when they complement the main image',
                'Experiment with different style images for varied results'
            ],
            general: [
                'AI filters work best with clear, detailed source images',
                'Results may vary based on input image quality and content',
                'Filters can dramatically transform image appearance and mood',
                'Allow 15-30 seconds for processing',
                'Experiment with different prompts and style combinations'
            ]
        };

        console.log('💡 AI Filter Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get filter style suggestions
     * @returns {Object} Object containing style suggestions
     */
    getFilterStyleSuggestions() {
        const styleSuggestions = {
            artistic: [
                'Oil painting style with rich textures',
                'Watercolor painting with soft edges',
                'Digital art with vibrant colors',
                'Sketch drawing with pencil strokes',
                'Abstract art with geometric shapes'
            ],
            photography: [
                'Vintage film photography with grain',
                'Black and white with high contrast',
                'HDR photography with enhanced details',
                'Portrait photography with soft lighting',
                'Street photography with documentary style'
            ],
            cinematic: [
                'Film noir with dramatic shadows',
                'Sci-fi with neon colors and effects',
                'Horror with dark, moody atmosphere',
                'Romance with warm, soft lighting',
                'Action with dynamic, high-contrast look'
            ],
            vintage: [
                'Retro 80s with neon and synthwave',
                'Vintage 70s with warm, earthy tones',
                'Classic Hollywood glamour',
                'Victorian era with sepia tones',
                'Art Deco with geometric patterns'
            ],
            modern: [
                'Minimalist with clean lines',
                'Contemporary with bold colors',
                'Urban with gritty textures',
                'Futuristic with metallic surfaces',
                'Instagram aesthetic with bright colors'
            ]
        };

        console.log('💡 Filter Style Suggestions:');
        for (const [category, suggestionList] of Object.entries(styleSuggestions)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return styleSuggestions;
    }

    /**
     * Get filter prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getFilterPromptExamples() {
        const promptExamples = {
            artistic: [
                'Transform into oil painting with rich textures and warm colors',
                'Apply watercolor effect with soft, flowing edges',
                'Create digital art style with vibrant, saturated colors',
                'Convert to pencil sketch with detailed line work',
                'Apply abstract art style with geometric patterns'
            ],
            mood: [
                'Create mysterious atmosphere with dark shadows and blue tones',
                'Apply warm, romantic lighting with golden hour glow',
                'Transform to dramatic, high-contrast black and white',
                'Create dreamy, ethereal effect with soft pastels',
                'Apply energetic, vibrant style with bold colors'
            ],
            vintage: [
                'Apply retro 80s synthwave style with neon colors',
                'Transform to vintage film photography with grain',
                'Create Victorian era aesthetic with sepia tones',
                'Apply Art Deco style with geometric patterns',
                'Transform to classic Hollywood glamour'
            ],
            modern: [
                'Apply minimalist style with clean, simple composition',
                'Create contemporary look with bold, modern colors',
                'Transform to urban aesthetic with gritty textures',
                'Apply futuristic style with metallic surfaces',
                'Create Instagram-worthy aesthetic with bright colors'
            ],
            cinematic: [
                'Apply film noir style with dramatic lighting',
                'Create sci-fi atmosphere with neon effects',
                'Transform to horror aesthetic with dark mood',
                'Apply romance style with soft, warm lighting',
                'Create action movie look with high contrast'
            ]
        };

        console.log('💡 Filter Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get filter use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getFilterUseCases() {
        const useCases = {
            social_media: [
                'Create Instagram-worthy aesthetic filters',
                'Apply trendy social media filters',
                'Transform photos for different platforms',
                'Create consistent brand aesthetic',
                'Enhance photos for social sharing'
            ],
            marketing: [
                'Create branded filter effects',
                'Apply campaign-specific aesthetics',
                'Transform product photos with style',
                'Create cohesive visual identity',
                'Enhance marketing materials'
            ],
            creative: [
                'Explore artistic styles and effects',
                'Create unique visual interpretations',
                'Experiment with different aesthetics',
                'Transform photos into art pieces',
                'Develop creative visual concepts'
            ],
            photography: [
                'Apply professional photo filters',
                'Create consistent editing style',
                'Transform photos for different moods',
                'Apply vintage or retro effects',
                'Enhance photo aesthetics'
            ],
            personal: [
                'Create personalized photo styles',
                'Apply favorite artistic effects',
                'Transform memories with filters',
                'Create unique photo collections',
                'Experiment with visual styles'
            ]
        };

        console.log('💡 Filter Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Get filter combination suggestions
     * @returns {Object} Object containing combination suggestions
     */
    getFilterCombinations() {
        const combinations = {
            text_only: [
                'Use descriptive text prompts for specific effects',
                'Combine multiple style descriptions in one prompt',
                'Include mood, lighting, and color specifications',
                'Reference artistic movements or photographers',
                'Describe the desired emotional impact'
            ],
            text_with_style: [
                'Use text prompt for overall direction',
                'Add style image for specific visual reference',
                'Combine text description with style image',
                'Use style image to guide color palette',
                'Apply text prompt with style image influence'
            ],
            style_only: [
                'Use style image as primary reference',
                'Let style image guide the transformation',
                'Apply style image characteristics to main image',
                'Use style image for color and texture reference',
                'Transform based on style image aesthetic'
            ]
        };

        console.log('💡 Filter Combination Suggestions:');
        for (const [category, combinationList] of Object.entries(combinations)) {
            console.log(`${category}: ${combinationList}`);
        }
        return combinations;
    }

    /**
     * Validate text prompt (utility function)
     * @param {string} textPrompt - Text prompt to validate
     * @returns {boolean} Whether the prompt is valid
     */
    validateTextPrompt(textPrompt) {
        if (!textPrompt || textPrompt.trim().length === 0) {
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
     * Generate filter with prompt validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for filter description
     * @param {Buffer|string} styleImageData - Optional style image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateFilterWithValidation(imageData, textPrompt, styleImageData = null, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return this.processFilter(imageData, textPrompt, styleImageData, contentType);
    }

    /**
     * Get filter intensity suggestions
     * @returns {Object} Object containing intensity suggestions
     */
    getFilterIntensitySuggestions() {
        const intensitySuggestions = {
            subtle: [
                'Apply gentle color adjustments',
                'Add subtle texture overlays',
                'Enhance existing colors slightly',
                'Apply soft lighting effects',
                'Create minimal artistic touches'
            ],
            moderate: [
                'Apply noticeable style changes',
                'Transform colors and mood',
                'Add artistic texture effects',
                'Create distinct visual style',
                'Apply balanced filter effects'
            ],
            dramatic: [
                'Apply bold, transformative effects',
                'Create dramatic color changes',
                'Add strong artistic elements',
                'Transform image completely',
                'Apply intense visual effects'
            ]
        };

        console.log('💡 Filter Intensity Suggestions:');
        for (const [intensity, suggestionList] of Object.entries(intensitySuggestions)) {
            console.log(`${intensity}: ${suggestionList}`);
        }
        return intensitySuggestions;
    }

    /**
     * Get filter category recommendations
     * @returns {Object} Object containing category recommendations
     */
    getFilterCategories() {
        const categories = {
            artistic: {
                description: 'Transform images into various artistic styles',
                examples: ['Oil painting', 'Watercolor', 'Digital art', 'Sketch', 'Abstract'],
                best_for: ['Creative projects', 'Artistic expression', 'Unique visuals']
            },
            vintage: {
                description: 'Apply retro and vintage aesthetics',
                examples: ['80s synthwave', 'Film photography', 'Victorian', 'Art Deco', 'Classic Hollywood'],
                best_for: ['Nostalgic content', 'Retro branding', 'Historical themes']
            },
            modern: {
                description: 'Apply contemporary and modern styles',
                examples: ['Minimalist', 'Contemporary', 'Urban', 'Futuristic', 'Instagram aesthetic'],
                best_for: ['Modern branding', 'Social media', 'Contemporary design']
            },
            cinematic: {
                description: 'Create movie-like visual effects',
                examples: ['Film noir', 'Sci-fi', 'Horror', 'Romance', 'Action'],
                best_for: ['Video content', 'Dramatic visuals', 'Storytelling']
            },
            mood: {
                description: 'Set specific emotional atmospheres',
                examples: ['Mysterious', 'Romantic', 'Dramatic', 'Dreamy', 'Energetic'],
                best_for: ['Emotional content', 'Mood setting', 'Atmospheric visuals']
            }
        };

        console.log('💡 Filter Categories:');
        for (const [category, info] of Object.entries(categories)) {
            console.log(`${category}: ${info.description}`);
            console.log(`  Examples: ${info.examples.join(', ')}`);
            console.log(`  Best for: ${info.best_for.join(', ')}`);
        }
        return categories;
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
        const lightx = new LightXAIFilterAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getFilterTips();
        lightx.getFilterStyleSuggestions();
        lightx.getFilterPromptExamples();
        lightx.getFilterUseCases();
        lightx.getFilterCombinations();
        lightx.getFilterIntensitySuggestions();
        lightx.getFilterCategories();

        // Load images (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';
        const filterReferencePath = 'path/to/filter-reference-image.jpg'; // Optional

        // Example 1: Text prompt only
        const result1 = await lightx.generateFilterWithValidation(
            imagePath,
            'Transform into oil painting with rich textures and warm colors',
            null, // No filter reference image
            'image/jpeg'
        );
        console.log('🎉 Oil painting filter result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Text prompt with filter reference image
        const result2 = await lightx.generateFilterWithValidation(
            imagePath,
            'Apply vintage film photography style',
            filterReferencePath, // With filter reference image
            'image/jpeg'
        );
        console.log('🎉 Vintage film filter result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Try different filter styles
        const filterStyles = [
            'Create watercolor effect with soft, flowing edges',
            'Apply retro 80s synthwave style with neon colors',
            'Transform to dramatic, high-contrast black and white',
            'Create dreamy, ethereal effect with soft pastels',
            'Apply minimalist style with clean, simple composition'
        ];

        for (const style of filterStyles) {
            const result = await lightx.generateFilterWithValidation(
                imagePath,
                style,
                null,
                'image/jpeg'
            );
            console.log(`🎉 ${style} result:`);
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
module.exports = LightXAIFilterAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
