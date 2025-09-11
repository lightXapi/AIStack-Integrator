"""
LightX Cleanup Picture API Integration - Python

This implementation provides a complete integration with LightX API v2
for image cleanup functionality using mask-based object removal.
"""

import requests
import os
import time
import json
from typing import Optional, Dict, Any, Tuple
from pathlib import Path


class LightXCleanupAPI:
    """
    LightX API client for cleanup picture functionality
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Cleanup API client
        
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
    
    def cleanup_picture(self, image_url: str, masked_image_url: str) -> str:
        """
        Cleanup picture using mask
        
        Args:
            image_url (str): URL of the original image
            masked_image_url (str): URL of the mask image
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If cleanup request fails
        """
        try:
            endpoint = f"{self.base_url}/v1/cleanup-picture"
            headers = {
                "Content-Type": "application/json",
                "x-api-key": self.api_key
            }
            
            payload = {
                "imageUrl": image_url,
                "maskedImageUrl": masked_image_url
            }
            
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            if data.get("statusCode") != 2000:
                raise requests.RequestException(f"Cleanup picture request failed: {data.get('message')}")
            
            order_info = data["body"]
            order_id = order_info["orderId"]
            max_retries = order_info["maxRetriesAllowed"]
            avg_response_time = order_info["avgResponseTimeInSec"]
            status = order_info["status"]
            
            print(f"📋 Order created: {order_id}")
            print(f"🔄 Max retries allowed: {max_retries}")
            print(f"⏱️  Average response time: {avg_response_time} seconds")
            print(f"📊 Status: {status}")
            
            return order_id
            
        except Exception as e:
            print(f"❌ Error cleaning up picture: {str(e)}")
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
                    print("✅ Picture cleanup completed successfully!")
                    print(f"🖼️  Output image: {status['output']}")
                    return status
                    
                elif status["status"] == "failed":
                    raise Exception("Picture cleanup failed")
                    
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
    
    def process_cleanup(self, original_image_path: str, mask_image_path: str, 
                       content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Complete workflow: Upload images and cleanup picture
        
        Args:
            original_image_path (str): Path to the original image
            mask_image_path (str): Path to the mask image
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        try:
            print("🚀 Starting LightX Cleanup Picture API workflow...")
            
            # Step 1: Upload both images
            print("📤 Uploading images...")
            original_url, mask_url = self.upload_images(original_image_path, mask_image_path, content_type)
            print(f"✅ Original image uploaded: {original_url}")
            print(f"✅ Mask image uploaded: {mask_url}")
            
            # Step 2: Cleanup picture
            print("🧹 Cleaning up picture...")
            order_id = self.cleanup_picture(original_url, mask_url)
            
            # Step 3: Wait for completion
            print("⏳ Waiting for processing to complete...")
            result = self.wait_for_completion(order_id)
            
            return result
            
        except Exception as e:
            print(f"❌ Workflow failed: {str(e)}")
            raise
    
    def create_mask_from_coordinates(self, width: int, height: int, 
                                   coordinates: list) -> str:
        """
        Create a simple mask from coordinates (utility function)
        
        Args:
            width (int): Image width
            height (int): Image height
            coordinates (list): List of dicts with {x, y, width, height} for white areas
            
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
            
            # Draw white rectangles for areas to remove
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


def main():
    """
    Example usage of the LightX Cleanup API client
    """
    try:
        # Initialize with your API key
        lightx = LightXCleanupAPI("YOUR_API_KEY_HERE")
        
        # Option 1: Process with existing images
        result = lightx.process_cleanup(
            original_image_path="./path/to/original-image.jpg",  # Original image path
            mask_image_path="./path/to/mask-image.png",          # Mask image path
            content_type="image/jpeg"                            # Content type
        )
        
        print("🎉 Final result:", json.dumps(result, indent=2))
        
        # Option 2: Create mask from coordinates and process
        # mask_path = lightx.create_mask_from_coordinates(
        #     width=800, height=600,
        #     coordinates=[
        #         {"x": 100, "y": 100, "width": 200, "height": 150},  # Area to remove
        #         {"x": 400, "y": 300, "width": 100, "height": 100}   # Another area to remove
        #     ]
        # )
        # 
        # if mask_path != "mask-creation-failed":
        #     result = lightx.process_cleanup(
        #         original_image_path="./path/to/original-image.jpg",
        #         mask_image_path=mask_path,
        #         content_type="image/jpeg"
        #     )
        #     print("🎉 Final result with generated mask:", json.dumps(result, indent=2))
        
    except Exception as e:
        print(f"❌ Example failed: {str(e)}")


if __name__ == "__main__":
    main()
