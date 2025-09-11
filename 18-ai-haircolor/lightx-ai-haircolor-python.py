"""
LightX AI Hair Color API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered hair color changing functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXAIHairColorAPI:
    """
    LightX AI Hair Color API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX AI Hair Color API client
        
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
            LightXHairColorException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise LightXHairColorException(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
            file_size = image_path.stat().st_size
        elif isinstance(image_data, bytes):
            image_bytes = image_data
            file_size = len(image_bytes)
        else:
            raise LightXHairColorException("Invalid image data provided")
        
        if file_size > self.max_file_size:
            raise LightXHairColorException("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def change_hair_color(self, image_url: str, text_prompt: str) -> str:
        """
        Change hair color using AI
        
        Args:
            image_url: URL of the input image
            text_prompt: Text prompt for hair color description
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXHairColorException: If request fails
        """
        endpoint = f"{self.base_url}/v2/haircolor/"
        
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
                raise LightXHairColorException(f"Hair color change request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🎨 Hair color prompt: \"{text_prompt}\"")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHairColorException(f"Network error: {e}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXHairColorException: If request fails
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
                raise LightXHairColorException(f"Status check failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHairColorException(f"Network error: {e}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXHairColorException: If maximum retries reached or processing fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Hair color changed successfully!")
                    if status.get("output"):
                        print(f"🎨 New hair color image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXHairColorException("Hair color change failed")
                
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
        
        raise LightXHairColorException("Maximum retry attempts reached")
    
    def process_hair_color_change(self, image_data: Union[str, bytes, Path], text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and change hair color
        
        Args:
            image_data: Image file path, bytes, or Path object
            text_prompt: Text prompt for hair color description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Hair Color API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Change hair color
        print("🎨 Changing hair color...")
        order_id = self.change_hair_color(image_url, text_prompt)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_hair_color_tips(self) -> Dict[str, List[str]]:
        """
        Get hair color tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with visible hair",
                "Ensure the person's hair is clearly visible in the image",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best hair color results",
                "Good lighting helps preserve hair texture and details"
            ],
            "text_prompts": [
                "Be specific about the hair color you want to achieve",
                "Include details about shade, tone, and intensity",
                "Mention specific hair color names or descriptions",
                "Describe the desired hair color effect clearly",
                "Keep prompts descriptive but concise"
            ],
            "hair_visibility": [
                "Ensure hair is clearly visible and not obscured",
                "Avoid images where hair is covered by hats or accessories",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "general": [
                "AI hair color works best with clear, detailed source images",
                "Results may vary based on input image quality and hair visibility",
                "Hair color changes preserve original texture and style",
                "Allow 15-30 seconds for processing",
                "Experiment with different color descriptions for varied results"
            ]
        }
        
        print("💡 Hair Color Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_hair_color_suggestions(self) -> Dict[str, List[str]]:
        """
        Get hair color suggestions
        
        Returns:
            Dict: Object containing hair color suggestions
        """
        color_suggestions = {
            "natural_colors": [
                "Natural black hair",
                "Dark brown hair",
                "Medium brown hair",
                "Light brown hair",
                "Natural blonde hair",
                "Strawberry blonde hair",
                "Auburn hair",
                "Red hair",
                "Gray hair",
                "Silver hair"
            ],
            "vibrant_colors": [
                "Bright red hair",
                "Vibrant orange hair",
                "Electric blue hair",
                "Purple hair",
                "Pink hair",
                "Green hair",
                "Yellow hair",
                "Turquoise hair",
                "Magenta hair",
                "Neon colors"
            ],
            "highlights_and_effects": [
                "Blonde highlights",
                "Brown highlights",
                "Red highlights",
                "Ombre hair effect",
                "Balayage hair effect",
                "Gradient hair colors",
                "Two-tone hair",
                "Color streaks",
                "Peekaboo highlights",
                "Money piece highlights"
            ],
            "trendy_colors": [
                "Rose gold hair",
                "Platinum blonde hair",
                "Ash blonde hair",
                "Chocolate brown hair",
                "Chestnut brown hair",
                "Copper hair",
                "Burgundy hair",
                "Mahogany hair",
                "Honey blonde hair",
                "Caramel highlights"
            ],
            "fantasy_colors": [
                "Unicorn hair colors",
                "Mermaid hair colors",
                "Galaxy hair colors",
                "Rainbow hair colors",
                "Pastel hair colors",
                "Metallic hair colors",
                "Holographic hair colors",
                "Chrome hair colors",
                "Iridescent hair colors",
                "Duochrome hair colors"
            ]
        }
        
        print("💡 Hair Color Suggestions:")
        for category, suggestion_list in color_suggestions.items():
            print(f"{category}: {suggestion_list}")
        return color_suggestions
    
    def get_hair_color_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get hair color prompt examples
        
        Returns:
            Dict: Object containing prompt examples
        """
        prompt_examples = {
            "natural_colors": [
                "Change hair to natural black color",
                "Transform hair to dark brown shade",
                "Apply medium brown hair color",
                "Change to light brown hair",
                "Transform to natural blonde hair",
                "Apply strawberry blonde hair color",
                "Change to auburn hair color",
                "Transform to natural red hair",
                "Apply gray hair color",
                "Change to silver hair color"
            ],
            "vibrant_colors": [
                "Change hair to bright red color",
                "Transform to vibrant orange hair",
                "Apply electric blue hair color",
                "Change to purple hair",
                "Transform to pink hair color",
                "Apply green hair color",
                "Change to yellow hair",
                "Transform to turquoise hair",
                "Apply magenta hair color",
                "Change to neon colors"
            ],
            "highlights_and_effects": [
                "Add blonde highlights to hair",
                "Apply brown highlights",
                "Add red highlights to hair",
                "Create ombre hair effect",
                "Apply balayage hair effect",
                "Create gradient hair colors",
                "Apply two-tone hair colors",
                "Add color streaks to hair",
                "Create peekaboo highlights",
                "Apply money piece highlights"
            ],
            "trendy_colors": [
                "Change hair to rose gold color",
                "Transform to platinum blonde hair",
                "Apply ash blonde hair color",
                "Change to chocolate brown hair",
                "Transform to chestnut brown hair",
                "Apply copper hair color",
                "Change to burgundy hair",
                "Transform to mahogany hair",
                "Apply honey blonde hair color",
                "Create caramel highlights"
            ],
            "fantasy_colors": [
                "Create unicorn hair colors",
                "Apply mermaid hair colors",
                "Create galaxy hair colors",
                "Apply rainbow hair colors",
                "Create pastel hair colors",
                "Apply metallic hair colors",
                "Create holographic hair colors",
                "Apply chrome hair colors",
                "Create iridescent hair colors",
                "Apply duochrome hair colors"
            ]
        }
        
        print("💡 Hair Color Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def get_hair_color_use_cases(self) -> Dict[str, List[str]]:
        """
        Get hair color use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "virtual_try_on": [
                "Virtual hair color try-on for salons",
                "Hair color consultation tools",
                "Before and after hair color previews",
                "Hair color selection assistance",
                "Virtual hair color makeovers"
            ],
            "beauty_platforms": [
                "Beauty app hair color features",
                "Hair color recommendation systems",
                "Virtual hair color consultations",
                "Hair color trend exploration",
                "Beauty influencer content creation"
            ],
            "personal_styling": [
                "Personal hair color experimentation",
                "Hair color change visualization",
                "Style inspiration and exploration",
                "Hair color trend testing",
                "Personal beauty transformations"
            ],
            "marketing": [
                "Hair color product marketing",
                "Salon service promotion",
                "Hair color brand campaigns",
                "Beauty product demonstrations",
                "Hair color trend showcases"
            ],
            "entertainment": [
                "Character hair color changes",
                "Costume and makeup design",
                "Creative hair color concepts",
                "Artistic hair color expressions",
                "Fantasy hair color creations"
            ]
        }
        
        print("💡 Hair Color Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
    def get_hair_color_intensity_suggestions(self) -> Dict[str, List[str]]:
        """
        Get hair color intensity suggestions
        
        Returns:
            Dict: Object containing intensity suggestions
        """
        intensity_suggestions = {
            "subtle": [
                "Apply subtle hair color changes",
                "Add gentle color highlights",
                "Create natural-looking color variations",
                "Apply soft color transitions",
                "Create minimal color effects"
            ],
            "moderate": [
                "Apply noticeable hair color changes",
                "Create distinct color variations",
                "Add visible color highlights",
                "Apply balanced color effects",
                "Create moderate color transformations"
            ],
            "dramatic": [
                "Apply bold hair color changes",
                "Create dramatic color transformations",
                "Add vibrant color effects",
                "Apply intense color variations",
                "Create striking color changes"
            ]
        }
        
        print("💡 Hair Color Intensity Suggestions:")
        for intensity, suggestion_list in intensity_suggestions.items():
            print(f"{intensity}: {suggestion_list}")
        return intensity_suggestions
    
    def get_hair_color_categories(self) -> Dict[str, Dict[str, Any]]:
        """
        Get hair color category recommendations
        
        Returns:
            Dict: Object containing category recommendations
        """
        categories = {
            "natural": {
                "description": "Natural hair colors that look realistic",
                "examples": ["Black", "Brown", "Blonde", "Red", "Gray"],
                "best_for": ["Professional looks", "Natural appearances", "Everyday styling"]
            },
            "vibrant": {
                "description": "Bright and bold hair colors",
                "examples": ["Electric blue", "Purple", "Pink", "Green", "Orange"],
                "best_for": ["Creative expression", "Bold statements", "Artistic looks"]
            },
            "highlights": {
                "description": "Highlight and lowlight effects",
                "examples": ["Blonde highlights", "Ombre", "Balayage", "Streaks", "Peekaboo"],
                "best_for": ["Subtle changes", "Dimension", "Style enhancement"]
            },
            "trendy": {
                "description": "Current popular hair color trends",
                "examples": ["Rose gold", "Platinum", "Ash blonde", "Copper", "Burgundy"],
                "best_for": ["Fashion-forward looks", "Trend following", "Modern styling"]
            },
            "fantasy": {
                "description": "Creative and fantasy hair colors",
                "examples": ["Unicorn", "Mermaid", "Galaxy", "Rainbow", "Pastel"],
                "best_for": ["Creative projects", "Fantasy themes", "Artistic expression"]
            }
        }
        
        print("💡 Hair Color Categories:")
        for category, info in categories.items():
            print(f"{category}: {info['description']}")
            print(f"  Examples: {', '.join(info['examples'])}")
            print(f"  Best for: {', '.join(info['best_for'])}")
        return categories
    
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
    
    def generate_hair_color_change_with_validation(self, image_data: Union[str, bytes, Path], text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate hair color change with prompt validation
        
        Args:
            image_data: Image file path, bytes, or Path object
            text_prompt: Text prompt for hair color description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise LightXHairColorException("Invalid text prompt")
        
        return self.process_hair_color_change(image_data, text_prompt, content_type)
    
    def get_hair_color_best_practices(self) -> Dict[str, List[str]]:
        """
        Get hair color best practices
        
        Returns:
            Dict: Object containing best practices
        """
        best_practices = {
            "prompt_writing": [
                "Be specific about the desired hair color",
                "Include details about shade, tone, and intensity",
                "Mention specific hair color names or descriptions",
                "Describe the desired hair color effect clearly",
                "Keep prompts descriptive but concise"
            ],
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure hair is clearly visible and well-lit",
                "Avoid heavily compressed or low-quality images",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible"
            ],
            "hair_visibility": [
                "Ensure hair is not covered by hats or accessories",
                "Use images with good hair definition",
                "Avoid images with extreme angles or poor lighting",
                "Ensure hair texture is visible",
                "Use images where hair is the main focus"
            ],
            "workflow_optimization": [
                "Batch process multiple images when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        }
        
        print("💡 Hair Color Best Practices:")
        for category, practice_list in best_practices.items():
            print(f"{category}: {practice_list}")
        return best_practices
    
    def get_hair_color_performance_tips(self) -> Dict[str, List[str]]:
        """
        Get hair color performance tips
        
        Returns:
            Dict: Object containing performance tips
        """
        performance_tips = {
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple images"
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
                "Offer hair color previews when possible",
                "Provide tips for better input images"
            ]
        }
        
        print("💡 Performance Tips:")
        for category, tip_list in performance_tips.items():
            print(f"{category}: {tip_list}")
        return performance_tips
    
    def get_hair_color_technical_specifications(self) -> Dict[str, Dict[str, Any]]:
        """
        Get hair color technical specifications
        
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
                "text_prompts": "Required for hair color description",
                "hair_detection": "Automatic hair detection and segmentation",
                "color_preservation": "Preserves hair texture and style",
                "facial_features": "Keeps facial features untouched",
                "output_quality": "High-quality JPEG output"
            }
        }
        
        print("💡 Hair Color Technical Specifications:")
        for category, specs in specifications.items():
            print(f"{category}: {specs}")
        return specifications
    
    def get_hair_color_workflow_examples(self) -> Dict[str, List[str]]:
        """
        Get hair color workflow examples
        
        Returns:
            Dict: Object containing workflow examples
        """
        workflow_examples = {
            "basic_workflow": [
                "1. Prepare high-quality input image with visible hair",
                "2. Write descriptive hair color prompt",
                "3. Upload image to LightX servers",
                "4. Submit hair color change request",
                "5. Monitor order status until completion",
                "6. Download hair color result"
            ],
            "advanced_workflow": [
                "1. Prepare input image with clear hair visibility",
                "2. Create detailed hair color prompt with specific details",
                "3. Upload image to LightX servers",
                "4. Submit hair color request with validation",
                "5. Monitor processing with retry logic",
                "6. Validate and download result",
                "7. Apply additional processing if needed"
            ],
            "batch_workflow": [
                "1. Prepare multiple input images",
                "2. Create consistent hair color prompts for batch",
                "3. Upload all images in parallel",
                "4. Submit multiple hair color requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            ]
        }
        
        print("💡 Hair Color Workflow Examples:")
        for workflow, step_list in workflow_examples.items():
            print(f"{workflow}:")
            for step in step_list:
                print(f"  {step}")
        return workflow_examples
    
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
                raise LightXHairColorException(f"Upload URL request failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHairColorException(f"Network error: {e}")
    
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
            raise LightXHairColorException(f"Upload error: {e}")


class LightXHairColorException(Exception):
    """Custom exception for LightX Hair Color API errors"""
    pass


def run_example():
    """Example usage of the LightX AI Hair Color API"""
    try:
        # Initialize with your API key
        lightx = LightXAIHairColorAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_hair_color_tips()
        lightx.get_hair_color_suggestions()
        lightx.get_hair_color_prompt_examples()
        lightx.get_hair_color_use_cases()
        lightx.get_hair_color_intensity_suggestions()
        lightx.get_hair_color_categories()
        lightx.get_hair_color_best_practices()
        lightx.get_hair_color_performance_tips()
        lightx.get_hair_color_technical_specifications()
        lightx.get_hair_color_workflow_examples()
        
        # Load image (replace with your image path)
        image_path = "path/to/input-image.jpg"
        
        # Example 1: Natural hair colors
        natural_colors = [
            "Change hair to natural black color",
            "Transform hair to dark brown shade",
            "Apply medium brown hair color",
            "Change to light brown hair",
            "Transform to natural blonde hair"
        ]
        
        for color in natural_colors:
            result = lightx.generate_hair_color_change_with_validation(
                image_path,
                color,
                "image/jpeg"
            )
            print(f"🎉 {color} result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 2: Vibrant hair colors
        vibrant_colors = [
            "Change hair to bright red color",
            "Transform to electric blue hair",
            "Apply purple hair color",
            "Change to pink hair",
            "Transform to green hair color"
        ]
        
        for color in vibrant_colors:
            result = lightx.generate_hair_color_change_with_validation(
                image_path,
                color,
                "image/jpeg"
            )
            print(f"🎉 {color} result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 3: Highlights and effects
        highlights = [
            "Add blonde highlights to hair",
            "Create ombre hair effect",
            "Apply balayage hair effect",
            "Add color streaks to hair",
            "Create peekaboo highlights"
        ]
        
        for highlight in highlights:
            result = lightx.generate_hair_color_change_with_validation(
                image_path,
                highlight,
                "image/jpeg"
            )
            print(f"🎉 {highlight} result:")
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
