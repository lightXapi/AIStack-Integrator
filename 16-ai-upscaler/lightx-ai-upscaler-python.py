"""
LightX AI Image Upscaler API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered image upscaling functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXImageUpscalerAPI:
    """
    LightX AI Image Upscaler API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Image Upscaler API client
        
        Args:
            api_key (str): Your LightX API key
        """
        self.api_key = api_key
        self.base_url = "https://api.lightxeditor.com/external/api"
        self.max_retries = 5
        self.retry_interval = 3  # seconds
        self.max_file_size = 5242880  # 5MB
        self.max_image_dimension = 2048  # Maximum dimension for upscaling
        
    def upload_image(self, image_data: Union[str, bytes, Path], content_type: str = "image/jpeg") -> str:
        """
        Upload image to LightX servers
        
        Args:
            image_data: Image file path, bytes, or Path object
            content_type: MIME type (image/jpeg or image/png)
            
        Returns:
            str: Final image URL
            
        Raises:
            LightXUpscalerException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise LightXUpscalerException(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
            file_size = image_path.stat().st_size
        elif isinstance(image_data, bytes):
            image_bytes = image_data
            file_size = len(image_bytes)
        else:
            raise LightXUpscalerException("Invalid image data provided")
        
        if file_size > self.max_file_size:
            raise LightXUpscalerException("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def generate_upscale(self, image_url: str, quality: int) -> str:
        """
        Generate image upscaling
        
        Args:
            image_url: URL of the input image
            quality: Upscaling quality (2 or 4)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXUpscalerException: If request fails
        """
        endpoint = f"{self.base_url}/v2/upscale/"
        
        payload = {
            "imageUrl": image_url,
            "quality": quality
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
                raise LightXUpscalerException(f"Upscale request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🔍 Upscale quality: {quality}x")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXUpscalerException(f"Network error: {e}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXUpscalerException: If request fails
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
                raise LightXUpscalerException(f"Status check failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXUpscalerException(f"Network error: {e}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXUpscalerException: If maximum retries reached or processing fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Image upscaling completed successfully!")
                    if status.get("output"):
                        print(f"🔍 Upscaled image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXUpscalerException("Image upscaling failed")
                
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
        
        raise LightXUpscalerException("Maximum retry attempts reached")
    
    def process_upscaling(self, image_data: Union[str, bytes, Path], quality: int, content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and generate upscaling
        
        Args:
            image_data: Image file path, bytes, or Path object
            quality: Upscaling quality (2 or 4)
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Image Upscaler API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Generate upscaling
        print("🔍 Generating image upscaling...")
        order_id = self.generate_upscale(image_url, quality)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_upscaling_tips(self) -> Dict[str, List[str]]:
        """
        Get image upscaling tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit images with good contrast",
                "Ensure the image is not already at maximum resolution",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best upscaling results",
                "Good source image quality improves upscaling results"
            ],
            "image_dimensions": [
                "Images 1024x1024 or smaller can be upscaled 2x or 4x",
                "Images larger than 1024x1024 but smaller than 2048x2048 can only be upscaled 2x",
                "Images larger than 2048x2048 cannot be upscaled",
                "Check image dimensions before attempting upscaling",
                "Resize large images before upscaling if needed"
            ],
            "quality_selection": [
                "Use 2x upscaling for moderate quality improvement",
                "Use 4x upscaling for maximum quality improvement",
                "4x upscaling works best on smaller images (1024x1024 or less)",
                "Consider file size increase with higher upscaling factors",
                "Choose quality based on your specific needs"
            ],
            "general": [
                "Image upscaling works best with clear, detailed source images",
                "Results may vary based on input image quality and content",
                "Upscaling preserves detail while enhancing resolution",
                "Allow 15-30 seconds for processing",
                "Experiment with different quality settings for optimal results"
            ]
        }
        
        print("💡 Image Upscaling Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_quality_suggestions(self) -> Dict[str, Dict]:
        """
        Get upscaling quality suggestions
        
        Returns:
            Dict: Object containing quality suggestions
        """
        quality_suggestions = {
            "2x": {
                "description": "Moderate quality improvement with 2x resolution increase",
                "best_for": [
                    "General image enhancement",
                    "Social media images",
                    "Web display images",
                    "Moderate quality improvement needs",
                    "Balanced quality and file size"
                ],
                "use_cases": [
                    "Enhancing photos for social media",
                    "Improving web images",
                    "General image quality enhancement",
                    "Preparing images for print at moderate sizes"
                ]
            },
            "4x": {
                "description": "Maximum quality improvement with 4x resolution increase",
                "best_for": [
                    "High-quality image enhancement",
                    "Print-ready images",
                    "Professional photography",
                    "Maximum detail preservation",
                    "Large format displays"
                ],
                "use_cases": [
                    "Professional photography enhancement",
                    "Print-ready image preparation",
                    "Large format display images",
                    "Maximum quality requirements",
                    "Archival image enhancement"
                ]
            }
        }
        
        print("💡 Upscaling Quality Suggestions:")
        for quality, suggestion in quality_suggestions.items():
            print(f"{quality}: {suggestion['description']}")
            print(f"  Best for: {', '.join(suggestion['best_for'])}")
            print(f"  Use cases: {', '.join(suggestion['use_cases'])}")
        return quality_suggestions
    
    def get_dimension_guidelines(self) -> Dict[str, Dict]:
        """
        Get image dimension guidelines
        
        Returns:
            Dict: Object containing dimension guidelines
        """
        dimension_guidelines = {
            "small_images": {
                "range": "Up to 1024x1024 pixels",
                "upscaling_options": ["2x upscaling", "4x upscaling"],
                "description": "Small images can be upscaled with both 2x and 4x quality",
                "examples": ["Profile pictures", "Thumbnails", "Small photos", "Icons"]
            },
            "medium_images": {
                "range": "1024x1024 to 2048x2048 pixels",
                "upscaling_options": ["2x upscaling only"],
                "description": "Medium images can only be upscaled with 2x quality",
                "examples": ["Standard photos", "Web images", "Medium prints"]
            },
            "large_images": {
                "range": "Larger than 2048x2048 pixels",
                "upscaling_options": ["Cannot be upscaled"],
                "description": "Large images cannot be upscaled and will show an error",
                "examples": ["High-resolution photos", "Large prints", "Professional images"]
            }
        }
        
        print("💡 Image Dimension Guidelines:")
        for category, info in dimension_guidelines.items():
            print(f"{category}: {info['range']}")
            print(f"  Upscaling options: {', '.join(info['upscaling_options'])}")
            print(f"  Description: {info['description']}")
            print(f"  Examples: {', '.join(info['examples'])}")
        return dimension_guidelines
    
    def get_upscaling_use_cases(self) -> Dict[str, List[str]]:
        """
        Get upscaling use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "photography": [
                "Enhance low-resolution photos",
                "Prepare images for large prints",
                "Improve vintage photo quality",
                "Enhance smartphone photos",
                "Professional photo enhancement"
            ],
            "web_design": [
                "Create high-DPI images for retina displays",
                "Enhance images for modern web standards",
                "Improve image quality for websites",
                "Create responsive image assets",
                "Enhance social media images"
            ],
            "print_media": [
                "Prepare images for large format printing",
                "Enhance images for magazine quality",
                "Improve poster and banner images",
                "Enhance images for professional printing",
                "Create high-resolution marketing materials"
            ],
            "archival": [
                "Enhance historical photographs",
                "Improve scanned document quality",
                "Restore old family photos",
                "Enhance archival images",
                "Preserve and enhance historical content"
            ],
            "creative": [
                "Enhance digital art and illustrations",
                "Improve concept art quality",
                "Enhance graphic design elements",
                "Improve texture and pattern quality",
                "Enhance creative project assets"
            ]
        }
        
        print("💡 Upscaling Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
    def validate_parameters(self, quality: int, width: int, height: int) -> bool:
        """
        Validate parameters
        
        Args:
            quality: Quality parameter to validate
            width: Image width to validate
            height: Image height to validate
            
        Returns:
            bool: Whether the parameters are valid
        """
        # Validate quality
        if quality not in [2, 4]:
            print("❌ Quality must be 2 or 4")
            return False
        
        # Validate image dimensions
        max_dimension = max(width, height)
        
        if max_dimension > self.max_image_dimension:
            print(f"❌ Image dimension ({max_dimension}px) exceeds maximum allowed ({self.max_image_dimension}px)")
            return False
        
        # Check quality vs dimension compatibility
        if max_dimension > 1024 and quality == 4:
            print("❌ 4x upscaling is only available for images 1024x1024 or smaller")
            return False
        
        print("✅ Parameters are valid")
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
    
    def generate_upscale_with_validation(self, image_data: Union[str, bytes, Path], quality: int, content_type: str = "image/jpeg") -> Dict:
        """
        Generate upscaling with parameter validation
        
        Args:
            image_data: Image file path, bytes, or Path object
            quality: Upscaling quality (2 or 4)
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        # Get image dimensions for validation
        width, height = self.get_image_dimensions(image_data)
        
        if not self.validate_parameters(quality, width, height):
            raise LightXUpscalerException("Invalid parameters")
        
        return self.process_upscaling(image_data, quality, content_type)
    
    def get_recommended_quality(self, width: int, height: int) -> Dict:
        """
        Get recommended quality based on image dimensions
        
        Args:
            width: Image width
            height: Image height
            
        Returns:
            Dict: Recommended quality options
        """
        max_dimension = max(width, height)
        
        if max_dimension <= 1024:
            return {
                "available": [2, 4],
                "recommended": 4,
                "reason": "Small images can use 4x upscaling for maximum quality"
            }
        elif max_dimension <= 2048:
            return {
                "available": [2],
                "recommended": 2,
                "reason": "Medium images can only use 2x upscaling"
            }
        else:
            return {
                "available": [],
                "recommended": None,
                "reason": "Large images cannot be upscaled"
            }
    
    def get_quality_comparison(self) -> Dict[str, Dict]:
        """
        Get detailed quality comparison between 2x and 4x upscaling
        
        Returns:
            Dict: Detailed comparison information
        """
        comparison = {
            "2x_upscaling": {
                "resolution_increase": "4x total pixels (2x width × 2x height)",
                "file_size_increase": "Approximately 4x larger",
                "processing_time": "Faster processing",
                "best_for": [
                    "General image enhancement",
                    "Web and social media use",
                    "Moderate quality improvement",
                    "Balanced quality and file size"
                ],
                "limitations": [
                    "Less dramatic quality improvement",
                    "May not be sufficient for large prints"
                ]
            },
            "4x_upscaling": {
                "resolution_increase": "16x total pixels (4x width × 4x height)",
                "file_size_increase": "Approximately 16x larger",
                "processing_time": "Longer processing time",
                "best_for": [
                    "Maximum quality enhancement",
                    "Large format printing",
                    "Professional photography",
                    "Archival image enhancement"
                ],
                "limitations": [
                    "Only available for images ≤1024x1024",
                    "Much larger file sizes",
                    "Longer processing time"
                ]
            }
        }
        
        print("💡 Quality Comparison:")
        for quality, info in comparison.items():
            print(f"{quality}:")
            for key, value in info.items():
                if isinstance(value, list):
                    print(f"  {key}: {', '.join(value)}")
                else:
                    print(f"  {key}: {value}")
        return comparison
    
    def get_technical_specifications(self) -> Dict[str, Dict]:
        """
        Get technical specifications for upscaling
        
        Returns:
            Dict: Technical specifications
        """
        specifications = {
            "supported_formats": {
                "input": ["JPEG", "PNG"],
                "output": ["JPEG"],
                "color_spaces": ["RGB", "sRGB"]
            },
            "size_limits": {
                "max_file_size": "5MB",
                "max_dimension": "2048px",
                "min_dimension": "1px"
            },
            "quality_options": {
                "2x": "Available for all supported image sizes",
                "4x": "Only available for images ≤1024x1024px"
            },
            "processing": {
                "max_retries": 5,
                "retry_interval": "3 seconds",
                "avg_processing_time": "15-30 seconds",
                "timeout": "No timeout limit"
            }
        }
        
        print("💡 Technical Specifications:")
        for category, specs in specifications.items():
            print(f"{category}: {specs}")
        return specifications
    
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
                raise LightXUpscalerException(f"Upload URL request failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXUpscalerException(f"Network error: {e}")
    
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
            raise LightXUpscalerException(f"Upload error: {e}")


class LightXUpscalerException(Exception):
    """Custom exception for LightX Upscaler API errors"""
    pass


def run_example():
    """Example usage of the LightX Image Upscaler API"""
    try:
        # Initialize with your API key
        lightx = LightXImageUpscalerAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_upscaling_tips()
        lightx.get_quality_suggestions()
        lightx.get_dimension_guidelines()
        lightx.get_upscaling_use_cases()
        lightx.get_quality_comparison()
        lightx.get_technical_specifications()
        
        # Load image (replace with your image path)
        image_path = "path/to/input-image.jpg"
        
        # Example 1: 2x upscaling
        result1 = lightx.generate_upscale_with_validation(
            image_path,
            2,  # 2x upscaling
            "image/jpeg"
        )
        print("🎉 2x upscaling result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: 4x upscaling (if image is small enough)
        width, height = lightx.get_image_dimensions(image_path)
        quality_recommendation = lightx.get_recommended_quality(width, height)
        
        if 4 in quality_recommendation["available"]:
            result2 = lightx.generate_upscale_with_validation(
                image_path,
                4,  # 4x upscaling
                "image/jpeg"
            )
            print("🎉 4x upscaling result:")
            print(f"Order ID: {result2['orderId']}")
            print(f"Status: {result2['status']}")
            if result2.get("output"):
                print(f"Output: {result2['output']}")
        else:
            print(f"⚠️  4x upscaling not available for this image size: {quality_recommendation['reason']}")
        
        # Example 3: Try different quality settings
        quality_options = [2, 4]
        for quality in quality_options:
            try:
                result = lightx.generate_upscale_with_validation(
                    image_path,
                    quality,
                    "image/jpeg"
                )
                print(f"🎉 {quality}x upscaling result:")
                print(f"Order ID: {result['orderId']}")
                print(f"Status: {result['status']}")
                if result.get("output"):
                    print(f"Output: {result['output']}")
            except Exception as e:
                print(f"❌ {quality}x upscaling failed: {e}")
        
        # Example 4: Get image dimensions and recommendations
        final_width, final_height = lightx.get_image_dimensions(image_path)
        if final_width > 0 and final_height > 0:
            print(f"📏 Original image: {final_width}x{final_height}")
            recommendation = lightx.get_recommended_quality(final_width, final_height)
            print(f"💡 Recommended quality: {recommendation['recommended']}x ({recommendation['reason']})")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
