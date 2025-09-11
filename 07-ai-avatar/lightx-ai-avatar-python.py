"""
LightX AI Avatar API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered avatar generation functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXAvatarAPI:
    """
    LightX AI Avatar API client for generating personalized avatars
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Avatar API client
        
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
    
    def upload_images(self, input_image_data: Union[bytes, str, Path], 
                     style_image_data: Optional[Union[bytes, str, Path]] = None,
                     content_type: str = "image/jpeg") -> Tuple[str, Optional[str]]:
        """
        Upload multiple images (input and optional style image)
        
        Args:
            input_image_data: Input image data
            style_image_data: Style image data (optional)
            content_type: MIME type
            
        Returns:
            Tuple of (input_url, style_url)
        """
        print("📤 Uploading input image...")
        input_url = self.upload_image(input_image_data, content_type)
        
        style_url = None
        if style_image_data:
            print("📤 Uploading style image...")
            style_url = self.upload_image(style_image_data, content_type)
        
        return input_url, style_url
    
    def generate_avatar(self, image_url: str, style_image_url: Optional[str] = None, 
                       text_prompt: Optional[str] = None) -> str:
        """
        Generate avatar
        
        Args:
            image_url: URL of the input image
            style_image_url: URL of the style image (optional)
            text_prompt: Text prompt for avatar style (optional)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/avatar"
        
        payload = {
            "imageUrl": image_url
        }
        
        # Add optional parameters
        if style_image_url:
            payload["styleImageUrl"] = style_image_url
        if text_prompt:
            payload["textPrompt"] = text_prompt
        
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            
            if data["statusCode"] != 2000:
                raise requests.RequestException(f"Avatar generation request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            if text_prompt:
                print(f"💬 Text prompt: \"{text_prompt}\"")
            if style_image_url:
                print(f"🎨 Style image: {style_image_url}")
            
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
                    print("✅ Avatar generation completed successfully!")
                    if status.get("output"):
                        print(f"👤 Avatar image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Avatar generation failed")
                
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
    
    def process_avatar_generation(self, input_image_data: Union[bytes, str, Path],
                                 style_image_data: Optional[Union[bytes, str, Path]] = None,
                                 text_prompt: Optional[str] = None,
                                 content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload images and generate avatar
        
        Args:
            input_image_data: Input image data
            style_image_data: Style image data (optional)
            text_prompt: Text prompt for avatar style (optional)
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Avatar API workflow...")
        
        # Step 1: Upload images
        print("📤 Uploading images...")
        input_url, style_url = self.upload_images(input_image_data, style_image_data, content_type)
        print(f"✅ Input image uploaded: {input_url}")
        if style_url:
            print(f"✅ Style image uploaded: {style_url}")
        
        # Step 2: Generate avatar
        print("👤 Generating avatar...")
        order_id = self.generate_avatar(input_url, style_url, text_prompt)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_suggested_prompts(self, category: str) -> List[str]:
        """
        Get common text prompts for different avatar styles
        
        Args:
            category: Category of avatar style
            
        Returns:
            List[str]: List of suggested prompts
        """
        prompt_suggestions = {
            "male": [
                "professional male avatar",
                "businessman avatar",
                "casual male portrait",
                "formal male avatar",
                "modern male character"
            ],
            "female": [
                "professional female avatar",
                "businesswoman avatar",
                "casual female portrait",
                "formal female avatar",
                "modern female character"
            ],
            "gaming": [
                "gaming avatar character",
                "esports player avatar",
                "gamer profile picture",
                "gaming character portrait",
                "pro gamer avatar"
            ],
            "social": [
                "social media avatar",
                "profile picture avatar",
                "social network avatar",
                "online avatar portrait",
                "digital identity avatar"
            ],
            "artistic": [
                "artistic avatar style",
                "creative avatar portrait",
                "artistic character design",
                "creative profile avatar",
                "artistic digital portrait"
            ],
            "corporate": [
                "corporate avatar",
                "professional business avatar",
                "executive avatar portrait",
                "business profile avatar",
                "corporate identity avatar"
            ]
        }
        
        prompts = prompt_suggestions.get(category, [])
        print(f"💡 Suggested prompts for {category}: {prompts}")
        return prompts
    
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
    
    def generate_avatar_with_prompt(self, input_image_data: Union[bytes, str, Path],
                                   text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate avatar with text prompt only
        
        Args:
            input_image_data: Input image data
            text_prompt: Text prompt for avatar style
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_avatar_generation(input_image_data, None, text_prompt, content_type)
    
    def generate_avatar_with_style(self, input_image_data: Union[bytes, str, Path],
                                  style_image_data: Union[bytes, str, Path],
                                  content_type: str = "image/jpeg") -> Dict:
        """
        Generate avatar with style image only
        
        Args:
            input_image_data: Input image data
            style_image_data: Style image data
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        return self.process_avatar_generation(input_image_data, style_image_data, None, content_type)
    
    def generate_avatar_with_style_and_prompt(self, input_image_data: Union[bytes, str, Path],
                                             style_image_data: Union[bytes, str, Path],
                                             text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate avatar with both style image and text prompt
        
        Args:
            input_image_data: Input image data
            style_image_data: Style image data
            text_prompt: Text prompt for avatar style
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_avatar_generation(input_image_data, style_image_data, text_prompt, content_type)
    
    def get_avatar_tips(self) -> Dict[str, List[str]]:
        """
        Get avatar tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better avatar results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for avatars"
            ],
            "style_image": [
                "Choose style images with clear facial features",
                "Use avatar examples as style references",
                "Ensure style image has good lighting",
                "Match the pose of your input image if possible",
                "Use high-quality style reference images"
            ],
            "text_prompts": [
                "Be specific about the avatar style you want",
                "Mention gender preference (male/female)",
                "Include professional or casual style preferences",
                "Specify if you want gaming, social, or corporate avatars",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Avatars work best with human faces",
                "Results may vary based on input image quality",
                "Style images influence both artistic style and pose",
                "Text prompts help guide the avatar generation",
                "Allow 15-30 seconds for processing"
            ]
        }
        
        print("💡 Avatar Generation Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
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
    Example usage of the LightX Avatar API
    """
    try:
        # Initialize with your API key
        lightx = LightXAvatarAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_avatar_tips()
        
        # Load input image (replace with your image loading logic)
        input_image_path = "path/to/input-image.jpg"
        style_image_path = "path/to/style-image.jpg"
        
        # Example 1: Generate avatar with text prompt only
        male_prompts = lightx.get_suggested_prompts("male")
        result1 = lightx.generate_avatar_with_prompt(
            input_image_path,
            male_prompts[0],
            "image/jpeg"
        )
        print("🎉 Male avatar result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Generate avatar with style image only
        result2 = lightx.generate_avatar_with_style(
            input_image_path,
            style_image_path,
            "image/jpeg"
        )
        print("🎉 Style-based avatar result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Generate avatar with both style image and text prompt
        female_prompts = lightx.get_suggested_prompts("female")
        result3 = lightx.generate_avatar_with_style_and_prompt(
            input_image_path,
            style_image_path,
            female_prompts[0],
            "image/jpeg"
        )
        print("🎉 Combined style and prompt result:")
        print(f"Order ID: {result3['orderId']}")
        print(f"Status: {result3['status']}")
        if result3.get("output"):
            print(f"Output: {result3['output']}")
        
        # Example 4: Generate avatars for different categories
        categories = ["male", "female", "gaming", "social", "artistic", "corporate"]
        for category in categories:
            prompts = lightx.get_suggested_prompts(category)
            result = lightx.generate_avatar_with_prompt(
                input_image_path,
                prompts[0],
                "image/jpeg"
            )
            print(f"🎉 {category} avatar result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 5: Get image dimensions
        width, height = lightx.get_image_dimensions(input_image_path)
        if width > 0 and height > 0:
            print(f"📏 Original image: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
