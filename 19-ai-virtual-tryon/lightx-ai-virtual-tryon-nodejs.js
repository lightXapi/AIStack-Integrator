/**
 * LightX AI Virtual Outfit Try-On API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered virtual outfit try-on functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXAIVirtualTryOnAPI {
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
     * Try on virtual outfit using AI
     * @param {string} imageUrl - URL of the input image (person)
     * @param {string} styleImageUrl - URL of the outfit reference image
     * @returns {Promise<string>} Order ID for tracking
     */
    async tryOnOutfit(imageUrl, styleImageUrl) {
        const endpoint = `${this.baseURL}/v2/aivirtualtryon`;

        const payload = {
            imageUrl: imageUrl,
            styleImageUrl: styleImageUrl
        };

        try {
            const response = await axios.post(endpoint, payload, {
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': this.apiKey
                }
            });

            if (response.data.statusCode !== 2000) {
                throw new Error(`Virtual try-on request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`👤 Person image: ${imageUrl}`);
            console.log(`👗 Outfit image: ${styleImageUrl}`);

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
                        console.log('✅ Virtual outfit try-on completed successfully!');
                        if (status.output) {
                            console.log(`👗 Virtual try-on result: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Virtual outfit try-on failed');

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
     * Complete workflow: Upload images and try on virtual outfit
     * @param {Buffer|string} personImageData - Person image data or file path
     * @param {Buffer|string} outfitImageData - Outfit reference image data or file path
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processVirtualTryOn(personImageData, outfitImageData, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Virtual Outfit Try-On API workflow...');

        // Step 1: Upload person image
        console.log('📤 Uploading person image...');
        const personImageUrl = await this.uploadImage(personImageData, contentType);
        console.log(`✅ Person image uploaded: ${personImageUrl}`);

        // Step 2: Upload outfit image
        console.log('📤 Uploading outfit image...');
        const outfitImageUrl = await this.uploadImage(outfitImageData, contentType);
        console.log(`✅ Outfit image uploaded: ${outfitImageUrl}`);

        // Step 3: Try on virtual outfit
        console.log('👗 Trying on virtual outfit...');
        const orderId = await this.tryOnOutfit(personImageUrl, outfitImageUrl);

        // Step 4: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get virtual try-on tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getVirtualTryOnTips() {
        const tips = {
            person_image: [
                'Use clear, well-lit photos with good body visibility',
                'Ensure the person is clearly visible and well-positioned',
                'Avoid heavily compressed or low-quality source images',
                'Use high-quality source images for best virtual try-on results',
                'Good lighting helps preserve body shape and details'
            ],
            outfit_image: [
                'Use clear outfit reference images with good detail',
                'Ensure the outfit is clearly visible and well-lit',
                'Choose outfit images with good color and texture definition',
                'Use high-quality outfit images for better transfer results',
                'Good outfit image quality improves virtual try-on accuracy'
            ],
            body_visibility: [
                'Ensure the person\'s body is clearly visible',
                'Avoid images where the person is heavily covered',
                'Use images with good body definition and posture',
                'Ensure the person\'s face is clearly visible',
                'Avoid images with extreme angles or poor lighting'
            ],
            outfit_selection: [
                'Choose outfit images that match the person\'s body type',
                'Select outfits with clear, well-defined shapes',
                'Use outfit images with good color contrast',
                'Ensure outfit images show the complete garment',
                'Choose outfits that complement the person\'s style'
            ],
            general: [
                'AI virtual try-on works best with clear, detailed source images',
                'Results may vary based on input image quality and outfit visibility',
                'Virtual try-on preserves body shape and facial features',
                'Allow 15-30 seconds for processing',
                'Experiment with different outfit combinations for varied results'
            ]
        };

        console.log('💡 Virtual Try-On Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get outfit category suggestions
     * @returns {Object} Object containing outfit category suggestions
     */
    getOutfitCategorySuggestions() {
        const outfitCategories = {
            casual: [
                'Casual t-shirts and jeans',
                'Comfortable hoodies and sweatpants',
                'Everyday dresses and skirts',
                'Casual blouses and trousers',
                'Relaxed shirts and shorts'
            ],
            formal: [
                'Business suits and blazers',
                'Formal dresses and gowns',
                'Dress shirts and dress pants',
                'Professional blouses and skirts',
                'Elegant evening wear'
            ],
            party: [
                'Party dresses and outfits',
                'Cocktail dresses and suits',
                'Festive clothing and accessories',
                'Celebration wear and costumes',
                'Special occasion outfits'
            ],
            seasonal: [
                'Summer dresses and shorts',
                'Winter coats and sweaters',
                'Spring jackets and light layers',
                'Fall clothing and warm accessories',
                'Seasonal fashion trends'
            ],
            sportswear: [
                'Athletic wear and gym clothes',
                'Sports jerseys and team wear',
                'Activewear and workout gear',
                'Running clothes and sneakers',
                'Fitness and sports apparel'
            ]
        };

        console.log('💡 Outfit Category Suggestions:');
        for (const [category, suggestionList] of Object.entries(outfitCategories)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return outfitCategories;
    }

    /**
     * Get virtual try-on use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getVirtualTryOnUseCases() {
        const useCases = {
            e_commerce: [
                'Online shopping virtual try-on',
                'E-commerce product visualization',
                'Online store outfit previews',
                'Virtual fitting room experiences',
                'Online shopping assistance'
            ],
            fashion_retail: [
                'Fashion store virtual try-on',
                'Retail outfit visualization',
                'In-store virtual fitting',
                'Fashion consultation tools',
                'Retail customer experience'
            ],
            personal_styling: [
                'Personal style exploration',
                'Virtual wardrobe try-on',
                'Style consultation services',
                'Personal fashion experiments',
                'Individual styling assistance'
            ],
            social_media: [
                'Social media outfit posts',
                'Fashion influencer content',
                'Style sharing platforms',
                'Fashion community features',
                'Social fashion experiences'
            ],
            entertainment: [
                'Character outfit changes',
                'Costume design and visualization',
                'Creative outfit concepts',
                'Artistic fashion expressions',
                'Entertainment industry applications'
            ]
        };

        console.log('💡 Virtual Try-On Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Get outfit style suggestions
     * @returns {Object} Object containing style suggestions
     */
    getOutfitStyleSuggestions() {
        const styleSuggestions = {
            classic: [
                'Classic business attire',
                'Traditional formal wear',
                'Timeless casual outfits',
                'Classic evening wear',
                'Traditional professional dress'
            ],
            modern: [
                'Contemporary fashion trends',
                'Modern casual wear',
                'Current style favorites',
                'Trendy outfit combinations',
                'Modern fashion statements'
            ],
            vintage: [
                'Retro fashion styles',
                'Vintage clothing pieces',
                'Classic era outfits',
                'Nostalgic fashion trends',
                'Historical style references'
            ],
            bohemian: [
                'Bohemian style outfits',
                'Free-spirited fashion',
                'Artistic clothing choices',
                'Creative style expressions',
                'Alternative fashion trends'
            ],
            minimalist: [
                'Simple, clean outfits',
                'Minimalist fashion choices',
                'Understated style pieces',
                'Clean, modern aesthetics',
                'Simple fashion statements'
            ]
        };

        console.log('💡 Outfit Style Suggestions:');
        for (const [style, suggestionList] of Object.entries(styleSuggestions)) {
            console.log(`${style}: ${suggestionList}`);
        }
        return styleSuggestions;
    }

    /**
     * Get virtual try-on best practices
     * @returns {Object} Object containing best practices
     */
    getVirtualTryOnBestPractices() {
        const bestPractices = {
            image_preparation: [
                'Start with high-quality source images',
                'Ensure good lighting and contrast in both images',
                'Avoid heavily compressed or low-quality images',
                'Use images with clear, well-defined details',
                'Ensure both person and outfit are clearly visible'
            ],
            outfit_selection: [
                'Choose outfit images that complement the person',
                'Select outfits with clear, well-defined shapes',
                'Use outfit images with good color contrast',
                'Ensure outfit images show the complete garment',
                'Choose outfits that match the person\'s body type'
            ],
            body_visibility: [
                'Ensure the person\'s body is clearly visible',
                'Avoid images where the person is heavily covered',
                'Use images with good body definition and posture',
                'Ensure the person\'s face is clearly visible',
                'Avoid images with extreme angles or poor lighting'
            ],
            workflow_optimization: [
                'Batch process multiple outfit combinations when possible',
                'Implement proper error handling and retry logic',
                'Monitor processing times and adjust expectations',
                'Store results efficiently to avoid reprocessing',
                'Implement progress tracking for better user experience'
            ]
        };

        console.log('💡 Virtual Try-On Best Practices:');
        for (const [category, practiceList] of Object.entries(bestPractices)) {
            console.log(`${category}: ${practiceList}`);
        }
        return bestPractices;
    }

    /**
     * Get virtual try-on performance tips
     * @returns {Object} Object containing performance tips
     */
    getVirtualTryOnPerformanceTips() {
        const performanceTips = {
            optimization: [
                'Use appropriate image formats (JPEG for photos, PNG for graphics)',
                'Optimize source images before processing',
                'Consider image dimensions and quality trade-offs',
                'Implement caching for frequently processed images',
                'Use batch processing for multiple outfit combinations'
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
                'Offer outfit previews when possible',
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
     * Get virtual try-on technical specifications
     * @returns {Object} Object containing technical specifications
     */
    getVirtualTryOnTechnicalSpecifications() {
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
                person_detection: 'Automatic person detection and body segmentation',
                outfit_transfer: 'Seamless outfit transfer onto person',
                body_preservation: 'Preserves body shape and facial features',
                realistic_rendering: 'Realistic outfit fitting and appearance',
                output_quality: 'High-quality JPEG output'
            }
        };

        console.log('💡 Virtual Try-On Technical Specifications:');
        for (const [category, specs] of Object.entries(specifications)) {
            console.log(`${category}: ${JSON.stringify(specs, null, 2)}`);
        }
        return specifications;
    }

    /**
     * Get virtual try-on workflow examples
     * @returns {Object} Object containing workflow examples
     */
    getVirtualTryOnWorkflowExamples() {
        const workflowExamples = {
            basic_workflow: [
                '1. Prepare high-quality person image',
                '2. Select outfit reference image',
                '3. Upload both images to LightX servers',
                '4. Submit virtual try-on request',
                '5. Monitor order status until completion',
                '6. Download virtual try-on result'
            ],
            advanced_workflow: [
                '1. Prepare person image with clear body visibility',
                '2. Select outfit image with good detail and contrast',
                '3. Upload both images to LightX servers',
                '4. Submit virtual try-on request with validation',
                '5. Monitor processing with retry logic',
                '6. Validate and download result',
                '7. Apply additional processing if needed'
            ],
            batch_workflow: [
                '1. Prepare multiple person images',
                '2. Select multiple outfit combinations',
                '3. Upload all images in parallel',
                '4. Submit multiple virtual try-on requests',
                '5. Monitor all orders concurrently',
                '6. Collect and organize results',
                '7. Apply quality control and validation'
            ]
        };

        console.log('💡 Virtual Try-On Workflow Examples:');
        for (const [workflow, stepList] of Object.entries(workflowExamples)) {
            console.log(`${workflow}:`);
            stepList.forEach(step => console.log(`  ${step}`));
        }
        return workflowExamples;
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
     * Get outfit combination suggestions
     * @returns {Object} Object containing combination suggestions
     */
    getOutfitCombinationSuggestions() {
        const combinations = {
            casual_combinations: [
                'T-shirt with jeans and sneakers',
                'Hoodie with joggers and casual shoes',
                'Blouse with trousers and flats',
                'Sweater with skirt and boots',
                'Polo shirt with shorts and sandals'
            ],
            formal_combinations: [
                'Blazer with dress pants and dress shoes',
                'Dress shirt with suit and formal shoes',
                'Blouse with pencil skirt and heels',
                'Dress with blazer and pumps',
                'Suit with dress shirt and oxfords'
            ],
            party_combinations: [
                'Cocktail dress with heels and accessories',
                'Party top with skirt and party shoes',
                'Evening gown with elegant accessories',
                'Festive outfit with matching accessories',
                'Celebration wear with themed accessories'
            ],
            seasonal_combinations: [
                'Summer dress with sandals and sun hat',
                'Winter coat with boots and scarf',
                'Spring jacket with light layers and sneakers',
                'Fall sweater with jeans and ankle boots',
                'Seasonal outfit with appropriate accessories'
            ]
        };

        console.log('💡 Outfit Combination Suggestions:');
        for (const [category, combinationList] of Object.entries(combinations)) {
            console.log(`${category}: ${combinationList}`);
        }
        return combinations;
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
        const lightx = new LightXAIVirtualTryOnAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getVirtualTryOnTips();
        lightx.getOutfitCategorySuggestions();
        lightx.getVirtualTryOnUseCases();
        lightx.getOutfitStyleSuggestions();
        lightx.getVirtualTryOnBestPractices();
        lightx.getVirtualTryOnPerformanceTips();
        lightx.getVirtualTryOnTechnicalSpecifications();
        lightx.getVirtualTryOnWorkflowExamples();
        lightx.getOutfitCombinationSuggestions();

        // Load images (replace with your image loading logic)
        const personImagePath = 'path/to/person-image.jpg';
        const outfitImagePath = 'path/to/outfit-image.jpg';

        // Example 1: Casual outfit try-on
        const result1 = await lightx.processVirtualTryOn(
            personImagePath,
            outfitImagePath,
            'image/jpeg'
        );
        console.log('🎉 Casual outfit try-on result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Try different outfit combinations
        const outfitCombinations = [
            'path/to/casual-outfit.jpg',
            'path/to/formal-outfit.jpg',
            'path/to/party-outfit.jpg',
            'path/to/sportswear-outfit.jpg',
            'path/to/seasonal-outfit.jpg'
        ];

        for (const outfitPath of outfitCombinations) {
            const result = await lightx.processVirtualTryOn(
                personImagePath,
                outfitPath,
                'image/jpeg'
            );
            console.log(`🎉 ${outfitPath} try-on result:`);
            console.log(`Order ID: ${result.orderId}`);
            console.log(`Status: ${result.status}`);
            if (result.output) {
                console.log(`Output: ${result.output}`);
            }
        }

        // Example 3: Get image dimensions
        const personDimensions = await lightx.getImageDimensions(personImagePath);
        const outfitDimensions = await lightx.getImageDimensions(outfitImagePath);
        
        if (personDimensions.width > 0 && personDimensions.height > 0) {
            console.log(`📏 Person image: ${personDimensions.width}x${personDimensions.height}`);
        }
        if (outfitDimensions.width > 0 && outfitDimensions.height > 0) {
            console.log(`📏 Outfit image: ${outfitDimensions.width}x${outfitDimensions.height}`);
        }

    } catch (error) {
        console.error('❌ Example failed:', error.message);
    }
}

// Export the class for use in other modules
module.exports = LightXAIVirtualTryOnAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
