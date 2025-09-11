"""
LightX AI Expand Photo (Outpainting) API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered photo expansion functionality using padding-based outpainting.
"""

import requests
import os
import time
import json
from typing import Optional, Dict, Any, Tuple
from pathlib import Path
from dataclasses import dataclass


@dataclass
class PaddingConfig:
    """Configuration for photo expansion padding"""
    left_padding: int = 0
    right_padding: int = 0
    top_padding: int = 0
    bottom_padding: int = 0
    
    def to_dict(self) -> Dict[str, int]:
        """Convert to dictionary for API request"""
        return {
            "leftPadding": self.left_padding,
            "rightPadding": self.right_padding,
            "topPadding": self.top_padding,
            "bottomPadding": self.bottom_padding
        }


class LightXExpandPhotoAPI:
    """
    LightX API client for AI photo expansion functionality
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Expand Photo API client
        
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
    
    def expand_photo(self, image_url: str, padding: PaddingConfig) -> str:
        """
        Expand photo using AI outpainting
        
        Args:
            image_url (str): URL of the uploaded image
            padding (PaddingConfig): Padding configuration
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If expansion request fails
        """
        try:
            endpoint = f"{self.base_url}/v1/expand-photo"
            headers = {
                "Content-Type": "application/json",
                "x-api-key": self.api_key
            }
            
            payload = {
                "imageUrl": image_url,
                **padding.to_dict()
            }
            
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            if data.get("statusCode") != 2000:
                raise requests.RequestException(f"Expand photo request failed: {data.get('message')}")
            
            order_info = data["body"]
            order_id = order_info["orderId"]
            max_retries = order_info["maxRetriesAllowed"]
            avg_response_time = order_info["avgResponseTimeInSec"]
            status = order_info["status"]
            
            print(f"📋 Order created: {order_id}")
            print(f"🔄 Max retries allowed: {max_retries}")
            print(f"⏱️  Average response time: {avg_response_time} seconds")
            print(f"📊 Status: {status}")
            print(f"📐 Padding: L:{padding.left_padding} R:{padding.right_padding} T:{padding.top_padding} B:{padding.bottom_padding}")
            
            return order_id
            
        except Exception as e:
            print(f"❌ Error expanding photo: {str(e)}")
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
                    print("✅ Photo expansion completed successfully!")
                    print(f"🖼️  Expanded image: {status['output']}")
                    return status
                    
                elif status["status"] == "failed":
                    raise Exception("Photo expansion failed")
                    
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
    
    def process_expansion(self, image_path: str, padding: PaddingConfig, 
                         content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Complete workflow: Upload image and expand photo
        
        Args:
            image_path (str): Path to the image file
            padding (PaddingConfig): Padding configuration
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        try:
            print("🚀 Starting LightX AI Expand Photo API workflow...")
            
            # Step 1: Upload image
            print("📤 Uploading image...")
            image_url = self.upload_image(image_path, content_type)
            print(f"✅ Image uploaded: {image_url}")
            
            # Step 2: Expand photo
            print("🖼️  Expanding photo with AI...")
            order_id = self.expand_photo(image_url, padding)
            
            # Step 3: Wait for completion
            print("⏳ Waiting for processing to complete...")
            result = self.wait_for_completion(order_id)
            
            return result
            
        except Exception as e:
            print(f"❌ Workflow failed: {str(e)}")
            raise
    
    def create_padding_config(self, direction: str, amount: int = 100, 
                            custom_padding: Optional[PaddingConfig] = None) -> PaddingConfig:
        """
        Create padding configuration for common expansion scenarios
        
        Args:
            direction (str): 'horizontal', 'vertical', 'all', or 'custom'
            amount (int): Padding amount in pixels
            custom_padding (PaddingConfig): Custom padding object (for 'custom' direction)
            
        Returns:
            PaddingConfig: Padding configuration
        """
        padding_configs = {
            "horizontal": PaddingConfig(
                left_padding=amount,
                right_padding=amount,
                top_padding=0,
                bottom_padding=0
            ),
            "vertical": PaddingConfig(
                left_padding=0,
                right_padding=0,
                top_padding=amount,
                bottom_padding=amount
            ),
            "all": PaddingConfig(
                left_padding=amount,
                right_padding=amount,
                top_padding=amount,
                bottom_padding=amount
            )
        }
        
        if direction == "custom":
            if custom_padding is None:
                raise ValueError("custom_padding must be provided for 'custom' direction")
            config = custom_padding
        else:
            config = padding_configs.get(direction)
            if config is None:
                raise ValueError(f"Invalid direction: {direction}. Use 'horizontal', 'vertical', 'all', or 'custom'")
        
        print(f"📐 Created {direction} padding config: {config}")
        return config
    
    def expand_to_aspect_ratio(self, image_path: str, target_width: int, target_height: int, 
                              content_type: str = "image/jpeg") -> Dict[str, Any]:
        """
        Expand photo to specific aspect ratio
        
        Args:
            image_path (str): Path to the image file
            target_width (int): Target width
            target_height (int): Target height
            content_type (str): MIME type
            
        Returns:
            Dict[str, Any]: Final result with output URL
        """
        try:
            print(f"🎯 Expanding to aspect ratio: {target_width}x{target_height}")
            print("⚠️  Note: This requires original image dimensions to calculate padding")
            
            # For demonstration, we'll use equal padding
            # In a real implementation, you'd calculate the required padding based on
            # the original image dimensions and target aspect ratio
            padding = self.create_padding_config("all", 100)
            return self.process_expansion(image_path, padding, content_type)
            
        except Exception as e:
            print(f"❌ Error expanding to aspect ratio: {str(e)}")
            raise
    
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
    Example usage of the LightX Expand Photo API client
    """
    try:
        # Initialize with your API key
        lightx = LightXExpandPhotoAPI("YOUR_API_KEY_HERE")
        
        # Example 1: Expand horizontally
        horizontal_padding = lightx.create_padding_config("horizontal", 150)
        result1 = lightx.process_expansion(
            image_path="./path/to/your/image.jpg",
            padding=horizontal_padding,
            content_type="image/jpeg"
        )
        print("🎉 Horizontal expansion result:", json.dumps(result1, indent=2))
        
        # Example 2: Expand vertically
        vertical_padding = lightx.create_padding_config("vertical", 200)
        result2 = lightx.process_expansion(
            image_path="./path/to/your/image.jpg",
            padding=vertical_padding,
            content_type="image/jpeg"
        )
        print("🎉 Vertical expansion result:", json.dumps(result2, indent=2))
        
        # Example 3: Expand all sides equally
        all_sides_padding = lightx.create_padding_config("all", 100)
        result3 = lightx.process_expansion(
            image_path="./path/to/your/image.jpg",
            padding=all_sides_padding,
            content_type="image/jpeg"
        )
        print("🎉 All-sides expansion result:", json.dumps(result3, indent=2))
        
        # Example 4: Custom padding
        custom_padding = PaddingConfig(
            left_padding=50,
            right_padding=200,
            top_padding=75,
            bottom_padding=125
        )
        result4 = lightx.process_expansion(
            image_path="./path/to/your/image.jpg",
            padding=custom_padding,
            content_type="image/jpeg"
        )
        print("🎉 Custom expansion result:", json.dumps(result4, indent=2))
        
        # Example 5: Get image dimensions
        width, height = lightx.get_image_dimensions("./path/to/your/image.jpg")
        if width > 0 and height > 0:
            print(f"📏 Original image: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {str(e)}")


if __name__ == "__main__":
    main()
