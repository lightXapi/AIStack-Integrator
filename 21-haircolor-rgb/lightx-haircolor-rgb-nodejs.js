/**
 * LightX Hair Color RGB API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered hair color changing using hex color codes.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXHairColorRGBAPI {
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
     * Change hair color using hex color code
     * @param {string} imageUrl - URL of the input image
     * @param {string} hairHexColor - Hex color code (e.g., "#FF0000")
     * @param {number} colorStrength - Color strength between 0.1 to 1
     * @returns {Promise<string>} Order ID for tracking
     */
    async changeHairColor(imageUrl, hairHexColor, colorStrength = 0.5) {
        const endpoint = `${this.baseURL}/v2/haircolor-rgb`;

        // Validate hex color
        if (!this.isValidHexColor(hairHexColor)) {
            throw new Error('Invalid hex color format. Use format like #FF0000');
        }

        // Validate color strength
        if (colorStrength < 0.1 || colorStrength > 1) {
            throw new Error('Color strength must be between 0.1 and 1');
        }

        const payload = {
            imageUrl: imageUrl,
            hairHexColor: hairHexColor,
            colorStrength: colorStrength
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
            console.log(`🎨 Hair color: ${hairHexColor}`);
            console.log(`💪 Color strength: ${colorStrength}`);

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
                        console.log('✅ Hair color change completed successfully!');
                        if (status.output) {
                            console.log(`🎨 Hair color result: ${status.output}`);
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
     * @param {string} hairHexColor - Hex color code
     * @param {number} colorStrength - Color strength between 0.1 to 1
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processHairColorChange(imageData, hairHexColor, colorStrength = 0.5, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX Hair Color RGB API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Change hair color
        console.log('🎨 Changing hair color...');
        const orderId = await this.changeHairColor(imageUrl, hairHexColor, colorStrength);

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
                'Use clear, well-lit photos with good hair visibility',
                'Ensure the person\'s hair is clearly visible and well-positioned',
                'Avoid heavily compressed or low-quality source images',
                'Use high-quality source images for best hair color results',
                'Good lighting helps preserve hair texture and details'
            ],
            hex_colors: [
                'Use valid hex color codes in format #RRGGBB',
                'Common hair colors: #000000 (black), #8B4513 (brown), #FFD700 (blonde)',
                'Experiment with different shades for natural-looking results',
                'Consider skin tone compatibility when choosing colors',
                'Use color strength to control intensity of the color change'
            ],
            color_strength: [
                'Lower values (0.1-0.3) create subtle color changes',
                'Medium values (0.4-0.7) provide balanced color intensity',
                'Higher values (0.8-1.0) create bold, vibrant color changes',
                'Start with medium strength and adjust based on results',
                'Consider the original hair color when setting strength'
            ],
            hair_visibility: [
                'Ensure the person\'s hair is clearly visible',
                'Avoid images where hair is heavily covered or obscured',
                'Use images with good hair definition and texture',
                'Ensure the person\'s face is clearly visible',
                'Avoid images with extreme angles or poor lighting'
            ],
            general: [
                'AI hair color change works best with clear, detailed source images',
                'Results may vary based on input image quality and hair visibility',
                'Hair color change preserves hair texture while changing color',
                'Allow 15-30 seconds for processing',
                'Experiment with different colors and strengths for varied results'
            ]
        };

        console.log('💡 Hair Color Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get popular hair color hex codes
     * @returns {Object} Object containing popular hair color hex codes
     */
    getPopularHairColors() {
        const hairColors = {
            natural_blondes: [
                { name: 'Platinum Blonde', hex: '#F5F5DC', strength: 0.7 },
                { name: 'Golden Blonde', hex: '#FFD700', strength: 0.6 },
                { name: 'Honey Blonde', hex: '#DAA520', strength: 0.5 },
                { name: 'Strawberry Blonde', hex: '#D2691E', strength: 0.6 },
                { name: 'Ash Blonde', hex: '#C0C0C0', strength: 0.5 }
            ],
            natural_browns: [
                { name: 'Light Brown', hex: '#8B4513', strength: 0.5 },
                { name: 'Medium Brown', hex: '#654321', strength: 0.6 },
                { name: 'Dark Brown', hex: '#3C2414', strength: 0.7 },
                { name: 'Chestnut Brown', hex: '#954535', strength: 0.5 },
                { name: 'Auburn Brown', hex: '#A52A2A', strength: 0.6 }
            ],
            natural_blacks: [
                { name: 'Jet Black', hex: '#000000', strength: 0.8 },
                { name: 'Soft Black', hex: '#1C1C1C', strength: 0.7 },
                { name: 'Blue Black', hex: '#0A0A0A', strength: 0.8 },
                { name: 'Brown Black', hex: '#2F1B14', strength: 0.6 }
            ],
            fashion_colors: [
                { name: 'Vibrant Red', hex: '#FF0000', strength: 0.8 },
                { name: 'Purple', hex: '#800080', strength: 0.7 },
                { name: 'Blue', hex: '#0000FF', strength: 0.7 },
                { name: 'Pink', hex: '#FF69B4', strength: 0.6 },
                { name: 'Green', hex: '#008000', strength: 0.6 },
                { name: 'Orange', hex: '#FFA500', strength: 0.7 }
            ],
            highlights: [
                { name: 'Blonde Highlights', hex: '#FFD700', strength: 0.4 },
                { name: 'Red Highlights', hex: '#FF4500', strength: 0.3 },
                { name: 'Purple Highlights', hex: '#9370DB', strength: 0.3 },
                { name: 'Blue Highlights', hex: '#4169E1', strength: 0.3 }
            ]
        };

        console.log('💡 Popular Hair Colors:');
        for (const [category, colorList] of Object.entries(hairColors)) {
            console.log(`${category}:`);
            colorList.forEach(color => {
                console.log(`  ${color.name}: ${color.hex} (strength: ${color.strength})`);
            });
        }
        return hairColors;
    }

    /**
     * Get hair color use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getHairColorUseCases() {
        const useCases = {
            virtual_makeovers: [
                'Virtual hair color try-on',
                'Makeover simulation apps',
                'Beauty consultation tools',
                'Personal styling experiments',
                'Virtual hair color previews'
            ],
            beauty_platforms: [
                'Beauty app hair color features',
                'Salon consultation tools',
                'Hair color recommendation systems',
                'Beauty influencer content',
                'Hair color trend visualization'
            ],
            personal_styling: [
                'Personal style exploration',
                'Hair color decision making',
                'Style consultation services',
                'Personal fashion experiments',
                'Individual styling assistance'
            ],
            social_media: [
                'Social media hair color posts',
                'Beauty influencer content',
                'Hair color sharing platforms',
                'Beauty community features',
                'Social beauty experiences'
            ],
            entertainment: [
                'Character hair color changes',
                'Costume design and visualization',
                'Creative hair color concepts',
                'Artistic hair color expressions',
                'Entertainment industry applications'
            ]
        };

        console.log('💡 Hair Color Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Get hair color best practices
     * @returns {Object} Object containing best practices
     */
    getHairColorBestPractices() {
        const bestPractices = {
            image_preparation: [
                'Start with high-quality source images',
                'Ensure good lighting and contrast in the original image',
                'Avoid heavily compressed or low-quality images',
                'Use images with clear, well-defined hair details',
                'Ensure the person\'s hair is clearly visible and well-lit'
            ],
            color_selection: [
                'Choose hex colors that complement skin tone',
                'Consider the original hair color when selecting new colors',
                'Use color strength to control the intensity of change',
                'Experiment with different shades for natural results',
                'Test multiple color options to find the best match'
            ],
            strength_control: [
                'Start with medium strength (0.5) and adjust as needed',
                'Use lower strength for subtle, natural-looking changes',
                'Use higher strength for bold, dramatic color changes',
                'Consider the contrast with the original hair color',
                'Balance color intensity with natural appearance'
            ],
            workflow_optimization: [
                'Batch process multiple color variations when possible',
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
                'Use batch processing for multiple color variations'
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
                'Offer color previews when possible',
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
                hex_color_codes: 'Required for precise color specification',
                color_strength: 'Controls intensity of color change (0.1 to 1.0)',
                hair_detection: 'Automatic hair detection and segmentation',
                texture_preservation: 'Preserves original hair texture and style',
                output_quality: 'High-quality JPEG output'
            }
        };

        console.log('💡 Hair Color Technical Specifications:');
        for (const [category, specs] of Object.entries(specifications)) {
            console.log(`${category}: ${JSON.stringify(specs, null, 2)}`);
        }
        return specifications;
    }

    /**
     * Get hair color workflow examples
     * @returns {Object} Object containing workflow examples
     */
    getHairColorWorkflowExamples() {
        const workflowExamples = {
            basic_workflow: [
                '1. Prepare high-quality input image with clear hair visibility',
                '2. Choose desired hair color hex code',
                '3. Set appropriate color strength (0.1 to 1.0)',
                '4. Upload image to LightX servers',
                '5. Submit hair color change request',
                '6. Monitor order status until completion',
                '7. Download hair color result'
            ],
            advanced_workflow: [
                '1. Prepare input image with clear hair definition',
                '2. Select multiple color options for comparison',
                '3. Set different strength values for each color',
                '4. Upload image to LightX servers',
                '5. Submit multiple hair color requests',
                '6. Monitor all orders with retry logic',
                '7. Compare and select best results',
                '8. Apply additional processing if needed'
            ],
            batch_workflow: [
                '1. Prepare multiple input images',
                '2. Create color palette for batch processing',
                '3. Upload all images in parallel',
                '4. Submit multiple hair color requests',
                '5. Monitor all orders concurrently',
                '6. Collect and organize results',
                '7. Apply quality control and validation'
            ]
        };

        console.log('💡 Hair Color Workflow Examples:');
        for (const [workflow, stepList] of Object.entries(workflowExamples)) {
            console.log(`${workflow}:`);
            stepList.forEach(step => console.log(`  ${step}`));
        }
        return workflowExamples;
    }

    /**
     * Validate hex color format
     * @param {string} hexColor - Hex color to validate
     * @returns {boolean} Whether the hex color is valid
     */
    isValidHexColor(hexColor) {
        const hexPattern = /^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/;
        return hexPattern.test(hexColor);
    }

    /**
     * Convert RGB to hex color
     * @param {number} r - Red value (0-255)
     * @param {number} g - Green value (0-255)
     * @param {number} b - Blue value (0-255)
     * @returns {string} Hex color code
     */
    rgbToHex(r, g, b) {
        const toHex = (n) => {
            const hex = Math.max(0, Math.min(255, Math.round(n))).toString(16);
            return hex.length === 1 ? '0' + hex : hex;
        };
        return `#${toHex(r)}${toHex(g)}${toHex(b)}`.toUpperCase();
    }

    /**
     * Convert hex color to RGB
     * @param {string} hex - Hex color code
     * @returns {Object} RGB values
     */
    hexToRgb(hex) {
        const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
        return result ? {
            r: parseInt(result[1], 16),
            g: parseInt(result[2], 16),
            b: parseInt(result[3], 16)
        } : null;
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
     * Change hair color with validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} hairHexColor - Hex color code
     * @param {number} colorStrength - Color strength between 0.1 to 1
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async changeHairColorWithValidation(imageData, hairHexColor, colorStrength = 0.5, contentType = 'image/jpeg') {
        if (!this.isValidHexColor(hairHexColor)) {
            throw new Error('Invalid hex color format. Use format like #FF0000');
        }

        if (colorStrength < 0.1 || colorStrength > 1) {
            throw new Error('Color strength must be between 0.1 and 1');
        }

        return this.processHairColorChange(imageData, hairHexColor, colorStrength, contentType);
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
        const lightx = new LightXHairColorRGBAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getHairColorTips();
        lightx.getPopularHairColors();
        lightx.getHairColorUseCases();
        lightx.getHairColorBestPractices();
        lightx.getHairColorPerformanceTips();
        lightx.getHairColorTechnicalSpecifications();
        lightx.getHairColorWorkflowExamples();

        // Load image (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';

        // Example 1: Natural blonde hair
        const result1 = await lightx.changeHairColorWithValidation(
            imagePath,
            '#FFD700', // Golden blonde
            0.6, // Medium strength
            'image/jpeg'
        );
        console.log('🎉 Golden blonde result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Fashion color - vibrant red
        const result2 = await lightx.changeHairColorWithValidation(
            imagePath,
            '#FF0000', // Vibrant red
            0.8, // High strength
            'image/jpeg'
        );
        console.log('🎉 Vibrant red result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Try different hair colors
        const hairColors = [
            { color: '#000000', name: 'Jet Black', strength: 0.8 },
            { color: '#8B4513', name: 'Light Brown', strength: 0.5 },
            { color: '#800080', name: 'Purple', strength: 0.7 },
            { color: '#FF69B4', name: 'Pink', strength: 0.6 },
            { color: '#0000FF', name: 'Blue', strength: 0.7 }
        ];

        for (const hairColor of hairColors) {
            const result = await lightx.changeHairColorWithValidation(
                imagePath,
                hairColor.color,
                hairColor.strength,
                'image/jpeg'
            );
            console.log(`🎉 ${hairColor.name} result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 4: Color conversion utilities
        const rgb = lightx.hexToRgb('#FFD700');
        console.log(`RGB values for #FFD700:`, rgb);
        
        const hex = lightx.rgbToHex(255, 215, 0);
        console.log(`Hex for RGB(255, 215, 0): ${hex}`);

        // Example 5: Get image dimensions
        const dimensions = await lightx.getImageDimensions(imagePath);
        if (dimensions.width > 0 && dimensions.height > 0) {
            console.log(`📏 Original image: ${dimensions.width}x${dimensions.height}`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXHairColorRGBAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
