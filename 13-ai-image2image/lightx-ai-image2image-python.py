"""
LightX AI Image to Image API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered image to image transformation functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXImage2ImageAPI:
    """
    LightX AI Image to Image API client for transforming images with style and content
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Image to Image API client
        
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
    
    def upload_images(self, input_image_data: Union[bytes, str, Path], 
                     style_image_data: Optional[Union[bytes, str, Path]] = None,
                     content_type: str = "image/jpeg") -> Tuple[str, Optional[str]]:
        """
        Upload multiple images (input and optional style image)
        
        Args:
            input_image_data: Input image data
            style_image_data: Style image data (optional)
            content_type: MIME type
            
        Returns:
            Tuple of (input_url, style_url)
        """
        print("📤 Uploading input image...")
        input_url = self.upload_image(input_image_data, content_type)
        
        style_url = None
        if style_image_data:
            print("📤 Uploading style image...")
            style_url = self.upload_image(style_image_data, content_type)
        
        return input_url, style_url
    
    def generate_image2image(self, image_url: str, strength: float, text_prompt: str, 
                           style_image_url: Optional[str] = None, style_strength: Optional[float] = None) -> str:
        """
        Generate image to image transformation
        
        Args:
            image_url: URL of the input image
            strength: Strength parameter (0.0 to 1.0)
            text_prompt: Text prompt for transformation
            style_image_url: URL of the style image (optional)
            style_strength: Style strength parameter (0.0 to 1.0, optional)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/image2image"
        
        payload = {
            "imageUrl": image_url,
            "strength": strength,
            "textPrompt": text_prompt
        }
        
        # Add optional parameters
        if style_image_url:
            payload["styleImageUrl"] = style_image_url
        if style_strength is not None:
            payload["styleStrength"] = style_strength
        
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key
        }
        
        try:
            response = requests.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            
            data = response.json()
            
            if data["statusCode"] != 2000:
                raise requests.RequestException(f"Image to image request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"💬 Text prompt: \"{text_prompt}\"")
            print(f"🎨 Strength: {strength}")
            if style_image_url:
                print(f"🎭 Style image: {style_image_url}")
                print(f"🎨 Style strength: {style_strength}")
            
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
                    print("✅ Image to image transformation completed successfully!")
                    if status.get("output"):
                        print(f"🖼️ Transformed image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Image to image transformation failed")
                
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
    
    def process_image2image_generation(self, input_image_data: Union[bytes, str, Path],
                                     strength: float, text_prompt: str,
                                     style_image_data: Optional[Union[bytes, str, Path]] = None,
                                     style_strength: Optional[float] = None,
                                     content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload images and generate image to image transformation
        
        Args:
            input_image_data: Input image data
            strength: Strength parameter (0.0 to 1.0)
            text_prompt: Text prompt for transformation
            style_image_data: Style image data (optional)
            style_strength: Style strength parameter (0.0 to 1.0, optional)
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Image to Image API workflow...")
        
        # Step 1: Upload images
        print("📤 Uploading images...")
        input_url, style_url = self.upload_images(input_image_data, style_image_data, content_type)
        print(f"✅ Input image uploaded: {input_url}")
        if style_url:
            print(f"✅ Style image uploaded: {style_url}")
        
        # Step 2: Generate image to image transformation
        print("🖼️ Generating image to image transformation...")
        order_id = self.generate_image2image(input_url, strength, text_prompt, style_url, style_strength)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_image2image_tips(self) -> Dict[str, List[str]]:
        """
        Get image to image transformation tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with good contrast",
                "Ensure the subject is clearly visible and well-composed",
                "Avoid cluttered or busy backgrounds",
                "Use high-resolution images for better results",
                "Good image quality improves transformation results"
            ],
            "strength_parameter": [
                "Higher strength (0.7-1.0) makes output more similar to input",
                "Lower strength (0.1-0.3) allows more creative transformation",
                "Medium strength (0.4-0.6) balances similarity and creativity",
                "Experiment with different strength values for desired results",
                "Strength affects how much the original image structure is preserved"
            ],
            "style_image": [
                "Choose style images with desired visual characteristics",
                "Ensure style image has good quality and clear features",
                "Style strength controls how much style is applied",
                "Higher style strength applies more style characteristics",
                "Use style images that complement your text prompt"
            ],
            "text_prompts": [
                "Be specific about the transformation you want",
                "Mention artistic styles, colors, and visual elements",
                "Include details about lighting, mood, and atmosphere",
                "Combine style descriptions with content descriptions",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Image to image works best with clear, well-composed photos",
                "Results may vary based on input image quality",
                "Text prompts guide the transformation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different strength and style combinations"
            ]
        }
        
        print("💡 Image to Image Transformation Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_strength_suggestions(self) -> Dict[str, Dict[str, Union[str, List[str]]]]:
        """
        Get strength parameter suggestions
        
        Returns:
            Dict[str, Dict[str, Union[str, List[str]]]]: Dictionary of strength suggestions
        """
        strength_suggestions = {
            "conservative": {
                "range": "0.7 - 1.0",
                "description": "Preserves most of the original image structure and content",
                "use_cases": ["Minor style adjustments", "Color corrections", "Light enhancement", "Subtle artistic effects"]
            },
            "balanced": {
                "range": "0.4 - 0.6",
                "description": "Balances original content with creative transformation",
                "use_cases": ["Style transfer", "Artistic interpretation", "Medium-level changes", "Creative enhancement"]
            },
            "creative": {
                "range": "0.1 - 0.3",
                "description": "Allows significant creative transformation while keeping basic structure",
                "use_cases": ["Major style changes", "Artistic reimagining", "Creative reinterpretation", "Dramatic transformation"]
            }
        }
        
        print("💡 Strength Parameter Suggestions:")
        for category, suggestion in strength_suggestions.items():
            print(f"{category}: {suggestion['range']} - {suggestion['description']}")
            print(f"  Use cases: {', '.join(suggestion['use_cases'])}")
        return strength_suggestions
    
    def get_style_strength_suggestions(self) -> Dict[str, Dict[str, Union[str, List[str]]]]:
        """
        Get style strength suggestions
        
        Returns:
            Dict[str, Dict[str, Union[str, List[str]]]]: Dictionary of style strength suggestions
        """
        style_strength_suggestions = {
            "subtle": {
                "range": "0.1 - 0.3",
                "description": "Applies subtle style characteristics",
                "use_cases": ["Gentle style influence", "Color palette transfer", "Light texture changes"]
            },
            "moderate": {
                "range": "0.4 - 0.6",
                "description": "Applies moderate style characteristics",
                "use_cases": ["Clear style transfer", "Artistic interpretation", "Medium style influence"]
            },
            "strong": {
                "range": "0.7 - 1.0",
                "description": "Applies strong style characteristics",
                "use_cases": ["Dramatic style transfer", "Complete artistic transformation", "Strong visual influence"]
            }
        }
        
        print("💡 Style Strength Suggestions:")
        for category, suggestion in style_strength_suggestions.items():
            print(f"{category}: {suggestion['range']} - {suggestion['description']}")
            print(f"  Use cases: {', '.join(suggestion['use_cases'])}")
        return style_strength_suggestions
    
    def get_transformation_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get transformation prompt examples
        
        Returns:
            Dict[str, List[str]]: Dictionary of prompt examples
        """
        prompt_examples = {
            "artistic": [
                "Transform into oil painting style with rich colors",
                "Convert to watercolor painting with soft edges",
                "Create digital art with vibrant colors and smooth gradients",
                "Transform into pencil sketch with detailed shading",
                "Convert to pop art style with bold colors and contrast"
            ],
            "style_transfer": [
                "Apply Van Gogh painting style with swirling brushstrokes",
                "Transform into Picasso cubist style with geometric shapes",
                "Apply Monet impressionist style with soft, blurred edges",
                "Convert to Andy Warhol pop art with bright, flat colors",
                "Transform into Japanese ukiyo-e woodblock print style"
            ],
            "mood_atmosphere": [
                "Create warm, golden hour lighting with soft shadows",
                "Transform into dramatic, high-contrast black and white",
                "Apply dreamy, ethereal atmosphere with soft focus",
                "Create vintage, sepia-toned nostalgic look",
                "Transform into futuristic, cyberpunk aesthetic"
            ],
            "color_enhancement": [
                "Enhance colors with vibrant, saturated tones",
                "Apply cool, blue-toned color grading",
                "Transform into warm, orange and red color palette",
                "Create monochromatic look with single color accent",
                "Apply vintage film color grading with faded tones"
            ]
        }
        
        print("💡 Transformation Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def validate_parameters(self, strength: float, text_prompt: str, style_strength: Optional[float] = None) -> bool:
        """
        Validate parameters (utility function)
        
        Args:
            strength: Strength parameter to validate
            text_prompt: Text prompt to validate
            style_strength: Style strength parameter to validate (optional)
            
        Returns:
            bool: Whether the parameters are valid
        """
        # Validate strength
        if not 0 <= strength <= 1:
            print("❌ Strength must be between 0.0 and 1.0")
            return False
        
        # Validate text prompt
        if not text_prompt or not text_prompt.strip():
            print("❌ Text prompt cannot be empty")
            return False
        
        if len(text_prompt) > 500:
            print("❌ Text prompt is too long (max 500 characters)")
            return False
        
        # Validate style strength if provided
        if style_strength is not None and not 0 <= style_strength <= 1:
            print("❌ Style strength must be between 0.0 and 1.0")
            return False
        
        print("✅ Parameters are valid")
        return True
    
    def generate_image2image_with_validation(self, input_image_data: Union[bytes, str, Path],
                                           strength: float, text_prompt: str,
                                           style_image_data: Optional[Union[bytes, str, Path]] = None,
                                           style_strength: Optional[float] = None,
                                           content_type: str = "image/jpeg") -> Dict:
        """
        Generate image to image transformation with parameter validation
        
        Args:
            input_image_data: Input image data
            strength: Strength parameter (0.0 to 1.0)
            text_prompt: Text prompt for transformation
            style_image_data: Style image data (optional)
            style_strength: Style strength parameter (0.0 to 1.0, optional)
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_parameters(strength, text_prompt, style_strength):
            raise ValueError("Invalid parameters")
        
        return self.process_image2image_generation(
            input_image_data, strength, text_prompt, style_image_data, style_strength, content_type
        )
    
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
    Example usage of the LightX Image to Image API
    """
    try:
        # Initialize with your API key
        lightx = LightXImage2ImageAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_image2image_tips()
        lightx.get_strength_suggestions()
        lightx.get_style_strength_suggestions()
        lightx.get_transformation_prompt_examples()
        
        # Load images (replace with your image loading logic)
        input_image_path = "path/to/input-image.jpg"
        style_image_path = "path/to/style-image.jpg"
        
        # Example 1: Conservative transformation with text prompt only
        result1 = lightx.generate_image2image_with_validation(
            input_image_path,
            0.8,  # High strength to preserve original
            "Transform into oil painting style with rich colors",
            None,  # No style image
            None,  # No style strength
            "image/jpeg"
        )
        print("🎉 Conservative transformation result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Balanced transformation with style image
        result2 = lightx.generate_image2image_with_validation(
            input_image_path,
            0.5,  # Balanced strength
            "Apply artistic style transformation",
            style_image_path,  # Style image
            0.7,  # Strong style influence
            "image/jpeg"
        )
        print("🎉 Balanced transformation result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Creative transformation with different strength values
        strength_values = [0.2, 0.5, 0.8]
        for strength in strength_values:
            result = lightx.generate_image2image_with_validation(
                input_image_path,
                strength,
                "Create artistic interpretation with vibrant colors",
                None,
                None,
                "image/jpeg"
            )
            print(f"🎉 Creative transformation (strength: {strength}) result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 4: Get image dimensions
        width, height = lightx.get_image_dimensions(input_image_path)
        if width > 0 and height > 0:
            print(f"📏 Original image: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
