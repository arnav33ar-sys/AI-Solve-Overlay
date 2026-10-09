require('dotenv').config();
const express = require('express');
const cors = require('cors');
const { GoogleGenerativeAI } = require('@google/generative-ai');

const app = express();
app.use(cors());
app.use(express.json({ limit: '10mb' }));

const port = process.env.PORT || 3000;
const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

app.get('/health', (req, res) => {
    res.status(200).json({ status: 'OK', message: 'Backend is running.' });
});

app.post('/api/solve', async (req, res) => {
    try {
        const { imageBase64 } = req.body;
        if (!imageBase64) {
            return res.status(400).json({ error: 'No image provided' });
        }

        const model = genAI.getGenerativeModel({ model: "gemini-1.5-flash" });
        const prompt = `Read the visible question from the selected screenshot. Recognize printed mathematics, science, multiple-choice questions, and ordinary text where legible. Explain the answer in simple, clear steps. Include the final answer prominently. State when the image is unclear rather than inventing missing information. Support Hindi and English questions.`;

        const image = {
            inlineData: {
                data: imageBase64,
                mimeType: "image/jpeg"
            }
        };

        const result = await model.generateContent([prompt, image]);
        const responseText = result.response.text();
        
        res.json({ answer: responseText });
    } catch (error) {
        console.error('Error calling Gemini:', error);
        res.status(500).json({ error: 'Failed to process image. Try again.' });
    }
});

app.listen(port, () => {
    console.log(`Backend listening on port ${port}`);
});
