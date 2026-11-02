// This file can be used for other Firebase services like Storage or Firestore if needed.
// For now, it's minimal as Auth has been moved to a custom implementation.

import { initializeApp, getApp, getApps } from 'firebase/app';

export const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};


const app = getApps().length ? getApp() : initializeApp(firebaseConfig);

// You can export other firebase services here if you add them
// e.g. import { getFirestore } from "firebase/firestore";
// export const db = getFirestore(app);
