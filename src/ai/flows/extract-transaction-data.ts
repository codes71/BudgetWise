import * as z from 'zod/v4';
import { generateStructured } from '@/ai/claude';

const ExtractTransactionInputSchema = z.object({
  image: z.string().describe('Base64 data URL of the UPI screenshot'),
});

const ExtractTransactionOutputSchema = z.object({
  amount: z.number().optional(),
  date: z.string().optional(),
  merchant: z.string().optional(),
  transactionId: z.string().optional(),
  description: z.string().optional(),
  categoryHint: z.string().optional(),
});

export async function extractTransactionData(
  input: z.infer<typeof ExtractTransactionInputSchema>
): Promise<z.infer<typeof ExtractTransactionOutputSchema>> {
  // The client sends a FileReader data URL: data:image/png;base64,....
  const match = /^data:(image\/(?:jpeg|png|gif|webp));base64,(.+)$/.exec(input.image);
  if (!match) {
    throw new Error('Unsupported image. Please upload a JPEG, PNG, GIF or WebP screenshot.');
  }
  const [, mediaType, data] = match;

  const promptText = `
      Analyze the following UPI transaction screenshot. Extract the following information:
      - Amount: The transaction amount.
      - Date: The date of the transaction, formatted as YYYY-MM-DD.
      - Merchant: The name of the merchant or recipient.
      - Transaction ID: Any unique transaction reference number.
      - Description: A brief description of the transaction.
      - Category Hint: A suggestion for the transaction category (e.g., Food, Travel, Utilities, Shopping).

      If a piece of information is not found, omit it from the output. If the photo does not contain any transaction data, return an empty object.
    `;

  return generateStructured({
    schema: ExtractTransactionOutputSchema,
    effort: 'low',
    content: [
      {
        type: 'image',
        source: { type: 'base64', media_type: mediaType as 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp', data },
      },
      { type: 'text', text: promptText },
    ],
  });
}
