"""
LightX Hair Color RGB API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered hair color changing using hex color codes.
"""

import requests
import time
import json
import re
from typing import Dict, List, Optional, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXHairColorRGBAPI:
    """
    LightX Hair Color RGB API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX Hair Color RGB API client
        
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
            LightXHairColorRGBException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise LightXHairColorRGBException(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
            file_size = image_path.stat().st_size
        elif isinstance(image_data, bytes):
            image_bytes = image_data
            file_size = len(image_bytes)
        else:
            raise LightXHairColorRGBException("Invalid image data provided")
        
        if file_size > self.max_file_size:
            raise LightXHairColorRGBException("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def change_hair_color(self, image_url: str, hair_hex_color: str, color_strength: float = 0.5) -> str:
        """
        Change hair color using hex color code
        
        Args:
            image_url: URL of the input image
            hair_hex_color: Hex color code (e.g., "#FF0000")
            color_strength: Color strength between 0.1 to 1
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXHairColorRGBException: If request fails
        """
        # Validate hex color
        if not self.is_valid_hex_color(hair_hex_color):
            raise LightXHairColorRGBException("Invalid hex color format. Use format like #FF0000")
        
        # Validate color strength
        if color_strength < 0.1 or color_strength > 1:
            raise LightXHairColorRGBException("Color strength must be between 0.1 and 1")
        
        endpoint = f"{self.base_url}/v2/haircolor-rgb"
        
        payload = {
            "imageUrl": image_url,
            "hairHexColor": hair_hex_color,
            "colorStrength": color_strength
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
                raise LightXHairColorRGBException(f"Hair color change request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🎨 Hair color: {hair_hex_color}")
            print(f"💪 Color strength: {color_strength}")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHairColorRGBException(f"Network error: {e}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXHairColorRGBException: If request fails
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
                raise LightXHairColorRGBException(f"Status check failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHairColorRGBException(f"Network error: {e}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXHairColorRGBException: If maximum retries reached or processing fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Hair color change completed successfully!")
                    if status.get("output"):
                        print(f"🎨 Hair color result: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXHairColorRGBException("Hair color change failed")
                
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
        
        raise LightXHairColorRGBException("Maximum retry attempts reached")
    
    def process_hair_color_change(self, image_data: Union[str, bytes, Path], hair_hex_color: str, color_strength: float = 0.5, content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload image and change hair color
        
        Args:
            image_data: Image file path, bytes, or Path object
            hair_hex_color: Hex color code
            color_strength: Color strength between 0.1 to 1
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX Hair Color RGB API workflow...")
        
        # Step 1: Upload image
        print("📤 Uploading image...")
        image_url = self.upload_image(image_data, content_type)
        print(f"✅ Image uploaded: {image_url}")
        
        # Step 2: Change hair color
        print("🎨 Changing hair color...")
        order_id = self.change_hair_color(image_url, hair_hex_color, color_strength)
        
        # Step 3: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_hair_color_tips(self) -> Dict[str, List[str]]:
        """
        Get hair color tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "input_image": [
                "Use clear, well-lit photos with good hair visibility",
                "Ensure the person's hair is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best hair color results",
                "Good lighting helps preserve hair texture and details"
            ],
            "hex_colors": [
                "Use valid hex color codes in format #RRGGBB",
                "Common hair colors: #000000 (black), #8B4513 (brown), #FFD700 (blonde)",
                "Experiment with different shades for natural-looking results",
                "Consider skin tone compatibility when choosing colors",
                "Use color strength to control intensity of the color change"
            ],
            "color_strength": [
                "Lower values (0.1-0.3) create subtle color changes",
                "Medium values (0.4-0.7) provide balanced color intensity",
                "Higher values (0.8-1.0) create bold, vibrant color changes",
                "Start with medium strength and adjust based on results",
                "Consider the original hair color when setting strength"
            ],
            "hair_visibility": [
                "Ensure the person's hair is clearly visible",
                "Avoid images where hair is heavily covered or obscured",
                "Use images with good hair definition and texture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "general": [
                "AI hair color change works best with clear, detailed source images",
                "Results may vary based on input image quality and hair visibility",
                "Hair color change preserves hair texture while changing color",
                "Allow 15-30 seconds for processing",
                "Experiment with different colors and strengths for varied results"
            ]
        }
        
        print("💡 Hair Color Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_popular_hair_colors(self) -> Dict[str, List[Dict]]:
        """
        Get popular hair color hex codes
        
        Returns:
            Dict: Object containing popular hair color hex codes
        """
        hair_colors = {
            "natural_blondes": [
                {"name": "Platinum Blonde", "hex": "#F5F5DC", "strength": 0.7},
                {"name": "Golden Blonde", "hex": "#FFD700", "strength": 0.6},
                {"name": "Honey Blonde", "hex": "#DAA520", "strength": 0.5},
                {"name": "Strawberry Blonde", "hex": "#D2691E", "strength": 0.6},
                {"name": "Ash Blonde", "hex": "#C0C0C0", "strength": 0.5}
            ],
            "natural_browns": [
                {"name": "Light Brown", "hex": "#8B4513", "strength": 0.5},
                {"name": "Medium Brown", "hex": "#654321", "strength": 0.6},
                {"name": "Dark Brown", "hex": "#3C2414", "strength": 0.7},
                {"name": "Chestnut Brown", "hex": "#954535", "strength": 0.5},
                {"name": "Auburn Brown", "hex": "#A52A2A", "strength": 0.6}
            ],
            "natural_blacks": [
                {"name": "Jet Black", "hex": "#000000", "strength": 0.8},
                {"name": "Soft Black", "hex": "#1C1C1C", "strength": 0.7},
                {"name": "Blue Black", "hex": "#0A0A0A", "strength": 0.8},
                {"name": "Brown Black", "hex": "#2F1B14", "strength": 0.6}
            ],
            "fashion_colors": [
                {"name": "Vibrant Red", "hex": "#FF0000", "strength": 0.8},
                {"name": "Purple", "hex": "#800080", "strength": 0.7},
                {"name": "Blue", "hex": "#0000FF", "strength": 0.7},
                {"name": "Pink", "hex": "#FF69B4", "strength": 0.6},
                {"name": "Green", "hex": "#008000", "strength": 0.6},
                {"name": "Orange", "hex": "#FFA500", "strength": 0.7}
            ],
            "highlights": [
                {"name": "Blonde Highlights", "hex": "#FFD700", "strength": 0.4},
                {"name": "Red Highlights", "hex": "#FF4500", "strength": 0.3},
                {"name": "Purple Highlights", "hex": "#9370DB", "strength": 0.3},
                {"name": "Blue Highlights", "hex": "#4169E1", "strength": 0.3}
            ]
        }
        
        print("💡 Popular Hair Colors:")
        for category, color_list in hair_colors.items():
            print(f"{category}:")
            for color in color_list:
                print(f"  {color['name']}: {color['hex']} (strength: {color['strength']})")
        return hair_colors
    
    def get_hair_color_use_cases(self) -> Dict[str, List[str]]:
        """
        Get hair color use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "virtual_makeovers": [
                "Virtual hair color try-on",
                "Makeover simulation apps",
                "Beauty consultation tools",
                "Personal styling experiments",
                "Virtual hair color previews"
            ],
            "beauty_platforms": [
                "Beauty app hair color features",
                "Salon consultation tools",
                "Hair color recommendation systems",
                "Beauty influencer content",
                "Hair color trend visualization"
            ],
            "personal_styling": [
                "Personal style exploration",
                "Hair color decision making",
                "Style consultation services",
                "Personal fashion experiments",
                "Individual styling assistance"
            ],
            "social_media": [
                "Social media hair color posts",
                "Beauty influencer content",
                "Hair color sharing platforms",
                "Beauty community features",
                "Social beauty experiences"
            ],
            "entertainment": [
                "Character hair color changes",
                "Costume design and visualization",
                "Creative hair color concepts",
                "Artistic hair color expressions",
                "Entertainment industry applications"
            ]
        }
        
        print("💡 Hair Color Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
    def get_hair_color_best_practices(self) -> Dict[str, List[str]]:
        """
        Get hair color best practices
        
        Returns:
            Dict: Object containing best practices
        """
        best_practices = {
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in the original image",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined hair details",
                "Ensure the person's hair is clearly visible and well-lit"
            ],
            "color_selection": [
                "Choose hex colors that complement skin tone",
                "Consider the original hair color when selecting new colors",
                "Use color strength to control the intensity of change",
                "Experiment with different shades for natural results",
                "Test multiple color options to find the best match"
            ],
            "strength_control": [
                "Start with medium strength (0.5) and adjust as needed",
                "Use lower strength for subtle, natural-looking changes",
                "Use higher strength for bold, dramatic color changes",
                "Consider the contrast with the original hair color",
                "Balance color intensity with natural appearance"
            ],
            "workflow_optimization": [
                "Batch process multiple color variations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        }
        
        print("💡 Hair Color Best Practices:")
        for category, practice_list in best_practices.items():
            print(f"{category}: {practice_list}")
        return best_practices
    
    def get_hair_color_performance_tips(self) -> Dict[str, List[str]]:
        """
        Get hair color performance tips
        
        Returns:
            Dict: Object containing performance tips
        """
        performance_tips = {
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple color variations"
            ],
            "resource_management": [
                "Monitor memory usage during processing",
                "Implement proper cleanup of temporary files",
                "Use efficient image loading and processing libraries",
                "Consider implementing image compression after processing",
                "Optimize network requests and retry logic"
            ],
            "user_experience": [
                "Provide clear progress indicators",
                "Set realistic expectations for processing times",
                "Implement proper error handling and user feedback",
                "Offer color previews when possible",
                "Provide tips for better input images"
            ]
        }
        
        print("💡 Performance Tips:")
        for category, tip_list in performance_tips.items():
            print(f"{category}: {tip_list}")
        return performance_tips
    
    def get_hair_color_technical_specifications(self) -> Dict[str, Dict[str, any]]:
        """
        Get hair color technical specifications
        
        Returns:
            Dict: Object containing technical specifications
        """
        specifications = {
            "supported_formats": {
                "input": ["JPEG", "PNG"],
                "output": ["JPEG"],
                "color_spaces": ["RGB", "sRGB"]
            },
            "size_limits": {
                "max_file_size": "5MB",
                "max_dimension": "No specific limit",
                "min_dimension": "1px"
            },
            "processing": {
                "max_retries": 5,
                "retry_interval": "3 seconds",
                "avg_processing_time": "15-30 seconds",
                "timeout": "No timeout limit"
            },
            "features": {
                "hex_color_codes": "Required for precise color specification",
                "color_strength": "Controls intensity of color change (0.1 to 1.0)",
                "hair_detection": "Automatic hair detection and segmentation",
                "texture_preservation": "Preserves original hair texture and style",
                "output_quality": "High-quality JPEG output"
            }
        }
        
        print("💡 Hair Color Technical Specifications:")
        for category, specs in specifications.items():
            print(f"{category}: {specs}")
        return specifications
    
    def get_hair_color_workflow_examples(self) -> Dict[str, List[str]]:
        """
        Get hair color workflow examples
        
        Returns:
            Dict: Object containing workflow examples
        """
        workflow_examples = {
            "basic_workflow": [
                "1. Prepare high-quality input image with clear hair visibility",
                "2. Choose desired hair color hex code",
                "3. Set appropriate color strength (0.1 to 1.0)",
                "4. Upload image to LightX servers",
                "5. Submit hair color change request",
                "6. Monitor order status until completion",
                "7. Download hair color result"
            ],
            "advanced_workflow": [
                "1. Prepare input image with clear hair definition",
                "2. Select multiple color options for comparison",
                "3. Set different strength values for each color",
                "4. Upload image to LightX servers",
                "5. Submit multiple hair color requests",
                "6. Monitor all orders with retry logic",
                "7. Compare and select best results",
                "8. Apply additional processing if needed"
            ],
            "batch_workflow": [
                "1. Prepare multiple input images",
                "2. Create color palette for batch processing",
                "3. Upload all images in parallel",
                "4. Submit multiple hair color requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            ]
        }
        
        print("💡 Hair Color Workflow Examples:")
        for workflow, step_list in workflow_examples.items():
            print(f"{workflow}:")
            for step in step_list:
                print(f"  {step}")
        return workflow_examples
    
    def is_valid_hex_color(self, hex_color: str) -> bool:
        """
        Validate hex color format
        
        Args:
            hex_color: Hex color to validate
            
        Returns:
            bool: Whether the hex color is valid
        """
        hex_pattern = r'^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$'
        return bool(re.match(hex_pattern, hex_color))
    
    def rgb_to_hex(self, r: int, g: int, b: int) -> str:
        """
        Convert RGB to hex color
        
        Args:
            r: Red value (0-255)
            g: Green value (0-255)
            b: Blue value (0-255)
            
        Returns:
            str: Hex color code
        """
        def to_hex(n):
            hex_val = hex(max(0, min(255, round(n))))[2:]
            return hex_val.zfill(2)
        
        return f"#{to_hex(r)}{to_hex(g)}{to_hex(b)}".upper()
    
    def hex_to_rgb(self, hex_color: str) -> Optional[Tuple[int, int, int]]:
        """
        Convert hex color to RGB
        
        Args:
            hex_color: Hex color code
            
        Returns:
            Optional[Tuple[int, int, int]]: RGB values or None if invalid
        """
        hex_color = hex_color.lstrip('#')
        if len(hex_color) == 3:
            hex_color = ''.join([c*2 for c in hex_color])
        
        try:
            return tuple(int(hex_color[i:i+2], 16) for i in (0, 2, 4))
        except ValueError:
            return None
    
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
    
    def change_hair_color_with_validation(self, image_data: Union[str, bytes, Path], hair_hex_color: str, color_strength: float = 0.5, content_type: str = "image/jpeg") -> Dict:
        """
        Change hair color with validation
        
        Args:
            image_data: Image file path, bytes, or Path object
            hair_hex_color: Hex color code
            color_strength: Color strength between 0.1 to 1
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.is_valid_hex_color(hair_hex_color):
            raise LightXHairColorRGBException("Invalid hex color format. Use format like #FF0000")
        
        if color_strength < 0.1 or color_strength > 1:
            raise LightXHairColorRGBException("Color strength must be between 0.1 and 1")
        
        return self.process_hair_color_change(image_data, hair_hex_color, color_strength, content_type)
    
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
                raise LightXHairColorRGBException(f"Upload URL request failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXHairColorRGBException(f"Network error: {e}")
    
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
            raise LightXHairColorRGBException(f"Upload error: {e}")


class LightXHairColorRGBException(Exception):
    """Custom exception for LightX Hair Color RGB API errors"""
    pass


def run_example():
    """Example usage of the LightX Hair Color RGB API"""
    try:
        # Initialize with your API key
        lightx = LightXHairColorRGBAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_hair_color_tips()
        lightx.get_popular_hair_colors()
        lightx.get_hair_color_use_cases()
        lightx.get_hair_color_best_practices()
        lightx.get_hair_color_performance_tips()
        lightx.get_hair_color_technical_specifications()
        lightx.get_hair_color_workflow_examples()
        
        # Load image (replace with your image path)
        image_path = "path/to/input-image.jpg"
        
        # Example 1: Natural blonde hair
        result1 = lightx.change_hair_color_with_validation(
            image_path,
            "#FFD700",  # Golden blonde
            0.6,  # Medium strength
            "image/jpeg"
        )
        print("🎉 Golden blonde result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Fashion color - vibrant red
        result2 = lightx.change_hair_color_with_validation(
            image_path,
            "#FF0000",  # Vibrant red
            0.8,  # High strength
            "image/jpeg"
        )
        print("🎉 Vibrant red result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Try different hair colors
        hair_colors = [
            {"color": "#000000", "name": "Jet Black", "strength": 0.8},
            {"color": "#8B4513", "name": "Light Brown", "strength": 0.5},
            {"color": "#800080", "name": "Purple", "strength": 0.7},
            {"color": "#FF69B4", "name": "Pink", "strength": 0.6},
            {"color": "#0000FF", "name": "Blue", "strength": 0.7}
        ]
        
        for hair_color in hair_colors:
            result = lightx.change_hair_color_with_validation(
                image_path,
                hair_color["color"],
                hair_color["strength"],
                "image/jpeg"
            )
            print(f"🎉 {hair_color['name']} result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 4: Color conversion utilities
        rgb = lightx.hex_to_rgb("#FFD700")
        print(f"RGB values for #FFD700: {rgb}")
        
        hex_color = lightx.rgb_to_hex(255, 215, 0)
        print(f"Hex for RGB(255, 215, 0): {hex_color}")
        
        # Example 5: Get image dimensions
        dimensions = lightx.get_image_dimensions(image_path)
        if dimensions[0] > 0 and dimensions[1] > 0:
            print(f"📏 Original image: {dimensions[0]}x{dimensions[1]}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
