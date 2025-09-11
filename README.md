# AI Stack Integrator

A comprehensive collection of AI-powered image processing and generation tools with implementations in multiple programming languages.

## 📁 Project Structure

Each AI feature is organized in its own folder with 4 language implementations:

```
AIStack-Integrator/
├── 01-remove-background/          # Background removal
├── 02-cleanup-picture/            # Image cleanup and enhancement
├── 03-expand-photo/               # Photo expansion
├── 04-replace-item/               # Object replacement
├── 05-cartoon-character/          # Cartoon character generation
├── 06-caricature-generator/       # Caricature creation
├── 07-ai-avatar/                  # AI avatar generation
├── 08-product-photoshoot/         # Product photography
├── 09-background-generator/       # Background generation
├── 10-ai-portrait/                # Portrait generation
├── 11-ai-faceswap/                # Face swapping
├── 12-ai-outfit/                  # Virtual outfit try-on
├── 13-ai-image2image/             # Image-to-image transformation
├── 14-ai-sketch2image/            # Sketch to image conversion
├── 15-ai-hairstyle/               # Hairstyle generation
├── 16-ai-upscaler/                # Image upscaling
├── 17-ai-filter/                  # AI filters
├── 18-ai-haircolor/               # Hair color change
├── 19-ai-virtual-tryon/           # Virtual try-on
├── 20-ai-headshot/                # Professional headshots
└── 21-haircolor-rgb/              # RGB hair color manipulation
```

## 🚀 Available Implementations

Each folder contains implementations in 4 programming languages:

- **Node.js** (`.js` files) - JavaScript/TypeScript implementation
- **Python** (`.py` files) - Python implementation  
- **Kotlin** (`.kt` files) - Android/Kotlin implementation
- **Swift** (`.swift` files) - iOS/Swift implementation

## 📋 Prerequisites

### For Node.js Implementation:
```bash
npm install axios
# or
yarn add axios
```

### For Python Implementation:
```bash
pip install requests pillow
```

### For Kotlin Implementation:
- Android Studio
- Add dependencies in `build.gradle`:
```gradle
implementation 'com.squareup.okhttp3:okhttp:4.9.3'
implementation 'com.google.code.gson:gson:2.8.9'
```

### For Swift Implementation:
- Xcode
- Add dependencies in `Package.swift` or use CocoaPods:
```ruby
pod 'Alamofire'
pod 'SwiftyJSON'
```

## 🔑 API Key Setup

1. Get your API key from [LightX API](https://lightx.ai)
2. Replace `YOUR_API_KEY` in the code with your actual API key
3. For production, use environment variables:

### Node.js:
```javascript
const API_KEY = process.env.LIGHTX_API_KEY || 'YOUR_API_KEY';
```

### Python:
```python
import os
API_KEY = os.getenv('LIGHTX_API_KEY', 'YOUR_API_KEY')
```

### Kotlin:
```kotlin
val apiKey = BuildConfig.LIGHTX_API_KEY ?: "YOUR_API_KEY"
```

### Swift:
```swift
let apiKey = Bundle.main.object(forInfoDictionaryKey: "LIGHTX_API_KEY") as? String ?? "YOUR_API_KEY"
```

## 🎯 How to Use

### 1. Choose Your Feature
Navigate to the folder of the AI feature you want to use (e.g., `01-remove-background/`)

### 2. Select Your Language
Choose the implementation file for your preferred language:
- `lightx-[feature]-nodejs.js` for Node.js
- `lightx-[feature]-python.py` for Python
- `LightX[Feature].kt` for Kotlin
- `LightX[Feature].swift` for Swift

### 3. Configure the Code
- Add your API key
- Set the input image path
- Configure any additional parameters
- Set the output path

### 4. Run the Code

#### Node.js:
```bash
node lightx-[feature]-nodejs.js
```

#### Python:
```bash
python lightx-[feature]-python.py
```

#### Kotlin:
- Open in Android Studio
- Build and run on device/emulator

#### Swift:
- Open in Xcode
- Build and run on device/simulator

## 📝 Example Usage

### Background Removal (Node.js):
```javascript
const fs = require('fs');
const axios = require('axios');

const API_KEY = 'YOUR_API_KEY';
const inputImage = 'path/to/input.jpg';
const outputPath = 'path/to/output.png';

// Read image file
const imageBuffer = fs.readFileSync(inputImage);

// Make API request
const response = await axios.post('https://api.lightx.ai/v1/remove-background', {
    image: imageBuffer.toString('base64')
}, {
    headers: {
        'Authorization': `Bearer ${API_KEY}`,
        'Content-Type': 'application/json'
    }
});

// Save result
fs.writeFileSync(outputPath, Buffer.from(response.data.result, 'base64'));
```

## 🔧 Common Parameters

Most implementations support these common parameters:

- `image`: Input image (base64 encoded or file path)
- `output_format`: Output format (png, jpg, webp)
- `quality`: Image quality (1-100)
- `resolution`: Output resolution
- `style`: Processing style (varies by feature)

## 📊 API Response Format

```json
{
    "success": true,
    "result": "base64_encoded_image",
    "processing_time": 2.5,
    "credits_used": 1
}
```

## 🛠️ Troubleshooting

### Common Issues:

1. **API Key Error**: Make sure your API key is valid and has sufficient credits
2. **Image Format**: Supported formats are JPG, PNG, WEBP
3. **File Size**: Maximum file size is usually 10MB
4. **Rate Limits**: Check your API rate limits and usage

### Error Codes:
- `401`: Invalid API key
- `402`: Insufficient credits
- `413`: File too large
- `415`: Unsupported file format
- `429`: Rate limit exceeded

## 📞 Support

- **Documentation**: [LightX API Docs]()
- **Support**: [LightX Support]()
- **Community**: [LightX Community]()

## 📄 License

This project is licensed under the MIT License. See the LICENSE file for details.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## 📈 Roadmap

- [ ] Add more AI features
- [ ] Implement batch processing
- [ ] Add web interface
- [ ] Create Docker containers
- [ ] Add unit tests
- [ ] Implement caching

---

**Note**: This is a collection of implementation examples. Make sure to follow LightX API terms of service and usage policies.
