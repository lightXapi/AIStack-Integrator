/**
 * LightX AI Sketch to Image API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered sketch to image transformation functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXSketch2ImageAPI {
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
     * Upload multiple images (sketch and optional style image)
     * @param {Buffer|string} sketchImageData - Sketch image data or file path
     * @param {Buffer|string} styleImageData - Style image data or file path (optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<{sketchURL: string, styleURL: string}>}
     */
    async uploadImages(sketchImageData, styleImageData = null, contentType = 'image/jpeg') {
        console.log('📤 Uploading sketch image...');
        const sketchURL = await this.uploadImage(sketchImageData, contentType);

        let styleURL = null;
        if (styleImageData) {
            console.log('📤 Uploading style image...');
            styleURL = await this.uploadImage(styleImageData, contentType);
        }

        return { sketchURL, styleURL };
    }

    /**
     * Generate sketch to image transformation
     * @param {string} imageUrl - URL of the sketch image
     * @param {number} strength - Strength parameter (0.0 to 1.0)
     * @param {string} textPrompt - Text prompt for transformation
     * @param {string} styleImageUrl - URL of the style image (optional)
     * @param {number} styleStrength - Style strength parameter (0.0 to 1.0, optional)
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateSketch2Image(imageUrl, strength, textPrompt, styleImageUrl = null, styleStrength = null) {
        const endpoint = `${this.baseURL}/v1/sketch2image`;

        const payload = {
            imageUrl: imageUrl,
            strength: strength,
            textPrompt: textPrompt
        };

        // Add optional parameters
        if (styleImageUrl) {
            payload.styleImageUrl = styleImageUrl;
        }
        if (styleStrength !== null) {
            payload.styleStrength = styleStrength;
        }

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Sketch to image request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`💬 Text prompt: "${textPrompt}"`);
            console.log(`🎨 Strength: ${strength}`);
            if (styleImageUrl) {
                console.log(`🎭 Style image: ${styleImageUrl}`);
                console.log(`🎨 Style strength: ${styleStrength}`);
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
                        console.log('✅ Sketch to image transformation completed successfully!');
                        if (status.output) {
                            console.log(`🖼️ Generated image: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Sketch to image transformation failed');

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
     * Complete workflow: Upload images and generate sketch to image transformation
     * @param {Buffer|string} sketchImageData - Sketch image data or file path
     * @param {number} strength - Strength parameter (0.0 to 1.0)
     * @param {string} textPrompt - Text prompt for transformation
     * @param {Buffer|string} styleImageData - Style image data or file path (optional)
     * @param {number} styleStrength - Style strength parameter (0.0 to 1.0, optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processSketch2ImageGeneration(sketchImageData, strength, textPrompt, styleImageData = null, styleStrength = null, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Sketch to Image API workflow...');

        // Step 1: Upload images
        console.log('📤 Uploading images...');
        const { sketchURL, styleURL } = await this.uploadImages(sketchImageData, styleImageData, contentType);
        console.log(`✅ Sketch image uploaded: ${sketchURL}`);
        if (styleURL) {
            console.log(`✅ Style image uploaded: ${styleURL}`);
        }

        // Step 2: Generate sketch to image transformation
        console.log('🎨 Generating sketch to image transformation...');
        const orderId = await this.generateSketch2Image(sketchURL, strength, textPrompt, styleURL, styleStrength);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get sketch to image transformation tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getSketch2ImageTips() {
        const tips = {
            sketch_quality: [
                'Use clear, well-defined sketches with good contrast',
                'Ensure sketch lines are visible and not too faint',
                'Avoid overly complex or cluttered sketches',
                'Use high-resolution sketches for better results',
                'Good sketch quality improves transformation results'
            ],
            strength_parameter: [
                'Higher strength (0.7-1.0) makes output more similar to sketch',
                'Lower strength (0.1-0.3) allows more creative interpretation',
                'Medium strength (0.4-0.6) balances sketch structure and creativity',
                'Experiment with different strength values for desired results',
                'Strength affects how much the original sketch structure is preserved'
            ],
            style_image: [
                'Choose style images with desired visual characteristics',
                'Ensure style image has good quality and clear features',
                'Style strength controls how much style is applied',
                'Higher style strength applies more style characteristics',
                'Use style images that complement your text prompt'
            ],
            text_prompts: [
                'Be specific about the final image you want to create',
                'Mention colors, lighting, mood, and visual style',
                'Include details about the subject matter and composition',
                'Combine style descriptions with content descriptions',
                'Keep prompts concise but descriptive'
            ],
            general: [
                'Sketch to image works best with clear, well-composed sketches',
                'Results may vary based on sketch quality and complexity',
                'Text prompts guide the image generation direction',
                'Allow 15-30 seconds for processing',
                'Experiment with different strength and style combinations'
            ]
        };

        console.log('💡 Sketch to Image Transformation Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get strength parameter suggestions for sketch to image
     * @returns {Object} Object containing strength suggestions
     */
    getStrengthSuggestions() {
        const strengthSuggestions = {
            conservative: {
                range: '0.7 - 1.0',
                description: 'Preserves most of the original sketch structure and composition',
                use_cases: ['Detailed sketch interpretation', 'Architectural drawings', 'Technical illustrations', 'Precise sketch rendering']
            },
            balanced: {
                range: '0.4 - 0.6',
                description: 'Balances sketch structure with creative interpretation',
                use_cases: ['Artistic sketch rendering', 'Creative interpretation', 'Style application', 'Balanced transformation']
            },
            creative: {
                range: '0.1 - 0.3',
                description: 'Allows significant creative interpretation while keeping basic sketch elements',
                use_cases: ['Artistic reimagining', 'Creative reinterpretation', 'Style-heavy transformation', 'Dramatic interpretation']
            }
        };

        console.log('💡 Strength Parameter Suggestions for Sketch to Image:');
        for (const [category, suggestion] of Object.entries(strengthSuggestions)) {
            console.log(`${category}: ${suggestion.range} - ${suggestion.description}`);
            console.log(`  Use cases: ${suggestion.use_cases.join(', ')}`);
        }
        return strengthSuggestions;
    }

    /**
     * Get style strength suggestions
     * @returns {Object} Object containing style strength suggestions
     */
    getStyleStrengthSuggestions() {
        const styleStrengthSuggestions = {
            subtle: {
                range: '0.1 - 0.3',
                description: 'Applies subtle style characteristics',
                use_cases: ['Gentle style influence', 'Color palette transfer', 'Light texture changes']
            },
            moderate: {
                range: '0.4 - 0.6',
                description: 'Applies moderate style characteristics',
                use_cases: ['Clear style transfer', 'Artistic interpretation', 'Medium style influence']
            },
            strong: {
                range: '0.7 - 1.0',
                description: 'Applies strong style characteristics',
                use_cases: ['Dramatic style transfer', 'Complete artistic transformation', 'Strong visual influence']
            }
        };

        console.log('💡 Style Strength Suggestions:');
        for (const [category, suggestion] of Object.entries(styleStrengthSuggestions)) {
            console.log(`${category}: ${suggestion.range} - ${suggestion.description}`);
            console.log(`  Use cases: ${suggestion.use_cases.join(', ')}`);
        }
        return styleStrengthSuggestions;
    }

    /**
     * Get sketch to image prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getSketch2ImagePromptExamples() {
        const promptExamples = {
            realistic: [
                'Create a realistic photograph with natural lighting and colors',
                'Generate a photorealistic image with detailed textures and shadows',
                'Transform into a high-quality photograph with professional lighting',
                'Create a realistic portrait with natural skin tones and expressions',
                'Generate a realistic landscape with natural colors and atmosphere'
            ],
            artistic: [
                'Transform into oil painting style with rich colors and brushstrokes',
                'Convert to watercolor painting with soft edges and flowing colors',
                'Create digital art with vibrant colors and smooth gradients',
                'Transform into pencil sketch with detailed shading and textures',
                'Convert to pop art style with bold colors and contrast'
            ],
            fantasy: [
                'Create a fantasy illustration with magical elements and vibrant colors',
                'Generate a sci-fi scene with futuristic technology and lighting',
                'Transform into a fantasy landscape with mystical atmosphere',
                'Create a fantasy character with magical powers and detailed costume',
                'Generate a fantasy creature with unique features and colors'
            ],
            architectural: [
                'Create a realistic architectural visualization with proper lighting',
                'Generate a modern building design with clean lines and materials',
                'Transform into an interior design with proper perspective and lighting',
                'Create a landscape architecture with natural elements',
                'Generate a futuristic building with innovative design elements'
            ]
        };

        console.log('💡 Sketch to Image Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get sketch types and their characteristics
     * @returns {Object} Object containing sketch type information
     */
    getSketchTypes() {
        const sketchTypes = {
            line_art: {
                description: 'Simple line drawings with clear outlines',
                best_for: ['Character design', 'Logo concepts', 'Simple illustrations'],
                tips: ['Use clear, bold lines', 'Avoid too many details', 'Keep composition simple']
            },
            architectural: {
                description: 'Technical drawings and architectural sketches',
                best_for: ['Building designs', 'Interior layouts', 'Urban planning'],
                tips: ['Use proper perspective', 'Include scale references', 'Keep lines precise']
            },
            character: {
                description: 'Character and figure sketches',
                best_for: ['Character design', 'Portrait concepts', 'Fashion design'],
                tips: ['Focus on proportions', 'Include facial features', 'Consider pose and expression']
            },
            landscape: {
                description: 'Nature and landscape sketches',
                best_for: ['Environment design', 'Nature scenes', 'Outdoor settings'],
                tips: ['Include horizon line', 'Show depth and perspective', 'Consider lighting direction']
            },
            concept: {
                description: 'Conceptual and idea sketches',
                best_for: ['Product design', 'Creative concepts', 'Abstract ideas'],
                tips: ['Focus on main concept', 'Use simple shapes', 'Include key elements']
            }
        };

        console.log('💡 Sketch Types and Characteristics:');
        for (const [type, info] of Object.entries(sketchTypes)) {
            console.log(`${type}: ${info.description}`);
            console.log(`  Best for: ${info.best_for.join(', ')}`);
            console.log(`  Tips: ${info.tips.join(', ')}`);
        }
        return sketchTypes;
    }

    /**
     * Validate parameters (utility function)
     * @param {number} strength - Strength parameter to validate
     * @param {string} textPrompt - Text prompt to validate
     * @param {number} styleStrength - Style strength parameter to validate (optional)
     * @returns {boolean} Whether the parameters are valid
     */
    validateParameters(strength, textPrompt, styleStrength = null) {
        // Validate strength
        if (strength < 0 || strength > 1) {
            console.log('❌ Strength must be between 0.0 and 1.0');
            return false;
        }

        // Validate text prompt
        if (!textPrompt || !textPrompt.trim()) {
            console.log('❌ Text prompt cannot be empty');
            return false;
        }

        if (textPrompt.length > 500) {
            console.log('❌ Text prompt is too long (max 500 characters)');
            return false;
        }

        // Validate style strength if provided
        if (styleStrength !== null && (styleStrength < 0 || styleStrength > 1)) {
            console.log('❌ Style strength must be between 0.0 and 1.0');
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
     * Generate sketch to image transformation with parameter validation
     * @param {Buffer|string} sketchImageData - Sketch image data or file path
     * @param {number} strength - Strength parameter (0.0 to 1.0)
     * @param {string} textPrompt - Text prompt for transformation
     * @param {Buffer|string} styleImageData - Style image data or file path (optional)
     * @param {number} styleStrength - Style strength parameter (0.0 to 1.0, optional)
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateSketch2ImageWithValidation(sketchImageData, strength, textPrompt, styleImageData = null, styleStrength = null, contentType = 'image/jpeg') {
        if (!this.validateParameters(strength, textPrompt, styleStrength)) {
            throw new Error('Invalid parameters');
        }

        return this.processSketch2ImageGeneration(sketchImageData, strength, textPrompt, styleImageData, styleStrength, contentType);
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
        const lightx = new LightXSketch2ImageAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getSketch2ImageTips();
        lightx.getStrengthSuggestions();
        lightx.getStyleStrengthSuggestions();
        lightx.getSketch2ImagePromptExamples();
        lightx.getSketchTypes();

        // Load images (replace with your image loading logic)
        const sketchImagePath = 'path/to/sketch-image.jpg';
        const styleImagePath = 'path/to/style-image.jpg';

        // Example 1: Conservative sketch to image transformation
        const result1 = await lightx.generateSketch2ImageWithValidation(
            sketchImagePath,
            0.8, // High strength to preserve sketch structure
            'Create a realistic photograph with natural lighting and colors',
            null, // No style image
            null, // No style strength
            'image/jpeg'
        );
        console.log('🎉 Conservative transformation result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Balanced transformation with style image
        const result2 = await lightx.generateSketch2ImageWithValidation(
            sketchImagePath,
            0.5, // Balanced strength
            'Transform into oil painting style with rich colors',
            styleImagePath, // Style image
            0.7, // Strong style influence
            'image/jpeg'
        );
        console.log('🎉 Balanced transformation result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Creative transformation with different strength values
        const strengthValues = [0.2, 0.5, 0.8];
        for (const strength of strengthValues) {
            const result = await lightx.generateSketch2ImageWithValidation(
                sketchImagePath,
                strength,
                'Create a fantasy illustration with magical elements and vibrant colors',
                null,
                null,
                'image/jpeg'
            );
            console.log(`🎉 Creative transformation (strength: ${strength}) result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 4: Get image dimensions
        const dimensions = await lightx.getImageDimensions(sketchImagePath);
        if (dimensions.width > 0 && dimensions.height > 0) {
            console.log(`📏 Original sketch: ${dimensions.width}x${dimensions.height}`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXSketch2ImageAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
