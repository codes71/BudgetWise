import { genkit } from 'genkit';
import { gemini15Flash, googleAI } from '@genkit-ai/googleai';
import { z } from 'zod';

// First, create the Genkit instance.
const ai = genkit({
  plugins: [googleAI()],
});

const ExtractTransactionInputSchema = z.object({
  image: z.string().describe('Base64 encoded image of the UPI screenshot'),
});

const ExtractTransactionOutputSchema = z.object({
  amount: z.number().optional(),
  date: z.string().optional(),
  merchant: z.string().optional(),
  transactionId: z.string().optional(),
  description: z.string().optional(),
  categoryHint: z.string().optional(),
});

export const extractTransactionData = ai.defineFlow(
  {
    name: 'extractTransactionData',
    inputSchema: ExtractTransactionInputSchema,
    outputSchema: ExtractTransactionOutputSchema,
  },
  async (input) => {
    const promptText = `
      Analyze the following UPI transaction screenshot. Extract the following information:
      - Amount: The transaction amount.
      - Date: The date of the transaction.
      - Merchant: The name of the merchant or recipient.
      - Transaction ID: Any unique transaction reference number.
      - Description: A brief description of the transaction.
      - Category Hint: A suggestion for the transaction category (e.g., Food, Travel, Utilities, Shopping).

      If a piece of information is not found, omit it from the output.
    `;

    const modelResponse = await ai.generate({
      model: gemini15Flash,
      prompt: [
        { text: promptText },
        { media: { url: input.image, contentType: 'image/jpeg' } },
      ],
      config: {
        temperature: 0.1,
      },
      output: {
        schema: ExtractTransactionOutputSchema,
      },
    });

    const output = modelResponse.output;

    if (!output) {
      throw new Error('Failed to extract transaction data. Model response did not match schema.');
    }

    return output;
  }
);