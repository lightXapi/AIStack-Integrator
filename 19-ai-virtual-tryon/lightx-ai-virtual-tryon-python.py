"""
LightX AI Virtual Outfit Try-On API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered virtual outfit try-on functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Tuple, Union
from pathlib import Path
from PIL import Image
import io


class LightXAIVirtualTryOnAPI:
    """
    LightX AI Virtual Outfit Try-On API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX AI Virtual Outfit Try-On API client
        
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
            LightXVirtualTryOnException: If upload fails
        """
        # Handle different input types
        if isinstance(image_data, (str, Path)):
            image_path = Path(image_data)
            if not image_path.exists():
                raise LightXVirtualTryOnException(f"Image file not found: {image_path}")
            with open(image_path, 'rb') as f:
                image_bytes = f.read()
            file_size = image_path.stat().st_size
        elif isinstance(image_data, bytes):
            image_bytes = image_data
            file_size = len(image_bytes)
        else:
            raise LightXVirtualTryOnException("Invalid image data provided")
        
        if file_size > self.max_file_size:
            raise LightXVirtualTryOnException("Image size exceeds 5MB limit")
        
        # Step 1: Get upload URL
        upload_url = self._get_upload_url(file_size, content_type)
        
        # Step 2: Upload image to S3
        self._upload_to_s3(upload_url["uploadImage"], image_bytes, content_type)
        
        print("✅ Image uploaded successfully")
        return upload_url["imageUrl"]
    
    def try_on_outfit(self, image_url: str, style_image_url: str) -> str:
        """
        Try on virtual outfit using AI
        
        Args:
            image_url: URL of the input image (person)
            style_image_url: URL of the outfit reference image
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXVirtualTryOnException: If request fails
        """
        endpoint = f"{self.base_url}/v2/aivirtualtryon"
        
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
                raise LightXVirtualTryOnException(f"Virtual try-on request failed: {data['message']}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"👤 Person image: {image_url}")
            print(f"👗 Outfit image: {style_image_url}")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXVirtualTryOnException(f"Network error: {e}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXVirtualTryOnException: If request fails
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
                raise LightXVirtualTryOnException(f"Status check failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXVirtualTryOnException(f"Network error: {e}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXVirtualTryOnException: If maximum retries reached or processing fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ Virtual outfit try-on completed successfully!")
                    if status.get("output"):
                        print(f"👗 Virtual try-on result: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXVirtualTryOnException("Virtual outfit try-on failed")
                
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
        
        raise LightXVirtualTryOnException("Maximum retry attempts reached")
    
    def process_virtual_try_on(self, person_image_data: Union[str, bytes, Path], outfit_image_data: Union[str, bytes, Path], content_type: str = "image/jpeg") -> Dict:
        """
        Complete workflow: Upload images and try on virtual outfit
        
        Args:
            person_image_data: Person image file path, bytes, or Path object
            outfit_image_data: Outfit reference image file path, bytes, or Path object
            content_type: MIME type
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Virtual Outfit Try-On API workflow...")
        
        # Step 1: Upload person image
        print("📤 Uploading person image...")
        person_image_url = self.upload_image(person_image_data, content_type)
        print(f"✅ Person image uploaded: {person_image_url}")
        
        # Step 2: Upload outfit image
        print("📤 Uploading outfit image...")
        outfit_image_url = self.upload_image(outfit_image_data, content_type)
        print(f"✅ Outfit image uploaded: {outfit_image_url}")
        
        # Step 3: Try on virtual outfit
        print("👗 Trying on virtual outfit...")
        order_id = self.try_on_outfit(person_image_url, outfit_image_url)
        
        # Step 4: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_virtual_try_on_tips(self) -> Dict[str, List[str]]:
        """
        Get virtual try-on tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "person_image": [
                "Use clear, well-lit photos with good body visibility",
                "Ensure the person is clearly visible and well-positioned",
                "Avoid heavily compressed or low-quality source images",
                "Use high-quality source images for best virtual try-on results",
                "Good lighting helps preserve body shape and details"
            ],
            "outfit_image": [
                "Use clear outfit reference images with good detail",
                "Ensure the outfit is clearly visible and well-lit",
                "Choose outfit images with good color and texture definition",
                "Use high-quality outfit images for better transfer results",
                "Good outfit image quality improves virtual try-on accuracy"
            ],
            "body_visibility": [
                "Ensure the person's body is clearly visible",
                "Avoid images where the person is heavily covered",
                "Use images with good body definition and posture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "outfit_selection": [
                "Choose outfit images that match the person's body type",
                "Select outfits with clear, well-defined shapes",
                "Use outfit images with good color contrast",
                "Ensure outfit images show the complete garment",
                "Choose outfits that complement the person's style"
            ],
            "general": [
                "AI virtual try-on works best with clear, detailed source images",
                "Results may vary based on input image quality and outfit visibility",
                "Virtual try-on preserves body shape and facial features",
                "Allow 15-30 seconds for processing",
                "Experiment with different outfit combinations for varied results"
            ]
        }
        
        print("💡 Virtual Try-On Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        return tips
    
    def get_outfit_category_suggestions(self) -> Dict[str, List[str]]:
        """
        Get outfit category suggestions
        
        Returns:
            Dict: Object containing outfit category suggestions
        """
        outfit_categories = {
            "casual": [
                "Casual t-shirts and jeans",
                "Comfortable hoodies and sweatpants",
                "Everyday dresses and skirts",
                "Casual blouses and trousers",
                "Relaxed shirts and shorts"
            ],
            "formal": [
                "Business suits and blazers",
                "Formal dresses and gowns",
                "Dress shirts and dress pants",
                "Professional blouses and skirts",
                "Elegant evening wear"
            ],
            "party": [
                "Party dresses and outfits",
                "Cocktail dresses and suits",
                "Festive clothing and accessories",
                "Celebration wear and costumes",
                "Special occasion outfits"
            ],
            "seasonal": [
                "Summer dresses and shorts",
                "Winter coats and sweaters",
                "Spring jackets and light layers",
                "Fall clothing and warm accessories",
                "Seasonal fashion trends"
            ],
            "sportswear": [
                "Athletic wear and gym clothes",
                "Sports jerseys and team wear",
                "Activewear and workout gear",
                "Running clothes and sneakers",
                "Fitness and sports apparel"
            ]
        }
        
        print("💡 Outfit Category Suggestions:")
        for category, suggestion_list in outfit_categories.items():
            print(f"{category}: {suggestion_list}")
        return outfit_categories
    
    def get_virtual_try_on_use_cases(self) -> Dict[str, List[str]]:
        """
        Get virtual try-on use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "e_commerce": [
                "Online shopping virtual try-on",
                "E-commerce product visualization",
                "Online store outfit previews",
                "Virtual fitting room experiences",
                "Online shopping assistance"
            ],
            "fashion_retail": [
                "Fashion store virtual try-on",
                "Retail outfit visualization",
                "In-store virtual fitting",
                "Fashion consultation tools",
                "Retail customer experience"
            ],
            "personal_styling": [
                "Personal style exploration",
                "Virtual wardrobe try-on",
                "Style consultation services",
                "Personal fashion experiments",
                "Individual styling assistance"
            ],
            "social_media": [
                "Social media outfit posts",
                "Fashion influencer content",
                "Style sharing platforms",
                "Fashion community features",
                "Social fashion experiences"
            ],
            "entertainment": [
                "Character outfit changes",
                "Costume design and visualization",
                "Creative outfit concepts",
                "Artistic fashion expressions",
                "Entertainment industry applications"
            ]
        }
        
        print("💡 Virtual Try-On Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        return use_cases
    
    def get_outfit_style_suggestions(self) -> Dict[str, List[str]]:
        """
        Get outfit style suggestions
        
        Returns:
            Dict: Object containing style suggestions
        """
        style_suggestions = {
            "classic": [
                "Classic business attire",
                "Traditional formal wear",
                "Timeless casual outfits",
                "Classic evening wear",
                "Traditional professional dress"
            ],
            "modern": [
                "Contemporary fashion trends",
                "Modern casual wear",
                "Current style favorites",
                "Trendy outfit combinations",
                "Modern fashion statements"
            ],
            "vintage": [
                "Retro fashion styles",
                "Vintage clothing pieces",
                "Classic era outfits",
                "Nostalgic fashion trends",
                "Historical style references"
            ],
            "bohemian": [
                "Bohemian style outfits",
                "Free-spirited fashion",
                "Artistic clothing choices",
                "Creative style expressions",
                "Alternative fashion trends"
            ],
            "minimalist": [
                "Simple, clean outfits",
                "Minimalist fashion choices",
                "Understated style pieces",
                "Clean, modern aesthetics",
                "Simple fashion statements"
            ]
        }
        
        print("💡 Outfit Style Suggestions:")
        for style, suggestion_list in style_suggestions.items():
            print(f"{style}: {suggestion_list}")
        return style_suggestions
    
    def get_virtual_try_on_best_practices(self) -> Dict[str, List[str]]:
        """
        Get virtual try-on best practices
        
        Returns:
            Dict: Object containing best practices
        """
        best_practices = {
            "image_preparation": [
                "Start with high-quality source images",
                "Ensure good lighting and contrast in both images",
                "Avoid heavily compressed or low-quality images",
                "Use images with clear, well-defined details",
                "Ensure both person and outfit are clearly visible"
            ],
            "outfit_selection": [
                "Choose outfit images that complement the person",
                "Select outfits with clear, well-defined shapes",
                "Use outfit images with good color contrast",
                "Ensure outfit images show the complete garment",
                "Choose outfits that match the person's body type"
            ],
            "body_visibility": [
                "Ensure the person's body is clearly visible",
                "Avoid images where the person is heavily covered",
                "Use images with good body definition and posture",
                "Ensure the person's face is clearly visible",
                "Avoid images with extreme angles or poor lighting"
            ],
            "workflow_optimization": [
                "Batch process multiple outfit combinations when possible",
                "Implement proper error handling and retry logic",
                "Monitor processing times and adjust expectations",
                "Store results efficiently to avoid reprocessing",
                "Implement progress tracking for better user experience"
            ]
        }
        
        print("💡 Virtual Try-On Best Practices:")
        for category, practice_list in best_practices.items():
            print(f"{category}: {practice_list}")
        return best_practices
    
    def get_virtual_try_on_performance_tips(self) -> Dict[str, List[str]]:
        """
        Get virtual try-on performance tips
        
        Returns:
            Dict: Object containing performance tips
        """
        performance_tips = {
            "optimization": [
                "Use appropriate image formats (JPEG for photos, PNG for graphics)",
                "Optimize source images before processing",
                "Consider image dimensions and quality trade-offs",
                "Implement caching for frequently processed images",
                "Use batch processing for multiple outfit combinations"
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
                "Offer outfit previews when possible",
                "Provide tips for better input images"
            ]
        }
        
        print("💡 Performance Tips:")
        for category, tip_list in performance_tips.items():
            print(f"{category}: {tip_list}")
        return performance_tips
    
    def get_virtual_try_on_technical_specifications(self) -> Dict[str, Dict[str, Any]]:
        """
        Get virtual try-on technical specifications
        
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
                "person_detection": "Automatic person detection and body segmentation",
                "outfit_transfer": "Seamless outfit transfer onto person",
                "body_preservation": "Preserves body shape and facial features",
                "realistic_rendering": "Realistic outfit fitting and appearance",
                "output_quality": "High-quality JPEG output"
            }
        }
        
        print("💡 Virtual Try-On Technical Specifications:")
        for category, specs in specifications.items():
            print(f"{category}: {specs}")
        return specifications
    
    def get_virtual_try_on_workflow_examples(self) -> Dict[str, List[str]]:
        """
        Get virtual try-on workflow examples
        
        Returns:
            Dict: Object containing workflow examples
        """
        workflow_examples = {
            "basic_workflow": [
                "1. Prepare high-quality person image",
                "2. Select outfit reference image",
                "3. Upload both images to LightX servers",
                "4. Submit virtual try-on request",
                "5. Monitor order status until completion",
                "6. Download virtual try-on result"
            ],
            "advanced_workflow": [
                "1. Prepare person image with clear body visibility",
                "2. Select outfit image with good detail and contrast",
                "3. Upload both images to LightX servers",
                "4. Submit virtual try-on request with validation",
                "5. Monitor processing with retry logic",
                "6. Validate and download result",
                "7. Apply additional processing if needed"
            ],
            "batch_workflow": [
                "1. Prepare multiple person images",
                "2. Select multiple outfit combinations",
                "3. Upload all images in parallel",
                "4. Submit multiple virtual try-on requests",
                "5. Monitor all orders concurrently",
                "6. Collect and organize results",
                "7. Apply quality control and validation"
            ]
        }
        
        print("💡 Virtual Try-On Workflow Examples:")
        for workflow, step_list in workflow_examples.items():
            print(f"{workflow}:")
            for step in step_list:
                print(f"  {step}")
        return workflow_examples
    
    def get_outfit_combination_suggestions(self) -> Dict[str, List[str]]:
        """
        Get outfit combination suggestions
        
        Returns:
            Dict: Object containing combination suggestions
        """
        combinations = {
            "casual_combinations": [
                "T-shirt with jeans and sneakers",
                "Hoodie with joggers and casual shoes",
                "Blouse with trousers and flats",
                "Sweater with skirt and boots",
                "Polo shirt with shorts and sandals"
            ],
            "formal_combinations": [
                "Blazer with dress pants and dress shoes",
                "Dress shirt with suit and formal shoes",
                "Blouse with pencil skirt and heels",
                "Dress with blazer and pumps",
                "Suit with dress shirt and oxfords"
            ],
            "party_combinations": [
                "Cocktail dress with heels and accessories",
                "Party top with skirt and party shoes",
                "Evening gown with elegant accessories",
                "Festive outfit with matching accessories",
                "Celebration wear with themed accessories"
            ],
            "seasonal_combinations": [
                "Summer dress with sandals and sun hat",
                "Winter coat with boots and scarf",
                "Spring jacket with light layers and sneakers",
                "Fall sweater with jeans and ankle boots",
                "Seasonal outfit with appropriate accessories"
            ]
        }
        
        print("💡 Outfit Combination Suggestions:")
        for category, combination_list in combinations.items():
            print(f"{category}: {combination_list}")
        return combinations
    
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
                raise LightXVirtualTryOnException(f"Upload URL request failed: {data['message']}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXVirtualTryOnException(f"Network error: {e}")
    
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
            raise LightXVirtualTryOnException(f"Upload error: {e}")


class LightXVirtualTryOnException(Exception):
    """Custom exception for LightX Virtual Try-On API errors"""
    pass


def run_example():
    """Example usage of the LightX AI Virtual Outfit Try-On API"""
    try:
        # Initialize with your API key
        lightx = LightXAIVirtualTryOnAPI("YOUR_API_KEY_HERE")
        
        # Get tips for better results
        lightx.get_virtual_try_on_tips()
        lightx.get_outfit_category_suggestions()
        lightx.get_virtual_try_on_use_cases()
        lightx.get_outfit_style_suggestions()
        lightx.get_virtual_try_on_best_practices()
        lightx.get_virtual_try_on_performance_tips()
        lightx.get_virtual_try_on_technical_specifications()
        lightx.get_virtual_try_on_workflow_examples()
        lightx.get_outfit_combination_suggestions()
        
        # Load images (replace with your image paths)
        person_image_path = "path/to/person-image.jpg"
        outfit_image_path = "path/to/outfit-image.jpg"
        
        # Example 1: Casual outfit try-on
        result1 = lightx.process_virtual_try_on(
            person_image_path,
            outfit_image_path,
            "image/jpeg"
        )
        print("🎉 Casual outfit try-on result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Try different outfit combinations
        outfit_combinations = [
            "path/to/casual-outfit.jpg",
            "path/to/formal-outfit.jpg",
            "path/to/party-outfit.jpg",
            "path/to/sportswear-outfit.jpg",
            "path/to/seasonal-outfit.jpg"
        ]
        
        for outfit_path in outfit_combinations:
            result = lightx.process_virtual_try_on(
                person_image_path,
                outfit_path,
                "image/jpeg"
            )
            print(f"🎉 {outfit_path} try-on result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
        
        # Example 3: Get image dimensions
        person_dimensions = lightx.get_image_dimensions(person_image_path)
        outfit_dimensions = lightx.get_image_dimensions(outfit_image_path)
        
        if person_dimensions[0] > 0 and person_dimensions[1] > 0:
            print(f"📏 Person image: {person_dimensions[0]}x{person_dimensions[1]}")
        if outfit_dimensions[0] > 0 and outfit_dimensions[1] > 0:
            print(f"📏 Outfit image: {outfit_dimensions[0]}x{outfit_dimensions[1]}")
        
    except Exception as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
