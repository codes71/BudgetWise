'use client';

import { initializeApp, getApps, getApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider, signInWithPopup, signOut } from 'firebase/auth';

const firebaseConfig = {
  "projectId": "budgetwise-5psqg",
  "appId": "1:493161832375:web:51ee63a889bb788235d232",
  "storageBucket": "budgetwise-5psqg.firebasestorage.app",
  "apiKey": "AIzaSyB_wvYiCGTPb6Ag-Pl24zwo64-OiGkixBE",
  "authDomain": "budgetwise-5psqg.firebaseapp.com",
  "measurementId": "",
  "messagingSenderId": "493161832375"
};

const app = !getApps().length ? initializeApp(firebaseConfig) : getApp();
const auth = getAuth(app);
const provider = new GoogleAuthProvider();

export { app, auth, provider, signInWithPopup, signOut };
