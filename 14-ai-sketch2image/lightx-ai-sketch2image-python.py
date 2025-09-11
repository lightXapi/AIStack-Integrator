"""
LightX AI Sketch to Image API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered sketch to image transformation functionality.
"""

import requests
import time
import json
from typing import Optional, Dict, List, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXSketch2ImageAPI:
    """
    LightX AI Sketch to Image API client for transforming sketches into detailed images
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Sketch to Image API client
        
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
    
    def upload_images(self, sketch_image_data: Union[bytes, str, Path], 
                     style_image_data: Optional[Union[bytes, str, Path]] = None,
                     content_type: str = "image/jpeg") -> Tuple[str, Optional[str]]:
        """
        Upload multiple images (sketch and optional style image)
        
        Args:
            sketch_image_data: Sketch image data
            style_image_data: Style image data (optional)
            content_type: MIME type
            
        Returns:
            Tuple of (sketch_url, style_url)
        """
        print("📤 Uploading sketch image...")
        sketch_url = self.upload_image(sketch_image_data, content_type)
        
        style_url = None
        if style_image_data:
            print("📤 Uploading style image...")
            style_url = self.upload_image(style_image_data, content_type)
        
        return sketch_url, style_url
    
    def generate_sketch2image(self, image_url: str, strength: float, text_prompt: str, 
                            style_image_url: Optional[str] = None, style_strength: Optional[float] = None) -> str:
        """
        Generate sketch to image transformation
        
        Args:
            image_url: URL of the sketch image
            strength: Strength parameter (0.0 to 1.0)
            text_prompt: Text prompt for transformation
            style_image_url: URL of the style image (optional)
            style_strength: Style strength parameter (0.0 to 1.0, optional)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            requests.RequestException: If API request fails
        """
        endpoint = f"{self.base_url}/v1/sketch2image"
        
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
                raise requests.RequestException(f"Sketch to image request failed: {data['message']}")
            
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
                    print("✅ Sketch to image transformation completed successfully!")
                    if status.get("output"):
                        print(f"🖼️ Generated image: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise requests.RequestException("Sketch to image transformation failed")
                
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
    
    def process_sketch2image_generation(self, sketch_image_data: Union[bytes, str, Path],
                                      strength: float, text_prompt: str,
                                      style_image_data: Optional[Union[bytes, str, Path]] = None,
                                      style_strength: Optional[float] = None,
                                      content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload images and generate sketch to image transformation
        
        Args:
            sketch_image_data: Sketch image data
            strength: Strength parameter (0.0 to 1.0)
            text_prompt: Text prompt for transformation
            style_image_data: Style image data (optional)
            style_strength: Style strength parameter (0.0 to 1.0, optional)
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Sketch to Image API workflow...")
        
        # Step 1: Upload images
        print("📤 Uploading images...")
        sketch_url, style_url = self.upload_images(sketch_image_data, style_image_data, content_type)
        print(f"✅ Sketch image uploaded: {sketch_url}")
        if style_url:
            print(f"✅ Style image uploaded: {style_url}")
        
        # Step 2: Generate sketch to image transformation
        print("🎨 Generating sketch to image transformation...")
        order_id = self.generate_sketch2image(sketch_url, strength, text_prompt, style_url, style_strength)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_sketch2image_tips(self) -> Dict[str, List[str]]:
        """
        Get sketch to image transformation tips and best practices
        
        Returns:
            Dict[str, List[str]]: Dictionary of tips for better results
        """
        tips = {
            "sketch_quality": [
                "Use clear, well-defined sketches with good contrast",
                "Ensure sketch lines are visible and not too faint",
                "Avoid overly complex or cluttered sketches",
                "Use high-resolution sketches for better results",
                "Good sketch quality improves transformation results"
            ],
            "strength_parameter": [
                "Higher strength (0.7-1.0) makes output more similar to sketch",
                "Lower strength (0.1-0.3) allows more creative interpretation",
                "Medium strength (0.4-0.6) balances sketch structure and creativity",
                "Experiment with different strength values for desired results",
                "Strength affects how much the original sketch structure is preserved"
            ],
            "style_image": [
                "Choose style images with desired visual characteristics",
                "Ensure style image has good quality and clear features",
                "Style strength controls how much style is applied",
                "Higher style strength applies more style characteristics",
                "Use style images that complement your text prompt"
            ],
            "text_prompts": [
                "Be specific about the final image you want to create",
                "Mention colors, lighting, mood, and visual style",
                "Include details about the subject matter and composition",
                "Combine style descriptions with content descriptions",
                "Keep prompts concise but descriptive"
            ],
            "general": [
                "Sketch to image works best with clear, well-composed sketches",
                "Results may vary based on sketch quality and complexity",
                "Text prompts guide the image generation direction",
                "Allow 15-30 seconds for processing",
                "Experiment with different strength and style combinations"
            ]
        }
        
        print("💡 Sketch to Image Transformation Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_strength_suggestions(self) -> Dict[str, Dict[str, Union[str, List[str]]]]:
        """
        Get strength parameter suggestions for sketch to image
        
        Returns:
            Dict[str, Dict[str, Union[str, List[str]]]]: Dictionary of strength suggestions
        """
        strength_suggestions = {
            "conservative": {
                "range": "0.7 - 1.0",
                "description": "Preserves most of the original sketch structure and composition",
                "use_cases": ["Detailed sketch interpretation", "Architectural drawings", "Technical illustrations", "Precise sketch rendering"]
            },
            "balanced": {
                "range": "0.4 - 0.6",
                "description": "Balances sketch structure with creative interpretation",
                "use_cases": ["Artistic sketch rendering", "Creative interpretation", "Style application", "Balanced transformation"]
            },
            "creative": {
                "range": "0.1 - 0.3",
                "description": "Allows significant creative interpretation while keeping basic sketch elements",
                "use_cases": ["Artistic reimagining", "Creative reinterpretation", "Style-heavy transformation", "Dramatic interpretation"]
            }
        }
        
        print("💡 Strength Parameter Suggestions for Sketch to Image:")
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
    
    def get_sketch2image_prompt_examples(self) -> Dict[str, List[str]]:
        """
        Get sketch to image prompt examples
        
        Returns:
            Dict[str, List[str]]: Dictionary of prompt examples
        """
        prompt_examples = {
            "realistic": [
                "Create a realistic photograph with natural lighting and colors",
                "Generate a photorealistic image with detailed textures and shadows",
                "Transform into a high-quality photograph with professional lighting",
                "Create a realistic portrait with natural skin tones and expressions",
                "Generate a realistic landscape with natural colors and atmosphere"
            ],
            "artistic": [
                "Transform into oil painting style with rich colors and brushstrokes",
                "Convert to watercolor painting with soft edges and flowing colors",
                "Create digital art with vibrant colors and smooth gradients",
                "Transform into pencil sketch with detailed shading and textures",
                "Convert to pop art style with bold colors and contrast"
            ],
            "fantasy": [
                "Create a fantasy illustration with magical elements and vibrant colors",
                "Generate a sci-fi scene with futuristic technology and lighting",
                "Transform into a fantasy landscape with mystical atmosphere",
                "Create a fantasy character with magical powers and detailed costume",
                "Generate a fantasy creature with unique features and colors"
            ],
            "architectural": [
                "Create a realistic architectural visualization with proper lighting",
                "Generate a modern building design with clean lines and materials",
                "Transform into an interior design with proper perspective and lighting",
                "Create a landscape architecture with natural elements",
                "Generate a futuristic building with innovative design elements"
            ]
        }
        
        print("💡 Sketch to Image Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        return prompt_examples
    
    def get_sketch_types(self) -> Dict[str, Dict[str, Union[str, List[str]]]]:
        """
        Get sketch types and their characteristics
        
        Returns:
            Dict[str, Dict[str, Union[str, List[str]]]]: Dictionary of sketch type information
        """
        sketch_types = {
            "line_art": {
                "description": "Simple line drawings with clear outlines",
                "best_for": ["Character design", "Logo concepts", "Simple illustrations"],
                "tips": ["Use clear, bold lines", "Avoid too many details", "Keep composition simple"]
            },
            "architectural": {
                "description": "Technical drawings and architectural sketches",
                "best_for": ["Building designs", "Interior layouts", "Urban planning"],
                "tips": ["Use proper perspective", "Include scale references", "Keep lines precise"]
            },
            "character": {
                "description": "Character and figure sketches",
                "best_for": ["Character design", "Portrait concepts", "Fashion design"],
                "tips": ["Focus on proportions", "Include facial features", "Consider pose and expression"]
            },
            "landscape": {
                "description": "Nature and landscape sketches",
                "best_for": ["Environment design", "Nature scenes", "Outdoor settings"],
                "tips": ["Include horizon line", "Show depth and perspective", "Consider lighting direction"]
            },
            "concept": {
                "description": "Conceptual and idea sketches",
                "best_for": ["Product design", "Creative concepts", "Abstract ideas"],
                "tips": ["Focus on main concept", "Use simple shapes", "Include key elements"]
            }
        }
        
        print("💡 Sketch Types and Characteristics:")
        for sketch_type, info in sketch_types.items():
            print(f"{sketch_type}: {info['description']}")
            print(f"  Best for: {', '.join(info['best_for'])}")
            print(f"  Tips: {', '.join(info['tips'])}")
        return sketch_types
    
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
    
    def generate_sketch2image_with_validation(self, sketch_image_data: Union[bytes, str, Path],
                                            strength: float, text_prompt: str,
                                            style_image_data: Optional[Union[bytes, str, Path]] = None,
                                            style_strength: Optional[float] = None,
                                            content_type: str = "image/jpeg") -> Dict:
        """
        Generate sketch to image transformation with parameter validation
        
        Args:
            sketch_image_data: Sketch image data
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
        
        return self.process_sketch2image_generation(
            sketch_image_data, strength, text_prompt, style_image_data, style_strength, content_type
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
    Example usage of the LightX Sketch to Image API
    """
    try:
        # Initialize with your API key
        lightx = LightXSketch2ImageAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_sketch2image_tips()
        lightx.get_strength_suggestions()
        lightx.get_style_strength_suggestions()
        lightx.get_sketch2image_prompt_examples()
        lightx.get_sketch_types()
        
        # Load images (replace with your image loading logic)
        sketch_image_path = "path/to/sketch-image.jpg"
        style_image_path = "path/to/style-image.jpg"
        
        # Example 1: Conservative sketch to image transformation
        result1 = lightx.generate_sketch2image_with_validation(
            sketch_image_path,
            0.8,  # High strength to preserve sketch structure
            "Create a realistic photograph with natural lighting and colors",
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
        result2 = lightx.generate_sketch2image_with_validation(
            sketch_image_path,
            0.5,  # Balanced strength
            "Transform into oil painting style with rich colors",
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
            result = lightx.generate_sketch2image_with_validation(
                sketch_image_path,
                strength,
                "Create a fantasy illustration with magical elements and vibrant colors",
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
        width, height = lightx.get_image_dimensions(sketch_image_path)
        if width > 0 and height > 0:
            print(f"📏 Original sketch: {width}x{height}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
