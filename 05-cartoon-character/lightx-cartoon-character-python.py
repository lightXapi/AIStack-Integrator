"""
LightX AI Cartoon Character Generator API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered cartoon character generation functionality.
"""

import requests
import os
import time
import json
from typing import Optional, Dict, Any, Tuple, List
from pathlib import Path


class LightXCartoonAPI:
    """
    LightX API client for AI cartoon character generation functionality
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Cartoon API client
        
        Args:
            api_key (str): Your LightX API key
        """
        self.api_key = api_key
        self.base_url = "https://api.lightxeditor.com/external/api"
        self.max_retries = 5
        self.retry_interval = 3  # seconds
        
    def upload_image(self, image_path: str, content_type: str = "image/jpeg") -> str:
        """
        Upload image to LightX servers
        
        Args:
            image_path (str): Path to the image file
            content_type (str): MIME type (image/jpeg or image/png)
            
        Returns:
            str: Final image URL
            
        Raises:
            ValueError: If image size exceeds 5MB limit
            requests.RequestException: If upload fails
        """
        try:
            # Get file size
            file_size = os.path.getsize(image_path)
            
            if file_size > 5242880:  # 5MB limit
                raise ValueError("Image size exceeds 5MB limit")
            
            # Step 1: Get upload URL
            upload_url_endpoint = f"{self.base_url}/v2/uploadImageUrl"
            headers = {
                "Content-Type": "application/json",
                "x-api-key": self.api_key
            }
            
            payload = {
                "uploadType": "imageUrl",
                "size": file_size,
                "contentType": content_type
            }
            
            response = requests.post(upload_url_endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            if data.get("statusCode") != 2000:
                raise requests.RequestException(f"Upload URL request failed: {data.get('message')}")
            
            upload_image_url = data["body"]["uploadImage"]
            final_image_url = data["body"]["imageUrl"]
            
            # Step 2: Upload image to S3
            with open(image_path, 'rb') as image_file:
                upload_headers = {"Content-Type": content_type}
                upload_response = requests.put(upload_image_url, data=image_file, headers=upload_headers)
                upload_response.raise_for_status()
            
            print("✅ Image uploaded successfully")
            return final_image_url
            
        except Exception as e:
            print(f"❌ Error uploading image: {str(e)}")
            raise
    
    def upload_images(self, input_image_path: str, style_image_path: Optional[str] = None, 
                     content_type: str = "image/jpeg") -> Tuple[str, Optional[str]]:
        """
        Upload multiple images (input and optional style image)
        
        Args:
            input_image_path (str): Path to the input image
            style_image_path (Optional[str]): Path to the style image (optional)
            content_type (str): MIME type
            
        Returns:
            Tuple[str, Optional[str]]: URLs for input and style images
        """
        try:
            print("📤 Uploading input image...")
            input_url = self.upload_image(input_image_path, content_type)
            
            style_url = None
            if style_image_path:
                print("📤 Uploading style image...")
                style_url = self.upload_image(style_image_path, content_type)
            
            return input_url, style_url
            
        except Exception as e:
            print(f"❌ Error uploading images: {str(e)}")
            raise
    
    def generate_cartoon(self, image_url: str, style_image_url: Optional[str] = None, 
                        text_prompt: Optional[str] = None) -> str:
        """
        Generate cartoon character
        
        Args:
            image_url (str): URL of the input image
            style_image_url (Optional[str]): URL of the style image (optional)
            text_prompt (Optional[str]): Text prompt for cartoon style (optional)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If cartoon generation request fails
        """
        try:
            endpoint = f"{self.base_url}/v1/cartoon"
            headers = {
                "Content-Type": "application/json",
                "x-api-key": self.api_key
            }
            
            payload = {"imageUrl": image_url}
            
            # Add optional parameters
            if style_image_url:
                payload["styleImageUrl"] = style_image_url
            if text_prompt:
                payload["textPrompt"] = text_prompt
            
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            if data.get("statusCode") != 2000:
                raise requests.RequestException(f"Cartoon generation request failed: {data.get('message')}")
            
            order_info = data["body"]
            order_id = order_info["orderId"]
            max_retries = order_info["maxRetriesAllowed"]
            avg_response_time = order_info["avgResponseTimeInSec"]
            status = order_info["status"]
            
            print(f"📋 Order created: {order_id}")
            print(f"🔄 Max retries allowed: {max_retries}")
            print(f"⏱️  Average response time: {avg_response_time} seconds")
            print(f"📊 Status: {status}")
            if text_prompt:
                print(f"💬 Text prompt: \"{text_prompt}\"")
            if style_image_url:
                print(f"🎨 Style image: {style_image_url}")
            
            return order_id
            
        except Exception as e:
            print(f"❌ Error generating cartoon: {str(e)}")
            raise
    
    def check_order_status(self, order_id: str) -> Dict[str, Any]:
        """
        Check order status
        
        Args:
            order_id (str): Order ID to check
            
        Returns:
            Dict[str, Any]: Order status and results
            
        Raises:
            requests.RequestException: If status check fails
        """
        try:
            endpoint = f"{self.base_url}/v1/order-status"
            headers = {
                "Content-Type": "application/json",
                "x-api-key": self.api_key
            }
            
            payload = {"orderId": order_id}
            
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            if data.get("statusCode") != 2000:
                raise requests.RequestException(f"Status check failed: {data.get('message')}")
            
            return data["body"]
            
        except Exception as e:
            print(f"❌ Error checking order status: {str(e)}")
            raise
    
    def wait_for_completion(self, order_id: str) -> Dict[str, Any]:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id (str): Order ID to monitor
            
        Returns:
            Dict[str, Any]: Final result with output URL
            
        Raises:
            Exception: If maximum retries reached or processing failed
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Cartoon generation completed successfully!")
                    print(f"🎨 Cartoon image: {status['output']}")
                    return status
                    
                elif status["status"] == "failed":
                    raise Exception("Cartoon generation failed")
                    
                elif status["status"] == "init":
                    # Still processing, wait and retry
                    attempts += 1
                    if attempts < self.max_retries:
                        print(f"⏳ Waiting {self.retry_interval} seconds before next check...")
                        time.sleep(self.retry_interval)
                        
            except Exception as e:
                attempts += 1
                if attempts >= self.max_retries:
                    raise
                print(f"⚠️  Error on attempt {attempts}, retrying...")
                time.sleep(self.retry_interval)
        
        raise Exception("Maximum retry attempts reached")
    
    def process_cartoon_generation(self, input_image_path: str, style_image_path: Optional[str] = None, 
                                  text_prompt: Optional[str] = None, content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Complete workflow: Upload images and generate cartoon
        
        Args:
            input_image_path (str): Path to the input image
            style_image_path (Optional[str]): Path to the style image (optional)
            text_prompt (Optional[str]): Text prompt for cartoon style (optional)
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        try:
            print("🚀 Starting LightX AI Cartoon Character Generator API workflow...")
            
            # Step 1: Upload images
            print("📤 Uploading images...")
            input_url, style_url = self.upload_images(input_image_path, style_image_path, content_type)
            print(f"✅ Input image uploaded: {input_url}")
            if style_url:
                print(f"✅ Style image uploaded: {style_url}")
            
            # Step 2: Generate cartoon
            print("🎨 Generating cartoon character...")
            order_id = self.generate_cartoon(input_url, style_url, text_prompt)
            
            # Step 3: Wait for completion
            print("⏳ Waiting for processing to complete...")
            result = self.wait_for_completion(order_id)
            
            return result
            
        except Exception as e:
            print(f"❌ Workflow failed: {str(e)}")
            raise
    
    def get_suggested_prompts(self, category: str) -> List[str]:
        """
        Get common text prompts for different cartoon styles
        
        Args:
            category (str): Category of cartoon style
            
        Returns:
            List[str]: List of suggested prompts
        """
        prompt_suggestions = {
            "classic": [
                "classic Disney style cartoon",
                "vintage cartoon character",
                "traditional animation style",
                "classic comic book style",
                "retro cartoon character"
            ],
            "modern": [
                "modern anime style",
                "contemporary cartoon character",
                "digital art style",
                "modern illustration style",
                "stylized cartoon character"
            ],
            "artistic": [
                "watercolor cartoon style",
                "oil painting cartoon",
                "sketch cartoon style",
                "artistic cartoon character",
                "painterly cartoon style"
            ],
            "fun": [
                "cute and adorable cartoon",
                "funny cartoon character",
                "playful cartoon style",
                "whimsical cartoon character",
                "cheerful cartoon style"
            ],
            "professional": [
                "professional cartoon portrait",
                "business cartoon style",
                "corporate cartoon character",
                "formal cartoon style",
                "professional illustration"
            ]
        }
        
        prompts = prompt_suggestions.get(category, [])
        print(f"💡 Suggested prompts for {category}: {prompts}")
        return prompts
    
    def validate_text_prompt(self, text_prompt: str) -> bool:
        """
        Validate text prompt (utility function)
        
        Args:
            text_prompt (str): Text prompt to validate
            
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
    
    def generate_cartoon_with_prompt(self, input_image_path: str, text_prompt: str, 
                                   content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Generate cartoon with text prompt only
        
        Args:
            input_image_path (str): Path to the input image
            text_prompt (str): Text prompt for cartoon style
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_cartoon_generation(input_image_path, None, text_prompt, content_type)
    
    def generate_cartoon_with_style(self, input_image_path: str, style_image_path: str, 
                                  content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Generate cartoon with style image only
        
        Args:
            input_image_path (str): Path to the input image
            style_image_path (str): Path to the style image
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        return self.process_cartoon_generation(input_image_path, style_image_path, None, content_type)
    
    def generate_cartoon_with_style_and_prompt(self, input_image_path: str, style_image_path: str, 
                                             text_prompt: str, content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Generate cartoon with both style image and text prompt
        
        Args:
            input_image_path (str): Path to the input image
            style_image_path (str): Path to the style image
            text_prompt (str): Text prompt for cartoon style
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_cartoon_generation(input_image_path, style_image_path, text_prompt, content_type)
    
    def get_image_dimensions(self, image_path: str) -> Tuple[int, int]:
        """
        Get image dimensions (utility function)
        
        Args:
            image_path (str): Path to the image file
            
        Returns:
            Tuple[int, int]: (width, height)
        """
        try:
            from PIL import Image
            
            with Image.open(image_path) as img:
                width, height = img.size
                print(f"📏 Image dimensions: {width}x{height}")
                return width, height
                
        except ImportError:
            print("⚠️  PIL (Pillow) not installed. Install with: pip install Pillow")
            return 0, 0
        except Exception as e:
            print(f"❌ Error getting image dimensions: {str(e)}")
            return 0, 0


def main():
    """
    Example usage of the LightX Cartoon API client
    """
    try:
        # Initialize with your API key
        lightx = LightXCartoonAPI("YOUR_API_KEY_HERE")
        
        # Example 1: Generate cartoon with text prompt only
        classic_prompts = lightx.get_suggested_prompts("classic")
        result1 = lightx.generate_cartoon_with_prompt(
            input_image_path="./path/to/your/image.jpg",
            text_prompt=classic_prompts[0],
            content_type="image/jpeg"
        )
        print("🎉 Classic cartoon result:", json.dumps(result1, indent=2))
        
        # Example 2: Generate cartoon with style image only
        result2 = lightx.generate_cartoon_with_style(
            input_image_path="./path/to/your/image.jpg",
            style_image_path="./path/to/style-image.jpg",
            content_type="image/jpeg"
        )
        print("🎉 Style-based cartoon result:", json.dumps(result2, indent=2))
        
        # Example 3: Generate cartoon with both style image and text prompt
        modern_prompts = lightx.get_suggested_prompts("modern")
        result3 = lightx.generate_cartoon_with_style_and_prompt(
            input_image_path="./path/to/your/image.jpg",
            style_image_path="./path/to/style-image.jpg",
            text_prompt=modern_prompts[0],
            content_type="image/jpeg"
        )
        print("🎉 Combined style and prompt result:", json.dumps(result3, indent=2))
        
        # Example 4: Generate cartoon with different style categories
        categories = ["classic", "modern", "artistic", "fun", "professional"]
        for category in categories:
            prompts = lightx.get_suggested_prompts(category)
            result = lightx.generate_cartoon_with_prompt(
                input_image_path="./path/to/your/image.jpg",
                text_prompt=prompts[0],
                content_type="image/jpeg"
            )
            print(f"🎉 {category} cartoon result:", json.dumps(result, indent=2))
        
        # Example 5: Get image dimensions
        width, height = lightx.get_image_dimensions("./path/to/your/image.jpg")
        if width > 0 and height > 0:
            print(f"📏 Original image: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {str(e)}")


if __name__ == "__main__":
    main()
