"""
LightX AI Replace Item API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered item replacement functionality using text prompts and masks.
"""

import requests
import os
import time
import json
from typing import Optional, Dict, Any, Tuple, List
from pathlib import Path


class LightXReplaceAPI:
    """
    LightX API client for AI item replacement functionality
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Replace API client
        
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
    
    def upload_images(self, original_image_path: str, mask_image_path: str, 
                     content_type: str = "image/jpeg") -> Tuple[str, str]:
        """
        Upload multiple images (original and mask)
        
        Args:
            original_image_path (str): Path to the original image
            mask_image_path (str): Path to the mask image
            content_type (str): MIME type
            
        Returns:
            Tuple[str, str]: URLs for original and mask images
        """
        try:
            print("📤 Uploading original image...")
            original_url = self.upload_image(original_image_path, content_type)
            
            print("📤 Uploading mask image...")
            mask_url = self.upload_image(mask_image_path, content_type)
            
            return original_url, mask_url
            
        except Exception as e:
            print(f"❌ Error uploading images: {str(e)}")
            raise
    
    def replace_item(self, image_url: str, masked_image_url: str, text_prompt: str) -> str:
        """
        Replace item using AI and text prompt
        
        Args:
            image_url (str): URL of the original image
            masked_image_url (str): URL of the mask image
            text_prompt (str): Text prompt describing what to replace with
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If replacement request fails
        """
        try:
            endpoint = f"{self.base_url}/v1/replace"
            headers = {
                "Content-Type": "application/json",
                "x-api-key": self.api_key
            }
            
            payload = {
                "imageUrl": image_url,
                "maskedImageUrl": masked_image_url,
                "textPrompt": text_prompt
            }
            
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            if data.get("statusCode") != 2000:
                raise requests.RequestException(f"Replace item request failed: {data.get('message')}")
            
            order_info = data["body"]
            order_id = order_info["orderId"]
            max_retries = order_info["maxRetriesAllowed"]
            avg_response_time = order_info["avgResponseTimeInSec"]
            status = order_info["status"]
            
            print(f"📋 Order created: {order_id}")
            print(f"🔄 Max retries allowed: {max_retries}")
            print(f"⏱️  Average response time: {avg_response_time} seconds")
            print(f"📊 Status: {status}")
            print(f"💬 Text prompt: \"{text_prompt}\"")
            
            return order_id
            
        except Exception as e:
            print(f"❌ Error replacing item: {str(e)}")
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
                    print("✅ Item replacement completed successfully!")
                    print(f"🖼️  Replaced image: {status['output']}")
                    return status
                    
                elif status["status"] == "failed":
                    raise Exception("Item replacement failed")
                    
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
    
    def process_replacement(self, original_image_path: str, mask_image_path: str, 
                           text_prompt: str, content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Complete workflow: Upload images and replace item
        
        Args:
            original_image_path (str): Path to the original image
            mask_image_path (str): Path to the mask image
            text_prompt (str): Text prompt for replacement
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        try:
            print("🚀 Starting LightX AI Replace API workflow...")
            
            # Step 1: Upload both images
            print("📤 Uploading images...")
            original_url, mask_url = self.upload_images(original_image_path, mask_image_path, content_type)
            print(f"✅ Original image uploaded: {original_url}")
            print(f"✅ Mask image uploaded: {mask_url}")
            
            # Step 2: Replace item
            print("🔄 Replacing item with AI...")
            order_id = self.replace_item(original_url, mask_url, text_prompt)
            
            # Step 3: Wait for completion
            print("⏳ Waiting for processing to complete...")
            result = self.wait_for_completion(order_id)
            
            return result
            
        except Exception as e:
            print(f"❌ Workflow failed: {str(e)}")
            raise
    
    def create_mask_from_coordinates(self, width: int, height: int, 
                                   coordinates: List[Dict[str, int]]) -> str:
        """
        Create a simple mask from coordinates (utility function)
        
        Args:
            width (int): Image width
            height (int): Image height
            coordinates (List[Dict]): List of dicts with {x, y, width, height} for white areas
            
        Returns:
            str: Path to created mask image
        """
        try:
            from PIL import Image, ImageDraw
            
            print("🎭 Creating mask from coordinates...")
            print(f"Image dimensions: {width}x{height}")
            print(f"White areas: {coordinates}")
            
            # Create black background
            mask = Image.new('RGB', (width, height), color='black')
            draw = ImageDraw.Draw(mask)
            
            # Draw white rectangles for areas to replace
            for coord in coordinates:
                x, y, w, h = coord['x'], coord['y'], coord['width'], coord['height']
                draw.rectangle([x, y, x + w, y + h], fill='white')
            
            # Save mask
            mask_path = "generated_mask.png"
            mask.save(mask_path)
            print(f"✅ Mask created and saved: {mask_path}")
            
            return mask_path
            
        except ImportError:
            print("⚠️  PIL (Pillow) not installed. Install with: pip install Pillow")
            print("🎭 Mask creation requires PIL. Returning placeholder.")
            return "mask-created-from-coordinates"
        except Exception as e:
            print(f"❌ Error creating mask: {str(e)}")
            return "mask-creation-failed"
    
    def get_suggested_prompts(self, category: str) -> List[str]:
        """
        Get common text prompts for different replacement scenarios
        
        Args:
            category (str): Category of replacement
            
        Returns:
            List[str]: List of suggested prompts
        """
        prompt_suggestions = {
            "face": [
                "a young woman with blonde hair",
                "an elderly man with a beard",
                "a smiling child",
                "a professional businessman",
                "a person wearing glasses"
            ],
            "clothing": [
                "a red dress",
                "a blue suit",
                "a casual t-shirt",
                "a winter jacket",
                "a formal shirt"
            ],
            "objects": [
                "a modern smartphone",
                "a vintage car",
                "a beautiful flower",
                "a wooden chair",
                "a glass vase"
            ],
            "background": [
                "a beach scene",
                "a mountain landscape",
                "a modern office",
                "a cozy living room",
                "a garden setting"
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
    Example usage of the LightX Replace API client
    """
    try:
        # Initialize with your API key
        lightx = LightXReplaceAPI("YOUR_API_KEY_HERE")
        
        # Example 1: Replace a face
        face_prompts = lightx.get_suggested_prompts("face")
        result1 = lightx.process_replacement(
            original_image_path="./path/to/original-image.jpg",
            mask_image_path="./path/to/mask-image.png",
            text_prompt=face_prompts[0],
            content_type="image/jpeg"
        )
        print("🎉 Face replacement result:", json.dumps(result1, indent=2))
        
        # Example 2: Replace clothing
        clothing_prompts = lightx.get_suggested_prompts("clothing")
        result2 = lightx.process_replacement(
            original_image_path="./path/to/original-image.jpg",
            mask_image_path="./path/to/mask-image.png",
            text_prompt=clothing_prompts[0],
            content_type="image/jpeg"
        )
        print("🎉 Clothing replacement result:", json.dumps(result2, indent=2))
        
        # Example 3: Replace background
        background_prompts = lightx.get_suggested_prompts("background")
        result3 = lightx.process_replacement(
            original_image_path="./path/to/original-image.jpg",
            mask_image_path="./path/to/mask-image.png",
            text_prompt=background_prompts[0],
            content_type="image/jpeg"
        )
        print("🎉 Background replacement result:", json.dumps(result3, indent=2))
        
        # Example 4: Create mask from coordinates and process
        # width, height = lightx.get_image_dimensions("./path/to/original-image.jpg")
        # if width > 0 and height > 0:
        #     mask_path = lightx.create_mask_from_coordinates(
        #         width=width, height=height,
        #         coordinates=[
        #             {"x": 100, "y": 100, "width": 200, "height": 150},  # Area to replace
        #             {"x": 400, "y": 300, "width": 100, "height": 100}   # Another area to replace
        #         ]
        #     )
        #     
        #     if mask_path != "mask-creation-failed":
        #         result = lightx.process_replacement(
        #             original_image_path="./path/to/original-image.jpg",
        #             mask_image_path=mask_path,
        #             text_prompt="a beautiful sunset",
        #             content_type="image/jpeg"
        #         )
        #         print("🎉 Replacement with generated mask:", json.dumps(result, indent=2))
        
    except Exception as e:
        print(f"❌ Example failed: {str(e)}")


if __name__ == "__main__":
    main()
