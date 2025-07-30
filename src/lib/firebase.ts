// This file can be used for other Firebase services like Storage or Firestore if needed.
// For now, it's minimal as Auth has been moved to a custom implementation.

import { initializeApp, getApp, getApps } from 'firebase/app';

export const firebaseConfig = {
  apiKey: "AIzaSyB_wvYiCGTPb6Ag-Pl24zwo64-OiGkixBE",
  authDomain: "budgetwise-5psqg.firebaseapp.com",
  projectId: "budgetwise-5psqg",
  storageBucket: "budgetwise-5psqg.firebasestorage.app",
  messagingSenderId: "493161832375",
  appId: "1:493161832375:web:51ee63a889bb788235d232"
};


const app = getApps().length ? getApp() : initializeApp(firebaseConfig);

// You can export other firebase services here if you add them
// e.g. import { getFirestore } from "firebase/firestore";
// export const db = getFirestore(app);
