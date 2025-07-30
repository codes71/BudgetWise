'use client';

import { initializeApp, getApp, getApps } from 'firebase/app';
import { getAuth, GoogleAuthProvider, signInWithPopup, signOut } from 'firebase/auth';

// export const firebaseConfig = {
//   "projectId": "budgetwise-5psqg",
//   "appId": "1:493161832375:web:51ee63a889bb788235d232",
//   "storageBucket": "budgetwise-5psqg.firebasestorage.app",
//   "apiKey": "AIzaSyB_wvYiCGTPb6Ag-Pl24zwo64-OiGkixBE",
//   "authDomain": "budgetwise-5psqg.firebaseapp.com",
//   "measurementId": "",
//   "messagingSenderId": "493161832375"
// };
export const firebaseConfig = {

  apiKey: "AIzaSyB_wvYiCGTPb6Ag-Pl24zwo64-OiGkixBE",

  authDomain: "budgetwise-5psqg.firebaseapp.com",

  projectId: "budgetwise-5psqg",

  storageBucket: "budgetwise-5psqg.firebasestorage.app",

  messagingSenderId: "493161832375",

  appId: "1:493161832375:web:51ee63a889bb788235d232"

};



function createFirebaseApp() {
    if (getApps().length > 0) {
        return getApp();
    }
    return initializeApp(firebaseConfig);
}

export const firebaseApp = createFirebaseApp();
export const auth = getAuth(firebaseApp);
export const provider = new GoogleAuthProvider();

export { signInWithPopup, signOut };