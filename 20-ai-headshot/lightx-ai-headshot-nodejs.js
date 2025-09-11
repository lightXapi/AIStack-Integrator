/**
 * LightX AI Headshot Generator API Integration - Node.js
 * 
 * This implementation provides a complete integration with LightX API v2
 * for AI-powered professional headshot generation functionality.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

class LightXAIHeadshotAPI {
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
     * Generate professional headshot using AI
     * @param {string} imageUrl - URL of the input image
     * @param {string} textPrompt - Text prompt for professional outfit description
     * @returns {Promise<string>} Order ID for tracking
     */
    async generateHeadshot(imageUrl, textPrompt) {
        const endpoint = `${this.baseURL}/v2/headshot/`;

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
                throw new Error(`Headshot generation request failed: ${response.data.message}`);
            }

            const orderInfo = response.data.body;

            console.log(`📋 Order created: ${orderInfo.orderId}`);
            console.log(`🔄 Max retries allowed: ${orderInfo.maxRetriesAllowed}`);
            console.log(`⏱️  Average response time: ${orderInfo.avgResponseTimeInSec} seconds`);
            console.log(`📊 Status: ${orderInfo.status}`);
            console.log(`👔 Professional prompt: "${textPrompt}"`);

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
                        console.log('✅ Professional headshot generated successfully!');
                        if (status.output) {
                            console.log(`👔 Professional headshot: ${status.output}`);
                        }
                        return status;

                    case 'failed':
                        throw new Error('Professional headshot generation failed');

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
     * Complete workflow: Upload image and generate professional headshot
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for professional outfit description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async processHeadshotGeneration(imageData, textPrompt, contentType = 'image/jpeg') {
        console.log('🚀 Starting LightX AI Headshot Generator API workflow...');

        // Step 1: Upload image
        console.log('📤 Uploading image...');
        const imageUrl = await this.uploadImage(imageData, contentType);
        console.log(`✅ Image uploaded: ${imageUrl}`);

        // Step 2: Generate professional headshot
        console.log('👔 Generating professional headshot...');
        const orderId = await this.generateHeadshot(imageUrl, textPrompt);

        // Step 3: Wait for completion
        console.log('⏳ Waiting for processing to complete...');
        const result = await this.waitForCompletion(orderId);

        return result;
    }

    /**
     * Get headshot generation tips and best practices
     * @returns {Object} Object containing tips for better results
     */
    getHeadshotTips() {
        const tips = {
            input_image: [
                'Use clear, well-lit photos with good face visibility',
                'Ensure the person\'s face is clearly visible and well-positioned',
                'Avoid heavily compressed or low-quality source images',
                'Use high-quality source images for best headshot results',
                'Good lighting helps preserve facial features and details'
            ],
            text_prompts: [
                'Be specific about the professional look you want to achieve',
                'Include details about outfit style, background, and setting',
                'Mention specific professional attire or business wear',
                'Describe the desired professional appearance clearly',
                'Keep prompts descriptive but concise'
            ],
            professional_setting: [
                'Specify professional background settings',
                'Mention corporate or business environments',
                'Include details about lighting and atmosphere',
                'Describe professional color schemes',
                'Specify formal or business-casual settings'
            ],
            outfit_selection: [
                'Choose professional attire descriptions',
                'Select business-appropriate clothing',
                'Use formal or business-casual outfit descriptions',
                'Ensure outfit descriptions match professional standards',
                'Choose outfits that complement the person\'s appearance'
            ],
            general: [
                'AI headshot generation works best with clear, detailed source images',
                'Results may vary based on input image quality and prompt clarity',
                'Headshots preserve facial features while enhancing professional appearance',
                'Allow 15-30 seconds for processing',
                'Experiment with different professional prompts for varied results'
            ]
        };

        console.log('💡 Headshot Generation Tips:');
        for (const [category, tipList] of Object.entries(tips)) {
            console.log(`${category}: ${tipList}`);
        }
        return tips;
    }

    /**
     * Get professional outfit suggestions
     * @returns {Object} Object containing professional outfit suggestions
     */
    getProfessionalOutfitSuggestions() {
        const outfitSuggestions = {
            business_formal: [
                'Dark business suit with white dress shirt',
                'Professional blazer with dress pants',
                'Formal business attire with tie',
                'Corporate suit with dress shoes',
                'Executive business wear'
            ],
            business_casual: [
                'Blazer with dress shirt and chinos',
                'Professional sweater with dress pants',
                'Business casual blouse with skirt',
                'Smart casual outfit with dress shoes',
                'Professional casual attire'
            ],
            corporate: [
                'Corporate dress with blazer',
                'Professional blouse with pencil skirt',
                'Business dress with heels',
                'Corporate suit with accessories',
                'Professional corporate wear'
            ],
            executive: [
                'Executive suit with power tie',
                'Professional dress with statement jewelry',
                'Executive blazer with dress pants',
                'Power suit with professional accessories',
                'Executive business attire'
            ],
            professional: [
                'Professional blouse with dress pants',
                'Business dress with cardigan',
                'Professional shirt with blazer',
                'Business casual with professional accessories',
                'Professional work attire'
            ]
        };

        console.log('💡 Professional Outfit Suggestions:');
        for (const [category, suggestionList] of Object.entries(outfitSuggestions)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return outfitSuggestions;
    }

    /**
     * Get professional background suggestions
     * @returns {Object} Object containing background suggestions
     */
    getProfessionalBackgroundSuggestions() {
        const backgroundSuggestions = {
            office_settings: [
                'Modern office background',
                'Corporate office environment',
                'Professional office setting',
                'Business office backdrop',
                'Executive office background'
            ],
            studio_settings: [
                'Professional studio background',
                'Clean studio backdrop',
                'Professional photography studio',
                'Studio lighting setup',
                'Professional portrait studio'
            ],
            neutral_backgrounds: [
                'Neutral professional background',
                'Clean white background',
                'Professional gray backdrop',
                'Subtle professional background',
                'Minimalist professional setting'
            ],
            corporate_backgrounds: [
                'Corporate building background',
                'Business environment backdrop',
                'Professional corporate setting',
                'Executive office background',
                'Corporate headquarters setting'
            ],
            modern_backgrounds: [
                'Modern professional background',
                'Contemporary office setting',
                'Sleek professional backdrop',
                'Modern business environment',
                'Contemporary corporate setting'
            ]
        };

        console.log('💡 Professional Background Suggestions:');
        for (const [category, suggestionList] of Object.entries(backgroundSuggestions)) {
            console.log(`${category}: ${suggestionList}`);
        }
        return backgroundSuggestions;
    }

    /**
     * Get headshot prompt examples
     * @returns {Object} Object containing prompt examples
     */
    getHeadshotPromptExamples() {
        const promptExamples = {
            business_formal: [
                'Create professional headshot with dark business suit and white dress shirt',
                'Generate corporate headshot with formal business attire',
                'Professional headshot with business suit and professional background',
                'Executive headshot with formal business wear and office setting',
                'Corporate headshot with professional suit and business environment'
            ],
            business_casual: [
                'Create professional headshot with blazer and dress shirt',
                'Generate business casual headshot with professional attire',
                'Professional headshot with smart casual outfit and office background',
                'Business headshot with professional blouse and corporate setting',
                'Professional headshot with business casual wear and modern office'
            ],
            corporate: [
                'Create corporate headshot with professional dress and blazer',
                'Generate executive headshot with corporate attire',
                'Professional headshot with business dress and office environment',
                'Corporate headshot with professional blouse and corporate background',
                'Executive headshot with corporate wear and business setting'
            ],
            executive: [
                'Create executive headshot with power suit and professional accessories',
                'Generate leadership headshot with executive attire',
                'Professional headshot with executive suit and corporate office',
                'Executive headshot with professional dress and executive background',
                'Leadership headshot with executive wear and business environment'
            ],
            professional: [
                'Create professional headshot with business attire and clean background',
                'Generate professional headshot with corporate wear and office setting',
                'Professional headshot with business casual outfit and professional backdrop',
                'Business headshot with professional attire and modern office background',
                'Professional headshot with corporate wear and business environment'
            ]
        };

        console.log('💡 Headshot Prompt Examples:');
        for (const [category, exampleList] of Object.entries(promptExamples)) {
            console.log(`${category}: ${exampleList}`);
        }
        return promptExamples;
    }

    /**
     * Get headshot use cases and examples
     * @returns {Object} Object containing use case examples
     */
    getHeadshotUseCases() {
        const useCases = {
            business_profiles: [
                'LinkedIn professional headshots',
                'Business profile photos',
                'Corporate directory photos',
                'Professional networking photos',
                'Business card headshots'
            ],
            resumes: [
                'Resume profile photos',
                'CV headshot photos',
                'Job application photos',
                'Professional resume images',
                'Career profile photos'
            ],
            corporate: [
                'Corporate website photos',
                'Company directory headshots',
                'Executive team photos',
                'Corporate communications',
                'Business presentation photos'
            ],
            professional_networking: [
                'Professional networking profiles',
                'Business conference photos',
                'Professional association photos',
                'Industry networking photos',
                'Professional community photos'
            ],
            marketing: [
                'Professional marketing materials',
                'Business promotional photos',
                'Corporate marketing campaigns',
                'Professional advertising photos',
                'Business marketing content'
            ]
        };

        console.log('💡 Headshot Use Cases:');
        for (const [category, useCaseList] of Object.entries(useCases)) {
            console.log(`${category}: ${useCaseList}`);
        }
        return useCases;
    }

    /**
     * Get professional style suggestions
     * @returns {Object} Object containing style suggestions
     */
    getProfessionalStyleSuggestions() {
        const styleSuggestions = {
            conservative: [
                'Traditional business attire',
                'Classic professional wear',
                'Conservative corporate dress',
                'Traditional business suit',
                'Classic professional appearance'
            ],
            modern: [
                'Contemporary business attire',
                'Modern professional wear',
                'Current business fashion',
                'Modern corporate dress',
                'Contemporary professional style'
            ],
            executive: [
                'Executive business attire',
                'Leadership professional wear',
                'Senior management dress',
                'Executive corporate attire',
                'Leadership professional style'
            ],
            creative_professional: [
                'Creative professional attire',
                'Modern creative business wear',
                'Contemporary professional dress',
                'Creative corporate attire',
                'Modern professional creative style'
            ],
            tech_professional: [
                'Tech industry professional attire',
                'Modern tech business wear',
                'Contemporary tech professional dress',
                'Tech corporate attire',
                'Modern tech professional style'
            ]
        };

        console.log('💡 Professional Style Suggestions:');
        for (const [style, suggestionList] of Object.entries(styleSuggestions)) {
            console.log(`${style}: ${suggestionList}`);
        }
        return styleSuggestions;
    }

    /**
     * Get headshot best practices
     * @returns {Object} Object containing best practices
     */
    getHeadshotBestPractices() {
        const bestPractices = {
            image_preparation: [
                'Start with high-quality source images',
                'Ensure good lighting and contrast in the original image',
                'Avoid heavily compressed or low-quality images',
                'Use images with clear, well-defined facial features',
                'Ensure the person\'s face is clearly visible and well-lit'
            ],
            prompt_writing: [
                'Be specific about the professional look you want to achieve',
                'Include details about outfit style, background, and setting',
                'Mention specific professional attire or business wear',
                'Describe the desired professional appearance clearly',
                'Keep prompts descriptive but concise'
            ],
            professional_setting: [
                'Specify professional background settings',
                'Mention corporate or business environments',
                'Include details about lighting and atmosphere',
                'Describe professional color schemes',
                'Specify formal or business-casual settings'
            ],
            workflow_optimization: [
                'Batch process multiple headshot variations when possible',
                'Implement proper error handling and retry logic',
                'Monitor processing times and adjust expectations',
                'Store results efficiently to avoid reprocessing',
                'Implement progress tracking for better user experience'
            ]
        };

        console.log('💡 Headshot Best Practices:');
        for (const [category, practiceList] of Object.entries(bestPractices)) {
            console.log(`${category}: ${practiceList}`);
        }
        return bestPractices;
    }

    /**
     * Get headshot performance tips
     * @returns {Object} Object containing performance tips
     */
    getHeadshotPerformanceTips() {
        const performanceTips = {
            optimization: [
                'Use appropriate image formats (JPEG for photos, PNG for graphics)',
                'Optimize source images before processing',
                'Consider image dimensions and quality trade-offs',
                'Implement caching for frequently processed images',
                'Use batch processing for multiple headshot variations'
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
                'Offer headshot previews when possible',
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
     * Get headshot technical specifications
     * @returns {Object} Object containing technical specifications
     */
    getHeadshotTechnicalSpecifications() {
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
                text_prompts: 'Required for professional outfit description',
                face_detection: 'Automatic face detection and enhancement',
                professional_transformation: 'Transforms casual photos into professional headshots',
                background_enhancement: 'Enhances or changes background to professional setting',
                output_quality: 'High-quality JPEG output'
            }
        };

        console.log('💡 Headshot Technical Specifications:');
        for (const [category, specs] of Object.entries(specifications)) {
            console.log(`${category}: ${JSON.stringify(specs, null, 2)}`);
        }
        return specifications;
    }

    /**
     * Get headshot workflow examples
     * @returns {Object} Object containing workflow examples
     */
    getHeadshotWorkflowExamples() {
        const workflowExamples = {
            basic_workflow: [
                '1. Prepare high-quality input image with clear face visibility',
                '2. Write descriptive professional outfit prompt',
                '3. Upload image to LightX servers',
                '4. Submit headshot generation request',
                '5. Monitor order status until completion',
                '6. Download professional headshot result'
            ],
            advanced_workflow: [
                '1. Prepare input image with clear facial features',
                '2. Create detailed professional prompt with specific attire and background',
                '3. Upload image to LightX servers',
                '4. Submit headshot request with validation',
                '5. Monitor processing with retry logic',
                '6. Validate and download result',
                '7. Apply additional processing if needed'
            ],
            batch_workflow: [
                '1. Prepare multiple input images',
                '2. Create consistent professional prompts for batch',
                '3. Upload all images in parallel',
                '4. Submit multiple headshot requests',
                '5. Monitor all orders concurrently',
                '6. Collect and organize results',
                '7. Apply quality control and validation'
            ]
        };

        console.log('💡 Headshot Workflow Examples:');
        for (const [workflow, stepList] of Object.entries(workflowExamples)) {
            console.log(`${workflow}:`);
            stepList.forEach(step => console.log(`  ${step}`));
        }
        return workflowExamples;
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
     * Generate headshot with prompt validation
     * @param {Buffer|string} imageData - Image data or file path
     * @param {string} textPrompt - Text prompt for professional outfit description
     * @param {string} contentType - MIME type
     * @returns {Promise<Object>} Final result with output URL
     */
    async generateHeadshotWithValidation(imageData, textPrompt, contentType = 'image/jpeg') {
        if (!this.validateTextPrompt(textPrompt)) {
            throw new Error('Invalid text prompt');
        }

        return this.processHeadshotGeneration(imageData, textPrompt, contentType);
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
        const lightx = new LightXAIHeadshotAPI('YOUR_API_KEY_HERE');

        // Get tips for better results
        lightx.getHeadshotTips();
        lightx.getProfessionalOutfitSuggestions();
        lightx.getProfessionalBackgroundSuggestions();
        lightx.getHeadshotPromptExamples();
        lightx.getHeadshotUseCases();
        lightx.getProfessionalStyleSuggestions();
        lightx.getHeadshotBestPractices();
        lightx.getHeadshotPerformanceTips();
        lightx.getHeadshotTechnicalSpecifications();
        lightx.getHeadshotWorkflowExamples();

        // Load image (replace with your image loading logic)
        const imagePath = 'path/to/input-image.jpg';

        // Example 1: Business formal headshot
        const result1 = await lightx.generateHeadshotWithValidation(
            imagePath,
            'Create professional headshot with dark business suit and white dress shirt',
            'image/jpeg'
        );
        console.log('🎉 Business formal headshot result:');
        console.log(`Order ID: ${result1.orderId}`);
        console.log(`Status: ${result1.status}`);
        if (result1.output) {
            console.log(`Output: ${result1.output}`);
        }

        // Example 2: Business casual headshot
        const result2 = await lightx.generateHeadshotWithValidation(
            imagePath,
            'Generate business casual headshot with professional attire',
            'image/jpeg'
        );
        console.log('🎉 Business casual headshot result:');
        console.log(`Order ID: ${result2.orderId}`);
        console.log(`Status: ${result2.status}`);
        if (result2.output) {
            console.log(`Output: ${result2.output}`);
        }

        // Example 3: Try different professional styles
        const professionalStyles = [
            'Create corporate headshot with professional dress and blazer',
            'Generate executive headshot with corporate attire',
            'Professional headshot with business dress and office environment',
            'Create executive headshot with power suit and professional accessories',
            'Generate leadership headshot with executive attire'
        ];

        for (const style of professionalStyles) {
            const result = await lightx.generateHeadshotWithValidation(
                imagePath,
                style,
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
module.exports = LightXAIHeadshotAPI;

// Run example if this file is executed directly
if (require.main === module) {
    runExample();
}
