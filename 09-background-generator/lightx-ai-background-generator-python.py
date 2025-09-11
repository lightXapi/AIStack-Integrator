"""
LightX AI Background Generator API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered background generation functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXBackgroundGeneratorAPI:
    """
    LightX AI Background Generator API client for generating custom backgrounds
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Background Generator API client
        
        Args:
            api_key (str): Your LightX API key
        """
        self.api_key = api_key
        self.base_url = "https://api.lightxeditor.com/external/api"
        self.max_retries = 5
        self.retry_interval = 3  # seconds
        self.max_file_size = 5242880  # 5MB
        
    def upload_image(self, image_data: Union[bytes, str, Path], content_type: str = "image/jpeg") -> str:
        """
        Upload image to LightX servers
        
        Args:
            image_data: Image data as bytes, file path, or Path object
            content_type: MIME type (image/jpeg or image/png)
            
        Returns:
            str: Final image URL
            
        Raises:
            ValueError: If image size exceeds limit
            requests.RequestException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise FileNotFoundError(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
        elif isinstance(image_data, bytes):
            image_bytes = image_data
        else:
            raise ValueError("Invalid image data type. Expected bytes, str, or Path")
        
        file_size = len(image_bytes)
        
        if file_size > self.max_file_size:
            raise ValueError("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def generate_background(self, image_url: str, text_prompt: str) -> str:
        """
        Generate background
        
        Args:
            image_url: URL of the input image
            text_prompt: Text prompt for background generation
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/background-generator"
        
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
                raise requests.RequestException(f"Background generation request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"💬 Text prompt: \"{text_prompt}\"")
            
            return order_info["orderId"]
            
        except requests.RequestException as e:
            raise requests.RequestException(f"Network error: {e}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/order-status"
        
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
                raise requests.RequestException(f"Status check failed: {data['message']}")
            
            return data["body"]
            
        except requests.RequestException as e:
            raise requests.RequestException(f"Network error: {e}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            requests.RequestException: If processing fails or max retries reached
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Background generation completed successfully!")
                    if status.get("output"):
                        print(f"🖼️ Background image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Background generation failed")
                
                elif status["status"] == "init":
                    attempts += 1
                    if attempts < self.max_retries:
                        print(f"⏳ Waiting {self.retry_interval} seconds before next check...")
                        time.sleep(self.retry_interval)
                
                else:
                    attempts += 1
                    if attempts < self.max_retries:
                        time.sleep(self.retry_interval)
                
            except requests.RequestException as e:
                attempts += 1
                if attempts >= self.max_retries:
                    raise e
                print(f"⚠️  Error on attempt {attempts}, retrying...")
                time.sleep(self.retry_interval)
        
        raise requests.RequestException("Maximum retry attempts reached")
    
    def process_background_generation(self, image_data: Union[bytes, str, Path],
                                     text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and generate background
        
        Args:
            image_data: Image data
            text_prompt: Text prompt for background generation
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Background Generator API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Generate background
        print("🖼️ Generating background...")
        order_id = self.generate_background(image_url, text_prompt)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_background_tips(self) -> Dict[str, List[str]]:
        """
        Get background generation tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better background results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the subject is clearly visible and centered",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good subject separation improves background generation"
            ],
            "text_prompts": [
                "Be specific about the background style you want",
                "Mention color schemes and mood preferences",
                "Include environmental details (indoor, outdoor, studio)",
                "Specify lighting preferences (natural, dramatic, soft)",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Background generation works best with clear subjects",
                "Results may vary based on input image quality",
                "Text prompts guide the background generation process",
                "Allow 15-30 seconds for processing",
                "Experiment with different prompt styles for variety"
            ]
        }
        
        print("💡 Background Generation Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_background_style_suggestions(self) -> Dict[str, List[str]]:
        """
        Get background style suggestions
        
        Returns:
            Dict[str, List[str]]: Dictionary of background style suggestions
        """
        style_suggestions = {
            "professional": [
                "professional office background",
                "clean minimalist workspace",
                "modern corporate environment",
                "elegant business setting",
                "sophisticated professional space"
            ],
            "natural": [
                "natural outdoor landscape",
                "beautiful garden background",
                "scenic mountain view",
                "peaceful forest setting",
                "serene beach environment"
            ],
            "creative": [
                "artistic studio background",
                "creative workspace environment",
                "colorful abstract background",
                "modern art gallery setting",
                "inspiring creative space"
            ],
            "lifestyle": [
                "cozy home interior",
                "modern living room",
                "stylish bedroom setting",
                "contemporary kitchen",
                "elegant dining room"
            ],
            "outdoor": [
                "urban cityscape background",
                "park environment",
                "outdoor cafe setting",
                "street scene background",
                "architectural landmark view"
            ]
        }
        
        print("💡 Background Style Suggestions:")
        for category, suggestion_list in style_suggestions.items():
            print(f"{category}: {suggestion_list}")
        return style_suggestions
    
    def get_background_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get background prompt examples
        
        Returns:
            Dict[str, List[str]]: Dictionary of prompt examples
        """
        prompt_examples = {
            "professional": [
                "Modern office with glass windows and city view",
                "Clean white studio background with soft lighting",
                "Professional conference room with wooden furniture",
                "Contemporary workspace with minimalist design",
                "Elegant business environment with neutral colors"
            ],
            "natural": [
                "Beautiful sunset over mountains in the background",
                "Lush green garden with blooming flowers",
                "Peaceful lake with reflection of trees",
                "Golden hour lighting in a forest setting",
                "Serene beach with gentle waves and palm trees"
            ],
            "creative": [
                "Artistic studio with colorful paint splashes",
                "Modern gallery with white walls and track lighting",
                "Creative workspace with vintage furniture",
                "Abstract colorful background with geometric shapes",
                "Bohemian style room with eclectic decorations"
            ],
            "lifestyle": [
                "Cozy living room with warm lighting and books",
                "Modern kitchen with marble countertops",
                "Stylish bedroom with soft natural light",
                "Contemporary dining room with elegant table",
                "Comfortable home office with plants and books"
            ],
            "outdoor": [
                "Urban cityscape with modern skyscrapers",
                "Charming street with cafes and shops",
                "Park setting with trees and walking paths",
                "Historic architecture with classical columns",
                "Modern outdoor space with contemporary design"
            ]
        }
        
        print("💡 Background Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def validate_text_prompt(self, text_prompt: str) -> bool:
        """
        Validate text prompt (utility function)
        
        Args:
            text_prompt: Text prompt to validate
            
        Returns:
            bool: Whether the prompt is valid
        """
        if not text_prompt or not text_prompt.strip():
            print("❌ Text prompt cannot be empty")
            return False
        
        if len(text_prompt) > 500:
            print("❌ Text prompt is too long (max 500 characters)")
            return False
        
        print("✅ Text prompt is valid")
        return True
    
    def generate_background_with_prompt(self, image_data: Union[bytes, str, Path],
                                       text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate background with prompt validation
        
        Args:
            image_data: Image data
            text_prompt: Text prompt for background generation
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_background_generation(image_data, text_prompt, content_type)
    
    def get_image_dimensions(self, image_data: Union[bytes, str, Path]) -> Tuple[int, int]:
        """
        Get image dimensions (utility function)
        
        Args:
            image_data: Image data as bytes, file path, or Path object
            
        Returns:
            Tuple[int, int]: (width, height)
        """
        try:
            if isinstance(image_data, (str, Path)):
                image_path = Path(image_data)
                with open(image_path, 'rb') as f:
                    image_bytes = f.read()
            elif isinstance(image_data, bytes):
                image_bytes = image_data
            else:
                raise ValueError("Invalid image data type")
            
            # Use PIL to get dimensions
            image = Image.open(io.BytesIO(image_bytes))
            width, height = image.size
            print(f"📏 Image dimensions: {width}x{height}")
            return width, height
            
        except Exception as e:
            print(f"❌ Error getting image dimensions: {e}")
            return 0, 0
    
    # Private methods
    
    def _get_upload_url(self, file_size: int, content_type: str) -> Dict:
        """
        Get upload URL from LightX API
        
        Args:
            file_size: Size of the file in bytes
            content_type: MIME type
            
        Returns:
            Dict: Upload URL response
            
        Raises:
            requests.RequestException: If API request fails
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
                raise requests.RequestException(f"Upload URL request failed: {data['message']}")
            
            return data["body"]
            
        except requests.RequestException as e:
            raise requests.RequestException(f"Network error: {e}")
    
    def _upload_to_s3(self, upload_url: str, image_bytes: bytes, content_type: str) -> None:
        """
        Upload image to S3 using the provided upload URL
        
        Args:
            upload_url: S3 upload URL
            image_bytes: Image data as bytes
            content_type: MIME type
            
        Raises:
            requests.RequestException: If upload fails
        """
        try:
            response = requests.put(upload_url, data=image_bytes, headers={"Content-Type": content_type})
            response.raise_for_status()
            
        except requests.RequestException as e:
            raise requests.RequestException(f"Upload error: {e}")


def run_example():
    """
    Example usage of the LightX Background Generator API
    """
    try:
        # Initialize with your API key
        lightx = LightXBackgroundGeneratorAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_background_tips()
        lightx.get_background_style_suggestions()
        lightx.get_background_prompt_examples()
        
        # Load image (replace with your image loading logic)
        image_path = "path/to/input-image.jpg"
        
        # Example 1: Generate background with professional style
        professional_prompts = lightx.get_background_style_suggestions()["professional"]
        result1 = lightx.generate_background_with_prompt(
            image_path,
            professional_prompts[0],
            "image/jpeg"
        )
        print("🎉 Professional background result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Generate background with natural style
        natural_prompts = lightx.get_background_style_suggestions()["natural"]
        result2 = lightx.generate_background_with_prompt(
            image_path,
            natural_prompts[0],
            "image/jpeg"
        )
        print("🎉 Natural background result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Generate backgrounds for different styles
        styles = ["professional", "natural", "creative", "lifestyle", "outdoor"]
        for style in styles:
            prompts = lightx.get_background_style_suggestions()[style]
            result = lightx.generate_background_with_prompt(
                image_path,
                prompts[0],
                "image/jpeg"
            )
            print(f"🎉 {style} background result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 4: Get image dimensions
        width, height = lightx.get_image_dimensions(image_path)
        if width > 0 and height > 0:
            print(f"📏 Original image: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
