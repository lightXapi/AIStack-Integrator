/**
 * LightX AI Hair Color API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hair color changing functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXAIHairColorAPI {
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
     * Change hair color using AI
     * @param {string} imageUrl - URL of the input image
     * @param {string} textPrompt - Text prompt for hair color description
     * @returns {Promise<string>} Order ID for tracking
     */
    async changeHairColor(imageUrl, textPrompt) {
        const endpoint = `${this.baseURL}/v2/haircolor/`;

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
                throw new Error(`Hair color change request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`🎨 Hair color prompt: "${textPrompt}"`);

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
                        console.log('✅ Hair color changed successfully!');
                        if (status.output) {
                            console.log(`🎨 New hair color image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Hair color change failed');

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
     * Complete workflow: Upload image and change hair color
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for hair color description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processHairColorChange(imageData, textPrompt, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Hair Color API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Change hair color
        console.log('🎨 Changing hair color...');
        const orderId = await this.changeHairColor(imageUrl, textPrompt);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get hair color tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getHairColorTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit photos with visible hair',
                'Ensure the person\'s hair is clearly visible in the image',
                'Avoid heavily compressed or low-quality source images',
                'Use high-quality source images for best hair color results',
                'Good lighting helps preserve hair texture and details'
            ],
            text_prompts: [
                'Be specific about the hair color you want to achieve',
                'Include details about shade, tone, and intensity',
                'Mention specific hair color names or descriptions',
                'Describe the desired hair color effect clearly',
                'Keep prompts descriptive but concise'
            ],
            hair_visibility: [
                'Ensure hair is clearly visible and not obscured',
                'Avoid images where hair is covered by hats or accessories',
                'Use images with good hair definition and texture',
                'Ensure the person\'s face is clearly visible',
                'Avoid images with extreme angles or poor lighting'
            ],
            general: [
                'AI hair color works best with clear, detailed source images',
                'Results may vary based on input image quality and hair visibility',
                'Hair color changes preserve original texture and style',
                'Allow 15-30 seconds for processing',
                'Experiment with different color descriptions for varied results'
            ]
        };

        console.log('💡 Hair Color Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get hair color suggestions
     * @returns {Object} Object containing hair color suggestions
     */
    getHairColorSuggestions() {
        const colorSuggestions = {
            natural_colors: [
                'Natural black hair',
                'Dark brown hair',
                'Medium brown hair',
                'Light brown hair',
                'Natural blonde hair',
                'Strawberry blonde hair',
                'Auburn hair',
                'Red hair',
                'Gray hair',
                'Silver hair'
            ],
            vibrant_colors: [
                'Bright red hair',
                'Vibrant orange hair',
                'Electric blue hair',
                'Purple hair',
                'Pink hair',
                'Green hair',
                'Yellow hair',
                'Turquoise hair',
                'Magenta hair',
                'Neon colors'
            ],
            highlights_and_effects: [
                'Blonde highlights',
                'Brown highlights',
                'Red highlights',
                'Ombre hair effect',
                'Balayage hair effect',
                'Gradient hair colors',
                'Two-tone hair',
                'Color streaks',
                'Peekaboo highlights',
                'Money piece highlights'
            ],
            trendy_colors: [
                'Rose gold hair',
                'Platinum blonde hair',
                'Ash blonde hair',
                'Chocolate brown hair',
                'Chestnut brown hair',
                'Copper hair',
                'Burgundy hair',
                'Mahogany hair',
                'Honey blonde hair',
                'Caramel highlights'
            ],
            fantasy_colors: [
                'Unicorn hair colors',
                'Mermaid hair colors',
                'Galaxy hair colors',
                'Rainbow hair colors',
                'Pastel hair colors',
                'Metallic hair colors',
                'Holographic hair colors',
                'Chrome hair colors',
                'Iridescent hair colors',
                'Duochrome hair colors'
            ]
        };

        console.log('💡 Hair Color Suggestions:');
        for (const [category, suggestionList] of Object.entries(colorSuggestions)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return colorSuggestions;
    }

    /**
     * Get hair color prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getHairColorPromptExamples() {
        const promptExamples = {
            natural_colors: [
                'Change hair to natural black color',
                'Transform hair to dark brown shade',
                'Apply medium brown hair color',
                'Change to light brown hair',
                'Transform to natural blonde hair',
                'Apply strawberry blonde hair color',
                'Change to auburn hair color',
                'Transform to natural red hair',
                'Apply gray hair color',
                'Change to silver hair color'
            ],
            vibrant_colors: [
                'Change hair to bright red color',
                'Transform to vibrant orange hair',
                'Apply electric blue hair color',
                'Change to purple hair',
                'Transform to pink hair color',
                'Apply green hair color',
                'Change to yellow hair',
                'Transform to turquoise hair',
                'Apply magenta hair color',
                'Change to neon colors'
            ],
            highlights_and_effects: [
                'Add blonde highlights to hair',
                'Apply brown highlights',
                'Add red highlights to hair',
                'Create ombre hair effect',
                'Apply balayage hair effect',
                'Create gradient hair colors',
                'Apply two-tone hair colors',
                'Add color streaks to hair',
                'Create peekaboo highlights',
                'Apply money piece highlights'
            ],
            trendy_colors: [
                'Change hair to rose gold color',
                'Transform to platinum blonde hair',
                'Apply ash blonde hair color',
                'Change to chocolate brown hair',
                'Transform to chestnut brown hair',
                'Apply copper hair color',
                'Change to burgundy hair',
                'Transform to mahogany hair',
                'Apply honey blonde hair color',
                'Create caramel highlights'
            ],
            fantasy_colors: [
                'Create unicorn hair colors',
                'Apply mermaid hair colors',
                'Create galaxy hair colors',
                'Apply rainbow hair colors',
                'Create pastel hair colors',
                'Apply metallic hair colors',
                'Create holographic hair colors',
                'Apply chrome hair colors',
                'Create iridescent hair colors',
                'Apply duochrome hair colors'
            ]
        };

        console.log('💡 Hair Color Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get hair color use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getHairColorUseCases() {
        const useCases = {
            virtual_try_on: [
                'Virtual hair color try-on for salons',
                'Hair color consultation tools',
                'Before and after hair color previews',
                'Hair color selection assistance',
                'Virtual hair color makeovers'
            ],
            beauty_platforms: [
                'Beauty app hair color features',
                'Hair color recommendation systems',
                'Virtual hair color consultations',
                'Hair color trend exploration',
                'Beauty influencer content creation'
            ],
            personal_styling: [
                'Personal hair color experimentation',
                'Hair color change visualization',
                'Style inspiration and exploration',
                'Hair color trend testing',
                'Personal beauty transformations'
            ],
            marketing: [
                'Hair color product marketing',
                'Salon service promotion',
                'Hair color brand campaigns',
                'Beauty product demonstrations',
                'Hair color trend showcases'
            ],
            entertainment: [
                'Character hair color changes',
                'Costume and makeup design',
                'Creative hair color concepts',
                'Artistic hair color expressions',
                'Fantasy hair color creations'
            ]
        };

        console.log('💡 Hair Color Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Get hair color intensity suggestions
     * @returns {Object} Object containing intensity suggestions
     */
    getHairColorIntensitySuggestions() {
        const intensitySuggestions = {
            subtle: [
                'Apply subtle hair color changes',
                'Add gentle color highlights',
                'Create natural-looking color variations',
                'Apply soft color transitions',
                'Create minimal color effects'
            ],
            moderate: [
                'Apply noticeable hair color changes',
                'Create distinct color variations',
                'Add visible color highlights',
                'Apply balanced color effects',
                'Create moderate color transformations'
            ],
            dramatic: [
                'Apply bold hair color changes',
                'Create dramatic color transformations',
                'Add vibrant color effects',
                'Apply intense color variations',
                'Create striking color changes'
            ]
        };

        console.log('💡 Hair Color Intensity Suggestions:');
        for (const [intensity, suggestionList] of Object.entries(intensitySuggestions)) {
            console.log(`${intensity}: ${suggestionList}`);
        }
        return intensitySuggestions;
    }

    /**
     * Get hair color category recommendations
     * @returns {Object} Object containing category recommendations
     */
    getHairColorCategories() {
        const categories = {
            natural: {
                description: 'Natural hair colors that look realistic',
                examples: ['Black', 'Brown', 'Blonde', 'Red', 'Gray'],
                best_for: ['Professional looks', 'Natural appearances', 'Everyday styling']
            },
            vibrant: {
                description: 'Bright and bold hair colors',
                examples: ['Electric blue', 'Purple', 'Pink', 'Green', 'Orange'],
                best_for: ['Creative expression', 'Bold statements', 'Artistic looks']
            },
            highlights: {
                description: 'Highlight and lowlight effects',
                examples: ['Blonde highlights', 'Ombre', 'Balayage', 'Streaks', 'Peekaboo'],
                best_for: ['Subtle changes', 'Dimension', 'Style enhancement']
            },
            trendy: {
                description: 'Current popular hair color trends',
                examples: ['Rose gold', 'Platinum', 'Ash blonde', 'Copper', 'Burgundy'],
                best_for: ['Fashion-forward looks', 'Trend following', 'Modern styling']
            },
            fantasy: {
                description: 'Creative and fantasy hair colors',
                examples: ['Unicorn', 'Mermaid', 'Galaxy', 'Rainbow', 'Pastel'],
                best_for: ['Creative projects', 'Fantasy themes', 'Artistic expression']
            }
        };

        console.log('💡 Hair Color Categories:');
        for (const [category, info] of Object.entries(categories)) {
            console.log(`${category}: ${info.description}`);
            console.log(`  Examples: ${info.examples.join(', ')}`);
            console.log(`  Best for: ${info.best_for.join(', ')}`);
        }
        return categories;
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
     * Generate hair color change with prompt validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for hair color description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateHairColorChangeWithValidation(imageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return this.processHairColorChange(imageData, textPrompt, contentType);
    }

    /**
     * Get hair color best practices
     * @returns {Object} Object containing best practices
     */
    getHairColorBestPractices() {
        const bestPractices = {
            prompt_writing: [
                'Be specific about the desired hair color',
                'Include details about shade, tone, and intensity',
                'Mention specific hair color names or descriptions',
                'Describe the desired hair color effect clearly',
                'Keep prompts descriptive but concise'
            ],
            image_preparation: [
                'Start with high-quality source images',
                'Ensure hair is clearly visible and well-lit',
                'Avoid heavily compressed or low-quality images',
                'Use images with good hair definition and texture',
                'Ensure the person\'s face is clearly visible'
            ],
            hair_visibility: [
                'Ensure hair is not covered by hats or accessories',
                'Use images with good hair definition',
                'Avoid images with extreme angles or poor lighting',
                'Ensure hair texture is visible',
                'Use images where hair is the main focus'
            ],
            workflow_optimization: [
                'Batch process multiple images when possible',
                'Implement proper error handling and retry logic',
                'Monitor processing times and adjust expectations',
                'Store results efficiently to avoid reprocessing',
                'Implement progress tracking for better user experience'
            ]
        };

        console.log('💡 Hair Color Best Practices:');
        for (const [category, practiceList] of Object.entries(bestPractices)) {
            console.log(`${category}: ${practiceList}`);
        }
        return bestPractices;
    }

    /**
     * Get hair color performance tips
     * @returns {Object} Object containing performance tips
     */
    getHairColorPerformanceTips() {
        const performanceTips = {
            optimization: [
                'Use appropriate image formats (JPEG for photos, PNG for graphics)',
                'Optimize source images before processing',
                'Consider image dimensions and quality trade-offs',
                'Implement caching for frequently processed images',
                'Use batch processing for multiple images'
            ],
            resource_management: [
                'Monitor memory usage during processing',
                'Implement proper cleanup of temporary files',
                'Use efficient image loading and processing libraries',
                'Consider implementing image compression after processing',
                'Optimize network requests and retry logic'
            ],
            user_experience: [
                'Provide clear progress indicators',
                'Set realistic expectations for processing times',
                'Implement proper error handling and user feedback',
                'Offer hair color previews when possible',
                'Provide tips for better input images'
            ]
        };

        console.log('💡 Performance Tips:');
        for (const [category, tipList] of Object.entries(performanceTips)) {
            console.log(`${category}: ${tipList}`);
        }
        return performanceTips;
    }

    /**
     * Get hair color technical specifications
     * @returns {Object} Object containing technical specifications
     */
    getHairColorTechnicalSpecifications() {
        const specifications = {
            supported_formats: {
                input: ['JPEG', 'PNG'],
                output: ['JPEG'],
                color_spaces: ['RGB', 'sRGB']
            },
            size_limits: {
                max_file_size: '5MB',
                max_dimension: 'No specific limit',
                min_dimension: '1px'
            },
            processing: {
                max_retries: 5,
                retry_interval: '3 seconds',
                avg_processing_time: '15-30 seconds',
                timeout: 'No timeout limit'
            },
            features: {
                text_prompts: 'Required for hair color description',
                hair_detection: 'Automatic hair detection and segmentation',
                color_preservation: 'Preserves hair texture and style',
                facial_features: 'Keeps facial features untouched',
                output_quality: 'High-quality JPEG output'
            }
        };

        console.log('💡 Hair Color Technical Specifications:');
        for (const [category, specs] of Object.entries(specifications)) {
            console.log(`${category}: ${JSON.stringify(specs, null, 2)}`);
        }
        return specifications;
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
        const lightx = new LightXAIHairColorAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getHairColorTips();
        lightx.getHairColorSuggestions();
        lightx.getHairColorPromptExamples();
        lightx.getHairColorUseCases();
        lightx.getHairColorIntensitySuggestions();
        lightx.getHairColorCategories();
        lightx.getHairColorBestPractices();
        lightx.getHairColorPerformanceTips();
        lightx.getHairColorTechnicalSpecifications();

        // Load image (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';

        // Example 1: Natural hair colors
        const naturalColors = [
            'Change hair to natural black color',
            'Transform hair to dark brown shade',
            'Apply medium brown hair color',
            'Change to light brown hair',
            'Transform to natural blonde hair'
        ];

        for (const color of naturalColors) {
            const result = await lightx.generateHairColorChangeWithValidation(
                imagePath,
                color,
                'image/jpeg'
            );
            console.log(`🎉 ${color} result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 2: Vibrant hair colors
        const vibrantColors = [
            'Change hair to bright red color',
            'Transform to electric blue hair',
            'Apply purple hair color',
            'Change to pink hair',
            'Transform to green hair color'
        ];

        for (const color of vibrantColors) {
            const result = await lightx.generateHairColorChangeWithValidation(
                imagePath,
                color,
                'image/jpeg'
            );
            console.log(`🎉 ${color} result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 3: Highlights and effects
        const highlights = [
            'Add blonde highlights to hair',
            'Create ombre hair effect',
            'Apply balayage hair effect',
            'Add color streaks to hair',
            'Create peekaboo highlights'
        ];

        for (const highlight of highlights) {
            const result = await lightx.generateHairColorChangeWithValidation(
                imagePath,
                highlight,
                'image/jpeg'
            );
            console.log(`🎉 ${highlight} result:`);
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
module.exports = LightXAIHairColorAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
