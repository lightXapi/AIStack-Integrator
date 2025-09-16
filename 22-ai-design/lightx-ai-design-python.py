"""
LightX AI Design API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered design generation functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Union
from pathlib import Path


class LightXAIDesignAPI:
    """
    LightX AI Design API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX AI Design API client
        
        Args:
            api_key (str): Your LightX API key
        """
        self.api_key = api_key
        self.base_url = "https://api.lightxeditor.com/external/api"
        self.max_retries = 5
        self.retry_interval = 3  # seconds
        
    def generate_design(self, text_prompt: str, resolution: str = "1:1", enhance_prompt: bool = True) -> str:
        """
        Generate AI design
        
        Args:
            text_prompt: Text prompt for design description
            resolution: Design resolution (1:1, 9:16, 3:4, 2:3, 16:9, 4:3)
            enhance_prompt: Whether to enhance the prompt (default: True)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXDesignException: If request fails
        """
        endpoint = f"{self.base_url}/v2/ai-design"
        
        payload = {
            "textPrompt": text_prompt,
            "resolution": resolution,
            "enhancePrompt": enhance_prompt
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
                raise LightXDesignException(f"Design request failed: {data.get('message', 'Unknown error')}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🎨 Design prompt: \"{text_prompt}\"")
            print(f"📐 Resolution: {resolution}")
            print(f"✨ Enhanced prompt: {'Yes' if enhance_prompt else 'No'}")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXDesignException(f"Network error: {str(e)}")
        except KeyError as e:
            raise LightXDesignException(f"Unexpected response format: {str(e)}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXDesignException: If request fails
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
                raise LightXDesignException(f"Status check failed: {data.get('message', 'Unknown error')}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXDesignException(f"Network error: {str(e)}")
        except KeyError as e:
            raise LightXDesignException(f"Unexpected response format: {str(e)}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXDesignException: If maximum retries reached or job fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ AI design generated successfully!")
                    if status.get("output"):
                        print(f"🎨 Design output: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXDesignException("AI design generation failed")
                
                elif status["status"] == "init":
                    attempts += 1
                    if attempts < self.max_retries:
                        print(f"⏳ Waiting {self.retry_interval} seconds before next check...")
                        time.sleep(self.retry_interval)
                
                else:
                    attempts += 1
                    if attempts < self.max_retries:
                        time.sleep(self.retry_interval)
                
            except LightXDesignException:
                raise
            except Exception as e:
                attempts += 1
                if attempts >= self.max_retries:
                    raise LightXDesignException(f"Maximum retry attempts reached: {str(e)}")
                print(f"⚠️  Error on attempt {attempts}, retrying...")
                time.sleep(self.retry_interval)
        
        raise LightXDesignException("Maximum retry attempts reached")
    
    def process_design(self, text_prompt: str, resolution: str = "1:1", enhance_prompt: bool = True) -> Dict:
        """
        Complete workflow: Generate AI design and wait for completion
        
        Args:
            text_prompt: Text prompt for design description
            resolution: Design resolution
            enhance_prompt: Whether to enhance the prompt
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Design API workflow...")
        
        # Step 1: Generate design
        print("🎨 Generating AI design...")
        order_id = self.generate_design(text_prompt, resolution, enhance_prompt)
        
        # Step 2: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_supported_resolutions(self) -> Dict:
        """
        Get supported resolutions
        
        Returns:
            Dict: Object containing supported resolutions
        """
        resolutions = {
            "1:1": {
                "name": "Square",
                "dimensions": "512 × 512",
                "description": "Perfect for social media posts, profile pictures, and square designs"
            },
            "9:16": {
                "name": "Portrait (9:16)",
                "dimensions": "289 × 512",
                "description": "Ideal for mobile stories, vertical videos, and tall designs"
            },
            "3:4": {
                "name": "Portrait (3:4)",
                "dimensions": "386 × 512",
                "description": "Great for portrait photos, magazine covers, and vertical layouts"
            },
            "2:3": {
                "name": "Portrait (2:3)",
                "dimensions": "341 × 512",
                "description": "Perfect for posters, flyers, and portrait-oriented designs"
            },
            "16:9": {
                "name": "Landscape (16:9)",
                "dimensions": "512 × 289",
                "description": "Ideal for banners, presentations, and widescreen designs"
            },
            "4:3": {
                "name": "Landscape (4:3)",
                "dimensions": "512 × 386",
                "description": "Great for traditional photos, presentations, and landscape layouts"
            }
        }
        
        print("📐 Supported Resolutions:")
        for ratio, info in resolutions.items():
            print(f"{ratio}: {info['name']} ({info['dimensions']}) - {info['description']}")
        
        return resolutions
    
    def get_design_prompt_examples(self) -> Dict:
        """
        Get design prompt examples
        
        Returns:
            Dict: Object containing prompt examples
        """
        prompt_examples = {
            "birthday_cards": [
                "BIRTHDAY CARD INVITATION with balloons and confetti",
                "Elegant birthday card with cake and candles",
                "Fun birthday invitation with party decorations",
                "Modern birthday card with geometric patterns",
                "Vintage birthday invitation with floral design"
            ],
            "posters": [
                "CONCERT POSTER with bold typography and neon colors",
                "Movie poster with dramatic lighting and action",
                "Event poster with modern minimalist design",
                "Festival poster with vibrant colors and patterns",
                "Art exhibition poster with creative typography"
            ],
            "flyers": [
                "RESTAURANT FLYER with appetizing food photos",
                "Gym membership flyer with fitness motivation",
                "Sale flyer with discount offers and prices",
                "Workshop flyer with educational theme",
                "Product launch flyer with modern design"
            ],
            "banners": [
                "WEBSITE BANNER with call-to-action button",
                "Social media banner with brand colors",
                "Advertisement banner with product showcase",
                "Event banner with date and location",
                "Promotional banner with special offers"
            ],
            "invitations": [
                "WEDDING INVITATION with elegant typography",
                "Party invitation with fun graphics",
                "Corporate event invitation with professional design",
                "Holiday party invitation with festive theme",
                "Anniversary invitation with romantic elements"
            ],
            "packaging": [
                "PRODUCT PACKAGING with modern minimalist design",
                "Food packaging with appetizing visuals",
                "Cosmetic packaging with luxury aesthetic",
                "Tech product packaging with sleek design",
                "Gift box packaging with premium feel"
            ]
        }
        
        print("💡 Design Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        
        return prompt_examples
    
    def get_design_tips(self) -> Dict:
        """
        Get design tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "text_prompts": [
                "Be specific about the design type (poster, card, banner, etc.)",
                "Include style preferences (modern, vintage, minimalist, etc.)",
                "Mention color schemes or mood you want to achieve",
                "Specify any text or typography requirements",
                "Include target audience or purpose of the design"
            ],
            "resolution_selection": [
                "Choose 1:1 for social media posts and profile pictures",
                "Use 9:16 for mobile stories and vertical content",
                "Select 2:3 for posters, flyers, and print materials",
                "Pick 16:9 for banners, presentations, and web headers",
                "Consider 4:3 for traditional photos and documents"
            ],
            "prompt_enhancement": [
                "Enable enhancePrompt for better, more detailed results",
                "Use enhancePrompt when you want richer visual elements",
                "Disable enhancePrompt for exact prompt interpretation",
                "Enhanced prompts work well for creative designs",
                "Basic prompts are better for simple, clean designs"
            ],
            "general": [
                "AI design works best with clear, descriptive prompts",
                "Results may vary based on prompt complexity and style",
                "Allow 15-30 seconds for processing",
                "Experiment with different resolutions for various use cases",
                "Combine text prompts with resolution for optimal results"
            ]
        }
        
        print("💡 AI Design Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        
        return tips
    
    def get_design_use_cases(self) -> Dict:
        """
        Get design use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "marketing": [
                "Create promotional posters and banners",
                "Generate social media content and ads",
                "Design product packaging and labels",
                "Create event flyers and invitations",
                "Generate website headers and graphics"
            ],
            "personal": [
                "Design birthday cards and invitations",
                "Create holiday greetings and cards",
                "Generate party decorations and themes",
                "Design personal branding materials",
                "Create custom artwork and prints"
            ],
            "business": [
                "Generate corporate presentation slides",
                "Create business cards and letterheads",
                "Design product catalogs and brochures",
                "Generate trade show materials",
                "Create company newsletters and reports"
            ],
            "creative": [
                "Explore artistic design concepts",
                "Generate creative project ideas",
                "Create portfolio pieces and samples",
                "Design book covers and illustrations",
                "Generate art prints and posters"
            ],
            "education": [
                "Create educational posters and charts",
                "Design course materials and handouts",
                "Generate presentation slides and graphics",
                "Create learning aids and visual guides",
                "Design school event materials"
            ]
        }
        
        print("💡 Design Use Cases:")
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
        if not text_prompt or text_prompt.strip() == "":
            print("❌ Text prompt cannot be empty")
            return False
        
        if len(text_prompt) > 500:
            print("❌ Text prompt is too long (max 500 characters)")
            return False
        
        print("✅ Text prompt is valid")
        return True
    
    def validate_resolution(self, resolution: str) -> bool:
        """
        Validate resolution (utility function)
        
        Args:
            resolution: Resolution to validate
            
        Returns:
            bool: Whether the resolution is valid
        """
        valid_resolutions = ["1:1", "9:16", "3:4", "2:3", "16:9", "4:3"]
        
        if resolution not in valid_resolutions:
            print(f"❌ Invalid resolution. Valid options: {', '.join(valid_resolutions)}")
            return False
        
        print("✅ Resolution is valid")
        return True
    
    def generate_design_with_validation(self, text_prompt: str, resolution: str = "1:1", enhance_prompt: bool = True) -> Dict:
        """
        Generate design with validation
        
        Args:
            text_prompt: Text prompt for design description
            resolution: Design resolution
            enhance_prompt: Whether to enhance the prompt
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise LightXDesignException("Invalid text prompt")
        
        if not self.validate_resolution(resolution):
            raise LightXDesignException("Invalid resolution")
        
        return self.process_design(text_prompt, resolution, enhance_prompt)


class LightXDesignException(Exception):
    """Custom exception for LightX Design API errors"""
    pass


# Example usage
def run_example():
    try:
        # Initialize with your API key
        lightx = LightXAIDesignAPI("YOUR_API_KEY_HERE")
        
        # Get tips and examples
        lightx.get_supported_resolutions()
        lightx.get_design_prompt_examples()
        lightx.get_design_tips()
        lightx.get_design_use_cases()
        
        # Example 1: Birthday card design
        result1 = lightx.generate_design_with_validation(
            "BIRTHDAY CARD INVITATION with balloons and confetti",
            "2:3",
            True
        )
        print("🎉 Birthday card design result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Poster design
        result2 = lightx.generate_design_with_validation(
            "CONCERT POSTER with bold typography and neon colors",
            "16:9",
            True
        )
        print("🎉 Concert poster design result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Try different design types
        design_types = [
            {"prompt": "RESTAURANT FLYER with appetizing food photos", "resolution": "2:3"},
            {"prompt": "WEBSITE BANNER with call-to-action button", "resolution": "16:9"},
            {"prompt": "WEDDING INVITATION with elegant typography", "resolution": "3:4"},
            {"prompt": "PRODUCT PACKAGING with modern minimalist design", "resolution": "1:1"},
            {"prompt": "SOCIAL MEDIA POST with vibrant colors", "resolution": "1:1"}
        ]
        
        for design in design_types:
            result = lightx.generate_design_with_validation(
                design["prompt"],
                design["resolution"],
                True
            )
            print(f"🎉 {design['prompt']} result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
    
    except LightXDesignException as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
