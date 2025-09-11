"""
LightX AI Outfit API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered outfit changing functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXOutfitAPI:
    """
    LightX AI Outfit API client for virtually trying on outfits
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Outfit API client
        
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
    
    def generate_outfit(self, image_url: str, text_prompt: str) -> str:
        """
        Generate outfit change
        
        Args:
            image_url: URL of the input image
            text_prompt: Text prompt for outfit description
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/outfit"
        
        payload = {
            "imageUrl": image_url,
            "textPrompt": text_prompt
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
                raise requests.RequestException(f"Outfit generation request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"👗 Outfit prompt: \"{text_prompt}\"")
            
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
                    print("✅ Outfit generation completed successfully!")
                    if status.get("output"):
                        print(f"👗 Outfit result: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Outfit generation failed")
                
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
    
    def process_outfit_generation(self, image_data: Union[bytes, str, Path],
                                 text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and generate outfit
        
        Args:
            image_data: Image data
            text_prompt: Text prompt for outfit description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Outfit API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Generate outfit
        print("👗 Generating outfit...")
        order_id = self.generate_outfit(image_url, text_prompt)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_outfit_tips(self) -> Dict[str, List[str]]:
        """
        Get outfit generation tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better outfit results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the person is clearly visible and centered",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good body visibility improves outfit generation"
            ],
            "text_prompts": [
                "Be specific about the outfit style you want",
                "Mention clothing items (shirt, dress, jacket, etc.)",
                "Include color preferences and patterns",
                "Specify the occasion (casual, formal, party, etc.)",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Outfit generation works best with clear human subjects",
                "Results may vary based on input image quality",
                "Text prompts guide the outfit generation process",
                "Allow 15-30 seconds for processing",
                "Experiment with different outfit styles for variety"
            ]
        }
        
        print("💡 Outfit Generation Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_outfit_style_suggestions(self) -> Dict[str, List[str]]:
        """
        Get outfit style suggestions
        
        Returns:
            Dict[str, List[str]]: Dictionary of outfit style suggestions
        """
        style_suggestions = {
            "professional": [
                "professional business suit",
                "formal office attire",
                "corporate blazer and dress pants",
                "elegant business dress",
                "sophisticated work outfit"
            ],
            "casual": [
                "casual jeans and t-shirt",
                "relaxed weekend outfit",
                "comfortable everyday wear",
                "casual summer dress",
                "laid-back street style"
            ],
            "formal": [
                "elegant evening gown",
                "formal tuxedo",
                "cocktail party dress",
                "black tie attire",
                "sophisticated formal wear"
            ],
            "sporty": [
                "athletic workout outfit",
                "sporty casual wear",
                "gym attire",
                "active lifestyle clothing",
                "comfortable sports outfit"
            ],
            "trendy": [
                "fashionable street style",
                "trendy modern outfit",
                "stylish contemporary wear",
                "fashion-forward ensemble",
                "chic trendy clothing"
            ]
        }
        
        print("💡 Outfit Style Suggestions:")
        for category, suggestion_list in style_suggestions.items():
            print(f"{category}: {suggestion_list}")
        return style_suggestions
    
    def get_outfit_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get outfit prompt examples
        
        Returns:
            Dict[str, List[str]]: Dictionary of prompt examples
        """
        prompt_examples = {
            "professional": [
                "Professional navy blue business suit with white shirt",
                "Elegant black blazer with matching dress pants",
                "Corporate dress in neutral colors",
                "Formal office attire with blouse and skirt",
                "Business casual outfit with cardigan and slacks"
            ],
            "casual": [
                "Casual blue jeans with white cotton t-shirt",
                "Relaxed summer dress in floral pattern",
                "Comfortable hoodie with denim jeans",
                "Casual weekend outfit with sneakers",
                "Lay-back style with comfortable clothing"
            ],
            "formal": [
                "Elegant black evening gown with accessories",
                "Formal tuxedo with bow tie",
                "Cocktail dress in deep red color",
                "Black tie formal wear",
                "Sophisticated formal attire for special occasion"
            ],
            "sporty": [
                "Athletic leggings with sports bra and sneakers",
                "Gym outfit with tank top and shorts",
                "Active wear for running and exercise",
                "Sporty casual outfit for outdoor activities",
                "Comfortable athletic clothing"
            ],
            "trendy": [
                "Fashionable street style with trendy accessories",
                "Modern outfit with contemporary fashion elements",
                "Stylish ensemble with current fashion trends",
                "Chic trendy clothing with fashionable details",
                "Fashion-forward outfit with modern styling"
            ]
        }
        
        print("💡 Outfit Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def get_outfit_use_cases(self) -> Dict[str, List[str]]:
        """
        Get outfit use cases and examples
        
        Returns:
            Dict[str, List[str]]: Dictionary of use case examples
        """
        use_cases = {
            "fashion": [
                "Virtual try-on for e-commerce",
                "Fashion styling and recommendations",
                "Outfit planning and coordination",
                "Style inspiration and ideas",
                "Fashion trend visualization"
            ],
            "retail": [
                "Online shopping experience enhancement",
                "Product visualization and styling",
                "Customer engagement and interaction",
                "Virtual fitting room technology",
                "Personalized fashion recommendations"
            ],
            "social": [
                "Social media content creation",
                "Fashion blogging and influencers",
                "Style sharing and inspiration",
                "Outfit of the day posts",
                "Fashion community engagement"
            ],
            "personal": [
                "Personal style exploration",
                "Wardrobe planning and organization",
                "Outfit coordination and matching",
                "Style experimentation",
                "Fashion confidence building"
            ]
        }
        
        print("💡 Outfit Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
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
    
    def generate_outfit_with_prompt(self, image_data: Union[bytes, str, Path],
                                   text_prompt: str, content_type: str = "image/jpeg") -> Dict:
        """
        Generate outfit with prompt validation
        
        Args:
            image_data: Image data
            text_prompt: Text prompt for outfit description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_outfit_generation(image_data, text_prompt, content_type)
    
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
    Example usage of the LightX Outfit API
    """
    try:
        # Initialize with your API key
        lightx = LightXOutfitAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_outfit_tips()
        lightx.get_outfit_style_suggestions()
        lightx.get_outfit_prompt_examples()
        lightx.get_outfit_use_cases()
        
        # Load image (replace with your image loading logic)
        image_path = "path/to/input-image.jpg"
        
        # Example 1: Generate professional outfit
        professional_prompts = lightx.get_outfit_style_suggestions()["professional"]
        result1 = lightx.generate_outfit_with_prompt(
            image_path,
            professional_prompts[0],
            "image/jpeg"
        )
        print("🎉 Professional outfit result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Generate casual outfit
        casual_prompts = lightx.get_outfit_style_suggestions()["casual"]
        result2 = lightx.generate_outfit_with_prompt(
            image_path,
            casual_prompts[0],
            "image/jpeg"
        )
        print("🎉 Casual outfit result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Generate outfits for different styles
        styles = ["professional", "casual", "formal", "sporty", "trendy"]
        for style in styles:
            prompts = lightx.get_outfit_style_suggestions()[style]
            result = lightx.generate_outfit_with_prompt(
                image_path,
                prompts[0],
                "image/jpeg"
            )
            print(f"🎉 {style} outfit result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 4: Get image dimensions
        width, height = lightx.get_image_dimensions(image_path)
        if width > 0 and height > 0:
            print(f"📏 Original image: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
