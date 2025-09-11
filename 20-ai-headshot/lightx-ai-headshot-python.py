"""
LightX AI Headshot Generator API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered professional headshot generation functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXAIHeadshotAPI:
    """
    LightX AI Headshot Generator API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX AI Headshot Generator API client
        
        Args:
            api_key (str): Your LightX API key
        """
        self.api_key = api_key
        self.base_url = "https://api.lightxeditor.com/external/api"
        self.max_retries = 5
        self.retry_interval = 3  # seconds
        self.max_file_size = 5242880  # 5MB
        
    def upload_image(self, image_data: Union[str, bytes, Path], content_type: str = "image/jpeg") -> str:
        """
        Upload image to LightX servers
        
        Args:
            image_data: Image file path, bytes, or Path object
            content_type: MIME type (image/jpeg or image/png)
            
        Returns:
            str: Final image URL
            
        Raises:
            LightXHeadshotException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise LightXHeadshotException(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
            file_size = image_path.stat().st_size
        elif isinstance(image_data, bytes):
            image_bytes = image_data
            file_size = len(image_bytes)
        else:
            raise LightXHeadshotException("Invalid image data provided")
        
        if file_size > self.max_file_size:
            raise LightXHeadshotException("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def generate_headshot(self, image_url: str, text_prompt: str) -> str:
        """
        Generate professional headshot using AI
        
        Args:
            image_url: URL of the input image
            text_prompt: Text prompt for professional outfit description
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXHeadshotException: If request fails
        """
        endpoint = f"{self.base_url}/v2/headshot/"
        
        payload = {
            "imageUrl": image_url,
            "textPrompt": text_prompt
        }
        
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            
            if data["statusCode"] != 2000:
                raise LightXHeadshotException(f"Headshot generation request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"👔 Professional prompt: \"{text_prompt}\"")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHeadshotException(f"Network error: {e}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXHeadshotException: If request fails
        """
        endpoint = f"{self.base_url}/v2/order-status"
        
        payload = {"orderId": order_id}
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            
            if data["statusCode"] != 2000:
                raise LightXHeadshotException(f"Status check failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHeadshotException(f"Network error: {e}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXHeadshotException: If maximum retries reached or processing fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Professional headshot generated successfully!")
                    if status.get("output"):
                        print(f"👔 Professional headshot: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXHeadshotException("Professional headshot generation failed")
                
                elif status["status"] == "init":
                    attempts += 1
                    if attempts < self.max_retries:
                        print(f"⏳ Waiting {self.retry_interval} seconds before next check...")
                        time.sleep(self.retry_interval)
                
                else:
                    attempts += 1
                    if attempts < self.max_retries:
                        time.sleep(self.retry_interval)
                
            except Exception as e:
                attempts += 1
                if attempts >= self.max_retries:
                    raise e
                print(f"⚠️  Error on attempt {attempts}, retrying...")
                time.sleep(self.retry_interval)
        
        raise LightXHeadshotException("Maximum retry attempts reached")
    
    def process_headshot_generation(self, image_data: Union[str, bytes, Path], text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and generate professional headshot
        
        Args:
            image_data: Image file path, bytes, or Path object
            text_prompt: Text prompt for professional outfit description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Headshot Generator API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Generate professional headshot
        print("👔 Generating professional headshot...")
        order_id = self.generate_headshot(image_url, text_prompt)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_headshot_tips(self) -> Dict[str, List[str]]:
        """
        Get headshot generation tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with good face visibility",
                "Ensure the person's face is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best headshot results",
                "Good lighting helps preserve facial features and details"
            ],
            "text_prompts": [
                "Be specific about the professional look you want to achieve",
                "Include details about outfit style, background, and setting",
                "Mention specific professional attire or business wear",
                "Describe the desired professional appearance clearly",
                "Keep prompts descriptive but concise"
            ],
            "professional_setting": [
                "Specify professional background settings",
                "Mention corporate or business environments",
                "Include details about lighting and atmosphere",
                "Describe professional color schemes",
                "Specify formal or business-casual settings"
            ],
            "outfit_selection": [
                "Choose professional attire descriptions",
                "Select business-appropriate clothing",
                "Use formal or business-casual outfit descriptions",
                "Ensure outfit descriptions match professional standards",
                "Choose outfits that complement the person's appearance"
            ],
            "general": [
                "AI headshot generation works best with clear, detailed source images",
                "Results may vary based on input image quality and prompt clarity",
                "Headshots preserve facial features while enhancing professional appearance",
                "Allow 15-30 seconds for processing",
                "Experiment with different professional prompts for varied results"
            ]
        }
        
        print("💡 Headshot Generation Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_professional_outfit_suggestions(self) -> Dict[str, List[str]]:
        """
        Get professional outfit suggestions
        
        Returns:
            Dict: Object containing professional outfit suggestions
        """
        outfit_suggestions = {
            "business_formal": [
                "Dark business suit with white dress shirt",
                "Professional blazer with dress pants",
                "Formal business attire with tie",
                "Corporate suit with dress shoes",
                "Executive business wear"
            ],
            "business_casual": [
                "Blazer with dress shirt and chinos",
                "Professional sweater with dress pants",
                "Business casual blouse with skirt",
                "Smart casual outfit with dress shoes",
                "Professional casual attire"
            ],
            "corporate": [
                "Corporate dress with blazer",
                "Professional blouse with pencil skirt",
                "Business dress with heels",
                "Corporate suit with accessories",
                "Professional corporate wear"
            ],
            "executive": [
                "Executive suit with power tie",
                "Professional dress with statement jewelry",
                "Executive blazer with dress pants",
                "Power suit with professional accessories",
                "Executive business attire"
            ],
            "professional": [
                "Professional blouse with dress pants",
                "Business dress with cardigan",
                "Professional shirt with blazer",
                "Business casual with professional accessories",
                "Professional work attire"
            ]
        }
        
        print("💡 Professional Outfit Suggestions:")
        for category, suggestion_list in outfit_suggestions.items():
            print(f"{category}: {suggestion_list}")
        return outfit_suggestions
    
    def get_professional_background_suggestions(self) -> Dict[str, List[str]]:
        """
        Get professional background suggestions
        
        Returns:
            Dict: Object containing background suggestions
        """
        background_suggestions = {
            "office_settings": [
                "Modern office background",
                "Corporate office environment",
                "Professional office setting",
                "Business office backdrop",
                "Executive office background"
            ],
            "studio_settings": [
                "Professional studio background",
                "Clean studio backdrop",
                "Professional photography studio",
                "Studio lighting setup",
                "Professional portrait studio"
            ],
            "neutral_backgrounds": [
                "Neutral professional background",
                "Clean white background",
                "Professional gray backdrop",
                "Subtle professional background",
                "Minimalist professional setting"
            ],
            "corporate_backgrounds": [
                "Corporate building background",
                "Business environment backdrop",
                "Professional corporate setting",
                "Executive office background",
                "Corporate headquarters setting"
            ],
            "modern_backgrounds": [
                "Modern professional background",
                "Contemporary office setting",
                "Sleek professional backdrop",
                "Modern business environment",
                "Contemporary corporate setting"
            ]
        }
        
        print("💡 Professional Background Suggestions:")
        for category, suggestion_list in background_suggestions.items():
            print(f"{category}: {suggestion_list}")
        return background_suggestions
    
    def get_headshot_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get headshot prompt examples
        
        Returns:
            Dict: Object containing prompt examples
        """
        prompt_examples = {
            "business_formal": [
                "Create professional headshot with dark business suit and white dress shirt",
                "Generate corporate headshot with formal business attire",
                "Professional headshot with business suit and professional background",
                "Executive headshot with formal business wear and office setting",
                "Corporate headshot with professional suit and business environment"
            ],
            "business_casual": [
                "Create professional headshot with blazer and dress shirt",
                "Generate business casual headshot with professional attire",
                "Professional headshot with smart casual outfit and office background",
                "Business headshot with professional blouse and corporate setting",
                "Professional headshot with business casual wear and modern office"
            ],
            "corporate": [
                "Create corporate headshot with professional dress and blazer",
                "Generate executive headshot with corporate attire",
                "Professional headshot with business dress and office environment",
                "Corporate headshot with professional blouse and corporate background",
                "Executive headshot with corporate wear and business setting"
            ],
            "executive": [
                "Create executive headshot with power suit and professional accessories",
                "Generate leadership headshot with executive attire",
                "Professional headshot with executive suit and corporate office",
                "Executive headshot with professional dress and executive background",
                "Leadership headshot with executive wear and business environment"
            ],
            "professional": [
                "Create professional headshot with business attire and clean background",
                "Generate professional headshot with corporate wear and office setting",
                "Professional headshot with business casual outfit and professional backdrop",
                "Business headshot with professional attire and modern office background",
                "Professional headshot with corporate wear and business environment"
            ]
        }
        
        print("💡 Headshot Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def get_headshot_use_cases(self) -> Dict[str, List[str]]:
        """
        Get headshot use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "business_profiles": [
                "LinkedIn professional headshots",
                "Business profile photos",
                "Corporate directory photos",
                "Professional networking photos",
                "Business card headshots"
            ],
            "resumes": [
                "Resume profile photos",
                "CV headshot photos",
                "Job application photos",
                "Professional resume images",
                "Career profile photos"
            ],
            "corporate": [
                "Corporate website photos",
                "Company directory headshots",
                "Executive team photos",
                "Corporate communications",
                "Business presentation photos"
            ],
            "professional_networking": [
                "Professional networking profiles",
                "Business conference photos",
                "Professional association photos",
                "Industry networking photos",
                "Professional community photos"
            ],
            "marketing": [
                "Professional marketing materials",
                "Business promotional photos",
                "Corporate marketing campaigns",
                "Professional advertising photos",
                "Business marketing content"
            ]
        }
        
        print("💡 Headshot Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
    def get_professional_style_suggestions(self) -> Dict[str, List[str]]:
        """
        Get professional style suggestions
        
        Returns:
            Dict: Object containing style suggestions
        """
        style_suggestions = {
            "conservative": [
                "Traditional business attire",
                "Classic professional wear",
                "Conservative corporate dress",
                "Traditional business suit",
                "Classic professional appearance"
            ],
            "modern": [
                "Contemporary business attire",
                "Modern professional wear",
                "Current business fashion",
                "Modern corporate dress",
                "Contemporary professional style"
            ],
            "executive": [
                "Executive business attire",
                "Leadership professional wear",
                "Senior management dress",
                "Executive corporate attire",
                "Leadership professional style"
            ],
            "creative_professional": [
                "Creative professional attire",
                "Modern creative business wear",
                "Contemporary professional dress",
                "Creative corporate attire",
                "Modern professional creative style"
            ],
            "tech_professional": [
                "Tech industry professional attire",
                "Modern tech business wear",
                "Contemporary tech professional dress",
                "Tech corporate attire",
                "Modern tech professional style"
            ]
        }
        
        print("💡 Professional Style Suggestions:")
        for style, suggestion_list in style_suggestions.items():
            print(f"{style}: {suggestion_list}")
        return style_suggestions
    
    def get_headshot_best_practices(self) -> Dict[str, List[str]]:
        """
        Get headshot best practices
        
        Returns:
            Dict: Object containing best practices
        """
        best_practices = {
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in the original image",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined facial features",
                "Ensure the person's face is clearly visible and well-lit"
            ],
            "prompt_writing": [
                "Be specific about the professional look you want to achieve",
                "Include details about outfit style, background, and setting",
                "Mention specific professional attire or business wear",
                "Describe the desired professional appearance clearly",
                "Keep prompts descriptive but concise"
            ],
            "professional_setting": [
                "Specify professional background settings",
                "Mention corporate or business environments",
                "Include details about lighting and atmosphere",
                "Describe professional color schemes",
                "Specify formal or business-casual settings"
            ],
            "workflow_optimization": [
                "Batch process multiple headshot variations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        }
        
        print("💡 Headshot Best Practices:")
        for category, practice_list in best_practices.items():
            print(f"{category}: {practice_list}")
        return best_practices
    
    def get_headshot_performance_tips(self) -> Dict[str, List[str]]:
        """
        Get headshot performance tips
        
        Returns:
            Dict: Object containing performance tips
        """
        performance_tips = {
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple headshot variations"
            ],
            "resource_management": [
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after processing",
                "Optimize network requests and retry logic"
            ],
            "user_experience": [
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer headshot previews when possible",
                "Provide tips for better input images"
            ]
        }
        
        print("💡 Performance Tips:")
        for category, tip_list in performance_tips.items():
            print(f"{category}: {tip_list}")
        return performance_tips
    
    def get_headshot_technical_specifications(self) -> Dict[str, Dict[str, any]]:
        """
        Get headshot technical specifications
        
        Returns:
            Dict: Object containing technical specifications
        """
        specifications = {
            "supported_formats": {
                "input": ["JPEG", "PNG"],
                "output": ["JPEG"],
                "color_spaces": ["RGB", "sRGB"]
            },
            "size_limits": {
                "max_file_size": "5MB",
                "max_dimension": "No specific limit",
                "min_dimension": "1px"
            },
            "processing": {
                "max_retries": 5,
                "retry_interval": "3 seconds",
                "avg_processing_time": "15-30 seconds",
                "timeout": "No timeout limit"
            },
            "features": {
                "text_prompts": "Required for professional outfit description",
                "face_detection": "Automatic face detection and enhancement",
                "professional_transformation": "Transforms casual photos into professional headshots",
                "background_enhancement": "Enhances or changes background to professional setting",
                "output_quality": "High-quality JPEG output"
            }
        }
        
        print("💡 Headshot Technical Specifications:")
        for category, specs in specifications.items():
            print(f"{category}: {specs}")
        return specifications
    
    def get_headshot_workflow_examples(self) -> Dict[str, List[str]]:
        """
        Get headshot workflow examples
        
        Returns:
            Dict: Object containing workflow examples
        """
        workflow_examples = {
            "basic_workflow": [
                "1. Prepare high-quality input image with clear face visibility",
                "2. Write descriptive professional outfit prompt",
                "3. Upload image to LightX servers",
                "4. Submit headshot generation request",
                "5. Monitor order status until completion",
                "6. Download professional headshot result"
            ],
            "advanced_workflow": [
                "1. Prepare input image with clear facial features",
                "2. Create detailed professional prompt with specific attire and background",
                "3. Upload image to LightX servers",
                "4. Submit headshot request with validation",
                "5. Monitor processing with retry logic",
                "6. Validate and download result",
                "7. Apply additional processing if needed"
            ],
            "batch_workflow": [
                "1. Prepare multiple input images",
                "2. Create consistent professional prompts for batch",
                "3. Upload all images in parallel",
                "4. Submit multiple headshot requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            ]
        }
        
        print("💡 Headshot Workflow Examples:")
        for workflow, step_list in workflow_examples.items():
            print(f"{workflow}:")
            for step in step_list:
                print(f"  {step}")
        return workflow_examples
    
    def validate_text_prompt(self, text_prompt: str) -> bool:
        """
        Validate text prompt
        
        Args:
            text_prompt: Text prompt to validate
            
        Returns:
            bool: Whether the prompt is valid
        """
        if not text_prompt or text_prompt.strip() == "":
            print("❌ Text prompt cannot be empty")
            return False
        
        if len(text_prompt) > 500:
            print("❌ Text prompt is too long (max 500 characters)")
            return False
        
        print("✅ Text prompt is valid")
        return True
    
    def get_image_dimensions(self, image_data: Union[str, bytes, Path]) -> Tuple[int, int]:
        """
        Get image dimensions
        
        Args:
            image_data: Image file path, bytes, or Path object
            
        Returns:
            Tuple[int, int]: Image dimensions (width, height)
        """
        try:
            if isinstance(image_data, (str, Path)):
                image_path = Path(image_data)
                with Image.open(image_path) as img:
                    width, height = img.size
            elif isinstance(image_data, bytes):
                with Image.open(io.BytesIO(image_data)) as img:
                    width, height = img.size
            else:
                raise ValueError("Invalid image data provided")
            
            print(f"📏 Image dimensions: {width}x{height}")
            return width, height
            
        except Exception as e:
            print(f"❌ Error getting image dimensions: {e}")
            return 0, 0
    
    def generate_headshot_with_validation(self, image_data: Union[str, bytes, Path], text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate headshot with prompt validation
        
        Args:
            image_data: Image file path, bytes, or Path object
            text_prompt: Text prompt for professional outfit description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise LightXHeadshotException("Invalid text prompt")
        
        return self.process_headshot_generation(image_data, text_prompt, content_type)
    
    # Private methods
    
    def _get_upload_url(self, file_size: int, content_type: str) -> Dict:
        """
        Get upload URL from LightX API
        
        Args:
            file_size: Size of the file in bytes
            content_type: MIME type
            
        Returns:
            Dict: Upload URL response
        """
        endpoint = f"{self.base_url}/v2/uploadImageUrl"
        
        payload = {
            "uploadType": "imageUrl",
            "size": file_size,
            "contentType": content_type
        }
        
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            
            if data["statusCode"] != 2000:
                raise LightXHeadshotException(f"Upload URL request failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHeadshotException(f"Network error: {e}")
    
    def _upload_to_s3(self, upload_url: str, image_bytes: bytes, content_type: str) -> None:
        """
        Upload image to S3
        
        Args:
            upload_url: S3 upload URL
            image_bytes: Image data as bytes
            content_type: MIME type
        """
        headers = {"Content-Type": content_type}
        
        try:
            response = requests.put(upload_url, data=image_bytes, headers=headers)
            response.raise_for_status()
            
        except requests.exceptions.RequestException as e:
            raise LightXHeadshotException(f"Upload error: {e}")


class LightXHeadshotException(Exception):
    """Custom exception for LightX Headshot API errors"""
    pass


def run_example():
    """Example usage of the LightX AI Headshot Generator API"""
    try:
        # Initialize with your API key
        lightx = LightXAIHeadshotAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_headshot_tips()
        lightx.get_professional_outfit_suggestions()
        lightx.get_professional_background_suggestions()
        lightx.get_headshot_prompt_examples()
        lightx.get_headshot_use_cases()
        lightx.get_professional_style_suggestions()
        lightx.get_headshot_best_practices()
        lightx.get_headshot_performance_tips()
        lightx.get_headshot_technical_specifications()
        lightx.get_headshot_workflow_examples()
        
        # Load image (replace with your image path)
        image_path = "path/to/input-image.jpg"
        
        # Example 1: Business formal headshot
        result1 = lightx.generate_headshot_with_validation(
            image_path,
            "Create professional headshot with dark business suit and white dress shirt",
            "image/jpeg"
        )
        print("🎉 Business formal headshot result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Business casual headshot
        result2 = lightx.generate_headshot_with_validation(
            image_path,
            "Generate business casual headshot with professional attire",
            "image/jpeg"
        )
        print("🎉 Business casual headshot result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Try different professional styles
        professional_styles = [
            "Create corporate headshot with professional dress and blazer",
            "Generate executive headshot with corporate attire",
            "Professional headshot with business dress and office environment",
            "Create executive headshot with power suit and professional accessories",
            "Generate leadership headshot with executive attire"
        ]
        
        for style in professional_styles:
            result = lightx.generate_headshot_with_validation(
                image_path,
                style,
                "image/jpeg"
            )
            print(f"🎉 {style} result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 4: Get image dimensions
        dimensions = lightx.get_image_dimensions(image_path)
        if dimensions[0] > 0 and dimensions[1] > 0:
            print(f"📏 Original image: {dimensions[0]}x{dimensions[1]}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
