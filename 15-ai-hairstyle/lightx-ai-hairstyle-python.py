"""
LightX AI Hairstyle API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered hairstyle transformation functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXHairstyleAPI:
    """
    LightX AI Hairstyle API client for virtual hairstyle try-on
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Hairstyle API client
        
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
    
    def generate_hairstyle(self, image_url: str, text_prompt: str) -> str:
        """
        Generate hairstyle transformation
        
        Args:
            image_url: URL of the input image
            text_prompt: Text prompt for hairstyle description
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/hairstyle"
        
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
                raise requests.RequestException(f"Hairstyle request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"💇 Hairstyle prompt: \"{text_prompt}\"")
            
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
                    print("✅ Hairstyle transformation completed successfully!")
                    if status.get("output"):
                        print(f"💇 New hairstyle: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Hairstyle transformation failed")
                
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
    
    def process_hairstyle_generation(self, image_data: Union[bytes, str, Path],
                                   text_prompt: str,
                                   content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and generate hairstyle transformation
        
        Args:
            image_data: Image data
            text_prompt: Text prompt for hairstyle description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Hairstyle API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Generate hairstyle transformation
        print("💇 Generating hairstyle transformation...")
        order_id = self.generate_hairstyle(image_url, text_prompt)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_hairstyle_tips(self) -> Dict[str, List[str]]:
        """
        Get hairstyle transformation tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the person's face and current hair are clearly visible",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good face visibility improves hairstyle transformation results"
            ],
            "text_prompts": [
                "Be specific about the hairstyle you want to try",
                "Mention hair length, style, and characteristics",
                "Include details about hair color, texture, and cut",
                "Describe the overall look and feel you're going for",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Hairstyle transformation works best with clear face photos",
                "Results may vary based on input image quality",
                "Text prompts guide the hairstyle generation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different hairstyle descriptions"
            ]
        }
        
        print("💡 Hairstyle Transformation Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_hairstyle_suggestions(self) -> Dict[str, List[str]]:
        """
        Get hairstyle style suggestions
        
        Returns:
            Dict[str, List[str]]: Dictionary of hairstyle suggestions
        """
        hairstyle_suggestions = {
            "short_styles": [
                "pixie cut with side-swept bangs",
                "short bob with layers",
                "buzz cut with fade",
                "short curly afro",
                "asymmetrical short cut"
            ],
            "medium_styles": [
                "shoulder-length layered cut",
                "medium bob with waves",
                "lob (long bob) with face-framing layers",
                "medium length with curtain bangs",
                "shoulder-length with subtle highlights"
            ],
            "long_styles": [
                "long flowing waves",
                "straight long hair with center part",
                "long layered cut with side bangs",
                "long hair with beachy waves",
                "long hair with balayage highlights"
            ],
            "curly_styles": [
                "natural curly afro",
                "loose beachy waves",
                "tight spiral curls",
                "wavy bob with natural texture",
                "curly hair with defined ringlets"
            ],
            "trendy_styles": [
                "modern shag cut with layers",
                "wolf cut with textured ends",
                "butterfly cut with face-framing layers",
                "mullet with modern styling",
                "bixie cut (bob-pixie hybrid)"
            ]
        }
        
        print("💡 Hairstyle Style Suggestions:")
        for category, suggestion_list in hairstyle_suggestions.items():
            print(f"{category}: {suggestion_list}")
        return hairstyle_suggestions
    
    def get_hairstyle_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get hairstyle prompt examples
        
        Returns:
            Dict[str, List[str]]: Dictionary of prompt examples
        """
        prompt_examples = {
            "classic": [
                "Classic bob haircut with clean lines",
                "Traditional pixie cut with side part",
                "Classic long layers with subtle waves",
                "Timeless shoulder-length cut with bangs",
                "Classic short back and sides with longer top"
            ],
            "modern": [
                "Modern shag cut with textured layers",
                "Contemporary lob with face-framing highlights",
                "Trendy wolf cut with choppy ends",
                "Modern pixie with asymmetrical styling",
                "Contemporary long hair with curtain bangs"
            ],
            "casual": [
                "Casual beachy waves for everyday wear",
                "Relaxed shoulder-length cut with natural texture",
                "Easy-care short bob with minimal styling",
                "Casual long hair with loose waves",
                "Low-maintenance pixie with natural movement"
            ],
            "formal": [
                "Elegant updo with sophisticated styling",
                "Formal bob with sleek, polished finish",
                "Classic long hair styled for special occasions",
                "Professional short cut with refined styling",
                "Elegant shoulder-length cut with smooth finish"
            ],
            "creative": [
                "Bold asymmetrical cut with dramatic angles",
                "Creative color-blocked hairstyle",
                "Artistic pixie with unique styling",
                "Dramatic long layers with bold highlights",
                "Creative short cut with geometric styling"
            ]
        }
        
        print("💡 Hairstyle Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def get_face_shape_recommendations(self) -> Dict[str, Dict[str, Union[str, List[str]]]]:
        """
        Get face shape hairstyle recommendations
        
        Returns:
            Dict[str, Dict[str, Union[str, List[str]]]]: Dictionary of face shape recommendations
        """
        face_shape_recommendations = {
            "oval": {
                "description": "Most versatile face shape - can pull off most hairstyles",
                "recommended": ["Long layers", "Pixie cuts", "Bob cuts", "Side-swept bangs", "Any length works well"],
                "avoid": ["Heavy bangs that cover forehead", "Styles that add width to face"]
            },
            "round": {
                "description": "Face is as wide as it is long with soft, curved lines",
                "recommended": ["Long layers", "Asymmetrical cuts", "Side parts", "Height at crown", "Angular cuts"],
                "avoid": ["Short, rounded cuts", "Center parts", "Full bangs", "Styles that add width"]
            },
            "square": {
                "description": "Strong jawline with angular features",
                "recommended": ["Soft layers", "Side-swept bangs", "Longer styles", "Rounded cuts", "Texture and movement"],
                "avoid": ["Sharp, angular cuts", "Straight-across bangs", "Very short cuts"]
            },
            "heart": {
                "description": "Wider at forehead, narrower at chin",
                "recommended": ["Chin-length cuts", "Side-swept bangs", "Layered styles", "Volume at chin level"],
                "avoid": ["Very short cuts", "Heavy bangs", "Styles that add width at top"]
            },
            "long": {
                "description": "Face is longer than it is wide",
                "recommended": ["Shorter cuts", "Side parts", "Layers", "Bangs", "Width-adding styles"],
                "avoid": ["Very long, straight styles", "Center parts", "Height at crown"]
            }
        }
        
        print("💡 Face Shape Hairstyle Recommendations:")
        for shape, info in face_shape_recommendations.items():
            print(f"{shape}: {info['description']}")
            print(f"  Recommended: {', '.join(info['recommended'])}")
            print(f"  Avoid: {', '.join(info['avoid'])}")
        return face_shape_recommendations
    
    def get_hair_type_tips(self) -> Dict[str, Dict[str, Union[str, List[str]]]]:
        """
        Get hair type styling tips
        
        Returns:
            Dict[str, Dict[str, Union[str, List[str]]]]: Dictionary of hair type tips
        """
        hair_type_tips = {
            "straight": {
                "characteristics": "Smooth, lacks natural curl or wave",
                "styling_tips": ["Layers add movement", "Blunt cuts work well", "Texture can be added with styling"],
                "best_styles": ["Blunt bob", "Long layers", "Pixie cuts", "Straight-across bangs"]
            },
            "wavy": {
                "characteristics": "Natural S-shaped waves",
                "styling_tips": ["Enhance natural texture", "Layers work beautifully", "Avoid over-straightening"],
                "best_styles": ["Layered cuts", "Beachy waves", "Shoulder-length styles", "Natural texture cuts"]
            },
            "curly": {
                "characteristics": "Natural spiral or ringlet formation",
                "styling_tips": ["Work with natural curl pattern", "Avoid heavy layers", "Moisture is key"],
                "best_styles": ["Curly bobs", "Natural afro", "Layered curls", "Curly pixie cuts"]
            },
            "coily": {
                "characteristics": "Tight, springy curls or coils",
                "styling_tips": ["Embrace natural texture", "Regular moisture needed", "Protective styles work well"],
                "best_styles": ["Natural afro", "Twist-outs", "Bantu knots", "Protective braided styles"]
            }
        }
        
        print("💡 Hair Type Styling Tips:")
        for hair_type, info in hair_type_tips.items():
            print(f"{hair_type}: {info['characteristics']}")
            print(f"  Styling tips: {', '.join(info['styling_tips'])}")
            print(f"  Best styles: {', '.join(info['best_styles'])}")
        return hair_type_tips
    
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
    
    def generate_hairstyle_with_validation(self, image_data: Union[bytes, str, Path],
                                         text_prompt: str,
                                         content_type: str = "image/jpeg") -> Dict:
        """
        Generate hairstyle transformation with parameter validation
        
        Args:
            image_data: Image data
            text_prompt: Text prompt for hairstyle description
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise ValueError("Invalid text prompt")
        
        return self.process_hairstyle_generation(image_data, text_prompt, content_type)
    
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
    Example usage of the LightX Hairstyle API
    """
    try:
        # Initialize with your API key
        lightx = LightXHairstyleAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_hairstyle_tips()
        lightx.get_hairstyle_suggestions()
        lightx.get_hairstyle_prompt_examples()
        lightx.get_face_shape_recommendations()
        lightx.get_hair_type_tips()
        
        # Load image (replace with your image loading logic)
        image_path = "path/to/input-image.jpg"
        
        # Example 1: Try a classic bob hairstyle
        result1 = lightx.generate_hairstyle_with_validation(
            image_path,
            "Classic bob haircut with clean lines and side part",
            "image/jpeg"
        )
        print("🎉 Classic bob result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Try a modern pixie cut
        result2 = lightx.generate_hairstyle_with_validation(
            image_path,
            "Modern pixie cut with asymmetrical styling and texture",
            "image/jpeg"
        )
        print("🎉 Modern pixie result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Try different hairstyles
        hairstyles = [
            "Long flowing waves with natural texture",
            "Shoulder-length layered cut with curtain bangs",
            "Short curly afro with natural texture",
            "Beachy waves with sun-kissed highlights"
        ]
        
        for hairstyle in hairstyles:
            result = lightx.generate_hairstyle_with_validation(
                image_path,
                hairstyle,
                "image/jpeg"
            )
            print(f"🎉 {hairstyle} result:")
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
