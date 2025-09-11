"""
LightX Remove Background API Integration - Python

This implementation provides a complete integration with LightX API v2
for image upload and background removal functionality using Python.
"""

import requests
import os
import time
import json
from typing import Optional, Dict, Any
from pathlib import Path


class LightXAPI:
    """
    LightX API client for background removal functionality
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX API client
        
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
    
    def remove_background(self, image_url: str, background: str = "transparent") -> str:
        """
        Remove background from image
        
        Args:
            image_url (str): URL of the uploaded image
            background (str): Background color, color code, or image URL
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If background removal request fails
        """
        try:
            endpoint = f"{self.base_url}/v1/remove-background"
            headers = {
                "Content-Type": "application/json",
                "x-api-key": self.api_key
            }
            
            payload = {
                "imageUrl": image_url,
                "background": background
            }
            
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            if data.get("statusCode") != 2000:
                raise requests.RequestException(f"Background removal request failed: {data.get('message')}")
            
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
            print(f"❌ Error removing background: {str(e)}")
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
            Dict[str, Any]: Final result with output URLs
            
        Raises:
            Exception: If maximum retries reached or processing failed
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Background removal completed successfully!")
                    print(f"🖼️  Output image: {status['output']}")
                    print(f"🎭 Mask image: {status['mask']}")
                    return status
                    
                elif status["status"] == "failed":
                    raise Exception("Background removal failed")
                    
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
    
    def process_image(self, image_path: str, background: str = "transparent", 
                     content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Complete workflow: Upload image and remove background
        
        Args:
            image_path (str): Path to the image file
            background (str): Background color/code/URL
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URLs
        """
        try:
            print("🚀 Starting LightX API workflow...")
            
            # Step 1: Upload image
            print("📤 Uploading image...")
            image_url = self.upload_image(image_path, content_type)
            print(f"✅ Image uploaded: {image_url}")
            
            # Step 2: Remove background
            print("🎨 Removing background...")
            order_id = self.remove_background(image_url, background)
            
            # Step 3: Wait for completion
            print("⏳ Waiting for processing to complete...")
            result = self.wait_for_completion(order_id)
            
            return result
            
        except Exception as e:
            print(f"❌ Workflow failed: {str(e)}")
            raise


def main():
    """
    Example usage of the LightX API client
    """
    try:
        # Initialize with your API key
        lightx = LightXAPI("YOUR_API_KEY_HERE")
        
        # Process an image
        result = lightx.process_image(
            image_path="./path/to/your/image.jpg",  # Image path
            background="white",                     # Background color
            content_type="image/jpeg"               # Content type
        )
        
        print("🎉 Final result:", json.dumps(result, indent=2))
        
    except Exception as e:
        print(f"❌ Example failed: {str(e)}")


if __name__ == "__main__":
    main()
