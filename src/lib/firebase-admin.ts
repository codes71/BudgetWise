import * as admin from 'firebase-admin';

// This file is no longer used for authentication but can be kept for other admin tasks
// like sending notifications or accessing other Firebase services from the backend.

if (!admin.apps.length) {
  try {
    // Ensure you have these variables in your .env file
    if (process.env.FIREBASE_PROJECT_ID) {
        admin.initializeApp({
          credential: admin.credential.cert({
            projectId: process.env.FIREBASE_PROJECT_ID,
            clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
            privateKey: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n'),
          }),
        });
    }
  } catch (error) {
    console.error('Firebase admin initialization error', error);
  }
}

export { admin };
