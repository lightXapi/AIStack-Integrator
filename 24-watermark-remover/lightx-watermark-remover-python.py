"""
LightX Watermark Remover API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered watermark removal functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Union
from pathlib import Path


class LightXWatermarkRemoverAPI:
    """
    LightX Watermark Remover API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Watermark Remover API client
        
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
            LightXWatermarkRemoverException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise LightXWatermarkRemoverException(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
            file_size = image_path.stat().st_size
        elif isinstance(image_data, bytes):
            image_bytes = image_data
            file_size = len(image_bytes)
        else:
            raise LightXWatermarkRemoverException("Invalid image data provided")
        
        if file_size > self.max_file_size:
            raise LightXWatermarkRemoverException("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def remove_watermark(self, image_url: str) -> str:
        """
        Remove watermark from image
        
        Args:
            image_url: URL of the input image
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXWatermarkRemoverException: If request fails
        """
        endpoint = f"{self.base_url}/v2/watermark-remover/"
        
        payload = {
            "imageUrl": image_url
        }
        
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers, timeout=60)
            response.raise_for_status()
            
            data = response.json()
            
            if data.get("statusCode") != 2000:
                raise LightXWatermarkRemoverException(f"Watermark removal request failed: {data.get('message', 'Unknown error')}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🖼️  Input image: {image_url}")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXWatermarkRemoverException(f"Network error: {str(e)}")
        except KeyError as e:
            raise LightXWatermarkRemoverException(f"Unexpected response format: {str(e)}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXWatermarkRemoverException: If request fails
        """
        endpoint = f"{self.base_url}/v2/order-status"
        
        payload = {"orderId": order_id}
        
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers, timeout=60)
            response.raise_for_status()
            
            data = response.json()
            
            if data.get("statusCode") != 2000:
                raise LightXWatermarkRemoverException(f"Status check failed: {data.get('message', 'Unknown error')}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXWatermarkRemoverException(f"Network error: {str(e)}")
        except KeyError as e:
            raise LightXWatermarkRemoverException(f"Unexpected response format: {str(e)}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXWatermarkRemoverException: If maximum retries reached or job fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Watermark removed successfully!")
                    if status.get("output"):
                        print(f"🖼️  Clean image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXWatermarkRemoverException("Watermark removal failed")
                
                elif status["status"] == "init":
                    attempts += 1
                    if attempts < self.max_retries:
                        print(f"⏳ Waiting {self.retry_interval} seconds before next check...")
                        time.sleep(self.retry_interval)
                
                else:
                    attempts += 1
                    if attempts < self.max_retries:
                        time.sleep(self.retry_interval)
                
            except LightXWatermarkRemoverException:
                raise
            except Exception as e:
                attempts += 1
                if attempts >= self.max_retries:
                    raise LightXWatermarkRemoverException(f"Maximum retry attempts reached: {str(e)}")
                print(f"⚠️  Error on attempt {attempts}, retrying...")
                time.sleep(self.retry_interval)
        
        raise LightXWatermarkRemoverException("Maximum retry attempts reached")
    
    def process_watermark_removal(self, image_data: Union[str, bytes, Path], content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and remove watermark
        
        Args:
            image_data: Image file path, bytes, or Path object
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX Watermark Remover API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Remove watermark
        print("🧹 Removing watermark...")
        order_id = self.remove_watermark(image_url)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_watermark_removal_tips(self) -> Dict:
        """
        Get watermark removal tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "input_image": [
                "Use high-quality images with clear watermarks",
                "Ensure the image is at least 512x512 pixels",
                "Avoid heavily compressed or low-quality source images",
                "Use images with good contrast and lighting",
                "Ensure watermarks are clearly visible in the image"
            ],
            "watermark_types": [
                "Text watermarks: Works best with clear, readable text",
                "Logo watermarks: Effective with distinct logo shapes",
                "Pattern watermarks: Good for repetitive patterns",
                "Transparent watermarks: Handles semi-transparent overlays",
                "Complex watermarks: May require multiple processing attempts"
            ],
            "image_quality": [
                "Higher resolution images produce better results",
                "Good lighting and contrast improve watermark detection",
                "Avoid images with excessive noise or artifacts",
                "Clear, sharp images work better than blurry ones",
                "Well-exposed images provide better results"
            ],
            "general": [
                "AI watermark removal works best with clearly visible watermarks",
                "Results may vary based on watermark complexity and image quality",
                "Allow 15-30 seconds for processing",
                "Some watermarks may require multiple processing attempts",
                "The tool preserves image quality while removing watermarks"
            ]
        }
        
        print("💡 Watermark Removal Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        
        return tips
    
    def get_watermark_removal_use_cases(self) -> Dict:
        """
        Get watermark removal use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "e_commerce": [
                "Remove watermarks from product photos",
                "Clean up stock images for online stores",
                "Prepare images for product catalogs",
                "Remove branding from supplier images",
                "Create clean product listings"
            ],
            "photo_editing": [
                "Remove watermarks from edited photos",
                "Clean up images for personal use",
                "Remove copyright watermarks",
                "Prepare images for printing",
                "Clean up stock photo watermarks"
            ],
            "news_publishing": [
                "Remove watermarks from news images",
                "Clean up press photos",
                "Remove agency watermarks",
                "Prepare images for articles",
                "Clean up editorial images"
            ],
            "social_media": [
                "Remove watermarks from social media images",
                "Clean up images for posts",
                "Remove branding from shared images",
                "Prepare images for profiles",
                "Clean up user-generated content"
            ],
            "creative_projects": [
                "Remove watermarks from design assets",
                "Clean up images for presentations",
                "Remove branding from templates",
                "Prepare images for portfolios",
                "Clean up creative resources"
            ]
        }
        
        print("💡 Watermark Removal Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        
        return use_cases
    
    def get_supported_formats(self) -> Dict:
        """
        Get supported image formats and requirements
        
        Returns:
            Dict: Object containing format information
        """
        formats = {
            "input_formats": {
                "JPEG": "Most common format, good for photos",
                "PNG": "Supports transparency, good for graphics",
                "WebP": "Modern format with good compression"
            },
            "output_format": {
                "JPEG": "Standard output format for compatibility"
            },
            "requirements": {
                "minimum_size": "512x512 pixels",
                "maximum_size": "5MB file size",
                "color_space": "RGB or sRGB",
                "compression": "Any standard compression level"
            },
            "recommendations": {
                "resolution": "Higher resolution images produce better results",
                "quality": "Use high-quality source images",
                "format": "JPEG is recommended for photos",
                "size": "Larger images allow better watermark detection"
            }
        }
        
        print("📋 Supported Formats and Requirements:")
        for category, info in formats.items():
            print(f"{category}: {json.dumps(info, indent=2)}")
        
        return formats
    
    def get_watermark_detection_capabilities(self) -> Dict:
        """
        Get watermark detection capabilities
        
        Returns:
            Dict: Object containing detection information
        """
        capabilities = {
            "detection_types": [
                "Text watermarks with various fonts and styles",
                "Logo watermarks with different shapes and colors",
                "Pattern watermarks with repetitive designs",
                "Transparent watermarks with varying opacity",
                "Complex watermarks with multiple elements"
            ],
            "coverage_areas": [
                "Full image watermarks covering the entire image",
                "Corner watermarks in specific image areas",
                "Center watermarks in the middle of images",
                "Scattered watermarks across multiple areas",
                "Border watermarks along image edges"
            ],
            "processing_features": [
                "Automatic watermark detection and removal",
                "Preserves original image quality and details",
                "Maintains image composition and structure",
                "Handles various watermark sizes and positions",
                "Works with different image backgrounds"
            ],
            "limitations": [
                "Very small or subtle watermarks may be challenging",
                "Watermarks that blend with image content",
                "Extremely complex or artistic watermarks",
                "Watermarks that are part of the main subject",
                "Very low resolution or poor quality images"
            ]
        }
        
        print("🔍 Watermark Detection Capabilities:")
        for category, capability_list in capabilities.items():
            print(f"{category}: {capability_list}")
        
        return capabilities
    
    def validate_image_data(self, image_data: Union[str, bytes, Path]) -> bool:
        """
        Validate image data (utility function)
        
        Args:
            image_data: Image data to validate
            
        Returns:
            bool: Whether the image data is valid
        """
        if not image_data:
            print("❌ Image data cannot be empty")
            return False
        
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                print("❌ Image file does not exist")
                return False
            file_size = image_path.stat().st_size
            if file_size > self.max_file_size:
                print("❌ Image file size exceeds 5MB limit")
                return False
        elif isinstance(image_data, bytes):
            if len(image_data) == 0:
                print("❌ Image buffer is empty")
                return False
            if len(image_data) > self.max_file_size:
                print("❌ Image size exceeds 5MB limit")
                return False
        else:
            print("❌ Invalid image data type")
            return False
        
        print("✅ Image data is valid")
        return True
    
    def process_watermark_removal_with_validation(self, image_data: Union[str, bytes, Path], content_type: str = "image/jpeg") -> Dict:
        """
        Process watermark removal with validation
        
        Args:
            image_data: Image file path, bytes, or Path object
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_image_data(image_data):
            raise LightXWatermarkRemoverException("Invalid image data")
        
        return self.process_watermark_removal(image_data, content_type)
    
    def _get_upload_url(self, file_size: int, content_type: str) -> Dict:
        """
        Get upload URL from LightX API
        
        Args:
            file_size: Size of the file in bytes
            content_type: MIME type of the file
            
        Returns:
            Dict: Upload URL information
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
            response = requests.post(endpoint, json=payload, headers=headers, timeout=60)
            response.raise_for_status()
            
            data = response.json()
            
            if data.get("statusCode") != 2000:
                raise LightXWatermarkRemoverException(f"Upload URL request failed: {data.get('message', 'Unknown error')}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXWatermarkRemoverException(f"Network error: {str(e)}")
        except KeyError as e:
            raise LightXWatermarkRemoverException(f"Unexpected response format: {str(e)}")
    
    def _upload_to_s3(self, upload_url: str, image_bytes: bytes, content_type: str) -> None:
        """
        Upload image to S3 using pre-signed URL
        
        Args:
            upload_url: Pre-signed S3 upload URL
            image_bytes: Image data as bytes
            content_type: MIME type of the image
        """
        try:
            response = requests.put(upload_url, data=image_bytes, headers={"Content-Type": content_type}, timeout=60)
            response.raise_for_status()
            
        except requests.exceptions.RequestException as e:
            raise LightXWatermarkRemoverException(f"Upload error: {str(e)}")


class LightXWatermarkRemoverException(Exception):
    """Custom exception for LightX Watermark Remover API errors"""
    pass


# Example usage
def run_example():
    try:
        # Initialize with your API key
        lightx = LightXWatermarkRemoverAPI("YOUR_API_KEY_HERE")
        
        # Get tips and information
        lightx.get_watermark_removal_tips()
        lightx.get_watermark_removal_use_cases()
        lightx.get_supported_formats()
        lightx.get_watermark_detection_capabilities()
        
        # Example 1: Process image from file path
        result1 = lightx.process_watermark_removal_with_validation(
            "path/to/watermarked-image.jpg",
            "image/jpeg"
        )
        print("🎉 Watermark removal result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Process image from bytes
        with open("path/to/another-image.png", "rb") as f:
            image_bytes = f.read()
        result2 = lightx.process_watermark_removal_with_validation(
            image_bytes,
            "image/png"
        )
        print("🎉 Second watermark removal result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Process multiple images
        image_paths = [
            "path/to/image1.jpg",
            "path/to/image2.png",
            "path/to/image3.jpg"
        ]
        
        for image_path in image_paths:
            result = lightx.process_watermark_removal_with_validation(
                image_path,
                "image/jpeg"
            )
            print(f"🎉 {image_path} watermark removal result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
    
    except LightXWatermarkRemoverException as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
