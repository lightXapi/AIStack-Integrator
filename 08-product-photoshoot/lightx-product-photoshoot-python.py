"""
LightX AI Product Photoshoot API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered product photoshoot functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXProductPhotoshootAPI:
    """
    LightX AI Product Photoshoot API client for generating professional product photos
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Product Photoshoot API client
        
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
    
    def upload_images(self, product_image_data: Union[bytes, str, Path], 
                     style_image_data: Optional[Union[bytes, str, Path]] = None,
                     content_type: str = "image/jpeg") -> Tuple[str, Optional[str]]:
        """
        Upload multiple images (product and optional style image)
        
        Args:
            product_image_data: Product image data
            style_image_data: Style image data (optional)
            content_type: MIME type
            
        Returns:
            Tuple of (product_url, style_url)
        """
        print("📤 Uploading product image...")
        product_url = self.upload_image(product_image_data, content_type)
        
        style_url = None
        if style_image_data:
            print("📤 Uploading style image...")
            style_url = self.upload_image(style_image_data, content_type)
        
        return product_url, style_url
    
    def generate_product_photoshoot(self, image_url: str, style_image_url: Optional[str] = None, 
                                   text_prompt: Optional[str] = None) -> str:
        """
        Generate product photoshoot
        
        Args:
            image_url: URL of the product image
            style_image_url: URL of the style image (optional)
            text_prompt: Text prompt for photoshoot style (optional)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/product-photoshoot"
        
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
                raise requests.RequestException(f"Product photoshoot request failed: {data['message']}")
            
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
                    print("✅ Product photoshoot completed successfully!")
                    if status.get("output"):
                        print(f"📸 Product photoshoot image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Product photoshoot failed")
                
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
    
    def process_product_photoshoot(self, product_image_data: Union[bytes, str, Path],
                                  style_image_data: Optional[Union[bytes, str, Path]] = None,
                                  text_prompt: Optional[str] = None,
                                  content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload images and generate product photoshoot
        
        Args:
            product_image_data: Product image data
            style_image_data: Style image data (optional)
            text_prompt: Text prompt for photoshoot style (optional)
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Product Photoshoot API workflow...")
        
        # Step 1: Upload images
        print("📤 Uploading images...")
        product_url, style_url = self.upload_images(product_image_data, style_image_data, content_type)
        print(f"✅ Product image uploaded: {product_url}")
        if style_url:
            print(f"✅ Style image uploaded: {style_url}")
        
        # Step 2: Generate product photoshoot
        print("📸 Generating product photoshoot...")
        order_id = self.generate_product_photoshoot(product_url, style_url, text_prompt)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_suggested_prompts(self, category: str) -> List[str]:
        """
        Get common text prompts for different product photoshoot styles
        
        Args:
            category: Category of photoshoot style
            
        Returns:
            List[str]: List of suggested prompts
        """
        prompt_suggestions = {
            "ecommerce": [
                "clean white background ecommerce",
                "professional product photography",
                "minimalist product shot",
                "studio lighting product photo",
                "commercial product photography"
            ],
            "lifestyle": [
                "lifestyle product photography",
                "natural environment product shot",
                "outdoor product photography",
                "casual lifestyle setting",
                "real-world product usage"
            ],
            "luxury": [
                "luxury product photography",
                "premium product presentation",
                "high-end product shot",
                "elegant product photography",
                "sophisticated product display"
            ],
            "tech": [
                "modern tech product photography",
                "sleek technology product shot",
                "contemporary tech presentation",
                "futuristic product photography",
                "digital product showcase"
            ],
            "fashion": [
                "fashion product photography",
                "stylish clothing presentation",
                "trendy fashion product shot",
                "modern fashion photography",
                "contemporary style product"
            ],
            "food": [
                "appetizing food photography",
                "delicious food presentation",
                "mouth-watering food shot",
                "professional food photography",
                "gourmet food styling"
            ],
            "beauty": [
                "beauty product photography",
                "cosmetic product presentation",
                "skincare product shot",
                "makeup product photography",
                "beauty brand styling"
            ],
            "home": [
                "home decor product photography",
                "interior design product shot",
                "home furnishing presentation",
                "decorative product photography",
                "lifestyle home product"
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
    
    def generate_product_photoshoot_with_prompt(self, product_image_data: Union[bytes, str, Path],
                                               text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate product photoshoot with text prompt only
        
        Args:
            product_image_data: Product image data
            text_prompt: Text prompt for photoshoot style
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_product_photoshoot(product_image_data, None, text_prompt, content_type)
    
    def generate_product_photoshoot_with_style(self, product_image_data: Union[bytes, str, Path],
                                              style_image_data: Union[bytes, str, Path],
                                              content_type: str = "image/jpeg") -> Dict:
        """
        Generate product photoshoot with style image only
        
        Args:
            product_image_data: Product image data
            style_image_data: Style image data
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        return self.process_product_photoshoot(product_image_data, style_image_data, None, content_type)
    
    def generate_product_photoshoot_with_style_and_prompt(self, product_image_data: Union[bytes, str, Path],
                                                         style_image_data: Union[bytes, str, Path],
                                                         text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate product photoshoot with both style image and text prompt
        
        Args:
            product_image_data: Product image data
            style_image_data: Style image data
            text_prompt: Text prompt for photoshoot style
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_product_photoshoot(product_image_data, style_image_data, text_prompt, content_type)
    
    def get_product_photoshoot_tips(self) -> Dict[str, List[str]]:
        """
        Get product photoshoot tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better photoshoot results
        """
        tips = {
            "product_image": [
                "Use clear, well-lit product photos with good contrast",
                "Ensure the product is clearly visible and centered",
                "Avoid cluttered backgrounds in the original image",
                "Use high-resolution images for better results",
                "Product should be the main focus of the image"
            ],
            "style_image": [
                "Choose style images with desired background or setting",
                "Use lifestyle or studio photography as style references",
                "Ensure style image has good lighting and composition",
                "Match the mood and aesthetic you want for your product",
                "Use high-quality style reference images"
            ],
            "text_prompts": [
                "Be specific about the photoshoot style you want",
                "Mention background preferences (white, lifestyle, outdoor)",
                "Include lighting preferences (studio, natural, dramatic)",
                "Specify the mood (professional, casual, luxury, modern)",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Product photoshoots work best with clear product images",
                "Results may vary based on input image quality",
                "Style images influence both background and overall aesthetic",
                "Text prompts help guide the photoshoot style",
                "Allow 15-30 seconds for processing"
            ]
        }
        
        print("💡 Product Photoshoot Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_product_category_tips(self) -> Dict[str, List[str]]:
        """
        Get product category-specific tips
        
        Returns:
            Dict[str, List[str]]: Dictionary of category-specific tips
        """
        category_tips = {
            "electronics": [
                "Use clean, minimalist backgrounds",
                "Ensure good lighting to show product details",
                "Consider showing the product from multiple angles",
                "Use neutral colors to make the product stand out"
            ],
            "clothing": [
                "Use mannequins or models for better presentation",
                "Consider lifestyle settings for fashion items",
                "Ensure good lighting to show fabric texture",
                "Use complementary background colors"
            ],
            "jewelry": [
                "Use dark or neutral backgrounds for contrast",
                "Ensure excellent lighting to show sparkle and detail",
                "Consider close-up shots to show craftsmanship",
                "Use soft lighting to avoid harsh reflections"
            ],
            "food": [
                "Use natural lighting when possible",
                "Consider lifestyle settings (kitchen, dining table)",
                "Ensure good color contrast and appetizing presentation",
                "Use props that complement the food item"
            ],
            "beauty": [
                "Use clean, professional backgrounds",
                "Ensure good lighting to show product colors",
                "Consider showing the product in use",
                "Use soft, flattering lighting"
            ],
            "home_decor": [
                "Use lifestyle settings to show context",
                "Consider room settings for larger items",
                "Ensure good lighting to show texture and materials",
                "Use complementary colors and styles"
            ]
        }
        
        print("💡 Product Category Tips:")
        for category, tip_list in category_tips.items():
            print(f"{category}: {tip_list}")
        return category_tips
    
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
    Example usage of the LightX Product Photoshoot API
    """
    try:
        # Initialize with your API key
        lightx = LightXProductPhotoshootAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_product_photoshoot_tips()
        lightx.get_product_category_tips()
        
        # Load product image (replace with your image loading logic)
        product_image_path = "path/to/product-image.jpg"
        style_image_path = "path/to/style-image.jpg"
        
        # Example 1: Generate product photoshoot with text prompt only
        ecommerce_prompts = lightx.get_suggested_prompts("ecommerce")
        result1 = lightx.generate_product_photoshoot_with_prompt(
            product_image_path,
            ecommerce_prompts[0],
            "image/jpeg"
        )
        print("🎉 E-commerce photoshoot result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Generate product photoshoot with style image only
        result2 = lightx.generate_product_photoshoot_with_style(
            product_image_path,
            style_image_path,
            "image/jpeg"
        )
        print("🎉 Style-based photoshoot result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Generate product photoshoot with both style image and text prompt
        luxury_prompts = lightx.get_suggested_prompts("luxury")
        result3 = lightx.generate_product_photoshoot_with_style_and_prompt(
            product_image_path,
            style_image_path,
            luxury_prompts[0],
            "image/jpeg"
        )
        print("🎉 Combined style and prompt result:")
        print(f"Order ID: {result3['orderId']}")
        print(f"Status: {result3['status']}")
        if result3.get("output"):
            print(f"Output: {result3['output']}")
        
        # Example 4: Generate photoshoots for different categories
        categories = ["ecommerce", "lifestyle", "luxury", "tech", "fashion", "food", "beauty", "home"]
        for category in categories:
            prompts = lightx.get_suggested_prompts(category)
            result = lightx.generate_product_photoshoot_with_prompt(
                product_image_path,
                prompts[0],
                "image/jpeg"
            )
            print(f"🎉 {category} photoshoot result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 5: Get image dimensions
        width, height = lightx.get_image_dimensions(product_image_path)
        if width > 0 and height > 0:
            print(f"📏 Original image: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
