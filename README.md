# Firebase Studio

This is a Next.js starter project for Firebase Studio.

## Getting Started

To get started, take a look at `src/app/page.tsx`.

## Running Locally

To run this application on your local machine, follow these steps:

### 1. Install Dependencies

First, install the necessary npm packages:

```bash
npm install
```

### 2. Set Up Environment Variables

Create a file named `.env` in the root of your project and add the following environment variables. You can get the Firebase credentials from your Firebase project's service account settings and the MongoDB URI from your database provider.

```
MONGODB_URI="your_mongodb_connection_string"
FIREBASE_PROJECT_ID="your-firebase-project-id"
FIREBASE_CLIENT_EMAIL="firebase-adminsdk-xxxxx@your-project.iam.gserviceaccount.com"
FIREBEASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\nyour-private-key\n-----END PRIVATE KEY-----\n"
```

**Important**: Make sure to replace the placeholder values with your actual credentials.

### 3. Run the Development Servers

This application uses Genkit to power its AI features, which runs as a separate process. You need to run both the Next.js frontend and the Genkit development server.

Open two separate terminal windows:

**In the first terminal, run the Next.js app:**

```bash
npm run dev
```

Your application will be available at [http://localhost:9002](http://localhost:9002).

**In the second terminal, run the Genkit server:**

```bash
npm run genkit:dev
```

This will start the Genkit flows, allowing the AI features of the app to function correctly.

You're all set! You can now start developing and testing your application locally.
