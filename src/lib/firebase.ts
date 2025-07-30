'use client';

import { initializeApp, getApp, getApps } from 'firebase/app';
import { getAuth, GoogleAuthProvider, signInWithPopup, signOut, createUserWithEmailAndPassword, signInWithEmailAndPassword } from 'firebase/auth';

export const firebaseConfig = {

  apiKey: "AIzaSyB_wvYiCGTPb6Ag-Pl24zwo64-OiGkixBE",

  authDomain: "budgetwise-5psqg.firebaseapp.com",

  projectId: "budgetwise-5psqg",

  storageBucket: "budgetwise-5psqg.firebasestorage.app",

  messagingSenderId: "493161832375",

  appId: "1:493161832375:web:51ee63a889bb788235d232"

};



const app = getApps().length ? getApp() : initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const provider = new GoogleAuthProvider();

export { signInWithPopup, signOut, createUserWithEmailAndPassword, signInWithEmailAndPassword };
