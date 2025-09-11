"""
LightX AI Face Swap API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered face swap functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXFaceSwapAPI:
    """
    LightX AI Face Swap API client for seamless face swapping in photos
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Face Swap API client
        
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
    
    def upload_images(self, source_image_data: Union[bytes, str, Path], 
                     target_image_data: Union[bytes, str, Path],
                     content_type: str = "image/jpeg") -> Tuple[str, str]:
        """
        Upload multiple images (source and target images)
        
        Args:
            source_image_data: Source image data
            target_image_data: Target image data
            content_type: MIME type
            
        Returns:
            Tuple of (source_url, target_url)
        """
        print("📤 Uploading source image...")
        source_url = self.upload_image(source_image_data, content_type)
        
        print("📤 Uploading target image...")
        target_url = self.upload_image(target_image_data, content_type)
        
        return source_url, target_url
    
    def generate_face_swap(self, image_url: str, style_image_url: str) -> str:
        """
        Generate face swap
        
        Args:
            image_url: URL of the source image (face to be swapped)
            style_image_url: URL of the target image (face to be replaced)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/face-swap"
        
        payload = {
            "imageUrl": image_url,
            "styleImageUrl": style_image_url
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
                raise requests.RequestException(f"Face swap request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🎭 Source image: {image_url}")
            print(f"🎯 Target image: {style_image_url}")
            
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
                    print("✅ Face swap completed successfully!")
                    if status.get("output"):
                        print(f"🔄 Face swap result: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Face swap failed")
                
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
    
    def process_face_swap(self, source_image_data: Union[bytes, str, Path],
                         target_image_data: Union[bytes, str, Path],
                         content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload images and generate face swap
        
        Args:
            source_image_data: Source image data
            target_image_data: Target image data
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Face Swap API workflow...")
        
        # Step 1: Upload images
        print("📤 Uploading images...")
        source_url, target_url = self.upload_images(source_image_data, target_image_data, content_type)
        print(f"✅ Source image uploaded: {source_url}")
        print(f"✅ Target image uploaded: {target_url}")
        
        # Step 2: Generate face swap
        print("🔄 Generating face swap...")
        order_id = self.generate_face_swap(source_url, target_url)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_face_swap_tips(self) -> Dict[str, List[str]]:
        """
        Get face swap tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better face swap results
        """
        tips = {
            "source_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the face is clearly visible and centered",
                "Avoid photos with multiple people",
                "Use high-resolution images for better results",
                "Front-facing photos work best for face swapping"
            ],
            "target_image": [
                "Choose target images with clear facial features",
                "Ensure target face is clearly visible and well-lit",
                "Use images with similar lighting conditions",
                "Avoid heavily edited or filtered images",
                "Use high-quality target reference images"
            ],
            "general": [
                "Face swaps work best with clear human faces",
                "Results may vary based on input image quality",
                "Similar lighting conditions improve results",
                "Front-facing photos produce better face swaps",
                "Allow 15-30 seconds for processing"
            ]
        }
        
        print("💡 Face Swap Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_face_swap_use_cases(self) -> Dict[str, List[str]]:
        """
        Get face swap use cases and examples
        
        Returns:
            Dict[str, List[str]]: Dictionary of use case examples
        """
        use_cases = {
            "entertainment": [
                "Movie character face swaps",
                "Celebrity face swaps",
                "Historical figure face swaps",
                "Fantasy character face swaps",
                "Comedy and entertainment content"
            ],
            "creative": [
                "Artistic face swap projects",
                "Creative photo manipulation",
                "Digital art creation",
                "Social media content",
                "Memes and viral content"
            ],
            "professional": [
                "Film and video production",
                "Marketing and advertising",
                "Educational content",
                "Training materials",
                "Presentation graphics"
            ],
            "personal": [
                "Fun personal photos",
                "Family photo editing",
                "Social media posts",
                "Party and event photos",
                "Creative selfies"
            ]
        }
        
        print("💡 Face Swap Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
    def get_face_swap_quality_tips(self) -> Dict[str, List[str]]:
        """
        Get face swap quality improvement tips
        
        Returns:
            Dict[str, List[str]]: Dictionary of quality improvement tips
        """
        quality_tips = {
            "lighting": [
                "Use similar lighting conditions in both images",
                "Avoid harsh shadows on faces",
                "Ensure even lighting across the face",
                "Natural lighting often produces better results",
                "Avoid backlit or silhouette images"
            ],
            "angle": [
                "Use front-facing photos for best results",
                "Avoid extreme angles or tilted heads",
                "Keep faces centered in the frame",
                "Similar head angles improve face swap quality",
                "Avoid profile shots for optimal results"
            ],
            "resolution": [
                "Use high-resolution images when possible",
                "Ensure clear facial features are visible",
                "Avoid heavily compressed images",
                "Good image quality improves face swap accuracy",
                "Minimum 512x512 pixels recommended"
            ],
            "expression": [
                "Neutral expressions often work best",
                "Similar facial expressions improve results",
                "Avoid extreme expressions or emotions",
                "Natural expressions produce better face swaps",
                "Consider the context of the target image"
            ]
        }
        
        print("💡 Face Swap Quality Tips:")
        for category, tip_list in quality_tips.items():
            print(f"{category}: {tip_list}")
        return quality_tips
    
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
    
    def validate_images_for_face_swap(self, source_image_data: Union[bytes, str, Path], 
                                     target_image_data: Union[bytes, str, Path]) -> bool:
        """
        Validate images for face swap (utility function)
        
        Args:
            source_image_data: Source image data
            target_image_data: Target image data
            
        Returns:
            bool: Whether images are valid for face swap
        """
        try:
            # Check if both images exist and are valid
            source_dimensions = self.get_image_dimensions(source_image_data)
            target_dimensions = self.get_image_dimensions(target_image_data)
            
            if source_dimensions[0] == 0 or target_dimensions[0] == 0:
                print("❌ Invalid image dimensions detected")
                return False
            
            # Additional validation could include:
            # - Face detection
            # - Image quality assessment
            # - Lighting condition analysis
            # - Resolution requirements
            
            print("✅ Images validated for face swap")
            return True
            
        except Exception as e:
            print(f"❌ Image validation failed: {e}")
            return False
    
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
    Example usage of the LightX Face Swap API
    """
    try:
        # Initialize with your API key
        lightx = LightXFaceSwapAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_face_swap_tips()
        lightx.get_face_swap_use_cases()
        lightx.get_face_swap_quality_tips()
        
        # Load images (replace with your image loading logic)
        source_image_path = "path/to/source-image.jpg"
        target_image_path = "path/to/target-image.jpg"
        
        # Validate images before processing
        is_valid = lightx.validate_images_for_face_swap(source_image_path, target_image_path)
        if not is_valid:
            print("❌ Images are not suitable for face swap")
            return
        
        # Example 1: Basic face swap
        result1 = lightx.process_face_swap(
            source_image_path,
            target_image_path,
            "image/jpeg"
        )
        print("🎉 Face swap result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Get image dimensions
        source_dimensions = lightx.get_image_dimensions(source_image_path)
        target_dimensions = lightx.get_image_dimensions(target_image_path)
        
        if source_dimensions[0] > 0 and source_dimensions[1] > 0:
            print(f"📏 Source image: {source_dimensions[0]}x{source_dimensions[1]}")
        if target_dimensions[0] > 0 and target_dimensions[1] > 0:
            print(f"📏 Target image: {target_dimensions[0]}x{target_dimensions[1]}")
        
        # Example 3: Multiple face swaps with different image pairs
        image_pairs = [
            {"source": "path/to/source1.jpg", "target": "path/to/target1.jpg"},
            {"source": "path/to/source2.jpg", "target": "path/to/target2.jpg"},
            {"source": "path/to/source3.jpg", "target": "path/to/target3.jpg"}
        ]
        
        for i, pair in enumerate(image_pairs):
            print(f"\n🎭 Processing face swap {i + 1}...")
            
            try:
                result = lightx.process_face_swap(
                    pair["source"],
                    pair["target"],
                    "image/jpeg"
                )
                print(f"✅ Face swap {i + 1} completed:")
                print(f"Order ID: {result['orderId']}")
                print(f"Status: {result['status']}")
                if result.get("output"):
                    print(f"Output: {result['output']}")
            except Exception as error:
                print(f"❌ Face swap {i + 1} failed: {error}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
