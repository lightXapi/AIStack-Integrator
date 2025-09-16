"""
LightX AI Logo Generator API Integration - Python

This implementation provides a complete integration with LightX API v2
for AI-powered logo generation functionality.
"""

import requests
import time
import json
from typing import Dict, List, Optional, Union
from pathlib import Path


class LightXAILogoGeneratorAPI:
    """
    LightX AI Logo Generator API client for Python
    """
    
    def __init__(self, api_key: str):
        """
        Initialize the LightX AI Logo Generator API client
        
        Args:
            api_key (str): Your LightX API key
        """
        self.api_key = api_key
        self.base_url = "https://api.lightxeditor.com/external/api"
        self.max_retries = 5
        self.retry_interval = 3  # seconds
        
    def generate_logo(self, text_prompt: str, enhance_prompt: bool = True) -> str:
        """
        Generate AI logo
        
        Args:
            text_prompt: Text prompt for logo description
            enhance_prompt: Whether to enhance the prompt (default: True)
            
        Returns:
            str: Order ID for tracking
            
        Raises:
            LightXLogoGeneratorException: If request fails
        """
        endpoint = f"{self.base_url}/v2/logo-generator"
        
        payload = {
            "textPrompt": text_prompt,
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
                raise LightXLogoGeneratorException(f"Logo request failed: {data.get('message', 'Unknown error')}")
            
            order_info = data["body"]
            
            print(f"📋 Order created: {order_info['orderId']}")
            print(f"🔄 Max retries allowed: {order_info['maxRetriesAllowed']}")
            print(f"⏱️  Average response time: {order_info['avgResponseTimeInSec']} seconds")
            print(f"📊 Status: {order_info['status']}")
            print(f"🎨 Logo prompt: \"{text_prompt}\"")
            print(f"✨ Enhanced prompt: {'Yes' if enhance_prompt else 'No'}")
            
            return order_info["orderId"]
            
        except requests.exceptions.RequestException as e:
            raise LightXLogoGeneratorException(f"Network error: {str(e)}")
        except KeyError as e:
            raise LightXLogoGeneratorException(f"Unexpected response format: {str(e)}")
    
    def check_order_status(self, order_id: str) -> Dict:
        """
        Check order status
        
        Args:
            order_id: Order ID to check
            
        Returns:
            Dict: Order status and results
            
        Raises:
            LightXLogoGeneratorException: If request fails
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
                raise LightXLogoGeneratorException(f"Status check failed: {data.get('message', 'Unknown error')}")
            
            return data["body"]
            
        except requests.exceptions.RequestException as e:
            raise LightXLogoGeneratorException(f"Network error: {str(e)}")
        except KeyError as e:
            raise LightXLogoGeneratorException(f"Unexpected response format: {str(e)}")
    
    def wait_for_completion(self, order_id: str) -> Dict:
        """
        Wait for order completion with automatic retries
        
        Args:
            order_id: Order ID to monitor
            
        Returns:
            Dict: Final result with output URL
            
        Raises:
            LightXLogoGeneratorException: If maximum retries reached or job fails
        """
        attempts = 0
        
        while attempts < self.max_retries:
            try:
                status = self.check_order_status(order_id)
                
                print(f"🔄 Attempt {attempts + 1}: Status - {status['status']}")
                
                if status["status"] == "active":
                    print("✅ AI logo generated successfully!")
                    if status.get("output"):
                        print(f"🎨 Logo output: {status['output']}")
                    return status
                
                elif status["status"] == "failed":
                    raise LightXLogoGeneratorException("AI logo generation failed")
                
                elif status["status"] == "init":
                    attempts += 1
                    if attempts < self.max_retries:
                        print(f"⏳ Waiting {self.retry_interval} seconds before next check...")
                        time.sleep(self.retry_interval)
                
                else:
                    attempts += 1
                    if attempts < self.max_retries:
                        time.sleep(self.retry_interval)
                
            except LightXLogoGeneratorException:
                raise
            except Exception as e:
                attempts += 1
                if attempts >= self.max_retries:
                    raise LightXLogoGeneratorException(f"Maximum retry attempts reached: {str(e)}")
                print(f"⚠️  Error on attempt {attempts}, retrying...")
                time.sleep(self.retry_interval)
        
        raise LightXLogoGeneratorException("Maximum retry attempts reached")
    
    def process_logo(self, text_prompt: str, enhance_prompt: bool = True) -> Dict:
        """
        Complete workflow: Generate AI logo and wait for completion
        
        Args:
            text_prompt: Text prompt for logo description
            enhance_prompt: Whether to enhance the prompt
            
        Returns:
            Dict: Final result with output URL
        """
        print("🚀 Starting LightX AI Logo Generator API workflow...")
        
        # Step 1: Generate logo
        print("🎨 Generating AI logo...")
        order_id = self.generate_logo(text_prompt, enhance_prompt)
        
        # Step 2: Wait for completion
        print("⏳ Waiting for processing to complete...")
        result = self.wait_for_completion(order_id)
        
        return result
    
    def get_logo_prompt_examples(self) -> Dict:
        """
        Get logo prompt examples
        
        Returns:
            Dict: Object containing prompt examples
        """
        prompt_examples = {
            "gaming": [
                "Minimal monoline fox logo, vector, flat",
                "Gaming channel logo with controller and neon effects",
                "Retro gaming logo with pixel art style",
                "Esports team logo with bold typography",
                "Gaming studio logo with futuristic design"
            ],
            "business": [
                "Professional tech company logo, modern, clean",
                "Corporate law firm logo with elegant typography",
                "Financial services logo with trust and stability",
                "Consulting firm logo with professional appearance",
                "Real estate company logo with building elements"
            ],
            "food": [
                "Restaurant logo with chef hat and elegant typography",
                "Coffee shop logo with coffee bean and warm colors",
                "Bakery logo with bread and vintage style",
                "Pizza place logo with pizza slice and fun design",
                "Food truck logo with bold, appetizing colors"
            ],
            "fashion": [
                "Fashion brand logo with elegant, minimalist design",
                "Luxury clothing logo with sophisticated typography",
                "Streetwear brand logo with urban, edgy style",
                "Jewelry brand logo with elegant, refined design",
                "Beauty brand logo with modern, clean aesthetics"
            ],
            "tech": [
                "Tech startup logo with modern, innovative design",
                "Software company logo with code and digital elements",
                "AI company logo with futuristic, smart design",
                "Mobile app logo with clean, user-friendly style",
                "Cybersecurity logo with shield and protection theme"
            ],
            "creative": [
                "Design agency logo with creative, artistic elements",
                "Photography studio logo with camera and lens",
                "Art gallery logo with brush strokes and colors",
                "Music label logo with sound waves and rhythm",
                "Film production logo with cinematic elements"
            ]
        }
        
        print("💡 Logo Prompt Examples:")
        for category, example_list in prompt_examples.items():
            print(f"{category}: {example_list}")
        
        return prompt_examples
    
    def get_logo_design_tips(self) -> Dict:
        """
        Get logo design tips and best practices
        
        Returns:
            Dict: Object containing tips for better results
        """
        tips = {
            "text_prompts": [
                "Be specific about the industry or business type",
                "Include style preferences (minimal, modern, vintage, etc.)",
                "Mention color schemes or mood you want to achieve",
                "Specify any symbols or elements you want included",
                "Include target audience or brand personality"
            ],
            "logo_types": [
                "Wordmark: Focus on typography and text styling",
                "Symbol: Emphasize icon or graphic elements",
                "Combination: Include both text and symbol elements",
                "Emblem: Create a badge or seal-style design",
                "Abstract: Use geometric shapes and modern forms"
            ],
            "industry_specific": [
                "Tech: Use modern, clean, and innovative elements",
                "Healthcare: Focus on trust, care, and professionalism",
                "Finance: Emphasize stability, security, and reliability",
                "Food: Use appetizing colors and food-related elements",
                "Creative: Show artistic flair and unique personality"
            ],
            "general": [
                "AI logo generation works best with clear, descriptive prompts",
                "Results are delivered in 1024x1024 JPEG format",
                "Allow 15-30 seconds for processing",
                "Enhanced prompts provide richer, more detailed results",
                "Experiment with different styles for various applications"
            ]
        }
        
        print("💡 Logo Design Tips:")
        for category, tip_list in tips.items():
            print(f"{category}: {tip_list}")
        
        return tips
    
    def get_logo_use_cases(self) -> Dict:
        """
        Get logo use cases and examples
        
        Returns:
            Dict: Object containing use case examples
        """
        use_cases = {
            "business": [
                "Create company logos for startups and enterprises",
                "Design brand identities for new businesses",
                "Generate logos for product launches",
                "Create professional business cards and letterheads",
                "Design trade show and marketing materials"
            ],
            "personal": [
                "Create personal branding logos",
                "Design logos for freelance services",
                "Generate logos for personal projects",
                "Create logos for social media profiles",
                "Design logos for personal websites"
            ],
            "creative": [
                "Generate logos for creative agencies",
                "Design logos for artists and designers",
                "Create logos for events and festivals",
                "Generate logos for publications and blogs",
                "Design logos for creative projects"
            ],
            "gaming": [
                "Create gaming channel logos",
                "Design esports team logos",
                "Generate gaming studio logos",
                "Create tournament and event logos",
                "Design gaming merchandise logos"
            ],
            "education": [
                "Create logos for educational institutions",
                "Design logos for online courses",
                "Generate logos for training programs",
                "Create logos for educational events",
                "Design logos for student organizations"
            ]
        }
        
        print("💡 Logo Use Cases:")
        for category, use_case_list in use_cases.items():
            print(f"{category}: {use_case_list}")
        
        return use_cases
    
    def get_logo_style_suggestions(self) -> Dict:
        """
        Get logo style suggestions
        
        Returns:
            Dict: Object containing style suggestions
        """
        style_suggestions = {
            "minimal": [
                "Clean, simple designs with minimal elements",
                "Focus on typography and negative space",
                "Use limited color palettes",
                "Emphasize clarity and readability",
                "Perfect for modern, professional brands"
            ],
            "vintage": [
                "Classic, timeless designs with retro elements",
                "Use traditional typography and classic symbols",
                "Incorporate aged or weathered effects",
                "Focus on heritage and tradition",
                "Great for established, traditional businesses"
            ],
            "modern": [
                "Contemporary designs with current trends",
                "Use bold typography and geometric shapes",
                "Incorporate technology and innovation",
                "Focus on forward-thinking and progress",
                "Perfect for tech and startup companies"
            ],
            "playful": [
                "Fun, energetic designs with personality",
                "Use bright colors and creative elements",
                "Incorporate humor and whimsy",
                "Focus on approachability and friendliness",
                "Great for entertainment and creative brands"
            ],
            "elegant": [
                "Sophisticated designs with refined aesthetics",
                "Use premium typography and luxury elements",
                "Incorporate subtle details and craftsmanship",
                "Focus on quality and exclusivity",
                "Perfect for luxury and high-end brands"
            ]
        }
        
        print("💡 Logo Style Suggestions:")
        for style, suggestion_list in style_suggestions.items():
            print(f"{style}: {suggestion_list}")
        
        return style_suggestions
    
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
    
    def generate_logo_with_validation(self, text_prompt: str, enhance_prompt: bool = True) -> Dict:
        """
        Generate logo with validation
        
        Args:
            text_prompt: Text prompt for logo description
            enhance_prompt: Whether to enhance the prompt
            
        Returns:
            Dict: Final result with output URL
        """
        if not self.validate_text_prompt(text_prompt):
            raise LightXLogoGeneratorException("Invalid text prompt")
        
        return self.process_logo(text_prompt, enhance_prompt)


class LightXLogoGeneratorException(Exception):
    """Custom exception for LightX Logo Generator API errors"""
    pass


# Example usage
def run_example():
    try:
        # Initialize with your API key
        lightx = LightXAILogoGeneratorAPI("YOUR_API_KEY_HERE")
        
        # Get tips and examples
        lightx.get_logo_prompt_examples()
        lightx.get_logo_design_tips()
        lightx.get_logo_use_cases()
        lightx.get_logo_style_suggestions()
        
        # Example 1: Gaming logo
        result1 = lightx.generate_logo_with_validation(
            "Minimal monoline fox logo, vector, flat",
            True
        )
        print("🎉 Gaming logo result:")
        print(f"Order ID: {result1['orderId']}")
        print(f"Status: {result1['status']}")
        if result1.get("output"):
            print(f"Output: {result1['output']}")
        
        # Example 2: Business logo
        result2 = lightx.generate_logo_with_validation(
            "Professional tech company logo, modern, clean",
            True
        )
        print("🎉 Business logo result:")
        print(f"Order ID: {result2['orderId']}")
        print(f"Status: {result2['status']}")
        if result2.get("output"):
            print(f"Output: {result2['output']}")
        
        # Example 3: Try different logo types
        logo_types = [
            "Restaurant logo with chef hat and elegant typography",
            "Fashion brand logo with elegant, minimalist design",
            "Tech startup logo with modern, innovative design",
            "Coffee shop logo with coffee bean and warm colors",
            "Design agency logo with creative, artistic elements"
        ]
        
        for logo_prompt in logo_types:
            result = lightx.generate_logo_with_validation(
                logo_prompt,
                True
            )
            print(f"🎉 {logo_prompt} result:")
            print(f"Order ID: {result['orderId']}")
            print(f"Status: {result['status']}")
            if result.get("output"):
                print(f"Output: {result['output']}")
    
    except LightXLogoGeneratorException as e:
        print(f"❌ Example failed: {e}")


if __name__ == "__main__":
    run_example()
