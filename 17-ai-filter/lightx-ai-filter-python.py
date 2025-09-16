"""
LightX AI Filter API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered image filtering functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXAIFilterAPI:
    """
    LightX AI Filter API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX AI Filter API client
        
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
            LightXFilterException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise LightXFilterException(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
            file_size = image_path.stat().st_size
        elif isinstance(image_data, bytes):
            image_bytes = image_data
            file_size = len(image_bytes)
        else:
            raise LightXFilterException("Invalid image data provided")
        
        if file_size > self.max_file_size:
            raise LightXFilterException("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def generate_filter(self, image_url: str, text_prompt: str, filter_reference_url: Optional[str] = None) -> str:
        """
        Generate AI filter
        
        Args:
            image_url: URL of the input image
            text_prompt: Text prompt for filter description
            filter_reference_url: Optional filter reference image URL
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXFilterException: If request fails
        """
        endpoint = f"{self.base_url}/v2/aifilter"
        
        payload = {
            "imageUrl": image_url,
            "textPrompt": text_prompt
        }
        
        # Add filter reference URL if provided
        if filter_reference_url:
            payload["filterReferenceUrl"] = filter_reference_url
        
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            
            if data["statusCode"] != 2000:
                raise LightXFilterException(f"Filter request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🎨 Filter prompt: \"{text_prompt}\"")
            if style_image_url:
                print(f"🎭 Style image: {style_image_url}")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXFilterException(f"Network error: {e}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXFilterException: If request fails
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
                raise LightXFilterException(f"Status check failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXFilterException(f"Network error: {e}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXFilterException: If maximum retries reached or processing fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ AI filter applied successfully!")
                    if status.get("output"):
                        print(f"🎨 Filtered image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXFilterException("AI filter application failed")
                
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
        
        raise LightXFilterException("Maximum retry attempts reached")
    
    def process_filter(self, image_data: Union[str, bytes, Path], text_prompt: str, style_image_data: Optional[Union[str, bytes, Path]] = None, content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and apply AI filter
        
        Args:
            image_data: Image file path, bytes, or Path object
            text_prompt: Text prompt for filter description
            style_image_data: Optional style image data
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Filter API workflow...")
        
        # Step 1: Upload main image
        print("📤 Uploading main image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Main image uploaded: {image_url}")
        
        # Step 2: Upload style image if provided
        style_image_url = None
        if style_image_data:
            print("📤 Uploading style image...")
            style_image_url = self.upload_image(style_image_data, content_type)
            print(f"✅ Style image uploaded: {style_image_url}")
        
        # Step 3: Generate filter
        print("🎨 Applying AI filter...")
        order_id = self.generate_filter(image_url, text_prompt, style_image_url)
        
        # Step 4: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_filter_tips(self) -> Dict[str, List[str]]:
        """
        Get AI filter tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit images with good contrast",
                "Ensure the image has good composition and framing",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best filter results",
                "Good source image quality improves filter application"
            ],
            "text_prompts": [
                "Be specific about the filter style you want to apply",
                "Describe the mood, atmosphere, or artistic style desired",
                "Include details about colors, lighting, and effects",
                "Mention specific artistic movements or styles",
                "Keep prompts descriptive but concise"
            ],
            "style_images": [
                "Use style images that match your desired aesthetic",
                "Ensure style images have good quality and clarity",
                "Choose style images with strong visual characteristics",
                "Style images work best when they complement the main image",
                "Experiment with different style images for varied results"
            ],
            "general": [
                "AI filters work best with clear, detailed source images",
                "Results may vary based on input image quality and content",
                "Filters can dramatically transform image appearance and mood",
                "Allow 15-30 seconds for processing",
                "Experiment with different prompts and style combinations"
            ]
        }
        
        print("💡 AI Filter Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_filter_style_suggestions(self) -> Dict[str, List[str]]:
        """
        Get filter style suggestions
        
        Returns:
            Dict: Object containing style suggestions
        """
        style_suggestions = {
            "artistic": [
                "Oil painting style with rich textures",
                "Watercolor painting with soft edges",
                "Digital art with vibrant colors",
                "Sketch drawing with pencil strokes",
                "Abstract art with geometric shapes"
            ],
            "photography": [
                "Vintage film photography with grain",
                "Black and white with high contrast",
                "HDR photography with enhanced details",
                "Portrait photography with soft lighting",
                "Street photography with documentary style"
            ],
            "cinematic": [
                "Film noir with dramatic shadows",
                "Sci-fi with neon colors and effects",
                "Horror with dark, moody atmosphere",
                "Romance with warm, soft lighting",
                "Action with dynamic, high-contrast look"
            ],
            "vintage": [
                "Retro 80s with neon and synthwave",
                "Vintage 70s with warm, earthy tones",
                "Classic Hollywood glamour",
                "Victorian era with sepia tones",
                "Art Deco with geometric patterns"
            ],
            "modern": [
                "Minimalist with clean lines",
                "Contemporary with bold colors",
                "Urban with gritty textures",
                "Futuristic with metallic surfaces",
                "Instagram aesthetic with bright colors"
            ]
        }
        
        print("💡 Filter Style Suggestions:")
        for category, suggestion_list in style_suggestions.items():
            print(f"{category}: {suggestion_list}")
        return style_suggestions
    
    def get_filter_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get filter prompt examples
        
        Returns:
            Dict: Object containing prompt examples
        """
        prompt_examples = {
            "artistic": [
                "Transform into oil painting with rich textures and warm colors",
                "Apply watercolor effect with soft, flowing edges",
                "Create digital art style with vibrant, saturated colors",
                "Convert to pencil sketch with detailed line work",
                "Apply abstract art style with geometric patterns"
            ],
            "mood": [
                "Create mysterious atmosphere with dark shadows and blue tones",
                "Apply warm, romantic lighting with golden hour glow",
                "Transform to dramatic, high-contrast black and white",
                "Create dreamy, ethereal effect with soft pastels",
                "Apply energetic, vibrant style with bold colors"
            ],
            "vintage": [
                "Apply retro 80s synthwave style with neon colors",
                "Transform to vintage film photography with grain",
                "Create Victorian era aesthetic with sepia tones",
                "Apply Art Deco style with geometric patterns",
                "Transform to classic Hollywood glamour"
            ],
            "modern": [
                "Apply minimalist style with clean, simple composition",
                "Create contemporary look with bold, modern colors",
                "Transform to urban aesthetic with gritty textures",
                "Apply futuristic style with metallic surfaces",
                "Create Instagram-worthy aesthetic with bright colors"
            ],
            "cinematic": [
                "Apply film noir style with dramatic lighting",
                "Create sci-fi atmosphere with neon effects",
                "Transform to horror aesthetic with dark mood",
                "Apply romance style with soft, warm lighting",
                "Create action movie look with high contrast"
            ]
        }
        
        print("💡 Filter Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def get_filter_use_cases(self) -> Dict[str, List[str]]:
        """
        Get filter use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "social_media": [
                "Create Instagram-worthy aesthetic filters",
                "Apply trendy social media filters",
                "Transform photos for different platforms",
                "Create consistent brand aesthetic",
                "Enhance photos for social sharing"
            ],
            "marketing": [
                "Create branded filter effects",
                "Apply campaign-specific aesthetics",
                "Transform product photos with style",
                "Create cohesive visual identity",
                "Enhance marketing materials"
            ],
            "creative": [
                "Explore artistic styles and effects",
                "Create unique visual interpretations",
                "Experiment with different aesthetics",
                "Transform photos into art pieces",
                "Develop creative visual concepts"
            ],
            "photography": [
                "Apply professional photo filters",
                "Create consistent editing style",
                "Transform photos for different moods",
                "Apply vintage or retro effects",
                "Enhance photo aesthetics"
            ],
            "personal": [
                "Create personalized photo styles",
                "Apply favorite artistic effects",
                "Transform memories with filters",
                "Create unique photo collections",
                "Experiment with visual styles"
            ]
        }
        
        print("💡 Filter Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
    def get_filter_combinations(self) -> Dict[str, List[str]]:
        """
        Get filter combination suggestions
        
        Returns:
            Dict: Object containing combination suggestions
        """
        combinations = {
            "text_only": [
                "Use descriptive text prompts for specific effects",
                "Combine multiple style descriptions in one prompt",
                "Include mood, lighting, and color specifications",
                "Reference artistic movements or photographers",
                "Describe the desired emotional impact"
            ],
            "text_with_style": [
                "Use text prompt for overall direction",
                "Add style image for specific visual reference",
                "Combine text description with style image",
                "Use style image to guide color palette",
                "Apply text prompt with style image influence"
            ],
            "style_only": [
                "Use style image as primary reference",
                "Let style image guide the transformation",
                "Apply style image characteristics to main image",
                "Use style image for color and texture reference",
                "Transform based on style image aesthetic"
            ]
        }
        
        print("💡 Filter Combination Suggestions:")
        for category, combination_list in combinations.items():
            print(f"{category}: {combination_list}")
        return combinations
    
    def get_filter_intensity_suggestions(self) -> Dict[str, List[str]]:
        """
        Get filter intensity suggestions
        
        Returns:
            Dict: Object containing intensity suggestions
        """
        intensity_suggestions = {
            "subtle": [
                "Apply gentle color adjustments",
                "Add subtle texture overlays",
                "Enhance existing colors slightly",
                "Apply soft lighting effects",
                "Create minimal artistic touches"
            ],
            "moderate": [
                "Apply noticeable style changes",
                "Transform colors and mood",
                "Add artistic texture effects",
                "Create distinct visual style",
                "Apply balanced filter effects"
            ],
            "dramatic": [
                "Apply bold, transformative effects",
                "Create dramatic color changes",
                "Add strong artistic elements",
                "Transform image completely",
                "Apply intense visual effects"
            ]
        }
        
        print("💡 Filter Intensity Suggestions:")
        for intensity, suggestion_list in intensity_suggestions.items():
            print(f"{intensity}: {suggestion_list}")
        return intensity_suggestions
    
    def get_filter_categories(self) -> Dict[str, Dict[str, Any]]:
        """
        Get filter category recommendations
        
        Returns:
            Dict: Object containing category recommendations
        """
        categories = {
            "artistic": {
                "description": "Transform images into various artistic styles",
                "examples": ["Oil painting", "Watercolor", "Digital art", "Sketch", "Abstract"],
                "best_for": ["Creative projects", "Artistic expression", "Unique visuals"]
            },
            "vintage": {
                "description": "Apply retro and vintage aesthetics",
                "examples": ["80s synthwave", "Film photography", "Victorian", "Art Deco", "Classic Hollywood"],
                "best_for": ["Nostalgic content", "Retro branding", "Historical themes"]
            },
            "modern": {
                "description": "Apply contemporary and modern styles",
                "examples": ["Minimalist", "Contemporary", "Urban", "Futuristic", "Instagram aesthetic"],
                "best_for": ["Modern branding", "Social media", "Contemporary design"]
            },
            "cinematic": {
                "description": "Create movie-like visual effects",
                "examples": ["Film noir", "Sci-fi", "Horror", "Romance", "Action"],
                "best_for": ["Video content", "Dramatic visuals", "Storytelling"]
            },
            "mood": {
                "description": "Set specific emotional atmospheres",
                "examples": ["Mysterious", "Romantic", "Dramatic", "Dreamy", "Energetic"],
                "best_for": ["Emotional content", "Mood setting", "Atmospheric visuals"]
            }
        }
        
        print("💡 Filter Categories:")
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
    
    def generate_filter_with_validation(self, image_data: Union[str, bytes, Path], text_prompt: str, style_image_data: Optional[Union[str, bytes, Path]] = None, content_type: str = "image/jpeg") -> Dict:
        """
        Generate filter with prompt validation
        
        Args:
            image_data: Image file path, bytes, or Path object
            text_prompt: Text prompt for filter description
            style_image_data: Optional style image data
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise LightXFilterException("Invalid text prompt")
        
        return self.process_filter(image_data, text_prompt, style_image_data, content_type)
    
    def get_filter_best_practices(self) -> Dict[str, List[str]]:
        """
        Get filter best practices
        
        Returns:
            Dict: Object containing best practices
        """
        best_practices = {
            "prompt_writing": [
                "Be specific about the desired style or effect",
                "Include details about colors, lighting, and mood",
                "Reference specific artistic movements or photographers",
                "Describe the emotional impact you want to achieve",
                "Keep prompts concise but descriptive"
            ],
            "style_image_selection": [
                "Choose style images with strong visual characteristics",
                "Ensure style images complement your main image",
                "Use high-quality style images for better results",
                "Experiment with different style images",
                "Consider the color palette of style images"
            ],
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in originals",
                "Avoid heavily compressed or low-quality images",
                "Consider the composition and framing",
                "Use images with clear, well-defined details"
            ],
            "workflow_optimization": [
                "Batch process multiple images when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        }
        
        print("💡 Filter Best Practices:")
        for category, practice_list in best_practices.items():
            print(f"{category}: {practice_list}")
        return best_practices
    
    def get_filter_performance_tips(self) -> Dict[str, List[str]]:
        """
        Get filter performance tips
        
        Returns:
            Dict: Object containing performance tips
        """
        performance_tips = {
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before applying filters",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple images"
            ],
            "resource_management": [
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after filtering",
                "Optimize network requests and retry logic"
            ],
            "user_experience": [
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer filter previews when possible",
                "Provide tips for better input images"
            ]
        }
        
        print("💡 Performance Tips:")
        for category, tip_list in performance_tips.items():
            print(f"{category}: {tip_list}")
        return performance_tips
    
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
                raise LightXFilterException(f"Upload URL request failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXFilterException(f"Network error: {e}")
    
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
            raise LightXFilterException(f"Upload error: {e}")


class LightXFilterException(Exception):
    """Custom exception for LightX Filter API errors"""
    pass


def run_example():
    """Example usage of the LightX AI Filter API"""
    try:
        # Initialize with your API key
        lightx = LightXAIFilterAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_filter_tips()
        lightx.get_filter_style_suggestions()
        lightx.get_filter_prompt_examples()
        lightx.get_filter_use_cases()
        lightx.get_filter_combinations()
        lightx.get_filter_intensity_suggestions()
        lightx.get_filter_categories()
        lightx.get_filter_best_practices()
        lightx.get_filter_performance_tips()
        
        # Load images (replace with your image paths)
        image_path = "path/to/input-image.jpg"
        style_image_path = "path/to/style-image.jpg"  # Optional
        
        # Example 1: Text prompt only
        result1 = lightx.generate_filter_with_validation(
            image_path,
            "Transform into oil painting with rich textures and warm colors",
            None,  # No style image
            "image/jpeg"
        )
        print("🎉 Oil painting filter result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Text prompt with style image
        result2 = lightx.generate_filter_with_validation(
            image_path,
            "Apply vintage film photography style",
            style_image_path,  # With style image
            "image/jpeg"
        )
        print("🎉 Vintage film filter result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Try different filter styles
        filter_styles = [
            "Create watercolor effect with soft, flowing edges",
            "Apply retro 80s synthwave style with neon colors",
            "Transform to dramatic, high-contrast black and white",
            "Create dreamy, ethereal effect with soft pastels",
            "Apply minimalist style with clean, simple composition"
        ]
        
        for style in filter_styles:
            result = lightx.generate_filter_with_validation(
                image_path,
                style,
                None,
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
